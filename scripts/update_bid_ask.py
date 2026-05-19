#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_bid_ask.py
内外盘比数据采集——东方财富实时行情快照（外盘/内盘）

数据来源: 腾讯证券实时行情 (qt.gtimg.cn)
字段: 6=总手, 7=外盘, 8=内盘, 3=最新价, 38=换手率(%), 31=涨跌幅(%), 57=成交额(万元)
存储到: stock_bid_ask 表

外盘(主动买盘): 以卖出价成交的成交量 → 推动上涨
内盘(主动卖盘): 以买入价成交的成交量 → 推动下跌
内外盘比 = 外盘 / 内盘
  > 1: 买方强势（外盘为主动买）
  = 1: 多空平衡
  < 1: 卖方强势

用法:
  python update_bid_ask.py                          # 增量：只更新有数据的股票
  python update_bid_ask.py --all                  # 全量：清空后重刷（慎用，耗时很长）
  python update_bid_ask.py --code 600519          # 单只测试
  python update_bid_ask.py --date 2026-05-15     # 补历史某日（仅当天快照可用）
  python update_bid_ask.py --check                # 仅打印统计，不写库
"""
import sys
import os
import time
import argparse
import requests
import pymysql
import signal
from datetime import datetime, date
from concurrent.futures import ThreadPoolExecutor, as_completed

# ─── SIGINT/SIGTERM 处理 ──────────────────────────────────────────
_EXIT_REQUESTED = False

def _sig_handler(signum, frame):
    global _EXIT_REQUESTED
    _EXIT_REQUESTED = True
    print("\n[中断] 收到终止信号，正在退出...")

for _sig in (signal.SIGINT, signal.SIGTERM):
    try:
        signal.signal(_sig, _sig_handler)
    except (ValueError, OSError):
        pass

os.environ["DISABLE_TQDM"] = "1"  # 禁用 tqdm 进度条

sys.path.insert(0, os.path.dirname(__file__))
from db_config import MYSQL_CONFIG

# ── 配置 ─────────────────────────────────────────────────────
MAX_WORKERS = 8        # 并发线程数（批量模式下每线程处理一批，8线程足够）
BATCH_SIZE = 60        # 腾讯接口每批最多约60-80只，保守取60
CHECK_MODE = False     # --check 时为 True
# ─────────────────────────────────────────────────────────────

# 数据源: 腾讯证券实时行情接口 (qt.gtimg.cn)
# 字段映射: 3=最新价, 6=总手, 7=外盘(主动买盘), 8=内盘(主动卖盘),
#           31=涨跌幅(%), 38=换手率(%), 57=成交额(万元)
# 格式: https://qt.gtimg.cn/q=sh600519 → v_sh600519="1~贵州茅台~600519~1320.92~..."
QQ_MARKET_MAP = {
    "sh": "sh",  # 上海
    "sz": "sz",  # 深圳
    "bj": "bj",  # 北京
}


import re  # 提到顶部


def _market_for_code(code):
    """根据代码判断交易所前缀"""
    if code.startswith("6"):
        return "sh"
    elif code.startswith(("0", "3")):
        return "sz"
    elif code.startswith(("4", "8", "9")):
        return "bj"
    return None


def _fetch_qq_quote(code):
    """从腾讯证券接口获取单只股票的内外盘数据（兼容旧调用）"""
    result = _fetch_qq_quote_batch([code])
    return result.get(code)


def _fetch_qq_quote_batch(codes):
    """
    从腾讯证券接口批量获取内外盘数据。
    腾讯 qt.gtimg.cn 支持批量查询，格式: q=sh600519,sz000001,...
    实测 URL 长度限制约 60 只/批，超过会截断或报错。
    返回: {code: {外盘/内盘/最新/...}, ...}
    """
    if not codes:
        return {}

    # 构建批量查询字符串
    parts = []
    valid_codes = []
    for c in codes:
        m = _market_for_code(c)
        if m:
            parts.append(f"{m}{c}")
            valid_codes.append(c)
    if not parts:
        return {}

    # 注意：qt.gtimg.cn 用 HTTP 即可，HTTPS 在 Windows 下 SSL 握手极慢（~7s）
    url = "http://qt.gtimg.cn/q=" + ",".join(parts)
    headers = {"User-Agent": "Mozilla/5.0"}
    r = requests.get(url, headers=headers, timeout=5)
    r.raise_for_status()

    try:
        text = r.content.decode("gbk")
    except Exception:
        text = r.text

    def safe_int(v):
        try:
            return int(float(v)) if v else None
        except (ValueError, TypeError):
            return None

    def safe_float(v):
        try:
            return float(v) if v else None
        except (ValueError, TypeError):
            return None

    results = {}
    for line in text.split(";"):
        line = line.strip()
        if not line:
            continue
        m = re.search(r'v_(\w+)="([^"]*)"', line)
        if not m:
            continue
        sym = m.group(1)  # e.g. "sh600519"
        raw = m.group(2)
        fields = raw.split("~")
        if len(fields) < 10:
            continue
        # 提取代码（去掉市场前缀）
        code_key = sym.replace("sh", "").replace("sz", "").replace("bj", "")
        results[code_key] = {
            "外盘": safe_int(fields[7]) if len(fields) > 7 else None,
            "内盘": safe_int(fields[8]) if len(fields) > 8 else None,
            "最新": safe_float(fields[3]) if len(fields) > 3 else None,
            "总手": safe_int(fields[6]) if len(fields) > 6 else None,
            "金额": safe_float(fields[57]) if len(fields) > 57 else None,
            "换手": safe_float(fields[38]) if len(fields) > 38 else None,
            "涨幅": safe_float(fields[31]) if len(fields) > 31 else None,
        }
    return results


def get_mysql_conn():
    cfg = MYSQL_CONFIG.copy()
    cfg["charset"] = "utf8mb4"
    cfg["autocommit"] = True
    return pymysql.connect(**cfg)


def ensure_table(conn):
    """建表：若无则创建"""
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS stock_bid_ask (
            id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            code        VARCHAR(6) NOT NULL COMMENT '股票代码（6位）',
            trade_date  DATE NOT NULL COMMENT '交易日期',
            outer_vol   BIGINT COMMENT '外盘量（主动买盘成交量）',
            inner_vol   BIGINT COMMENT '内盘量（主动卖盘成交量）',
            ratio       DECIMAL(10,4) COMMENT '内外盘比（外盘/内盘）',
            latest_price DECIMAL(10,2) COMMENT '最新价',
            total_vol   BIGINT COMMENT '总手',
            amount      DECIMAL(20,2) COMMENT '成交额（元）',
            turnover_rate DECIMAL(10,4) COMMENT '换手率（%）',
            change_pct  DECIMAL(10,2) COMMENT '涨跌幅（%）',
            fetched_at   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '数据获取时间',
            UNIQUE KEY uk_code_date (code, trade_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内外盘比数据（腾讯证券实时行情快照）'
    """)
    cur.close()


