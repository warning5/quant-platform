#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_stock_daily_baostock.py
==============================
使用 Baostock 获取真实股票历史数据

Baostock 是免费的证券数据接口，无需 token
官网: http://baostock.com/

优点:
- 免费，无需注册
- 无需 token
- 相对稳定

缺点:
- 数据更新可能有延迟
- 北交所股票数据不全
"""

import sys
import time
import argparse
from datetime import datetime, timedelta

import pymysql
import baostock as bs


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


def get_db_connection():
    """获取数据库连接"""
    return pymysql.connect(**DB_CONFIG)


def get_all_stocks(conn, market=None, limit=0):
    """从 stock_info 表获取股票列表"""
    sql = "SELECT code, name, market FROM stock_info"

    if market:
        sql += f" WHERE market = '{market}'"

    sql += " ORDER BY code"

    if limit > 0:
        sql += f" LIMIT {limit}"

    with conn.cursor() as cur:
        cur.execute(sql)
        return [(row['code'], row['name'], row['market']) for row in cur.fetchall()]


def get_baostock_code(code, market):
    """
    转换为 Baostock 需要的股票代码格式
    SH -> sh.600000
    SZ -> sz.000001
    """
    if market == "SH":
        return f"sh.{code}"
    elif market == "SZ":
        return f"sz.{code}"
    else:
        return None


def fetch_stock_history(code, name, market, start_date, end_date, max_retries=3):
    """
    使用 Baostock 获取单只股票的历史行情
    返回: DataFrame 或 None
    包含断线重连逻辑
    """
    bs_code = get_baostock_code(code, market)

    if not bs_code:
        print(f"[INFO] {code} 不支持 Baostock (可能是北交所)")
        return None

    for attempt in range(max_retries):
        try:
            # 转换日期格式
            start_date_str = start_date.strftime("%Y-%m-%d")
            end_date_str = end_date.strftime("%Y-%m-%d")

            # 获取数据（包含 PE 和 PB）
            rs = bs.query_history_k_data_plus(
                bs_code,
                "date,code,open,high,low,close,volume,amount,adjustflag,turn,tradestatus,pctChg,isST,peTTM,pbMRQ",
                start_date=start_date_str,
                end_date=end_date_str,
                frequency="d",
                adjustflag="3"  # 3: 不复权
            )

            data_list = []
            while (rs.error_code == '0') & rs.next():
                data_list.append(rs.get_row_data())

            if len(data_list) == 0:
                return None

            # 转换为 DataFrame
            import pandas as pd
            df = pd.DataFrame(data_list, columns=rs.fields)

            # 转换日期
            df['date'] = pd.to_datetime(df['date']).dt.date

            # 只保留交易正常的记录
            df = df[df['tradestatus'] == '1']

            return df

        except Exception as e:
            err_msg = str(e)
            if attempt < max_retries - 1 and ('10054' in err_msg or '10060' in err_msg or '10053' in err_msg):
                wait = (attempt + 1) * 5
                print(f"[WARN] {code} 连接断开, {wait}秒后重连 ({attempt+1}/{max_retries})...")
                time.sleep(wait)
                # 尝试重新登录
                try:
                    bs.logout()
                    time.sleep(1)
                    lg = bs.login()
                    if lg.error_code != '0':
                        time.sleep(3)
                        lg = bs.login()
                except:
                    time.sleep(3)
                continue
            else:
                print(f"[ERROR] {code} 获取失败: {e}")
                return None
    return None


def to_float(value):
    """转换为浮点数"""
    try:
        if value is None or value == "":
            return None
        return float(value)
    except:
        return None


def to_int(value):
    """转换为整数"""
    try:
        if value is None or value == "":
            return None
        return int(float(value))
    except:
        return None


def get_prev_close(conn, code, market, trade_date):
    """获取某股票在指定日期之前的最近收盘价（昨收）"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT close_price FROM stock_daily WHERE code = %s AND trade_date < %s ORDER BY trade_date DESC LIMIT 1",
            (code, trade_date)
        )
        result = cur.fetchone()
        return to_float(result['close_price']) if result else None


