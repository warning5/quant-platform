"""
补全 stock_daily 表的 market_cap / circ_market_cap 字段
计算公式:
  market_cap = stock_info.total_share * stock_daily.close_price  (总股本 × 收盘价 = 总市值)
  circ_market_cap = stock_info.float_share * stock_daily.close_price (流通股本 × 收盘价 = 流通市值)

用法:
  python update_data/fill_market_cap.py
  python update_data/fill_market_cap.py --batch-size 50000
  python update_data/fill_market_cap.py --dry-run   # 只统计不执行
"""

import argparse
import sys
import time
import pymysql

DB_CONFIG = {
    "host": "localhost",
    "port": 3306,
    "user": "root",
    "password": "123456",
    "database": "stock",
    "charset": "utf8mb4",
}


def load_share_map(conn):
    """加载 code -> (total_share, float_share) 映射"""
    cur = conn.cursor()
    cur.execute("SELECT code, total_share, float_share FROM stock_info WHERE total_share > 0")
    share_map = {}
    for code, ts, fs in cur.fetchall():
        share_map[code] = (float(ts), float(fs) if fs else 0.0)
    print(f"[INFO] 加载了 {len(share_map)} 只股票的股本数据")
    return share_map


def fill_market_cap(conn, share_map, batch_size=50000, dry_run=False):
    """批量回算市值"""
    cur = conn.cursor()

    # 统计需要更新的行数
    cur.execute("""
        SELECT COUNT(*) FROM stock_daily sd
        INNER JOIN stock_info si ON sd.code = si.code
        WHERE si.total_share IS NOT NULL AND si.total_share > 0
          AND sd.close_price IS NOT NULL AND sd.close_price > 0
          AND sd.market_cap IS NULL
    """)
    total_rows = cur.fetchone()[0]

    if total_rows == 0:
        print("[INFO] 没有需要更新的行，market_cap 已全部填充")
        return

    print(f"[INFO] 需要更新 {total_rows:,} 行")

    if dry_run:
        print("[DRY-RUN] 仅统计，不执行更新")
        # 抽样展示
        cur.execute("""
            SELECT sd.code, sd.trade_date, sd.close_price, si.total_share, si.float_share
            FROM stock_daily sd
            INNER JOIN stock_info si ON sd.code = si.code
            WHERE si.total_share IS NOT NULL AND si.total_share > 0
              AND sd.close_price IS NOT NULL AND sd.close_price > 0
              AND sd.market_cap IS NULL
            LIMIT 5
        """)
        for code, date, close, ts, fs in cur.fetchall():
            mc = float(ts) * float(close)
            cmc = float(fs or 0) * float(close)
            print(f"  {code} {date} close={close} ts={ts} → market_cap={mc:,.0f} circ_market_cap={cmc:,.0f}")
        return

    # 使用 SQL JOIN 批量 UPDATE（高效，避免逐行 Python 往返）
    # 分批处理，避免锁表太久
    updated = 0
    processed = 0
    start_time = time.time()

    print(f"[INFO] 开始批量 UPDATE ...")

    # MySQL 不支持多表 UPDATE + LIMIT，用子查询方式分批
    while processed < total_rows:
        # 先查出本批要更新的 stock_daily.id 列表
        sql_select = """
            SELECT sd.id FROM stock_daily sd
            INNER JOIN stock_info si ON sd.code = si.code
            WHERE si.total_share IS NOT NULL AND si.total_share > 0
              AND sd.close_price IS NOT NULL AND sd.close_price > 0
              AND sd.market_cap IS NULL
            LIMIT %s
        """
        cur.execute(sql_select, (batch_size,))
        ids = [row[0] for row in cur.fetchall()]

        if not ids:
            break

        # 用这些 id 做 UPDATE JOIN
        id_list = ",".join(str(i) for i in ids)
        sql_update = f"""
            UPDATE stock_daily sd
            INNER JOIN stock_info si ON sd.code = si.code
            SET sd.market_cap = ROUND(si.total_share * sd.close_price, 2),
                sd.circ_market_cap = ROUND(si.float_share * sd.close_price, 2)
            WHERE sd.id IN ({id_list})
        """
        cur.execute(sql_update)
        batch_updated = cur.rowcount
        conn.commit()
        updated += batch_updated
        processed += len(ids)

        elapsed = time.time() - start_time
        speed = processed / elapsed if elapsed > 0 else 0
        pct = min(100.0, processed / total_rows * 100)
        print(f"  进度: {pct:5.1f}% ({processed:,}/{total_rows:,})  速度: {speed:,.0f} 行/秒  本批: {batch_updated:,} 行")

        if batch_updated == 0:
            break

    elapsed = time.time() - start_time
    print(f"\n[DONE] 更新完成: {updated:,} 行, 耗时 {elapsed:.1f} 秒")


def verify(conn):
    """验证结果"""
    cur = conn.cursor()
    cur.execute("""
        SELECT 
          COUNT(*) as total,
          SUM(CASE WHEN market_cap IS NOT NULL THEN 1 ELSE 0 END) as has_cap,
          SUM(CASE WHEN circ_market_cap IS NOT NULL THEN 1 ELSE 0 END) as has_circ
        FROM stock_daily
    """)
    total, has_cap, has_circ = cur.fetchone()
    print(f"\n[验证] stock_daily 总计 {total:,} 行")
    print(f"  market_cap 非空: {has_cap:,} ({has_cap/total*100:.1f}%)")
    print(f"  circ_market_cap 非空: {has_circ:,} ({has_circ/total*100:.1f}%)")

    # 抽样检查
    cur.execute("""
        SELECT sd.code, si.symbol, sd.trade_date, sd.close_price, sd.market_cap, sd.circ_market_cap,
               si.total_share, si.float_share
        FROM stock_daily sd
        INNER JOIN stock_info si ON sd.code = si.code
        WHERE sd.market_cap IS NOT NULL
        ORDER BY RAND() LIMIT 5
    """)
    print("\n  抽样检查:")
    for code, symbol, date, close, mc, cmc, ts, fs in cur.fetchall():
        expected_mc = float(ts) * float(close)
        expected_cmc = float(fs or 0) * float(close)
        mc_ok = abs(float(mc) - expected_mc) < 1
        cmc_ok = abs(float(cmc or 0) - expected_cmc) < 1
        status = "✓" if (mc_ok and cmc_ok) else "✗"
        print(f"    {status} {symbol} {date} close={close} market_cap={mc:,.0f} (expect {expected_mc:,.0f})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="补全 stock_daily 表的市值字段")
    parser.add_argument("--batch-size", type=int, default=50000, help="每批更新行数")
    parser.add_argument("--dry-run", action="store_true", help="仅统计不执行")
    args = parser.parse_args()

    conn = pymysql.connect(**DB_CONFIG)
    try:
        share_map = load_share_map(conn)
        fill_market_cap(conn, share_map, batch_size=args.batch_size, dry_run=args.dry_run)
        if not args.dry_run:
            verify(conn)
    finally:
        conn.close()
