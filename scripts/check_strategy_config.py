import pymysql, json

conn = pymysql.connect(host='localhost', user='root', password='123456', database='stock', charset='utf8mb4')
cur = conn.cursor()

# 检查4个策略的因子配置
for code in ['DIVIDEND_LOW_VOL', 'LIMIT_UP_MOMENTUM', 'MARKET_SENTIMENT', 'VALUATION_RECOVERY_LLM']:
    cur.execute(f"SELECT strategy_code, strategy_name, factor_config_json FROM strategy_definition WHERE strategy_code='{code}'")
    row = cur.fetchone()
    if row:
        print(f"\n=== {row[0]} - {row[1]} ===")
        cfg = json.loads(row[2])
        for f, w in cfg.items():
            print(f"  {f}: {w}")
    else:
        print(f"\n未找到 {code}")

# 检查 LIMIT_UP_COUNT 是否在 factor_value 中有数据
cur.execute("SELECT COUNT(DISTINCT symbol) FROM stock.factor_value WHERE factor_code='LIMIT_UP_COUNT'")
print(f"\nLIMIT_UP_COUNT 覆盖: {cur.fetchone()[0]} 只股票")

# 检查 MOM5/VOLUME_RATIO 覆盖（涨停板策略核心因子）
cur.execute("SELECT factor_code, COUNT(DISTINCT symbol) FROM stock.factor_value WHERE factor_code IN ('MOM5','VOLUME_RATIO','LIMIT_UP_COUNT') GROUP BY factor_code")
for row in cur.fetchall():
    print(f"  {row[0]}: {row[1]} 只")

conn.close()
print("\n[DONE]")
