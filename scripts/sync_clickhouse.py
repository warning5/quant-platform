#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MySQL → ClickHouse 同步脚本（增量版）

同步 MySQL stock_daily 中指定日期的数据到 ClickHouse。
使用 FINAL 预过滤 + 直接 INSERT（不产生 mutation），与 db_helper.upsert_daily() 一致。
"""
import pymysql
import clickhouse_connect
import os
import sys
import time
from datetime import datetime, date

MYSQL = {'host': 'localhost', 'port': 3306, 'user': 'root',
         'password': '123456', 'database': 'stock', 'charset': 'utf8mb4'}

CH_HOST = 'localhost'
CH_PORT = 8123
CH_USER = 'default'
CH_PASS = '123456'
CH_DB = 'stock'
CH_TABLE = 'stock.stock_daily'

# CH 列名（与 db_helper.py DAILY_COLUMNS 一致）
CH_COLUMNS = [
    'id', 'code', 'trade_date', 'name', 'open_price', 'close_price',
    'high_price', 'low_price', 'pre_close', 'volume', 'amount',
    'change_percent', 'change_amount', 'turnover_rate',
    'pe_ttm', 'pb', 'create_time', 'update_time',
]


def sync_date(sync_date_str: str, batch_size: int = 50000):
    """同步指定日期的 MySQL 数据到 ClickHouse"""
    print(f'=== MySQL → ClickHouse 同步 ({sync_date_str}) ===')

    # 1. 读取 MySQL 数据
    mc = pymysql.connect(**MYSQL)
    cur = mc.cursor()
    cur.execute('''
        SELECT 0, code, trade_date, name, open_price, close_price,
            high_price, low_price, pre_close, volume, amount, change_percent,
            change_amount, turnover_rate, pe_ttm, pb,
            create_time, update_time
        FROM stock_daily
        WHERE trade_date = %s
    ''', (sync_date_str,))
    rows = list(cur.fetchall())
    cur.close()
    mc.close()

    if not rows:
        print(f'MySQL 无 {sync_date_str} 数据，跳过')
        return 0

    print(f'MySQL {sync_date_str} 数据: {len(rows)} 条')

    # 2. 连接 ClickHouse
    ch = clickhouse_connect.get_client(
        host=CH_HOST, port=CH_PORT,
        username=CH_USER, password=CH_PASS,
        database=CH_DB,
    )

    # 3. FINAL 预过滤：找出 CH 中已存在的 (code, trade_date)
    existing_keys = set()
    codes_in_batch = list(set(str(r[1]) for r in rows))
    for ci in range(0, len(codes_in_batch), 500):
        chunk = codes_in_batch[ci:ci + 500]
        code_ph = ", ".join(f"'{c}'" for c in chunk)
        r = ch.query(
            f"SELECT code, trade_date FROM stock_daily FINAL "
            f"WHERE code IN ({code_ph}) AND trade_date = '{sync_date_str}'"
        )
        for row_data in r.result_rows:
            existing_keys.add((str(row_data[0]), row_data[1]))

    print(f'CH 已存在: {len(existing_keys)} 条')

    # 4. 过滤掉已存在且无需更新的行
    insert_rows = []
    skipped = 0
    now_dt = datetime.now()
    for row in rows:
        code = str(row[1])
        td = row[2]
        if isinstance(td, str):
            td = date.fromisoformat(td)
        elif isinstance(td, datetime):
            td = td.date()

        key = (code, td)
        if key in existing_keys:
            skipped += 1
            continue

        # 补齐 id / create_time / update_time
        vals = list(row)
        if vals[0] == 0 or vals[0] is None:
            vals[0] = 0
        if vals[16] is None:
            vals[16] = now_dt
        if vals[17] is None:
            vals[17] = now_dt
        insert_rows.append(vals)

    if skipped > 0:
        print(f'跳过已存在: {skipped} 条')

    if not insert_rows:
        print('无新增数据需要写入')
        return 0

    # 5. 分批 INSERT
    total_inserted = 0
    for i in range(0, len(insert_rows), batch_size):
        batch = insert_rows[i:i + batch_size]
        ch.insert(CH_TABLE, batch, column_names=CH_COLUMNS)
        total_inserted += len(batch)
        print(f'  写入批次 {i // batch_size + 1}: {len(batch)} 条')

    print(f'完成! 新增写入 {total_inserted} 条，跳过 {skipped} 条')
    return total_inserted


def run():
    """默认同步今天的数据"""
    today = datetime.now().strftime('%Y-%m-%d')
    sync_date(today)


if __name__ == '__main__':
    os.environ['PYTHONIOENCODING'] = 'utf-8'
    if len(sys.argv) > 1:
        # 支持指定日期: python sync_clickhouse.py 2026-04-29
        sync_date(sys.argv[1])
    else:
        run()
