#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_shenwan_index.py
================================
Fetch Shenwan Level-1 industry index daily data via akshare index_hist_sw(),
write into index_daily table (same table as broad-market indices).

Data source: akshare.index_hist_sw(symbol, period='day')
  symbol: Shenwan L1 industry code, e.g. 801030 (Basic Chemicals)

Shenwan L1 industry codes (28 total, some discontinued):
  801010 Agri/Forestry/Fishery    801020 Mining (discontinued 2021)
  801030 Basic Chemicals           801040 Steel
  801050 Non-ferrous Metals       801080 Electronics
  801110 Household Appliances     801120 Food & Beverage
  801130 Textiles & Apparel       801140 Light Industry
  801150 Pharma & Biotech        801160 Utilities
  801170 Transportation           801180 Real Estate
  801200 Commercial Retail       801210 Social Services
  801220 Oil & Petrochem (discontinued 2014)
  801230 Conglomerate            801240 Construction & Decoration
  801250 Power Equipment         801260 Defense & Military
  801270 Computer                801280 Media
  801300 Telecom                 801330 Automobile

Only fetch active indices (data up to >= 2024).

Dependencies: pip install akshare pymysql clickhouse-connect pandas
Usage:
  python update_shenwan_index.py                # incremental update all active
  python update_shenwan_index.py --code 801030  # only Basic Chemicals
  python update_shenwan_index.py --force         # force full re-fetch
  python update_shenwan_index.py --summary      # show data summary
