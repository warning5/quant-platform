#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
财务三大表并发采集 - 重构版 v3

核心优化：
1. requests.Session 复用 TCP 连接，大幅降低连接建立开销
2. 去除 fetch_sina_report 内部 ThreadPoolExecutor（原来每表都创建，白白爆炸）
3. DBUtils 连接池替代每票建连
4. 预批量加载已存在 (code, report_date)，消除 5490 次冗余查询
5. 并发数 15-20，预估耗时 30-50 分钟（vs 原来 164 分钟）

用法：
  python scripts/update_financial_sina_fast.py [--workers 15] [--timeout 10] [--force]
"""
import argparse
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import pandas as pd
import pymysql
import requests
from dbutils.pooled_db import PooledDB
from db_config import MYSQL_CONFIG
FAIL_LOG = os.path.join(os.path.dirname(__file__), '_sina_failed_codes.txt')

sys.path.insert(0, os.path.dirname(__file__))
from update_financial_data import (
    INCOME_MAP, BALANCE_MAP, CASHFLOW_MAP,
    parse_number, report_type_from_date, end_date_from_report,
)

# ── 全局状态 ──────────────────────────────────────────────
_global_existing = set()   # {(code, report_date), ...}
_db_pool = None


def get_db_pool():
    global _db_pool
    if _db_pool is None:
        _db_pool = PooledDB(
            creator=pymysql,
            maxconnections=25,
            blocking=True,
            **MYSQL_CONFIG,
        )
    return _db_pool


# ── 新浪 JSON API ─────────────────────────────────────────
# akshare 内部实现已验证：https://quotes.sina.cn/cn/api/openopoenapi.php/CompanyFinanceService.getFinanceReport2022
_SINA_URL = "https://quotes.sina.cn/cn/api/openapi.php/CompanyFinanceService.getFinanceReport2022"
_SYMBOL_MAP = {
    'income':   ('lrb', '利润表'),
    'balance':  ('fzb', '资产负债表'),
    'cashflow': ('llb', '现金流量表'),
}
_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Referer': 'https://vip.stock.finance.sina.com.cn/',
}


def _new_session():
    """创建带连接池的 requests.Session"""
    s = requests.Session()
    s.headers.update(_HEADERS)
    return s


# 线程局部 session：每线程复用独立 session
import threading
_thread_local = threading.local()


def get_session() -> requests.Session:
    if not hasattr(_thread_local, 'session'):
        _thread_local.session = _new_session()
    return _thread_local.session


def _fetch_sina_table(session: requests.Session, code: str,
                      table_key: str, timeout: int = 10) -> pd.DataFrame | None:
    """
    直接请求新浪 JSON API，解析为 DataFrame。
    返回 DataFrame（含 '报告日' 列），失败返回 None。
    """
    source, _ = _SYMBOL_MAP[table_key]
    params = {
        'paperCode': code,
        'source': source,
        'type': '0',
        'page': '1',
        'num': '100',
    }
    try:
        resp = session.get(_SINA_URL, params=params, timeout=(5.0, timeout))
        resp.raise_for_status()
        data = resp.json()
    except Exception:
        return None

    try:
        # 实际结构：data['result']['data']
        result = data.get('result', {})
        inner = result.get('data', result)  # 兼容两种取法
        if not isinstance(inner, dict):
            return None

        report_dates = inner.get('report_date', [])
        report_list = inner.get('report_list', {})
        if not report_dates:
            return None

        # 构建横向表格：行为报告期，列为财务科目
        rows = []
        for rd_info in report_dates:
            date_str = rd_info.get('date_value', '')
            if not date_str:
                continue
            period_data = report_list.get(date_str, {})
            row = {'报告日': date_str}
            for item in period_data.get('data', []):
                row[item['item_title']] = item['item_value']
            # 元数据
            row['_数据源'] = period_data.get('data_source', '')
            row['_是否审计'] = period_data.get('is_audit', '')
            row['_公告日期'] = period_data.get('publish_date', '')
            rows.append(row)

        df = pd.DataFrame(rows)
        if df.empty:
            return None
        # 去掉重复列（不含元数据列）
        df = df.loc[:, ~df.columns.duplicated(keep='first')]
        return df
    except Exception:
        return None


# ── 单票处理 ──────────────────────────────────────────────
def process_one_stock(args_tuple) -> tuple[str, int, str | None]:
    """拉取单只股票3张表并写入DB。线程安全。"""
    code, force, timeout = args_tuple
    session = get_session()
    conn = get_db_pool().connection()

    try:
        inserted = 0
        for table_key, label, table_name, col_map in [
            ('income',   '利润表',       'stock_income',    INCOME_MAP),
            ('balance',  '资产负债表',   'stock_balance',   BALANCE_MAP),
            ('cashflow', '现金流量表',   'stock_cashflow', CASHFLOW_MAP),
        ]:
            df = None
            for attempt in range(3):
                df = _fetch_sina_table(session, code, table_key, timeout=timeout)
                if df is not None and not df.empty:
                    break
                time.sleep(0.5 * (attempt + 1))

            if df is None or df.empty:
                # 备选：akshare（它有自己的 timeout 处理，但不做重试）
                try:
                    import akshare as ak
                    df = ak.stock_financial_report_sina(
                        stock=code, symbol=label
                    )
                except Exception:
                    df = None

            if df is None or df.empty:
                continue

            n = _save_fast(df, code, table_name, col_map, conn, force)
            inserted += n

        return code, inserted, None
    except Exception as e:
        return code, 0, str(e)
    finally:
        conn.close()


# ── 写入（全局已存在集合加速）─────────────────────────────
def _save_fast(df: pd.DataFrame, code: str, table_name: str,
               col_map: dict, conn, force: bool) -> int:
    """使用预加载的已存在集合跳过已有数据"""
    if df is None or df.empty:
        return 0

    cursor = conn.cursor()
    rows_to_insert = []

    for _, row in df.iterrows():
        raw = str(row.get('报告日', '')).strip()
        rd = raw.replace('-', '').replace('/', '')[:8]
        if len(rd) != 8:
            continue
        if not force and (code, rd) in _global_existing:
            continue

        rt = report_type_from_date(rd)
        ed = end_date_from_report(rd)

        seen = set()
        insert_cols = []
        insert_vals = []
        for src_col, db_field in col_map.items():
            if db_field is None or db_field in seen:
                continue
            if src_col in row.index:
                val = parse_number(row[src_col])
                if val is not None:
                    insert_cols.append(db_field)
                    insert_vals.append(val)
                    seen.add(db_field)

        if not insert_cols:
            continue
        rows_to_insert.append((code, rd, rt, ed, insert_cols, insert_vals))

    if not rows_to_insert:
        return 0

    inserted = 0
    for code_, rd, rt, ed, insert_cols, insert_vals in rows_to_insert:
        sets = ', '.join(f"{c} = VALUES({c})" for c in insert_cols)
        sql = (
            f"INSERT INTO {table_name} "
            f"(code, report_date, report_type, end_date, {', '.join(insert_cols)}) "
            f"VALUES (%s,%s,%s,%s, {', '.join(['%s']*len(insert_cols))}) "
            f"ON DUPLICATE KEY UPDATE {sets}"
        )
        try:
            cursor.execute(sql, [code_, rd, rt, ed] + insert_vals)
            inserted += 1
            _global_existing.add((code_, rd))
        except Exception as e:
            print(f"    ERR {table_name} {code_} {rd}: {e}")

    conn.commit()

    if table_name == 'stock_cashflow' and inserted > 0:
        try:
            cursor.execute("""
                UPDATE stock_cashflow
                SET free_cash_flow = COALESCE(net_operate_cf, 0) + COALESCE(net_invest_cf, 0)
                WHERE code = %s AND free_cash_flow IS NULL
                  AND net_operate_cf IS NOT NULL AND net_invest_cf IS NOT NULL
            """, (code,))
            conn.commit()
        except Exception:
            pass

    return inserted


# ── 预加载 ────────────────────────────────────────────────
def preload_existing():
    print("  预加载已存在记录...")
    conn = pymysql.connect(**MYSQL_CONFIG)
    cursor = conn.cursor()
    total = 0
    for t in ['stock_income', 'stock_balance', 'stock_cashflow']:
        cursor.execute(f"SELECT code, report_date FROM {t}")
        rows = cursor.fetchall()
        _global_existing.update((r[0], r[1]) for r in rows)
        total += len(rows)
    conn.close()
    print(f"  已加载 {total:,} 条，覆盖 {len(_global_existing):,} 个(code,report_date)")


# ── 主流程 ────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description='新浪三大表并发采集')
    parser.add_argument('--start-code', type=str, default=None, help='从指定代码开始')
    parser.add_argument('--workers', type=int, default=15, help='并发线程数（默认15）')
    parser.add_argument('--timeout', type=int, default=10, help='HTTP请求超时秒数（默认10）')
    parser.add_argument('--force', action='store_true', help='强制覆盖已有数据')
    args = parser.parse_args()

    get_db_pool()
    preload_existing()

    if args.force:
        print("  FORCE 模式：忽略已有数据，全量写入")

    conn = pymysql.connect(**MYSQL_CONFIG)
    cursor = conn.cursor()
    cursor.execute("SELECT code FROM stock_info ORDER BY code")
    codes = [r[0] for r in cursor.fetchall()]
    conn.close()

    if args.start_code:
        idx = next((i for i, c in enumerate(codes) if c >= args.start_code), len(codes))
        codes = codes[idx:]
        print(f"  从 {args.start_code} 开始，剩余 {len(codes)} 只")

    print(f"\n待采集: {len(codes)} 只  并发: {args.workers}  HTTP超时: {args.timeout}s")
    start_time = time.time()

    done = failed = total_inserted = 0
    failed_codes = []

    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {
            executor.submit(process_one_stock, (code, args.force, args.timeout)): code
            for code in codes
        }

        for future in as_completed(futures):
            code, n, err = future.result()
            done += 1
            total_inserted += n
            if err:
                failed += 1
                failed_codes.append(code)

            if done % 50 == 0 or done == len(codes):
                elapsed = time.time() - start_time
                speed = done / elapsed if elapsed > 0 else 0
                eta = (len(codes) - done) / speed if speed > 0 else 0
                print(
                    f"  {done}/{len(codes)} ({done/len(codes)*100:.1f}%)"
                    f"  插入 {total_inserted:,} 条  失败 {failed}"
                    f"  {speed:.1f}只/秒  剩余 {eta/60:.0f}分钟"
                )

    elapsed = time.time() - start_time
    print(f"\n完成: {done} 只  {total_inserted:,} 条  失败 {failed}  {elapsed/60:.1f} 分钟")

    if failed_codes:
        with open(FAIL_LOG, 'w') as f:
            f.write('\n'.join(failed_codes))
        print(f"失败股票 {len(failed_codes)} 只 -> {FAIL_LOG}")
    elif os.path.exists(FAIL_LOG):
        os.remove(FAIL_LOG)


if __name__ == '__main__':
    main()
