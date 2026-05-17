import pymysql
from db_config import MYSQL_CONFIG

conn = pymysql.connect(**MYSQL_CONFIG)
cur = conn.cursor()
cur.execute("SELECT COUNT(*) FROM stock_info WHERE code NOT LIKE '920%'")
total = cur.fetchone()[0]
cur.execute("SELECT COUNT(DISTINCT stock_code) FROM stock_fund_holder")
with_data = cur.fetchone()[0]
cur.close()
conn.close()
print(f"stock_info总数(排除北交所): {total}")
print(f"已有基金持仓: {with_data}")
print(f"缺失基金持仓: {total - with_data}")
