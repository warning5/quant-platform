"""
修复 CH stock_daily 中 name 字段为 NULL 的记录
原因：这12只股票的日线数据入库时没有带上 name 字段
方案：从 stock_info 获取正确 name，重新 INSERT 覆盖（ReplacingMergeTree 以 update_time 去重）
"""
import requests
import json
from datetime import datetime

CH_HOST = "http://localhost:8123"
CH_PARAMS = {"user": "default", "password": "123456", "database": "stock"}

def ch_query(sql, fmt="JSON"):
    resp = requests.post(CH_HOST, params={**CH_PARAMS, "default_format": fmt}, data=sql.encode())
    resp.raise_for_status()
    if fmt == "JSON":
        return resp.json()
    return resp.text

def ch_insert(sql):
    resp = requests.post(CH_HOST, params=CH_PARAMS, data=sql.encode())
    if resp.status_code != 200:
        raise Exception(f"INSERT failed: {resp.text[:500]}")
    return resp.text

# 1. 查出有哪些 code 的 name 为 NULL
print("Step 1: 找出 name 为 NULL 的 code ...")
r = ch_query("""
SELECT code, count() as rows
FROM stock_daily FINAL
WHERE name IS NULL OR name = ''
GROUP BY code
ORDER BY code
""")
null_codes = [(row['code'], int(row['rows'])) for row in r['data']]
print(f"  共 {len(null_codes)} 只股票，总行数: {sum(v for _,v in null_codes)}")
for c, n in null_codes:
    print(f"  {c}: {n} 行")

# 2. 从 stock_info 获取 name 映射
codes_str = ", ".join(f"'{c}'" for c,_ in null_codes)
r2 = ch_query(f"SELECT code, name FROM stock_info WHERE code IN ({codes_str})")
name_map = {row['code']: row['name'] for row in r2['data']}
print(f"\nStep 2: stock_info 中找到 {len(name_map)} 只股票的 name")

missing = [c for c,_ in null_codes if c not in name_map]
if missing:
    print(f"  ⚠️  stock_info 中缺失: {missing}，这些跳过")

# 3. 逐只股票：查出全量行，填入 name，INSERT 覆盖
now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
total_inserted = 0

for code, row_count in null_codes:
    name = name_map.get(code)
    if not name:
        print(f"  跳过 {code}（stock_info 无 name）")
        continue

    # 把 name 里的单引号转义
    name_escaped = name.replace("'", "\\'")

    print(f"\nStep 3: 修复 {code} ({name}) — {row_count} 行 ...", end="", flush=True)

    # 查出该 code 所有行
    r3 = ch_query(f"""
SELECT id, code, trade_date, open_price, close_price, high_price, low_price,
       pre_close, volume, amount, change_percent, change_amount,
       turnover_rate, pe_ttm, pb, create_time
FROM stock_daily FINAL
WHERE code = '{code}'
ORDER BY trade_date
""")
    rows = r3['data']

    if not rows:
        print(" 无数据，跳过")
        continue

    # 拼 INSERT 语句（VALUES 批量）
    vals = []
    for row in rows:
        def _v(x):
            if x is None:
                return "NULL"
            if isinstance(x, str):
                return f"'{x}'"
            return str(x)

        vals.append(
            f"({_v(row['id'])}, {_v(row['code'])}, {_v(row['trade_date'])}, "
            f"'{name_escaped}', "
            f"{_v(row['open_price'])}, {_v(row['close_price'])}, {_v(row['high_price'])}, "
            f"{_v(row['low_price'])}, {_v(row['pre_close'])}, {_v(row['volume'])}, "
            f"{_v(row['amount'])}, {_v(row['change_percent'])}, {_v(row['change_amount'])}, "
            f"{_v(row['turnover_rate'])}, {_v(row['pe_ttm'])}, {_v(row['pb'])}, "
            f"{_v(row['create_time'])}, '{now_str}')"
        )

    insert_sql = f"""
INSERT INTO stock_daily
(id, code, trade_date, name, open_price, close_price, high_price, low_price,
 pre_close, volume, amount, change_percent, change_amount,
 turnover_rate, pe_ttm, pb, create_time, update_time)
VALUES {', '.join(vals)}
"""
    ch_insert(insert_sql)
    total_inserted += len(rows)
    print(f" ✓ ({len(rows)} 行)")

# 4. 验证
print(f"\nStep 4: 验证修复结果 ...")
r4 = ch_query("""
SELECT countIf(name IS NULL OR name='') as still_null,
       countIf(name IS NOT NULL AND name!='') as has_name
FROM stock_daily FINAL
WHERE code IN (""" + codes_str + ")")
d = r4['data'][0]
print(f"  still_null = {d['still_null']}, has_name = {d['has_name']}")
print(f"\n✅ 完成！共 INSERT {total_inserted} 行，等待 ClickHouse 后台合并去重...")
print("  如需立即生效可运行: OPTIMIZE TABLE stock_daily FINAL（约需几分钟）")
