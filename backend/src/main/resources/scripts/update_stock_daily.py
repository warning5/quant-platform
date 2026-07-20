#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_stock_daily.py
=====================
统一日线更新脚本——通过 data_provider 模块自动适配 Baostock / 腾讯接口。

通过环境变量 DATA_SOURCE 或命令行 --source 切换数据源:
    DATA_SOURCE=baostock python update_stock_daily.py   # 使用 Baostock
    DATA_SOURCE=tencent  python update_stock_daily.py   # 使用腾讯接口
    python update_stock_daily.py --source tencent        # 命令行指定

功能与 update_stock_daily_baostock.py / update_bj_stock_daily_qq.py 等价，
但通过统一接口实现数据源可插拔。

用法:
    # 全市场增量更新（从环境变量读取数据源）
    python update_stock_daily.py --start-date 2026-07-16 --end-date 2026-07-19

    # 指定腾讯接口 + 沪深市场
    python update_stock_daily.py --source tencent --market SH

    # 指定单只股票测试
    python update_stock_daily.py --code 600000 --source tencent
"""

import sys
import time
import argparse
from datetime import datetime, timedelta

from db_config import get_backend_label
from db_helper import StockDailyDB
from data_provider import get_provider, get_data_source


def main():
    parser = argparse.ArgumentParser(
        description="统一日线更新脚本（支持 Baostock / 腾讯接口切换）"
    )
    parser.add_argument("--start-date", type=str, default=None,
                        help="开始日期 (默认: 今天-3天)")
    parser.add_argument("--end-date", type=str, default=None,
                        help="结束日期 (默认: 今天)")
    parser.add_argument("--code", type=str, help="只处理指定股票代码 (测试用)")
    parser.add_argument("--market", choices=["SH", "SZ", "BJ", "ALL"], default=None,
                        help="只处理指定市场 (不指定=全市场)")
    parser.add_argument("--source", choices=["baostock", "tencent"], default=None,
                        help="数据源 (默认: 从环境变量 DATA_SOURCE 读取)")
    parser.add_argument("--limit", type=int, default=0,
                        help="只处理前N只股票 (测试用)")
    parser.add_argument("--batch-size", type=int, default=20,
                        help="每批处理的股票数 (默认:20)")
    parser.add_argument("--delay", type=float, default=0.3,
                        help="批次间延迟秒数 (默认:0.3)")
    parser.add_argument("--resume", action="store_true",
                        help="断点续传(跳过已有数据的股票)")
    parser.add_argument("--force", action="store_true",
                        help="强制写入(跳过去重预过滤，直接INSERT覆盖)")
    parser.add_argument("--pool", type=str, default=None,
                        choices=["SH300", "SZ50", "ZZ500", "ZZ1000", "STAR50"],
                        help="股票池筛选")
    # 兼容后端 addCommonArgs 透传的参数（此脚本忽略这些标志）
    parser.add_argument("--daily-only", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--info-only", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--workers", type=int, default=1, help=argparse.SUPPRESS)
    parser.add_argument("--exclude-st", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()

    # ── 日期默认值 ──
    end_date = (datetime.strptime(args.end_date, "%Y-%m-%d").date()
                if args.end_date else datetime.now().date())
    start_date = (datetime.strptime(args.start_date, "%Y-%m-%d").date()
                  if args.start_date else end_date - timedelta(days=3))

    # ── 数据源（命令行 > 环境变量）──
    source = args.source or get_data_source()

    # ── 获取 provider ──
    provider = get_provider(source)

    # ── 打印配置 ──
    print("=" * 70)
    print(f"stock_daily 数据更新脚本 (统一接口 -> {get_backend_label()})")
    print("=" * 70)
    print(f"数据源: {source}")
    print(f"  支持全历史: {'是' if provider.supports_full_history else '否(640日上限)'}")
    print(f"  支持历史PE/PB: {'是' if provider.supports_historical_pe_pb else '否(仅快照)'}")
    print(f"  支持并行: {'是' if provider.supports_parallel else '否'}")
    print(f"存储后端: {get_backend_label()}")
    print(f"日期范围: {start_date} ~ {end_date}")
    print(f"市场: {args.market if args.market else '全市场'}")
    print(f"批次大小: {args.batch_size}, 延迟: {args.delay}s, 断点续传: {args.resume}")
    print("-" * 70)

    # ── 登录 ──
    print(f"\n正在登录 {source}...")
    if not provider.login():
        print(f"[ERROR] {source} 登录失败")
        return 1
    print(f"{source} 登录成功")

    db = StockDailyDB()
    start_time = time.time()

    try:
        # ── 获取股票列表 ──
        print("\n[1/3] 获取股票列表...")
        if args.code:
            stocks = db.get_stocks(code=args.code)
            if not stocks:
                print(f"[ERROR] 股票代码 {args.code} 不存在")
                return 1
        elif args.pool:
            stocks = db.get_stocks(pool=args.pool)
            if args.market and args.market != "ALL":
                stocks = [(c, n, m) for c, n, m in stocks if m == args.market]
        else:
            market_filter = args.market if args.market and args.market != "ALL" else None
            stocks = db.get_stocks(market=market_filter, limit=args.limit)

        print(f"        待处理股票: {len(stocks)} 只")

        if not stocks:
            print("无股票可处理，退出。")
            return 0

        # ── 腾讯接口：预取批量快照 PE/PB ──
        if source == "tencent" and hasattr(provider, 'prefetch_snapshots'):
            print(f"\n[2/3] 预取腾讯快照（PE/PB）...")
            codes_markets = [(c, m) for c, n, m in stocks]
            provider.prefetch_snapshots(codes_markets)
            print(f"        快照获取完成: {len(provider._snapshot_cache)} 只")

        # ── 断点续传：检查已有数据 ──
        actual_end_date = db.get_last_trading_day_before(end_date)
        stock_start_dates = {}
        if args.resume and not args.code:
            all_codes = [s[0] for s in stocks]
            latest_map = db.get_latest_dates_in_range_batch(all_codes, start_date, end_date)
            resume_cutoff = end_date - timedelta(days=3)
            for code, name, market in stocks:
                latest = latest_map.get(code)
                if latest and latest >= resume_cutoff:
                    continue
                if latest:
                    stock_start_dates[code] = max(start_date, latest + timedelta(days=1))
                else:
                    stock_start_dates[code] = start_date
            stocks = [(c, n, m) for c, n, m in stocks if c in stock_start_dates]
            print(f"        断点续传: {len(stocks)} 只需更新")

        # ── 遍历股票，获取数据并写入 ──
        print(f"\n[3/3] 获取行情并写入（共 {len(stocks)} 只）...")

        total_success = 0
        total_skipped = 0
        total_no_data = 0
        total_failed = 0
        processed_codes = []
        consecutive_no_data = 0

        for i, (code, name, market) in enumerate(stocks, 1):
            try:
                # 进度
                if (i - 1) % 50 == 0 or i <= 3 or i % 200 == 0:
                    pct = i * 100 / len(stocks)
                    print(f"[进度] {i}/{len(stocks)} ({pct:.1f}%) {code} {name}...", flush=True)

                # 断点续传调整起始日期
                actual_start = stock_start_dates.get(code, start_date)

                # 往前扩3天（确保跨周末/节假日有 prev_close 参照）
                fetch_start = actual_start - timedelta(days=3)

                # 调用 provider 获取数据
                df = provider.query_history(code, market, fetch_start, end_date)

                if df is not None and len(df) > 0:
                    rows = provider.build_daily_rows(db, code, name, market, df)
                    # 过滤掉 fetch_start ~ actual_start 的参照行
                    if actual_start > start_date or fetch_start < start_date:
                        rows = [r for r in rows
                                if str(r['trade_date']) >= str(actual_start)]
                    n = db.upsert_daily(rows, force=args.force)
                    total_success += n
                    total_skipped += len(rows) - n
                    processed_codes.append((code, market))
                    consecutive_no_data = 0
                    if i <= 5 or i % 50 == 0:
                        print(f"  [{i}/{len(stocks)}] {code} {name}: +{n}条")
                else:
                    total_no_data += 1
                    consecutive_no_data += 1
                    if consecutive_no_data == 10:
                        print(f"  [WARN] 连续10只无数据，数据源可能未更新", flush=True)
                    if consecutive_no_data == 50:
                        print(f"  [ABORT] 连续50只无数据，终止", flush=True)
                        break

                if i % args.batch_size == 0 and i < len(stocks):
                    if total_success > 0:
                        print(f"  [累计] 写入: {total_success:,} 条")
                    time.sleep(args.delay)

            except Exception as e:
                print(f"  [ERROR] {code}: {e}")
                total_failed += 1
                continue

        # ── 统计 ──
        elapsed = time.time() - start_time
        print(f"\n{'=' * 70}")
        print(f"完成! 数据源: {source}")
        print(f"耗时: {elapsed:.1f}s ({elapsed/60:.1f}min)")
        print(f"处理: {len(stocks)} 只 | 写入: {total_success:,} 条 | "
              f"跳过: {total_skipped} | 无数据: {total_no_data} | 失败: {total_failed}")
        print(f"{'=' * 70}")

        # ── 补全 change 字段 ──
        if total_success > 0:
            print(f"\n补全 change 字段（pre_close/change_percent/change_amount）...")
            try:
                from field_completer import complete_fields
                target_dates = [str(actual_end_date)] if actual_end_date else None
                n = complete_fields(db,
                                    code=args.code if args.code else None,
                                    stock_list=processed_codes if not args.code else None,
                                    skip_valuation=not provider.supports_historical_pe_pb,
                                    target_dates=target_dates,
                                    scan_mode="full")
                print(f"  补全完成: {n} 条")
            except Exception as e:
                print(f"  [WARN] change 补全异常（不影响日线）: {e}")

            # ── 腾讯接口：PE/PB 历史补缺提示 ──
            if not provider.supports_historical_pe_pb:
                print(f"\n[提示] {source} 仅提供 PE/PB 快照值（当日）。")
                print(f"  历史 PE/PB 精确值需 Baostock 解封后运行 field_completer 补全。")
                print(f"  快照值精度: 日变化通常 <1%，对因子排名影响极小。")

        return 0 if total_failed == 0 else 1

    finally:
        db.close()
        provider.logout()
        print(f"{source} 登出完成")


if __name__ == "__main__":
    sys.exit(main())
