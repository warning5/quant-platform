#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_earnings_report.py
==========================
从东方财富采集业绩快报数据，存入 MySQL stock_earnings_report 表。
供事件驱动策略使用：业绩快报 vs 一致预期 → 超预期/不及预期信号。

数据源: AKShare → stock_yjkb_em（东方财富业绩快报）
采集频率: 每日一次（财报季期间更新频繁）

用法:
    python update_earnings_report.py            # 采集最近4个季度
    python update_earnings_report.py --latest   # 只采集最新季度
"""

import argparse
import sys
import time
import math
from datetime import datetime

import akshare as ak
import pymysql

sys.path.insert(0, ".")
from db_config import MYSQL_CONFIG

# 动态计算最近n个已结束季度的末日期（格式如 '20260331'）
def _recent_quarters(n=4):
    """返回最近n个已结束的季度末日期字符串"""
    from calendar import monthrange
    quarters = []
    # 从当前月往前推，找已结束的季度
    year = datetime.now().year
    month = datetime.now().month

    for _ in range(n):
        # 当前季度末月份：3/6/9/12
        q_month = (month - 1) // 3 * 3 + 3  # 当前季度末月份
        if q_month > month:
            # 季度还没结束，取上一季度末
            if q_month == 3:
                q_month = 12
                year -= 1
            else:
                q_month -= 3
        _, day = monthrange(year, int(q_month))
        quarters.append(f"{year}{int(q_month):02d}{day:02d}")
        # 前移一个季度
        if q_month <= 3:
            year -= 1
            month = 12
        else:
            month = int(q_month) - 3

    return quarters


RECENT_QUARTERS = _recent_quarters(4)  # 每次启动自动计算


def _safe(val):
    """安全转换：NaN/None → None"""
    if val is None:
        return None
    if isinstance(val, float) and (math.isnan(val) or math.isinf(val)):
        return None
    return val


def ensure_table(conn):
    """确保 stock_earnings_report 表存在"""
    ddl = """
    CREATE TABLE IF NOT EXISTS stock_earnings_report (
        id              BIGINT AUTO_INCREMENT PRIMARY KEY,
        code            VARCHAR(10) NOT NULL COMMENT '股票代码(纯代码)',
        name            VARCHAR(40)          COMMENT '股票简称',
        report_date     VARCHAR(10) NOT NULL COMMENT '报告期(如20250331)',
        eps             DECIMAL(10,4)        COMMENT '每股收益',
        revenue         DECIMAL(20,4)        COMMENT '营业收入(元)',
        revenue_yoy     DECIMAL(10,4)        COMMENT '营收同比增长率(%)',
        net_profit      DECIMAL(20,4)        COMMENT '净利润(元)',
        net_profit_yoy  DECIMAL(10,4)        COMMENT '净利润同比增长率(%)',
        roe             DECIMAL(10,4)        COMMENT '净资产收益率(%)',
        bvps            DECIMAL(10,4)        COMMENT '每股净资产',
        industry        VARCHAR(40)          COMMENT '所属行业',
        announce_date   VARCHAR(10)          COMMENT '公告日期',
        update_time     DATETIME    NOT NULL COMMENT '数据更新时间',
        UNIQUE KEY uk_code_report (code, report_date),
        KEY idx_code (code),
        KEY idx_announce (announce_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业绩快报(东财)'
    """
    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()
    print("[OK] 表 stock_earnings_report 已就绪")


def fetch_quarter(date_str):
    """获取指定季度日期的业绩快报"""
    try:
        df = ak.stock_yjkb_em(date=date_str)
        return df
    except Exception as e:
        print(f"[WARN] 采集 {date_str} 失败: {e}")
        return None


def upsert_rows(conn, df, report_date):
    """将业绩快报数据写入MySQL"""
    now = datetime.now()
    rows = 0
    with conn.cursor() as cur:
        for _, row in df.iterrows():
            code = str(row.get("股票代码", "")).strip()
            if not code:
                continue
            name = row.get("股票简称", "")
            eps = _safe(row.get("每股收益"))
            revenue = _safe(row.get("营业收入-营业收入"))
            revenue_yoy = _safe(row.get("营业收入-同比增长"))
            net_profit = _safe(row.get("净利润-净利润"))
            net_profit_yoy = _safe(row.get("净利润-同比增长"))
            roe = _safe(row.get("净资产收益率"))
            bvps = _safe(row.get("每股净资产"))
            industry = row.get("所处行业", "")
            announce_date = str(row.get("公告日期", "")) if row.get("公告日期") else ""

            cur.execute("""
                INSERT INTO stock_earnings_report
                    (code, name, report_date, eps, revenue, revenue_yoy,
                     net_profit, net_profit_yoy, roe, bvps, industry,
                     announce_date, update_time)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    name=VALUES(name), eps=VALUES(eps), revenue=VALUES(revenue),
                    revenue_yoy=VALUES(revenue_yoy), net_profit=VALUES(net_profit),
                    net_profit_yoy=VALUES(net_profit_yoy), roe=VALUES(roe),
                    bvps=VALUES(bvps), industry=VALUES(industry),
                    announce_date=VALUES(announce_date), update_time=VALUES(update_time)
            """, (code, name, report_date, eps, revenue, revenue_yoy,
                  net_profit, net_profit_yoy, roe, bvps, industry,
                  announce_date if announce_date else "", now))
            rows += 1
    conn.commit()
    return rows


def main():
    parser = argparse.ArgumentParser(description="采集业绩快报数据")
    parser.add_argument("--latest", action="store_true", help="只采集最新季度")
    args = parser.parse_args()

    conn = pymysql.connect(**MYSQL_CONFIG)
    ensure_table(conn)

    quarters = RECENT_QUARTERS[:1] if args.latest else RECENT_QUARTERS
    total_rows = 0

    for date_str in quarters:
        print(f"[INFO] 采集业绩快报: report_date={date_str}")
        df = fetch_quarter(date_str)
        if df is not None and not df.empty:
            rows = upsert_rows(conn, df, date_str)
            total_rows += rows
            print(f"  写入 {rows} 行")
        else:
            print(f"  无数据")
        time.sleep(1)

    conn.close()
    print(f"\n[DONE] 采集完成: 共写入 {total_rows} 行")


if __name__ == "__main__":
    main()
