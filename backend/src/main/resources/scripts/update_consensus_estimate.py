#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_consensus_estimate.py
============================
从同花顺采集一致预期数据（预测年报净利润），存入 MySQL stock_consensus_estimate 表。
供事件驱动策略使用：实际业绩 vs 一致预期 → 超预期/不及预期信号。

数据源: AKShare → stock_profit_forecast_ths（同花顺一致预期）
采集频率: 每周一次（周频足够，一致预期变化较慢）

用法:
    python update_consensus_estimate.py           # 全量采集所有股票
    python update_consensus_estimate.py --top 300  # 只采集沪深300
    python update_consensus_estimate.py --code 600519  # 单只股票
"""

import argparse
import sys
import time
import threading
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

import akshare as ak
import pymysql

# ─── 配置 ────────────────────────────────────────────────────
sys.path.insert(0, ".")
from db_config import MYSQL_CONFIG

BATCH_DELAY = 0.2  # 每只股票间隔（秒），避免限流
MAX_RETRIES = 2
WORKERS = 2  # 并发线程数（降低以避免MySQL deadlock）
DEADLOCK_RETRIES = 3  # 死锁重试次数
DEADLOCK_WAIT = 0.5  # 死锁重试等待(秒)


def ensure_table(conn):
    """确保 stock_consensus_estimate 表存在"""
    ddl = """
    CREATE TABLE IF NOT EXISTS stock_consensus_estimate (
        id              BIGINT AUTO_INCREMENT PRIMARY KEY,
        code            VARCHAR(10) NOT NULL COMMENT '股票代码(纯代码)',
        forecast_year   INT         NOT NULL COMMENT '预测年度',
        agency_count    INT                  COMMENT '预测机构数',
        estimate_min    DECIMAL(20,4)        COMMENT '预测最小值(亿元)',
        estimate_avg    DECIMAL(20,4)        COMMENT '预测均值(亿元)',
        estimate_max    DECIMAL(20,4)        COMMENT '预测最大值(亿元)',
        industry_avg    DECIMAL(20,4)        COMMENT '行业平均(亿元)',
        update_time     DATETIME    NOT NULL COMMENT '数据更新时间',
        UNIQUE KEY uk_code_year (code, forecast_year),
        KEY idx_code (code),
        KEY idx_update_time (update_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一致预期(同花顺)'
    """
    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()
    print("[OK] 表 stock_consensus_estimate 已就绪")


def fetch_one(code):
    """获取单只股票的一致预期数据"""
    for attempt in range(MAX_RETRIES):
        try:
            df = ak.stock_profit_forecast_ths(symbol=code, indicator="预测年报净利润")
            if df is None or df.empty:
                return None
            return df
        except Exception as e:
            if attempt < MAX_RETRIES - 1:
                time.sleep(1)
            else:
                return None
    return None


def _clean_decimal(val):
    """清洗同花顺缺省占位符 '-' → None（MySQL DECIMAL 列不接受 '-')"""
    if val is None or val == '-' or val == '' or str(val).strip() in ('-', '', 'nan', 'None'):
        return None
    try:
        return float(val)
    except (ValueError, TypeError):
        return None


def upsert_rows(conn, code, df):
    """将一致预期数据写入MySQL（含死锁重试）"""
    now = datetime.now()
    rows = 0
    for attempt in range(DEADLOCK_RETRIES):
        try:
            with conn.cursor() as cur:
                for _, row in df.iterrows():
                    year = int(row.get("年度", 0))
                    if year < 2024:
                        continue
                    agency_count = row.get("预测机构数", 0)
                    est_min = _clean_decimal(row.get("最小值"))
                    est_avg = _clean_decimal(row.get("均值"))
                    est_max = _clean_decimal(row.get("最大值"))
                    ind_avg = _clean_decimal(row.get("行业平均数"))

                    cur.execute("""
                        INSERT INTO stock_consensus_estimate
                            (code, forecast_year, agency_count, estimate_min, estimate_avg, estimate_max, industry_avg, update_time)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                        ON DUPLICATE KEY UPDATE
                            agency_count=VALUES(agency_count),
                            estimate_min=VALUES(estimate_min),
                            estimate_avg=VALUES(estimate_avg),
                            estimate_max=VALUES(estimate_max),
                            industry_avg=VALUES(industry_avg),
                            update_time=VALUES(update_time)
                    """, (code, year, int(agency_count) if agency_count else 0,
                          est_min, est_avg, est_max, ind_avg, now))
                    rows += 1
            conn.commit()
            return rows
        except pymysql.err.OperationalError as e:
            if e.args[0] == 1213 and attempt < DEADLOCK_RETRIES - 1:
                # Deadlock: rollback, wait, retry
                try:
                    conn.rollback()
                except Exception:
                    pass
                time.sleep(DEADLOCK_WAIT * (attempt + 1))
                continue
            raise


def get_stock_list(conn, top_n=None, code=None):
    """获取要采集的股票列表"""
    with conn.cursor() as cur:
        if code:
            cur.execute("SELECT code, name FROM stock_info WHERE code = %s", (code,))
        elif top_n:
            # 按市值排序取 top N
            cur.execute("""
                SELECT code, name FROM stock_info
                WHERE market IN ('SH','SZ') AND total_market_cap > 0
                ORDER BY total_market_cap DESC
                LIMIT %s
            """, (top_n,))
        else:
            cur.execute("""
                SELECT code, name FROM stock_info
                WHERE market IN ('SH','SZ')
                ORDER BY code
            """)
        return cur.fetchall()


def main():
    parser = argparse.ArgumentParser(description="采集一致预期数据")
    parser.add_argument("--top", type=int, help="只采集前N只(按市值)")
    parser.add_argument("--code", type=str, help="只采集指定代码")
    parser.add_argument("--workers", type=int, default=WORKERS, help="并发线程数")
    args = parser.parse_args()

    conn = pymysql.connect(**MYSQL_CONFIG)
    ensure_table(conn)
    conn.close()  #建表后关闭主连接

    stock_list = get_stock_list(pymysql.connect(**MYSQL_CONFIG), top_n=args.top, code=args.code)
    total = len(stock_list)
    print(f"[INFO] 待采集股票数: {total} | 线程数: {args.workers}")

    counters = {"ok": 0, "empty": 0, "error": 0, "done": 0}
    lock = threading.Lock()
    t0 = time.time()

    def worker(code_name):
        code, name = code_name
        t_conn = pymysql.connect(**MYSQL_CONFIG)  # 每线程独立连接
        try:
            df = fetch_one(code)
            if df is not None and not df.empty:
                rows = upsert_rows(t_conn, code, df)
                with lock:
                    counters["ok"] += 1
                return code, name, rows, None
            else:
                with lock:
                    counters["empty"] += 1
                return code, name, 0, "empty"
        except Exception as e:
            with lock:
                counters["error"] += 1
            return code, name, 0, str(e)[:60]
        finally:
            t_conn.close()

    # 提交所有任务，4线程并发即自然限流
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(worker, (code, name)): (code, name) for code, name in stock_list}

        for fut in as_completed(futures):
            code, name, rows, err = fut.result()
            with lock:
                counters["done"] += 1
                local_done = counters["done"]
            if local_done % 100 == 0 or local_done == 1 or local_done == total:
                elapsed = time.time() - t0
                eta = elapsed / local_done * (total - local_done)
                print(f"[{local_done}/{total}] {elapsed:.0f}s ETA {eta:.0f}s | ok={counters['ok']} empty={counters['empty']} err={counters['error']}")
            if err and err != "empty" and counters["error"] <= 10:
                print(f"[ERROR] {code} {name}: {err}")

    elapsed = time.time() - t0
    print(f"\n[DONE] {elapsed:.0f}s | 成功={counters['ok']}, 无数据={counters['empty']}, 失败={counters['error']}")


if __name__ == "__main__":
    main()
