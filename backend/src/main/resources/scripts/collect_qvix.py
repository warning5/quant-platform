"""
采集中国QVIX(恐慌指数)数据，存入ClickHouse market_sentiment表

QVIX 是上证50ETF期权隐含波动率，相当于中国版VIX，
数值越高代表市场恐慌情绪越重，是市场情绪策略的核心信号。

AKShare 接口:
  - index_option_50etf_qvix()      -> 50ETF QVIX (最主流)
  - index_option_300etf_qvix()    -> 300ETF QVIX
  - index_option_500etf_qvix()   -> 500ETF QVIX (如有)
  - index_option_1000etf_qvix()  -> 1000ETF QVIX (如有)
  - index_option_cyb_qvix()      -> 创业板 QVIX (如有)
  - index_option_kc_qvix()       -> 科创板 QVIX (如有)

用法:
  python scripts/collect_qvix.py                  # 全量采集
  python scripts/collect_qvix.py --incremental    # 增量：只补最近N天
  python scripts/collect_qvix.py --date 2026-06-15  # 指定日期
"""
import argparse
import math
import sys
import time
from datetime import date, timedelta

import akshare as ak
import pymysql
import clickhouse_connect

from db_config import CLICKHOUSE_CONFIG, MYSQL_CONFIG


def init_mysql_table():
    """确保MySQL market_sentiment表存在(元数据索引)"""
    conn = pymysql.connect(**MYSQL_CONFIG)
    try:
        cur = conn.cursor()
        cur.execute("""
            CREATE TABLE IF NOT EXISTS market_sentiment (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                trade_date DATE NOT NULL,
                indicator VARCHAR(32) NOT NULL,
                value DOUBLE NOT NULL,
                UNIQUE KEY uk_date_ind (trade_date, indicator)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
        conn.commit()
        cur.close()
    except Exception as e:
        print(f"[WARN] MySQL market_sentiment表初始化失败: {e}")
    finally:
        conn.close()


def init_ch_table():
    """确保 ClickHouse market_sentiment 表存在"""
    ch = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
    try:
        ch.command("""
            CREATE TABLE IF NOT EXISTS market_sentiment (
                trade_date Date,
                indicator  String,
                value      Float64
            ) ENGINE = MergeTree()
            PARTITION BY toYYYYMM(trade_date)
            ORDER BY (indicator, trade_date)
        """)
        print("[OK] ClickHouse market_sentiment 表就绪")
    except Exception as e:
        print(f"[WARN] CH表初始化: {e}")
    finally:
        ch.close()


def collect_qvix(etf_name, func):
    """
    采集单个ETF的QVIX数据
    etf_name: 如 '50ETF', '300ETF'
    func: akshare调用函数
    """
    print(f"\n[采集] QVIX {etf_name} ...")
    try:
        df = func()
        if df is None or df.empty:
            print(f"  {etf_name}: 无数据")
            return []

        # 标准化列名
        df.columns = [c.lower().strip() for c in df.columns]
        # 预期列: date/date/index, qvix/qvix_value/value
        date_col = None
        val_col = None
        for c in df.columns:
            if 'date' in c or c == 'index':
                date_col = c
            if 'qvix' in c or 'value' == c or 'close' == c:
                val_col = c
        if not date_col or not val_col:
            print(f"  {etf_name}: 无法识别列名 {list(df.columns)}")
            return []

        records = []
        for _, row in df.iterrows():
            dt = row[date_col]
            val = row[val_col]
            if dt is None or val is None:
                continue
            try:
                if isinstance(dt, str):
                    dt = date.fromisoformat(str(dt)[:10])
                elif hasattr(dt, 'date'):
                    dt = dt.date()
                v = float(val)
                if v <= 0 or math.isnan(v) or math.isinf(v):
                    continue
                records.append((dt, f"QVIX_{etf_name}", v))
            except (ValueError, TypeError):
                continue

        print(f"  {etf_name}: 读取 {len(records)} 条")
        return records
    except Exception as e:
        print(f"  {etf_name}: 采集失败: {e}")
        return []


def write_records(records):
    """写入 ClickHouse + MySQL，分批避免分区过多"""
    if not records:
        return 0

    # 去重
    seen = set()
    unique = []
    for dt, ind, val in records:
        key = (str(dt), ind)
        if key not in seen:
            seen.add(key)
            unique.append((dt, ind, val))

    if not unique:
        return 0

    # 按日期排序，分批写入(每1000条一批，避免分区过多)
    unique.sort(key=lambda x: x[0])
    BATCH = 1000
    total = 0

    for i in range(0, len(unique), BATCH):
        batch = unique[i:i+BATCH]
        # 写入 CH
        ch = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
        try:
            ch.insert("market_sentiment", batch,
                     column_names=["trade_date", "indicator", "value"])
            total += len(batch)
        except Exception as e:
            print(f"  CH写入失败(批次{i//BATCH}): {e}")
        finally:
            ch.close()

    print(f"  CH写入: {total} 条")

    # 写入 MySQL (元数据，分批)
    conn = pymysql.connect(**MYSQL_CONFIG)
    try:
        cur = conn.cursor()
        for dt, ind, val in unique:
            cur.execute("""
                INSERT INTO market_sentiment (trade_date, indicator, value)
                VALUES (%s, %s, %s)
                ON DUPLICATE KEY UPDATE value = VALUES(value)
            """, (dt, ind, val))
        conn.commit()
        cur.close()
    except Exception as e:
        print(f"  MySQL写入失败: {e}")
    finally:
        conn.close()

    return total


def get_last_date(indicator):
    """查询CH中最新的日期"""
    ch = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
    try:
        result = ch.query("""
            SELECT max(trade_date) FROM market_sentiment
            WHERE indicator = %s
        """, (indicator,))
        return result.first_row[0] if result.first_row[0] else None
    except Exception:
        return None
    finally:
        ch.close()


def main():
    parser = argparse.ArgumentParser(description="采集QVIX中国恐慌指数")
    parser.add_argument("--incremental", action="store_true", help="增量模式")
    parser.add_argument("--date", default=None, help="指定日期 YYYY-MM-DD")
    args = parser.parse_args()

    init_mysql_table()
    init_ch_table()

    # 定义需要采集的ETF
    etf_list = [
        ("50ETF",   ak.index_option_50etf_qvix),
        ("300ETF",  ak.index_option_300etf_qvix),
    ]

    # 尝试扩展ETF列表
    for name, func_name in [("500ETF", "index_option_500etf_qvix"),
                              ("1000ETF", "index_option_1000etf_qvix"),
                              ("CYB",      "index_option_cyb_qvix"),
                              ("KC",       "index_option_kc_qvix")]:
        if hasattr(ak, func_name):
            etf_list.append((name, getattr(ak, func_name)))
            print(f"[INFO] 发现额外QVIX接口: {name}")
        else:
            print(f"[INFO] 无{name} QVIX接口，跳过")

    all_records = []

    if args.date:
        # 指定日期模式：只采当天（AKShare通常不支持单日查询，需全量过滤）
        print(f"指定日期模式: {args.date}，将全量采集后过滤")
        target_date = date.fromisoformat(args.date)
        for etf_name, func in etf_list:
            recs = collect_qvix(etf_name, func)
            recs = [(dt, ind, val) for dt, ind, val in recs if dt == target_date]
            all_records.extend(recs)
    elif args.incremental:
        # 增量：只采最近7天
        for etf_name, func in etf_list:
            last = get_last_date(f"QVIX_{etf_name}")
            if last:
                print(f"  QVIX_{etf_name} 最新日期: {last}")
        print("[增量] 全量采集(QVIX接口不支持日期范围，将全量后去重)")
        for etf_name, func in etf_list:
            recs = collect_qvix(etf_name, func)
            all_records.extend(recs)
    else:
        # 全量
        for etf_name, func in etf_list:
            recs = collect_qvix(etf_name, func)
            all_records.extend(recs)

    n = write_records(all_records)
    print(f"\n[DONE] QVIX采集完成，写入 {n} 条")


if __name__ == "__main__":
    main()
