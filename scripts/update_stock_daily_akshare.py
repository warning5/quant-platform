#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_stock_daily_akshare.py
=============================
使用 akshare 获取沪深股票历史数据（Baostock 备用方案）
数据源: 东方财富

优化点:
- 单线程顺序执行，避免并发导致的卡死问题
- 添加请求超时机制
- 减少日志输出，提升性能
"""

import sys
import time
import argparse
from datetime import datetime, timedelta

import pymysql
import pandas as pd
import akshare as ak

# ─── 数据库配置 ──────────────────────────────
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


def get_latest_trade_date(conn, code):
    """获取某股票的最新交易日期"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT MAX(trade_date) as max_date FROM stock_daily WHERE code = %s",
            (code,)
        )
        result = cur.fetchone()
        return result['max_date'] if result else None


def get_akshare_code(code, market):
    """
    转换为 akshare 需要的股票代码格式
    SH -> sh600000
    SZ -> sz000001
    """
    if market == "SH":
        return f"sh{code}"
    elif market == "SZ":
        return f"sz{code}"
    else:
        return None


def fetch_stock_history(code, name, market, start_date, end_date, max_retries=3, timeout=10):
    """
    使用 akshare 获取单只股票的历史行情
    返回: DataFrame 或 None
    timeout: 单只股票请求超时时间（秒）
    """
    import threading
    
    ak_code = get_akshare_code(code, market)
    
    if not ak_code:
        return None
    
    result = [None]
    
    def fetch_with_timeout():
        try:
            # 使用 akshare 获取历史数据 - 使用新浪接口更稳定
            df = ak.stock_zh_a_daily(
                symbol=ak_code,
                start_date=start_date.strftime("%Y%m%d"),
                end_date=end_date.strftime("%Y%m%d"),
                adjust="qfq"  # 前复权
            )
            result[0] = df
        except Exception as e:
            result[0] = e
    
    for attempt in range(max_retries):
        try:
            # 使用线程实现超时控制
            thread = threading.Thread(target=fetch_with_timeout)
            thread.daemon = True
            thread.start()
            thread.join(timeout=timeout)
            
            if thread.is_alive():
                # 超时
                if attempt < max_retries - 1:
                    time.sleep(0.5)
                    continue
                else:
                    return None
            
            df = result[0]
            if isinstance(df, Exception):
                raise df
            
            if df is None or df.empty:
                return None
            
            # 重命名列以匹配数据库结构
            df = df.rename(columns={
                'date': 'trade_date',
                'open': 'open',
                'high': 'high',
                'low': 'low',
                'close': 'close',
                'volume': 'volume',
                'amount': 'amount',
                'amplitude': 'amplitude',
                'pct_chg': 'pct_chg',
                'change': 'change',
                'turnover': 'turnover',
            })
            
            # 添加 code 列
            df['code'] = code
            
            # 转换日期格式
            df['trade_date'] = pd.to_datetime(df['trade_date']).dt.strftime('%Y-%m-%d')
            
            return df
            
        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(0.5 * (attempt + 1))
            else:
                return None


def save_to_database(conn, df, batch_size=1000):
    """将数据保存到数据库"""
    if df is None or df.empty:
        return 0
    
    # 确保数值列是 float 类型
    numeric_cols = ['open', 'high', 'low', 'close', 'volume', 'amount', 
                    'amplitude', 'pct_chg', 'change', 'turnover']
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors='coerce')
    
    # 替换 NaN 为 None
    df = df.where(pd.notnull(df), None)
    
    # 使用与数据库匹配的字段名
    insert_sql = """
        INSERT INTO stock_daily 
        (code, trade_date, open_price, high_price, low_price, close_price, volume, amount, 
         change_percent, change_amount, turnover_rate, create_time, update_time)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
        open_price = VALUES(open_price),
        high_price = VALUES(high_price),
        low_price = VALUES(low_price),
        close_price = VALUES(close_price),
        volume = VALUES(volume),
        amount = VALUES(amount),
        change_percent = VALUES(change_percent),
        change_amount = VALUES(change_amount),
        turnover_rate = VALUES(turnover_rate),
        update_time = NOW()
    """
    
    count = 0
    with conn.cursor() as cur:
        for i in range(0, len(df), batch_size):
            batch = df.iloc[i:i+batch_size]
            values = []
            for _, row in batch.iterrows():
                values.append((
                    row.get('code'),
                    row.get('trade_date'),
                    row.get('open'),
                    row.get('high'),
                    row.get('low'),
                    row.get('close'),
                    row.get('volume'),
                    row.get('amount'),
                    row.get('pct_chg'),
                    row.get('change'),
                    row.get('turnover'),
                ))
            
            cur.executemany(insert_sql, values)
            count += len(values)
        conn.commit()
    
    return count


