#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
update_sentiment_data.py v3
========================
拉取市场情绪数据并双写 ClickHouse + MySQL：

  ClickHouse 表（同名 MySQL 表同步）:
    1. stock_sentiment_lhb          龙虎榜详情
    2. stock_sentiment_lhb_inst     龙虎榜机构统计
    3. stock_sentiment_margin       融资融券汇总（沪市）
    4. stock_sentiment_margin_detail 融资融券个股（沪市）
    5. stock_sentiment_survey      机构调研
    6. stock_sentiment_block_trade 大宗交易
    7. stock_sentiment_activity    市场活跃度
    8. stock_sentiment_zt         涨跌停池
    9. stock_sentiment_moneyflow  资金流向（东方财富全市场个股，v3升级）
   10. stock_sentiment_notice     公告

用法：
  python update_sentiment_data.py                     # 全部
  python update_sentiment_data.py --skip-lhb           # 跳过龙虎榜
  python update_sentiment_data.py --date 20260430     # 指定日期
  python update_sentiment_data.py --dry-run          # 不写库
  python update_sentiment_data.py --catchup 30        # 补最近30天数据
  python update_sentiment_data.py --moneyflow-hist    # 历史资金流向补全（逐只股票）
"""

import sys
import time
import datetime
import argparse
import traceback
import subprocess
import json
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

import pandas as pd
import clickhouse_connect
import pymysql
from db_helper import ch_dedup_filter
from db_config import CLICKHOUSE_CONFIG, MYSQL_CONFIG
import subprocess
import os as _os

# ─── NeoData Token 自动刷新 ───────────────────────────────────────

_REFRESH_SCRIPT = str(__file__).replace("\\", "/").rsplit("/", 1)[0] + "/refresh_neodata_token.py"

def _check_neodata_token(verbose=True):
    """检查 NeoData token 状态，快过期则警告，已过期则退出并提示刷新

    已过期时脚本以退出码 42 退出 → AI 检测到后会调用 connect_cloud_service 刷新
    """
    try:
        r = subprocess.run(
            [sys.executable, _REFRESH_SCRIPT],
            capture_output=True, timeout=15,
            cwd=str(__file__).replace("\\", "/").rsplit("/", 1)[0] or ".",
        )
    except Exception as e:
        if verbose:
            print(f"[NeoData token] 检查失败: {e}，跳过（NeoData 功能可能不可用）")
        return  # 不阻断，允许继续

    code = r.returncode
    if code == 0:
        # 正常，打印一行即可
        if verbose:
            out = r.stdout.decode("utf-8", errors="ignore").strip()
            if out:
                print(f"[NeoData token] {out}")
        return

    stderr = r.stderr.decode("utf-8", errors="ignore") if r.stderr else ""

    if code == 43:
        # 快过期警告，不阻断
        warn_line = [l for l in stderr.splitlines() if l.strip()]
        if warn_line:
            print(f"[NeoData token] ⚠️  {warn_line[0].strip()}", file=sys.stderr)
        return

    if code == 42:
        # 已过期 → 打印提示后退出（AI 检测到 exit 42 会自动刷新）
        expired_lines = [l for l in stderr.splitlines() if l.strip()]
        for line in expired_lines[-4:]:
            print(f"[NeoData token] {line.strip()}", file=sys.stderr)
        # AI 会检测到这个 exit code 并自动刷新，无需手动提示
        sys.exit(42)


# 脚本启动时自动检查 token（首次 NeoData 调用前）
_check_neodata_token(verbose=True)


# ─── 工具函数 ────────────────────────────────────────────────────

# ─── 工具函数 ────────────────────────────────────────────────────

def to_float(val, default=0.0):
    if val is None or str(val).strip() in ("", "-", "None", "nan"):
        return default
    try:
        f = float(val)
        return f if f == f else default   # NaN → default
    except (ValueError, TypeError):
        return default


def to_int(val, default=0):
    if val is None or str(val).strip() in ("", "-", "None"):
        return default
    try:
        return int(val)
    except (ValueError, TypeError):
        return default


def to_date(val):
    """将 '2026-04-30' / 'YYYYMMDD' / date 对象转为 date"""
    if isinstance(val, datetime.date):
        return val
    s = str(val).strip()
    if not s or s in ("None", "-"):
        return None
    if len(s) == 8 and s.isdigit():
        return datetime.date(int(s[:4]), int(s[4:6]), int(s[6:8]))
    if len(s) >= 10:
        return datetime.date.fromisoformat(s[:10])
    return None


# ─── NeoData 资金流向 ────────────────────────────────────────────

NEO_QUERY = r"C:\Users\warning5\.workbuddy\plugins\marketplaces\cb_teams_marketplace\plugins\finance-data\skills\neodata-financial-search\scripts\query.py"


def query_neodata(q: str) -> dict | None:
    """通过 NeoData query.py 查询，返回解析后的 JSON 或 None"""
    try:
        r = subprocess.run(
            [sys.executable, NEO_QUERY, "--query", q],
            capture_output=True, timeout=60,
        )
    except subprocess.TimeoutExpired:
        return None
    if r.returncode != 0:
        # 打印 stderr 方便排查
        if r.stderr:
            try:
                print(f"  [NeoData stderr] {r.stderr.decode('utf-8', errors='ignore')[:200]}")
            except Exception:
                pass
        return None
    try:
        out = r.stdout.decode("utf-8", errors="ignore") if r.stdout else ""
        return json.loads(out)
    except Exception:
        return None


def extract_neodata_moneyflow(data: dict) -> list:
    """从 NeoData 响应中提取资金流向 rows，格式同 _dual_write 的 stock_sentiment_moneyflow
    返回 [ts_code, trade_date, code, name, close, pct_change,
           net_main, net_main_pct, net_huge, net_big, net_medium, net_small, update_time]
    支持两种 NeoData 响应格式:
      - "历史资金流向": 表格格式，多日数据
      - "今日资金流向": 纯文本格式，单日数据
    NeoData 表格实际列顺序（14列，末尾空列已丢弃）:
      0=交易日期 1=中单净流入 2=主力流入(超大+大买盘) 3=主力流入占比(%)
      4=主力净流入 5=主力流出(超大+大卖盘) 6=主力流出占比(%)
      7=散户流入(中单+小单买盘) 8=小单净流入 9=散户净流出
      10=散户流出 11=超大单净流入 12=大单净流入
    """
    result = []
    # 防御性访问：data 可能为 None，data.get("data") 也可能为 None
    api_recall = (((data or {}).get("data") or {}).get("apiData") or {}).get("apiRecall") or []
    for recall in api_recall:
        recall_type = recall.get("type", "")
        content = recall.get("content", "")

        # ── 格式1: "历史资金流向"（表格格式，支持多日）──────────────────
        if recall_type == "历史资金流向":
            # 按 "##" 分割内容，每段是一个股票的数据块
            # 例: "## 名称：海立股份 代码：600619.SH\n\n| 日期 | ..."
            blocks = re.split(r"(?=## )", content)
            for block in blocks:
                block = block.strip()
                if not block:
                    continue
                # 从块中提取该股票代码
                code_m = re.search(r"代码[：:]\s*([0-9]{6}\.[A-Z]{2})", block)
                if not code_m:
                    continue
                ts_code = code_m.group(1)
                code = ts_code.split(".")[0]
                # 过滤 B 股（900xxx/200xxx）
                if code.startswith("900") or code.startswith("200"):
                    continue

                for line in block.split("\n"):
                    if not line.strip().startswith("|"):
                        continue
                    cols = [c.strip() for c in line.split("|")][1:]
                    if len(cols) < 13:
                        continue
                    date_str = cols[0]
                    if not re.match(r"^\d{8}$", date_str):
                        continue
                    try:
                        td = datetime.date(int(date_str[:4]), int(date_str[4:6]), int(date_str[6:8]))
                    except ValueError:
                        continue
                    try:
                        result.append([
                            ts_code,                              # ts_code
                            td,                                   # trade_date (datetime.date，CH需要)
                            code,                                 # code
                            "",                                   # name (NeoData 无此字段)
                            0.0,                                  # close (NeoData 无此字段)
                            0.0,                                  # pct_change (NeoData 无此字段)
                            to_float(cols[4]),                    # net_main 主力净流入
                            to_float(cols[3]),                    # net_main_pct 主力流入占比
                            to_float(cols[11]),                   # net_huge 超大单净流入
                            to_float(cols[12]),                   # net_big 大单净流入
                            to_float(cols[1]),                    # net_medium 中单净流入
                            to_float(cols[8]),                    # net_small 小单净流入
                            datetime.datetime.now(),               # update_time
                        ])
                    except (ValueError, IndexError):
                        continue

        # ── 格式2: "今日资金流向"（纯文本格式，单日数据）──────────────
        elif recall_type == "今日资金流向":
            # 匹配代码: "代码：000001.SZ"
            code_m = re.search(r"代码[：:]\s*([0-9]{6}\.[A-Z]{2})", content)
            if not code_m:
                continue
            ts_code = code_m.group(1)
            code = ts_code.split(".")[0]
            if code.startswith("900") or code.startswith("200"):
                continue

            # 从文本中提数（所有金额单位为"元"，提取后保持一致）
            def extract_yuan(pattern, text):
                m = re.search(pattern, text)
                return float(m.group(1).replace(",", "")) if m else 0.0

            net_main      = extract_yuan(r"主力净流入([-+]?\d[\d,]*\.?\d*)元", content)
            # 第一个"占比："即为"主力净流入"的占比（文字格式中顺序固定）
            net_main_pct  = extract_yuan(r"占比[：:]([0-9.]+)", content)
            # 文本格式中"超大单流入/大单流入"是"流入量"而非"净流入"（缺卖出数据）
            net_huge      = extract_yuan(r"超大单流入([-+]?\d[\d,]*\.?\d*)元", content)
            net_big       = extract_yuan(r"大单流入([-+]?\d[\d,]*\.?\d*)元", content)
            net_medium    = extract_yuan(r"中单流入([-+]?\d[\d,]*\.?\d*)元", content)
            net_small     = extract_yuan(r"小单流入([-+]?\d[\d,]*\.?\d*)元", content)

            # 文本格式的 net_huge/big 是"流入量"而非"净流入"，与表格语义不同
            # 只有 net_main 是严格净流入，方向可信；其他降级为近似值，标记时间以便识别
            result.append([
                ts_code,                  # ts_code
                datetime.date.today(),    # trade_date（今日）
                code,                     # code
                "",                       # name
                0.0,                     # close
                0.0,                     # pct_change
                net_main,                 # net_main（净流入，精确）
                net_main_pct,             # net_main_pct
                net_huge,                # net_huge（仅流入量，降级）
                net_big,                 # net_big（仅流入量，降级）
                net_medium,              # net_medium（仅流入量，降级）
                net_small,               # net_small（仅流入量，降级）
                datetime.datetime.now(),  # update_time
            ])

    return result


def fill_close_pct_from_stock_daily(rows, ch_client):
    """
    从 CH stock_daily 表回填 close_price → close, change_percent → pct_change
    rows: list of [ts_code, trade_date(date), code, name, close, pct_change, ...]
    """
    if not rows:
        return
    code = rows[0][2]
    min_date = min(r[1] for r in rows).isoformat()
    max_date = max(r[1] for r in rows).isoformat()
    try:
        ch_result = ch_client.query(
            f"SELECT trade_date, close_price, change_percent "
            f"FROM stock_daily "
            f"WHERE code = '{code}' AND trade_date >= '{min_date}' AND trade_date <= '{max_date}'"
        )
        lookup = {}
        for r in ch_result.result_set:
            lookup[r[0]] = (r[1], r[2])
        filled = 0
        for row in rows:
            td = row[1]
            if td in lookup:
                row[4] = lookup[td][0] if lookup[td][0] is not None else 0.0
                row[5] = lookup[td][1] if lookup[td][1] is not None else 0.0
                filled += 1
        if filled:
            print(f"  [CH lookup] filled {filled}/{len(rows)} rows")
    except Exception as e:
        print(f"  [CH lookup failed for {code}] {e}")


def _ch_batch_insert(table: str, rows: list, column_names: list, dry_run: bool = False) -> int:
    """写入 CH（不做预过滤，由 _dual_write 统一处理）"""

    if not rows:
        print(f"[CH] {table}: 无数据，跳过")
        return 0
    if dry_run:
        print(f"[DRY-RUN] {table}: CH 模拟写入 {len(rows)} 条")
        return len(rows)
    try:
        client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
        client.insert(table, rows, column_names=column_names)
        client.close()
        print(f"[CH] {table}: 写入 {len(rows)} 条")
        return len(rows)
    except Exception as e:
        print(f"[ERROR] {table} CH 写入失败: {e}")
        traceback.print_exc()
        return 0


# ─── MySQL 写入 ──────────────────────────────────────────────────

def _mysql_batch_upsert(table: str, rows: list, column_names: list,
                           unique_cols: list, dry_run: bool = False) -> int:
    if not rows:
        return 0
    if dry_run:
        print(f"[DRY-RUN] {table}: MySQL 模拟写入 {len(rows)} 条")
        return len(rows)
    try:
        conn = pymysql.connect(**MYSQL_CONFIG)
        cur = conn.cursor()
        placeholders = ", ".join(["%s"] * len(column_names))
        sql = f"REPLACE INTO {table} ({', '.join(column_names)}) VALUES ({placeholders})"
        cur.executemany(sql, rows)
        conn.commit()
        affected = cur.rowcount
        cur.close()
        conn.close()
        print(f"[MySQL] {table}: 写入 {len(rows)} 条（affected={affected}）")
        return affected
    except Exception as e:
        print(f"[ERROR] {table} MySQL 写入失败: {e}")
        traceback.print_exc()
        return 0


# ─── 双写入口 ──────────────────────────────────────────────────

def _dual_write(table, rows, ch_columns, mysql_columns, mysql_unique, dry_run=False, force=False):
    if not rows:
        print(f"[SKIP] {table}: 无数据")
        return
    # CH 预过滤：查 FINAL 表跳过已存在行，CH 和 MySQL 共用过滤结果
    # force=True 时跳过过滤（用于全量重刷，允许覆盖已有数据）
    if not dry_run and mysql_unique and not force:
        rows = ch_dedup_filter(table, rows, ch_columns, mysql_unique)
    if not rows:
        print(f"  ≡ {table}: CH/MySQL 均已存在，跳过")
        return
    ch_ok  = _ch_batch_insert(table, rows, ch_columns, dry_run)
    my_ok = _mysql_batch_upsert(table, rows, mysql_columns, mysql_unique, dry_run)
    if ch_ok and my_ok:
        print(f"  ✓ {table}: CH({ch_ok}) + MySQL({my_ok}) 写入完成")
    elif ch_ok:
        print(f"  ⚠ {table}: CH 成功，MySQL 失败")
    elif my_ok:
        print(f"  ⚠ {table}: MySQL 成功，CH 失败")


# ═══════════════════════════════════════════════════════════════
# 1. 龙虎榜详情  stock_lhb_detail_em
# ║  返回字段: 序号/代码/名称/上榜日/解读/收盘价/涨跌幅/
# ║            龙虎榜净买额/买入额/卖出额/成交额/市场总成交额/
# ║            净买额占总成交比/成交额占总成交比/换手率/流通市值/
# ║            上榜原因/上榜后1日/上榜后2日/上榜后5日/上榜后10日
# ╙══════════════════════════════════════════════════════════════

def fetch_lhb_detail(start_date: str, end_date: str) -> list:
    """龙虎榜详情，返回 rows: [code,name,trade_date,close,pct_change,
                                   net_amount,buy_amount,sell_amount,total_amount,
                                   market_amount,net_ratio,amount_ratio,turnover,
                                   float_mv,reason,after_1d,after_2d,after_5d,after_10d,update_time]"""
    import akshare as ak
    rows = []
    try:
        df = ak.stock_lhb_detail_em(start_date=start_date, end_date=end_date)
        if df is None or df.empty:
            return rows
        for _, r in df.iterrows():
            code = str(r.get("代码", "")).strip().zfill(6)
            if not code or code == "000000":
                continue
            td = to_date(r.get("上榜日"))
            if td is None:
                continue
            rows.append([
                code,
                str(r.get("名称", ""))[:50],
                td,
                to_float(r.get("收盘价")),
                to_float(r.get("涨跌幅")),
                to_float(r.get("龙虎榜净买额")),
                to_float(r.get("龙虎榜买入额")),
                to_float(r.get("龙虎榜卖出额")),
                to_float(r.get("龙虎榜成交额")),
                to_float(r.get("市场总成交额")),
                to_float(r.get("净买额占总成交比")),
                to_float(r.get("成交额占总成交比")),
                to_float(r.get("换手率")),
                to_float(r.get("流通市值")),
                str(r.get("上榜原因", ""))[:200],
                to_float(r.get("上榜后1日"), None),
                to_float(r.get("上榜后2日"), None),
                to_float(r.get("上榜后5日"), None),
                to_float(r.get("上榜后10日"), None),
                datetime.datetime.now(),
            ])
        print(f"  龙虎榜详情: {len(rows)} 条")
    except Exception as e:
        print(f"  龙虎榜详情获取失败: {e}")
    return rows


# ═══════════════════════════════════════════════════════════════
# 2. 龙虎榜机构统计  stock_lhb_jgmmtj_em
# ║  返回字段: 序号/代码/名称/收盘价/涨跌幅/买方机构数/卖方机构数/
# ║            机构买入总额/机构卖出总额/机构买入净额/市场总成交额/
# ║            机构净买额占总成交额比/换手率/流通市值/上榜原因/上榜日期
# ╙══════════════════════════════════════════════════════════════

def fetch_lhb_inst(start_date: str, end_date: str) -> list:
    import akshare as ak
    rows = []
    try:
        df = ak.stock_lhb_jgmmtj_em(start_date=start_date, end_date=end_date)
        if df is None or df.empty:
            return rows
        for _, r in df.iterrows():
            code = str(r.get("代码", "")).strip().zfill(6)
            if not code or code == "000000":
                continue
            td = to_date(r.get("上榜日期"))
            if td is None:
                continue
            rows.append([
                code,
                str(r.get("名称", ""))[:50],
                td,
                to_float(r.get("收盘价")),
                to_float(r.get("涨跌幅")),
                to_int(r.get("买方机构数")),
                to_int(r.get("卖方机构数")),
                to_float(r.get("机构买入总额")),
                to_float(r.get("机构卖出总额")),
                to_float(r.get("机构买入净额")),
                to_float(r.get("市场总成交额")),
                to_float(r.get("机构净买额占总成交额比")),
                to_float(r.get("换手率")),
                to_float(r.get("流通市值")),
                str(r.get("上榜原因", ""))[:200],
                datetime.datetime.now(),
            ])
        print(f"  龙虎榜机构: {len(rows)} 条")
    except Exception as e:
        print(f"  龙虎榜机构获取失败: {e}")
    return rows


# ═══════════════════════════════════════════════════════════════
# 3. 融资融券汇总（沪市） stock_margin_sse
# ║  返回字段: 信用交易日期/融资余额/融资买入额/融券余量/
# ║            融券余量金额/融券卖出量/融资融券余额
# ╙══════════════════════════════════════════════════════════════

def fetch_margin(start_date: str, end_date: str) -> list:
    import akshare as ak
    rows = []
    try:
        df = ak.stock_margin_sse(start_date=start_date, end_date=end_date)
        if df is None or df.empty:
            return rows
        for _, r in df.iterrows():
            td = to_date(r.get("信用交易日期"))
            if td is None:
                continue
            rows.append([
                td,
                to_float(r.get("融资余额")),
                to_float(r.get("融资买入额")),
                to_float(r.get("融券余量")),
                to_float(r.get("融券余量金额")),
                to_float(r.get("融券卖出量")),
                to_float(r.get("融资融券余额")),
                datetime.datetime.now(),
            ])
        print(f"  融资融券汇总: {len(rows)} 条")
    except Exception as e:
        print(f"  融资融券汇总获取失败: {e}")
    return rows


# ═══════════════════════════════════════════════════════════════
# 4. 融资融券个股（沪市） stock_margin_detail_sse
# ║  返回字段: 信用交易日期/标的证券代码/标的证券简称/
# ║            融资余额/融资买入额/融资偿还额/融券余量/融券卖出量/融券偿还量
# ╙══════════════════════════════════════════════════════════════

def fetch_margin_detail(trade_date: str) -> list:
    import akshare as ak
    rows = []
    try:
        df = ak.stock_margin_detail_sse(date=trade_date)
        if df is None or df.empty:
            print("  融资融券个股: 无数据（可能非交易日或接口无数据）")
            return rows
        for _, r in df.iterrows():
            code = str(r.get("标的证券代码", "")).strip().zfill(6)
            if not code:
                continue
            td = to_date(r.get("信用交易日期"))
            if td is None:
                continue
            rows.append([
                td,
                code,
                str(r.get("标的证券简称", ""))[:50],
                to_float(r.get("融资余额")),
                to_float(r.get("融资买入额")),
                to_float(r.get("融资偿还额")),
                to_float(r.get("融券余量")),
                to_float(r.get("融券卖出量")),
                to_float(r.get("融券偿还量")),
                datetime.datetime.now(),
            ])
        print(f"  融资融券个股: {len(rows)} 条")
    except Exception as e:
        print(f"  融资融券个股获取失败: {e}（该日期可能非交易日或接口暂无数据）")
    return rows


# ═══════════════════════════════════════════════════════════════
# 5. 机构调研  stock_jgdy_tj_em
# ║  返回字段: 序号/代码/名称/最新价/涨跌幅/接待机构数量/
# ║            接待方式/接待人员/接待地点/接待日期/公告日期
# ╙══════════════════════════════════════════════════════════════

def fetch_survey(notice_date: str) -> list:
    import akshare as ak
    rows = []
    try:
        df = ak.stock_jgdy_tj_em(date=notice_date)
        if df is None or df.empty:
            return rows
        for _, r in df.iterrows():
            code = str(r.get("代码", "")).strip().zfill(6)
            if not code or code == "000000":
                continue
            md = to_date(r.get("接待日期"))
            nd = to_date(r.get("公告日期"))
            if md is None:
                continue
            rows.append([
                code,
                str(r.get("名称", ""))[:50],
                to_float(r.get("最新价")),
                to_float(r.get("涨跌幅")),
                to_int(r.get("接待机构数量")),
                str(r.get("接待方式", ""))[:100],
                str(r.get("接待人员", ""))[:100],
                str(r.get("接待地点", ""))[:100],
                md,
                nd,
                datetime.datetime.now(),
            ])
        print(f"  机构调研: {len(rows)} 条")
    except Exception as e:
        print(f"  机构调研获取失败: {e}")
    return rows


# ═══════════════════════════════════════════════════════════════
# 6. 大宗交易  stock_dzjy_mrmx（逐笔明细，A股含营业部）
# ║  返回字段: 序号/交易日期/证券代码/证券简称/
# ║            涨跌幅/收盘价/成交价/折溢率/
# ║            成交量(股)/成交额(元)/成交额占流通市值比/
# ║            买方营业部/卖方营业部
# ║  逐笔存储，同一股票同一天多笔分别记录(seq_no区分)
# ╙══════════════════════════════════════════════════════════════

def fetch_block_trade(start_date: str, end_date: str) -> list:
    import akshare as ak
    rows = []
    try:
        df = ak.stock_dzjy_mrmx(symbol="A股", start_date=start_date, end_date=end_date)
        if df is None or df.empty:
            return rows
        # 逐笔存储，按(code, trade_date)分配seq_no
        from collections import defaultdict
        seq_counter = defaultdict(int)
        for _, r in df.iterrows():
            code = str(r.get("证券代码", "")).strip().zfill(6)
            if not code:
                continue
            td = to_date(r.get("交易日期"))
            if td is None:
                continue
            key = (code, str(td))
            seq_counter[key] += 1
            rows.append([
                seq_counter[key],                     # seq_no
                td,                                    # trade_date
                code,                                  # code
                str(r.get("证券简称", ""))[:50],       # name
                to_float(r.get("成交价")),              # price(元)
                to_float(r.get("成交量")),              # volume(股)
                to_float(r.get("成交额")),              # amount(元)
                to_float(r.get("折溢率")),              # discount_rate
                to_float(r.get("涨跌幅")),              # change_pct
                to_float(r.get("收盘价")),              # close_price
                to_float(r.get("成交额/流通市值")),     # pct_of_float
                str(r.get("买方营业部", "")).strip()[:200],  # buy_branch
                str(r.get("卖方营业部", "")).strip()[:200],  # sell_branch
                datetime.datetime.now(),               # update_time
            ])
        print(f"  大宗交易: {len(rows)} 笔(A股逐笔)")
    except TypeError as e:
        # mrmx 在非交易日返回 None，触发 'NoneType' object is not subscriptable
        print(f"  大宗交易: 无数据（可能非交易日）")
    except Exception as e:
        print(f"  大宗交易获取失败: {e}")
    return rows


# ═══════════════════════════════════════════════════════════════
# 7. 市场活跃度  stock_market_activity_legu
# ║  返回字段: item/value
# ║  item 取值: 上涨/涨停/真实涨停/st st*涨停/下跌/跌停/
# ║              真实跌停/st st*跌停/平盘/停牌/活跃度/统计日期
# ╙══════════════════════════════════════════════════════════════

def fetch_activity() -> list:
    """市场活跃度，返回 [trade_date, up, zt, zt_real, zt_st,
                                   down, dt, dt_real, dt_st, flat, suspended,
                                   activity_ratio, update_time]"""
    import akshare as ak
    try:
        df = ak.stock_market_activity_legu()
        if df is None or df.empty:
            return []
        rec = {}
        trade_date = None
        for _, r in df.iterrows():
            item  = str(r.get("item", ""))
            value = r.get("value")
            # 统计日期行：提取日期
            if "统计日期" in item or "统计日期" in str(value):
                ds = str(value)
                if len(ds) >= 10:
                    trade_date = to_date(ds[:10])
                continue
            rec[item] = value

        if trade_date is None:
            # 从"统计日期"行取日期
            for _, r in df.iterrows():
                if "统计日期" in str(r.get("item", "")):
                    ds = str(r.get("value", ""))
                    if len(ds) >= 10:
                        trade_date = to_date(ds[:10])
                        break
        if trade_date is None:
            # fallback：用今天
            trade_date = datetime.date.today()

        activity_str = str(rec.get("活跃度", "0%")).replace("%", "")
        rows = [[
            trade_date,
            to_int(rec.get("上涨")),
            to_int(rec.get("涨停")),
            to_int(rec.get("真实涨停")),
            to_int(rec.get("st st*涨停", rec.get("st*涨停", rec.get("ST涨停", 0)))),
            to_int(rec.get("下跌")),
            to_int(rec.get("跌停")),
            to_int(rec.get("真实跌停")),
            to_int(rec.get("st st*跌停", rec.get("st*跌停", rec.get("ST跌停", 0)))),
            to_int(rec.get("平盘")),
            to_int(rec.get("停牌")),
            to_float(activity_str),
            datetime.datetime.now(),
        ]]
        print(f"  市场活跃度: {trade_date} 上涨{rows[0][1]}家/涨停{rows[0][2]}家")
        return rows
    except Exception as e:
        print(f"  市场活跃度获取失败: {e}")
        return []


# ═══════════════════════════════════════════════════════════════
# 保留：8. 涨跌停池（涨停/跌停/炸板）
# ╙══════════════════════════════════════════════════════════════

def fetch_zt_data(date_str: str) -> list:
    """获取涨跌停池，返回 zt_rows（供 _dual_write 直接写入 stock_sentiment_zt）"""
    import akshare as ak
    zt_rows = []
    try:
        # 涨停强势池
        df = ak.stock_zt_pool_strong_em(date=date_str)
        if df is not None and not df.empty:
            for _, r in df.iterrows():
                code = str(r.get("代码", "")).zfill(6)
                zt_stat = str(r.get("涨停统计", ""))
                is_new = 1 if zt_stat.startswith("1/") else 0
                zt_rows.append([
                    code, to_date(date_str), code,
                    str(r.get("名称", "")),
                    to_float(r.get("最新价")), to_float(r.get("涨跌幅")),
                    "zt", str(r.get("入选理由", ""))[:200], is_new,
                    datetime.datetime.now(),
                ])
            print(f"  涨停强势池: {len(zt_rows)} 条")
    except Exception as e:
        print(f"  涨停强势池获取失败: {e}")

    try:
        df2 = ak.stock_zt_pool_dtgc_em(date=date_str)
        if df2 is not None and not df2.empty:
            for _, r in df2.iterrows():
                code = str(r.get("代码", "")).zfill(6)
                zt_rows.append([
                    code, to_date(date_str), code,
                    str(r.get("名称", "")),
                    to_float(r.get("最新价")), to_float(r.get("涨跌幅")),
                    "dt", str(r.get("所属行业", ""))[:200], 0,
                    datetime.datetime.now(),
                ])
            print(f"  跌停池: {len(df2)} 条")
    except Exception as e:
        print(f"  跌停池获取失败: {e}")

    try:
        df3 = ak.stock_zt_pool_zbgc_em(date=date_str)
        if df3 is not None and not df3.empty:
            for _, r in df3.iterrows():
                code = str(r.get("代码", "")).zfill(6)
                zt_rows.append([
                    code, to_date(date_str), code,
                    str(r.get("名称", "")),
                    to_float(r.get("最新价")), to_float(r.get("涨跌幅")),
                    "zbgc", str(r.get("所属行业", ""))[:200], 0,
                    datetime.datetime.now(),
                ])
            print(f"  炸板池: {len(df3)} 条")
    except Exception as e:
        print(f"  炸板池获取失败: {e}")

    return zt_rows


# ═══════════════════════════════════════════════════════════════
# 9. 资金流向（东方财富全市场个股资金流）
# ║  使用 stock_individual_fund_flow_rank 一次获取全市场数据
# ║  列名: 序号/代码/名称/最新价/今日涨跌幅/
# ║        今日主力净流入-净额/净占比/超大单净额/净占比/
# ║        大单净额/净占比/中单净额/净占比/小单净额/净占比
# ╙══════════════════════════════════════════════════════════════

def fetch_moneyflow_rank() -> list:
    """获取全市场个股当日资金流向排行（东方财富）
    返回 [ts_code, trade_date, code, name, close, pct_change,
           net_main, net_main_pct, net_huge, net_big, net_medium, net_small,
           update_time]
    注意：该接口无日期列，trade_date 用最近一个交易日（从 stock_individual_fund_flow 探测）"""
    import akshare as ak
    rows = []
    try:
        # 1) 先用单只股票探测最新交易日
        probe_df = ak.stock_individual_fund_flow(stock="600519", market="sh")
        if probe_df is None or probe_df.empty:
            print("  资金流向: 探测交易日失败")
            return rows
        latest_date = to_date(probe_df["日期"].iloc[-1])
        if latest_date is None:
            print("  资金流向: 无法解析交易日")
            return rows

        # 2) 获取全市场资金流向排行
        df = ak.stock_individual_fund_flow_rank(indicator="今日")
        if df is None or df.empty:
            print("  资金流向: 无数据")
            return rows

        # 自适应列名（"今日" / "3日" / "5日" / "10日" 前缀不同）
        prefix = "今日"
        col_code = "代码"
        col_name = "名称"
        col_close = "最新价"
        col_pct = f"{prefix}涨跌幅"
        col_net_main = f"{prefix}主力净流入-净额"
        col_pct_main = f"{prefix}主力净流入-净占比"
        col_net_huge = f"{prefix}超大单净流入-净额"
        col_net_big = f"{prefix}大单净流入-净额"
        col_net_medium = f"{prefix}中单净流入-净额"
        col_net_small = f"{prefix}小单净流入-净额"

        for _, r in df.iterrows():
            code = str(r.get(col_code, "")).strip().zfill(6)
            if not code or code == "000000":
                continue
            rows.append([
                code,                          # ts_code
                latest_date,                   # trade_date
                code,                          # code
                str(r.get(col_name, "")),       # name
                to_float(r.get(col_close)),     # close
                to_float(r.get(col_pct)),       # pct_change
                to_float(r.get(col_net_main)),  # net_main
                to_float(r.get(col_pct_main)),  # net_main_pct
                to_float(r.get(col_net_huge)),  # net_huge
                to_float(r.get(col_net_big)),   # net_big
                to_float(r.get(col_net_medium)),# net_medium
                to_float(r.get(col_net_small)), # net_small
                datetime.datetime.now(),        # update_time
            ])
        print(f"  资金流向(全市场): {len(rows)} 条  日期={latest_date}")
    except Exception as e:
        print(f"  资金流向获取失败: {e}")
        traceback.print_exc()
    return rows


def fetch_moneyflow_hist_single(code: str, market: str, max_retries: int = 5) -> list:
    """获取单只股票近 120 天资金流向历史数据（带重试+退避）
    返回 [ts_code, trade_date, code, name, close, pct_change,
           net_main, net_main_pct, net_huge, net_big, net_medium, net_small,
           update_time]"""
    import akshare as ak
    import random
    rows = []
    for attempt in range(max_retries):
        try:
            # 每个请求用独立 session，避免被东财识别并发特征
            import requests
            session = requests.Session()
            ua = random.choice([
                'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36',
                'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/121.0.0.0 Safari/537.36',
                'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36',
            ])
            session.headers.update({'User-Agent': ua})
            ak.session = session

            df = ak.stock_individual_fund_flow(stock=code, market=market)
            if df is None or df.empty:
                return rows
            for _, r in df.iterrows():
                td = to_date(r.get("日期"))
                if td is None:
                    continue
                rows.append([
                    code,                              # ts_code
                    td,                                # trade_date
                    code,                              # code
                    "",                                # name (历史数据不一定有)
                    to_float(r.get("收盘价")),          # close
                    to_float(r.get("涨跌幅")),          # pct_change
                    to_float(r.get("主力净流入-净额")),  # net_main
                    to_float(r.get("主力净流入-净占比")), # net_main_pct
                    to_float(r.get("超大单净流入-净额")), # net_huge
                    to_float(r.get("大单净流入-净额")),   # net_big
                    to_float(r.get("中单净流入-净额")),   # net_medium
                    to_float(r.get("小单净流入-净额")),   # net_small
                    datetime.datetime.now(),            # update_time
                ])
            return rows
        except Exception:
            if attempt < max_retries - 1:
                wait = min(2 ** attempt * 3.0, 30.0)
                time.sleep(wait)
            else:
                pass  # 最后一次失败静默
    return rows


# ═══════════════════════════════════════════════════════════════
# 保留：10. 公告  stock_notice_report
# ╙══════════════════════════════════════════════════════════════

def fetch_notice_report(date_str: str) -> list:
    import akshare as ak
    rows = []
    try:
        df = ak.stock_notice_report(symbol="全部", date=date_str)
        if df is None or df.empty:
            print(f"  公告({date_str[:4]}-{date_str[4:6]}-{date_str[6:]}): 无数据")
            return rows
        col_code = "代码" if "代码" in df.columns else ("股票代码" if "股票代码" in df.columns else None)
        col_name = "股票简称" if "股票简称" in df.columns else ("名称" if "名称" in df.columns else None)
        if not col_code:
            print(f"  公告: 未知列名 {list(df.columns)}")
            return rows
        for _, r in df.iterrows():
            code = str(r.get(col_code, "")).strip().zfill(6)
            if not code:
                continue
            rows.append([
                code,
                code,
                str(r.get(col_name, ""))[:50],
                str(r.get("公告类型", ""))[:50],
                to_date(r.get("公告日期") or date_str),
                str(r.get("公告标题", ""))[:500],
                datetime.datetime.now(),
            ])
        print(f"  公告: {len(rows)} 条")
    except Exception as e:
        print(f"  公告获取失败: {e}")
    return rows


# ═══════════════════════════════════════════════════════════════
# NeoData 资金流向（补数据模式）
# 用法：
#   python update_sentiment_data.py --moneyflow-neodata                        # 全部缺失股票
#   python update_sentiment_data.py --moneyflow-neodata --moneyflow-codes 600519,000001  # 指定股票
#   python update_sentiment_data.py --moneyflow-neodata --start-date 2026-05-07 --end-date 2026-05-17  # 日期范围
# ═══════════════════════════════════════════════════════════════

def run_neodata_moneyflow(args):
    """通过 NeoData 补全资金流向，支持双写 CH + MySQL"""
    # 确定日期范围
    start_str = args.start_date or "2026-05-07"
    end_str = args.end_date or datetime.date.today().isoformat()

    # 计算期望日期数（自然日，作为数据完整性的近似阈值）
    start_dt = datetime.datetime.strptime(start_str, "%Y-%m-%d").date()
    end_dt = datetime.datetime.strptime(end_str, "%Y-%m-%d").date()
    expected_days = (end_dt - start_dt).days + 1

    # 获取股票列表
    if args.moneyflow_codes:
        raw_codes = [c.strip().zfill(6) for c in args.moneyflow_codes.split(",")]
        conn = pymysql.connect(**MYSQL_CONFIG)
        cur = conn.cursor()
        cur.execute("SELECT code, name FROM stock_info WHERE code IN (" +
                    ",".join(["%s"] * len(raw_codes)) + ")", raw_codes)
        name_map = dict(cur.fetchall())
        cur.close()
        conn.close()
        target = []
        for c in raw_codes:
            prefix = ".SH" if c.startswith(("6", "5")) else ".SZ"
            target.append((c + prefix, c, name_map.get(c, c)))
    else:
        conn = pymysql.connect(**MYSQL_CONFIG)
        cur = conn.cursor()
        # 按日期范围检查缺失：查询每个 ts_code 在范围内的数据条数
        cur.execute("""
            SELECT ts_code, COUNT(DISTINCT trade_date) as cnt
            FROM stock_sentiment_moneyflow
            WHERE trade_date >= %s AND trade_date <= %s
            GROUP BY ts_code
        """, (start_str, end_str))
        existing = {r[0]: r[1] for r in cur.fetchall()}
        cur.execute("SELECT code, name FROM stock_info")
        all_name_map = dict(cur.fetchall())
        cur.close()
        conn.close()
        bex = {s for s in all_name_map if s.startswith("8") or s.startswith("4")}
        target = []
        for code in all_name_map:
            if code in bex or len(code) != 6:
                continue
            ts = code + (".SH" if code.startswith(("6", "5")) else ".SZ")
            cnt = existing.get(ts, 0)
            # 数据条数 >= 期望日期数的 80% 视为已完整，跳过
            if cnt >= expected_days * 0.8:
                continue
            target.append((ts, code, all_name_map[code]))
        target.sort()

    print(f"=== NeoData 资金流向补全: {len(target)} 只 ===")

    # 创建 CH 客户端用于回填 close/change_percent
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

    # 格式化为自然语言查询
    start_fmt = start_str.replace("-", "年", 1).replace("-", "月") + "日"
    end_fmt = end_str.replace("-", "年", 1).replace("-", "月") + "日"
    date_label = f"{start_fmt}到{end_fmt}"

    MF_CH_COLS = ["ts_code","trade_date","code","name","close","pct_change",
                  "net_main","net_main_pct","net_huge","net_big","net_medium","net_small","update_time"]
    MF_MY_COLS = MF_CH_COLS[:]
    MF_UNIQUE = ["code", "trade_date"]

    grand_start = datetime.datetime.now()
    ok_count = fail_count = 0

    for i, (ts_code, code, name) in enumerate(target):
        label = f"{code}.{name}" if name != code else code
        print(f"[{i+1}/{len(target)}] {label}...", end=" ", flush=True)

        query_str = f"{name}资金流向{date_label}"
        data = query_neodata(query_str)
        if not data:
            print("✗ 查询失败")
            fail_count += 1
            time.sleep(0.2)
            continue

        rows = extract_neodata_moneyflow(data)
        if not rows:
            print("✗ 无数据")
            fail_count += 1
            time.sleep(0.2)
            continue

        # 从 stock_daily 回填 close 和 pct_change
        fill_close_pct_from_stock_daily(rows, ch_client)

        # 查一只写一只（双写 CH + MySQL）
        _dual_write(
            "stock_sentiment_moneyflow", rows,
            MF_CH_COLS, MF_MY_COLS, MF_UNIQUE,
            dry_run=args.dry_run,
        )
        print(f"✓ {len(rows)}条")
        ok_count += 1
        time.sleep(0.2)

    ch_client.close()
    grand_elapsed = (datetime.datetime.now() - grand_start).total_seconds()
    print(f"\n{'=' * 60}")
    print(f"  NeoData 资金流向完成")
    print(f"  总耗时 {grand_elapsed:.1f}s ({grand_elapsed/60:.1f}min)")
    print(f"  成功 {ok_count} 只  失败 {fail_count} 只")
    print(f"{'=' * 60}")


def _fetch_stock_moneyflow(args, ts_code: str, code: str, name: str, months: list):
    """单只股票的全量资金流向抓取（供并行调用）

    Returns:
        (ts_code, code, name, rows_list)
    """
    try:
        all_rows = []
        for m_start, m_end in months:
            start_fmt = m_start.strftime("%Y年%m月%d日")
            end_fmt   = m_end.strftime("%Y年%m月%d日")
            date_label = f"{start_fmt}到{end_fmt}"
            query_str  = f"{name}资金流向{date_label}"
            try:
                data = query_neodata(query_str)
                if not isinstance(data, dict):
                    continue
                rows = extract_neodata_moneyflow(data)
                if rows:
                    all_rows.extend(rows)
            except Exception as e:
                print(f"  [WARN] {code} {date_label} 抓取失败: {e}")
            time.sleep(0.2)
        return (ts_code, code, name, all_rows)
    except Exception as e:
        print(f"  [ERROR] {code}.{name} 整体失败: {e}")
        return (ts_code, code, name, [])


def run_neodata_refresh(args):
    """NeoData 全量重刷：多线程并行查询，覆盖全部已有数据
    用法：
      python update_sentiment_data.py --moneyflow-refresh              # 全量重刷 2025-11 起
      python update_sentiment_data.py --moneyflow-refresh --refresh-codes 600519,000001  # 指定股票
      python update_sentiment_data.py --moneyflow-refresh --refresh-start 2026-01-01   # 指定起始月
    """
    # ── 确定日期范围 ─────────────────────────────────────────────
    start_str = args.refresh_start  # e.g. "2025-11-01"
    end_str = datetime.date.today().isoformat()

    start_dt = datetime.datetime.strptime(start_str, "%Y-%m-%d").date()
    end_dt = datetime.date.today()

    # 生成月首/月尾列表
    months = []
    cur = start_dt.replace(day=1)
    while cur <= end_dt:
        if cur.month == 12:
            next_month = cur.replace(year=cur.year + 1, month=1, day=1)
        else:
            next_month = cur.replace(month=cur.month + 1, day=1)
        last_day = next_month - datetime.timedelta(days=1)
        if last_day > end_dt:
            last_day = end_dt
        months.append((cur, last_day))
        cur = next_month

    print(f"=== NeoData 全量重刷: {len(months)} 个月 [{start_str} ~ {end_str}] ===")

    # ── 获取股票列表 ─────────────────────────────────────────────
    conn = pymysql.connect(**MYSQL_CONFIG)
    cur = conn.cursor()

    if args.refresh_codes:
        raw_codes = [c.strip().zfill(6) for c in args.refresh_codes.split(",")]
        cur.execute("SELECT code, name FROM stock_info WHERE code IN (" +
                    ",".join(["%s"] * len(raw_codes)) + ")", raw_codes)
    else:
        cur.execute("""
            SELECT code, name FROM stock_info
            WHERE code NOT LIKE '920%'
              AND code NOT LIKE '8%'
              AND name NOT LIKE 'ST%'
              AND name NOT LIKE '*ST%'
              AND LENGTH(code) = 6
            ORDER BY code
        """)

    rows_raw = cur.fetchall()
    cur.close()
    conn.close()

    target = []
    for code, name in rows_raw:
        prefix = ".SH" if code.startswith(("6", "5")) else ".SZ"
        target.append((code + prefix, code, name or code))
    print(f"  共 {len(target)} 只股票")

    # ── CH 客户端（复用） ───────────────────────────────────────
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

    MF_CH_COLS = ["ts_code","trade_date","code","name","close","pct_change",
                  "net_main","net_main_pct","net_huge","net_big","net_medium","net_small","update_time"]
    MF_MY_COLS = MF_CH_COLS[:]
    MF_UNIQUE  = ["code", "trade_date"]

    grand_start = datetime.datetime.now()
    total_ok = total_fail = total_rows = 0
    done = 0

    # ── 多线程并行查询 NeoData ───────────────────────────────────
    # 每只股票内部按月串行（同一 API 并发无意义），不同股票间并行
    WORKERS = 12   # 并发数，太高可能触发 NeoData 限流

    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {
            pool.submit(_fetch_stock_moneyflow, args, ts, cd, nm, months): (ts, cd, nm)
            for ts, cd, nm in target
        }

        for future in as_completed(futures):
            ts_code, code, name = futures[future]
            done += 1
            label = f"{code}.{name}" if name != code else code

            try:
                _, _, _, rows = future.result()
            except Exception as e:
                print(f"[{done}/{len(target)}] {label}... ✗ 异常 {e}")
                total_fail += 1
                continue

            if not rows:
                print(f"[{done}/{len(target)}] {label}... ✗ 无数据")
                total_fail += 1
                continue

            # 回填 close/pct_change
            fill_close_pct_from_stock_daily(rows, ch_client)

            _dual_write(
                "stock_sentiment_moneyflow", rows,
                MF_CH_COLS, MF_MY_COLS, MF_UNIQUE,
                dry_run=args.dry_run,
                force=True,
            )
            total_ok   += 1
            total_rows += len(rows)
            print(f"[{done}/{len(target)}] {label}... ✓ {len(rows)}条")

    ch_client.close()
    elapsed = (datetime.datetime.now() - grand_start).total_seconds()
    print(f"\n{'=' * 60}")
    print(f"  NeoData 全量重刷完成")
    print(f"  总耗时 {elapsed:.0f}s ({elapsed/60:.0f}min)")
    print(f"  成功 {total_ok} 只  失败 {total_fail} 只  总写入 {total_rows} 条")
    print(f"{'=' * 60}")


# ═══════════════════════════════════════════════════════════════
# 主流程
# ╙══════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(description="市场情绪数据采集 v3")
    parser.add_argument("--date",        default=None, help="指定日期 YYYYMMDD（默认今日）")
    parser.add_argument("--start-date",  default=None, help="开始日期 YYYY-MM-DD")
    parser.add_argument("--end-date",    default=None, help="结束日期 YYYY-MM-DD")
    parser.add_argument("--dry-run",     action="store_true", help="不写入数据库")
    parser.add_argument("--catchup",    type=int, default=0, help="补最近 N 天数据")
    parser.add_argument("--moneyflow-hist", action="store_true", help="历史资金流向补全（逐只股票120天，akshare东财）")
    parser.add_argument("--moneyflow-codes", default=None, help="指定股票代码补全历史资金流，逗号分隔")
    parser.add_argument("--moneyflow-neodata", action="store_true", help="NeoData 补全资金流向（更快，推荐）")
    parser.add_argument("--moneyflow-refresh", action="store_true", help="NeoData 全量重刷（按月分批，覆盖全部已有数据）")
    parser.add_argument("--refresh-start", default="2025-11-01", help="重刷起始月 YYYY-MM-DD（默认 2025-11-01）")
    parser.add_argument("--refresh-codes", default=None, help="仅重刷指定股票，逗号分隔（默认全量）")
    # 跳过选项
    parser.add_argument("--skip-lhb",        action="store_true", help="跳过龙虎榜详情")
    parser.add_argument("--skip-lhb-inst",  action="store_true", help="跳过龙虎榜机构")
    parser.add_argument("--skip-margin",     action="store_true", help="跳过融资融券汇总")
    parser.add_argument("--skip-margin-detail", action="store_true", help="跳过融资融券个股")
    parser.add_argument("--skip-survey",    action="store_true", help="跳过机构调研")
    parser.add_argument("--skip-block",     action="store_true", help="跳过大宗交易")
    parser.add_argument("--skip-activity",  action="store_true", help="跳过市场活跃度")
    parser.add_argument("--skip-zt",       action="store_true", help="跳过涨跌停池")
    parser.add_argument("--skip-moneyflow", action="store_true", help="跳过资金流向")
    parser.add_argument("--skip-notice",    action="store_true", help="跳过公告")
    parser.add_argument("--fund-holder",   action="store_true", help="采集基金投倉明细 (akshare)")
    parser.add_argument("--fund-holder-codes", default=None, help="指定股版列表,逗号分隔")
    parser.add_argument("--shareholder",   action="store_true", help="采集股东人数 (akshare)")
    parser.add_argument("--shareholder-codes", default=None, help="指定股票代码列表,逗号分隔")
    parser.add_argument("--news",          action="store_true", help="采集个股新闻+事件标签+情感 (akshare)")
    parser.add_argument("--news-codes",    default=None,       help="指定股票代码列表,逗号分隔")
    parser.add_argument("--news-days",     type=int, default=90, help="新闻天数范围（默认90天）")
    parser.add_argument("--force",         action="store_true", help="全量重刷（force，覆盖已有数据）")
    args = parser.parse_args()

    # ── 专项采集（可与默认情绪数据采集并存）───

    # 11. 基金持仓批量采集
    if args.fund_holder:
        import pymysql, time as _time, math as _math, datetime as _dt
        from concurrent.futures import ThreadPoolExecutor, as_completed
        import update_fund_holder as ufh
        conn = ufh.get_conn()
        codes = ufh.get_codes(conn, skip_done=not args.force)
        if args.fund_holder_codes:
            codes = [c.strip().zfill(6) for c in args.fund_holder_codes.split(",")]
        ufh.run_batch(conn, codes, workers=4)
        ufh.check_status(conn)
        conn.close()

    # 12. 股东人数批量采集
    if args.shareholder:
        import pymysql, time as _time, math as _math, datetime as _dt
        from concurrent.futures import ThreadPoolExecutor, as_completed
        import update_shareholder_batch as usb
        conn = usb.get_mysql_conn()
        codes = usb.get_codes_to_fetch(conn, force_all=args.force)
        if args.shareholder_codes:
            codes = [c.strip().zfill(6) for c in args.shareholder_codes.split(",")]
        usb.run_batch(conn, codes, max_workers=8)
        usb.check_status(conn)
        conn.close()

    # 13. 个股新闻+事件标签+情感采集
    if args.news:
        import update_news_data as und
        conn = und.get_conn()
        codes = und.get_batch_stocks(conn, force_all=args.force)
        if args.news_codes:
            codes = [c.strip().zfill(6) for c in args.news_codes.split(",")]
        und.run_batch(codes, days=args.news_days, workers=4)

    if args.moneyflow_neodata:
        run_neodata_moneyflow(args)
        return

    if args.moneyflow_refresh:
        run_neodata_refresh(args)
        return

    # ── 历史资金流向补全模式 ───────────────────────────────────
    if args.moneyflow_hist:
        run_moneyflow_hist(args)
        return

    # ── 日期范围模式：直接循环处理，不启动子进程 ──────────────
    if args.start_date and args.end_date:
        start_dt = datetime.datetime.strptime(args.start_date, "%Y-%m-%d").date()
        end_dt   = datetime.datetime.strptime(args.end_date,   "%Y-%m-%d").date()
        date_list = []
        d = start_dt
        while d <= end_dt:
            date_list.append(d.strftime("%Y%m%d"))
            d += datetime.timedelta(days=1)
        print(f"=== 日期范围模式：{args.start_date} ~ {args.end_date}，共 {len(date_list)} 天 ===")
        grand_start = datetime.datetime.now()
        for ds in date_list:
            print(f"\n{'=' * 60}")
            print(f"  日期: {ds[:4]}-{ds[4:6]}-{ds[6:]}  [{date_list.index(ds)+1}/{len(date_list)}]")
            print(f"{'=' * 60}")
            process_single_date(args, ds)
        grand_elapsed = (datetime.datetime.now() - grand_start).total_seconds()
        print(f"\n{'=' * 60}")
        print(f"  全部日期采集完成  总耗时 {grand_elapsed:.1f}s")
        return

    # 单日期 / catchup 模式：直接调用
    if args.date:
        date_str = args.date
    else:
        today = datetime.date.today()
        date_str = today.strftime("%Y%m%d")

    # catchup 模式：循环补数据
    if args.catchup > 0:
        run_catchup(args, date_str)
        return

    process_single_date(args, date_str)


def process_single_date(args, date_str: str):
    """处理单日数据采集（供日期范围模式或单日模式调用）"""
    # 防写未来日期校验：拒绝写入 trade_date > today 的数据
    today = datetime.date.today()
    try:
        req_date = datetime.date(int(date_str[:4]), int(date_str[4:6]), int(date_str[6:]))
        if req_date > today:
            print(f"[SKIP] 日期 {date_str} 是未来日期，跳过 (today={today.isoformat()})")
            return
    except (ValueError, IndexError):
        print(f"[WARN] 无效日期格式: {date_str}，跳过")

    date_disp = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:]}"
    start_str = date_str
    end_str   = date_str

    start = datetime.datetime.now()
    print("=" * 60)
    print(f"  市场情绪数据采集  {date_disp}")
    print("=" * 60)

    # ── 1. 龙虎榜详情 ─────────────────────────────────────────
    if not args.skip_lhb:
        print("[INFO] 龙虎榜详情...")
        rows = fetch_lhb_detail(start_str, end_str)
        if rows:
            _dual_write(
                "stock_sentiment_lhb", rows,
                ["code","name","trade_date","close","pct_change",
                 "net_amount","buy_amount","sell_amount","total_amount",
                 "market_amount","net_ratio","amount_ratio","turnover",
                 "float_mv","reason","after_1d","after_2d","after_5d","after_10d","update_time"],
                ["code","name","trade_date","close","pct_change",
                 "net_amount","buy_amount","sell_amount","total_amount",
                 "market_amount","net_ratio","amount_ratio","turnover",
                 "float_mv","reason","after_1d","after_2d","after_5d","after_10d","update_time"],
                ["code", "trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无龙虎榜数据（可能非交易日）")
    else:
        print("[SKIP] 龙虎榜详情已跳过")

    # ── 2. 龙虎榜机构 ───────────────────────────────────────
    if not args.skip_lhb_inst:
        print("[INFO] 龙虎榜机构统计...")
        rows = fetch_lhb_inst(start_str, end_str)
        if rows:
            _dual_write(
                "stock_sentiment_lhb_inst", rows,
                ["code","name","trade_date","close","pct_change",
                 "buy_inst_cnt","sell_inst_cnt","buy_inst_amt","sell_inst_amt",
                 "net_inst_amt","market_amount","net_inst_ratio",
                 "turnover","float_mv","reason","update_time"],
                ["code","name","trade_date","close","pct_change",
                 "buy_inst_cnt","sell_inst_cnt","buy_inst_amt","sell_inst_amt",
                 "net_inst_amt","market_amount","net_inst_ratio",
                 "turnover","float_mv","reason","update_time"],
                ["code", "trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无龙虎榜机构数据")
    else:
        print("[SKIP] 龙虎榜机构已跳过")

    # ── 3. 融资融券汇总 ──────────────────────────────────────
    if not args.skip_margin:
        print("[INFO] 融资融券汇总（沪市）...")
        rows = fetch_margin(start_str, end_str)
        if rows:
            _dual_write(
                "stock_sentiment_margin", rows,
                ["trade_date","margin_balance","margin_buy",
                 "short_balance_vol","short_balance_amt","short_sell_vol",
                 "margin_short_bal","update_time"],
                ["trade_date","margin_balance","margin_buy",
                 "short_balance_vol","short_balance_amt","short_sell_vol",
                 "margin_short_bal","update_time"],
                ["trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无融资融券汇总数据")
    else:
        print("[SKIP] 融资融券汇总已跳过")

    # ── 4. 融资融券个股 ──────────────────────────────────────
    if not args.skip_margin_detail:
        print("[INFO] 融资融券个股（沪市）...")
        rows = fetch_margin_detail(date_str)
        if rows:
            _dual_write(
                "stock_sentiment_margin_detail", rows,
                ["trade_date","code","name","margin_balance","margin_buy",
                 "margin_repay","short_balance_vol","short_sell_vol",
                 "short_repay_vol","update_time"],
                ["trade_date","code","name","margin_balance","margin_buy",
                 "margin_repay","short_balance_vol","short_sell_vol",
                 "short_repay_vol","update_time"],
                ["code", "trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无融资融券个股数据")
    else:
        print("[SKIP] 融资融券个股已跳过")

    # ── 5. 机构调研 ─────────────────────────────────────────
    if not args.skip_survey:
        print(f"[INFO] 机构调研（公告日期 {date_disp}）...")
        rows = fetch_survey(date_str)
        if rows:
            _dual_write(
                "stock_sentiment_survey", rows,
                ["code","name","price","pct_change","inst_count",
                 "meeting_type","staff","location","meeting_date","notice_date","update_time"],
                ["code","name","price","pct_change","inst_count",
                 "meeting_type","staff","location","meeting_date","notice_date","update_time"],
                ["code", "meeting_date", "notice_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无机构调研数据")
    else:
        print("[SKIP] 机构调研已跳过")

    # ── 6. 大宗交易 ────────────────────────────────────────
    if not args.skip_block:
        print("[INFO] 大宗交易...")
        rows = fetch_block_trade(start_str, end_str)
        if rows:
            _dual_write(
                "stock_sentiment_block_trade", rows,
                ["seq_no","trade_date","code","name","price","volume","amount",
                 "discount_rate","change_pct","close_price","pct_of_float",
                 "buy_branch","sell_branch","update_time"],
                ["seq_no","trade_date","code","name","price","volume","amount",
                 "discount_rate","change_pct","close_price","pct_of_float",
                 "buy_branch","sell_branch","update_time"],
                ["code", "trade_date", "seq_no"],
                dry_run=args.dry_run,
            )
        else:
            print("  无大宗交易数据")
    else:
        print("[SKIP] 大宗交易已跳过")

    # ── 7. 市场活跃度 ──────────────────────────────────────
    if not args.skip_activity:
        print("[INFO] 市场活跃度...")
        rows = fetch_activity()
        if rows:
            _dual_write(
                "stock_sentiment_activity", rows,
                ["trade_date","up_count","zt_count","zt_real_count","zt_st_count",
                 "down_count","dt_count","dt_real_count","dt_st_count",
                 "flat_count","suspended_count","activity_ratio","update_time"],
                ["trade_date","up_count","zt_count","zt_real_count","zt_st_count",
                 "down_count","dt_count","dt_real_count","dt_st_count",
                 "flat_count","suspended_count","activity_ratio","update_time"],
                ["trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无市场活跃度数据")
    else:
        print("[SKIP] 市场活跃度已跳过")

    # ── 8. 涨跌停池（保留）────────────────────────────────
    if not args.skip_zt:
        print(f"[INFO] 涨跌停池 ({date_str})...")
        zt_rows = fetch_zt_data(date_str)
        if zt_rows:
            _dual_write(
                "stock_sentiment_zt", zt_rows,
                ["code","trade_date","ts_code","name","close",
                 "pct_change","zt_type","reason","is_new","update_time"],
                ["code","trade_date","ts_code","name","close",
                 "pct_change","zt_type","reason","is_new","update_time"],
                ["code", "trade_date", "zt_type"],
                dry_run=args.dry_run,
            )
        else:
            print(f"  当日 ({date_str}) 无涨跌停数据（可能非交易日）")
    else:
        print("[SKIP] 涨跌停池已跳过")

    # ── 9. 资金流向（全市场个股）───────────────────────────
    if not args.skip_moneyflow:
        print("[INFO] 资金流向（东方财富全市场）...")
        mf_rows = fetch_moneyflow_rank()
        if mf_rows:
            _dual_write(
                "stock_sentiment_moneyflow", mf_rows,
                ["ts_code","trade_date","code","name","close",
                 "pct_change","net_main","net_main_pct",
                 "net_huge","net_big","net_medium","net_small",
                 "update_time"],
                ["ts_code","trade_date","code","name","close",
                 "pct_change","net_main","net_main_pct",
                 "net_huge","net_big","net_medium","net_small",
                 "update_time"],
                ["code", "trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  资金流向: 无有效数据（可能非交易日）")
    else:
        print("[SKIP] 资金流向已跳过")

    # ── 10. 公告（保留）─────────────────────────────────
    if not args.skip_notice:
        print(f"[INFO] 公告 ({date_disp})...")
        notice_rows = fetch_notice_report(date_str)
        if notice_rows:
            _dual_write(
                "stock_sentiment_notice", notice_rows,
                ["ts_code","code","name","notice_type","notice_date","title","update_time"],
                ["ts_code","code","name","notice_type","notice_date","title","update_time"],
                ["code", "notice_date"],
                dry_run=args.dry_run,
            )
        else:
            print(f"  公告({date_disp}): 无数据")
    else:
        print("[SKIP] 公告已跳过")

    elapsed = (datetime.datetime.now() - start).total_seconds()
    print("=" * 60)
    print(f"  采集完成  耗时 {elapsed:.1f}s")
    print("=" * 60)


def run_catchup(args, latest_date_str: str):
    """补数据模式：从 latest_date_str 往前推 N 天，逐个日期执行"""
    n = args.catchup
    print(f"=== 补数据模式：最近 {n} 个交易日 ===")
    # 生成日期列表（简单处理：取最近 n 个自然日，脚本内部会跳过非交易日）
    date_list = []
    d = datetime.date.today()
    for i in range(n):
        dd = d - datetime.timedelta(days=i)
        date_list.append(dd.strftime("%Y%m%d"))
    date_list.reverse()

    grand_start = datetime.datetime.now()
    for idx, ds in enumerate(date_list):
        print(f"\n{'=' * 60}")
        print(f"  补数据: {ds[:4]}-{ds[4:6]}-{ds[6:]}  [{idx+1}/{len(date_list)}]")
        print(f"{'=' * 60}")
        process_single_date(args, ds)
    grand_elapsed = (datetime.datetime.now() - grand_start).total_seconds()
    print(f"\n{'=' * 60}")
    print(f"  补数据完成  总耗时 {grand_elapsed:.1f}s")


def _get_market_prefix(code: str) -> str:
    """根据股票代码判断市场前缀 (sh/sz/bj)"""
    if code.startswith("6") or code.startswith("9"):
        return "sh"
    elif code.startswith("0") or code.startswith("3"):
        return "sz"
    elif code.startswith("4") or code.startswith("8"):
        return "bj"
    return "sz"  # 默认


def run_moneyflow_hist(args):
    """历史资金流向补全：单线程慢速拉取全市场股票近120天资金流数据
    用法：
      python update_sentiment_data.py --moneyflow-hist                     # 全市场
      python update_sentiment_data.py --moneyflow-hist --moneyflow-codes 600519,000001  # 指定股票
    """
    # 获取股票列表（排除北交所 920 开头）
    if args.moneyflow_codes:
        codes = [c.strip().zfill(6) for c in args.moneyflow_codes.split(",")]
        print(f"=== 历史资金流向补全: 指定 {len(codes)} 只股票 ===")
    else:
        conn = pymysql.connect(**MYSQL_CONFIG)
        cur = conn.cursor()
        cur.execute("SELECT DISTINCT code FROM stock_info WHERE code NOT LIKE '920%' COLLATE utf8mb4_bin ORDER BY code")
        codes = [row[0] for row in cur.fetchall()]
        cur.close()
        conn.close()
        print(f"=== 历史资金流向补全: 全市场 {len(codes)} 只股票（排除北交所 920）===")
        print(f"  注意: 东财接口限制较严，每只间隔2秒，预计需要 {len(codes)*2/60:.0f} 分钟")

    moneyflow_columns = ["ts_code","trade_date","code","name","close",
                          "pct_change","net_main","net_main_pct",
                          "net_huge","net_big","net_medium","net_small",
                          "update_time"]
    moneyflow_unique = ["code", "trade_date"]

    grand_start = datetime.datetime.now()
    total_written = 0
    total_failed = 0
    batch_rows = []
    BATCH_FLUSH = 6000

    def flush_batch():
        nonlocal batch_rows, total_written
        if not batch_rows:
            return
        _dual_write(
            "stock_sentiment_moneyflow", batch_rows,
            moneyflow_columns, moneyflow_columns, moneyflow_unique,
            dry_run=args.dry_run,
        )
        total_written += len(batch_rows)
        batch_rows = []

    done = 0
    for code in codes:
        done += 1
        market = _get_market_prefix(code)

        # 单线程 + 每只2秒延迟，避免东财限流
        rows = fetch_moneyflow_hist_single(code, market, max_retries=3)
        if rows:
            batch_rows.extend(rows)
            if len(batch_rows) >= BATCH_FLUSH:
                flush_batch()
        else:
            total_failed += 1

        # 进度打印
        if done % 10 == 0 or done == len(codes):
            elapsed = (datetime.datetime.now() - grand_start).total_seconds()
            speed = done / elapsed if elapsed > 0 else 0
            eta = (len(codes) - done) / speed if speed > 0 else 0
            print(f"  进度: {done}/{len(codes)}  "
                  f"已写入={total_written}  失败={total_failed}  "
                  f"速度={speed:.1f}只/s  预计剩余={eta/60:.1f}min")

        # 每只间隔2秒，避免东财封IP
        if done < len(codes):
            time.sleep(2.0)

    flush_batch()

    grand_elapsed = (datetime.datetime.now() - grand_start).total_seconds()
    print(f"\n{'=' * 60}")
    print(f"  历史资金流向补全完成")
    print(f"  总耗时 {grand_elapsed:.1f}s ({grand_elapsed/60:.1f}min)")
    print(f"  写入 {total_written} 条  失败 {total_failed} 只")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    import os
    main()

