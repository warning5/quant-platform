#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OPTIMIZE TABLE stock.stock_daily FINAL 去重"""
import sys
import time
sys.path.insert(0, '.')
from db_config import CLICKHOUSE_CONFIG
import clickhouse_connect

client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

print('=== OPTIMIZE TABLE stock.stock_daily FINAL ===')
print('这将会强制合并去重，耗时可能较长，请等待...\n')

t0 = time.time()

# 先查去重前的行数
r_before = client.query("SELECT count() FROM stock.stock_daily")
count_before = r_before.result_rows[0][0]
print(f'去重前行数: {count_before:,}')

# 执行 OPTIMIZE FINAL
print('\n正在执行 OPTIMIZE...')
try:
    client.command("OPTIMIZE TABLE stock.stock_daily FINAL")
    elapsed = time.time() - t0
    print(f'  ✓ OPTIMIZE 完成，耗时: {elapsed:.1f}s')
except Exception as e:
    elapsed = time.time() - t0
    print(f'  ✗ OPTIMIZE 失败: {e}')
    print(f'  耗时: {elapsed:.1f}s')

# 查去重后的行数
r_after = client.query("SELECT count() FROM stock.stock_daily")
count_after = r_after.result_rows[0][0]
print(f'\n去重后行数: {count_after:,}')
print(f'删除重复行数: {count_before - count_after:,}')

# 验证是否还有重复
r_dup = client.query("""
    SELECT count() as dup_groups
    FROM (
        SELECT code, trade_date
        FROM stock.stock_daily
        GROUP BY code, trade_date
        HAVING count() > 1
    )
""")
dup_groups = r_dup.result_rows[0][0]
print(f'剩余重复 (code+trade_date) 组数: {dup_groups}')

if dup_groups == 0:
    print('\n✓ 去重完成，无剩余重复数据')
else:
    print(f'\n⚠️ 仍有 {dup_groups} 组重复数据（可能 OPTIMIZE 未完成）')

client.close()
print('\n=== 完成 ===')