def upsert_bid_ask(conn, code, trade_date, data):
    """Upsert一只股票的内外盘数据"""
    if data.get('fetched') is not True:
        return False
    outer = data.get('outer_vol')
    inner = data.get('inner_vol')
    ratio = None
    if outer and inner and inner > 0:
        ratio = round(outer / inner, 4)

    amount_raw = data.get('amount')
    amount_val = round(amount_raw * 10000, 2) if amount_raw else None

    cur = conn.cursor()
    cur.execute("""
        INSERT INTO stock_bid_ask
            (code, trade_date, outer_vol, inner_vol, ratio,
             latest_price, total_vol, amount, turnover_rate, change_pct)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            outer_vol    = VALUES(outer_vol),
            inner_vol    = VALUES(inner_vol),
            ratio        = VALUES(ratio),
            latest_price = VALUES(latest_price),
            total_vol    = VALUES(total_vol),
            amount       = VALUES(amount),
            turnover_rate = VALUES(turnover_rate),
            change_pct   = VALUES(change_pct),
            fetched_at   = CURRENT_TIMESTAMP
    """, (
        code,
        trade_date,
        outer,
        inner,
        ratio,
        data.get('latest_price'),
        data.get('total_vol'),
        amount_val,
        data.get('turnover_rate'),
        data.get('change_pct'),
    ))
    cur.close()
    return True