def insert_stock_daily(conn, code, name, market, df, record_date):
    """
    插入单只股票的日行情数据
    """
    if df is None or len(df) == 0:
        return 0, 0

    symbol = f"{code}.{market}"
    success = failed = 0

    # 获取第一条记录的前收盘（从数据库查）
    first_date = df.iloc[0]['date']
    prev_close = get_prev_close(conn, code, market, first_date)

    INSERT_SQL = """
    INSERT INTO stock_daily
    (code, name, trade_date, open_price, close_price,
     high_price, low_price, pre_close, volume, amount, change_percent,
     change_amount, turnover_rate, pe_ttm, pb, market_cap, circ_market_cap,
     create_time, update_time)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW())
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
        pe_ttm = VALUES(pe_ttm),
        pb = VALUES(pb),
        update_time = NOW()
    """

    with conn.cursor() as cur:
        for _, row in df.iterrows():
            try:
                close_price = to_float(row['close'])
                change_percent = to_float(row['pctChg'])

                # 计算涨跌额
                if close_price and change_percent:
                    change_amount = round(close_price * change_percent / 100, 2)
                else:
                    change_amount = None

                # pre_close：首条从数据库查，后续用前一行收盘价
                if prev_close is None:
                    # 尝试用 open - change_amount 反推
                    open_p = to_float(row['open'])
                    if open_p and change_amount:
                        pre_close_val = round(open_p - change_amount, 2)
                    else:
                        pre_close_val = None
                else:
                    pre_close_val = prev_close

                values = (
                    code,
                    name,
                    row['date'],
                    to_float(row['open']),
                    close_price,
                    to_float(row['high']),
                    to_float(row['low']),
                    pre_close_val,
                    to_int(row['volume']),
                    to_float(row['amount']),
                    change_percent,
                    change_amount,
                    to_float(row['turn']),
                    to_float(row['peTTM']),
                    to_float(row['pbMRQ']),
                    None,  # market_cap (Baostock日线接口无此字段，留NULL)
                    None,  # circ_market_cap
                )

                cur.execute(INSERT_SQL, values)
                prev_close = close_price  # 下一行以此为昨收
                success += 1

            except Exception as e:
                failed += 1
                if failed <= 5:
                    print(f"[ERROR] {code} 插入失败 {row['date']}: {e}")

    conn.commit()
    return success, failed


