#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_stock_daily_baostock.py
==============================
使用 Baostock 获取沪深股票历史数据

Baostock 是免费的证券数据接口，无需 token
官网: http://baostock.com/

存储后端: 通过 db_config.DB_BACKEND 切换 ClickHouse / MySQL
估值字段: pe_ttm/pb 由 Baostock 原生提供
          注意：已移除 market_cap/circ_market_cap 字段，市值从 stock_info 获取
"""

import sys
import time
import argparse
import threading
from datetime import datetime, timedelta

import baostock as bs

# ─── 数据库操作封装 ──────────────────────────────────────────────
from db_config import get_backend_label
from db_helper import StockDailyDB


def get_baostock_code(code, market):
    """转换为 Baostock 需要的股票代码格式: SH -> sh.600000"""
    if market == "SH":
        return f"sh.{code}"
    elif market == "SZ":
        return f"sz.{code}"
    return None


def fetch_stock_history(code, name, market, start_date, end_date, max_retries=2, timeout=5):
    """
    使用 Baostock 获取单只股票的历史行情
    返回: DataFrame 或 None
    
    参数:
        timeout: 单次请求超时秒数，超时直接跳过该股票
    """
    import pandas as pd

    bs_code = get_baostock_code(code, market)
    if not bs_code:
        print(f"[INFO] {code} 不支持 Baostock (可能是北交所)")
        return None

    start_str = start_date.strftime("%Y-%m-%d")
    end_str = end_date.strftime("%Y-%m-%d")

    def _do_query():
        rs = bs.query_history_k_data_plus(
            bs_code,
            "date,code,open,high,low,close,volume,amount,adjustflag,turn,tradestatus,pctChg,isST,peTTM,pbMRQ",
            start_date=start_str,
            end_date=end_str,
            frequency="d",
            adjustflag="3",  # 3: 不复权
        )
        data_list = []
        while (rs.error_code == '0') & rs.next():
            data_list.append(rs.get_row_data())
        return data_list, rs.fields

    for attempt in range(max_retries):
        try:
            # 在独立线程中执行 Baostock 请求，防止卡死
            result_holder = [None]
            error_holder = [None]

            def _worker():
                try:
                    result_holder[0] = _do_query()
                except Exception as e:
                    error_holder[0] = e

            t = threading.Thread(target=_worker, daemon=True)
            t.start()
            t.join(timeout=timeout)

            if t.is_alive():
                print(f"[TIMEOUT] {code} 超时({timeout}s), 跳过")
                return None

            if error_holder[0]:
                raise error_holder[0]

            data_list, fields = result_holder[0]

            if len(data_list) == 0:
                return None

            df = pd.DataFrame(data_list, columns=fields)
            df['date'] = pd.to_datetime(df['date']).dt.date
            df = df[df['tradestatus'] == '1']
            return df

        except Exception as e:
            err_msg = str(e)
            # 编码/压缩错误直接跳过不重试
            if "codec can't decode" in err_msg or "decompressing data" in err_msg:
                print(f"[SKIP] {code} Baostock 返回数据编码异常，跳过: {err_msg[:80]}")
                return None
            if attempt < max_retries - 1 and ('10054' in err_msg or '10060' in err_msg or '10053' in err_msg):
                wait = (attempt + 1) * 3
                print(f"[WARN] {code} 连接断开, {wait}秒后重连 ({attempt+1}/{max_retries})...")
                time.sleep(wait)
                try:
                    bs.logout()
                    time.sleep(1)
                    lg = bs.login()
                    if lg.error_code != '0':
                        time.sleep(2)
                        lg = bs.login()
                except:
                    time.sleep(2)
                continue
            else:
                print(f"[ERROR] {code} 获取失败: {e}")
                return None
    return None


def to_float(value):
    try:
        if value is None or value == "":
            return None
        return float(value)
    except:
        return None


def to_int(value):
    try:
        if value is None or value == "":
            return None
        return int(float(value))
    except:
        return None


def build_daily_rows(db, code, name, market, df):
    """
    将 Baostock DataFrame 转换为 db_helper.upsert_daily() 需要的 row list。
    同时计算 pre_close / change_percent / change_amount。

    估值字段:
      - pe_ttm / pb: Baostock 原生字段 (peTTM / pbMRQ)，直接写入
      - 注意：已移除 market_cap/circ_market_cap 字段
    """
    if df is None or len(df) == 0:
        return []

    # 获取第一条记录的前收盘
    first_date = df.iloc[0]['date']
    prev_close = db.get_prev_close(code, first_date)

    rows = []
    for _, row in df.iterrows():
        close_price = to_float(row['close'])
        pct_chg = to_float(row['pctChg'])

        # pre_close: 优先用 DB 查询的昨收；若 DB 为空则用 pctChg 反算
        # 反算公式: pre_close = close / (1 + pctChg/100)
        # 当 CH 数据有缺口（如周末/节假日）时，get_prev_close 返回 None，
        # 此时 baostock 自带的 pctChg 可保证 pre_close 正确
        if prev_close is not None:
            pre_close_val = prev_close
        elif pct_chg is not None and close_price is not None and pct_chg != 0:
            pre_close_val = round(close_price / (1 + pct_chg / 100), 2)
        elif close_price is not None:
            # pctChg=0（停牌/非交易日/数据缺失）时：向前查找最近交易日
            # get_prev_close 的 30 天 lookback 已包含相邻交易日
            db_prev = db.get_prev_close(code, row['date'])
            pre_close_val = db_prev if db_prev is not None else None
        else:
            pre_close_val = None

        # change_percent: 直接用 baostock 原生 pctChg（最准确）
        change_percent = pct_chg

        # change_amount
        if pre_close_val is not None and close_price is not None:
            change_amount = round(close_price - pre_close_val, 2)
        elif close_price and change_percent:
            change_amount = round(close_price * change_percent / 100, 2)
        else:
            change_amount = None

        rows.append({
            "code": code,
            "name": name,
            "trade_date": row['date'],
            "open_price": to_float(row['open']),
            "close_price": close_price,
            "high_price": to_float(row['high']),
            "low_price": to_float(row['low']),
            "pre_close": pre_close_val,
            "volume": to_int(row['volume']),
            "amount": to_float(row['amount']),
            "change_percent": change_percent,
            "change_amount": change_amount,
            "turnover_rate": to_float(row['turn']),
            "pe_ttm": to_float(row['peTTM']),
            "pb": to_float(row['pbMRQ']),
        })

        prev_close = close_price  # 下一行以此为昨收

    return rows


def main():
    parser = argparse.ArgumentParser(description="使用 Baostock 获取沪深股票历史数据")
    parser.add_argument("--start-date", type=str, default="2026-03-01", help="开始日期 (默认: 2026-03-01)")
    parser.add_argument("--end-date", type=str, default=None, help="结束日期 (默认: 今天)")
    parser.add_argument("--code", type=str, help="只处理指定股票 (测试用)")
    parser.add_argument("--market", choices=["SH", "SZ"], default="SZ", help="只处理指定市场")
    parser.add_argument("--limit", type=int, default=0, help="只处理前N只股票 (测试用)")
    parser.add_argument("--batch-size", type=int, default=10, help="每批处理的股票数 (默认:10)")
    parser.add_argument("--delay", type=float, default=0.3, help="批次间延迟秒数 (默认:0.3)")
    parser.add_argument("--resume", action="store_true", help="断点续传(跳过已有数据的股票)")
    parser.add_argument("--pool", type=str, default=None,
                       choices=["SH300", "SZ50", "ZZ500", "ZZ1000", "STAR50"],
                       help="股票池筛选 (SH300/SZ50/ZZ500/ZZ1000/STAR50)")
    args = parser.parse_args()

    if args.end_date:
        end_date = datetime.strptime(args.end_date, "%Y-%m-%d").date()
    else:
        end_date = datetime.now().date()
    start_date = datetime.strptime(args.start_date, "%Y-%m-%d").date()

    print("=" * 80)
    print(f"stock_daily 数据更新脚本 (Baostock -> {get_backend_label()})")
    print("=" * 80)
    print(f"数据源: Baostock")
    print(f"存储后端: {get_backend_label()}")
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
        sys.exit(1)
    print("Baostock 登录成功")

    # 初始化数据库连接
    db = StockDailyDB()
    start_time = time.time()

    try:
        # 获取股票列表
        print("\n[1/4] 获取股票列表...")
        if args.code:
            stocks = db.get_stocks(code=args.code)
            if not stocks:
                print(f"[ERROR] 股票代码 {args.code} 不存在")
                return
        else:
            if args.pool:
                # pool 模式下：先查完整池，再在 Python 层按 market 过滤
                # 避免 SQL 层 market + pool 交叉导致市场内股票数量减少
                all_pool_stocks = db.get_stocks(pool=args.pool)
                if args.market:
                    stocks = [(c, n, m) for (c, n, m) in all_pool_stocks if m == args.market]
                else:
                    stocks = all_pool_stocks
            else:
                stocks = db.get_stocks(market=args.market, limit=args.limit)

        print(f"        待处理股票: {len(stocks)} 只")
        if args.pool:
            print(f"        股票池: {args.pool}")

        # 计算 end_date 之前的最后一个实际交易日（供断点续传比较用）
        actual_end_date = db.get_last_trading_day_before(end_date)
        print(f"        实际期末交易日: {actual_end_date} (end_date={end_date})")

        # 断点续传（无论是否指定 pool，均在此处执行）
        if args.resume and not args.code:
            print("\n[2/4] 检查已有数据...")
            all_codes = [s[0] for s in stocks]
            # 使用区间查询，只检查 [start_date, end_date] 范围内的最新数据
            code_latest_map = db.get_latest_dates_in_range_batch(all_codes, start_date, end_date)

            filtered_stocks = []
            skipped = 0
            for code, name, market in stocks:
                latest_date = code_latest_map.get(code)
                if latest_date and latest_date >= actual_end_date:
                    skipped += 1
                    continue
                filtered_stocks.append((code, name, market))
            print(f"        跳过: {skipped} 只(已有数据至实际期末 >= {actual_end_date})")
            print(f"        待更新: {len(filtered_stocks)} 只")
            stocks = filtered_stocks

        if len(stocks) == 0:
            print("\n没有需要更新的股票")
            return

        # ── [3/4] 遍历股票，插入数据 ─────────────────────────────────
        print(f"\n[4/4] 获取历史行情并写入...")
        total_success = 0
        total_failed = 0
        total_no_data = 0

        processed_codes = []
        for i, (code, name, market) in enumerate(stocks, 1):
            try:
                # 每50只重新登录 Baostock
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

                # 断点续传：根据已有数据调整实际起始日期
                actual_start = start_date
                if args.resume:
                    latest_date = code_latest_map.get(code)
                    if latest_date is not None and latest_date < actual_end_date:
                        actual_start = latest_date + timedelta(days=1)
                        print(f"  [resume] {code} 已有数据至 {latest_date}，从 {actual_start} 开始补全")
                    elif latest_date is not None and latest_date >= actual_end_date:
                        # 该股票已完整，理论上不应进入此循环，防御性 continue
                        print(f"  [resume] {code} 数据已完整({latest_date} >= {actual_end_date})，跳过")
                        continue

                df = fetch_stock_history(code, name, market, actual_start, end_date)

                if df is not None and len(df) > 0:
                    rows = build_daily_rows(db, code, name, market, df)
                    n = db.upsert_daily(rows)
                    total_success += n
                    total_failed += len(df) - n
                    processed_codes.append((code, market))
                    print(f"[{i}/{len(stocks)}] {code} {name}: 写入 {n} 条")
                elif df is None:
                    total_no_data += 1

                if i % args.batch_size == 0 and i < len(stocks):
                    print(f"[累计] 写入记录: {total_success:,} 条")
                    time.sleep(args.delay)

            except Exception as e:
                print(f"[ERROR] {code} 处理异常: {e}")
                total_failed += 1
                continue

        # 统计
        elapsed = time.time() - start_time
        print(f"\n完成")
        print("-" * 80)
        print(f"总耗时: {elapsed:.1f} 秒")
        print(f"处理股票: {len(stocks)} 只")
        print(f"成功记录: {total_success:,} 条")
        print(f"失败记录: {total_failed} 条")
        print(f"无数据: {total_no_data} 只")
        print("=" * 80)

        # ─── 自动补全 change 字段 ────────────────────────────────────
        if total_success > 0:
            print(f"\n补全 change 字段（pre_close/change_percent/change_amount）...")
            try:
                from field_completer import complete_fields
                # 只补全本次成功写入的股票，避免全表扫描
                n = complete_fields(db,
                                   code=args.code if args.code else None,
                                   stock_list=processed_codes if not args.code else None,
                                   skip_valuation=False,
                                   force_full_scan=False)
                print(f"  补全完成: {n} 条")
            except Exception as e:
                print(f"  [WARN] change 字段补全异常（不影响日线数据）: {e}")

        return 0 if total_failed == 0 else 1

    finally:
        db.close()
        bs.logout()
        print("Baostock 登出成功")


if __name__ == '__main__':
    sys.exit(main())
