"""获取10年国债收益率历史数据并存入MySQL（P2-2）"""
import sys
import pymysql
import akshare as ak

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

conn = pymysql.connect(host='localhost', port=3306, user='root', password='123456', database='stock', charset='utf8mb4')
cursor = conn.cursor()

# 建表（如果不存在）
cursor.execute("""
CREATE TABLE IF NOT EXISTS macro_bond_yield (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL,
    yield_10y DOUBLE COMMENT '10年期国债收益率(%)',
    yield_2y DOUBLE COMMENT '2年期国债收益率(%)',
    yield_spread DOUBLE COMMENT '10年-2年利差(%)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
""")
conn.commit()

# 获取数据
df = ak.bond_china_yield()
gov = df[df.iloc[:, 0] == '中债国债收益率曲线']

if len(gov) == 0:
    print("ERROR: 未找到中债国债收益率曲线数据")
    cursor.close()
    conn.close()
    sys.exit(1)

# 列名：曲线名称, 日期, 3月, 6月, 1年, 3年, 5年, 7年, 10年, 30年
inserted = 0
updated = 0
for _, row in gov.iterrows():
    date_str = str(row.iloc[1])
    yield_10y = row.get('10年')
    yield_2y = row.get('2年') if '2年' in row.index else None

    if yield_10y is None:
        continue

    try:
        yield_10y = float(yield_10y)
    except (ValueError, TypeError):
        continue

    yield_2y_val = None
    if yield_2y is not None:
        try:
            yield_2y_val = float(yield_2y)
        except (ValueError, TypeError):
            pass

    spread = None
    if yield_10y is not None and yield_2y_val is not None:
        spread = yield_10y - yield_2y_val

    # 使用 INSERT ON DUPLICATE KEY UPDATE
    cursor.execute(
        """INSERT INTO macro_bond_yield (trade_date, yield_10y, yield_2y, yield_spread)
           VALUES (%s, %s, %s, %s)
           ON DUPLICATE KEY UPDATE yield_10y=VALUES(yield_10y), yield_2y=VALUES(yield_2y), yield_spread=VALUES(yield_spread)""",
        (date_str, yield_10y, yield_2y_val, spread)
    )
    if cursor.rowcount == 1:
        inserted += 1
    else:
        updated += 1

conn.commit()
print(f"Done! inserted={inserted}, updated={updated}, total={inserted + updated}")

cursor.close()
conn.close()