def fetch_one(code, retries=2, delay=1.0):
    """采集单只股票的内外盘数据（含重试）"""
    result = {'fetched': False, 'code': code}
    last_error = None

    for attempt in range(retries):
        try:
            kv = _fetch_qq_quote(str(code).zfill(6))
            if kv is None:
                return result

            outer = kv.get('外盘')
            inner = kv.get('内盘')

            result = {
                'fetched': True,
                'outer_vol': outer,
                'inner_vol': inner,
                'latest_price': kv.get('最新'),
                'total_vol': kv.get('总手'),
                'amount': kv.get('金额'),  # 万元 → 存入时转为元
                'turnover_rate': kv.get('换手'),
                'change_pct': kv.get('涨幅'),
            }
            return result

        except Exception as e:
            last_error = e
            if attempt < retries - 1:
                time.sleep(delay)
    return result


def get_active_codes(conn):
    """从 stock_info 获取有交易的股票代码（排除ST/退市）"""
    cur = conn.cursor()
    cur.execute("""
        SELECT code FROM stock_info
        WHERE LENGTH(code) = 6
          AND (name IS NOT NULL AND name NOT LIKE '%退%' AND name NOT LIKE '%ST%')
        ORDER BY total_market_cap DESC
    """)
    codes = [r[0] for r in cur.fetchall()]
    cur.close()
    return codes


def _market_tag(code):
    """返回市场标签"""
    if code.startswith("6"):
        return "SH"
    elif code.startswith(("0", "3")):
        return "SZ"
    elif code.startswith(("4", "8", "9")):
        return "BJ"
    return "OTHER"


def batch_update(codes, trade_date, conn, check_only=False):
    """批量采集并写入（批量请求模式：每批60只，8线程并发）"""
    total = len(codes)
    success = 0
    failed = 0
    processed = 0

    # 按市场统计
    mkt_total = {"SH": 0, "SZ": 0, "BJ": 0, "OTHER": 0}
    mkt_success = {"SH": 0, "SZ": 0, "BJ": 0, "OTHER": 0}
    for c in codes:
        mkt_total[_market_tag(c)] += 1

    import threading
    lock = threading.Lock()

    # 将股票代码分批次
    batches = []
    for i in range(0, total, BATCH_SIZE):
        batches.append(codes[i:i + BATCH_SIZE])

    def process_batch(batch_codes):
        nonlocal success, failed, processed
        if _EXIT_REQUESTED:
            with lock:
                failed += len(batch_codes)
                processed += len(batch_codes)
            return

        # 1) 批量请求腾讯接口（一次HTTP请求获取整批数据）
        try:
            batch_data = _fetch_qq_quote_batch(batch_codes)
        except Exception as e:
            # 整批失败，逐只记为失败
            with lock:
                failed += len(batch_codes)
                processed += len(batch_codes)
            return

        # 2) 写入数据库（每个线程独立连接）
        thread_conn = None
        if not check_only:
            try:
                thread_conn = get_mysql_conn()
            except Exception:
                thread_conn = None

        batch_success = 0
        batch_failed = 0
        batch_mkt_success = {"SH": 0, "SZ": 0, "BJ": 0, "OTHER": 0}
        for code in batch_codes:
            if _EXIT_REQUESTED:
                batch_failed += 1
                continue
            kv = batch_data.get(code)
            if kv is None:
                batch_failed += 1
                continue

            outer = kv.get('外盘')
            inner = kv.get('内盘')
            data = {
                'fetched': True,
                'outer_vol': outer,
                'inner_vol': inner,
                'latest_price': kv.get('最新'),
                'total_vol': kv.get('总手'),
                'amount': kv.get('金额'),
                'turnover_rate': kv.get('换手'),
                'change_pct': kv.get('涨幅'),
            }

            if not check_only and thread_conn:
                try:
                    upsert_bid_ask(thread_conn, code, trade_date, data)
                    batch_success += 1
                    batch_mkt_success[_market_tag(code)] += 1
                except Exception:
                    batch_failed += 1
            else:
                batch_success += 1
                batch_mkt_success[_market_tag(code)] += 1

        if thread_conn:
            thread_conn.close()

        with lock:
            success += batch_success
            failed += batch_failed
            processed += len(batch_codes)
            for m in mkt_success:
                mkt_success[m] += batch_mkt_success[m]
            p = processed
            s = success
            f = failed
        # 每处理到整百只打印一次进度
        if p % 100 < BATCH_SIZE or p == total:
            print(f"[{p}/{total}] 成功:{s} 失败:{f}")

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(process_batch, batch): i for i, batch in enumerate(batches)}
        for future in as_completed(futures):
            if _EXIT_REQUESTED:
                print("\n[中断] 正在停止所有线程...")
                executor.shutdown(wait=False, cancel_futures=True)
                break
            try:
                future.result()
            except Exception as e:
                with lock:
                    failed += 1

    return success, failed, mkt_total, mkt_success


