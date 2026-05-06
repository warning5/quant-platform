#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_index_daily_baostock.py
================================
使用 Baostock 获取指数日线数据，写入 stock_daily 表（通过 db_helper，支持 ClickHouse/MySQL）。

与个股数据区分：
  - stock_info 表不包含指数记录，指数数据仅写入 stock_daily
  - stock_daily 中通过 market='SH'/'SZ' + code 前缀区分（指数代码无 stock_info 记录）
  - 本脚本直接用硬编码的指数列表，不依赖 stock_info

支持的主要指数：
  - 沪深300 (000300.SH)
  - 上证指数 (000001.SH)
  - 中证500 (000905.SH)
  - 中证1000 (000852.SH)
  - 上证50 (000016.SH)
  - 创业板指 (399006.SZ)
  - 科创50 (000688.SH)
  - 中证红利 (000022.SH)
  - 国证2000 (399303.SZ)

数据源: Baostock (免费，无需 token)，备用: 腾讯证券
  指数代码格式: sh.000300 / sz.399006

用法:
  # 更新全部指数（自动检测起始日期）
  python update_index_daily_baostock.py

  # 指定日期范围
  python update_index_daily_baostock.py --start-date 2020-01-01 --end-date 2026-04-18

  # 只更新指定指数
  python update_index_daily_baostock.py --code 000300
  python update_index_daily_baostock.py --code 000001,000300,000905

  # 查看当前指数数据概况
  python update_index_daily_baostock.py --summary

  # 切换数据库后端
  DB_BACKEND=mysql python update_index_daily_baostock.py

