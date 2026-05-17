#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_fund_holder.py
=====================
采集全市场基金持仓明细，upsert 到 MySQL stock_fund_holder 表。
数据源: akshare stock_fund_stock_holder(symbol=code)
字段: 基金名称/代码, 持仓数量, 占流通股比例, 持股市值, 占净值比例, 截止日期

用法:
  python update_fund_holder.py                    # 增量（跳过已有数据的股票）
  python update_fund_holder.py --all              # 全量重刷
  python update_fund_holder.py --code 600519     # 单只测试
  python update_fund_holder.py --check           # 查看数据概况
  python update_fund_holder.py --workers 4       # 线程数（默认4）
"""
import sys
import os
import time
import math
import argparse
import pymysql
from datetime import datetime

sys.path.insert(0, os.path.dirname(__file__))
from db_config import MYSQL_CONFIG

MAX_WORKERS = 4


def get_conn():
    cfg = MYSQL_CONFIG.copy()
    cfg["charset"] = "utf8mb4"
    cfg["autocommit"] = True
    return pymysql.connect(**cfg)


def upsert_rows(conn, rows):
    """批量 upsert 基金持仓数据"""
    if not rows:
        return 0
    cols = ["stock_code","report_date","fund_name","fund_code",
            "holding_quantity","float_ratio","market_value","nav_ratio","update_time"]
    sql = f"""
        INSERT INTO stock_fund_holder ({','.join(cols)})
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
            fund_name = VALUES(fund_name),
            holding_quantity = VALUES(holding_quantity),
            float_ratio = VALUES(float_ratio),
            market_value = VALUES(market_value),
            nav_ratio = VALUES(nav_ratio),
            update_time = CURRENT_TIMESTAMP
    """
    cur = conn.cursor()
    cur.executemany(sql, rows)
    conn.commit()
    cur.close()
    return len(rows)


def fetch_one(code: str, max_retries: int = 2) -> list:
    """采集单只股票的基金持仓明细"""
    import akshare as ak
    for attempt in range(max_retries):
        try:
            df = ak.stock_fund_stock_holder(symbol=code)
            if df is None or df.empty:
                return []
            # 取截止日期（所有行相同）
            report_date = None
            for _, r in df.iterrows():
                rd = r.get("截止日期")
                if rd is not None:
                    if hasattr(rd, "date"):
                        rd = rd.date()
                    report_date = str(rd)
                    break
            rows = []
            for _, r in df.iterrows():
                fund_code = str(r.get("基金代码", "")).strip().zfill(6)
                if not fund_code or fund_code == "000000":
                    continue
                fund_name = str(r.get("基金名称", ""))[:100]
                def f(v):
                    if isinstance(v, (int, float)):
                        if math.isnan(v) or math.isinf(v):
                            return None
                        return v
                    return None
                rows.append([
                    code,
                    report_date,
                    fund_name,
                    fund_code,
                    f(r.get("持仓数量")),
                    f(r.get("占流通股比例")),
                    f(r.get("持股市值")),
                    f(r.get("占净值比例")),
                    datetime.now(),
                ])
            return rows
        except Exception:
            if attempt < max_retries - 1:
                time.sleep(2 ** attempt * 1.5)
    return []


def get_codes(conn, skip_done=False):
    """返回待采集股票列表"""
    cur = conn.cursor()
    if skip_done:
        # 增量：跳过已有数据的股票
        cur.execute("""
            SELECT si.code FROM stock_info si
            LEFT JOIN (
                SELECT DISTINCT stock_code FROM stock_fund_holder
            ) fh ON fh.stock_code COLLATE utf8mb4_unicode_ci = si.code
            WHERE fh.stock_code IS NULL
              AND si.code NOT LIKE '920%' COLLATE utf8mb4_bin
            ORDER BY si.code
        """)
    else:
        # 全量
        cur.execute("""
            SELECT code FROM stock_info
            WHERE code NOT LIKE '920%' COLLATE utf8mb4_bin
            ORDER BY code
        """)
    rows = cur.fetchall()
    cur.close()
    return [r[0] for r in rows]


def check_status(conn):
    cur = conn.cursor()
    cur.execute("SELECT COUNT(DISTINCT stock_code) FROM stock_fund_holder")
    n_stocks = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM stock_fund_holder")
    n_rows = cur.fetchone()[0]
    cur.execute("SELECT COUNT(DISTINCT fund_code) FROM stock_fund_holder")
    n_funds = cur.fetchone()[0]
    cur.execute("SELECT MIN(report_date), MAX(report_date) FROM stock_fund_holder")
    dr = cur.fetchone()
    cur.execute("""
        SELECT stock_code, COUNT(*) as cnt FROM stock_fund_holder
        GROUP BY stock_code ORDER BY cnt DESC LIMIT 5
    """)
    top = cur.fetchall()
    cur.close()
    print(f"\n── 基金持仓数据概况 ─────────────────────────────")
    print(f"  有持仓的股票数: {n_stocks}")
    print(f"  总记录数:       {n_rows:,}")
    print(f"  涉及基金数:     {n_funds:,}")
    print(f"  截止日期范围:   {dr[0]} ~ {dr[1]}")
    print(f"  持仓最多5只股票:")
    for r in top:
        print(f"    {r[0]}  {r[1]:,}条")
    print("────────────────────────────────────────────────\n")


def run_worker(args_tuple, code):
    """线程worker"""
    conn_args, workers = args_tuple
    rows = fetch_one(code)
    if rows:
        conn = get_conn()
        upsert_rows(conn, rows)
        conn.close()
    return code, len(rows)


def run_batch(conn, codes, workers=MAX_WORKERS):
    from concurrent.futures import ThreadPoolExecutor, as_completed
    total = len(codes)
    done = [0]; stored = [0]; failed = [0]
    args_tuple = (None, workers)  # placeholder

    print(f"\n基金持仓采集：共 {total} 只股票，线程数 {workers}")
    print("=" * 60)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {}
        for c in codes:
            f = executor.submit(run_worker, (None, workers), c)
            futures[f] = c

        for future in as_completed(futures):
            code, n_rows = future.result()
            done[0] += 1
            if n_rows > 0:
                stored[0] += 1
            else:
                failed[0] += 1
            if done[0] % 20 == 0 or done[0] == total:
                print(f"  [{done[0]}/{total}] {code}  "
                      f"(累计有效 {stored[0]}, 空 {failed[0]})")
            time.sleep(0.3)

    print(f"\n{'='*60}")
    print(f"  完成: {done[0]}  有效: {stored[0]}  空: {failed[0]}")
    print(f"{'='*60}\n")


def main():
    parser = argparse.ArgumentParser(description="基金持仓批量采集")
    parser.add_argument("--all",      action="store_true", help="全量重刷")
    parser.add_argument("--check",    action="store_true", help="仅查看数据概况")
    parser.add_argument("--code",     type=str, help="单只股票测试")
    parser.add_argument("--workers",  type=int, default=MAX_WORKERS, help="线程数")
    args = parser.parse_args()

    conn = get_conn()

    if args.check:
        check_status(conn)
        conn.close()
        return

    if args.code:
        print(f"\n单只测试: {args.code}")
        rows = fetch_one(args.code)
        print(f"  获取 {len(rows)} 条基金持仓")
        if rows:
            upsert_rows(conn, rows)
            print(f"  已入库")
        else:
            print("  无数据")
        conn.close()
        return

    codes = get_codes(conn, skip_done=not args.all)
    if not codes:
        print("\n✓ 所有股票已有基金持仓数据，无需补充。用 --all 强制重刷。")
        conn.close()
        return

    print(f"\n待采集: {len(codes)} 只股票")
    run_batch(conn, codes, workers=args.workers)
    check_status(conn)
    conn.close()


if __name__ == "__main__":
    main()
