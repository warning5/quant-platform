#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
find_delisted_stocks.py
=======================
检测系统中已退市股票：stock_info 中存在但 ClickHouse 中最近 N 天无交易数据的股票。

输出格式（JSON 数组）:
[
  {
    "code": "600001",
    "name": "邯郸钢铁",
    "market": "SH",
    "out_date": "2009-12-29",
    "max_date": "2009-12-25",
    "days_inactive": 6000,
    "daily_rows": 2500,
    "factor_rows": 0,
    "moneyflow_rows": 0
  },
  ...
]
"""
import warnings
import json
import sys
import os
import pymysql
from datetime import datetime

warnings.filterwarnings("ignore")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

from db_config import MYSQL_CONFIG, CLICKHOUSE_CONFIG


def get_stock_info():
    """从 MySQL stock_info 获取所有股票"""
    stocks = {}
    try:
        conn = pymysql.connect(**MYSQL_CONFIG)
        with conn.cursor() as cur:
            cur.execute("SELECT code, name, market FROM stock_info")
            for row in cur.fetchall():
                code, name, market = row
                stocks[str(code).strip()] = {
                    "name": str(name).strip() if name else "",
                    "market": str(market).strip().upper() if market else ""
                }
        conn.close()
    except Exception as e:
        print(f"查询 stock_info 失败: {e}", file=sys.stderr)
        sys.exit(1)
    return stocks


def get_inactive_stocks(threshold_days=60):
    """从 ClickHouse 查询最近 threshold_days 天无交易数据的股票"""
    import clickhouse_connect

    client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

    # 获取每只股票的最新交易日期和数据量
    sql = f"""
    SELECT
        code,
        count() as daily_rows,
        max(trade_date) as max_date
    FROM stock_daily
    GROUP BY code
    HAVING max(trade_date) < today() - {threshold_days}
    ORDER BY max_date ASC
    """

    result = client.query(sql)
    inactive = {}
    for row in result.result_rows:
        code, daily_rows, max_date = row
        inactive[str(code).strip()] = {
            "daily_rows": int(daily_rows),
            "max_date": str(max_date) if max_date else None
        }

    # 因子数据量
    if inactive:
        codes = "','".join(inactive.keys())
        sql2 = f"""
        SELECT symbol, count() as factor_rows
        FROM factor_value
        WHERE symbol IN ('{codes}')
        GROUP BY symbol
        """
        result2 = client.query(sql2)
        for row in result2.result_rows:
            symbol, factor_rows = row
            code = str(symbol).strip()
            if code in inactive:
                inactive[code]["factor_rows"] = int(factor_rows)

        # 资金流数据量
        sql3 = f"""
        SELECT code, count() as moneyflow_rows
        FROM stock_sentiment_moneyflow
        WHERE code IN ('{codes}')
        GROUP BY code
        """
        result3 = client.query(sql3)
        for row in result3.result_rows:
            code, moneyflow_rows = row
            code = str(code).strip()
            if code in inactive:
                inactive[code]["moneyflow_rows"] = int(moneyflow_rows)

    client.close()
    return inactive


def main():
    threshold_days = int(sys.argv[1]) if len(sys.argv) > 1 else 60

    print(f"正在查询 stock_info...", file=sys.stderr)
    stock_info = get_stock_info()
    print(f"stock_info 共 {len(stock_info)} 只", file=sys.stderr)

    print(f"正在查询 ClickHouse（停牌>{threshold_days}天）...", file=sys.stderr)
    inactive = get_inactive_stocks(threshold_days)
    print(f"疑似退市/停牌: {len(inactive)} 只", file=sys.stderr)

    today = datetime.now().date()
    result = []
    for code, info in inactive.items():
        if code not in stock_info:
            continue  # stock_info 中不存在的跳过

        si = stock_info[code]
        max_date = info.get("max_date")
        days_inactive = 0
        if max_date:
            try:
                d = datetime.strptime(str(max_date), "%Y-%m-%d").date()
                days_inactive = (today - d).days
            except:
                pass

        result.append({
            "code": code,
            "name": si["name"],
            "market": si["market"],
            "out_date": "",
            "max_date": max_date or "",
            "days_inactive": days_inactive,
            "daily_rows": info.get("daily_rows", 0),
            "factor_rows": info.get("factor_rows", 0),
            "moneyflow_rows": info.get("moneyflow_rows", 0),
        })

    # 按停牌天数降序
    result.sort(key=lambda x: x["days_inactive"], reverse=True)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"输出 {len(result)} 只", file=sys.stderr)


if __name__ == "__main__":
    main()
