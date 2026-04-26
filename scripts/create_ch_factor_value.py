#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
create_ch_factor_value.py
=========================
在 ClickHouse 中创建 factor_value 表，并从 MySQL 全量同步数据。

用法:
    python create_ch_factor_value.py          # 仅建表，不同步数据
    python create_ch_factor_value.py --sync   # 建表 + 从 MySQL 全量同步
"""

import sys
import pymysql
import clickhouse_connect
from datetime import datetime

# ── 连接配置（与 db_config.py 保持一致）─────────────────────────────
CH = dict(host='localhost', port=8123, username='default', password='clickhouse')
MYSQL = dict(host='localhost', port=3306, user='root', password='123456',
             database='stock', charset='utf8mb4')


def create_table(ch):
    """创建 factor_value 表（ReplacingMergeTree）"""
    print('[1/2] 删除旧表（如存在）...')
    ch.command('DROP TABLE IF EXISTS stock.factor_value')
    print('      旧表已删除')

    print('[2/2] 创建 ReplacingMergeTree 表...')
    ch.command("""
        CREATE TABLE stock.factor_value
        (
            id           Int64,
            factor_code  String,
            symbol       String,
            calc_date    Date,
            factor_val   Nullable(Float64),
            rank_value   Nullable(Float64),
            z_score      Nullable(Float64),
            created_at   DateTime DEFAULT now(),
            update_time  DateTime DEFAULT now()
        )
        ENGINE = ReplacingMergeTree(update_time)
        ORDER BY (factor_code, symbol, calc_date)
        SETTINGS index_granularity = 8192
    """)
    print('      factor_value 表已创建')
    print()


def sync_from_mysql(ch):
    """从 MySQL 全量同步 factor_value 数据"""
    print('=== 开始从 MySQL 同步数据 ===')
    mc = pymysql.connect(**MYSQL)
    cur = mc.cursor()

    cur.execute('SELECT COUNT(*) FROM factor_value')
    total = cur.fetchone()[0]
    print(f'MySQL 总记录数: {total:,}')

    if total == 0:
        print('MySQL 无数据，跳过同步')
        mc.close()
        return

    start = datetime.now()
    offset = 0
    batch = 50000
    synced = 0

    while offset < total:
        cur.execute(f"""
            SELECT id, factor_code, symbol, calc_date,
                   factor_val, rank_value, z_score, created_at
            FROM factor_value
            ORDER BY id
            LIMIT {batch} OFFSET {offset}
        """)
        rows = cur.fetchall()
        if not rows:
            break

        now_dt = datetime.now()
        converted = []
        for row in rows:
            r = list(row)
            # created_at 为 NULL 时补默认值
            if r[7] is None:
                r[7] = now_dt
            converted.append(r)

        ch.insert(
            'stock.factor_value',
            converted,
            column_names=['id', 'factor_code', 'symbol', 'calc_date',
                          'factor_val', 'rank_value', 'z_score', 'created_at']
        )

        synced += len(rows)
        offset += len(rows)
        elapsed = (datetime.now() - start).total_seconds()
        speed = synced / elapsed if elapsed > 0 else 0
        eta = (total - synced) / speed if speed > 0 else 0
        print(f'\r  进度: {synced:,}/{total:,} ({synced*100/total:.1f}%)'
              f' | {speed:,.0f} 条/s | 剩余 {eta/60:.1f} min', end='', flush=True)

    mc.close()
    elapsed = (datetime.now() - start).total_seconds()
    print(f'\n  同步完成，耗时 {elapsed:.0f}s')
    print()

    # OPTIMIZE FINAL 去重
    print('OPTIMIZE TABLE FINAL 去重中...')
    ch.command('OPTIMIZE TABLE stock.factor_value FINAL')
    print('  完成')
    print()


def verify(ch):
    """验证结果"""
    print('=== 验证 ===')
    r = ch.query('SELECT count() FROM stock.factor_value FINAL')
    cnt = r.result_rows[0][0]
    print(f'去重后总记录: {cnt:,}')

    r2 = ch.query("""
        SELECT factor_code, count() as cnt, min(calc_date), max(calc_date)
        FROM stock.factor_value FINAL
        GROUP BY factor_code
        ORDER BY cnt DESC
        LIMIT 10
    """)
    if r2.result_rows:
        print('\n因子统计 (Top 10):')
        for row in r2.result_rows:
            print(f'  {row[0]}: {row[1]:>8,} 条  {row[2]} ~ {row[3]}')
    else:
        print('(暂无数据，可在写入因子值后重新验证)')


def main():
    do_sync = '--sync' in sys.argv

    print(f'=== CH factor_value 表初始化  {datetime.now()} ===')
    print(f'同步数据: {"是" if do_sync else "否（仅建表）"}')
    print()

    ch = clickhouse_connect.get_client(**CH)

    create_table(ch)

    if do_sync:
        sync_from_mysql(ch)

    verify(ch)
    ch.close()
    print()
    print('=== 完成 ===')


if __name__ == '__main__':
    main()
