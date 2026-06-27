#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""迁移 stock_daily：ReplacingMergeTree(version) → ReplacingMergeTree(update_time)"""
import clickhouse_connect

CH_CONFIG = dict(
    host="172.19.72.140",
    port=8123,
    username="default",
    password="123456",
    database="stock",
)

ch = clickhouse_connect.get_client(**CH_CONFIG, settings={"receive_timeout": 600})

# Step 1: 创建新表
print("[Step 1/5] 创建 stock_daily_new...")
ch.command("""
CREATE TABLE stock.stock_daily_new (
    `id`              Int64,
    `code`            String,
    `trade_date`      Date,
    `name`            Nullable(String),
    `open_price`      Nullable(Float64),
    `close_price`     Nullable(Float64),
    `high_price`      Nullable(Float64),
    `low_price`       Nullable(Float64),
    `pre_close`       Nullable(Float64),
    `volume`          Nullable(Int64),
    `amount`          Nullable(Float64),
    `change_percent`  Nullable(Float64),
    `change_amount`   Nullable(Float64),
    `turn_over_rate`  Nullable(Float64),
    `pe_ttm`          Nullable(Float64),
    `pb`              Nullable(Float64),
    `data_source`     Nullable(String),
    `create_time`     DateTime,
    `update_time`     DateTime
) ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code, trade_date)
SETTINGS index_granularity = 8192
""")
print("  OK")

# Step 2: 迁移数据（按名列名，跳过 version 列）
print("[Step 2/5] 迁移数据（约 720 万行），请稍候...")
r = ch.query("SELECT count() FROM stock.stock_daily")
print(f"  源表: {r.result_rows[0][0]:,} 行")

migrate_sql = """
INSERT INTO stock.stock_daily_new
    (id, code, trade_date, name, open_price, close_price, high_price, low_price,
     pre_close, volume, amount, change_percent, change_amount, turn_over_rate,
     pe_ttm, pb, data_source, create_time, update_time)
SELECT
    id, code, trade_date, name, open_price, close_price, high_price, low_price,
    pre_close, volume, amount, change_percent, change_amount, turnover_rate,
    pe_ttm, pb, data_source, create_time, update_time
FROM stock.stock_daily
"""
ch.query(migrate_sql)

r = ch.query("SELECT count() FROM stock.stock_daily_new")
print(f"  新表: {r.result_rows[0][0]:,} 行")

# Step 3: 校验行数一致
r_old = ch.query("SELECT count() FROM stock.stock_daily").result_rows[0][0]
r_new = ch.query("SELECT count() FROM stock.stock_daily_new").result_rows[0][0]
if r_old == r_new:
    print(f"[Step 3/5] ✅ 行数一致: {r_old:,}")
else:
    print(f"[Step 3/5] ❌ 行数不一致! old={r_old:,} new={r_new:,}")
    raise SystemExit(1)

# Step 4: 重命名
print("[Step 4/5] 切换表名...")
ch.command("""
RENAME TABLE
    stock.stock_daily       TO stock.stock_daily_old,
    stock.stock_daily_new   TO stock.stock_daily
""")
print("  OK")

# Step 5: 验证新表 engine
print("[Step 5/5] 验证新表...")
rows = ch.query("SHOW CREATE TABLE stock.stock_daily").result_rows
create_sql = rows[0][0]
if "ReplacingMergeTree(update_time)" in create_sql:
    print("  ✅ Engine 正确: ReplacingMergeTree(update_time)")
else:
    print("  ❌ Engine 异常!")
    print(create_sql[:300])

print()
print("✅ 迁移完成！")
print("  旧表保留为: stock.stock_daily_old")
print("  确认新表正常后，可手动执行:")
print("    DROP TABLE stock.stock_daily_old;")
ch.close()
