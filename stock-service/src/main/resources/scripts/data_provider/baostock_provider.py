#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
baostock_provider.py
====================
Baostock 数据提供者实现。

从 update_stock_daily_baostock.py 和 refresh_qfq_history.py 提取核心逻辑，
封装为 DataProvider 接口实现。

特点:
    - 原生提供 16 个字段（含 preclose, pctChg, peTTM, pbMRQ, isST, tradestatus）
    - 支持全历史回刷（无日期上限）
    - 支持历史每日 PE/PB 序列
    - 不支持多进程并行（全局单 socket）
    - 有 IP 限流风险（高频请求可能被黑名单）
"""

import sys
import os
import time
import threading
from datetime import date, timedelta
from contextlib import contextmanager
from typing import List, Dict, Optional, Tuple

import pandas as pd

from .base import DataProvider
from db_helper import to_float, to_int


@contextmanager
def _suppress_stdout():
    """临时屏蔽 stdout（屏蔽 baostock login/logout 输出）"""
    original_stdout = sys.stdout
    sys.stdout = open(os.devnull, 'w')
    try:
        yield
    finally:
        sys.stdout.close()
        sys.stdout = original_stdout


def _get_baostock_code(code, market):
    """转换为 Baostock 代码格式: SH -> sh.600000"""
    if market == "SH":
        return f"sh.{code}"
    elif market == "SZ":
        return f"sz.{code}"
    return None


class BaostockDataProvider(DataProvider):
    """Baostock 数据提供者"""

    BS_FIELDS = ("date,code,open,high,low,close,preclose,volume,amount,"
                 "adjustflag,turn,tradestatus,pctChg,isST,peTTM,pbMRQ")

    def __init__(self):
        self._logged_in = False
        self._lock = threading.Lock()

    def login(self) -> bool:
        import baostock as bs
        for attempt in range(3):
            try:
                with _suppress_stdout():
                    lg = bs.login()
                if lg.error_code == '0':
                    self._logged_in = True
                    return True
                # 黑名单用户不重试
                if "黑名单" in lg.error_msg:
                    return False
            except Exception:
                pass
            time.sleep(2 * (attempt + 1))
        return False

    def logout(self):
        if not self._logged_in:
            return
        import baostock as bs
        try:
            with _suppress_stdout():
                bs.logout()
        except Exception:
            pass
        self._logged_in = False

    def relogin(self) -> bool:
        """重新登录（连接断开时调用）"""
        self.logout()
        time.sleep(1)
        return self.login()

    def query_history(self, code: str, market: str,
                      start_date: date, end_date: date,
                      adjustflag: str = "2",
                      timeout: int = 30,
                      chunk_years: int = 0) -> Optional[pd.DataFrame]:
        """
        查询历史日线（前复权）。

        参数:
            timeout: 单次请求超时秒数
            chunk_years: >0 时启用分块拉取（长历史防超时），0=不分块
        """
        import baostock as bs

        bs_code = _get_baostock_code(code, market)
        if not bs_code:
            return None

        if chunk_years > 0:
            return self._query_chunked(code, market, start_date, end_date,
                                       adjustflag, timeout, chunk_years)
        return self._query_single(code, market, start_date, end_date,
                                  adjustflag, timeout)

    def _query_single(self, code, market, start_date, end_date,
                      adjustflag, timeout):
        """单次查询（不分块）"""
        import baostock as bs

        bs_code = _get_baostock_code(code, market)
        start_str = start_date.strftime("%Y-%m-%d")
        end_str = end_date.strftime("%Y-%m-%d")

        def _do_query():
            rs = bs.query_history_k_data_plus(
                bs_code, self.BS_FIELDS,
                start_date=start_str, end_date=end_str,
                frequency="d", adjustflag=adjustflag,
            )
            data_list = []
            while (rs.error_code == '0') and rs.next():
                data_list.append(rs.get_row_data())
            return data_list, rs.fields

        for attempt in range(2):
            try:
                result_holder = [None]
                error_holder = [None]

                def _worker():
                    try:
                        result_holder[0] = _do_query()
                    except Exception as e:
                        error_holder[0] = e

                t = threading.Thread(target=_worker, daemon=True)
                t.start()
                t.join(timeout=timeout)

                if t.is_alive():
                    if attempt < 1:
                        self.relogin()
                        continue
                    return None

                if error_holder[0]:
                    raise error_holder[0]

                data_list, fields = result_holder[0]
                if not data_list:
                    return None

                df = pd.DataFrame(data_list, columns=fields)
                df['date'] = pd.to_datetime(df['date']).dt.date
                df = df[df['tradestatus'] == '1']
                return df if len(df) > 0 else None

            except Exception as e:
                err_msg = str(e)
                if any(kw in err_msg for kw in ["codec can't decode", "decompressing data",
                                                 "NoneType", "has no attribute"]):
                    return None
                if attempt < 1 and any(code in err_msg for code in ['10054', '10060', '10053']):
                    time.sleep(3)
                    self.relogin()
                    continue
                return None
        return None

    def _query_chunked(self, code, market, start_date, end_date,
                       adjustflag, timeout, chunk_years):
        """分块查询（长历史，每块 chunk_years 年）"""
        chunk_days = 365 * chunk_years
        chunks = []
        current = start_date
        while current < end_date:
            chunk_end = min(current + timedelta(days=chunk_days), end_date)
            chunks.append((current, chunk_end))
            current = chunk_end + timedelta(days=1)

        if len(chunks) <= 1:
            return self._query_single(code, market, start_date, end_date,
                                      adjustflag, timeout)

        all_dfs = []
        for i, (cs, ce) in enumerate(chunks):
            df = self._query_single(code, market, cs, ce, adjustflag, timeout)
            if df is not None and len(df) > 0:
                all_dfs.append(df)
            if i < len(chunks) - 1:
                time.sleep(0.1)

        if not all_dfs:
            return None
        return pd.concat(all_dfs, ignore_index=True)

    def build_daily_rows(self, db, code: str, name: str, market: str,
                         df: pd.DataFrame) -> List[Dict]:
        """将 Baostock DataFrame 转换为 upsert_daily row list"""
        if df is None or len(df) == 0:
            return []

        first_date = df.iloc[0]['date']
        prev_close = db.get_prev_close(code, first_date)

        rows = []
        for _, row in df.iterrows():
            close_price = to_float(row['close'])
            pct_chg = to_float(row['pctChg'])
            bs_preclose = to_float(row['preclose'])

            if bs_preclose is not None and bs_preclose > 0:
                pre_close_val = round(bs_preclose, 2)
            elif pct_chg is not None and close_price is not None and pct_chg != 0:
                pre_close_val = round(close_price / (1 + pct_chg / 100), 2)
            elif prev_close is not None:
                pre_close_val = prev_close
            elif close_price is not None:
                db_prev = db.get_prev_close(code, row['date'])
                pre_close_val = db_prev if db_prev is not None else None
            else:
                pre_close_val = None

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

    def fetch_snapshot_pe_pb(self, codes_markets: List[Tuple[str, str]]
                             ) -> Dict[str, Dict]:
        """Baostock 不需要快照接口——历史序列已含 PE/PB"""
        return {}

    @property
    def source_name(self) -> str:
        return "baostock"

    @property
    def supports_full_history(self) -> bool:
        return True

    @property
    def supports_historical_pe_pb(self) -> bool:
        return True

    @property
    def supports_parallel(self) -> bool:
        return False
