"""获取10年国债收益率历史数据并存入MySQL（P2-2）

数据源：
  akshare bond_gb_zh_sina（新浪财经·中债国债收益率曲线，每日序列）
  说明：原 bond_china_yield() 自某 akshare 版本起返回数据卡在 2020 年且
        不再支持 start_date/end_date 参数，已失效，故切换至此接口。
        bond_gb_zh_sina() 返回字段 date/open/high/low/close/volume，
        其中 close 即 10 年期国债收益率(%)。本脚本按日期区间过滤写入。
  注：该接口仅提供 10Y 序列（约最近 4 年交易日），2Y 与利差暂置空。
"""
import sys
import argparse
import pymysql
import akshare as ak
from datetime import datetime, timedelta
from db_config import MYSQL_CONFIG

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

parser = argparse.ArgumentParser(description='Update bond yield data')
parser.add_argument('--start-date', type=str, help='Start date (YYYY-MM-DD)')
parser.add_argument('--end-date', type=str, help='End date (YYYY-MM-DD)')
parser.add_argument('--force', action='store_true', help='Force update all data')
args = parser.parse_args()

# ─── 数据库连接 ───────────────────────────────────────────────
conn = pymysql.connect(**MYSQL_CONFIG)
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

    数据源 ak.bond_gb_zh_sina() 返回完整每日序列（约最近 4 年交易日），
    按日期区间 [start_date, end_date] 过滤后取 close 作为 10Y 收益率(%)。
    2Y 与利差暂无法从该接口获取，置空。
    """
    print(f"  获取 {start_date} ~ {end_date} 的数据...")
    try:
        df = ak.bond_gb_zh_sina()
        if df is None or len(df) == 0:
            print(f"    未获取到国债收益率数据")
            return []

        df = df.copy()
        df['date'] = df['date'].astype(str)
        seg = df[(df['date'] >= start_date) & (df['date'] <= end_date)]
        if len(seg) == 0:
            print(f"    区间内无数据")
            return []

        records = []
        for _, row in seg.iterrows():
            date_str = str(row['date'])
            try:
                yield_10y = float(row['close'])
            except (ValueError, TypeError):
                continue
            # 合理性校验：10Y 国债收益率通常落在 0.5%~5% 区间
            if not (0.5 <= yield_10y <= 5.0):
                continue
            records.append((date_str, yield_10y, None, None))

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
