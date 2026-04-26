#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_stock_daily_akshare.py
=============================
使用 akshare 获取沪深股票历史数据（Baostock 备用方案）
数据源: 东方财富

存储后端: 通过 db_config.DB_BACKEND 切换 ClickHouse / MySQL
估值字段:
  - pe_ttm/pb: 暂为 None
  注意：已移除 market_cap/circ_market_cap 字段，市值从 stock_info 获取
"""

import sys
import time
import argparse
import threading
from datetime import datetime, timedelta

import akshare as ak

from db_config import get_backend_label
from db_helper import StockDailyDB


def get_akshare_code(code, market):
    """转换为 akshare 需要的股票代码格式: SH -> sh600000"""
    if market == "SH":
        return f"sh{code}"
    elif market == "SZ":
        return f"sz{code}"
    return None


def fetch_stock_history(code, market, start_date, end_date, timeout=5):
    """使用 akshare 获取单只股票的历史行情，返回 DataFrame 或 None

    超时(5s)直接跳过，不重试。
    """
    import pandas as pd

    ak_code = get_akshare_code(code, market)
    if not ak_code:
        return None

    result = [None]

    def fetch_with_timeout():
        try:
            df = ak.stock_zh_a_daily(
                symbol=ak_code,
                start_date=start_date.strftime("%Y%m%d"),
                end_date=end_date.strftime("%Y%m%d"),
                adjust="qfq",
            )
            result[0] = df
        except Exception as e:
            result[0] = e

    try:
        thread = threading.Thread(target=fetch_with_timeout)
        thread.daemon = True
        thread.start()
        thread.join(timeout=timeout)

        if thread.is_alive():
            print(f"  [TIMEOUT] {code} 超时({timeout}s), 跳过")
            return None

        df = result[0]
        if isinstance(df, Exception):
            print(f"  [ERROR] {code} 请求异常: {df}")
            return None

        if df is None or df.empty:
            return None

        df['trade_date'] = pd.to_datetime(df['date']).dt.strftime('%Y-%m-%d')
        return df

    except Exception as e:
        print(f"  [ERROR] {code} 获取失败: {e}")
        return None


def build_daily_rows(db, code, name, market, df):
    """将 akshare DataFrame 转换为 db_helper.upsert_daily() 需要的 row list

    估值字段:
      - pe_ttm/pb: 暂为 None
      注意：已移除 market_cap/circ_market_cap 字段
    """
    if df is None or df.empty:
        return []

    first_date = df.iloc[0]['trade_date']
    prev_close = db.get_prev_close(code, first_date)

    rows = []
    for i, (_, row) in enumerate(df.iterrows()):
        close_p = row.get('close')
        if close_p is not None:
            close_p = float(close_p)

        # 第一条记录：若 prev_close 为 None（DB 无前收），跳过本条，等 resume 补全
        if i == 0 and prev_close is None:
            print(f"  [SKIP] {code} {row['trade_date']} prev_close=None，跳过首条，等 resume 补全")
            if close_p is not None:
                prev_close = close_p
            continue

        if prev_close is not None and prev_close != 0 and close_p is not None:
            change_pct = round((close_p - prev_close) / prev_close * 100, 2)
            change_amt = round(close_p - prev_close, 2)
        else:
            change_pct = row.get('pct_chg')
            change_amt = row.get('change')
            if change_pct is not None:
                change_pct = float(change_pct)
            if change_amt is not None:
                change_amt = float(change_amt)

        rows.append({
            "code": code,
            "name": name,
            "trade_date": row['trade_date'],
            "open_price": float(row['open']) if row.get('open') else None,
            "close_price": close_p,
            "high_price": float(row['high']) if row.get('high') else None,
            "low_price": float(row['low']) if row.get('low') else None,
            "pre_close": prev_close,
            "volume": int(float(row['volume'])) if row.get('volume') else None,
            "amount": float(row['amount']) if row.get('amount') else None,
            "change_percent": change_pct,
            "change_amount": change_amt,
            "turnover_rate": float(row['turnover']) if row.get('turnover') else None,
            "pe_ttm": None,
            "pb": None,
        })

        prev_close = close_p

    return rows


def main():
    parser = argparse.ArgumentParser(description='使用 akshare 更新沪深股票日线数据')
    parser.add_argument('--market', type=str, default='ALL', help='市场: SH / SZ / ALL')
    parser.add_argument('--start-date', type=str, required=True, help='开始日期 YYYY-MM-DD')
    parser.add_argument('--end-date', type=str, default=None, help='结束日期 YYYY-MM-DD')
    parser.add_argument('--limit', type=int, default=0, help='限制处理股票数量（测试用）')
    parser.add_argument('--delay', type=float, default=0.05, help='请求间隔秒数（默认0.05秒）')
    parser.add_argument('--resume', action='store_true', help='断点续传')
    parser.add_argument('--pool', type=str, default=None,
                       choices=['SH300', 'SZ50', 'ZZ500', 'ZZ1000', 'STAR50'],
                       help='股票池筛选 (SH300/SZ50/ZZ500/ZZ1000/STAR50)')
    parser.add_argument('--quiet', action='store_true', help='安静模式')
    args = parser.parse_args()

    start_date = datetime.strptime(args.start_date, '%Y-%m-%d')
    end_date = datetime.strptime(args.end_date, '%Y-%m-%d') if args.end_date else datetime.now()

    print(f"{'=' * 60}")
    print(f"akshare 沪深日线数据更新 → {get_backend_label()}")
    print(f"日期范围: {start_date.date()} ~ {end_date.date()}")
    print(f"市场: {args.market}")
    print(f"{'=' * 60}")

    db = StockDailyDB()

    # 计算 end_date 之前的最后一个实际交易日（供断点续传比较用）
    actual_end_date = db.get_last_trading_day_before(end_date.date())
    print(f"        实际期末交易日: {actual_end_date} (end_date={end_date.date()})")

    try:
        if args.market == 'ALL':
            markets = ['SH', 'SZ']
        else:
            markets = [args.market]

        all_stocks = []
        for market in markets:
            if args.pool:
                # pool 模式下：先查完整池，再在 Python 层按 market 过滤
                pool_stocks = db.get_stocks(pool=args.pool)
                stocks = [(c, n, m) for (c, n, m) in pool_stocks if m == market]
            else:
                stocks = db.get_stocks(market=market, limit=args.limit)
            all_stocks.extend(stocks)

        # 断点续传
        if args.resume:
            print(f"\n[断点续传] 检查 [{start_date.date()}~{end_date.date()}] 已有数据...")
            all_codes = [s[0] for s in all_stocks]
            # 使用区间查询，只检查指定范围内的最新数据
            latest_map = db.get_latest_dates_in_range_batch(all_codes, start_date, end_date)
            filtered = []
            skipped = 0
            for code, name, market in all_stocks:
                latest = latest_map.get(code)
                if latest and latest >= actual_end_date:
                    skipped += 1
                    continue
                filtered.append((code, name, market))
            print(f"  跳过: {skipped} 只(区间内数据已完整), 待更新: {len(filtered)} 只")
            all_stocks = filtered

        if not all_stocks:
            print("\n没有需要更新的股票")
            return 0

        print(f"\n共 {len(all_stocks)} 只股票待处理")
        print(f"{'='*60}\n")

        total_records = 0
        failed_stocks = []
        processed_codes = []

        for i, (code, name, market) in enumerate(all_stocks, 1):
            try:
                # 断点续传：根据已有数据调整实际起始日期
                actual_start = start_date
                if args.resume:
                    latest_date = latest_map.get(code)
                    if latest_date is not None and latest_date < actual_end_date:
                        actual_start = latest_date + timedelta(days=1)
                        if actual_start > actual_end_date:
                            print(f"  [resume] {code} 数据已完整，跳过")
                            continue
                        print(f"  [resume] {code} 已有数据至 {latest_date}，从 {actual_start} 开始补全")

                df = fetch_stock_history(code, market, actual_start, end_date)
                if df is not None and not df.empty:
                    rows = build_daily_rows(db, code, name, market, df)
                    n = db.upsert_daily(rows)
                    total_records += n
                    processed_codes.append((code, market))
                    if not args.quiet:
                        print(f"[{i}/{len(all_stocks)}] {code} {name} 保存 {n} 条")
                else:
                    failed_stocks.append((code, name))
                    if not args.quiet:
                        print(f"[{i}/{len(all_stocks)}] {code} {name} 无数据")

                if i % 100 == 0:
                    print(f"[进度] 已处理 {i}/{len(all_stocks)} 只, 累计保存 {total_records} 条")

            except Exception as e:
                failed_stocks.append((code, name))
                if not args.quiet:
                    print(f"[{i}/{len(all_stocks)}] {code} {name} 错误: {e}")

            time.sleep(args.delay)

        print(f"\n{'='*60}")
        print(f"更新完成: 处理 {len(all_stocks)} 只股票, 新增/更新 {total_records} 条记录")
        if failed_stocks:
            print(f"失败 {len(failed_stocks)} 只")
        print(f"{'='*60}")

        # ─── 自动补全 change 字段（pre_close/change_percent/change_amount）───
        if total_records > 0:
            print(f"\n补全 change 字段（pre_close/change_percent/change_amount）...")
            try:
                from field_completer import complete_fields
                # 只补全本次成功写入的股票，避免全表扫描
                n = complete_fields(db, code=None,
                                   stock_list=processed_codes if processed_codes else None,
                                   skip_valuation=True)
                print(f"  补全完成: {n} 条")
            except Exception as e:
                print(f"  [WARN] change 字段补全异常: {e}")

        return 0 if total_records > 0 else 1

    finally:
        db.close()


if __name__ == '__main__':
    sys.exit(main())
