#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_stock_data.py
====================
统一入口：更新 stock 数据库中的行情数据

功能:
  1. 更新 stock_daily 表（日线行情）—— SH/SZ 用 Baostock，BJ 用腾讯证券接口
  2. 更新 stock_info 表（动态字段：市值、PE、PB、ST标记）—— 腾讯财经接口

用法:
  # 更新全部（日线 + stock_info）
  python update_stock_data.py

  # 只更新今天的日线
  python update_stock_data.py --start-date today

  # 更新指定日期范围
  python update_stock_data.py --start-date 2026-04-10 --end-date 2026-04-15

  # 只更新日线，不更新 stock_info
  python update_stock_data.py --daily-only

  # 只更新 stock_info 表
  python update_stock_data.py --info-only

  # 只更新指定市场
  python update_stock_data.py --market SH
  python update_stock_data.py --market SZ
  python update_stock_data.py --market BJ
  python update_stock_data.py --market SH,SZ

  # 单只股票测试
  python update_stock_data.py --code 000001

  # 断点续传（跳过已有数据的股票）
  python update_stock_data.py --resume

  # 限制数量（测试用）
  python update_stock_data.py --limit 10

  # 补充缺失的 601958
  python update_stock_data.py --code 601958 --market SH

依赖: pip install pymysql baostock requests pandas
"""

import sys
import os
import time
import argparse
import subprocess
from datetime import datetime, timedelta

# ─── 基础路径（确保子脚本能被找到） ─────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# ─── 子脚本映射 ─────────────────────────────────────────────
BAOSTOCK_SCRIPT = os.path.join(SCRIPT_DIR, "update_stock_daily_baostock.py")
BJ_QQ_SCRIPT    = os.path.join(SCRIPT_DIR, "update_bj_stock_daily_qq.py")
INFO_SCRIPT     = os.path.join(SCRIPT_DIR, "update_stock_info_daily.py")
INDEX_SCRIPT    = os.path.join(SCRIPT_DIR, "update_index_daily_baostock.py")


def resolve_date(date_str):
    """
    解析日期参数
    支持: YYYY-MM-DD / today / yesterday / N天前的自然语言
    """
    if not date_str:
        return None

    date_str = date_str.strip()

    # 特殊关键字
    today = datetime.now().date()
    if date_str.lower() == "today":
        return today.strftime("%Y-%m-%d")
    elif date_str.lower() == "yesterday":
        return (today - timedelta(days=1)).strftime("%Y-%m-%d")

    # "N days ago" 格式
    if date_str.lower().endswith(" days ago"):
        try:
            n = int(date_str.split()[0])
            return (today - timedelta(days=n)).strftime("%Y-%m-%d")
        except (ValueError, IndexError):
            pass

    # 标准 YYYY-MM-DD
    try:
        dt = datetime.strptime(date_str, "%Y-%m-%d")
        return dt.strftime("%Y-%m-%d")
    except ValueError:
        print(f"[ERROR] 无法解析日期: {date_str}")
        print("        支持格式: YYYY-MM-DD / today / yesterday / N days ago")
        sys.exit(1)


def run_cmd(cmd, description):
    """运行子进程，打印命令信息"""
    print(f"\n{'=' * 70}")
    print(f"  >> {description}")
    print(f"  >> 命令: {' '.join(cmd)}")
    print(f"{'=' * 70}\n")

    start = time.time()
    result = subprocess.run(cmd)
    elapsed = time.time() - start

    status = "成功" if result.returncode == 0 else "失败"
    print(f"\n  [完成] {description} - {status} (耗时 {elapsed:.1f}s)")
    return result.returncode == 0


def run_baostock(market, start_date, end_date, extra_args):
    """调用 Baostock 脚本更新 SH/SZ 日线"""
    if not os.path.exists(BAOSTOCK_SCRIPT):
        print(f"[ERROR] 找不到脚本: {BAOSTOCK_SCRIPT}")
        return False

    cmd = [sys.executable, BAOSTOCK_SCRIPT, "--market", market, "--start-date", start_date]
    if end_date:
        cmd += ["--end-date", end_date]
    cmd += extra_args

    return run_cmd(cmd, f"{market} 日线数据 (Baostock)")


def run_bj_qq(start_date, end_date, extra_args):
    """调用腾讯接口脚本更新 BJ 日线"""
    if not os.path.exists(BJ_QQ_SCRIPT):
        print(f"[ERROR] 找不到脚本: {BJ_QQ_SCRIPT}")
        return False

    cmd = [sys.executable, BJ_QQ_SCRIPT, "--start-date", start_date]
    if end_date:
        cmd += ["--end-date", end_date]
    cmd += extra_args

    return run_cmd(cmd, "BJ 北交所日线数据 (腾讯接口)")


def run_stock_info(extra_args):
    """调用脚本更新 stock_info 动态字段"""
    if not os.path.exists(INFO_SCRIPT):
        print(f"[ERROR] 找不到脚本: {INFO_SCRIPT}")
        return False

    cmd = [sys.executable, INFO_SCRIPT] + extra_args

    return run_cmd(cmd, "stock_info 动态字段 (市值/PE/PB)")


def run_index_daily(start_date, end_date, extra_args):
    """调用指数日线更新脚本"""
    if not os.path.exists(INDEX_SCRIPT):
        print(f"[ERROR] 找不到脚本: {INDEX_SCRIPT}")
        return False

    cmd = [sys.executable, INDEX_SCRIPT, "--start-date", start_date]
    if end_date:
        cmd += ["--end-date", end_date]
    # 指数脚本不使用 --code/--resume/--limit 等
    cmd += extra_args

    return run_cmd(cmd, "指数日线数据 (Baostock)")


def show_summary():
    """显示当前数据概况"""
    try:
        import pymysql
        conn = pymysql.connect(
            host="localhost", port=3306, db="stock",
            user="root", password="123456", charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
        )
        with conn.cursor() as cur:
            # stock_daily 概况
            cur.execute("SELECT COUNT(*) as total FROM stock_daily")
            total = cur.fetchone()["total"]

            cur.execute("SELECT COUNT(DISTINCT code) as stocks FROM stock_daily")
            stocks = cur.fetchone()["stocks"]

            cur.execute("SELECT MIN(trade_date) as min_date, MAX(trade_date) as max_date FROM stock_daily")
            dates = cur.fetchone()

            # 各市场覆盖
            cur.execute("""
                SELECT s.market, COUNT(DISTINCT s.code) as stock_count,
                       COUNT(DISTINCT d.trade_date) as date_count
                FROM stock_info s
                LEFT JOIN stock_daily d ON s.code = d.code
                GROUP BY s.market ORDER BY s.market
            """)
            market_stats = cur.fetchall()

            # 指数数据概况（通过 code + name 组合区分指数和个股）
            index_names = {
                "000001": "上证指数", "000016": "上证50", "000022": "中证红利",
                "000300": "沪深300", "000688": "科创50", "000852": "中证1000",
                "000905": "中证500", "399001": "深证成指", "399006": "创业板指",
                "399303": "国证2000",
            }
            conditions = " OR ".join(["(code = %s AND name = %s)"] * len(index_names))
            params = []
            for c, n in index_names.items():
                params.extend([c, n])

            cur.execute(f"""
                SELECT COUNT(*) as total FROM stock_daily
                WHERE {conditions}
            """, params)
            index_total = cur.fetchone()["total"]

            cur.execute(f"""
                SELECT MIN(trade_date) as min_date, MAX(trade_date) as max_date FROM stock_daily
                WHERE {conditions}
            """, params)
            index_dates = cur.fetchone()

            cur.execute(f"""
                SELECT COUNT(DISTINCT code) as cnt FROM stock_daily
                WHERE {conditions}
            """, params)
            index_count = cur.fetchone()["cnt"]

        conn.close()

        print(f"\n{'=' * 50}")
        print(f"  当前数据概况")
        print(f"{'=' * 50}")
        print(f"  stock_daily 总记录: {total:,} 条")
        print(f"  覆盖股票数:         {stocks} 只")
        print(f"  日期范围:           {dates['min_date']} ~ {dates['max_date']}")
        print(f"{'─' * 50}")
        for m in market_stats:
            print(f"  {m['market']:4s}  {m['stock_count']:5d} 只股票  {m['date_count']:6d} 个交易日")
        print(f"{'─' * 50}")
        print(f"  指数数据:           {index_count} 个指数  {index_total:,} 条记录")
        if index_dates["min_date"]:
            print(f"  指数日期范围:       {index_dates['min_date']} ~ {index_dates['max_date']}")
        else:
            print(f"  指数日期范围:       (无数据)")
        print(f"{'=' * 50}\n")

    except Exception as e:
        print(f"[WARN] 无法获取数据概况: {e}")


def main():
    parser = argparse.ArgumentParser(
        description="统一更新 stock 数据库行情数据",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  %(prog)s                                    # 更新全部（日线 + stock_info）
  %(prog)s --start-date today                 # 更新今天
  %(prog)s --start-date 2026-04-10            # 从指定日期开始
  %(prog)s --start-date 3 days ago            # 从3天前开始
  %(prog)s --start-date 2026-04-10 --end-date 2026-04-15
  %(prog)s --daily-only                       # 只更新日线行情
  %(prog)s --info-only                        # 只更新 stock_info
  %(prog)s --market SH                        # 只更新沪市
  %(prog)s --market SH,SZ                     # 更新沪深
  %(prog)s --code 000001                      # 单只股票测试
  %(prog)s --resume                           # 断点续传
  %(prog)s --limit 10                         # 限制数量（测试）
  %(prog)s --summary                          # 只看数据概况，不更新

日期参数支持:
  YYYY-MM-DD     标准日期格式
  today          今天
  yesterday      昨天
  N days ago     N天前
        """
    )

    # ─── 日期参数 ───
    parser.add_argument(
        "--start-date", type=str, default=None,
        help="开始日期 (默认: 自动检测数据库中最新交易日的次日，若为空则用3天前)"
    )
    parser.add_argument(
        "--end-date", type=str, default=None,
        help="结束日期 (默认: 今天)"
    )

    # ─── 功能选择 ───
    parser.add_argument(
        "--daily-only", action="store_true",
        help="只更新 stock_daily 日线行情"
    )
    parser.add_argument(
        "--info-only", action="store_true",
        help="只更新 stock_info 动态字段（市值/PE/PB）"
    )
    parser.add_argument(
        "--index-only", action="store_true",
        help="只更新指数日线数据（沪深300/上证50/中证500等，Baostock）"
    )
    parser.add_argument(
        "--summary", action="store_true",
        help="只显示数据概况，不执行更新"
    )

    # ─── 市场选择 ───
    parser.add_argument(
        "--market", type=str, default="ALL",
        help="市场选择: SH / SZ / BJ / ALL / SH,SZ (默认: ALL)"
    )

    # ─── 范围限制 ───
    parser.add_argument("--code", type=str, help="只处理指定股票代码")
    parser.add_argument("--limit", type=int, default=0, help="每市场只处理前N只 (测试用)")

    # ─── 执行控制 ───
    parser.add_argument("--resume", action="store_true", help="断点续传（跳过已有数据的股票）")
    parser.add_argument("--batch-size", type=int, default=10, help="每批股票数 (默认:10)")
    parser.add_argument("--delay", type=float, default=0.3, help="批次间延迟秒数 (默认:0.3)")
    parser.add_argument("--skip-bj-info", action="store_true", help="更新 stock_info 时跳过北交所")

    args = parser.parse_args()

    # ─── 解析日期 ───
    end_date = resolve_date(args.end_date) if args.end_date else None

    if args.start_date:
        start_date = resolve_date(args.start_date)
    else:
        # 自动检测：查询数据库中各市场的最新交易日
        start_date = auto_detect_start_date()

    # 结束日期默认今天
    if not end_date:
        end_date = datetime.now().strftime("%Y-%m-%d")

    # ─── 解析市场 ───
    if args.market.upper() == "ALL":
        markets = ["SH", "SZ", "BJ"]
    else:
        markets = [m.strip().upper() for m in args.market.split(",")]

    for m in markets:
        if m not in ("SH", "SZ", "BJ"):
            print(f"[ERROR] 无效的市场: {m}，支持: SH / SZ / BJ / ALL")
            sys.exit(1)

    # ─── 构建 extra_args ───
    extra_args = []
    if args.code:
        extra_args += ["--code", args.code]
    if args.limit > 0:
        extra_args += ["--limit", str(args.limit)]
    if args.resume:
        extra_args.append("--resume")
    if args.batch_size != 10:
        extra_args += ["--batch-size", str(args.batch_size)]
    if args.delay != 0.3:
        extra_args += ["--delay", str(args.delay)]

    # ─── 打印配置 ───
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"\n{'#' * 70}")
    print(f"  Stock 数据更新工具")
    print(f"  时间: {now_str}")
    print(f"{'#' * 70}")
    print(f"  日期范围:   {start_date} ~ {end_date}")
    print(f"  市场:       {', '.join(markets)}")
    if args.code:
        print(f"  指定股票:   {args.code}")
    if args.resume:
        print(f"  断点续传:   是")
    if args.limit > 0:
        print(f"  数量限制:   前 {args.limit} 只")
    if args.daily_only:
        print(f"  更新模式:   仅个股日线 (stock_daily)")
    elif args.info_only:
        print(f"  更新模式:   仅信息 (stock_info)")
    elif args.index_only:
        print(f"  更新模式:   仅指数日线")
    else:
        print(f"  更新模式:   个股日线 + 指数日线 + 信息 (全部)")
    print(f"{'#' * 70}")

    # ─── 只看概况 ───
    if args.summary:
        show_summary()
        return 0

    # ─── 显示更新前概况 ───
    show_summary()

    # ─── 执行更新 ───
    total_start = time.time()
    results = []

    do_daily = not args.info_only and not args.index_only
    do_info  = not args.daily_only and not args.index_only
    do_index = not args.info_only and not args.daily_only or args.index_only

    # ─── Part 1: 更新日线行情 ───
    if do_daily:
        for market in markets:
            if market == "BJ":
                ok = run_bj_qq(start_date, end_date, extra_args)
            else:  # SH / SZ
                ok = run_baostock(market, start_date, end_date, extra_args)
            results.append((f"{market} 日线", ok))

    # ─── Part 1.5: 更新指数日线 ───
    if do_index:
        # 指数脚本用 2018-01-02 作为默认起始（指数历史更长）
        index_start = "2018-01-02"
        ok = run_index_daily(index_start, end_date, [])
        results.append(("指数日线", ok))

    # ─── Part 2: 更新 stock_info ───
    if do_info:
        info_args = []
        if args.skip_bj_info:
            info_args.append("--skip-bj")
        ok = run_stock_info(info_args)
        results.append(("stock_info", ok))

    # ─── 汇总 ───
    total_elapsed = time.time() - total_start

    print(f"\n{'#' * 70}")
    print(f"  全部任务完成")
    print(f"{'#' * 70}")
    for name, ok in results:
        status = "OK" if ok else "FAIL"
        print(f"  [{status}] {name}")
    print(f"  总耗时: {total_elapsed:.1f}s ({total_elapsed/60:.1f}min)")
    print(f"{'#' * 70}\n")

    return 0 if all(ok for _, ok in results) else 1