def get_latest_trade_date(conn, code):
    """获取某股票的最新交易日期"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT MAX(trade_date) as max_date FROM stock_daily WHERE code = %s",
            (code,)
        )
        result = cur.fetchone()
        return result['max_date']


def main():
    parser = argparse.ArgumentParser(description="使用 Baostock 获取真实股票历史数据")
    parser.add_argument(
        "--start-date",
        type=str,
        default="2026-03-01",
        help="开始日期 (默认: 2026-03-01)"
    )
    parser.add_argument(
        "--end-date",
        type=str,
        default=None,
        help="结束日期 (默认: 今天)"
    )
    parser.add_argument("--code", type=str, help="只处理指定股票 (测试用)")
    parser.add_argument(
        "--market",
        choices=["SH", "SZ"],
        default="SZ",  # Baostock 主要支持沪深
        help="只处理指定市场"
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="只处理前N只股票 (测试用)"
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=10,
        help="每批处理的股票数 (默认:10)"
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.3,
        help="批次间延迟秒数 (默认:0.3)"
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="断点续传(跳过已有数据的股票)"
    )
    args = parser.parse_args()

    # 结束日期
    if args.end_date:
        end_date = datetime.strptime(args.end_date, "%Y-%m-%d").date()
    else:
        end_date = datetime.now().date()

    start_date = datetime.strptime(args.start_date, "%Y-%m-%d").date()

    print("=" * 80)
    print("stock_daily 真实数据更新脚本 (Baostock)")
    print("=" * 80)
    print(f"数据源: Baostock")
    print(f"日期范围: {start_date} ~ {end_date}")
    print(f"市场: {args.market}")
    print(f"批次大小: {args.batch_size}")
    print(f"批次延迟: {args.delay}秒")
    print(f"断点续传: {'是' if args.resume else '否'}")
    print("-" * 80)

    # 登录 Baostock
    print("\n正在登录 Baostock...")
    lg = bs.login()
    if lg.error_code != '0':
        print(f"[ERROR] Baostock 登录失败: {lg.error_msg}")
        return
    print("Baostock 登录成功")

    conn = get_db_connection()
    start_time = time.time()

    try:
        # 获取股票列表
        print("\n[1/4] 获取股票列表...")
        if args.code:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT code, name, market FROM stock_info WHERE code = %s",
                    (args.code,)
                )
                row = cur.fetchone()
                if row:
                    stocks = [(row['code'], row['name'], row['market'])]
                else:
                    print(f"[ERROR] 股票代码 {args.code} 不存在")
                    return
        else:
            stocks = get_all_stocks(conn, market=args.market, limit=args.limit)

        print(f"        待处理股票: {len(stocks)} 只")

        # 过滤已有数据的股票
        if args.resume and not args.code:
            print("\n[2/4] 检查已有数据...")
            filtered_stocks = []
            skipped = 0
            for code, name, market in stocks:
                latest_date = get_latest_trade_date(conn, code)
                # 修正：检查是否已更新到结束日期，而非开始日期
                if latest_date and latest_date >= end_date:
                    skipped += 1
                    continue
                filtered_stocks.append((code, name, market))

            print(f"        跳过: {skipped} 只(已更新到 {end_date})")
            print(f"        待更新: {len(filtered_stocks)} 只")
            stocks = filtered_stocks

        if len(stocks) == 0:
            print("\n没有需要更新的股票")
            return

        # 获取数据
        print(f"\n[3/4] 获取历史行情...")

        total_success = 0
        total_failed = 0
        total_no_data = 0

        for i, (code, name, market) in enumerate(stocks, 1):
            try:
                # 每50只主动重新登录Baostock，防止长连接断开
                if i > 1 and (i - 1) % 50 == 0:
                    try:
                        bs.logout()
                        time.sleep(1)
                        lg = bs.login()
                        if lg.error_code != '0':
                            print(f"[WARN] Baostock 重登失败: {lg.error_msg}, 3秒后重试...")
                            time.sleep(3)
                            lg = bs.login()
                            if lg.error_code != '0':
                                print(f"[ERROR] Baostock 重登彻底失败，退出")
                                break
                    except Exception as e:
                        print(f"[WARN] 重登异常: {e}")

                df = fetch_stock_history(code, name, market, start_date, end_date)

                record_date = datetime.now().date()
                if df is not None and len(df) > 0:
                    success, failed = insert_stock_daily(conn, code, name, market, df, record_date)
                    total_success += success
                    total_failed += failed
                    print(f"[{i}/{len(stocks)}] {code} {name}: 成功 {success}, 失败 {failed}")
                else:
                    total_no_data += 1
                    print(f"[{i}/{len(stocks)}] {code} {name}: 无数据")

                if i % args.batch_size == 0 and i < len(stocks):
                    print(f"[累计] 写入记录: {total_success:,} 条")
                    time.sleep(args.delay)

            except Exception as e:
                print(f"[ERROR] {code} 处理异常: {e}")
                total_failed += 1
                continue

        # 统计
        elapsed = time.time() - start_time
        print(f"\n[4/4] 完成")
        print("-" * 80)
        print(f"总耗时: {elapsed:.1f} 秒")
        print(f"处理股票: {len(stocks)} 只")
        print(f"成功记录: {total_success:,} 条")
        print(f"失败记录: {total_failed} 条")
        print(f"无数据: {total_no_data} 只")
        print("=" * 80)

    finally:
        conn.close()
        bs.logout()
        print("Baostock 登出成功")


if __name__ == '__main__':
    main()
