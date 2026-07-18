#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
refresh_qfq_history.py
======================
前复权因子刷新脚本。

问题：ClickHouse stock_daily 存储的 qfq (前复权) 价格是时点快照。
      每次除权除息后，Baostock 会 retroactive 更新所有历史日期的 qfq 因子，
      但 CH 中旧数据不会自动更新，导致历史 qfq 价格过期。

解决方案：
  1. 查询 stock_dividend 表，找出近期发生除权除息的股票
  2. 对每只受影响股票，从 Baostock 重新拉取完整历史 (adjustflag="2")
     - 从上市日到今天全部刷新（qfq因子是retroactive全量更新的）
  3. 写入 CH 时使用 refresh_version=True，update_time=now() 确保覆盖旧快照

触发时机：DIVIDEND 任务完成后自动触发（通过 data_task_dependency 配置）
也可手动执行：python refresh_qfq_history.py --days 1 --timeout 30

注意：仅处理沪深股票（Baostock 覆盖范围），北交所由腾讯 API 处理。
      串行执行（Baostock 不支持多进程并行），timeout=30s/股/块，delay=0.1s。
      分块拉取：长历史股票按3年/块分块查询，避免单次长查询超时。
      单块超时后自动 re-login + retry 1次，失败则跳过该块继续后续块。
      任何错误后自动 re-login + retry 1次。
