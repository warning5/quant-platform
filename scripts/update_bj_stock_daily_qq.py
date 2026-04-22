#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_bj_stock_daily_qq.py
============================
使用腾讯证券接口获取北交所股票历史数据
数据源: https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get

字段: 日期, 开盘, 收盘, 最高, 最低, 成交量, {}, 换手率, 成交额, ""
"""

import sys
import time
import argparse
import json
import re
from datetime import datetime, timedelta

import requests
import pymysql

# ─── 数据库配置 ──────────────────────────────────────────────
DB_CONFIG = dict(
    host="localhost",
    port=3306,
    db="stock",
    user="root",
    password="123456",
    charset="utf8mb4",
    cursorclass=pymysql.cursors.DictCursor,
)

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Referer": "https://gu.qq.com/",
    "Accept": "*/*",
}


def get_db_connection():
    return pymysql.connect(**DB_CONFIG)


def get_bj_stocks(conn, limit=0):
    sql = "SELECT code, name, market FROM stock_info WHERE market = 'BJ' ORDER BY code"
    if limit > 0:
        sql += f" LIMIT {limit}"
    with conn.cursor() as cur:
        cur.execute(sql)
        return [(row['code'], row['name'], row['market']) for row in cur.fetchall()]


def get_latest_trade_date(conn, code):
    with conn.cursor() as cur:
        cur.execute(
            "SELECT MAX(trade_date) as max_date FROM stock_daily WHERE code = %s",
            (code,)
        )
        result = cur.fetchone()
        return result['max_date']


def get_latest_trade_date_in_range(conn, code, start_date, end_date):
    """获取指定日期范围内该股票的最新交易日期"""
    with conn.cursor() as cur:
        cur.execute(
            """SELECT MAX(trade_date) as max_date 
               FROM stock_daily 
               WHERE code = %s AND trade_date BETWEEN %s AND %s""",
            (code, start_date, end_date)
        )
        result = cur.fetchone()
        return result['max_date']


def fetch_bj_stock_history_one(code, start_date, end_date):
    """
    单次请求获取北交所股票历史行情（最多640条）
    返回 rows 列表或 None
    """
    url = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get"
    start_str = start_date.strftime("%Y-%m-%d")
    end_str = end_date.strftime("%Y-%m-%d")

    params = {
        "_var": "kline_dayqfq",
        "param": f"bj{code},day,{start_str},{end_str},640,qfq",
        "r": "0.1"
    }

    try:
        r = requests.get(url, params=params, headers=HEADERS, timeout=15)
        r.raise_for_status()

        # 去掉 JS 变量前缀
        text = re.sub(r'^kline_dayqfq=', '', r.text.strip())
        d = json.loads(text)

        if d.get('code') != 0:
            return None

        stock_data = d.get('data', {}).get(f'bj{code}', {})
        # 优先使用 qfqday(前复权)，如果不存在则使用 day(原始数据)
        rows = stock_data.get('qfqday', []) or stock_data.get('day', [])

        if not rows:
            return None

        return rows

    except Exception as e:
        return None


def split_date_ranges(start_date, end_date, months=6):
    """
    将日期范围按月数切分为多个子段，确保每段不超过640个交易日。
    每段6个月（约130个交易日），远低于640上限。
    """
    # 确保使用 date 类型
    if hasattr(start_date, 'date'):
        start_date = start_date.date()
    if hasattr(end_date, 'date'):
        end_date = end_date.date()

    ranges = []
    current = start_date
    while current < end_date:
        # 计算当前段的结束日期
        year = current.year + (current.month + months - 1) // 12
        month = (current.month + months - 1) % 12 + 1
        next_end = min(
            datetime(year, month, 1).date() - timedelta(days=1),
            end_date
        )
        ranges.append((current, next_end))
        current = next_end + timedelta(days=1)
    return ranges


def fetch_bj_stock_history(code, start_date, end_date):
    """
    自动分段获取北交所股票历史行情（突破640条限制）
    字段顺序: 日期, 开盘, 收盘, 最高, 最低, 成交量(手), {}, 换手率(%), 成交额(万元), ""
    """
    date_ranges = split_date_ranges(start_date, end_date, months=6)
    all_rows = []
    seen_dates = set()

    for seg_start, seg_end in date_ranges:
        rows = fetch_bj_stock_history_one(code, seg_start, seg_end)
        if rows:
            for row in rows:
                if row[0] not in seen_dates:
                    seen_dates.add(row[0])
                    all_rows.append(row)
            # 段间短延迟
            time.sleep(0.15)

    return all_rows if all_rows else None


def to_float(value):
    try:
        if value is None or value == '' or value == {}:
            return None
        return float(value)
    except:
        return None


def to_int(value):
    try:
        if value is None or value == '' or value == {}:
            return None
        return int(float(value))
    except:
        return None


def insert_stock_daily(conn, code, name, market, rows):
    """
    插入北交所日行情数据
    腾讯数据格式: [日期, 开盘, 收盘, 最高, 最低, 成交量(手), {}, 换手率(%), 成交额(万元), ""]
    """
    if not rows:
        return 0, 0

    INSERT_SQL = """
    INSERT INTO stock_daily
    (code, name, trade_date, open_price, close_price,
     high_price, low_price, pre_close, volume, amount, change_percent,
     change_amount, turnover_rate, pe_ttm, pb, market_cap, circ_market_cap,
     create_time, update_time)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NULL, NULL, NULL, NULL, NOW(), NOW())
    ON DUPLICATE KEY UPDATE
        name = VALUES(name),
        open_price = VALUES(open_price),
        close_price = VALUES(close_price),
        high_price = VALUES(high_price),
        low_price = VALUES(low_price),
        pre_close = VALUES(pre_close),
        volume = VALUES(volume),
        amount = VALUES(amount),
        change_percent = VALUES(change_percent),
        change_amount = VALUES(change_amount),
        turnover_rate = VALUES(turnover_rate),
        update_time = NOW()
    """

    success = failed = 0
    prev_close = None

    with conn.cursor() as cur:
        for row in rows:
            try:
                # 字段: [日期, 开盘, 收盘, 最高, 最低, 成交量, {}, 换手率, 成交额, ""]
                trade_date = row[0]
                open_p = to_float(row[1])
                close_p = to_float(row[2])
                high_p = to_float(row[3])
                low_p = to_float(row[4])
                volume = to_int(row[5])  # 成交量（手）
                # row[6] 是 {} 占位符，跳过
                turnover = to_float(row[7]) if len(row) > 7 else None
                amount = to_float(row[8]) if len(row) > 8 else None  # 万元
                if amount is not None:
                    amount = amount * 10000  # 转为元

                # 计算涨跌幅和涨跌额
                if prev_close is not None and prev_close != 0 and close_p is not None:
                    change_pct = round((close_p - prev_close) / prev_close * 100, 2)
                    change_amt = round(close_p - prev_close, 4)
                else:
                    change_pct = None
                    change_amt = None

                values = (
                    code, name, trade_date,
                    open_p, close_p, high_p, low_p,
                    prev_close, volume, amount,
                    change_pct, change_amt, turnover
                )

                cur.execute(INSERT_SQL, values)
                prev_close = close_p  # 更新为当前收盘，供下次循环使用
                success += 1

            except Exception as e:
                failed += 1
                if failed <= 3:
                    print(f"  [ERROR] {code} 插入失败 {row[0] if row else '?'}: {e}")

    conn.commit()
    return success, failed


def main():
    parser = argparse.ArgumentParser(description="使用腾讯证券接口获取北交所股票历史数据")
    parser.add_argument("--start-date", type=str, default="2026-03-20", help="开始日期 (默认: 2026-03-20)")
    parser.add_argument("--end-date", type=str, default=None, help="结束日期 (默认: 今天)")
    parser.add_argument("--code", type=str, help="只处理指定股票代码 (测试用)")
    parser.add_argument("--limit", type=int, default=0, help="只处理前N只股票 (测试用)")
    parser.add_argument("--batch-size", type=int, default=20, help="每批处理的股票数 (默认:20)")
    parser.add_argument("--delay", type=float, default=0.3, help="批次间延迟秒数 (默认:0.3)")
    parser.add_argument("--resume", action="store_true", help="断点续传(跳过已有数据的股票)")
    args = parser.parse_args()

    end_date = datetime.strptime(args.end_date, "%Y-%m-%d").date() if args.end_date else datetime.now().date()
    start_date = datetime.strptime(args.start_date, "%Y-%m-%d").date()

    print("=" * 70)
    print("北交所股票日线数据更新 (腾讯证券接口)")
    print("=" * 70)
    print(f"日期范围: {start_date} ~ {end_date}")
    print(f"批次大小: {args.batch_size}, 批次延迟: {args.delay}s, 断点续传: {args.resume}")
    print("-" * 70)

    conn = get_db_connection()
    start_time = time.time()

    try:
        # 获取股票列表
        if args.code:
            with conn.cursor() as cur:
                cur.execute("SELECT code, name, market FROM stock_info WHERE code = %s AND market = 'BJ'", (args.code,))
                row = cur.fetchone()
                stocks = [(row['code'], row['name'], row['market'])] if row else []
        else:
            stocks = get_bj_stocks(conn, limit=args.limit)

        print(f"北交所股票总数: {len(stocks)} 只")

        if not stocks:
            print("未找到北交所股票，退出。")
            return

        # 断点续传：按每只股票最早缺失日期确定实际 start_date
        stock_start_dates = {}
        if args.resume and not args.code:
            for code, name, market in stocks:
                # 检查指定日期范围内的最新数据，而非整体最新数据
                latest_in_range = get_latest_trade_date_in_range(conn, code, start_date, end_date)
                if latest_in_range and latest_in_range >= end_date:
                    # 指定范围内已有完整数据，跳过
                    continue
                # 实际起始 = max(请求start_date, 范围内最新日期+1天)
                if latest_in_range:
                    actual_start = max(start_date, latest_in_range + timedelta(days=1))
                else:
                    actual_start = start_date
                stock_start_dates[code] = actual_start

            print(f"断点续传: {len(stock_start_dates)} 只需更新, {len(stocks)-len(stock_start_dates)} 只已跳过")
            stocks = [(c, n, m) for c, n, m in stocks if c in stock_start_dates]

        if not stocks:
            print("所有股票已有数据，无需更新。")
            return

        print(f"待处理股票: {len(stocks)} 只")

        total_success = total_failed = total_no_data = 0

        for i, (code, name, market) in enumerate(stocks, 1):
            # 确定该股票的实际起始日期
            if args.resume and code in stock_start_dates:
                actual_start = stock_start_dates[code]
            else:
                actual_start = start_date

            rows = fetch_bj_stock_history(code, actual_start, end_date)

            if rows:
                ok, fail = insert_stock_daily(conn, code, name, market, rows)
                total_success += ok
                total_failed += fail
                if i % 10 == 0 or i <= 5:
                    elapsed = time.time() - start_time
                    speed = i / elapsed
                    eta = (len(stocks) - i) / speed if speed > 0 else 0
                    print(f"[{i}/{len(stocks)}] {code} {name}: +{ok}条  "
                          f"速度:{speed:.1f}只/s  预计剩余:{eta/60:.1f}min")
            else:
                total_no_data += 1
                if i <= 10 or total_no_data <= 5:
                    print(f"[{i}/{len(stocks)}] {code} {name}: 无数据")

            if i % args.batch_size == 0:
                print(f"[累计] 写入记录: {total_success:,} 条")
                time.sleep(args.delay)

        elapsed = time.time() - start_time
        print()
        print("=" * 70)
        print(f"完成! 耗时: {elapsed:.1f}秒 ({elapsed/60:.1f}分钟)")
        print(f"处理股票: {len(stocks)} 只")
        print(f"成功写入: {total_success:,} 条")
        print(f"写入失败: {total_failed} 条")
        print(f"无数据  : {total_no_data} 只")
        print("=" * 70)

    finally:
        conn.close()


if __name__ == '__main__':
    main()
