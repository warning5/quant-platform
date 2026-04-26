#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MySQL → ClickHouse 同步脚本（修复版：DELETE+INSERT upsert + 增量同步）"""
import pymysql, clickhouse_connect, os
from datetime import datetime, timedelta

MYSQL = {'host': 'localhost', 'port': 3306, 'user': 'root',
         'password': '123456', 'database': 'stock', 'charset': 'utf8mb4'}

CH_HOST = 'localhost'
CH_PORT = 8123
CH_USER = 'default'
CH_PASS = 'clickhouse'
CH_TABLE = 'stock.stock_daily'

def run():
    today = datetime.now().strftime('%Y-%m-%d')
    print(f'=== MySQL → ClickHouse 同步 (仅 {today}) ===')

    # 1. 读取 MySQL 当日数据
    mc = pymysql.connect(**MYSQL)
    cur = mc.cursor()
    cur.execute(f'''
        SELECT code, trade_date, name, open_price, close_price,
            high_price, low_price, pre_close, volume, amount, change_percent,
            change_amount, turnover_rate, pe_ttm, pb,
            create_time, update_time
        FROM stock_daily
        WHERE trade_date = %s
    ''', (today,))
    rows = list(cur.fetchall())
    cur.close()
    mc.close()

    if not rows:
        print(f'MySQL 无 {today} 数据，跳过')
        return

    print(f'MySQL {today} 数据: {len(rows)} 条')

    # 2. 连接 ClickHouse
    ch = clickhouse_connect.get_client(
        host=CH_HOST, port=CH_PORT,
        username=CH_USER, password=CH_PASS
    )

    # 3. DELETE CH 中当日数据（upsert 模式）
    codes = list(set(r[0] for r in rows))
    codes_list = "','".join(codes)
    del_sql = f"ALTER TABLE {CH_TABLE} DELETE WHERE trade_date = '{today}' AND code IN ('{codes_list}')"
    print(f'删除 CH 中 {today} 的 {len(codes)} 只股票...')
    ch.command(del_sql)

    # 等待删除完成
    ch.query("SELECT 1")
    import time; time.sleep(2)

    # 4. INSERT MySQL 数据
    converted = [list(row) for row in rows]
    ch.insert(CH_TABLE, converted,
        column_names=['code','trade_date','name','open_price','close_price',
                      'high_price','low_price','pre_close','volume','amount',
                      'change_percent','change_amount','turnover_rate',
                      'pe_ttm','pb',
                      'create_time','update_time'])
    ch.query("SELECT 1")  # 等待插入

    print(f'完成! 已同步 {len(rows)} 条到 CH')

    # 5. 清理重复 parts（OPTIMIZE FINAL）
    print('清理重复数据...')
    ch.command(f"OPTIMIZE TABLE {CH_TABLE} FINAL SETTINGS mutations_sync=2")
    ch.query("SELECT 1")
    print('完成!')

if __name__ == '__main__':
    os.environ['PYTHONIOENCODING'] = 'utf-8'
    run()
