#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 ClickHouse stock_daily 表的重复数据"""
import sys
sys.path.insert(0, '.')
from db_config import CLICKHOUSE_CONFIG
import clickhouse_connect

client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

print('=== 检查 stock_daily 重复数据 ===\n')

# 方法1：查找有重复的 code + trade_date 组合
print('1. 检查 (code + trade_date) 重复组合...')
result = client.query("""
    SELECT 
        code,
        trade_date,
        count() as cnt
    FROM stock.stock_daily
    GROUP BY code, trade_date
    HAVING count() > 1
    ORDER BY cnt DESC
    LIMIT 20
""")

print(f'   重复 (code + trade_date) 组合数: {len(result.result_rows)}')
if result.result_rows:
    print('\n   前 20 组重复数据:')
    print(f'   {"code":<10} {"trade_date":<12} {"重复次数":<10}')
    print('   ' + '-' * 32)
    for row in result.result_rows:
        print(f'   {row[0]:<10} {str(row[1]):<12} {row[2]:<10}')
else:
    print('   ✓ 无 (code + trade_date) 重复')

# 方法2：统计总重复条数
print('\n2. 统计重复行总数...')
result2 = client.query("""
    SELECT sum(cnt - 1) as total_dup_rows
    FROM (
        SELECT 
            code,
            trade_date,
            count() as cnt
        FROM stock.stock_daily
        GROUP BY code, trade_date
        HAVING cnt > 1
    )
""")

if result2.result_rows and result2.result_rows[0][0] is not None:
    total_dup = result2.result_rows[0][0]
    print(f'   重复行总数: {total_dup}')
else:
    print('   ✓ 无重复行')

# 方法3：检查 id 重复
print('\n3. 检查 id 重复...')
result3 = client.query("""
    SELECT 
        id,
        count() as cnt
    FROM stock.stock_daily
    GROUP BY id
    HAVING count() > 1
    LIMIT 20
""")

print(f'   重复 id 数量: {len(result3.result_rows)}')
if result3.result_rows:
    print('\n   前 20 个重复 id:')
    for row in result3.result_rows:
        print(f'      id={row[0]}, 重复次数={row[1]}')
else:
    print('   ✓ 无 id 重复')

# 方法4：表总行数 vs 唯一 (code, trade_date) 数
print('\n4. 总行数 vs 唯一 (code, trade_date) 数...')
result4 = client.query("""
    SELECT 
        count() as total_rows,
        count(DISTINCT (code, trade_date)) as unique_pairs
    FROM stock.stock_daily
""")

total_rows = result4.result_rows[0][0]
unique_pairs = result4.result_rows[0][1]
print(f'   总行数: {total_rows:,}')
print(f'   唯一 (code, trade_date) 数: {unique_pairs:,}')
if total_rows > unique_pairs:
    print(f'   ⚠️ 有 {total_rows - unique_pairs:,} 条重复数据')
else:
    print('   ✓ 无重复数据')

client.close()
print('\n=== 检查完成 ===')
