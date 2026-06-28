#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
update_sentiment_data.py v4
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
    9. stock_sentiment_moneyflow  资金流向（东方财富全市场，v4新增EM实时+历史）
   10. stock_sentiment_notice     公告

用法：
  python update_sentiment_data.py                     # 全部
  python update_sentiment_data.py --skip-lhb           # 跳过龙虎榜
  python update_sentiment_data.py --date 20260430     # 指定日期
  python update_sentiment_data.py --dry-run          # 不写库
  python update_sentiment_data.py --catchup 30        # 补最近30天数据
  # 资金流（新数据源，东方财富）
  python update_sentiment_data.py --em-moneyflow           # 实时全市场（分页，约2000-5500条/次）
  python update_sentiment_data.py --em-moneyflow --codes 600519,000001  # 指定股票
  python update_sentiment_data.py --em-moneyflow-hist      # 历史120天全市场（并发，~5500只）
  python update_sentiment_data.py --em-moneyflow-hist --codes 600519,000001  # 指定股票
  # 资金流（westock-data）
  python update_sentiment_data.py --moneyflow-westock     # westock-data 补全
  python update_sentiment_data.py --moneyflow-refresh     # westock-data 全量重刷
  python update_sentiment_data.py --moneyflow-hist        # akshare 历史补全（慢）