依赖: pip install pymysql clickhouse-connect baostock
"""

import sys
import os
import time
import re
import json
import argparse
from datetime import datetime, timedelta

import requests
import baostock as bs

# 切换到脚本目录，确保能 import db_helper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from db_helper import StockDailyDB
from db_config import get_backend_label

# 腾讯证券接口（备用数据源，用于 Baostock 未收录的指数如科创50）
QQ_FINANCE_URL = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get"
QQ_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://finance.qq.com/",
}


# ─── 内置指数列表 ────────────────────────────────────────────
# (code, name, market, baostock_code)
BUILTIN_INDICES = [
    ("000001", "上证指数",     "SH", "sh.000001"),
    ("000016", "上证50",       "SH", "sh.000016"),
    ("000022", "中证红利",     "SH", "sh.000022"),
    ("000300", "沪深300",      "SH", "sh.000300"),
    ("000688", "科创50",       "SH", "sh.000688"),
    ("000852", "中证1000",     "SH", "sh.000852"),
    ("000905", "中证500",      "SH", "sh.000905"),
    ("399001", "深证成指",     "SZ", "sz.399001"),
    ("399006", "创业板指",     "SZ", "sz.399006"),
    ("399303", "国证2000",     "SZ", "sz.399303"),
]

# 构建 code -> info 的映射
INDEX_MAP = {idx[0]: idx for idx in BUILTIN_INDICES}


def get_db_connection():
    return StockDailyDB()





def code_to_baostock(code):
    """根据指数代码查找 Baostock 格式"""
    info = INDEX_MAP.get(code)
    return info[3] if info else None


def code_to_qq(code, market):
    """根据指数代码和交易所生成腾讯接口前缀: sh000688 / sz399006"""
    prefix = "sh" if market == "SH" else "sz"
    return f"{prefix}{code}"


def split_date_ranges_qq(start_date, end_date, months=6):
    """将日期范围拆分成多段（腾讯接口单次最多640条）"""
    start = datetime.strptime(start_date, "%Y-%m-%d").date()
    end = datetime.strptime(end_date, "%Y-%m-%d").date()
    ranges = []
    current = start
    while current <= end:
        seg_end = min(current + timedelta(days=int(months * 31)), end)
        ranges.append((current.strftime("%Y-%m-%d"), seg_end.strftime("%Y-%m-%d")))
        current = seg_end + timedelta(days=1)
    return ranges


def fetch_index_history_qq(code, market, start_date, end_date):
    """使用腾讯证券接口获取指数日线数据（Baostock 备用数据源）
    
    返回格式与 fetch_index_history 相同:
    [[date, open, high, low, close, volume, amount, preclose, turn, pctChg], ...]
    """
    qq_code = code_to_qq(code, market)
    ranges = split_date_ranges_qq(start_date, end_date)
    all_rows = []

    for seg_start, seg_end in ranges:
        params = {
            "_var": "kline_dayqfq",
            "param": f"{qq_code},day,{seg_start},{seg_end},640,qfq",
            "r": "0.1",
        }
        try:
            r = requests.get(QQ_FINANCE_URL, params=params, headers=QQ_HEADERS, timeout=15)
            r.raise_for_status()

            text = re.sub(r'^kline_dayqfq=', '', r.text.strip())
            d = json.loads(text)

            if d.get('code') != 0:
                continue

            stock_data = d.get('data', {}).get(qq_code, {})
            # 指数不需要复权，直接使用 day（不复权）数据
            rows = stock_data.get('day') or stock_data.get('qfqday') or []

            for row in rows:
                # 腾讯 day 格式: [date, open, close, high, low, volume, {}, pctChg, amount, ...]
                if not row or not row[0] or not row[2]:
                    continue
                trade_date = row[0]
                open_price = row[1]
                close_price = row[2]
                high_price = row[3]
                low_price = row[4]
                volume = row[5]
                pct_chg = row[7] if len(row) > 7 and row[7] else ""
                amount = row[8] if len(row) > 8 and row[8] else "0"
                # 腾讯接口对指数不提供换手率
                turn = ""

                all_rows.append([trade_date, open_price, high_price, low_price, close_price,
                                 volume, amount, "", turn, pct_chg])
        except Exception as e:
            print(f"    [WARN] 腾讯接口请求失败 {seg_start}~{seg_end}: {e}")
            continue

    return all_rows


def fetch_index_history(bs_code, start_date, end_date, max_retries=3):
    """使用 Baostock 获取单个指数的历史行情"""
    for attempt in range(1, max_retries + 1):
        try:
            rs = bs.query_history_k_data_plus(
                bs_code,
                "date,open,high,low,close,volume,amount,preclose,turn,pctChg",
                start_date=start_date,
                end_date=end_date,
                frequency="d",
                adjustflag="3",  # 不复权
            )
            rows = []
            while rs.next():
                row = rs.get_row_data()
                if row and row[0] and row[4]:  # date 和 close 非空
                    rows.append(row)
            return rows
        except Exception as e:
            if attempt < max_retries:
                print(f"    [WARN] 第{attempt}次请求失败: {e}, 重试中...")
                time.sleep(2)
                # 尝试重新登录
                try:
                    bs.logout()
                    bs.login()
                except:
                    pass
            else:
                print(f"    [ERROR] 获取失败: {e}")
                return []


def build_row_dict(code, name, market, row):
    """将 Baostock/腾讯 返回的原始 row 转换为 db_helper upsert_daily 接受的 dict。

    row 格式: [date, open, high, low, close, volume, amount, preclose, turn, pctChg]
    """
    def _f(v): return float(v) if v not in (None, "", "null") else None
    def _i(v): return int(float(v)) if v not in (None, "", "null") else None

    date_str      = row[0]
    open_price    = _f(row[1])
    high_price    = _f(row[2])
    low_price     = _f(row[3])
    close_price   = _f(row[4])
    volume        = _i(row[5])
    amount        = _f(row[6])
    pre_close     = _f(row[7])
    turnover_rate = _f(row[8])
    change_pct    = _f(row[9])

    change_amount = None
    if close_price is not None and pre_close is not None and pre_close != 0:
        change_amount = round(close_price - pre_close, 2)

    return {
        "code":          code,
        "trade_date":    date_str,
        "name":          name,
        "open_price":    open_price,
        "close_price":   close_price,
        "high_price":    high_price,
        "low_price":     low_price,
        "pre_close":     pre_close,
        "volume":        volume,
        "amount":        amount,
        "change_percent": change_pct,
        "change_amount": change_amount,
        "turnover_rate": turnover_rate,
        "pe_ttm":        None,
        "pb":            None,
    }


def process_index(db, code, start_date, end_date, force=False):
    """处理单个指数的数据更新（使用 db_helper）"""
    info = INDEX_MAP.get(code)
    if not info:
        print(f"  [SKIP] 未知指数代码: {code}")
        return 0, 0

    code, name, market, bs_code = info

    # 检查最新日期（通过 db_helper，查 index_daily 表）
    if not force:
        # index_daily 中 code 为纯数字格式（如 000001）
        latest_date = db.get_latest_date_by_code(code, table="index")
        if latest_date:
            if hasattr(latest_date, 'strftime'):
                db_next = (latest_date + timedelta(days=1)).strftime("%Y-%m-%d")
                db_max_str = latest_date.strftime("%Y-%m-%d")
            else:
                db_max_str = str(latest_date)
                db_next = (datetime.strptime(db_max_str, "%Y-%m-%d").date() + timedelta(days=1)).strftime("%Y-%m-%d")
            if db_next > end_date:
                print(f"  [{code}] {name} 已是最新 ({db_max_str}), 跳过")
                return 0, 0
            print(f"  [{code}] {name} | 数据库最新: {db_max_str}, 从 {db_next} 增量更新")
            start_date = db_next
        else:
            print(f"  [{code}] {name} | 无历史数据, 从 {start_date} 开始")
    else:
        print(f"  [{code}] {name} | 强制全量, 从 {start_date} 开始")

    # 拉取数据
    rows = fetch_index_history(bs_code, start_date, end_date)
    source = "Baostock"

    # Baostock 无数据时，尝试腾讯证券接口
    if not rows:
        print(f"  [{code}] {name} | Baostock 无数据, 尝试腾讯证券接口...")
        rows = fetch_index_history_qq(code, market, start_date, end_date)
        source = "腾讯证券"

    if not rows:
        print(f"  [{code}] {name} | 无新增数据")
        return 0, 0

    # 构建 dict 列表，通过 db_helper 写入 index_daily 表（分表存储）
    row_dicts = [build_row_dict(code, name, market, r) for r in rows]
    try:
        inserted = db.upsert_daily(row_dicts, table="index")
    except Exception as e:
        print(f"    [ERROR] 写入失败: {e}")
        return 0, len(rows)

    print(f"  [{code}] {name} | 新增/更新 {inserted} 条 (共获取 {len(rows)} 条, 数据源: {source})")
    return inserted, len(rows)


def show_summary():
    """显示指数数据概况（从 index_daily 表读取）"""
    db = StockDailyDB()
    try:
        print(f"\n{'=' * 65}")
        print(f"  指数数据概况 (index_daily 表, 后端: {get_backend_label()})")
        print(f"{'=' * 65}")
        print(f"  {'代码':<12s} {'名称':<10s} {'记录数':>8s} {'起始日期':<12s} {'最新日期':<12s}")
        print(f"  {'─' * 55}")

        index_table = db.CH_INDEX_TABLE if db.backend == "clickhouse" else "index_daily"

        for code, name, market, bs_code in BUILTIN_INDICES:
            # index_daily 中 code 为纯数字
            if db.backend == "clickhouse":
                r = db.ch_client.query(
                    f"SELECT count() as cnt, MIN(trade_date) as min_date, MAX(trade_date) as max_date "
                    f"FROM {index_table} WHERE code = '{code}'"
                )
                row = r.result_rows[0] if r.result_rows else (0, None, None)
                cnt = row[0]
                min_d = str(row[1]) if row[1] else "—"
                max_d = str(row[2]) if row[2] else "—"
            else:
                import pymysql
                with db.mysql_conn.cursor() as cur:
                    cur.execute(
                        f"SELECT COUNT(*) as cnt, MIN(trade_date) as min_date, MAX(trade_date) as max_date "
                        f"FROM index_daily WHERE code = %s",
                        (code,)
                    )
                    row = cur.fetchone()
                    cnt = row["cnt"]
                    min_d = str(row["min_date"]) if row["min_date"] else "—"
                    max_d = str(row["max_date"]) if row["max_date"] else "—"

            print(f"  {code:<12s} {name:<10s} {cnt:>8,d} {min_d:<12s} {max_d:<12s}")

        print(f"  {'─' * 55}")
        print(f"{'=' * 65}\n")
    finally:
        db.close()


def resolve_date(date_str):
    """解析日期参数"""
    if not date_str:
        return None
    date_str = date_str.strip()
    today = datetime.now().date()
    if date_str.lower() == "today":
        return today.strftime("%Y-%m-%d")
    elif date_str.lower() == "yesterday":
        return (today - timedelta(days=1)).strftime("%Y-%m-%d")
    if date_str.lower().endswith(" days ago"):
        try:
            n = int(date_str.split()[0])
            return (today - timedelta(days=n)).strftime("%Y-%m-%d")
        except (ValueError, IndexError):
            pass
    try:
        dt = datetime.strptime(date_str, "%Y-%m-%d")
        return dt.strftime("%Y-%m-%d")
    except ValueError:
        print(f"[ERROR] 无法解析日期: {date_str}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        description="更新指数日线数据 (Baostock)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  %(prog)s                                    # 更新全部指数
  %(prog)s --summary                          # 查看指数数据概况
  %(prog)s --start-date 2020-01-01            # 从指定日期开始
  %(prog)s --code 000300                      # 只更新沪深300
  %(prog)s --code 000001,000300,000905        # 更新多个指数
        """
    )

    parser.add_argument("--start-date", type=str, default=None,
                        help="开始日期 (默认: 数据库中该指数最新日期的次日, 若无则最近一年前)")
    parser.add_argument("--end-date", type=str, default=None,
                        help="结束日期 (默认: 今天)")
    parser.add_argument("--code", type=str, default=None,
                        help="只更新指定指数代码, 逗号分隔 (如: 000300,000905)")
    parser.add_argument("--force", action="store_true",
                        help="强制全量更新（忽略增量检测，从 --start-date 重新拉取）")
    parser.add_argument("--summary", action="store_true",
                        help="只显示指数数据概况, 不执行更新")

    args = parser.parse_args()

    # ─── 解析日期 ───
    end_date = resolve_date(args.end_date) if args.end_date else datetime.now().strftime("%Y-%m-%d")
    default_start = (datetime.now() - timedelta(days=365)).strftime("%Y-%m-%d")  # 默认最近一年
    start_date = resolve_date(args.start_date) if args.start_date else default_start

    # ─── 确定要更新的指数列表 ───
    if args.code:
        codes = [c.strip() for c in args.code.split(",")]
        # 验证代码
        for c in codes:
            if c not in INDEX_MAP:
                print(f"[ERROR] 未知指数代码: {c}")
                print(f"  支持的指数: {', '.join(INDEX_MAP.keys())}")
                sys.exit(1)
    else:
        codes = [idx[0] for idx in BUILTIN_INDICES]

    # ─── 只看概况 ───
    if args.summary:
        show_summary()
        return 0

    # ─── 开始更新 ───
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"\n{'#' * 65}")
    print(f"  指数日线数据更新 (Baostock)")
    print(f"  时间: {now_str}")
    print(f"  数据库后端: {get_backend_label()}")
    print(f"{'#' * 65}")
    print(f"  日期范围: {start_date} ~ {end_date}")
    print(f"  指数数量: {len(codes)} 个")
    print(f"{'#' * 65}\n")

    # 登录 Baostock
    lg = bs.login()
    if lg.error_code != "0":
        print(f"[ERROR] Baostock 登录失败: {lg.error_msg}")
        return 1
    print(f"  Baostock 登录成功\n")

    db = StockDailyDB()
    total_start = time.time()
    total_inserted = 0
    total_fetched = 0

    try:
        for i, code in enumerate(codes, 1):
            info = INDEX_MAP[code]
            print(f"  [{i}/{len(codes)}] 处理: {code}.{info[2]} {info[1]}")

            inserted, fetched = process_index(db, code, start_date, end_date, force=args.force)
            total_inserted += inserted
            total_fetched += fetched

            # 避免请求过快
            if i < len(codes):
                time.sleep(0.5)

    finally:
        db.close()
        bs.logout()

    elapsed = time.time() - total_start
    print(f"\n{'#' * 65}")
    print(f"  指数数据更新完成")
    print(f"{'#' * 65}")
    print(f"  新增/更新: {total_inserted} 条")
    print(f"  总耗时:    {elapsed:.1f}s")
    print(f"{'#' * 65}\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
