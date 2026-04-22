#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_dividend_baostock.py
==========================
采集分红除权数据，写入 stock_dividend 表。

数据源: 巨潮资讯 webapi (直接调用，绕过 akshare 的字段名 bug)
- akshare stock_dividend_cninfo 对部分股票(如688981)因缺少 F006D 列而 KeyError
- 本脚本直接调用巨潮 API，字段缺失时优雅处理
- 一只股票一次返回全量分红，支持多线程并发，5200只 ≈ 10分钟完成
- 派息/送股/转增比例单位为"每10股"，入库前需除以10

覆盖范围: 沪深两市全部个股（SH/SZ）

用法:
    python update_dividend_baostock.py                    # 全量采集
    python update_dividend_baostock.py --code 600519      # 单只股票
    python update_dividend_baostock.py --market SH        # 只采集沪市
    python update_dividend_baostock.py --resume            # 跳过已有数据
    python update_dividend_baostock.py --limit 20          # 限制数量（测试用）
    python update_dividend_baostock.py --workers 5         # 并发线程数
"""

import sys
import time
import argparse
import threading
import warnings
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import py_mini_racer
import pymysql

# 抑制 urllib3 在线程中的 InsecureRequestWarning
warnings.filterwarnings("ignore")

# 全局 requests Session：连接池 + 重试
_session = None

def get_session(workers=5):
    global _session
    if _session is None:
        _session = requests.Session()
        _session.mount("https://", HTTPAdapter(
            pool_connections=workers,
            pool_maxsize=workers + 2,
            max_retries=Retry(total=2, backoff_factor=0.5, status_forcelist=[500, 502, 503, 504]),
        ))
        _session.headers.update({
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                         "(KHTML, like Gecko) Chrome/93.0.4577.63 Safari/537.36"
        })
    return _session


DB_CONFIG = dict(
    host="localhost", port=3306, db="stock",
    user="root", password="123456",
    charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
)

# 巨潮 API 字段映射（可能缺失）
FIELD_MAP = {
    "F006D": "实施方案公告日期",
    "F044V": "分红类型",
    "F011N": "转增比例",
    "F010N": "送股比例",
    "F012N": "派息比例",
    "F018D": "股权登记日",
    "F020D": "除权日",
    "F023D": "派息日",
    "F025D": "股份到账日",
    "F007V": "实施方案分红说明",
    "F001V": "报告时间",
}

CNINFO_URL = "https://webapi.cninfo.com.cn/api/sysapi/p_sysapi1139"

# 同花顺接口已失败的股票缓存（避免重复请求）
_fallback_failed = set()
_ths_semaphore = threading.Semaphore(2)  # 同花顺备用源并发限制为2
_em_failed = set()  # 东方财富接口已失败的股票缓存


def _get_mcode():
    """获取巨潮 API 的 Accept-Enckey"""
    from akshare.datasets import get_ths_js
    js_path = get_ths_js("cninfo.js")
    with open(js_path, encoding="utf-8") as f:
        js_content = f.read()
    ctx = py_mini_racer.MiniRacer()
    ctx.eval(js_content)
    return ctx.call("getResCode1")


def get_db():
    return pymysql.connect(**DB_CONFIG)


def ensure_table(conn):
    conn.cursor().execute("""
    CREATE TABLE IF NOT EXISTS `stock_dividend` (
        `id` BIGINT NOT NULL AUTO_INCREMENT,
        `code` VARCHAR(20) NOT NULL,
        `name` VARCHAR(100) DEFAULT NULL,
        `ex_dividend_date` DATE NOT NULL,
        `record_date` DATE DEFAULT NULL,
        `pay_date` DATE DEFAULT NULL,
        `cash_dividend` DECIMAL(14,6) DEFAULT NULL,
        `stock_dividend` DECIMAL(14,6) DEFAULT NULL,
        `convert_dividend` DECIMAL(14,6) DEFAULT NULL,
        `report_year` VARCHAR(10) DEFAULT NULL,
        `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (`id`),
        UNIQUE KEY `uk_code_ex_date` (`code`, `ex_dividend_date`),
        KEY `idx_code` (`code`),
        KEY `idx_ex_date` (`ex_dividend_date`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """)
    conn.commit()


def get_stocks(conn, market=None, limit=0):
    sql = "SELECT code, name, market FROM stock_info WHERE market IN ('SH','SZ')"
    if market:
        sql += f" AND market='{market}'"
    sql += " ORDER BY code"
    if limit > 0:
        sql += f" LIMIT {limit}"
    with conn.cursor() as c:
        c.execute(sql)
        return [(r['code'], r['name'], r['market']) for r in c.fetchall()]


def get_existing_codes(conn):
    with conn.cursor() as c:
        c.execute("SELECT DISTINCT code FROM stock_dividend")
        return {r['code'] for r in c.fetchall()}


def parse_date(s):
    if s is None:
        return None
    import pandas as pd
    # 处理 pandas NaT
    if pd.isna(s):
        return None
    # 处理 datetime.date / datetime.datetime 类型
    if hasattr(s, 'strftime'):
        return s.strftime('%Y-%m-%d')
    s = str(s).strip()
    if not s or s in ('', '-', 'None', 'nan', 'NaT'):
        return None
    for fmt in ('%Y-%m-%d', '%Y%m%d', '%Y/%m/%d'):
        try:
            return datetime.strptime(s, fmt).strftime('%Y-%m-%d')
        except ValueError:
            continue
    return None


def fetch_stock(code, name, mcode):
    """
    采集单只股票全部分红数据（巨潮资讯 webapi）。
    返回 (records_list, error_msg)
    每条 record = (code, name, ex_date, record_date, pay_date, cash, sd, cd, report_year)
    """
    try:
        sess = get_session()
        headers = {
            "Accept": "*/*",
            "Accept-Enckey": mcode,
            "Accept-Encoding": "gzip, deflate",
            "Content-Length": "0",
            "Origin": "http://webapi.cninfo.com.cn",
            "Referer": "http://webapi.cninfo.com.cn/",
        }
        r = sess.post(CNINFO_URL, params={"scode": code}, headers=headers, timeout=(5, 10))
        r.raise_for_status()
        data = r.json()
        records_raw = data.get("records", [])
        if not records_raw:
            return [], None

        records = []
        for item in records_raw:
            # 安全获取除权日（核心字段，缺失则跳过）
            ex_date = parse_date(item.get("F020D", ""))
            if not ex_date:
                continue

            # 巨潮比例单位是"每10股"，除以10得到"每股"
            raw_cash = item.get("F012N", 0)
            raw_sd = item.get("F010N", 0)
            raw_cd = item.get("F011N", 0)

            def safe_div(v):
                try:
                    v = float(v)
                    return v / 10.0 if v and v > 0 else 0.0
                except (ValueError, TypeError):
                    return 0.0

            cash = safe_div(raw_cash)
            sd = safe_div(raw_sd)
            cd = safe_div(raw_cd)

            if cash == 0 and sd == 0 and cd == 0:
                continue

            report_time = str(item.get("F001V", ""))
            report_year = report_time.replace('年报', '').replace('中报', '').replace('三季报', '').strip()[:4]
            if not report_year or not report_year.isdigit():
                report_year = None

            records.append((
                code, name, ex_date,
                parse_date(item.get("F018D", "")),  # 股权登记日
                parse_date(item.get("F023D", "")),  # 派息日
                cash, sd, cd,
                report_year,
            ))

        return records, None

    except Exception as e:
        return [], str(e)


def fetch_stock_ths(code, name):
    """
    备用数据源：同花顺 stock_history_dividend_detail
    当巨潮无数据时使用。派息/送股/转增单位为"每10股"，入库前除以10。
    返回 (records_list, error_msg)
    """
    global _fallback_failed, _ths_semaphore
    if code in _fallback_failed:
        return [], None

    try:
        import akshare as ak

        # 用信号量限制同花顺并发数（同花顺内部可能调巨潮API，并发过高会卡死）
        with _ths_semaphore:
            df = ak.stock_history_dividend_detail(symbol=code, indicator='分红', date='')
        if df is None or df.empty:
            _fallback_failed.add(code)
            return [], None

        records = []
        for _, row in df.iterrows():
            # 只处理已实施的分红（有除权除息日）
            ex_date = parse_date(row.get('除权除息日'))
            if not ex_date:
                continue

            def safe_div_ths(v):
                try:
                    v = float(v)
                    return v / 10.0 if v and v > 0 else 0.0
                except (ValueError, TypeError):
                    return 0.0

            cash = safe_div_ths(row.get('派息', 0))
            sd = safe_div_ths(row.get('送股', 0))
            cd = safe_div_ths(row.get('转增', 0))

            if cash == 0 and sd == 0 and cd == 0:
                continue

            record_date = parse_date(row.get('股权登记日'))
            report_year = None  # 同花顺不提供报告年度

            records.append((
                code, name, ex_date,
                record_date, None,  # pay_date 不可用
                cash, sd, cd,
                report_year,
            ))

        if not records:
            _fallback_failed.add(code)

        return records, None

    except Exception as e:
        _fallback_failed.add(code)
        return [], str(e)


def fetch_stock_em(code, name):
    """
    备用数据源：东方财富 stock_fhps_detail_em
    巨潮和同花顺都无数据时使用。数据源独立，覆盖面广。
    返回 (records_list, error_msg)
    注意：比例单位为"每10股"，入库前除以10
    """
    global _em_failed
    if code in _em_failed:
        return [], None

    try:
        import akshare as ak
        df = ak.stock_fhps_detail_em(symbol=code)
        if df is None or df.empty:
            _em_failed.add(code)
            return [], None

        records = []
        for _, row in df.iterrows():
            # 只处理已实施的分红（有除权除息日）
            ex_date = parse_date(row.get('除权除息日'))
            if not ex_date:
                continue

            def safe_div_em(v):
                try:
                    v = float(v)
                    return v / 10.0 if v and v > 0 else 0.0
                except (ValueError, TypeError):
                    return 0.0

            cash = safe_div_em(row.get('现金分红-现金分红比例', 0))
            sd = safe_div_em(row.get('送转股份-送股比例', 0))
            cd = safe_div_em(row.get('送转股份-转股比例', 0))

            if cash == 0 and sd == 0 and cd == 0:
                continue

            record_date = parse_date(row.get('股权登记日'))
            # 报告期取前4位作为报告年度
            report_period = str(row.get('报告期', ''))
            report_year = report_period[:4] if report_period and report_period[:4].isdigit() else None

            records.append((
                code, name, ex_date,
                record_date, None,  # pay_date 不可用
                cash, sd, cd,
                report_year,
            ))

        if not records:
            _em_failed.add(code)

        return records, None

    except Exception as e:
        _em_failed.add(code)
        return [], str(e)


def fetch_stock_with_fallback(code, name, mcode):
    """
    三级数据源回退：巨潮 → 同花顺 → 东方财富
    返回 (records_list, source, error_msg)
    source: 'cninfo' / 'ths' / 'em' / None
    error_msg: 仅当接口本身异常时返回，"无数据"不视为错误
    """
    # 第一级：巨潮资讯
    records, err = fetch_stock(code, name, mcode)
    if records:
        return records, 'cninfo', None

    # 第二级：同花顺
    records2, err2 = fetch_stock_ths(code, name)
    if records2:
        return records2, 'ths', None

    # 第三级：东方财富（独立数据源，覆盖面最广）
    records3, err3 = fetch_stock_em(code, name)
    if records3:
        return records3, 'em', None

    # 三个源都无数据：区分"接口异常"和"该股票无分红"
    ths_is_no_data = (err2 is None or 'No tables found' in str(err2))
    em_is_no_data = (err3 is None or 'NoneType' in str(err3))
    if ths_is_no_data and em_is_no_data and err is None:
        return [], None, None  # 真的没有分红数据，不报错
    return [], None, err or err2 or err3


def fetch_with_timeout(code, name, mcode, timeout=25):
    """
    带超时的采集封装。用 daemon 线程执行，超时直接放弃。
    返回 (records, source, error_msg, no_data)
    - no_data: True 表示三个源都确认该股票无分红数据
    - error_msg: 仅当接口异常或超时时有值
    """
    result = [None]

    def worker():
        try:
            result[0] = fetch_stock_with_fallback(code, name, mcode)
        except Exception as e:
            result[0] = ([], None, str(e))

    t = threading.Thread(target=worker, daemon=True)
    t.start()
    t.join(timeout=timeout)
    if t.is_alive():
        return [], None, f"接口超时({timeout}秒)，已跳过", False
    records, source, err = result[0]
    no_data = (not records and err is None)
    return records, source, err, no_data


# 线程安全的写入缓冲
_results_buffer = []
_buffer_lock = None  # 主线程的 threading.Lock


def save_batch(conn, records):
    if not records:
        return 0
    sql = """
    INSERT INTO stock_dividend (code,name,ex_dividend_date,record_date,pay_date,
                                 cash_dividend,stock_dividend,convert_dividend,report_year)
    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
    ON DUPLICATE KEY UPDATE
        name=VALUES(name), record_date=VALUES(record_date), pay_date=VALUES(pay_date),
        cash_dividend=VALUES(cash_dividend), stock_dividend=VALUES(stock_dividend),
        convert_dividend=VALUES(convert_dividend), report_year=VALUES(report_year)
    """
    count = 0
    with conn.cursor() as c:
        for r in records:
            try:
                c.execute(sql, r)
                count += 1
            except Exception as e:
                print(f"    [ERR] {r[0]} {r[2]}: {e}", file=sys.stderr)
    conn.commit()
    return count


def main():
    import threading

    parser = argparse.ArgumentParser(description='采集分红除权数据（巨潮资讯）')
    parser.add_argument('--code', type=str, help='指定股票代码')
    parser.add_argument('--market', type=str, choices=['SH', 'SZ'], help='指定市场')
    parser.add_argument('--resume', action='store_true', help='跳过已有数据的股票')
    parser.add_argument('--limit', type=int, default=0, help='限制数量（测试用）')
    parser.add_argument('--workers', type=int, default=5, help='并发线程数（默认5）')
    args = parser.parse_args()

    conn = get_db()
    ensure_table(conn)

    # 预获取巨潮 API 密钥（只需获取一次，所有线程共用）
    print("正在获取巨潮 API 密钥...")
    mcode = _get_mcode()
    print(f"密钥获取成功，开始采集...")

    if args.code:
        with conn.cursor() as c:
            c.execute("SELECT code,name,market FROM stock_info WHERE code=%s", (args.code,))
            row = c.fetchone()
            if not row:
                print(f"股票 {args.code} 不存在")
                sys.exit(1)
            stocks = [(row['code'], row['name'], row['market'])]
    else:
        stocks = get_stocks(conn, args.market, args.limit)

    total = len(stocks)
    skipped = 0
    if args.resume:
        existing = get_existing_codes(conn)
        skipped = sum(1 for s in stocks if s[0] in existing)
        stocks = [(c, n, m) for c, n, m in stocks if c not in existing]

    actual = len(stocks)
    print(f"共 {total} 只，跳过已有 {skipped}，待处理 {actual}，并发 {args.workers} 线程")

    if actual == 0:
        with conn.cursor() as c:
            c.execute("SELECT COUNT(*) n FROM stock_dividend")
            n = c.fetchone()['n']
            c.execute("SELECT COUNT(DISTINCT code) n FROM stock_dividend")
            nc = c.fetchone()['n']
            c.execute("SELECT MIN(ex_dividend_date) d1, MAX(ex_dividend_date) d2 FROM stock_dividend")
            dr = c.fetchone()
        print(f"已有 {n} 条，覆盖 {nc} 只股票", f"范围 {dr['d1']}~{dr['d2']}" if dr['d1'] else '')
        conn.close()
        return

    # 多线程采集 + 批量写入
    processed = 0
    errors = 0
    no_data_count = 0   # 三个源都无分红的股票数
    total_records = 0
    total_saved = 0
    cninfo_count = 0  # 巨潮数据源命中数
    ths_count = 0     # 同花顺备用源命中数
    em_count = 0      # 东方财富备用源命中数
    lock = threading.Lock()
    pending_records = []  # 待写入缓冲
    t0 = time.time()
    batch_size = 50  # 每50只股票写入一次
    log_interval = max(10, min(50, actual // 10))  # 动态日志间隔：最少10只，最多50只

    def flush_buffer():
        """批量写入缓冲区"""
        nonlocal pending_records, total_saved
        if pending_records:
            rows = save_batch(conn, pending_records)
            total_saved += rows
            pending_records.clear()

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {}
        for code, name, market in stocks:
            f = pool.submit(fetch_with_timeout, code, name, mcode)
            futures[f] = (code, name)

        for f in as_completed(futures):
            code, name = futures[f]
            records, source, err, no_data = f.result()

            with lock:
                processed += 1
                if source == 'cninfo':
                    cninfo_count += 1
                elif source == 'ths':
                    ths_count += 1
                elif source == 'em':
                    em_count += 1
                if err:
                    errors += 1
                    print(f"  [ERR] {code} {name}: {err}", file=sys.stderr)
                elif no_data:
                    no_data_count += 1
                total_records += len(records)
                pending_records.extend(records)

                # 定期写入数据库
                if processed % batch_size == 0:
                    flush_buffer()

                # 进度日志：动态间隔 + 首只 + 末只
                if processed % log_interval == 0 or processed == 1 or processed == actual:
                    flush_buffer()
                    elapsed = time.time() - t0
                    speed = processed / elapsed if elapsed > 0 else 0
                    remaining = (actual - processed) / speed if speed > 0 else 0
                    print(f"[{processed}/{actual}] ({processed * 100 // actual}%) "
                          f"已采集 {total_records} 条（巨潮{cninfo_count}/同花顺{ths_count}/东财{em_count}）"
                          f" | 未分红 {no_data_count} 只"
                          f" | 速度 {speed:.1f} 只/秒 | "
                          f"预计剩余 {remaining / 60:.1f} 分钟")

    # 写入剩余
    flush_buffer()

    elapsed = time.time() - t0
    speed = processed / elapsed if elapsed > 0 else 0

    with conn.cursor() as c:
        c.execute("SELECT COUNT(*) n FROM stock_dividend")
        n = c.fetchone()['n']
        c.execute("SELECT COUNT(DISTINCT code) n FROM stock_dividend")
        nc = c.fetchone()['n']
        c.execute("SELECT MIN(ex_dividend_date) d1, MAX(ex_dividend_date) d2 FROM stock_dividend")
        dr = c.fetchone()
    conn.close()

    print(f"\n===== 完成 =====")
    print(f"处理: {processed}/{actual}（总{total}，跳过{skipped}）")
    print(f"本次采集: {total_records} 条，未分红: {no_data_count} 只，接口错误: {errors} 只")
    print(f"数据源: 巨潮{cninfo_count}只 / 同花顺{ths_count}只 / 东财{em_count}只")
    print(f"数据库: {n} 条，覆盖 {nc} 只股票")
    if dr['d1']:
        print(f"范围: {dr['d1']} ~ {dr['d2']}")
    print(f"耗时: {elapsed:.0f} 秒（{speed:.1f} 只/秒）")


if __name__ == '__main__':
    main()
