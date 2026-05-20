#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_bj_stock_daily_qq.py
============================
使用腾讯证券接口获取北交所股票历史数据
数据源: https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get

字段: 日期, 开盘, 收盘, 最高, 最低, 成交量, {}, 换手率, 成交额, ""

存储后端: 通过 db_config.DB_BACKEND 切换 ClickHouse / MySQL
估值字段: pe_ttm / pb 在插入时直接注入（腾讯实时快照）
  - 北交所 pe_ttm/pb 来自腾讯实时行情，history 接口无历史估值
  - 市值数据统一从 stock_info.total_market_cap 获取，不写入 stock_daily
"""

import sys
import time
import argparse
import json
import re
from datetime import datetime, timedelta

import requests

from db_config import get_backend_label
from db_helper import StockDailyDB, to_float, to_int

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Referer": "https://gu.qq.com/",
    "Accept": "*/*",
}


def get_bj_stocks(db, limit=0):
    """从 stock_info 获取北交所股票列表"""
    return db.get_stocks(market="BJ", limit=limit)


def fetch_bj_stock_history_one(code, start_date, end_date):
    """单次请求获取北交所股票历史行情（最多640条）"""
    url = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get"
    start_str = start_date.strftime("%Y-%m-%d")
    end_str = end_date.strftime("%Y-%m-%d")

    params = {
        "_var": "kline_dayqfq",
        "param": f"bj{code},day,{start_str},{end_str},640,qfq",
        "r": "0.1",
    }

    try:
        r = requests.get(url, params=params, headers=HEADERS, timeout=15)
        r.raise_for_status()
        text = re.sub(r'^kline_dayqfq=', '', r.text.strip())
        d = json.loads(text)
        if d.get('code') != 0:
            return None
        stock_data = d.get('data', {}).get(f'bj{code}', {})
        rows = stock_data.get('qfqday', []) or stock_data.get('day', [])
        return rows if rows else None
    except Exception:
        return None


def split_date_ranges(start_date, end_date, months=6):
    """将日期范围按月数切分，确保每段不超过640个交易日"""
    if hasattr(start_date, 'date'):
        start_date = start_date.date()
    if hasattr(end_date, 'date'):
        end_date = end_date.date()

    # 单日查询直接返回，避免循环产生无效范围
    if start_date == end_date:
        return [(start_date, end_date)]

    ranges = []
    current = start_date
    while current <= end_date:
        year = current.year + (current.month + months - 1) // 12
        month = (current.month + months - 1) % 12 + 1
        next_end = min(datetime(year, month, 1).date() - timedelta(days=1), end_date)
        ranges.append((current, next_end))
        current = next_end + timedelta(days=1)
    return ranges


def fetch_bj_stock_history(code, start_date, end_date):
    """自动分段获取北交所股票历史行情"""
    date_ranges = split_date_ranges(start_date, end_date, months=6)
    all_rows = []
    seen_dates = set()
    for seg_start, seg_end in date_ranges:
        rows = fetch_bj_stock_history_one(code, seg_start, seg_end)
        if rows:
            for row in rows:
                d = row[0]
                if d not in seen_dates and seg_start <= datetime.strptime(d, "%Y-%m-%d").date() <= seg_end:
                    seen_dates.add(d)
                    all_rows.append(row)
            time.sleep(0.15)
    return all_rows if all_rows else None


# to_float / to_int 已从 db_helper 导入，不再本地定义

# ── 腾讯实时行情批量快照（内联，无外部依赖）──────────────────────────────
_QQ_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://gu.qq.com/",
}
_QQ_MARKET_PREFIX = {"SH": "sh", "SZ": "sz", "BJ": "bj"}


def _qq_parse_float(s):
    if not s or s in ("-", "0.00", "0", ""):
        return None
    try:
        v = float(s)
        return v if v != 0.0 else None
    except ValueError:
        return None


def fetch_qq_snapshot_batch(codes_markets, batch_size=100, delay=0.1):
    """
    腾讯实时行情批量获取（仅 pe_ttm / pb）。

    参数:
        codes_markets: [(code, market), ...]
    返回:
        {code: {"pe_ttm": float|None, "pb": float|None}}
    """
    result = {}
    for i in range(0, len(codes_markets), batch_size):
        batch = codes_markets[i: i + batch_size]
        symbols = ",".join(f"{_QQ_MARKET_PREFIX.get(m, 'sz')}{c}" for c, m in batch)
        try:
            url = f"https://qt.gtimg.cn/q={symbols}"
            r = requests.get(url, headers=_QQ_HEADERS, timeout=20)
            r.encoding = "gbk"
            for line in r.text.strip().split(";"):
                if "~" not in line:
                    continue
                parts = line.split("~")
                if len(parts) < 54:
                    continue
                stock_code = parts[2]
                result[stock_code] = {
                    "pe_ttm": _qq_parse_float(parts[53]),   # [53]=动态PE(TTM)
                    "pb":     _qq_parse_float(parts[46]),   # [46]=PB
                }
        except Exception as e:
            print(f"  [WARN] 腾讯快照批量请求失败: {e}")
        if i + batch_size < len(codes_markets):
            time.sleep(delay)
    return result


def build_daily_rows(db, code, name, market, rows, snapshot=None):
    """将腾讯接口数据转换为 db_helper.upsert_daily() 需要的 row list

    当 rows 只有1条时（单日查询场景），不跳过，直接写入（pre_close 留空后续由
    field_completer 补全）。当 rows >= 2 条时，第一条作为 prev_close 参照不写入。

    参数:
        snapshot: {"pe_ttm": ..., "pb": ...}
                  来自 fetch_qq_snapshot_batch() 批量获取
                  腾讯快照只有当前值，北交所无历史估值，所以用快照值填所有日期
    """
    if not rows:
        return []

    # 腾讯快照值（北交所无历史估值，用当前值填所有交易日）
    snap_pe = snapshot.get("pe_ttm") if snapshot else None
    snap_pb = snapshot.get("pb") if snapshot else None

    result = []
    prev_close = None  # 初始无参照

    # 只有1条时直接写入，不需要 prev_close 参照行
    if len(rows) == 1:
        row = rows[0]
        close_p = to_float(row[2])
        amount = to_float(row[8]) if len(row) > 8 else None
        if amount is not None:
            amount = amount * 10000  # 万元→元
        result.append({
            "code": code,
            "name": name,
            "trade_date": row[0],
            "open_price": to_float(row[1]),
            "close_price": close_p,
            "high_price": to_float(row[3]),
            "low_price": to_float(row[4]),
            "pre_close": None,  # 后续由 field_completer 补全
            "volume": to_int(row[5]),
            "amount": amount,
            "change_percent": None,
            "change_amount": None,
            "turnover_rate": to_float(row[7]) if len(row) > 7 else None,
            "pe_ttm": snap_pe,
            "pb": snap_pb,
        })
        return result

    # 2条及以上：第一条作为 prev_close 参照，不写入
    prev_close = to_float(rows[0][2])

    for i, row in enumerate(rows):
        if i == 0:
            continue  # skip padding 行

        trade_date = row[0]
        close_p = to_float(row[2])

        # 计算涨跌幅和涨跌额
        if prev_close is not None and prev_close != 0 and close_p is not None:
            change_pct = round((close_p - prev_close) / prev_close * 100, 2)
            change_amt = round(close_p - prev_close, 2)
        else:
            change_pct = None
            change_amt = None

        amount = to_float(row[8]) if len(row) > 8 else None
        if amount is not None:
            amount = amount * 10000  # 万元→元

        result.append({
            "code": code,
            "name": name,
            "trade_date": trade_date,
            "open_price": to_float(row[1]),
            "close_price": close_p,
            "high_price": to_float(row[3]),
            "low_price": to_float(row[4]),
            "pre_close": prev_close,
            "volume": to_int(row[5]),
            "amount": amount,
            "change_percent": change_pct,
            "change_amount": change_amt,
            "turnover_rate": to_float(row[7]) if len(row) > 7 else None,
            "pe_ttm": snap_pe,            # 腾讯快照当前值（北交所无历史）
            "pb": snap_pb,                # 同上
        })

        prev_close = close_p

    return result


def main():
    parser = argparse.ArgumentParser(description="使用腾讯证券接口获取北交所股票历史数据")
    parser.add_argument("--start-date", type=str, default="2026-03-20", help="开始日期 (默认: 2026-03-20)")
    parser.add_argument("--end-date", type=str, default=None, help="结束日期 (默认: 今天)")
    parser.add_argument("--code", type=str, help="只处理指定股票代码 (测试用)")
    parser.add_argument("--limit", type=int, default=0, help="只处理前N只股票 (测试用)")
    parser.add_argument("--batch-size", type=int, default=20, help="每批处理的股票数 (默认:20)")
    parser.add_argument("--delay", type=float, default=0.3, help="批次间延迟秒数 (默认:0.3)")
    parser.add_argument("--resume", action="store_true", help="断点续传(跳过已有数据的股票)")
    parser.add_argument("--force", action="store_true", help="强制写入(跳过去重预过滤，直接INSERT覆盖)")
    parser.add_argument("--pool", type=str, default=None,
                       choices=["SH300", "SZ50", "ZZ500", "ZZ1000", "STAR50"],
                       help="股票池筛选 (SH300/SZ50/ZZ500/ZZ1000/STAR50)")
    args = parser.parse_args()

    end_date = datetime.strptime(args.end_date, "%Y-%m-%d").date() if args.end_date else datetime.now().date()
    start_date = datetime.strptime(args.start_date, "%Y-%m-%d").date()

    print("=" * 70)
    print(f"北交所股票日线数据更新 (腾讯接口 → {get_backend_label()})")
    print("=" * 70)
    print(f"存储后端: {get_backend_label()}")
    print(f"日期范围: {start_date} ~ {end_date}")
    print(f"批次大小: {args.batch_size}, 批次延迟: {args.delay}s, 断点续传: {args.resume}")
    print("-" * 70)

    db = StockDailyDB()
    start_time = time.time()

    try:
        # 获取股票列表
        if args.code:
            stocks = db.get_stocks(code=args.code, pool=args.pool)
        elif args.pool:
            # 股票池模式下，北交所不在任何 SH/SZ 池内，跳过
            print(f"[跳过] 股票池 {args.pool} 不包含北交所股票")
            db.close()
            return 0
        else:
            stocks = get_bj_stocks(db, limit=args.limit)

        print(f"北交所股票总数: {len(stocks)} 只")
        if not stocks:
            print("未找到北交所股票，退出。")
            return

        # 断点续传
        stock_start_dates = {}
        if args.resume and not args.code:
            all_codes = [s[0] for s in stocks]
            latest_in_range_map = db.get_latest_dates_in_range_batch(all_codes, start_date, end_date)
            # end_date 可能是未来（如今天或周末），容忍 3 天误差
            resume_cutoff = end_date - timedelta(days=3)
            for code, name, market in stocks:
                latest_in_range = latest_in_range_map.get(code)
                if latest_in_range and latest_in_range >= resume_cutoff:
                    continue
                if latest_in_range:
                    actual_start = max(start_date, latest_in_range + timedelta(days=1))
                else:
                    actual_start = start_date
                stock_start_dates[code] = actual_start

            print(f"断点续传: {len(stock_start_dates)} 只需更新, {len(stocks)-len(stock_start_dates)} 只已跳过")
            stocks = [(c, n, m) for c, n, m in stocks if c in stock_start_dates]

        if not stocks:
            print("所有股票已有数据，无需更新。")
            return

        print(f"待处理股票: {len(stocks)} 只")

        # ── 批量预取腾讯快照（一次请求获取全部北交所股票的 pe_ttm / pb）──
        print("  批量获取腾讯快照...")
        codes_markets = [(c, "BJ") for c, n, m in stocks]
        all_snapshots = fetch_qq_snapshot_batch(codes_markets, batch_size=100)
        print(f"  快照获取完成: {len(all_snapshots)}/{len(stocks)} 只有数据\n")

        total_success = total_skipped = total_no_data = 0

        for i, (code, name, market) in enumerate(stocks, 1):
            if args.resume and code in stock_start_dates:
                actual_start = stock_start_dates[code]
            else:
                actual_start = start_date

            # 往前扩3天：确保跨周末/节假日时能获取到最近一个交易日作为 prev_close 参照
            fetch_start = actual_start - timedelta(days=3)
            rows = fetch_bj_stock_history(code, fetch_start, end_date)

            if rows:
                # 直接从预取好的快照映射中取（无需逐只请求腾讯接口）
                snapshot = all_snapshots.get(code, {})
                daily_rows = build_daily_rows(db, code, name, market, rows, snapshot=snapshot)
                n = db.upsert_daily(daily_rows, force=args.force)
                total_success += n
                total_skipped += len(daily_rows) - n
                if i % 10 == 0 or i <= 5:
                    elapsed = time.time() - start_time
                    speed = i / elapsed
                    eta = (len(stocks) - i) / speed if speed > 0 else 0
                    print(f"[{i}/{len(stocks)}] {code} {name}: +{n}条  "
                          f"速度:{speed:.1f}只/s  预计剩余:{eta/60:.1f}min")
            else:
                total_no_data += 1
                if i <= 10 or total_no_data <= 5:
                    print(f"[{i}/{len(stocks)}] {code} {name}: 无数据")

            if i % args.batch_size == 0:
                print(f"[累计] 写入记录: {total_success:,} 条")
                time.sleep(args.delay)

        elapsed = time.time() - start_time
        print()
        print("=" * 70)
        print(f"完成! 耗时: {elapsed:.1f}秒 ({elapsed/60:.1f}分钟)")
        print(f"处理股票: {len(stocks)} 只")
        print(f"成功写入: {total_success:,} 条")
        print(f"跳过已存在: {total_skipped} 条")
        print(f"无数据  : {total_no_data} 只")
        print("=" * 70)

        # ─── 自动补全 change 字段（pre_close/change_percent/change_amount）───
        # pe_ttm / pb 已在腾讯快照中获取，change 字段补全如下
        if total_success > 0:
            print(f"\n补全 change 字段（pre_close/change_percent/change_amount）...")
            try:
                from field_completer import complete_fields
                bj_stock_list = [(c, "BJ") for c, n, m in stocks]
                n = complete_fields(db, code=args.code if args.code else None,
                                    stock_list=bj_stock_list if not args.code else None,
                                    skip_valuation=False,
                                    force_full_scan=False)
                print(f"  补全完成: {n} 条")
            except Exception as e:
                print(f"  [WARN] change 字段补全异常（不影响日线数据）: {e}")

        # ─── ClickHouse OPTIMIZE（去重合并）─────────────────────
        if db.backend == "clickhouse":
            import clickhouse_connect, time as _t
            try:
                from db_config import CLICKHOUSE_CONFIG
                ch = clickhouse_connect.get_client(
                    host=CLICKHOUSE_CONFIG["host"], port=CLICKHOUSE_CONFIG["port"],
                    username=CLICKHOUSE_CONFIG["user"], password=CLICKHOUSE_CONFIG["password"],
                    database=CLICKHOUSE_CONFIG["database"],
                )
                print(f"\n  ClickHouse OPTIMIZE TABLE FINAL（去重合并）...")
                t0 = _t.time()
                ch.command("OPTIMIZE TABLE stock.stock_daily FINAL")
                elapsed = _t.time() - t0
                r = ch.query("SELECT count() AS total, countDistinct(code, trade_date) AS distinct_rows FROM stock.stock_daily")
                total_cnt, distinct_cnt = r.result_rows[0]
                dups = total_cnt - distinct_cnt
                print(f"  完成 (耗时 {elapsed:.1f}s): 总行 {total_cnt:,}, 去重后 {distinct_cnt:,}, 重复 {dups:,}")
            except Exception as e:
                print(f"  [WARN] ClickHouse OPTIMIZE 失败: {e}")

        return 0 if total_failed == 0 else 1

    finally:
        db.close()


if __name__ == '__main__':
    main()