"""

import sys
import os
import time
import argparse
from datetime import datetime, timedelta
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from db_helper import StockDailyDB, to_float, to_int
from db_config import get_backend_label

# Shenwan L1 industry index list: (code, name_en, name_zh, enabled)
SHENWAN_L1 = [
    ("801010", "AgriForestry",   "农林牧渔",  True),
    ("801020", "Mining",         "矿业",      False),  # discontinued 2021-12
    ("801030", "BasicChemicals", "基础化工",  True),
    ("801040", "Steel",          "钢铁",      True),
    ("801050", "NonFerrous",     "有色金属",  True),
    ("801080", "Electronics",     "电子",      True),
    ("801110", "HouseholdAppl",  "家用电器",  True),
    ("801120", "FoodBeverage",   "食品饮料",  True),
    ("801130", "TextileApparel", "纺织服饰",  True),
    ("801140", "LightIndustry",  "轻工制造",  True),
    ("801150", "PharmaBio",      "医药生物",  True),
    ("801160", "Utilities",      "公用事业",  True),
    ("801170", "Transportation",  "交通运输",  True),
    ("801180", "RealEstate",     "房地产",    True),
    ("801200", "ComRetail",      "商贸零售",  True),
    ("801210", "SocialServices", "社会服务",  True),
    ("801220", "OilPetrochem",   "石油石化",  False),  # discontinued 2014-02
    ("801230", "Conglomerate",  "综合",      True),
    ("801240", "Construction",   "建筑装饰",  False),  # moved to 801720
    ("801250", "PowerEquipment",  "电力设备",  True),
    ("801260", "DefenseMilitary", "国防军工",  True),
    ("801270", "Computer",       "计算机",    True),
    ("801280", "Media",          "传媒",      True),
    ("801300", "Telecom",        "通信",      False),  # moved to 801770
    ("801330", "Automobile",     "汽车",      False),  # moved to 801880
    # ---- New codes (2021 Shenwan revision) ----
    ("801710", "BuildingMaterials", "建筑材料", True),
    ("801720", "ConstructionDeco",  "建筑装饰", True),
    ("801730", "PowerEquipment2",   "电力设备", False),  # old 801250 still works
    ("801740", "DefenseMilitary2",  "国防军工", False),  # old 801260 still works
    ("801750", "Computer2",         "计算机",   False),  # old 801270 still works
    ("801760", "Media2",            "传媒",     False),  # old 801280 still works
    ("801770", "Telecom2",          "通信",     False),  # old 801300 still works
    ("801780", "Bank",              "银行",     True),
    ("801880", "Automobile2",       "汽车",     True),
    ("801890", "Mechanical",        "机械设备", True),
    ("801950", "Coal",              "煤炭",     True),
    ("801960", "OilPetrochem2",     "石油石化", True),
    ("801970", "Environmental",     "环保",     True),
    ("801980", "BeautyCare",        "美容护理", True),
]

ACTIVE_INDICES = [(c, ne, nz) for c, ne, nz, e in SHENWAN_L1 if e]


def fetch_industry_hist(symbol, start_date, end_date, max_retries=3):
    """Fetch Shenwan industry index daily bars via akshare index_hist_sw.

    Returns: list of dicts  or  empty list on failure.
    Column names from akshare are in Chinese: 代码 日期 收盘 开盘 最高 最低 成交量 成交额
    """
    import akshare as ak

    for attempt in range(1, max_retries + 1):
        try:
            df = ak.index_hist_sw(symbol=symbol, period="day")
            if df is None or df.empty:
                return []

            # Normalize date column
            df["_date"] = pd.to_datetime(df["日期"]).dt.date

            start = datetime.strptime(start_date, "%Y-%m-%d").date()
            end = datetime.strptime(end_date, "%Y-%m-%d").date()
            df = df[(df["_date"] >= start) & (df["_date"] <= end)]

            records = []
            for _, row in df.iterrows():
                records.append({
                    "code":         symbol,
                    "trade_date":   str(row["日期"]),
                    "name":         "",   # filled later
                    "open_price":   to_float(row["开盘"]),
                    "close_price":  to_float(row["收盘"]),
                    "high_price":   to_float(row["最高"]),
                    "low_price":    to_float(row["最低"]),
                    "volume":       to_int(row["成交量"]),
                    "amount":       to_float(row["成交额"]),
                    "change_percent": None,
                    "change_amount": None,
                    "turn_over_rate": None,
                    "pe_ttm":        None,
                    "pb":            None,
                })
            return records

        except Exception as e:
            if attempt < max_retries:
                time.sleep(2)
            else:
                print(f"    [ERROR] fetch {symbol} failed: {e}")
                return []


def process_industry(db, code, name_en, name_zh, start_date, end_date, force=False):
    """Fetch + write a single industry index into index_daily."""

    # Step 1: decide start_date from DB latest (unless --force)
    actual_start = start_date
    if not force:
        latest_date = db.get_latest_date_by_code(code, table="index")
        if latest_date:
            # latest_date may be date obj or string
            if hasattr(latest_date, "strftime"):
                next_day = (latest_date + timedelta(days=1)).strftime("%Y-%m-%d")
                latest_str = latest_date.strftime("%Y-%m-%d")
            else:
                latest_str = str(latest_date)
                try:
                    d = datetime.strptime(latest_str, "%Y-%m-%d").date()
                    next_day = (d + timedelta(days=1)).strftime("%Y-%m-%d")
                except Exception:
                    next_day = start_date
            if next_day > end_date:
                print(f"  [{code}] {name_zh} already up-to-date ({latest_str}), skip")
                return 0, 0
            print(f"  [{code}] {name_zh} | DB latest: {latest_str}, incremental from {next_day}")
            actual_start = next_day
        else:
            print(f"  [{code}] {name_zh} | no DB data, start from {start_date}")

    # Step 2: fetch from akshare
    rows = fetch_industry_hist(code, actual_start, end_date)
    if not rows:
        print(f"  [{code}] {name_zh} | no new data from akshare")
        return 0, 0

    # Step 3: fill name + compute change_percent / change_amount
    prev_close = None
    for r in rows:
        r["name"] = name_zh
        cur_close = r["close_price"]
        if prev_close and cur_close:
            r["change_amount"] = round(cur_close - prev_close, 2)
            if prev_close != 0:
                r["change_percent"] = round((cur_close - prev_close) / prev_close * 100, 2)
        prev_close = cur_close

    # Step 4: upsert into index_daily
    try:
        inserted = db.upsert_daily(rows, table="index")
        print(f"  [{code}] {name_zh} | inserted/updated {inserted} rows (fetched {len(rows)})")
        return inserted, len(rows)
    except Exception as e:
        print(f"    [ERROR] write failed: {e}")
        return 0, len(rows)


def show_summary():
    """Print summary of index_daily data for all Shenwan L1 indices."""
    db = StockDailyDB()
    try:
        print(f"\n{'=' * 70}")
        print(f"  Shenwan L1 Industry Index Summary (index_daily, backend: {get_backend_label()})")
        print(f"{'=' * 70}")
        print(f"  {'Code':<10s} {'Name':<12s} {'Rows':>8s} {'MinDate':<12s} {'MaxDate':<12s}")
        print(f"  {'-' * 60}")

        for code, name_en, name_zh, enabled in SHENWAN_L1:
            if not enabled:
                print(f"  {code:<10s} {name_zh:<12s} {'DISCONTINUED':>8s}")
                continue

            if db.backend == "clickhouse":
                ch_table = db.CH_INDEX_TABLE
                r = db.ch_client.query(
                    f"SELECT count() as cnt, MIN(trade_date) as min_d, MAX(trade_date) as max_d "
                    f"FROM {ch_table} FINAL WHERE code = '{code}'"
                )
                row = r.result_rows[0] if r.result_rows else (0, None, None)
                cnt = row[0]
                min_d = str(row[1]) if row[1] else "-"
                max_d = str(row[2]) if row[2] else "-"
            else:
                with db.mysql_conn.cursor() as cur:
                    cur.execute(
                        "SELECT COUNT(*) as cnt, MIN(trade_date) as min_d, MAX(trade_date) as max_d "
                        "FROM index_daily WHERE code = %s", (code,)
                    )
                    row = cur.fetchone()
                    cnt = row["cnt"]
                    min_d = str(row["min_d"]) if row["min_d"] else "-"
                    max_d = str(row["max_d"]) if row["max_d"] else "-"

            flag = "" if cnt > 0 else " (no data)"
            print(f"  {code:<10s} {name_zh:<12s} {cnt:>8,d} {min_d:<12s} {max_d:<12s}{flag}")

        print(f"  {'-' * 60}")
        print(f"{'=' * 70}\n")
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser(
        description="Update Shenwan L1 industry index daily data (akshare index_hist_sw)"
    )
    parser.add_argument("--start-date", type=str, default=None,
                        help="Start date (default: next day after latest DB record, or 1999-01-01 if empty)")
    parser.add_argument("--end-date",   type=str, default=None,
                        help="End date (default: today)")
    parser.add_argument("--code",       type=str, default=None,
                        help="Only update specified codes, comma-separated (e.g. 801030,801080)")
    parser.add_argument("--force",      action="store_true",
                        help="Force full re-fetch (ignore incremental check)")
    parser.add_argument("--summary",   action="store_true",
                        help="Only show data summary, do not update")
    args = parser.parse_args()

    end_date = args.end_date or datetime.now().strftime("%Y-%m-%d")
    default_start = "1999-01-01"

    # Determine which indices to update
    if args.code:
        code_list = [c.strip() for c in args.code.split(",")]
        indices = [(c, ne, nz) for c, ne, nz, e in SHENWAN_L1 if c in code_list and e]
        if not indices:
            print(f"[ERROR] No active indices matched: {args.code}")
            return 1
    else:
        indices = ACTIVE_INDICES

    if args.summary:
        show_summary()
        return 0

    print(f"\n{'=' * 70}")
    print(f"  Shenwan L1 Industry Index Update (akshare index_hist_sw)")
    print(f"  Date range: {args.start_date or default_start} ~ {end_date}")
    print(f"  Indices: {len(indices)}")
    print(f"{'=' * 70}\n")

    db = StockDailyDB()
    total_start = time.time()
    total_inserted = 0
    total_fetched = 0

    try:
        for i, (code, name_en, name_zh) in enumerate(indices, 1):
            print(f"  [{i}/{len(indices)}] Processing: {code} {name_zh}")
            start = args.start_date or default_start
            inserted, fetched = process_industry(
                db, code, name_en, name_zh, start, end_date, force=args.force
            )
            total_inserted += inserted
            total_fetched += fetched
            if i < len(indices):
                time.sleep(0.3)

        elapsed = time.time() - total_start
        print(f"\n{'=' * 70}")
        print(f"  Done: {total_inserted} rows inserted/updated (fetched {total_fetched})")
        print(f"  Elapsed: {elapsed:.1f}s")
        print(f"{'=' * 70}\n")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(main())