def main():
    parser = argparse.ArgumentParser(description='使用 akshare 更新沪深股票日线数据')
    parser.add_argument('--market', type=str, default='ALL', help='市场: SH / SZ / ALL')
    parser.add_argument('--start-date', type=str, required=True, help='开始日期 YYYY-MM-DD')
    parser.add_argument('--end-date', type=str, default=None, help='结束日期 YYYY-MM-DD')
    parser.add_argument('--limit', type=int, default=0, help='限制处理股票数量（测试用）')
    parser.add_argument('--delay', type=float, default=0.05, help='请求间隔秒数（默认0.05秒）')
    parser.add_argument('--batch-size', type=int, default=1000, help='数据库批量插入大小')
    parser.add_argument('--workers', type=int, default=1, help='并发线程数（默认1，建议保持单线程避免卡死）')
    parser.add_argument('--quiet', action='store_true', help='安静模式，减少输出')
    parser.add_argument('--resume', action='store_true', help='断点续传（跳过已有数据的股票）')
    
    args = parser.parse_args()
    
    # 解析日期
    start_date = datetime.strptime(args.start_date, '%Y-%m-%d')
    end_date = datetime.strptime(args.end_date, '%Y-%m-%d') if args.end_date else datetime.now()
    
    print(f"=" * 60)
    print(f"akshare 沪深日线数据更新")
    print(f"日期范围: {start_date.date()} ~ {end_date.date()}")
    print(f"市场: {args.market}")
    print(f"请求间隔: {args.delay}秒")
    print(f"=" * 60)
    
    conn = get_db_connection()
    
    try:
        # 获取股票列表
        if args.market == 'ALL':
            markets = ['SH', 'SZ']
        else:
            markets = [args.market]
        
        all_stocks = []
        for market in markets:
            stocks = get_all_stocks(conn, market, args.limit)
            all_stocks.extend([(code, name, market) for code, name, _ in stocks])
        
        # 断点续传：过滤已有数据的股票
        if args.resume:
            print(f"\n[断点续传] 检查已有数据...")
            filtered_stocks = []
            skipped = 0
            for code, name, market in all_stocks:
                latest_date = get_latest_trade_date(conn, code)
                # 检查是否已更新到结束日期
                if latest_date and latest_date >= end_date.date():
                    skipped += 1
                    continue
                filtered_stocks.append((code, name, market))
            
            print(f"        跳过: {skipped} 只(已更新到 {end_date.date()})")
            print(f"        待更新: {len(filtered_stocks)} 只")
            all_stocks = filtered_stocks
        
        if len(all_stocks) == 0:
            print("\n没有需要更新的股票")
            return 0
        
        print(f"\n共 {len(all_stocks)} 只股票待处理")
        print(f"{'='*60}\n")
        
        total_records = 0
        failed_stocks = []
        
        # 单线程顺序处理，避免并发导致的卡死问题
        for i, (code, name, market) in enumerate(all_stocks, 1):
            try:
                df = fetch_stock_history(code, name, market, start_date, end_date)
                
                if df is not None and not df.empty:
                    count = save_to_database(conn, df, args.batch_size)
                    total_records += count
                    if not args.quiet:
                        print(f"[{i}/{len(all_stocks)}] {code} {name} 保存 {count} 条")
                else:
                    failed_stocks.append((code, name))
                    if not args.quiet:
                        print(f"[{i}/{len(all_stocks)}] {code} {name} 无数据")
                
                # 每100只输出进度
                if i % 100 == 0:
                    print(f"[进度] 已处理 {i}/{len(all_stocks)} 只, 累计保存 {total_records} 条")
                    
            except Exception as e:
                failed_stocks.append((code, name))
                if not args.quiet:
                    print(f"[{i}/{len(all_stocks)}] {code} {name} 错误: {e}")
            
            # 请求间隔，避免频率限制
            time.sleep(args.delay)
        
        print(f"\n{'='*60}")
        print(f"更新完成: 处理 {len(all_stocks)} 只股票, 新增/更新 {total_records} 条记录")
        if failed_stocks:
            print(f"失败 {len(failed_stocks)} 只")
        print(f"{'='*60}")
        
        # 只要有数据保存成功就算成功
        return 0 if total_records > 0 else 1
        
    finally:
        conn.close()


if __name__ == '__main__':
    sys.exit(main())