def main():
    parser = argparse.ArgumentParser(description='内外盘比数据采集')
    parser.add_argument('--all', action='store_true', help='全量重刷（慎用）')
    parser.add_argument('--code', type=str, help='单只股票代码测试')
    parser.add_argument('--date', type=str, help='指定日期 YYYY-MM-DD（仅快照可用）')
    parser.add_argument('--check', action='store_true', help='仅打印统计，不写库')
    parser.add_argument('--limit', type=int, default=0, help='限制股票数量（测试用）')
    args = parser.parse_args()

    global CHECK_MODE
    CHECK_MODE = args.check

    trade_date = datetime.now().date()
    if args.date:
        try:
            trade_date = datetime.strptime(args.date, '%Y-%m-%d').date()
        except ValueError:
            print(f"[ERROR] 日期格式错误: {args.date}，应为 YYYY-MM-DD")
            sys.exit(1)

    print(f"=== 内外盘比数据采集 ===")
    print(f"  目标日期: {trade_date}")
    print(f"  模式: {'仅检查' if CHECK_MODE else '写入数据库'}")

    conn = get_mysql_conn()
    ensure_table(conn)

    if args.code:
        # 单只测试
        print(f"\n[单只] {args.code}")
        data = fetch_one(args.code)
        print(f"  数据: {data}")
        if not CHECK_MODE:
            upsert_bid_ask(conn, args.code, trade_date, data)
            print("  已写入")
        return

    codes = get_active_codes(conn)
    truncate = args.all and args.limit == 0  # --limit > 0 时视为测试模式，不清空
    if args.limit > 0:
        codes = codes[:args.limit]
        print(f"\n待采集股票数: {len(codes)}（测试模式，不清空表）")
    else:
        if truncate:
            print("\n[全量模式] 清空 stock_bid_ask 表...")
            cur = conn.cursor()
            cur.execute("TRUNCATE TABLE stock_bid_ask")
            cur.close()
            print("  已清空，开始采集...")
        print(f"\n待采集股票数: {len(codes)}")

    success, failed, mkt_total, mkt_success = batch_update(codes, trade_date, conn, check_only=CHECK_MODE)
    print(f"\n=== 完成 ===")
    print(f"  成功: {success}  失败/无数据: {failed}  总计: {len(codes)}")
    print(f"\n  数据概览:")
    print(f"  {'市场':<6}  {'目标':>6}  {'成功':>6}  {'失败':>6}  {'成功率':>7}")
    print(f"  {'-'*6}  {'-'*6}  {'-'*6}  {'-'*6}  {'-'*7}")
    for mkt in ("SH", "SZ", "BJ"):
        t = mkt_total[mkt]
        s = mkt_success[mkt]
        f = t - s
        pct = f"{100*s/t:.1f}%" if t > 0 else "  N/A"
        print(f"  {mkt:<6}  {t:>6}  {s:>6}  {f:>6}  {pct:>7}")
    conn.close()


if __name__ == '__main__':
    main()
