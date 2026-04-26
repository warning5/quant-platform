#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
complete_all.py
================
全量字段补全入口脚本。

执行步骤:
1. SQL 补全 change 字段（全量，快速）
2. Baostock 历史补全 PE/PB（全量，约 10-20 分钟）
3. akshare 并发补全市值字段到 stock_info（全量，约 5-10 分钟）
4. 显示数据状态统计

用法:
    python complete_all.py
"""

import time
import sys
import os

# 确保脚本目录在 sys.path（可被 import）
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from db_helper import StockDailyDB
from field_completer import complete_fields
import akshare as ak
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime


# ─── 市值字段补全（akshare → stock_info.total_market_cap）────────

MARKET_PREFIX_AK = {"SH": "sh", "SZ": "sz", "BJ": "bj"}
MAX_WORKERS = 30


def _fetch_market_cap_akshare(code, market):
    """
    通过 akshare 获取单只股票的市值（亿元）。
    返回: (code, total_market_cap) 或 None
    """
    try:
        prefix = MARKET_PREFIX_AK.get(market, "sz")
        sym = f"{prefix}{code}"
        df = ak.stock_zh_a_spot_em()
        row = df[df["代码"] == code]
        if row.empty:
            return None
        total_cap = row.iloc[0].get("总市值")
        if total_cap is None or (isinstance(total_cap, float) and total_cap <= 0):
            return None
        return (code, float(total_cap))
    except Exception:
        return None


def _batch_update_stock_info_market_cap(db, code_caps, batch_size=200):
    """
    批量更新 stock_info.total_market_cap。
    返回: 更新的记录数
    """
    total_updated = 0
    for i in range(0, len(code_caps), batch_size):
        batch = code_caps[i:i + batch_size]
        try:
            if db.backend == "clickhouse":
                for code, cap in batch:
                    db.ch_client.command(
                        "ALTER TABLE stock.stock_info UPDATE total_market_cap = %(cap)s WHERE code = %(code)s",
                        parameters={"cap": cap, "code": code}
                    )
            else:
                placeholders = ", ".join(["(%s, %s)"] * len(batch))
                values = []
                for code, cap in batch:
                    values.extend([code, cap])
                sql = f"INSERT INTO stock_info (code, total_market_cap) VALUES {placeholders} ON DUPLICATE KEY UPDATE total_market_cap = VALUES(total_market_cap)"
                with db.mysql_info_conn.cursor() as cur:
                    cur.execute(sql, values)
                db.mysql_info_conn.commit()
            total_updated += len(batch)
        except Exception as e:
            print(f"    [WARN] 批量更新失败: {e}")
    return total_updated


def complete_market_cap_akshare(db, max_workers=MAX_WORKERS):
    """
    akshare 并发补全全市场股票的总市值字段到 stock_info 表。
    返回: 更新的股票数
    """
    # 获取所有股票
    stocks = db.get_stocks()
    print(f"[市值补全] 共 {len(stocks)} 只股票待处理")

    if not stocks:
        return 0

    # 并发抓取
    results = []
    done = 0
    total = len(stocks)
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {pool.submit(_fetch_market_cap_akshare, code, market): (code, market)
                   for code, name, market in stocks}
        for fut in as_completed(futures):
            done += 1
            val = fut.result()
            if val:
                results.append(val)
            if done % 500 == 0 or done == total:
                pct = done * 100 // total
                print(f"  进度 {done}/{total} ({pct}%) | 已获取 {len(results)} 条市值")

    if not results:
        print("[市值补全] 未获取到任何市值数据")
        return 0

    # 批量写入 stock_info
    updated = _batch_update_stock_info_market_cap(db, results)
    print(f"[市值补全] 完成: 写入 {updated} 条市值到 stock_info")
    return updated


# ─── 主流程 ───────────────────────────────────────────────────

def main():
    t0 = time.time()
    print(f"{'='*60}")
    print(f"全量字段补全  开始  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'='*60}\n")

    with StockDailyDB() as db:
        # ── Step 1: SQL 补全 change 字段 ─────────────────────────
        print("[Step 1] SQL 补全 change 字段（全量）...")
        t1 = time.time()
        n1 = db.complete_change_fields()
        print(f"  → 完成: {n1:,} 条  (耗时 {time.time()-t1:.1f}s)\n")

        # ── Step 2: Baostock 历史补全 PE/PB ─────────────────────
        print("[Step 2] Baostock 历史补全 PE/PB（全量，约 10-20 分钟）...")
        t2 = time.time()
        n2 = complete_fields(db, force_full_scan=True)
        print(f"  → 完成: 累计补全 {n2:,} 条  (耗时 {time.time()-t2:.1f}s)\n")

        # ── Step 3: akshare 并发补全市值字段 ────────────────────
        print("[Step 3] akshare 并发补全市值字段（全量，约 5-10 分钟）...")
        t3 = time.time()
        n3 = complete_market_cap_akshare(db)
        print(f"  → 完成: 更新 {n3:,} 只  (耗时 {time.time()-t3:.1f}s)\n")

        # ── Step 4: 数据状态统计 ────────────────────────────────
        print("[Step 4] 数据状态统计...")
        stats = db.get_daily_stats()
        print(f"  stock_daily 总记录数: {stats['total']:,}")
        print(f"  股票只数: {stats['stocks']:,}")
        print(f"  日期范围: {stats['min_date']} ~ {stats['max_date']}")

        print(f"\n  字段覆盖率（2025-01-01 至今）:")
        cov = db.get_field_coverage(since="2025-01-01")
        print(f"  {'字段':<12} {'非空数':>10} {'总数':>10} {'覆盖率':>8}")
        print(f"  {'-'*44}")
        for label, cnt, total in cov:
            pct = cnt / total * 100 if total > 0 else 0
            print(f"  {label:<12} {cnt:>10,} {total:>10,} {pct:>7.1f}%")

    elapsed = time.time() - t0
    print(f"\n{'='*60}")
    print(f"全量字段补全  完成  耗时 {elapsed:.1f}s ({elapsed/60:.1f} 分钟)")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
