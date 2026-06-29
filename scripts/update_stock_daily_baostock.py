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


# ─── 串行处理（主进程内，用于失败重分配）───────────────────────────
def _run_sequential(stocks, start_date, end_date, force, inter_stock_delay,
                    progress_queue=None, worker_id=-1):
    """
    在当前进程内串行处理一批股票。用于并行 worker 失败后的 fallback。
    返回 stats dict，与 worker 返回格式一致。
    """
    import baostock as _bs
    from db_helper import StockDailyDB

    lg = _bs.login()
    if lg.error_code != '0':
        return {"success": 0, "skipped": 0, "failed": len(stocks), "no_data": 0,
                "processed": [], "error": f"Baostock login failed: {lg.error_msg}"}

    db = StockDailyDB()
    stats = {"success": 0, "skipped": 0, "failed": 0, "no_data": 0, "processed": []}
    consecutive_no_data = 0

    try:
        for i, (code, name, market) in enumerate(stocks):
            try:
                # 每50只重登
                if i > 0 and i % 50 == 0:
                    _bs.logout()
                    time.sleep(1)
                    rl = _bs.login()
                    if rl.error_code != '0':
                        stats["failed"] += len(stocks) - i
                        break

                df = fetch_stock_history(code, name, market, start_date, end_date)

                if df is not None and len(df) > 0:
                    rows = build_daily_rows(db, code, name, market, df)
                    n = db.upsert_daily(rows, force=force)
                    stats["success"] += n
                    stats["skipped"] += len(df) - n
                    stats["processed"].append((code, market))
                    consecutive_no_data = 0
                else:
                    stats["no_data"] += 1
                    consecutive_no_data += 1
                    if consecutive_no_data >= 20:
                        print(f"[串行] 连续20只无数据，终止", flush=True)
                        break

                if inter_stock_delay > 0 and i < len(stocks) - 1:
                    time.sleep(inter_stock_delay)

            except Exception:
                stats["failed"] += 1
                continue

            # 进度汇报（每10只）
            if progress_queue and (i + 1) % 10 == 0:
                progress_queue.put((worker_id if worker_id >= 0 else 99,
                                    i + 1, len(stocks), stats, "fallback"))
    finally:
        db.close()
        _bs.logout()

    return stats


# ─── 多进程 Worker ────────────────────────────────────────────────
def _mp_worker_process_chunk(args, progress_queue=None):
    """
    多进程 worker：在子进程中处理一批股票，每个 worker 独立登录 Baostock。
    绕过 Baostock 单 socket 串行限制，实现真正的并行。

    args: (chunk, start_date_str, end_date_str, force, inter_stock_delay, worker_id)
        chunk: [(code, name, market), ...]
        worker_id: worker编号（用于日志标识）
    progress_queue: multiprocessing.Queue，定期向主进程推送进度
    """
    import datetime as _dt
    import time as _time
    import sys as _sys
    import os as _os
    from contextlib import contextmanager
    import baostock as _bs
    from db_helper import StockDailyDB

    @contextmanager
    def _suppress_stdout():
        orig = _sys.stdout
        _sys.stdout = open(_os.devnull, 'w')
        try:
            yield
        finally:
            _sys.stdout.close()
            _sys.stdout = orig

    chunk, start_date_str, end_date_str, force, inter_stock_delay, worker_id = args
    start_date = _dt.datetime.strptime(start_date_str, "%Y-%m-%d").date()
    end_date = _dt.datetime.strptime(end_date_str, "%Y-%m-%d").date()

    # 错开启动：每个 worker 按 worker_id * 5s 延迟，避免同时冲击 Baostock 登录
    if worker_id > 0:
        stagger_delay = worker_id * 5
        if progress_queue:
            progress_queue.put(f"[W{worker_id}] 等待{stagger_delay}s后启动...")
        _time.sleep(stagger_delay)

    # 每个子进程独立登录 Baostock（独立 TCP Socket），登录失败自动重试5次
    login_max_retries = 5
    lg = None
    for _retry in range(login_max_retries):
        with _suppress_stdout():
            lg = _bs.login()
        if lg.error_code == '0':
            break
        if _retry < login_max_retries - 1:
            delay = 5 * (_retry + 1) + worker_id * 2  # 递增延迟: 5+2wid, 10+2wid, 15+2wid...
            _time.sleep(delay)
            if progress_queue:
                progress_queue.put(f"[W{worker_id}] 登录失败(第{_retry+1}次): {lg.error_msg}, {delay}s后重试...")
    if lg is None or lg.error_code != '0':
        return {"success": 0, "skipped": 0, "failed": len(chunk), "no_data": 0,
                "processed": [], "error": f"Baostock login failed after {login_max_retries} retries: {lg.error_msg if lg else 'unknown'}"}

    db = StockDailyDB()
    stats = {"success": 0, "skipped": 0, "failed": 0, "no_data": 0, "processed": []}
    _consecutive_no_data = 0
    _progress_interval = 5  # 每5只股票汇报一次进度

    try:
        for i, (code, name, market) in enumerate(chunk):
            try:
                # 每 50 只重登一次，防止连接超时（带重试）
                if i > 0 and i % 50 == 0:
                    with _suppress_stdout():
                        _bs.logout()
                    _time.sleep(1)
                    _relogin_ok = False
                    for _rl in range(5):
                        with _suppress_stdout():
                            lg = _bs.login()
                        if lg.error_code == '0':
                            _relogin_ok = True
                            break
                        _time.sleep(5 * (_rl + 1) + worker_id * 2)
                    if not _relogin_ok:
                        stats["failed"] += len(chunk) - i
                        break

                df = fetch_stock_history(code, name, market, start_date, end_date)

                if df is not None and len(df) > 0:
                    rows = build_daily_rows(db, code, name, market, df)
                    n = db.upsert_daily(rows, force=force)
                    stats["success"] += n
                    stats["skipped"] += len(df) - n
                    stats["processed"].append((code, market))
                    _consecutive_no_data = 0  # 重置
                else:
                    stats["no_data"] += 1
                    _consecutive_no_data += 1
                    if _consecutive_no_data == 20:
                        # Worker 内连续20只无数据 → 提前终止，不处理剩余
                        stats["failed"] += len(chunk) - i - 1
                        stats["no_data"] += len(chunk) - i - 1
                        if progress_queue:
                            progress_queue.put((worker_id, i+1, len(chunk), dict(stats)))
                        break

                # 每 _progress_interval 只向主进程汇报进度
                if progress_queue and (i + 1) % _progress_interval == 0:
                    progress_queue.put((worker_id, i+1, len(chunk), dict(stats)))

                # 股票间微延迟，避免冲击服务器
                if inter_stock_delay > 0 and i < len(chunk) - 1:
                    _time.sleep(inter_stock_delay)

            except TimeoutError:
                stats["failed"] += len(chunk) - i
                if progress_queue:
                    progress_queue.put((worker_id, i+1, len(chunk), dict(stats), "TIMEOUT"))
                break
            except Exception:
                stats["failed"] += 1
                continue
    finally:
        db.close()
        with _suppress_stdout():
            _bs.logout()

    return stats


