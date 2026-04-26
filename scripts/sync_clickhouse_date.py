#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""增量同步：只同步指定日期的数据从 MySQL → ClickHouse

用法: python sync_clickhouse_date.py [YYYY-MM-DD]
"""
import sys
import time
import pymysql
import clickhouse_connect
from datetime import datetime

MYSQL = {'host': 'localhost', 'port': 3306, 'user': 'root',
         'password': '123456', 'database': 'stock', 'charset': 'utf8mb4'}
CH_HOST = 'localhost'
CH_PORT = 8123
CH_USER = 'default'
CH_PASS = 'clickhouse'
CH_TABLE = 'stock.stock_daily'


def wait_mutation(ch, target_date, timeout=120):
    """等待 ALTER TABLE DELETE mutation 完成（轮询 count 直到为 0）"""
    for i in range(timeout):
        r = ch.query(f"SELECT count() FROM {CH_TABLE} WHERE trade_date = toDate('{target_date}')")
        cnt = r.result_rows[0][0]
        if cnt == 0:
            return True
        time.sleep(1)
    return False


def sync_date(target_date):
    print(f'=== MySQL → ClickHouse 增量同步 {target_date} ===')
    print(f'时间: {datetime.now()}')

    mc = pymysql.connect(**MYSQL)
    cur = mc.cursor()

    # 查询 MySQL 中该日期的记录数
    cur.execute('SELECT COUNT(*) FROM stock_daily WHERE trade_date = %s', (target_date,))
    total = cur.fetchone()[0]
    print(f'MySQL {target_date} 记录数: {total:,}')

    if total == 0:
        print('无数据，跳过')
        mc.close()
        return

    ch = clickhouse_connect.get_client(
        host=CH_HOST, port=CH_PORT,
        username=CH_USER, password=CH_PASS
    )

    # Step 1: 查看当前 CH 中该日期有多少条
    r = ch.query(f"SELECT count() FROM {CH_TABLE} WHERE trade_date = toDate('{target_date}')")
    old_cnt = r.result_rows[0][0]
    print(f'ClickHouse 当前 {target_date} 记录数: {old_cnt:,}')

    if old_cnt > 0:
        # Step 2: 先删除 ClickHouse 中该日期的旧数据
        ch.command(
            f"ALTER TABLE {CH_TABLE} DELETE WHERE trade_date = toDate('{target_date}')"
        )
        print(f'已提交 DELETE mutation，等待完成...')

        # Step 3: 等待 mutation 完成
        start_wait = time.time()
        ok = wait_mutation(ch, target_date)
        elapsed = time.time() - start_wait
        if ok:
            print(f'Mutation 完成（等待 {elapsed:.1f}s）')
        else:
            # 强制 OPTIMIZE 加速
            print(f'Mutation 等待超时（{elapsed:.1f}s），尝试 OPTIMIZE...')
            ch.command(f'OPTIMIZE TABLE {CH_TABLE} FINAL')
            ok = wait_mutation(ch, target_date, timeout=60)
            if not ok:
                print(f'ERROR: 无法删除旧数据，跳过同步')
                mc.close()
                ch.close()
                return

        # 验证
        r = ch.query(f"SELECT count() FROM {CH_TABLE} WHERE trade_date = toDate('{target_date}')")
        after_del = r.result_rows[0][0]
        print(f'删除后剩余: {after_del} 条')

    # Step 4: 从 MySQL 读取并同步
    print(f'从 MySQL 读取数据...')
    cur.execute('''
        SELECT code, trade_date, name, open_price, close_price,
               high_price, low_price, pre_close, volume, amount, change_percent,
               change_amount, turnover_rate, pe_ttm, pb,
               create_time, update_time
        FROM stock_daily WHERE trade_date = %s
        ORDER BY code
    ''', (target_date,))
    rows = cur.fetchall()
    print(f'读取到 {len(rows)} 条记录')

    converted = [list(row) for row in rows]
    ch.insert(CH_TABLE, converted,
              column_names=['code', 'trade_date', 'name', 'open_price', 'close_price',
                            'high_price', 'low_price', 'pre_close', 'volume', 'amount',
                            'change_percent', 'change_amount', 'turnover_rate',
                            'pe_ttm', 'pb',
                            'create_time', 'update_time'])

    # Step 5: 验证
    r = ch.query(f"SELECT count() FROM {CH_TABLE} WHERE trade_date = toDate('{target_date}')")
    ch_total = r.result_rows[0][0]
    print(f'同步完成: MySQL={total:,} → ClickHouse={ch_total:,}')

    if ch_total != total:
        print(f'WARNING: 数量不一致! 差 {abs(ch_total - total):,} 条')
        # 可能是 mutation 未完全执行，等待一会再查
        print('等待 5s 后重新检查...')
        time.sleep(5)
        r = ch.query(f"SELECT count() FROM {CH_TABLE} WHERE trade_date = toDate('{target_date}')")
        ch_total = r.result_rows[0][0]
        print(f'再次查询: ClickHouse={ch_total:,}')
        if ch_total != total:
            print(f'仍然不一致，可能需要手动 TRUNCATE + 全量同步')

    mc.close()
    ch.close()
    print(f'=== 完成 ===')


if __name__ == '__main__':
    date_str = sys.argv[1] if len(sys.argv) > 1 else '2026-04-22'
    sync_date(date_str)