def auto_detect_start_date():
    """
    自动检测起始日期：查询数据库中各市场最新交易日的次日。
    如果数据库为空，则默认 7 天前。
    """
    try:
        import pymysql
        conn = pymysql.connect(
            host="localhost", port=3306, db="stock",
            user="root", password="123456", charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
        )
        with conn.cursor() as cur:
            cur.execute("SELECT MAX(trade_date) as max_date FROM stock_daily")
            row = cur.fetchone()
        conn.close()

        if row and row["max_date"]:
            max_date = row["max_date"]
            if hasattr(max_date, "strftime"):
                next_day = max_date + timedelta(days=1)
            else:
                next_day = datetime.strptime(str(max_date), "%Y-%m-%d").date() + timedelta(days=1)
            result = next_day.strftime("%Y-%m-%d")
            print(f"  [自动检测] 数据库最新交易日: {max_date}, 从 {result} 开始更新")
            return result
        else:
            fallback = (datetime.now() - timedelta(days=7)).strftime("%Y-%m-%d")
            print(f"  [自动检测] 数据库为空, 默认从 {fallback} 开始")
            return fallback

    except Exception as e:
        fallback = (datetime.now() - timedelta(days=7)).strftime("%Y-%m-%d")
        print(f"  [自动检测] 无法查询数据库: {e}, 默认从 {fallback} 开始")
        return fallback


if __name__ == "__main__":
    sys.exit(main())