def main():
    parser = argparse.ArgumentParser(description="使用 Baostock 获取沪深股票历史数据")
    parser.add_argument("--start-date", type=str, default="2026-03-01", help="开始日期 (默认: 2026-03-01)")
    parser.add_argument("--end-date", type=str, default=None, help="结束日期 (默认: 今天)")
    parser.add_argument("--code", type=str, help="只处理指定股票 (测试用)")
    parser.add_argument("--market", choices=["SH", "SZ", "ALL"], default=None,
                       help="只处理指定市场 (不指定=全市场)")
    parser.add_argument("--limit", type=int, default=0, help="只处理前N只股票 (测试用)")
    parser.add_argument("--batch-size", type=int, default=10, help="每批处理的股票数 (默认:10)")
    parser.add_argument("--delay", type=float, default=0.3, help="批次间延迟秒数 (默认:0.3)")
    parser.add_argument("--resume", action="store_true", help="断点续传(跳过已有数据的股票)")
    parser.add_argument("--force", action="store_true", help="强制写入(跳过去重预过滤，直接INSERT覆盖)")
    parser.add_argument("--pool", type=str, default=None,
                       choices=["SH300", "SZ50", "ZZ500", "ZZ1000", "STAR50"],
                       help="股票池筛选 (SH300/SZ50/ZZ500/ZZ1000/STAR50)")
    parser.add_argument("--workers", type=int, default=1,
                       help="并行进程数 (default:1 串行; Baostock并发限制低, 多worker易失败)")
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
    print(f"市场: {args.market if args.market else '全市场'}")
    print(f"批次大小: {args.batch_size}")
    print(f"批次延迟: {args.delay}秒")
    print(f"断点续传: {'是' if args.resume else '否'}")
    print(f"强制写入: {'是' if args.force else '否'}")
    if args.workers > 1:
        print(f"并行模式: {args.workers} workers（加速 ~{args.workers}×）")
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
                if args.market and args.market != "ALL":
                    stocks = [(c, n, m) for (c, n, m) in all_pool_stocks if m == args.market]
                else:
                    stocks = all_pool_stocks
            else:
                market_filter = args.market if args.market and args.market != "ALL" else None
                stocks = db.get_stocks(market=market_filter, limit=args.limit)

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

        # ── 遍历股票，插入数据 ─────────────────────────────────────
        total_count = len(stocks)
        total_success = 0
        total_skipped = 0
        total_failed = 0
        total_no_data = 0
        consecutive_no_data = 0        # 连续无数据计数器
        CONSECUTIVE_WARN = 10          # 连续N只无数据→警告
        CONSECUTIVE_ABORT = 50         # 连续N只无数据→终止
        processed_codes = []

        if args.workers > 1 and total_count > 0:
            # ──────────── 并行模式：多进程同时请求 Baostock ────────────
            from concurrent.futures import ProcessPoolExecutor, as_completed
            import multiprocessing as _mp

            workers = min(args.workers, total_count)
            if workers > 2:
                print(f"        ⚠️ Baostock 并发限制低, workers={workers} 易失败, 建议用 --workers 1 或 2", flush=True)
            print(f"\n[并行] {workers} worker × {total_count} 只股票...", flush=True)

            chunk_size = (total_count + workers - 1) // workers
            chunks = [stocks[i:i + chunk_size] for i in range(0, total_count, chunk_size)]
            print(f"        每个 worker ~{chunk_size} 只，预计加速 ~{workers}×", flush=True)

            # 创建进度队列（子进程→主进程）
            # Manager().Queue() 是代理对象，可序列化传递给 spawn 子进程
            # （_mp.Queue() 只能通过继承共享，submit() 无法传递）
            _mgr = _mp.Manager()
            progress_queue = _mgr.Queue()
            # 主进程轮询进度，实时打印
            _stop_poll = threading.Event()
            def _poll_progress():
                while not _stop_poll.is_set():
                    try:
                        msg = progress_queue.get(timeout=2)
                        if isinstance(msg, str):
                            # 字符串消息：直接打印（启动延迟、登录重试等）
                            print(msg, flush=True)
                        else:
                            # 元组消息：格式化进度
                            wid, done, total, st = msg[0], msg[1], msg[2], msg[3]
                            tag = msg[4] if len(msg) > 4 else ""
                            pct = done * 100 / total
                            prefix = f"[W{wid}]" if not tag else f"[W{wid}]"
                            extra = f" ({tag})" if tag else ""
                            print(f"{prefix} {done}/{total} ({pct:.0f}%) "
                                  f"写入{st['success']} 跳过{st['skipped']} "
                                  f"无数据{st['no_data']} 失败{st['failed']}{extra}",
                                  flush=True)
                    except:
                        pass  # queue.get timeout → continue polling
            poll_thread = threading.Thread(target=_poll_progress, daemon=True)
            poll_thread.start()

            ctx = _mp.get_context('spawn')
            parallel_start = time.time()
            with ProcessPoolExecutor(max_workers=workers, mp_context=ctx) as executor:
                futures = {}
                for j, chunk in enumerate(chunks):
                    f = executor.submit(
                        _mp_worker_process_chunk,
                        (chunk, start_date.strftime("%Y-%m-%d"),
                         end_date.strftime("%Y-%m-%d"), args.force, 0.05, j),
                        progress_queue
                    )
                    futures[f] = j

                completed_w = 0
                failed_chunks = []  # 收集失败的 chunk，后续重新分配
                for f in as_completed(futures):
                    completed_w += 1
                    try:
                        r = f.result()
                        if "error" in r:
                            wid = futures[f]
                            print(f"[ERROR] Worker{wid} 失败: {r['error']}", flush=True)
                            # 登录类失败 → 标记该 chunk 待重分配
                            if "login" in r.get("error", "").lower():
                                failed_chunks.append((wid, chunks[wid], r.get("processed", [])))
                            else:
                                total_failed += r["failed"]
                                total_no_data += r.get("no_data", 0)
                        else:
                            total_success += r["success"]
                            total_skipped += r["skipped"]
                            total_failed += r["failed"]
                            total_no_data += r["no_data"]
                        processed_codes.extend(r.get("processed", []))
                        elapsed_w = time.time() - parallel_start
                        print(f"[并行] Worker{futures[f]} 完成: {r.get('success', 0)}条写入 "
                              f"({completed_w}/{workers}, "
                              f"已耗时{elapsed_w:.0f}s, 预计剩余~{elapsed_w/completed_w*(workers-completed_w):.0f}s)",
                              flush=True)
                    except Exception as e:
                        print(f"[ERROR] Worker{futures[f]} 异常: {e}", flush=True)
                        failed_chunks.append((futures[f], chunks[futures[f]], []))
                        total_failed += len(chunks[futures[f]])

                # ── 失败 chunk 重分配（登录失败时）──
                if failed_chunks:
                    remaining = []
                    for wid, orig_chunk, done_codes in failed_chunks:
                        # 排除已处理过的股票
                        done_set = set(done_codes)
                        retry_chunk = [(c, n, m) for c, n, m in orig_chunk if (c, m) not in done_set]
                        if retry_chunk:
                            remaining.extend(retry_chunk)
                            print(f"[重分配] Worker{wid} 剩余 {len(retry_chunk)} 只待重试", flush=True)

                    if remaining and total_success > 0:
                        print(f"\n[重分配] 共 {len(remaining)} 只失败股票，用串行模式补跑...", flush=True)
                        # 用串行模式逐只处理（避免再次并发冲突）
                        _retry_stats = _run_sequential(remaining, start_date, end_date,
                                                       args.force, 0.05, progress_queue, -1)
                        total_success += _retry_stats["success"]
                        total_skipped += _retry_stats["skipped"]
                        total_failed += _retry_stats["failed"]
                        total_no_data += _retry_stats["no_data"]
                        processed_codes.extend(_retry_stats.get("processed", []))
                    elif remaining:
                        print(f"\n[重分配] {len(remaining)} 只股票全部跳过（无可用 worker）", flush=True)

            # 停止进度轮询线程
            _stop_poll.set()
            poll_thread.join(timeout=5)
            _mgr.shutdown()

        else:
            # ──────────── 串行模式（原有逻辑） ────────────
            print(f"\n[4/4] 获取历史行情并写入（共 {total_count} 只股票）...", flush=True)
            for i, (code, name, market) in enumerate(stocks, 1):
                try:
                    # 每5只或每50只打印进度（防止长时间无输出）
                    if (i - 1) % 50 == 0 or i <= 3 or i % 200 == 0:
                        pct = i * 100 / total_count
                        print(f"[进度] {i}/{total_count} ({pct:.1f}%) 正在 {code} {name}...", flush=True)
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
                        consecutive_no_data = 0  # 重置
                    else:
                        # df is None 或 df 为空（如所有行 tradestatus=0 被过滤）
                        total_no_data += 1
                        consecutive_no_data += 1
                        if consecutive_no_data == CONSECUTIVE_WARN:
                            print(f"[WARN] 连续 {consecutive_no_data} 只股票无数据返回！"
                                  f"数据源可能尚未更新 {actual_start}~{end_date} 的行情，"
                                  f"继续等待可能白跑 {total_count - i} 只...", flush=True)
                        elif consecutive_no_data == CONSECUTIVE_ABORT:
                            print(f"[ABORT] 连续 {consecutive_no_data} 只股票无数据，"
                                  f"确认数据源暂无 {actual_start}~{end_date} 的行情，终止脚本。", flush=True)
                            sys.exit(0)

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
        run_completion = total_success > 0 or skipped > 0
        if run_completion:
            print(f"\n补全 change 字段 + PE/PB（pre_close/change_percent/change_amount + 估值数据）...")
            try:
                from field_completer import complete_fields
                if args.code:
                    # 指定单只股票时全量补缺
                    target_dates = None
                    scan_mode = "full"
                    stock_list_arg = None
                elif total_success > 0:
                    # 正常更新：只补当天，只处理刚更新的股票
                    target_dates = [str(actual_end_date)]
                    scan_mode = "full"  # stock_list已指定，scan_mode不影响
                    stock_list_arg = processed_codes
                else:
                    # 重复运行（全部跳过）：只补当天，用never_processed避免扫描2694只亏损企业
                    target_dates = [str(actual_end_date)]
                    scan_mode = "never_processed"
                    stock_list_arg = None
                n = complete_fields(db,
                                   code=args.code if args.code else None,
                                   stock_list=stock_list_arg,
                                   skip_valuation=False,
                                   target_dates=target_dates,
                                   scan_mode=scan_mode)
                print(f"  补全完成: {n} 条")

                # ── 日常渐进补缺：额外补新上市/从未处理的股票 ────
                # 只扫描"从未被处理过的"股票（所有日期 PE+PB 都 NULL），
                # 避免重复处理已有部分 PE/PB 值但剩余 NULL 是正常亏损期的股票
                HISTORICAL_BACKFILL_LIMIT = 100
                if not args.code:
                    try:
                        from field_completer import fix_valuation_by_qq
                        print(f"\n历史 PE/PB 渐进补缺（最多 {HISTORICAL_BACKFILL_LIMIT} 只，仅从未处理的）...")
                        n_hist = fix_valuation_by_qq(db, codes=None, target_dates=None,
                                                     max_stocks=HISTORICAL_BACKFILL_LIMIT,
                                                     scan_mode="never_processed")
                        print(f"  历史补缺完成: {n_hist} 条")
                    except Exception as e:
                        print(f"  [WARN] 历史 PE/PB 补缺异常（不影响日线）: {e}")
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
