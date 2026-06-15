#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_consensus_estimate.py
============================
从同花顺采集一致预期数据（预测年报净利润），存入 MySQL stock_consensus_estimate 表。
供事件驱动策略使用：实际业绩 vs 一致预期 → 超预期/不及预期信号。

数据源: AKShare → stock_profit_forecast_ths（同花顺一致预期）
采集频率: 每周一次（周频足够，一致预期变化较慢）

用法:
    python update_consensus_estimate.py           # 全量采集所有股票
    python update_consensus_estimate.py --top 300  # 只采集沪深300
    python update_consensus_estimate.py --code 600519  # 单只股票
"""

import argparse
import sys
import time
from datetime import datetime

import akshare as ak
import pymysql

# ─── 配置 ────────────────────────────────────────────────────
sys.path.insert(0, ".")
from db_config import MYSQL_CONFIG

BATCH_DELAY = 0.5  # 每只股票间隔（秒），避免限流
MAX_RETRIES = 2


def ensure_table(conn):
    """确保 stock_consensus_estimate 表存在"""
    ddl = """
    CREATE TABLE IF NOT EXISTS stock_consensus_estimate (
        id              BIGINT AUTO_INCREMENT PRIMARY KEY,
        code            VARCHAR(10) NOT NULL COMMENT '股票代码(纯代码)',
        forecast_year   INT         NOT NULL COMMENT '预测年度',
        agency_count    INT                  COMMENT '预测机构数',
        estimate_min    DECIMAL(20,4)        COMMENT '预测最小值(亿元)',
        estimate_avg    DECIMAL(20,4)        COMMENT '预测均值(亿元)',
        estimate_max    DECIMAL(20,4)        COMMENT '预测最大值(亿元)',
        industry_avg    DECIMAL(20,4)        COMMENT '行业平均(亿元)',
        update_time     DATETIME    NOT NULL COMMENT '数据更新时间',
        UNIQUE KEY uk_code_year (code, forecast_year),
        KEY idx_code (code),
        KEY idx_update_time (update_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一致预期(同花顺)'
    """
    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()
    print("[OK] 表 stock_consensus_estimate 已就绪")


def fetch_one(code):
    """获取单只股票的一致预期数据"""
    for attempt in range(MAX_RETRIES):
        try:
            df = ak.stock_profit_forecast_ths(symbol=code, indicator="预测年报净利润")
            if df is None or df.empty:
                return None
            return df
        except Exception as e:
            if attempt < MAX_RETRIES - 1:
                time.sleep(1)
            else:
                return None
    return None


def upsert_rows(conn, code, df):
    """将一致预期数据写入MySQL"""
    now = datetime.now()
    rows = 0
    with conn.cursor() as cur:
        for _, row in df.iterrows():
            year = int(row.get("年度", 0))
            if year < 2024:
                continue
            agency_count = row.get("预测机构数", 0)
            est_min = row.get("最小值")
            est_avg = row.get("均值")
            est_max = row.get("最大值")
            ind_avg = row.get("行业平均数")

            cur.execute("""
                INSERT INTO stock_consensus_estimate
                    (code, forecast_year, agency_count, estimate_min, estimate_avg, estimate_max, industry_avg, update_time)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    agency_count=VALUES(agency_count),
                    estimate_min=VALUES(estimate_min),
                    estimate_avg=VALUES(estimate_avg),
                    estimate_max=VALUES(estimate_max),
                    industry_avg=VALUES(industry_avg),
                    update_time=VALUES(update_time)
            """, (code, year, int(agency_count) if agency_count else 0,
                  est_min, est_avg, est_max, ind_avg, now))
            rows += 1
    conn.commit()
    return rows


def get_stock_list(conn, top_n=None, code=None):
    """获取要采集的股票列表"""
    with conn.cursor() as cur:
        if code:
            cur.execute("SELECT code, name FROM stock_info WHERE code = %s", (code,))
        elif top_n:
            # 按市值排序取 top N
            cur.execute("""
                SELECT code, name FROM stock_info
                WHERE market IN ('SH','SZ') AND total_market_cap > 0
                ORDER BY total_market_cap DESC
                LIMIT %s
            """, (top_n,))
        else:
            cur.execute("""
                SELECT code, name FROM stock_info
                WHERE market IN ('SH','SZ')
                ORDER BY code
            """)
        return cur.fetchall()


def main():
    parser = argparse.ArgumentParser(description="采集一致预期数据")
    parser.add_argument("--top", type=int, help="只采集前N只(按市值)")
    parser.add_argument("--code", type=str, help="只采集指定代码")
    args = parser.parse_args()

    conn = pymysql.connect(**MYSQL_CONFIG)
    ensure_table(conn)

    stock_list = get_stock_list(conn, top_n=args.top, code=args.code)
    total = len(stock_list)
    print(f"[INFO] 待采集股票数: {total}")

    ok_count = 0
    empty_count = 0
    error_count = 0

    for idx, (code, name) in enumerate(stock_list, 1):
        try:
            df = fetch_one(code)
            if df is not None and not df.empty:
                rows = upsert_rows(conn, code, df)
                ok_count += 1
                if idx % 50 == 0 or idx == total:
                    print(f"[{idx}/{total}] {code} {name}: {rows}行数据")
            else:
                empty_count += 1
        except Exception as e:
            error_count += 1
            if error_count <= 5:
                print(f"[ERROR] {code} {name}: {e}")

        if idx < total:
            time.sleep(BATCH_DELAY)

    conn.close()
    print(f"\n[DONE] 采集完成: 成功={ok_count}, 无数据={empty_count}, 失败={error_count}")


if __name__ == "__main__":
    main()
