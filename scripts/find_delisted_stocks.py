#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
find_delisted_stocks.py
=========================
通过 Baostock query_all_stock 获取当前所有股票（含退市），
与 MySQL stock_info 差分，找出不在 Baostock 列表中的股票 = 已退市。
再通过 query_stock_basic 逐只确认退市日期。
最后查 ClickHouse 获取最后交易日和各表数据量。

输出: JSON 数组，不含多余 print（baostock 日志重定向到 stderr）

用法:
    python find_delisted_stocks.py
"""

import sys
import json
import argparse
import io
from datetime import datetime, timedelta

import pymysql
import baostock as bs
import clickhouse_connect

# ─── 数据库配置 ────────────────────────────────────────────────
MYSQL_CONFIG = dict(host="localhost", port=3306, user="root", password="123456", database="stock", charset="utf8mb4")
CH_CONFIG = dict(host="localhost", port=8123, username="default", password="123456", database="stock")


# ─── baostock stdout 重定向 ─────────────────────────────
# baostock login()/logout() 直接 print 到 sys.stdout，会污染 JSON 输出。
# 用 DevNull 类吃掉输出。
class _DevNull:
    def write(self, s): pass
    def flush(self): pass

_devnull = _DevNull()
_orig_stdout = sys.stdout

def _bs_login():
    sys.stdout = _devnull
    bs.login()
    sys.stdout = _orig_stdout

def _bs_logout():
    sys.stdout = _devnull
    bs.logout()
    sys.stdout = _orig_stdout


def to_baostock_code(code, market):
    """转换为 Baostock 格式: 600000 -> sh.600000"""
    if market == "SH":
        return "sh.{}".format(code)
    elif market == "SZ":
        return "sz.{}".format(code)
    elif market == "BJ":
        return "bj.{}".format(code)
    return None


def get_baostock_all_codes():
    """
    通过 Baostock query_all_stock 获取当天所有股票代码（含已退市）。
    返回 set of pure codes (如 '600000')
    """
    _bs_login()
    today = datetime.now()

    for offset in range(7):
        day = today - timedelta(days=offset)
        day_str = day.strftime("%Y-%m-%d")
        rs = bs.query_all_stock(day=day_str)
        rows = []
        while rs.next():
            rows.append(rs.get_row_data())
        if rows:
            codes = set()
            for row in rows:
                code_with_prefix = row[0]  # sh.600000
                pure_code = code_with_prefix.split(".")[1]
                codes.add(pure_code)
            print("[INFO] Baostock({}) 共 {} 只股票".format(day_str, len(codes)), file=sys.stderr)
            _bs_logout()
            return codes

    _bs_logout()
    return set()


def get_stock_info_all():
    """从 MySQL stock_info 获取所有 SH/SZ 股票的 code, name, market, list_date"""
    conn = pymysql.connect(**MYSQL_CONFIG)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT code, name, market, list_date FROM stock_info "
                "WHERE market IN ('SH', 'SZ') ORDER BY code"
            )
            return cur.fetchall()
    finally:
        conn.close()


def get_out_dates(codes_with_market):
    """
    批量查询退市日期。
    codes_with_market: list of (code, market)
    返回 {code: out_date_str}
    """
    _bs_login()
    result = {}
    for code, market in codes_with_market:
        bs_code = to_baostock_code(code, market)
        if not bs_code:
            continue
        rs = bs.query_stock_basic(code=bs_code)
        while rs.next():
            row = rs.get_row_data()
            # fields: code, code_name, ipoDate, outDate, type, status
            out_date = row[3] if len(row) > 3 else ""
            if out_date and out_date.strip():
                result[code] = out_date.strip()
            break
    _bs_logout()
    return result


def get_ch_stock_stats(codes):
    """
    从 ClickHouse 批量查询退市股的统计数据。
    返回 {code: {max_date, daily_rows, factor_rows, moneyflow_rows}}
    """
    if not codes:
        return {}

    result = {}
    client = clickhouse_connect.get_client(**CH_CONFIG)

    try:
        in_clause = ",".join("'{}'".format(c) for c in codes)

        # stock_daily 最后交易日 + 行数
        rows = client.query(
            "SELECT code, max(trade_date), count() "
            "FROM stock.stock_daily FINAL "
            "WHERE code IN ({}) ".format(in_clause) +
            "GROUP BY code"
        ).result_rows
        for row in rows:
            code = row[0]
            max_date = str(row[1])
            cnt = int(row[2])
            result[code] = {"max_date": max_date, "daily_rows": cnt}

        # factor_value
        rows = client.query(
            "SELECT symbol, count() "
            "FROM stock.factor_value FINAL "
            "WHERE symbol IN ({}) ".format(in_clause) +
            "GROUP BY symbol"
        ).result_rows
        for row in rows:
            symbol = row[0]
            cnt = int(row[1])
            if symbol in result:
                result[symbol]["factor_rows"] = cnt

        # moneyflow
        rows = client.query(
            "SELECT code, count() "
            "FROM stock.stock_sentiment_moneyflow FINAL "
            "WHERE code IN ({}) ".format(in_clause) +
            "GROUP BY code"
        ).result_rows
        for row in rows:
            code = row[0]
            cnt = int(row[1])
            if code in result:
                result[code]["moneyflow_rows"] = cnt
    finally:
        client.close()

    # 填充默认值
    for code in codes:
        if code not in result:
            result[code] = {"max_date": None, "daily_rows": 0}
        result[code].setdefault("factor_rows", 0)
        result[code].setdefault("moneyflow_rows", 0)

    return result


def main():
    parser = argparse.ArgumentParser(description="查找退市股票")
    args = parser.parse_args()

    # 1. Baostock 当天所有股票
    baostock_codes = get_baostock_all_codes()
    print("[INFO] Baostock 代码数: {}".format(len(baostock_codes)), file=sys.stderr)

    # 2. stock_info 全部 SH/SZ 股票
    all_stocks = get_stock_info_all()
    print("[INFO] stock_info SH/SZ 股票数: {}".format(len(all_stocks)), file=sys.stderr)

    # 3. 差分
    delisted = []
    for row in all_stocks:
        code = row[0]
        name = row[1]
        market = row[2]
        list_date = row[3]
        if code not in baostock_codes:
            delisted.append({
                "code": code,
                "name": name,
                "market": market,
                "list_date": str(list_date) if list_date else None,
            })

    print("[INFO] 差分结果（疑似退市）: {} 只".format(len(delisted)), file=sys.stderr)

    if not delisted:
        print(json.dumps([], ensure_ascii=False))
        return

    # 4. 用 query_stock_basic 确认退市日期
    codes_with_market = [(s["code"], s["market"]) for s in delisted]
    out_dates = get_out_dates(codes_with_market)
    for stock in delisted:
        stock["out_date"] = out_dates.get(stock["code"])

    # 5. ClickHouse 统计
    delisted_codes = [s["code"] for s in delisted]
    stats = get_ch_stock_stats(delisted_codes)

    # 6. 组装
    today_str = datetime.now().strftime("%Y-%m-%d")
    for stock in delisted:
        code = stock["code"]
        s = stats.get(code, {})
        stock["max_date"] = s.get("max_date")
        stock["daily_rows"] = s.get("daily_rows", 0)
        stock["factor_rows"] = s.get("factor_rows", 0)
        stock["moneyflow_rows"] = s.get("moneyflow_rows", 0)
        if stock["max_date"]:
            try:
                last = datetime.strptime(stock["max_date"], "%Y-%m-%d")
                now = datetime.strptime(today_str, "%Y-%m-%d")
                stock["days_inactive"] = (now - last).days
            except (ValueError, TypeError):
                stock["days_inactive"] = -1
        else:
            stock["days_inactive"] = -1

    delisted.sort(key=lambda x: x.get("out_date") or x.get("max_date") or "0000-00-00")

    print(json.dumps(delisted, ensure_ascii=False))


if __name__ == "__main__":
    main()
