"""
update_shareholder_batch.py
批量采集股东人数（stock_zh_a_gdhs_detail_em），upsert 到 stock_shareholder 表。

用法:
  python update_shareholder_batch.py              # 增量：只补没有数据的股票
  python update_shareholder_batch.py --all       # 全量：清空后重刷
  python update_shareholder_batch.py --code 600519  # 单只测试
  python update_shareholder_batch.py --check     # 仅打印统计
"""
import sys
import os
import time
import argparse
import pymysql
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

os.environ["DISABLE_TQDM"] = "1"  # 禁用 tqdm 进度条，避免日志乱码

sys.path.insert(0, os.path.dirname(__file__))
from db_config import MYSQL_CONFIG
from update_report_data import fetch_shareholder_count

# ── 配置 ─────────────────────────────────────────────────────
MAX_WORKERS = 8
DELAY_PER_REQ = 0.3      # 单线程时每只间隔（秒）
BATCH_DELAY  = 0.5       # 批次间隔
# ─────────────────────────────────────────────────────────────


def get_mysql_conn():
    cfg = MYSQL_CONFIG.copy()
    cfg["charset"] = "utf8mb4"
    cfg["autocommit"] = True
    return pymysql.connect(**cfg)


def ensure_table(conn):
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS stock_shareholder (
            id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            code        VARCHAR(6) NOT NULL,
            report_date DATE,
            holder_count BIGINT,
            avg_shares  DECIMAL(20,4),
            change_pct   DECIMAL(10,4),
            change_count BIGINT,
            fetched_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY uk_code_date (code, report_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)
    cur.close()


def upsert_one(conn, code, data):
    """将一只股票的股东人数最新一期 upsert 到 stock_shareholder"""
    if not data.get('fetched'):
        return False
    rd = data.get('report_date')
    if not rd:
        return False
    cur = conn.cursor()
    cur.execute("""
        INSERT INTO stock_shareholder
            (code, report_date, holder_count, avg_shares, change_pct, change_count)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            holder_count = VALUES(holder_count),
            avg_shares  = VALUES(avg_shares),
            change_pct   = VALUES(change_pct),
            change_count = VALUES(change_count),
            fetched_at   = CURRENT_TIMESTAMP
    """, (
        code,
        rd,
        data.get('holder_count'),
        data.get('avg_shares'),
        data.get('change_pct'),
        data.get('change_count'),
    ))
    cur.close()
    return True


def get_codes_to_fetch(conn, force_all=False):
    """返回需要采集的股票列表"""
    cur = conn.cursor()
    if force_all:
        cur.execute("SELECT code FROM stock_info ORDER BY code")
    else:
        # 增量：只取 stock_shareholder 中没有记录的
        cur.execute("""
            SELECT si.code
            FROM stock_info si
            LEFT JOIN stock_shareholder sh ON sh.code = si.code COLLATE utf8mb4_unicode_ci
            WHERE sh.code IS NULL
            ORDER BY si.code
        """)
    rows = cur.fetchall()
    cur.close()
    return [r[0] for r in rows]


def worker_fetch_and_store(conn_pool_args, code):
    """单只股票：采集 + upsert。返回 (code, success, msg)"""
    try:
        data = fetch_shareholder_count(code)
        if not data.get('fetched'):
            return code, False, data.get('error') or 'no data'
        # 每个线程独立连接
        conn = get_mysql_conn()
        ok = upsert_one(conn, code, data)
        conn.close()
        if ok:
            hc = data.get('holder_count')
            return code, True, f"holder_count={hc:,}" if hc else "ok"
        return code, False, "upsert failed"
    except Exception as e:
        return code, False, str(e)[:80]


def run_batch(conn, codes, max_workers=MAX_WORKERS):
    total  = len(codes)
    done   = [0]
    stored = [0]
    errors = [0]

    print(f"\n批量股东人数采集：共 {total} 只，线程数 {max_workers}")
    print("=" * 60)

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(worker_fetch_and_store, None, c): c for c in codes}
        for future in as_completed(futures):
            code, ok, msg = future.result()
            done[0] += 1
            if ok:
                stored[0] += 1
                if done[0] % 20 == 0 or done[0] == total:
                    print(f"  [{done[0]}/{total}] {code} ✓  {msg}")
            else:
                errors[0] += 1
                if done[0] % 20 == 0 or done[0] == total:
                    print(f"  [{done[0]}/{total}] {code} ✗  {msg}")
            # 轻微节流
            time.sleep(0.05)

    print(f"\n{'='*60}")
    print(f"  完成: {done[0]}  入库: {stored[0]}  错误: {errors[0]}")
    print(f"{'='*60}\n")


def check_status(conn):
    cur = conn.cursor()
    cur.execute("SELECT COUNT(DISTINCT code) FROM stock_shareholder")
    n_stocks = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM stock_shareholder")
    n_rows = cur.fetchone()[0]
    cur.execute("SELECT MIN(report_date), MAX(report_date) FROM stock_shareholder")
    date_range = cur.fetchone()
    cur.execute("SELECT code, report_date, holder_count, change_pct FROM stock_shareholder ORDER BY report_date DESC LIMIT 5")
    latest = cur.fetchall()
    cur.close()
    print(f"\n── 股东人数数据概况 ─────────────────────────")
    print(f"  有数据的股票数: {n_stocks}")
    print(f"  总记录数:      {n_rows}")
    print(f"  数据日期范围:  {date_range[0]} ~ {date_range[1]}")
    print(f"  最新5条记录:")
    for r in latest:
        print(f"    {r[0]}  {r[1]}  户数={r[2]:,}  变化={r[3]:.2f}%" if r[2] else "")
    print("─────────────────────────────────────────────\n")


def main():
    parser = argparse.ArgumentParser(description="批量采集股东人数")
    parser.add_argument("--all",  action="store_true", help="全量重刷（跳过已有检查）")
    parser.add_argument("--check", action="store_true", help="仅查看数据概况")
    parser.add_argument("--code", type=str, help="单只股票测试")
    parser.add_argument("--workers", type=int, default=MAX_WORKERS, help="线程数（默认8）")
    args = parser.parse_args()

    conn = get_mysql_conn()
    ensure_table(conn)

    if args.check:
        check_status(conn)
        conn.close()
        return

    if args.code:
        print(f"\n单只测试: {args.code}")
        data = fetch_shareholder_count(args.code)
        print(f"  result: {data}")
        if data.get('fetched'):
            upsert_one(conn, args.code, data)
            print("  已入库")
        conn.close()
        return

    codes = get_codes_to_fetch(conn, force_all=args.all)
    if not codes:
        print("\n✓ 所有股票已有股东人数数据，无需补充。用 --all 强制重刷。")
        conn.close()
        return

    print(f"\n待采集: {len(codes)} 只股票")
    run_batch(conn, codes, max_workers=args.workers)

    check_status(conn)
    conn.close()


if __name__ == "__main__":
    main()