"""

import sys
import time
import argparse
import threading
import os
from datetime import datetime, date, timedelta
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


from db_config import get_backend_label
from db_helper import StockDailyDB, to_float, to_int


# 全局 Baostock 登录状态锁
_bs_lock = threading.Lock()
_bs_logged_in = False


def bs_login():
    """登录 Baostock（线程安全）"""
    global _bs_logged_in
    with _bs_lock:
        try:
            with suppress_stdout():
                bs.logout()
            time.sleep(0.5)
        except:
            pass
        try:
            with suppress_stdout():
                lg = bs.login()
            if lg.error_code == '0':
                _bs_logged_in = True
                return True
            else:
                _bs_logged_in = False
                return False
        except:
            _bs_logged_in = False
            return False


def bs_relogin():
    """重新登录 Baostock（错误恢复用）"""
    global _bs_logged_in
    with _bs_lock:
        try:
            with suppress_stdout():
                bs.logout()
        except:
            pass
        time.sleep(1)
        try:
            with suppress_stdout():
                lg = bs.login()
            if lg.error_code == '0':
                _bs_logged_in = True
                return True
            else:
                _bs_logged_in = False
                return False
        except:
            _bs_logged_in = False
            return False


def get_baostock_code(code, market):
    """转换为 Baostock 需要的股票代码格式: SH -> sh.600000"""
    if market == "SH":
        return f"sh.{code}"
    elif market == "SZ":
        return f"sz.{code}"
    return None


def _fetch_single_query(code, market, start_date, end_date, timeout=30):
    """
    单次 Baostock 查询（带超时控制和重登录重试）。
    返回: DataFrame 或 None
    """
    import pandas as pd

    bs_code = get_baostock_code(code, market)
    if not bs_code:
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
            adjustflag="2",  # 2: 前复权
        )
        data_list = []
        while (rs.error_code == '0') and rs.next():
            data_list.append(rs.get_row_data())
        return data_list, rs.fields

    max_attempts = 2  # 最多尝试2次（1次正常 + 1次重登录后重试）
    for attempt in range(max_attempts):
        try:
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
                # 超时：线程仍在后台运行（无法杀掉），必须 re-login
                if attempt < max_attempts - 1:
                    print(f"      [TIMEOUT] {code} {start_str}~{end_str} 超时({timeout}s)，重新登录...")
                    bs_relogin()
                    continue
                else:
                    print(f"      [SKIP] {code} {start_str}~{end_str} 超时，跳过此段")
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
            should_relogin = (
                "codec can't decode" in err_msg or
                "decompressing data" in err_msg or
                "NoneType" in err_msg or
                "has no attribute" in err_msg or
                "10054" in err_msg or
                "10060" in err_msg or
                "10053" in err_msg or
                "Connection" in err_msg or
                "reset" in err_msg.lower()
            )

            if should_relogin and attempt < max_attempts - 1:
                print(f"      [RETRY] {code} {start_str}~{end_str} Baostock错误({err_msg[:40]})，重新登录...")
                bs_relogin()
                continue
            else:
                print(f"      [SKIP] {code} {start_str}~{end_str} 失败: {err_msg[:40]}")
                return None

    return None


def fetch_stock_history(code, market, start_date, end_date, timeout=30, chunk_years=3):
    """
    使用 Baostock 获取单只股票的历史行情（前复权）。
    采用分块拉取策略：将日期范围按 chunk_years 年分块，每块独立超时控制。
    单块失败不影响其他块，最终合并所有成功的数据。
    返回: DataFrame 或 None
    """
    import pandas as pd

    # 计算分块
    chunk_days = 365 * chunk_years
    chunks = []
    current_start = start_date
    while current_start < end_date:
        current_end = min(current_start + timedelta(days=chunk_days), end_date)
        chunks.append((current_start, current_end))
        current_start = current_end + timedelta(days=1)

    if len(chunks) <= 1:
        # 只有一块，直接查询
        return _fetch_single_query(code, market, start_date, end_date, timeout)

    print(f"    [CHUNK] {code} 分 {len(chunks)} 块拉取 ({start_date} ~ {end_date})")

    all_dfs = []
    for i, (chunk_start, chunk_end) in enumerate(chunks):
        df = _fetch_single_query(code, market, chunk_start, chunk_end, timeout)
        if df is not None and len(df) > 0:
            all_dfs.append(df)
        if i < len(chunks) - 1:
            time.sleep(0.1)  # 块间延迟

    if not all_dfs:
        return None

    result = pd.concat(all_dfs, ignore_index=True)
    failed_chunks = len(chunks) - len(all_dfs)
    if failed_chunks > 0:
        print(f"    [CHUNK] {code} {len(chunks)}块中成功{len(all_dfs)}块失败{failed_chunks}块，共{len(result)}条")
    return result


def build_daily_rows(db, code, name, market, df):
    """将 Baostock DataFrame 转换为 upsert_daily 需要的 row list。"""
    if df is None or len(df) == 0:
        return []

    first_date = df.iloc[0]['date']
    prev_close = db.get_prev_close(code, first_date)

    rows = []
    for _, row in df.iterrows():
        close_price = to_float(row['close'])
        pct_chg = to_float(row['pctChg'])
        bs_preclose = to_float(row['preclose'])

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

        change_percent = pct_chg

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
            "data_source": "baostock_qfq_refresh",
        })

        prev_close = close_price

    return rows


def get_ex_dividend_stocks(db, days=7, max_stocks=None):
    """
    查询最近 N 天内发生除权除息的股票列表。
    从 MySQL stock_dividend 表查询。
    返回: [(code, name, market, ex_date), ...]
    """
    cutoff_date = (datetime.now().date() - timedelta(days=days))
    sql = """
        SELECT DISTINCT d.code, d.name, d.ex_dividend_date,
               COALESCE(s.market, '') as market
        FROM stock_dividend d
        LEFT JOIN stock_info s ON d.code = s.code
        WHERE d.ex_dividend_date >= %s
          AND d.ex_dividend_date <= CURDATE()
          AND (s.delist_date IS NULL OR s.delist_date > CURDATE())
        ORDER BY d.ex_dividend_date DESC
    """
    try:
        with db.mysql_info_conn.cursor() as cur:
            cur.execute(sql, (cutoff_date,))
            rows = cur.fetchall()
        # 过滤掉无法确定市场的（非沪深）
        result = []
        for r in rows:
            market = r.get('market', '')
            if market in ('SH', 'SZ'):
                result.append((r['code'], r['name'], market, r['ex_dividend_date']))
        if max_stocks and len(result) > max_stocks:
            result = result[:max_stocks]
        return result
    except Exception as e:
        print(f"[ERROR] 查询除权除息股票失败: {e}")
        return []


def get_stock_info(db, code):
    """从 stock_info 查询单只股票信息"""
    sql = "SELECT code, name, market, list_date FROM stock_info WHERE code = %s"
    try:
        with db.mysql_info_conn.cursor() as cur:
            cur.execute(sql, (code,))
            return cur.fetchone()
    except Exception:
        return None


def main():
    parser = argparse.ArgumentParser(description="前复权因子刷新: 重新拉取除权股票的历史qfq数据")
    parser.add_argument("--days", type=int, default=7,
                       help="查找最近N天内除权除息的股票 (默认: 7)")
    parser.add_argument("--code", type=str,
                       help="只刷新指定股票 (测试用, 格式: 纯数字代码)")
    parser.add_argument("--start-date", type=str, default=None,
                       help="历史数据起始日期 (默认: 股票上市日)")
    parser.add_argument("--end-date", type=str, default=None,
                       help="历史数据结束日期 (默认: 今天)")
    parser.add_argument("--max-stocks", type=int, default=None,
                       help="单次最多处理股票数 (默认: 无限制)")
    parser.add_argument("--delay", type=float, default=0.1,
                       help="股票间延迟秒数 (默认: 0.1)")
    parser.add_argument("--timeout", type=int, default=30,
                       help="单只股票单次请求超时秒数 (默认: 30)")
    parser.add_argument("--chunk-years", type=int, default=3,
                       help="分块拉取的年数 (默认: 3年/块, 0=不分块)")
    parser.add_argument("--workers", type=int, default=3,
                       help="已弃用: Baostock不支持多进程并行, 保持串行模式")
    args = parser.parse_args()

    end_date = datetime.strptime(args.end_date, "%Y-%m-%d").date() if args.end_date else datetime.now().date()
    explicit_start = datetime.strptime(args.start_date, "%Y-%m-%d").date() if args.start_date else None

    print("=" * 70)
    print(f"前复权因子刷新脚本 (Baostock -> {get_backend_label()})")
    print(f"  历史范围: 上市日 ~ 今天 | 超时: {args.timeout}s | 延迟: {args.delay}s")
    print("=" * 70)

    db = StockDailyDB()
    start_time = time.time()

    try:
        # 获取需要刷新的股票列表
        if args.code:
            # 单只股票模式
            info = get_stock_info(db, args.code)
            if not info:
                print(f"[ERROR] 股票 {args.code} 不在 stock_info 中")
                return
            market = info.get('market', '')
            if market not in ('SH', 'SZ'):
                print(f"[ERROR] {args.code} 市场={market}, Baostock仅支持沪深")
                return
            stocks_to_refresh = [(args.code, info.get('name', ''), market, None)]
        else:
            # 查询近期除权除息股票
            print(f"\n[1/3] 查询最近 {args.days} 天内除权除息的股票...")
            stocks_to_refresh = get_ex_dividend_stocks(db, args.days, args.max_stocks)

        print(f"        待刷新股票: {len(stocks_to_refresh)} 只")
        if not stocks_to_refresh:
            print("[INFO] 无需刷新的股票，退出")
            return

        for code, name, market, ex_date in stocks_to_refresh:
            print(f"  - {code} {name} ex_date={ex_date} market={market}")

        # 串行刷新
        if not args.code:
            print(f"\n[2/3] 开始刷新前复权历史数据 (timeout={args.timeout}s, 全量历史)...")

        print("\n正在登录 Baostock...")
        if not bs_login():
            print("[ERROR] Baostock 登录失败")
            return
        print("Baostock 登录成功")

        total = len(stocks_to_refresh)
        total_refreshed = 0
        total_failed = 0
        failed_stocks = []

        for i, (code, name, market, ex_date) in enumerate(stocks_to_refresh):
            pct = (i + 1) * 100 // total
            elapsed = time.time() - start_time
            if i > 0:
                eta = elapsed / i * (total - i)
                eta_str = f"ETA {eta:.0f}s"
            else:
                eta_str = "ETA --"
            print(f"\n  [{i+1}/{total}] ({pct}%) {code} {name} (ex_date={ex_date}) [{eta_str}]")

            # 确定起始日期：从上市日开始拉取（qfq因子是retroactive全量更新的）
            if explicit_start:
                fetch_start = explicit_start
            else:
                info = get_stock_info(db, code)
                if info and info.get('list_date'):
                    ld = info['list_date']
                    if isinstance(ld, str):
                        ld = datetime.strptime(ld, "%Y-%m-%d").date()
                    elif isinstance(ld, datetime):
                        ld = ld.date()
                    fetch_start = ld
                else:
                    fetch_start = date(1990, 1, 1)

            try:
                df = fetch_stock_history(code, market, fetch_start, end_date,
                                         timeout=args.timeout, chunk_years=args.chunk_years)
                if df is None or len(df) == 0:
                    print(f"    [WARN] 未获取到数据，跳过")
                    total_failed += 1
                    failed_stocks.append(code)
                    continue

                rows = build_daily_rows(db, code, name, market, df)
                if not rows:
                    print(f"    [WARN] 转换后无数据，跳过")
                    total_failed += 1
                    failed_stocks.append(code)
                    continue

                inserted = db.upsert_daily(rows, force=True, refresh_version=True)
                total_refreshed += inserted
                print(f"    [OK] 刷新 {inserted} 条历史记录 (date: {rows[0]['trade_date']} ~ {rows[-1]['trade_date']})")

            except TimeoutError:
                print(f"    [ERROR] 请求超时，跳过")
                total_failed += 1
                failed_stocks.append(code)
            except Exception as e:
                print(f"    [ERROR] 刷新失败: {e}")
                total_failed += 1
                failed_stocks.append(code)

            if args.delay > 0 and i < len(stocks_to_refresh) - 1:
                time.sleep(args.delay)

        try:
            with suppress_stdout():
                bs.logout()
        except:
            pass

        elapsed = time.time() - start_time
        print(f"\n[3/3] 刷新完成")
        print(f"  总计刷新: {total_refreshed} 条")
        print(f"  成功: {total - total_failed} 只 | 失败: {total_failed} 只")
        print(f"  耗时: {elapsed:.1f}s")
        if failed_stocks:
            print(f"  失败股票: {', '.join(failed_stocks[:20])}")
            if len(failed_stocks) > 20:
                print(f"  ... 及其他 {len(failed_stocks) - 20} 只")

        if total_refreshed > 0 and db.backend == "clickhouse":
            print(f"\n[提示] 建议执行 OPTIMIZE TABLE 加速 CH 合并:")
            print(f"  OPTIMIZE TABLE stock.stock_daily FINAL")

    finally:
        db.close()


if __name__ == "__main__":
    main()
