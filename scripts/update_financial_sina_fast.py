#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Step 3 并发版：新浪三大表快速采集
使用线程池并发，比原版快 5-8 倍

用法：
  python update_data/update_financial_sina_fast.py [--start-code 603100] [--workers 5] [--force]
"""

import argparse
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import akshare as ak
import pymysql

DB_CONFIG = {
    'host': 'localhost',
    'port': 3306,
    'user': 'root',
    'password': '123456',
    'database': 'stock',
    'charset': 'utf8mb4',
}

# 直接从主脚本导入映射和工具函数
sys.path.insert(0, r'c:\Users\warning5\WorkBuddy\Claw\update_data')
from update_financial_data import (
    INCOME_MAP, BALANCE_MAP, CASHFLOW_MAP,
    parse_number, report_type_from_date, end_date_from_report,
    fetch_sina_report, save_sina_to_table,
)


def process_one_stock(args_tuple):
    """处理单只股票：拉取3张表并写入数据库（线程安全）"""
    code, force = args_tuple
    conn = pymysql.connect(**DB_CONFIG)
    try:
        total = 0
        for symbol_type, table_name, col_map in [
            ('利润表', 'stock_income', INCOME_MAP),
            ('资产负债表', 'stock_balance', BALANCE_MAP),
            ('现金流量表', 'stock_cashflow', CASHFLOW_MAP),
        ]:
            df = fetch_sina_report(code, symbol_type, timeout=15)
            n = save_sina_to_table(df, code, table_name, col_map, conn, force)
            total += n
        return code, total, None
    except Exception as e:
        return code, 0, str(e)
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description='Step 3 并发版')
    parser.add_argument('--start-code', type=str, default=None, help='从指定代码开始')
    parser.add_argument('--workers', type=int, default=5, help='并发线程数（默认5）')
    parser.add_argument('--force', action='store_true', help='强制重新采集')
    args = parser.parse_args()

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    cursor.execute("SELECT code FROM stock_info ORDER BY code")
    codes = [r[0] for r in cursor.fetchall()]
    conn.close()

    if args.start_code:
        idx = next((i for i, c in enumerate(codes) if c >= args.start_code), len(codes))
        codes = codes[idx:]

    print(f"待采集: {len(codes)} 只股票, 并发: {args.workers} 线程")
    if args.force:
        print("强制模式: ON")
    start_time = time.time()

    done = 0
    failed = 0
    total_inserted = 0

    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(process_one_stock, (code, args.force)): code for code in codes}

        for future in as_completed(futures):
            code, n, err = future.result()
            done += 1
            total_inserted += n
            if err:
                failed += 1

            # 每 50 只输出一次进度
            if done % 50 == 0 or done == len(codes):
                elapsed = time.time() - start_time
                speed = done / elapsed if elapsed > 0 else 0
                eta = (len(codes) - done) / speed if speed > 0 else 0
                print(f"  {done}/{len(codes)} ({done/len(codes)*100:.1f}%)  "
                      f"插入 {total_inserted} 条  失败 {failed}  "
                      f"速度 {speed:.1f}只/秒  剩余 {eta/60:.0f}分钟  "
                      f"当前 {code}")

    elapsed = time.time() - start_time
    print(f"\n完成! {done} 只, {total_inserted} 条, 失败 {failed}, 耗时 {elapsed/60:.1f} 分钟")


if __name__ == '__main__':
    main()
