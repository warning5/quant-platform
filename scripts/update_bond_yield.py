"""获取10年国债收益率历史数据并存入MySQL（P2-2）

数据源：
  akshare bond_china_yield（中债国债收益率曲线，支持 start_date/end_date 参数）
  接口目标地址: http://yield.chinabond.com.cn/cbweb-pbc-web/pbc/historyQuery
  注意：start_date 到 end_date 需要小于一年，所以需要分段获取
"""
import sys
import argparse
import pymysql
import akshare as ak
from datetime import datetime, timedelta

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

parser = argparse.ArgumentParser(description='Update bond yield data')
parser.add_argument('--start-date', type=str, help='Start date (YYYY-MM-DD)')
parser.add_argument('--end-date', type=str, help='End date (YYYY-MM-DD)')
parser.add_argument('--force', action='store_true', help='Force update all data')
args = parser.parse_args()

# ─── 数据库连接 ───────────────────────────────────────────────
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

# ─── 获取数据库中最新日期 ────────────────────────────────────
db_latest = None
cursor.execute("SELECT MAX(trade_date) FROM macro_bond_yield")
row = cursor.fetchone()
if row and row[0]:
    db_latest = str(row[0])
    print(f"数据库最新日期: {db_latest}")

# ─── 数据源: akshare bond_china_yield（中债国债收益率曲线） ──

def fetch_bond_yield_by_period(start_date, end_date):
    """获取指定日期范围的国债收益率数据

    注意：akshare bond_china_yield 接口限制 start_date 到 end_date 必须小于一年
    接口期望 YYYYMMDD 格式（8位无分隔符）
    """
    print(f"  获取 {start_date} ~ {end_date} 的数据...")
    try:
        # akshare 接口要求 YYYYMMDD 格式
        start_fmt = start_date.replace('-', '')
        end_fmt = end_date.replace('-', '')
        df = ak.bond_china_yield(start_date=start_fmt, end_date=end_fmt)
        gov = df[df.iloc[:, 0] == '中债国债收益率曲线']
        if len(gov) == 0:
            print(f"    未找到中债国债收益率曲线数据")
            return []

        records = []
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
                spread = round(yield_10y - yield_2y_val, 4)

            records.append((date_str, yield_10y, yield_2y_val, spread))

        print(f"    获取 {len(records)} 条记录")
        return records
    except Exception as e:
        print(f"    获取失败: {e}")
        return []


def fetch_all_bond_yield(start_date, end_date):
    """分段获取所有国债收益率数据（每次最多一年）"""
    print(f"\n[数据源] akshare bond_china_yield（中债国债收益率曲线）")
    print(f"日期范围: {start_date} ~ {end_date}")

    all_records = []
    current_start = datetime.strptime(start_date, '%Y-%m-%d')
    final_end = datetime.strptime(end_date, '%Y-%m-%d')

    while current_start < final_end:
        # 每次最多获取一年的数据
        current_end = current_start + timedelta(days=364)
        if current_end > final_end:
            current_end = final_end

        segment_start = current_start.strftime('%Y-%m-%d')
        segment_end = current_end.strftime('%Y-%m-%d')

        records = fetch_bond_yield_by_period(segment_start, segment_end)
        all_records.extend(records)

        # 下一段从当前结束日期的下一天开始
        current_start = current_end + timedelta(days=1)

    print(f"\n总计获取 {len(all_records)} 条记录")
    return all_records


# ─── 写入数据库 ──────────────────────────────────────────────
def write_to_db(records):
    """批量写入数据库"""
    inserted = 0
    updated = 0
    for date_str, y10, y2, spread in records:
        try:
            cursor.execute(
                """INSERT INTO macro_bond_yield (trade_date, yield_10y, yield_2y, yield_spread)
                   VALUES (%s, %s, %s, %s)
                   ON DUPLICATE KEY UPDATE yield_10y=VALUES(yield_10y), yield_2y=VALUES(yield_2y), yield_spread=VALUES(yield_spread)""",
                (date_str, y10, y2, spread)
            )
            if cursor.rowcount == 1:
                inserted += 1
            else:
                updated += 1
        except Exception as e:
            print(f"  写入失败 {date_str}: {e}")
    conn.commit()
    return inserted, updated


# ─── 主逻辑 ──────────────────────────────────────────────────
start_date = args.start_date
end_date = args.end_date or datetime.now().strftime('%Y-%m-%d')

if not args.force and db_latest:
    # 增量更新：从数据库最新日期的下一天开始
    next_day = (datetime.strptime(db_latest, '%Y-%m-%d') + timedelta(days=1)).strftime('%Y-%m-%d')
    if not start_date or start_date <= db_latest:
        start_date = next_day
        print(f"增量更新: 从 {start_date} 开始")

if not start_date:
    # 默认从2002年开始（中债国债收益率曲线最早数据）
    start_date = '2002-01-01'

print(f"日期范围: {start_date} ~ {end_date}")

# 获取数据
records = fetch_all_bond_yield(start_date, end_date)

# 写入数据库
total_inserted = 0
total_updated = 0
if records:
    ins, upd = write_to_db(records)
    total_inserted += ins
    total_updated += upd
    print(f"写入: inserted={ins}, updated={upd}")

# ─── 统计结果 ──────────────────────────────────────────────
cursor.execute("SELECT COUNT(*), MIN(trade_date), MAX(trade_date) FROM macro_bond_yield")
row = cursor.fetchone()
print(f"\n总计: inserted={total_inserted}, updated={total_updated}")
print(f"数据库: 共 {row[0]} 条, 日期范围 {row[1]} ~ {row[2]}")

cursor.close()
conn.close()