"""

import sys
import os

# 必须在 import 任何可能触发 tqdm 的模块之前设置
os.environ["TQDM_DISABLE"] = "1"
os.environ["DISABLE_TQDM"] = "1"
os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
os.environ["TRANSFORMERS_VERBOSITY"] = "error"

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
import requests
import urllib3
urllib3.disable_warnings()
from db_helper import ch_dedup_filter
from db_config import CLICKHOUSE_CONFIG, MYSQL_CONFIG

# ─── westock-data 资金流向（已替代 NeoData，无需 token）───────────






# ─── 工具函数 ────────────────────────────────────────────────────

# ─── 工具函数 ────────────────────────────────────────────────────

def _latest_trading_day() -> datetime.date:
    """返回最近的交易日：当前时间 < 15:00（收市前）返回上一交易日，否则返回今天。"""
    today = datetime.date.today()
    now = datetime.datetime.now()
    wd = today.weekday()  # 0=Mon ... 6=Sun

    # 周末 → 上周五
    if wd == 5:  # Saturday
        return today - datetime.timedelta(days=1)
    if wd == 6:  # Sunday
        return today - datetime.timedelta(days=2)

    # 周一至周五：收市前（<15:00）返回上一交易日
    if now.hour < 15:
        if wd == 0:  # 周一 <15:00 → 上周五
            return today - datetime.timedelta(days=3)
        else:  # 周二~周五 <15:00 → 昨天
            return today - datetime.timedelta(days=1)

    return today  # 15:00 之后，今天数据已可获取


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


# ─── westock-data 资金流向 ───────────────────────────────────

NEO_QUERY = r"C:\Users\warning5\.workbuddy\plugins\marketplaces\cb_teams_marketplace\plugins\finance-data\skills\neodata-financial-search\scripts\query.py"


# [DEPRECATED] query_neodata / extract_neodata_moneyflow → 已迁移到 westock_moneyflow.py
def fill_close_pct_from_stock_daily(rows, ch_client):
    """
    从 CH stock_daily 表回填 close_price → close, change_percent → pct_change
    rows: list of [ts_code, trade_date(date), code, close, pct_change, ...]
    
    优化：多只股票时只发 1 条 CH 查询（code IN (...)），
    而非逐只查询（549批 × 10只 × ~3秒 ≈ 4.5小时 → 549批 × 1次 ≈ 5分钟）。
    """
    if not rows:
        return
    all_codes = list({r[2] for r in rows})
    min_date  = min(r[1] for r in rows).isoformat()
    max_date  = max(r[1] for r in rows).isoformat()
    try:
        if len(all_codes) == 1:
            # 单股票：直接查询（保留原有简单路径）
            code = all_codes[0]
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
                    row[3] = lookup[td][0] if lookup[td][0] is not None else 0.0
                    row[4] = lookup[td][1] if lookup[td][1] is not None else 0.0
                    filled += 1
            if filled:
                print(f"  [CH lookup] filled {filled}/{len(rows)} rows")
        else:
            # 多股票：批量 IN 查询，1次CH调用
            codes_str = ",".join(f"'{c}'" for c in all_codes)
            ch_result = ch_client.query(
                f"SELECT code, trade_date, close_price, change_percent "
                f"FROM stock_daily "
                f"WHERE code IN ({codes_str}) AND trade_date >= '{min_date}' AND trade_date <= '{max_date}'"
            )
            # lookup[code][trade_date] = (close, pct)
            lookup: dict = {}
            for r in ch_result.result_set:
                code_key, td, close_val, pct_val = r[0], r[1], r[2], r[3]
                if code_key not in lookup:
                    lookup[code_key] = {}
                lookup[code_key][td] = (close_val, pct_val)
            filled = 0
            for row in rows:
                code_key = row[2]
                td = row[1]
                if code_key in lookup and td in lookup[code_key]:
                    row[3] = lookup[code_key][td][0] if lookup[code_key][td][0] is not None else 0.0
                    row[4] = lookup[code_key][td][1] if lookup[code_key][td][1] is not None else 0.0
                    filled += 1
            if filled:
                print(f"  [CH lookup batch] filled {filled}/{len(rows)} rows ({len(all_codes)}只)")
    except Exception as e:
        print(f"  [CH lookup failed] {e}")


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
# 9a. 资金流向（东方财富 实时全市场接口）
# ═══════════════════════════════════════════════════════════════

def _http_json(url: str, params: dict, headers: dict, timeout: int = 30) -> dict:
    """用 Python requests 请求 HTTP 接口（push2 等域名 http 可达，https 被封）。"""
    import requests as _requests, json as _json
    r = _requests.get(url, params=params, headers=headers, timeout=timeout)
    r.raise_for_status()
    return r.json()


def _curl_json(url: str, params: dict, headers: dict, timeout: int = 30) -> dict:
    """用 Windows 原生 curl.exe 绕过 Python SSL / MinGW curl 问题。"""
    import subprocess, urllib.parse, json as _json, sys, os
    query = urllib.parse.urlencode(params)
    full_url = url + "?" + query
    hdr_args = []
    for k, v in headers.items():
        hdr_args += ["-H", f"{k}: {v}"]
    # 优先使用 Windows 原生 curl.exe（Git MinGW curl 在 HTTPS 上有兼容问题）
    if sys.platform == "win32" and os.path.exists(r"C:\Windows\System32\curl.exe"):
        curl_bin = r"C:\Windows\System32\curl.exe"
    else:
        curl_bin = "curl"
    cmd = [
        curl_bin, "-s", "-L",
        "--max-time", str(timeout),
        "--noproxy", "*",    # 禁止走系统代理，直接连目标 IP
    ] + hdr_args + [full_url]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 5)
    if r.returncode != 0:
        raise RuntimeError(f"curl failed (rc={r.returncode}): {r.stderr[:200]}")
    return _json.loads(r.stdout)


def fetch_moneyflow_em_realtime() -> list:
    """
    东方财富实时全市场资金流向，用 curl 绕过 Python SSL 问题。
    非交易日自动往前回溯最近 N 个交易日获取数据。
    返回 [ts_code, trade_date, code, close, pct_change,
           net_main, net_main_pct, net_huge, net_big, net_medium, net_small,
           update_time]
    """
    rows = []

    today = datetime.date.today()
    trade_date_candidates = []
    for i in range(14):
        d = today - datetime.timedelta(days=i)
        trade_date_candidates.append(d.strftime("%Y%m%d"))

    url = "http://push2.eastmoney.com/api/qt/clist/get"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer": "https://data.eastmoney.com/",
        "Accept": "application/json",
    }

    PAGE_SIZE = 300
    all_items = []
    detected_date = None

    for td_candidate in trade_date_candidates:
        page = 1
        page_failed = False
        while True:
            params = {
                "fid": "f62",
                "po": 1,
                "pz": PAGE_SIZE,
                "pn": page,
                "np": 1,
                "fltt": 2,
                "invt": 2,
                "ut": "b2884a393a59ad64002292a3e90d46a5",
                "fields": "f12,f14,f2,f3,f62,f72,f75,f78,f66",
                "fs": "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
                "token": os.environ.get("EASTMONEY_TOKEN", "95508b664a61ff632843c2d25ef6dfcb"),
            }
            json_data = None
            for attempt in range(3):
                try:
                    # push2 域名 HTTPS 被封，改用 HTTP + requests（curl 对 http 也失败）
                    json_data = _http_json(url, params, headers, timeout=30)
                    break
                except Exception as e:
                    if attempt < 2:
                        time.sleep(2 ** attempt)
                    else:
                        if page == 1:
                            print(f"  [EM realtime] {td_candidate} 第1页请求失败: {e}")
                            page_failed = True
                        break

            if page_failed:
                break

            try:
                diff = json_data.get("data", {})
            except Exception:
                break

            if not diff:
                break

            total = diff.get("total", 0)
            if page == 1:
                detected_date = td_candidate
                if total == 0:
                    print(f"  [EM realtime] {td_candidate} 无数据，尝试更早日期...")
                    break

            page_items = diff.get("diff", [])
            if not page_items:
                break

            all_items.extend(page_items)

            if page * PAGE_SIZE >= total:
                break
            page += 1
            time.sleep(0.3)

        if all_items:
            break
        all_items = []
        detected_date = None

    if not all_items:
        print("  [EM realtime] 全市场资金流向无数据（可能非交易日）")
        return rows

    print(f"  [EM realtime] 检测到交易日: {detected_date}, 共 {len(all_items)} 只股票")

    try:
        ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
    except Exception:
        ch_client = None

    for item in all_items:
        code_raw = str(item.get("f12", "")).strip().zfill(6)
        close    = to_float(item.get("f2"))
        pct      = to_float(item.get("f3"))
        net_main = to_float(item.get("f62"))
        net_huge = to_float(item.get("f66"))
        net_big  = to_float(item.get("f72"))
        net_med  = to_float(item.get("f75"))
        net_sml  = to_float(item.get("f78"))

        if not code_raw or code_raw == "000000":
            continue
        if code_raw.startswith("900") or code_raw.startswith("200"):
            continue

        if code_raw.startswith(("6", "5", "9")):
            ts_code = code_raw + ".SH"
        else:
            ts_code = code_raw + ".SZ"

        try:
            td = datetime.date(int(detected_date[:4]), int(detected_date[4:6]), int(detected_date[6:8]))
        except (ValueError, IndexError):
            td = datetime.date.today()

        rows.append([
            ts_code, td, code_raw,
            close, pct,
            net_main, 0.0,
            net_huge, net_big, net_med, net_sml,
            datetime.datetime.now(),
        ])

    if ch_client:
        ch_client.close()
    print(f"  资金流向(EM实时全市场): {len(rows)} 条  日期={detected_date}")
    return rows



def fetch_moneyflow_em_hist_single(code: str, market: str = "", max_retries: int = 3) -> list:
    """
    东方财富历史资金流向：单只股票近120天日线。
    接口：https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get
    secid: 1=上交所(含科创板9开头), 0=深交所

    返回 [ts_code, trade_date, code, name, close, pct_change,
           net_main, net_main_pct, net_huge, net_big, net_medium, net_small,
           update_time]
    klines[i] 逗号分隔:
      [0]=日期 [1]=主力净流入(f51) [2]=小单(f52) [3]=中单(f53) [4]=大单(f54) [5]=超大单(f55)
      [6-10]=各档占比 [11]=收盘价 [12]=涨跌幅 [13]=成交量 [14]=成交额
    """
    import requests
    rows = []

    if code.startswith(("6", "5", "9")):
        secid = "1." + code
    else:
        secid = "0." + code

    url = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get"
    params = {
        "secid": secid,
        "klt": 101,
        "lmt": 120,
        "ut": "b2884a393a59ad64002292a3e90d46a5",
        "fields1": "f1,f2,f3,f7",
        "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65",
        "token": os.environ.get("EASTMONEY_TOKEN", "95508b664a61ff632843c2d25ef6dfcb"),
    }
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer": "https://data.eastmoney.com/",
        "Accept": "application/json",
    }

    # 用 _curl_json()（自动选择 Windows 原生 curl.exe，绕过 MinGW curl SSL 兼容问题）

    for attempt in range(max_retries):
        try:
            json_data = _curl_json(url, params, headers, timeout=30)
            break
        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(min(2 ** attempt * 3.0, 20.0))  # 递增退避：3s, 6s, 12s（上限20s）
            else:
                # 网络错误：抛出异常，交给调用方单独处理，不要默默返回空列表
                raise ConnectionError(f"东财历史接口网络异常（{max_retries}次重试均失败）: {e}") from None

    data = json_data.get("data", {})
    if not data:
        return rows

    klines_raw = data.get("klines", [])
    if not klines_raw:
        return rows

    ts_code = code + (".SH" if code.startswith(("6", "5", "9")) else ".SZ")

    for line in klines_raw:
        parts = line.split(",")
        if len(parts) < 13:
            continue
        try:
            # 日期格式：YYYY-MM-DD（不是 YYYYMMDD）
            date_str = parts[0]
            td = datetime.date.fromisoformat(date_str)
            net_main     = to_float(parts[1])   # f51 主力净流入
            net_sml      = to_float(parts[2])   # f52 小单净流入
            net_med      = to_float(parts[3])   # f53 中单净流入
            net_big      = to_float(parts[4])   # f54 大单净流入
            net_huge     = to_float(parts[5])   # f55 超大单净流入
            net_main_pct = to_float(parts[7]) if len(parts) > 7 else 0.0
            close        = to_float(parts[11])
            pct          = to_float(parts[12])

            rows.append([
                ts_code, td, code,    # 0=ts_code, 1=trade_date, 2=code
                close, pct,           # 3=close, 4=pct_change
                net_main, net_main_pct,  # 5=net_main, 6=net_main_pct
                net_huge, net_big,    # 7=net_huge, 8=net_big
                net_med, net_sml,     # 9=net_medium, 10=net_small
                datetime.datetime.now(),  # 11=update_time
            ])
        except (ValueError, IndexError):
            continue

    return rows


def run_em_moneyflow_realtime(args):
    """东方财富实时全市场资金流向（单次请求 ~5500 只）。
    用法: python update_sentiment_data.py --em-moneyflow
    直接走东财接口，不降级。
    """
    print("=== 东方财富 实时资金流向（全市场）===")
    rows = fetch_moneyflow_em_realtime()
    if not rows:
        print("  [无数据，可能非交易日或接口异常]")
        return

    MF_CH_COLS = ["ts_code","trade_date","code","close","pct_change",
                  "net_main","net_main_pct","net_huge","net_big","net_medium","net_small","update_time"]
    MF_MY_COLS = MF_CH_COLS[:]
    MF_UNIQUE  = ["code", "trade_date"]

    _dual_write(
        "stock_sentiment_moneyflow", rows,
        MF_CH_COLS, MF_MY_COLS, MF_UNIQUE,
        dry_run=args.dry_run,
        force=args.force,
    )


def run_em_moneyflow_hist(args):
    """东方财富历史资金流向（push2his 权威接口，与东财网页数据一致）。
    用法:
      python update_sentiment_data.py --em-moneyflow-hist              # 全市场
      python update_sentiment_data.py --em-moneyflow-hist --codes 600519,000001  # 指定股票
    """
    # 确定日期范围
    start_str = args.start_date or "2026-05-07"
    end_str = args.end_date or _latest_trading_day().isoformat()

    # 获取股票列表
    conn = pymysql.connect(**MYSQL_CONFIG)
    cur = conn.cursor()
    if args.codes:
        raw_codes = [c.strip().zfill(6) for c in args.codes.split(",")]
        cur.execute("SELECT code, name FROM stock_info WHERE code IN (" +
                    ",".join(["%s"] * len(raw_codes)) + ")", raw_codes)
        name_map = dict(cur.fetchall())
        codes = [(c, name_map.get(c, c)) for c in raw_codes]
    else:
        cur.execute("SELECT code, name FROM stock_info WHERE code NOT LIKE '8%' AND code NOT LIKE '4%'")
        codes = list(cur.fetchall())
    cur.close()
    conn.close()

    print(f"=== 东方财富 历史资金流向（push2his）: {len(codes)} 只 ===")

    # 获取 CH 客户端用于 close 回填
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

    MF_CH_COLS = ["ts_code","trade_date","code","close","pct_change",
                  "net_main","net_main_pct","net_huge","net_big","net_medium","net_small","update_time"]
    MF_MY_COLS = MF_CH_COLS[:]
    MF_UNIQUE = ["code", "trade_date"]

    ok_count = fail_count = 0
    for i, (code, name) in enumerate(codes):
        label = f"{code}.{name}"
        try:
            rows = fetch_moneyflow_em_hist_single(code)
            if not rows:
                print(f"[{i+1}/{len(codes)}] {label}... ✗ 无数据")
                fail_count += 1
                continue

            # 过滤日期范围 + close=0 的停牌数据
            start_dt = datetime.date.fromisoformat(start_str)
            end_dt = datetime.date.fromisoformat(end_str)
            rows = [r for r in rows
                    if start_dt <= r[1] <= end_dt
                    and r[3] is not None and r[3] != 0]
            if not rows:
                print(f"[{i+1}/{len(codes)}] {label}... ✗ 日期范围内无有效数据")
                fail_count += 1
                continue

            fill_close_pct_from_stock_daily(rows, ch_client)
            rows = [r for r in rows if r[3] is not None and r[3] != 0]
            if not rows:
                print(f"[{i+1}/{len(codes)}] {label}... ✗ close回填后无有效数据")
                fail_count += 1
                continue

            _dual_write("stock_sentiment_moneyflow", rows, MF_CH_COLS, MF_MY_COLS, MF_UNIQUE)
            print(f"[{i+1}/{len(codes)}] {label}... ✓ {len(rows)}条")
            ok_count += 1
        except Exception as e:
            print(f"[{i+1}/{len(codes)}] {label}... ✗ {e}")
            fail_count += 1

        # 每100只输出进度
        if (i + 1) % 100 == 0:
            print(f"  进度: {i+1}/{len(codes)}  成功={ok_count}  失败={fail_count}")

    print(f"=== 完成: 成功={ok_count}  失败={fail_count} ===")


# ═══════════════════════════════════════════════════════════════
# 保留：8. 涨跌停池（涨停/跌停/炸板）
# ╙══════════════════════════════════════════════════════════════

def fetch_zt_data(date_str: str) -> list:
    """获取涨跌停池，返回 zt_rows（供 _dual_write 直接写入 stock_sentiment_zt）"""
    import akshare as ak
    zt_rows = []
    try:
        # 涨停板行情（stock_zt_pool_em 有时混入"60日新高"等非真实涨停）
        df = ak.stock_zt_pool_em(date=date_str)
        if df is not None and not df.empty:
            for _, r in df.iterrows():
                code = str(r.get("代码", "")).zfill(6)
                pct_chg = to_float(r.get("涨跌幅"))
                name = str(r.get("名称", ""))
                # 按板块和ST状态确定涨停阈值（含容差）
                is_st = "ST" in name or "*ST" in name
                if code.startswith("688") or code.startswith("300") or code.startswith("301"):
                    threshold = 19.5
                elif code.startswith("43") or code.startswith("83") or code.startswith("87") \
                        or code.startswith("88") or code.startswith("92") or code.startswith("920"):
                    threshold = 29.5
                elif is_st:
                    threshold = 4.8
                else:
                    threshold = 9.8
                # 过滤：涨幅必须 ≥ 对应板块涨停阈值才算真实涨停
                if pct_chg is None or pct_chg < threshold:
                    continue
                zt_stat = str(r.get("涨停统计", ""))
                lb_count = str(r.get("连板数", ""))
                is_new = 1 if zt_stat.startswith("1/") else 0
                reason = f"{zt_stat}" if zt_stat else "涨停"
                if lb_count and lb_count != "nan":
                    reason += f" 连板{lb_count}"
                zt_rows.append([
                    code, to_date(date_str), code,
                    str(r.get("名称", "")),
                    to_float(r.get("最新价")), pct_chg,
                    "zt", reason[:200], is_new,
                    datetime.datetime.now(),
                ])
            print(f"  涨停板: {len(df)} 条")
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
# westock-data 资金流向（补数据模式）
# 用法：
#   python update_sentiment_data.py --moneyflow-westock                        # 全部缺失股票（westock-data）
#   python update_sentiment_data.py --moneyflow-westock --moneyflow-codes 600519,000001  # 指定股票（westock-data）
#   python update_sentiment_data.py --moneyflow-westock --start-date 2026-05-07 --end-date 2026-05-17  # 日期范围（westock-data）
# ═══════════════════════════════════════════════════════════════

def _parse_date_label(date_label: str):
    """从自然语言日期标签中提取 start/end，格式: '2026年03月01日到2026年03月31日'"""
    m = re.search(r"(\d{4})年(\d{2})月(\d{2})日到(\d{4})年(\d{2})月(\d{2})日", date_label)
    if not m:
        return None, None
    start = datetime.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    end   = datetime.date(int(m.group(4)), int(m.group(5)), int(m.group(6)))
    return start, end


def _split_date_range_backward(start: datetime.date, end: datetime.date):
    """
    [DEPRECATED] 反向分页逻辑（westock 已废弃）
    所以 end_date 需要递减约10天，start_date 相应跟随。
    返回 [(cur_start, cur_end), ...] 列表（从后往前排列）。
    """
    chunks = []
    # 每次向前推约12个自然日（≈10个交易日），使 end_date 恰好是上一页最后一条的日期-1天
    step = datetime.timedelta(days=12)
    chunk_end = end
    while chunk_end >= start:
        chunk_start = max(start, chunk_end - step + datetime.timedelta(days=1))
        chunks.append((chunk_start, chunk_end))
        # 下一段的 end_date 跳到本段 start 之前，避免重叠
        chunk_end = chunk_start - datetime.timedelta(days=1)
    return chunks


def _single_moneyflow_query(codes: list, start: datetime.date, end: datetime.date):
    """通过 westock-data asfund 查询资金流向，返回原始 rows 列表
    codes: ["sh600619", "sz000001"] 格式
    """
    from westock_moneyflow import query_westock, extract_westock_moneyflow
    start_str = start.strftime("%Y-%m-%d")
    end_str = end.strftime("%Y-%m-%d")
    md_text = query_westock(codes, start_str, end_str)
    if not md_text:
        return []
    return extract_westock_moneyflow(md_text)


def _fetch_batch_moneyflow(stocks: list, date_label: str):
    """批量查询多只股票的资金流向（westock-data asfund，最多10只）

    stocks: list of (ts_code, code, name)
    date_label: 自然语言日期标签，如 "2026年03月01日到2026年03月31日"

    Returns:
        dict { code(str): rows_list }
        row 格式: [ts_code, trade_date, code, close, pct_change,
                   net_main, net_main_pct, net_huge, net_big,
                   net_medium, net_small, update_time]
    """
    if not stocks:
        return {}

    start, end = _parse_date_label(date_label)
    if start is None:
        return {cd: [] for _, cd, _ in stocks}

    # ts_code "600619.SH" -> westock code "sh600619"
    code_list = []
    # 建立 westock_ts_code -> (ts_code, plain_code, name) 映射
    ws_map = {}
    for ts, cd, name in stocks:
        parts = ts.split(".")
        if len(parts) == 2:
            ws_key = parts[1].lower() + parts[0]
        else:
            ws_key = ts.lower()
        code_list.append(ws_key)
        ws_map[ws_key.upper()] = (ts, cd, name)

    data = _single_moneyflow_query(code_list, start, end)
    result: dict = {cd: [] for _, cd, _ in stocks}

    # extract_westock_moneyflow 返回 dict 格式:
    # {"SZ000001": {"20260525": {"close": ..., "net_main": ...}, ...}}
    if isinstance(data, dict):
        for ws_ts, date_dict in data.items():
            if ws_ts not in ws_map:
                continue
            ts_code, code, name = ws_map[ws_ts]
            for trade_date_str, vals in date_dict.items():
                trade_date = datetime.datetime.strptime(trade_date_str, "%Y%m%d").date()
                row = [
                    ts_code,                                    # 0: ts_code
                    trade_date,                                 # 1: trade_date (date obj)
                    code,                                       # 2: code
                    vals.get("close", None),                    # 3: close
                    0.0,                                        # 4: pct_change (回填)
                    vals.get("net_main", 0.0),                  # 5: net_main
                    vals.get("net_main_pct", 0.0),              # 6: net_main_pct
                    vals.get("net_huge", 0.0),                  # 7: net_huge
                    vals.get("net_big", 0.0),                   # 8: net_big
                    vals.get("net_medium", 0.0),                # 9: net_medium
                    vals.get("net_small", 0.0),                 # 10: net_small
                    datetime.datetime.now(),                                # 11: update_time
                ]
                result[code].append(row)
    else:
        # 向后兼容：旧版返回 list of rows 格式
        for row in data:
            cd = str(row[2])
            if cd in result:
                result[cd].append(row)

    return result





def run_westock_moneyflow(args):
    """通过 westock-data 补全资金流向，支持双写 CH + MySQL（批量优化版）"""
    # westock-data 无需 token
    # 确定日期范围
    start_str = args.start_date or "2026-05-07"
    end_str = args.end_date or _latest_trading_day().isoformat()

    # 计算期望日期数（自然日，作为数据完整性的近似阈值）
    start_dt = datetime.datetime.strptime(start_str, "%Y-%m-%d").date()
    end_dt = datetime.datetime.strptime(end_str, "%Y-%m-%d").date()
    expected_days = (end_dt - start_dt).days + 1

    # 获取股票列表
    if args.codes:
        raw_codes = [c.strip().zfill(6) for c in args.codes.split(",")]
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
    elif args.moneyflow_codes:
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
        # 从 ClickHouse 查已有数据（不用 MySQL，MySQL 可能残留已从 CH 删除的脏数据）
        # 统计所有记录（含 close=0 的数据），避免重复查询
        ch_temp = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
        result = ch_temp.query(f"""
            SELECT code, COUNT(DISTINCT trade_date) as cnt
            FROM stock_sentiment_moneyflow
            WHERE trade_date >= '{start_str}' AND trade_date <= '{end_str}'
            GROUP BY code
        """)
        existing = {}
        for row in result.named_results():
            code = row['code']
            ts = code + (".SH" if code.startswith(("6", "5")) else ".SZ")
            existing[ts] = row['cnt']
        ch_temp.close()

        conn = pymysql.connect(**MYSQL_CONFIG)
        cur = conn.cursor()
        cur.execute("SELECT code, name FROM stock_info")
        all_name_map = dict(cur.fetchall())
        cur.close()
        conn.close()
        bex = {s for s in all_name_map if s.startswith(("8", "4", "9", "2"))}
        target = []
        for code in all_name_map:
            if code in bex or len(code) != 6:
                continue
            ts = code + (".SH" if code.startswith(("6", "5")) else ".SZ")
            cnt = existing.get(ts, 0)
            # force=True 时忽略 80% 覆盖阈值，强制所有股票重新查询

            if not args.force and cnt >= expected_days * 0.8:
                continue
            target.append((ts, code, all_name_map[code]))
        target.sort()

    print(f"=== Westock 资金流向补全: {len(target)} 只 ===")

    # 格式化为自然语言查询日期标签
    start_fmt = start_str.replace("-", "年", 1).replace("-", "月") + "日"
    end_fmt = end_str.replace("-", "年", 1).replace("-", "月") + "日"
    date_label = f"{start_fmt}到{end_fmt}"

    MF_CH_COLS = ["ts_code","trade_date","code","close","pct_change",
                  "net_main","net_main_pct","net_huge","net_big","net_medium","net_small","update_time"]
    MF_MY_COLS = MF_CH_COLS[:]
    MF_UNIQUE = ["code", "trade_date"]

    # 进度日志文件（供监控服务读取）
    _PROGRESS_LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               'moneyflow_progress.log')

    def _write_progress(batch_idx, total_batches, code, name, status, extra=''):
        """写进度到日志文件，供 moneyflow_progress_server.py 读取"""
        line = f"[{batch_idx+1}/{total_batches}] {code}.{name}... {status}{extra}\n"
        try:
            with open(_PROGRESS_LOG, 'a', encoding='utf-8') as f:
                f.write(line)
        except Exception:
            pass
        # 也写 summary 行（覆盖模式，供 API 快速读取）
        try:
            summary = json.dumps({
                'current_batch': batch_idx + 1,
                'total_batches': total_batches,
                'current_code': code,
                'current_name': name,
                'status': 'running',
                'updated_at': datetime.datetime.now().isoformat(),
            }, ensure_ascii=False)
            with open(_PROGRESS_LOG + '.json', 'w', encoding='utf-8') as f:
                f.write(summary)
        except Exception:
            pass

    # CH 客户端用于回填 close/pct_change
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

    BATCH_SIZE = 10  # 每批 10 只（westock 单次最多 10 只）
    PARALLEL = 5     # 并行 API 调用数
    batches = [target[i:i + BATCH_SIZE] for i in range(0, len(target), BATCH_SIZE)]
    total_batches = len(batches)
    grand_start = datetime.datetime.now()
    ok_count = fail_count = 0
    total_stocks = len(target)

    print(f"  共 {total_batches} 批，每批 ≤{BATCH_SIZE} 只，{PARALLEL} 路并行查询")

    # 初始化进度日志
    try:
        with open(_PROGRESS_LOG, 'w', encoding='utf-8') as f:
            f.write(f"=== Westock 资金流向补全: {total_stocks} 只 ===\n")
            f.write(f"  共 {total_batches} 批，每批 ≤{BATCH_SIZE} 只，{PARALLEL} 路并行\n")
    except Exception:
        pass

    # ── 阶段 1：并行查询（带重试）──
    retry_rescued = 0  # 被重试救回来的批次数
    def _query_batch(batch_stocks, batch_label, batch_idx):
        """单个批次的 API 查询，带 3 次重试（含指数退避）"""
        import time as _time
        nonlocal retry_rescued
        last_err = None
        for attempt in range(3):
            try:
                result = _fetch_batch_moneyflow(batch_stocks, batch_label)
                if result:
                    has_data = any(rows for rows in result.values())
                    if has_data:
                        if attempt > 0:
                            retry_rescued += 1
                            print(f"  [RETRY OK] 第{batch_idx+1}批在第{attempt+1}次重试成功")
                        return result
                    last_err = "全空结果"
                else:
                    last_err = "空返回"
            except Exception as e:
                last_err = str(e)
            if attempt < 2:
                wait = 2 ** attempt  # 1s, 2s
                _time.sleep(wait)
        raise RuntimeError(f"重试3次仍失败: {last_err}")

    all_results = {}  # batch_idx -> {code: rows_list}
    query_start = datetime.datetime.now()
    failed_batch_indices = []  # 重试仍失败的批次
    _completed_count = 0
    _total_to_query = total_batches
    with ThreadPoolExecutor(max_workers=PARALLEL) as executor:
        futures = {executor.submit(_query_batch, batch, date_label, idx): idx
                   for idx, batch in enumerate(batches)}
        for f in as_completed(futures):
            idx = futures[f]
            _completed_count += 1
            try:
                all_results[idx] = f.result()
            except Exception as e:
                print(f"  [ERROR] 第 {idx+1} 批查询失败: {e}")
                all_results[idx] = {}
                failed_batch_indices.append(idx)
            # 每 10 批或最后一批打印进度
            if _completed_count % 10 == 0 or _completed_count == _total_to_query:
                elapsed = (datetime.datetime.now() - query_start).total_seconds()
                speed = _completed_count / elapsed if elapsed > 0 else 0
                eta = (_total_to_query - _completed_count) / speed if speed > 0 else 0
                print(f"  [查询进度] {_completed_count}/{_total_to_query} 批完成"
                      f"  已用 {elapsed:.0f}s  预计剩余 {eta:.0f}s"
                      f"  失败 {len(failed_batch_indices)}")
    query_elapsed = (datetime.datetime.now() - query_start).total_seconds()
    failed_batch_count = len(failed_batch_indices)
    print(f"  并行查询完成，耗时 {query_elapsed:.1f}s（失败批次: {failed_batch_count}, 重试救回: {retry_rescued}）")

    # ── 阶段 2：顺序回填 close/pct_change + 双写（确保写入安全）──
    write_start = datetime.datetime.now()
    for idx in range(total_batches):
        done = idx + 1
        batch = batches[idx]
        code_rows_map = all_results.get(idx, {})

        # 收集有数据的股票（无回退重试，已修复解析逻辑）
        batch_all_rows = []
        no_data_stocks = []
        for ts_code, code, name in batch:
            rows = code_rows_map.get(code, [])
            if rows:
                batch_all_rows.append((ts_code, code, name, rows))
            else:
                no_data_stocks.append((ts_code, code, name))

        # 报告无数据
        for ts_code, code, name in no_data_stocks:
            label = f"{code}.{name}" if name != code else code
            print(f"[{done}/{total_batches}] {label}... ✗ 无数据")
            _write_progress(idx, total_batches, code, name, "✗ 无数据")
            fail_count += 1

        if not batch_all_rows:
            continue

        # 回填 close/pct_change
        all_rows_flat = [row for _, _, _, rows in batch_all_rows for row in rows]
        fill_close_pct_from_stock_daily(all_rows_flat, ch_client)

        # 双写（逐只，保留主键冲突处理）
        for ts_code, code, name, rows in batch_all_rows:
            rows = [r for r in rows if r[3] is not None and r[3] != 0
                    and not (r[5] == 0 and r[7] == 0 and r[8] == 0 and r[9] == 0 and r[10] == 0)]
            if not rows:
                continue
            label = f"{code}.{name}" if name != code else code
            _dual_write(
                "stock_sentiment_moneyflow", rows,
                MF_CH_COLS, MF_MY_COLS, MF_UNIQUE,
                dry_run=args.dry_run,
            )
            print(f"[{done}/{total_batches}] {label}... ✓ {len(rows)}条")
            _write_progress(idx, total_batches, code, name, f"✓ {len(rows)}条")
            ok_count += 1

    write_elapsed = (datetime.datetime.now() - write_start).total_seconds()
    print(f"  写入完成，耗时 {write_elapsed:.1f}s")

    # 标记完成
    try:
        with open(_PROGRESS_LOG + '.json', 'w', encoding='utf-8') as f:
            f.write(json.dumps({
                'current_batch': total_batches,
                'total_batches': total_batches,
                'status': 'done',
                'finished_at': datetime.datetime.now().isoformat(),
            }, ensure_ascii=False))
    except Exception:
        pass

    ch_client.close()
    grand_elapsed = (datetime.datetime.now() - grand_start).total_seconds()
    print(f"\n{'=' * 60}")
    print(f"  Westock 资金流向完成（并行版）")
    print(f"  总耗时 {grand_elapsed:.1f}s ({grand_elapsed/60:.1f}min)")
    print(f"  成功 {ok_count} 只  失败 {fail_count} 只")
    print(f"{'=' * 60}")


def _fetch_stock_moneyflow(args, ts_code: str, code: str, name: str, months: list):
    """单只股票的全量资金流向抓取（westock-data，供并行调用）

    Returns:
        (ts_code, code, name, rows_list)
    """
    from westock_moneyflow import query_westock, extract_westock_moneyflow
    try:
        # ts_code "600619.SH" -> westock code "sh600619"
        parts = ts_code.split(".")
        wcode = (parts[1].lower() + parts[0]) if len(parts) == 2 else ts_code.lower()

        seen_dates: set = set()
        all_rows = []
        for m_start, m_end in months:
            start_str = m_start.strftime("%Y-%m-%d")
            end_str = m_end.strftime("%Y-%m-%d")
            try:
                md_text = query_westock([wcode], start_str, end_str)
                if not md_text:
                    continue
                rows = extract_westock_moneyflow(md_text)
                for row in rows:
                    td = row[1]
                    if td not in seen_dates:
                        seen_dates.add(td)
                        all_rows.append(row)
            except Exception as e:
                print(f"  [WARN] {code} [{start_str}->{end_str}] 抓取失败: {e}")
        all_rows.sort(key=lambda r: r[1])
        return (ts_code, code, name, all_rows)
    except Exception as e:
        print(f"  [ERROR] {code}.{name} 整体失败: {e}")
        return (ts_code, code, name, [])


def run_westock_refresh(args):
    """Westock 全量重刷：多线程并行查询，覆盖全部已有数据
    用法：
      python update_sentiment_data.py --moneyflow-refresh              # westock-data 全量重刷
      python update_sentiment_data.py --moneyflow-refresh --refresh-codes 600519,000001  # westock-data 指定股票
      python update_sentiment_data.py --moneyflow-refresh --refresh-start 2026-01-01   # westock-data 指定起始月
    """
    # westock-data 无需 token
    # ── 确定日期范围 ─────────────────────────────────────────────
    start_str = args.refresh_start  # e.g. "2025-11-01"
    end_str = _latest_trading_day().isoformat()

    start_dt = datetime.datetime.strptime(start_str, "%Y-%m-%d").date()
    end_dt = _latest_trading_day()

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

    print(f"=== Westock 全量重刷: {len(months)} 个月 [{start_str} ~ {end_str}] ===")

    # ── 获取股票列表 ─────────────────────────────────────────────
    conn = pymysql.connect(**MYSQL_CONFIG)
    cur = conn.cursor()

    if args.codes:
        raw_codes = [c.strip().zfill(6) for c in args.codes.split(",")]
        cur.execute("SELECT code, name FROM stock_info WHERE code IN (" +
                    ",".join(["%s"] * len(raw_codes)) + ")", raw_codes)
    elif args.refresh_codes:
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

    MF_CH_COLS = ["ts_code","trade_date","code","close","pct_change",
                  "net_main","net_main_pct","net_huge","net_big","net_medium","net_small","update_time"]
    MF_MY_COLS = MF_CH_COLS[:]
    MF_UNIQUE  = ["code", "trade_date"]

    grand_start = datetime.datetime.now()
    total_ok = total_fail = total_rows = 0
    done = 0

    # ── 多线程并行查询 westock-data ──────────────────────────
    # 每只股票内部按月串行（同一 API 并发无意义），不同股票间并行
    WORKERS = 5   # westock 限流约 7-8 次/秒，5 并发留余量（每只股票内部按月串行）

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
            # 过滤 close=0 的停牌股（fill_close 无法回填已停牌股票的收盘价）
            # 行格式: [ts_code(0), trade_date(1), code(2), close(3), pct_change(4), ...]
            # ⚠️ 不能用 r[4]（pct_change可为0），必须用 r[3]（close）
            rows = [r for r in rows if r[3] is not None and r[3] != 0]
            if not rows:
                print(f"[{done}/{len(target)}] {label}... ✗ 停牌无收盘价")
                total_fail += 1
                continue

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
    print(f"  Westock 全量重刷完成")
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
    parser.add_argument("--moneyflow-westock", action="store_true", help="westock-data 补全资金流向")
    parser.add_argument("--moneyflow-refresh", action="store_true", help="westock-data 全量重刷（按月分批，覆盖全部已有数据）")
    parser.add_argument("--refresh-start", default="2025-11-01", help="重刷起始月 YYYY-MM-DD（默认 2025-11-01）")
    parser.add_argument("--refresh-codes", default=None, help="仅重刷指定股票，逗号分隔（默认全量）")
    parser.add_argument("--em-moneyflow", action="store_true", help="东方财富实时全市场资金流向（单次请求 ~5500 只）")
    parser.add_argument("--em-moneyflow-hist", action="store_true", help="东方财富历史资金流向（批量并发，全市场 ~5500 只）")
    parser.add_argument("--codes",       default=None, help="指定股票代码列表，逗号分隔；所有模块仅处理这些股票")
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
        if args.codes:
            codes = [c.strip().zfill(6) for c in args.codes.split(",")]
        elif args.fund_holder_codes:
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
        if args.codes:
            codes = [c.strip().zfill(6) for c in args.codes.split(",")]
        elif args.shareholder_codes:
            codes = [c.strip().zfill(6) for c in args.shareholder_codes.split(",")]
        usb.run_batch(conn, codes, max_workers=8)
        usb.check_status(conn)
        conn.close()

    # 13. 个股新闻+事件标签+情感采集
    if args.news:
        import update_news_data as und
        conn = und.get_conn()
        codes = und.get_batch_stocks(conn, force_all=args.force)
        if args.codes:
            codes = [c.strip().zfill(6) for c in args.codes.split(",")]
        elif args.news_codes:
            codes = [c.strip().zfill(6) for c in args.news_codes.split(",")]
        und.run_batch(codes, days=args.news_days, workers=4)

    if args.em_moneyflow:
        run_em_moneyflow_realtime(args)
        return

    if args.em_moneyflow_hist:
        run_em_moneyflow_hist(args)
        return

    if args.moneyflow_westock:
        run_westock_moneyflow(args)
        return

    if args.moneyflow_refresh:
        run_westock_refresh(args)
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
    except (ValueError, IndexError):
        print(f"[WARN] 无效日期格式: {date_str}，跳过")
        return

    if req_date > today:
        print(f"[SKIP] 日期 {date_str} 是未来日期，跳过 (today={today.isoformat()})")
        return

    # 智能回退：当天 17:00 前数据未发布，自动用昨天
    if req_date == today:
        now = datetime.datetime.now()
        if now.hour < 17:
            yesterday = today - datetime.timedelta(days=1)
            date_str = yesterday.strftime("%Y%m%d")
            req_date = yesterday
            print(f"[INFO] 当天 {now.hour:02d}:{now.minute:02d}，"
                  f"情绪数据（龙虎榜/融资融券等）通常收盘后 ~17:00 发布，"
                  f"自动回退到 {date_str[:4]}-{date_str[4:6]}-{date_str[6:]}")

    date_disp = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:]}"
    start_str = date_str
    end_str   = date_str

    # ── 解析目标股票代码（--codes 参数）─────────────────
    target_codes = None   # None = 全量；set = 只处理这些代码
    if args.codes:
        target_codes = {c.strip().zfill(6) for c in args.codes.split(",")}
        print(f"[INFO] 指定股票模式: {len(target_codes)} 只")

    def _get_code_pos(rows):
        """自动检测 code 在 tuple 中的列位置：找第一个 6 位纯数字字符串"""
        if not rows:
            return None
        first = rows[0]
        if isinstance(first, dict):
            return "dict"
        # tuple: 找第一个 6 位纯数字字符串
        for i, v in enumerate(first):
            if isinstance(v, str) and len(v) == 6 and v.isdigit():
                return i
        return None  # 找不到

    def _filter(rows):
        """按 target_codes 过滤行"""
        if not target_codes or not rows:
            return rows
        pos = _get_code_pos(rows)
        if pos is None:
            return rows
        if pos == "dict":
            return [r for r in rows if r.get("code") in target_codes]
        return [r for r in rows if r[pos] in target_codes]

    start = datetime.datetime.now()
    print("=" * 60)
    print(f"  市场情绪数据采集  {date_disp}")
    print("=" * 60)

    # ── 1. 龙虎榜详情 ─────────────────────────────────────────
    if not args.skip_lhb:
        print("[INFO] 龙虎榜详情...")
        rows = fetch_lhb_detail(start_str, end_str)
        rows = _filter(rows)
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
        rows = _filter(rows)
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
        rows = _filter(rows)
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
        rows = _filter(rows)
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
        rows = _filter(rows)
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
        rows = _filter(rows)
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
        rows = _filter(rows)
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
        zt_rows = _filter(zt_rows)
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
        mf_rows = _filter(mf_rows)
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
        notice_rows = _filter(notice_rows)
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
    if args.codes:
        codes = [c.strip().zfill(6) for c in args.codes.split(",")]
        print(f"=== 历史资金流向补全: 指定 {len(codes)} 只股票 ===")
    elif args.moneyflow_codes:
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

    moneyflow_columns = ["ts_code","trade_date","code","close",
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
        rows = _filter(rows)
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

