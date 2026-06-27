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
import os
from datetime import datetime, timedelta
from contextlib import contextmanager

import baostock as bs


@contextmanager
def suppress_stdout():
    """临时屏蔽 stdout（用于屏蔽 baostock 的 login/logout 输出）"""
    original_stdout = sys.stdout
    sys.stdout = open(os.devnull, 'w')
    try:
        yield
    finally:
        sys.stdout.close()
        sys.stdout = original_stdout

# ─── 数据库操作封装 ──────────────────────────────────────────────
from db_config import get_backend_label
from db_helper import StockDailyDB, to_float, to_int


def get_baostock_code(code, market):
    """转换为 Baostock 需要的股票代码格式: SH -> sh.600000"""
    if market == "SH":
        return f"sh.{code}"
    elif market == "SZ":
        return f"sz.{code}"
    return None


def fetch_stock_history(code, name, market, start_date, end_date, max_retries=2, timeout=30):
    """
    使用 Baostock 获取单只股票的历史行情
    返回: DataFrame 或 None
    
    参数:
        timeout: 单次请求超时秒数（默认30s，增量更新足够；历史回填可通过命令行 --timeout 调大）
                 超时时抛出 TimeoutError，上层捕获后 sys.exit(1)，外层 run_baostock 会重试
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
            "date,code,open,high,low,close,preclose,volume,amount,adjustflag,turn,tradestatus,pctChg,isST,peTTM,pbMRQ",
            start_date=start_str,
            end_date=end_str,
            frequency="d",
            adjustflag="2",  # 2: 前复权（1=后复权,3=不复权；前复权保证close与实时行情一致）
        )
        data_list = []
        while (rs.error_code == '0') and rs.next():
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
                print(f"[TIMEOUT] {code} 超时({timeout}s), 终止脚本")
                raise TimeoutError(f"{code} 请求超时({timeout}s)")

            if error_holder[0]:
                raise error_holder[0]

            data_list, fields = result_holder[0]

            if len(data_list) == 0:
                return None

            df = pd.DataFrame(data_list, columns=fields)
            df['date'] = pd.to_datetime(df['date']).dt.date
            df = df[df['tradestatus'] == '1']
            return df

        except TimeoutError:
            raise  # 向上传播，由 main() 的 except TimeoutError 捕获后 sys.exit(1)
        except Exception as e:
            err_msg = str(e)
            # 编码/压缩错误直接跳过不重试
            if "codec can't decode" in err_msg or "decompressing data" in err_msg:
                print(f"[SKIP] {code} Baostock 返回数据编码异常，跳过: {err_msg[:80]}")
                return None
            # NoneType 错误（Baostock 返回 None，通常是日期无效或服务端问题），不重试
            if "NoneType" in err_msg or "has no attribute" in err_msg:
                print(f"[WARN] {code} Baostock 查询失败({attempt+1}次): {err_msg[:100]}")
                return None
            if attempt < max_retries - 1 and ('10054' in err_msg or '10060' in err_msg or '10053' in err_msg):
                wait = (attempt + 1) * 3
                print(f"[WARN] {code} 连接断开, {wait}秒后重连 ({attempt+1}/{max_retries})...")
                time.sleep(wait)
                try:
                    with suppress_stdout():
                        bs.logout()
                    time.sleep(1)
                    with suppress_stdout():
                        lg = bs.login()
                    if lg.error_code != '0':
                        time.sleep(2)
                        with suppress_stdout():
                            lg = bs.login()
                except:
                    time.sleep(2)
                continue
            else:
                print(f"[ERROR] {code} 获取失败: {e}")
                return None
    return None


# to_float / to_int 已从 db_helper 导入，不再本地定义

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
        bs_preclose = to_float(row['preclose'])  # Baostock 前复权昨收价

        # pre_close 优先级：
        #   1) Baostock 原生 preclose（真实昨收价，不受复权影响）
        #      经验证：前复权模式下 Baostock 的 preclose 字段 = 真实昨收价，
        #      而 close 是前复权价格。pctChg = (close_复权 - preclose_真实) / preclose_真实 * 100
        #   2) pctChg 反算（preclose 缺失时的兜底）
        #   3) DB 查询的前一交易日 close_price
        #   4) 递推 prev_close（最后兜底）
        if bs_preclose is not None and bs_preclose > 0:
            pre_close_val = round(bs_preclose, 2)
        elif pct_chg is not None and close_price is not None and pct_chg != 0:
            pre_close_val = round(close_price / (1 + pct_chg / 100), 2)
        elif prev_close is not None:
            pre_close_val = prev_close
        elif close_price is not None:
            db_prev = db.get_prev_close(code, row['date'])
            pre_close_val = db_prev if db_prev is not None else None
        else:
            pre_close_val = None

        # change_percent: 直接用 baostock 原生 pctChg（最准确）
        change_percent = pct_chg

        # change_amount
        if pre_close_val is not None and close_price is not None:
            change_amount = round(close_price - pre_close_val, 2)
        elif close_price is not None and change_percent is not None:
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
            "data_source": "baostock",
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
    parser.add_argument("--force", action="store_true", help="强制写入(跳过去重预过滤，直接INSERT覆盖)")
    parser.add_argument("--pool", type=str, default=None,
                       choices=["SH300", "SZ50", "ZZ500", "ZZ1000", "STAR50"],
                       help="股票池筛选 (SH300/SZ50/ZZ500/ZZ1000/STAR50)")
    args = parser.parse_args()

    if args.end_date:
        end_date = datetime.strptime(args.end_date, "%Y-%m-%d").date()
    else:
        end_date = datetime.now().date()
    start_date = datetime.strptime(args.start_date, "%Y-%m-%d").date()

    print("=" * 70)
    print(f"stock_daily 数据更新脚本 (Baostock -> {get_backend_label()})")
    print("=" * 70)
    print(f"数据源: Baostock")
    print(f"存储后端: {get_backend_label()}")
    print(f"日期范围: {start_date} ~ {end_date}")
    print(f"市场: {args.market}")
    print(f"批次大小: {args.batch_size}")
    print(f"批次延迟: {args.delay}秒")
    print(f"断点续传: {'是' if args.resume else '否'}")
    print(f"强制写入: {'是' if args.force else '否'}")
    print("-" * 70)

    # 登录 Baostock
    print("\n正在登录 Baostock...")
    with suppress_stdout():
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

        # 快速预检：无论是否 --resume，都先查已有数据，避免空跑
        # --resume 跳过已有 → 减少 Baostock 请求；不 resume 也做预检，全量已有直接进补全
        if not args.code:
            print("\n[2/4] 检查已有数据...")
            all_codes = [s[0] for s in stocks]
            code_latest_map = db.get_latest_dates_in_range_batch(all_codes, start_date, end_date)

            pending = []
            skipped = 0
            for code, name, market in stocks:
                latest_date = code_latest_map.get(code)
                if latest_date and latest_date >= actual_end_date:
                    skipped += 1
                    continue
                pending.append((code, name, market))
            print(f"        跳过: {skipped} 只(已有数据至实际期末 >= {actual_end_date})")
            print(f"        待更新: {len(pending)} 只")

            if args.force:
                # --force 强制全量重刷，不跳过任何股票
                pass
            elif len(pending) == 0:
                # 全量已有数据，跳过采集循环，直接进补全
                print("\n全量数据已就绪，跳过日线采集")
                stocks = []
            else:
                # 只处理有缺口的股票（无论是否 --resume）
                # Baostock 按股票请求，跳过已有数据的不会加速单股查询
                stocks = pending

        # ── [3/4] 遍历股票，插入数据 ─────────────────────────────────
        print(f"\n[4/4] 获取历史行情并写入...")
        total_success = 0
        total_skipped = 0
        total_failed = 0
        total_no_data = 0

        processed_codes = []
        for i, (code, name, market) in enumerate(stocks, 1):
            try:
                # 每50只重新登录 Baostock
                if i > 1 and (i - 1) % 50 == 0:
                    try:
                        with suppress_stdout():
                            bs.logout()
                        time.sleep(1)
                        with suppress_stdout():
                            lg = bs.login()
                        if lg.error_code != '0':
                            print(f"[WARN] Baostock 重登失败: {lg.error_msg}, 3秒后重试...")
                            time.sleep(3)
                            with suppress_stdout():
                                lg = bs.login()
                            if lg.error_code != '0':
                                print(f"[ERROR] Baostock 重登彻底失败: {lg.error_msg}，终止脚本")
                                sys.exit(1)
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
                    n = db.upsert_daily(rows, force=args.force)
                    total_success += n
                    total_skipped += len(df) - n
                    processed_codes.append((code, market))
                    print(f"[{i}/{len(stocks)}] {code} {name}: 写入 {n} 条")
                else:
                    # df is None 或 df 为空（如所有行 tradestatus=0 被过滤）
                    total_no_data += 1

                if i % args.batch_size == 0 and i < len(stocks):
                    if total_success > 0:
                        print(f"[累计] 写入记录: {total_success:,} 条")
                    time.sleep(args.delay)

            except TimeoutError:
                print(f"[FATAL] 发生超时，脚本终止")
                sys.exit(1)
            except Exception as e:
                print(f"[ERROR] {code} 处理异常: {e}")
                total_failed += 1
                continue

        # 统计
        elapsed = time.time() - start_time
        print(f"\n完成")
        print("-" * 70)
        print(f"总耗时: {elapsed:.1f} 秒")
        print(f"处理股票: {len(stocks)} 只")
        print(f"成功记录: {total_success:,} 条")
        print(f"跳过已存在: {total_skipped} 条")
        print(f"失败记录: {total_failed} 条")
        print(f"无数据: {total_no_data} 只")
        print("=" * 70)

        # ─── 自动补全 change 字段 + PE/PB ────────────────────────────
        # 即使日线采集全部跳过（total_success==0），仍触发补全以消化历史缺口
        run_completion = total_success > 0 or skipped > 0
        if run_completion:
            print(f"\n补全 change 字段 + PE/PB（pre_close/change_percent/change_amount + 估值数据）...")
            try:
                from field_completer import complete_fields
                # 日线全部跳过时传 None，让 complete_fields 自动扫全库缺口
                n = complete_fields(db,
                                   code=args.code if args.code else None,
                                   stock_list=processed_codes if not args.code and total_success > 0 else None,
                                   skip_valuation=False,
                                   force_full_scan=(total_success == 0))
                print(f"  补全完成: {n} 条")
            except Exception as e:
                print(f"  [WARN] change 字段补全异常（不影响日线数据）: {e}")

        return 0 if total_failed == 0 else 1

    finally:
        db.close()
        with suppress_stdout():
            bs.logout()
        print("Baostock 登出成功")


if __name__ == '__main__':
    sys.exit(main())
