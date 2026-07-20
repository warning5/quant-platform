#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tencent_provider.py
===================
腾讯接口数据提供者实现。

使用腾讯 ifzq kline 获取 OHLCV + 前复权 + 换手率，
使用 qt.gtimg.cn 快照获取 PE/PB（当前值），
DB 反查 / 相邻行推导 preclose / pctChg / tradestatus。

特点:
    - 无需登录，无 IP 黑名单风险
    - 覆盖沪深 + 北交所全市场
    - 前复权数据有 640 交易日上限（~2.5年），不支持全历史回刷
    - PE/PB 仅有当前快照值，不支持历史每日序列
    - 支持多进程并行（无全局 socket 限制）
    - 科创板 688/689 volume 单位为"股"，其余为"手"需×100
"""

import time
import json
import re
import warnings
from datetime import date, datetime, timedelta
from typing import List, Dict, Optional, Tuple

import requests
import pandas as pd

from .base import DataProvider
from db_helper import to_float, to_int


# ─── 腾讯接口配置 ──────────────────────────────────────────────
_KLINE_URL = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get"
_SNAPSHOT_URL = "https://qt.gtimg.cn/q="

_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Referer": "https://gu.qq.com/",
    "Accept": "*/*",
}

# 市场 → 腾讯前缀映射
_QQ_MARKET_PREFIX = {"SH": "sh", "SZ": "sz", "BJ": "bj"}

# 腾讯 ifzq kline 最多返回 640 个交易日
MAX_KLINE_DAYS = 640


def _qq_parse_float(s):
    """解析腾讯快照字段，空值/无效值返回 None"""
    if not s or s in ("-", "0.00", "0", ""):
        return None
    try:
        v = float(s)
        return v if v != 0.0 else None
    except (ValueError, TypeError):
        return None


def _normalize_volume(code: str, raw_volume) -> Optional[int]:
    """
    科创板 688/689 腾讯返回"股"，其余返回"手"需×100。
    北交所也返回"股"。
    """
    vol = to_int(raw_volume)
    if vol is None:
        return None
    # 科创板 + 北交所：单位已经是"股"
    if code.startswith(("688", "689")) or code.startswith(("8", "4", "92", "93")):
        return vol
    # 沪深主板/创业板：单位是"手"，×100 转为"股"
    return vol * 100


class TencentDataProvider(DataProvider):
    """腾讯接口数据提供者"""

    def __init__(self):
        self._snapshot_cache: Dict[str, Dict] = {}
        self._cache_ts = 0.0

    def login(self) -> bool:
        """腾讯接口无需登录"""
        return True

    def logout(self):
        """腾讯接口无需登出"""
        pass

    def _get_qq_prefix(self, code: str, market: str) -> str:
        """获取腾讯股票代码前缀"""
        return _QQ_MARKET_PREFIX.get(market, "sz")

    def _fetch_kline(self, code: str, market: str,
                     start_date: date, end_date: date,
                     adjustflag: str = "2") -> Optional[List[list]]:
        """
        调用腾讯 ifzq kline 接口获取 K 线数据。

        注意：腾讯接口总是返回最近 640 个交易日，忽略 param 中的日期范围。
              需要在获取后按日期过滤。

        返回: [[date, open, close, high, low, volume, "", turnover, amount, ""], ...]
              或 None
        """
        prefix = self._get_qq_prefix(code, market)
        symbol = f"{prefix}{code}"
        start_str = start_date.strftime("%Y-%m-%d")
        end_str = end_date.strftime("%Y-%m-%d")

        # adjustflag: "2"=前复权→qfq, "1"=后复权→hfq, "3"=不复权→(空)
        if adjustflag == "2":
            fq_type = "qfq"
            var_name = "kline_dayqfq"
        elif adjustflag == "1":
            fq_type = "hfq"
            var_name = "kline_dayhfq"
        else:
            fq_type = ""
            var_name = "kline_day"

        params = {
            "_var": var_name,
            "param": f"{symbol},day,{start_str},{end_str},{MAX_KLINE_DAYS},{fq_type}",
            "r": "0.1",
        }

        try:
            r = requests.get(_KLINE_URL, params=params, headers=_HEADERS, timeout=15)
            r.raise_for_status()
            text = re.sub(rf'^{var_name}=', '', r.text.strip())
            d = json.loads(text)
            if d.get('code') != 0:
                return None
            stock_data = d.get('data', {}).get(symbol, {})
            # qfqday / hfqday / day 取第一个非空的
            rows = (stock_data.get(f'{fq_type}day')
                    or stock_data.get('day')
                    or stock_data.get('qfqday')
                    or stock_data.get('hfqday'))
            return rows if rows else None
        except Exception:
            return None

    def query_history(self, code: str, market: str,
                      start_date: date, end_date: date,
                      adjustflag: str = "2") -> Optional[pd.DataFrame]:
        """
        查询历史日线数据，返回标准化 DataFrame。

        腾讯接口限制:
            - 最多返回 640 个交易日（~2.5年）
            - 如果 start_date 超出 640 天范围，只返回可用的部分
            - PE/PB 仅有当前快照值，所有日期填同一个值
        """
        raw_rows = self._fetch_kline(code, market, start_date, end_date, adjustflag)
        if not raw_rows:
            return None

        # 转为 DataFrame（腾讯返回列数可能不同：通常10列，部分股票11列）
        # 已知列位置: 0=date, 1=open, 2=close, 3=high, 4=low,
        #             5=volume, 7=turn, 8=amount
        n_cols = len(raw_rows[0])
        col_names = ['date', 'open', 'close', 'high', 'low',
                     'volume', '_extra', 'turn', 'amount', '_extra2',
                     '_extra3', '_extra4']
        # 如果实际列数超过预定义列名，补齐；少于则截断
        if n_cols > len(col_names):
            col_names = col_names + [f'_extra{i}' for i in range(n_cols - len(col_names))]
        else:
            col_names = col_names[:n_cols]
        df = pd.DataFrame(raw_rows, columns=col_names)
        df['date'] = pd.to_datetime(df['date']).dt.date

        # 按日期过滤（腾讯总是返回最近640天，需要截取请求范围）
        mask = (df['date'] >= start_date) & (df['date'] <= end_date)
        df = df[mask].copy()

        if len(df) == 0:
            return None

        # ── 检查是否超出 640 天限制 ──
        oldest_available = df['date'].min()
        if oldest_available > start_date:
            warnings.warn(
                f"[Tencent] {code} 请求起始 {start_date} 超出腾讯640日上限，"
                f"实际最早 {oldest_available}（缺少 {start_date} ~ {oldest_available} 数据）"
            )

        # ── 构造标准化列 ──
        # preclose: 取相邻前一日 close
        df['preclose'] = df['close'].shift(1)

        # pctChg: (close - preclose) / preclose * 100
        df['pctChg'] = ((df['close'].astype(float) - df['preclose'].astype(float))
                        / df['preclose'].astype(float) * 100).round(2)

        # tradestatus: 有数据 = "1"
        df['tradestatus'] = '1'

        # isST: 腾讯 kline 无此字段，从快照名称推断（历史精度有限）
        df['isST'] = '0'

        # volume 单位归一化
        df['volume'] = df['volume'].apply(lambda v: _normalize_volume(code, v))

        # amount: 万元→元
        df['amount'] = df['amount'].apply(lambda x: to_float(x) * 10000 if to_float(x) else None)

        # PE/PB: 快照值填所有日期
        snapshot = self._get_snapshot(code, market)
        snap_pe = snapshot.get("pe_ttm") if snapshot else None
        snap_pb = snapshot.get("pb") if snapshot else None
        df['peTTM'] = str(snap_pe) if snap_pe else ""
        df['pbMRQ'] = str(snap_pb) if snap_pb else ""

        # 列顺序标准化（与 Baostock 一致）
        result_cols = ['date', 'open', 'high', 'low', 'close', 'preclose',
                       'volume', 'amount', 'turn', 'tradestatus',
                       'pctChg', 'isST', 'peTTM', 'pbMRQ']
        for col in result_cols:
            if col not in df.columns:
                df[col] = ""

        return df[result_cols].reset_index(drop=True)

    def build_daily_rows(self, db, code: str, name: str, market: str,
                         df: pd.DataFrame) -> List[Dict]:
        """将腾讯 DataFrame 转换为 upsert_daily row list"""
        if df is None or len(df) == 0:
            return []

        first_date = df.iloc[0]['date']
        # 尝试从 DB 获取 first row 的 preclose（腾讯首行 preclose 是 NaN）
        db_prev_close = db.get_prev_close(code, first_date)

        rows = []
        prev_close = db_prev_close

        for _, row in df.iterrows():
            close_price = to_float(row['close'])
            # preclose: 优先用腾讯相邻行推导，首行用 DB
            qq_preclose = to_float(row['preclose'])
            if qq_preclose is not None:
                pre_close_val = round(qq_preclose, 2)
            elif prev_close is not None:
                pre_close_val = prev_close
            else:
                db_prev = db.get_prev_close(code, row['date'])
                pre_close_val = db_prev if db_prev is not None else None

            # pctChg: 用腾讯计算的，或反算
            pct_chg = to_float(row['pctChg'])
            if pct_chg is None and pre_close_val and close_price:
                pct_chg = round((close_price - pre_close_val) / pre_close_val * 100, 2)
            change_percent = pct_chg

            if pre_close_val is not None and close_price is not None:
                change_amount = round(close_price - pre_close_val, 2)
            elif close_price is not None and change_percent is not None:
                change_amount = round(close_price * change_percent / 100, 2)
            else:
                change_amount = None

            rows.append({
                "code": code,
                "name": name,
                "trade_date": row['date'],
                "open_price": to_float(row['open']),
                "close_price": close_price,
                "high_price": to_float(row['high']),
                "low_price": to_float(row['low']),
                "pre_close": pre_close_val,
                "volume": to_int(row['volume']),
                "amount": to_float(row['amount']),
                "change_percent": change_percent,
                "change_amount": change_amount,
                "turnover_rate": to_float(row['turn']),
                "pe_ttm": to_float(row['peTTM']),
                "pb": to_float(row['pbMRQ']),
                "data_source": self.source_name,
            })
            prev_close = close_price

        return rows

    def _get_snapshot(self, code: str, market: str) -> Dict:
        """获取单只股票的 PE/PB 快照（带批量缓存）"""
        if code in self._snapshot_cache:
            return self._snapshot_cache[code]
        result = self.fetch_snapshot_pe_pb([(code, market)])
        return result.get(code, {})

    def fetch_snapshot_pe_pb(self, codes_markets: List[Tuple[str, str]],
                             batch_size: int = 100,
                             delay: float = 0.1) -> Dict[str, Dict]:
        """
        批量获取腾讯实时快照 PE_TTM / PB。

        使用 qt.gtimg.cn 接口，每批最多 100 只。
        """
        result = {}
        for i in range(0, len(codes_markets), batch_size):
            batch = codes_markets[i: i + batch_size]
            symbols = ",".join(f"{_QQ_MARKET_PREFIX.get(m, 'sz')}{c}"
                               for c, m in batch)
            try:
                url = f"{_SNAPSHOT_URL}{symbols}"
                r = requests.get(url, headers=_HEADERS, timeout=20)
                r.encoding = "gbk"
                for line in r.text.strip().split(";"):
                    if "~" not in line:
                        continue
                    parts = line.split("~")
                    if len(parts) < 54:
                        continue
                    stock_code = parts[2]
                    snap = {
                        "pe_ttm": _qq_parse_float(parts[53]),  # 动态PE(TTM)
                        "pb": _qq_parse_float(parts[46]),       # PB
                    }
                    result[stock_code] = snap
                    self._snapshot_cache[stock_code] = snap
            except Exception:
                pass
            if i + batch_size < len(codes_markets):
                time.sleep(delay)
        return result

    def prefetch_snapshots(self, codes_markets: List[Tuple[str, str]]):
        """预取批量快照（在遍历股票前调用，减少运行时请求）"""
        self._snapshot_cache = self.fetch_snapshot_pe_pb(codes_markets)
        self._cache_ts = time.time()
        return self._snapshot_cache

    @property
    def source_name(self) -> str:
        return "qq"

    @property
    def supports_full_history(self) -> bool:
        return False

    @property
    def supports_historical_pe_pb(self) -> bool:
        return False

    @property
    def supports_parallel(self) -> bool:
        return True
