#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
complete_all.py
===============
全量字段补全脚本。

依次执行：
1. SQL 补全 change 字段（全量，~12s）
2. Baostock 补全 PE/PB（全量，~10-20 分钟）
3. akshare 补全市值到 stock_info（全量，~5-10 分钟）
4. 显示数据状态统计

修复说明（2026-04-30）：
- 原版每只股票都调一次 ak.stock_zh_a_spot_em()（5490 次！），改为只调一次
- 每步加 try-except 防止崩溃
- 非交易时间 akshare 会失败，属正常现象，不崩溃

修复说明（2026-05-04）：
- 修复 akshare 返回 `nan` 值导致 MySQL 更新失败的问题
- 重建丢失的 complete_all.py 脚本
- 在 db_helper.py 中添加 update_stock_info_market_cap_batch 方法

用法:
    python complete_all.py
"""

import time
import sys
import os
import traceback
import math

# 添加路径以便导入
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from db_helper import StockDailyDB
from field_completer import fix_valuation_by_qq
import akshare as ak

# 环境变量
DB_BACKEND = os.environ.get("DB_BACKEND", "clickhouse")


def step1_complete_change_fields(db):
    """
    Step 1: SQL 补全 change 字段（全量，快速）
    """
    print("\n" + "=" * 60)
    print("Step 1: SQL 补全 change 字段（全量）")
    print("=" * 60)
    t0 = time.time()
    try:
        n = db.complete_change_fields()
        elapsed = time.time() - t0
        print(f"  ✓ 补全 {n:,} 条")
        print(f"  耗时: {elapsed:.1f}s")
        return n
    except Exception as e:
        elapsed = time.time() - t0
        print(f"  ✗ 失败: {e}")
        traceback.print_exc()
        print(f"  耗时: {elapsed:.1f}s")
        return 0


def step2_complete_pe_pb(db):
    """
    Step 2: Baostock 补全 PE/PB（全量）
    """
    print("\n" + "=" * 60)
    print("Step 2: Baostock 补全 PE/PB（全量）")
    print("=" * 60)
    t0 = time.time()
    try:
        n = fix_valuation_by_qq(db, codes=None)
        elapsed = time.time() - t0
        print(f"  ✓ 补全 {n:,} 条")
        print(f"  耗时: {elapsed:.1f}s")
        return n
    except Exception as e:
        elapsed = time.time() - t0
        print(f"  ✗ 失败: {e}")
        traceback.print_exc()
        print(f"  耗时: {elapsed:.1f}s")
        return 0


def step3_update_market_cap(db):
    """
    Step 3: akshare 补全市值到 stock_info（全量）
    只调用一次 stock_zh_a_spot_em()，然后批量更新
    """
    print("\n" + "=" * 60)
    print("Step 3: akshare 补全市值到 stock_info（全量）")
    print("=" * 60)
    t0 = time.time()

    try:
        # 只调用一次 akshare API
        print("  正在调用 akshare stock_zh_a_spot_em ...")
        df = ak.stock_zh_a_spot_em()
        elapsed_fetch = time.time() - t0
        print(f"  获取到 {len(df)} 只股票的数据 (耗时 {elapsed_fetch:.1f}s)")

        # 解析 akshare 数据
        # stock_zh_a_spot_em 返回的列包括：
        # 代码, 名称, 最新价, 涨跌幅, 涨跌额, 成交量, 成交额, 振幅,
        # 最高, 最低, 今开, 昨收, 换手率, 市盈率, 市净率,
        # 总市值, 流通市值, 涨速, 5分钟涨跌, 60日涨跌幅, 年初至今涨跌幅

        # 检查必要的列是否存在
        required_cols = ['代码', '总市值']
        missing_cols = [c for c in required_cols if c not in df.columns]
        if missing_cols:
            print(f"  ✗ 数据缺少必要列: {missing_cols}")
            print(f"  可用列: {list(df.columns)}")
            return 0

        # 构建更新数据
        updates = []
        for _, row in df.iterrows():
            code = str(row['代码']).zfill(6)  # 确保6位代码
            total_market_cap = row['总市值']

            # 转换为浮点数，处理 nan 值
            try:
                if total_market_cap is not None and total_market_cap != '-':
                    total_market_cap = float(total_market_cap)
                    # 检查是否为 nan（MySQL 不支持 nan）
                    if math.isnan(total_market_cap):
                        total_market_cap = None
                else:
                    total_market_cap = None
            except (ValueError, TypeError):
                total_market_cap = None

            if total_market_cap is not None:
                updates.append((total_market_cap, code))

        print(f"  解析到 {len(updates)} 只股票的市值数据")

        # 批量更新到 stock_info 表
        if updates:
            t1 = time.time()
            n = db.update_stock_info_market_cap_batch(updates)
            elapsed_update = time.time() - t1
            print(f"  ✓ 更新 {n:,} 条到 stock_info")
            print(f"  更新耗时: {elapsed_update:.1f}s")

        elapsed = time.time() - t0
        print(f"  总耗时: {elapsed:.1f}s")
        return len(updates)

    except Exception as e:
        elapsed = time.time() - t0
        print(f"  ✗ 失败: {e}")
        print(f"  非交易时间可能会失败，属正常现象")
        traceback.print_exc()
        print(f"  耗时: {elapsed:.1f}s")
        return 0


def step4_show_stats(db):
    """
    Step 4: 显示数据状态统计
    """
    print("\n" + "=" * 60)
    print("Step 4: 数据状态统计")
    print("=" * 60)

    # 基本统计
    stats = db.get_daily_stats()
    print(f"  stock_daily 总条数: {stats['total']:,}")
    print(f"  股票数量: {stats['stocks']:,}")
    print(f"  日期范围: {stats['min_date']} ~ {stats['max_date']}")

    # 字段覆盖率（2025-01-01 至今）
    since = "2025-01-01"
    print(f"\n  字段覆盖率（{since} 至今）:")
    coverage = db.get_field_coverage(since=since)
    for label, cnt, total in coverage:
        pct = (cnt / total * 100) if total > 0 else 0
        print(f"    {label:>8}: {pct:>6.1f}% ({cnt:,}/{total:,})")


def main():
    print("=" * 60)
    print("全量字段补全脚本")
    print("=" * 60)
    print(f"  DB_BACKEND: {DB_BACKEND}")
    print(f"  开始时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")

    db = StockDailyDB()
    t0 = time.time()

    try:
        # Step 1: SQL 补全 change 字段
        n1 = step1_complete_change_fields(db)

        # Step 2: Baostock 补全 PE/PB
        n2 = step2_complete_pe_pb(db)

        # Step 3: akshare 补全市值到 stock_info
        n3 = step3_update_market_cap(db)

        # Step 4: 显示数据状态统计
        step4_show_stats(db)

    except KeyboardInterrupt:
        print("\n\n用户中断")
    except Exception as e:
        print(f"\n\n✗ 未预期的错误: {e}")
        traceback.print_exc()
    finally:
        db.close()

    elapsed = time.time() - t0
    print("\n" + "=" * 60)
    print(f"全部完成！总耗时: {elapsed:.1f}s ({elapsed/60:.1f}min)")
    print(f"结束时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 60)


if __name__ == "__main__":
    main()
