"""
补算缠论因子(CHAN_*)的 rank_value 和 z_score
对已有的 factor_val 做横截面归一化，通过 INSERT 覆盖写入

用法:
  python scripts/fix_chan_factors_rank.py              # 全部5个缠论因子
  python scripts/fix_chan_factors_rank.py --dry-run     # 只统计不写入
"""

import argparse
import math
import sys
from collections import defaultdict

import clickhouse_connect

CH_CONFIG = {
    "host": "localhost",
    "port": 8123,
    "username": "default",
    "password": "123456",
    "database": "stock",
}

CHAN_FACTORS = [
    "CHAN_PEN_DIR",
    "CHAN_TREND",
    "CHAN_BUY_SELL",
    "CHAN_HUB_POS",
    "CHAN_PEN_COUNT",
]


def main():
    parser = argparse.ArgumentParser(description="补算缠论因子 rank_value / z_score")
    parser.add_argument("--dry-run", action="store_true", help="只统计，不写入")
    parser.add_argument("--factors", nargs="+", help="指定因子，默认全部5个")
    args = parser.parse_args()

    factors = args.factors if args.factors else CHAN_FACTORS
    for f in factors:
        if f not in CHAN_FACTORS:
            print(f"未知因子: {f}，跳过")
            factors.remove(f)

    ch = clickhouse_connect.get_client(**CH_CONFIG)
    try:
        for fc in factors:
            process_factor(ch, fc, dry_run=args.dry_run)
    finally:
        ch.close()


def process_factor(ch, factor_code, dry_run=False):
    print(f"\n{'='*60}")
    print(f"处理: {factor_code}")
    print(f"{'='*60}")

    # 读取全部数据（factor_val 非空的）
    print("  [1/3] 读取 factor_val 数据...")
    r = ch.query(
        f"SELECT symbol, calc_date, factor_val "
        f"FROM stock.factor_value FINAL "
        f"WHERE factor_code = '{factor_code}' "
        f"  AND factor_val IS NOT NULL "
        f"ORDER BY calc_date"
    )

    if not r.result_rows:
        print(f"  无数据，跳过")
        return

    # 按日期分组
    date_groups = defaultdict(list)
    total_rows = 0
    for symbol, calc_date, factor_val in r.result_rows:
        date_groups[str(calc_date)].append((symbol, calc_date, factor_val))
        total_rows += 1

    print(f"  总记录: {total_rows:,}, 日期数: {len(date_groups):,}")

    # 横截面归一化
    print("  [2/3] 横截面归一化 (Z-Score + 百分位)...")
    batch = []
    stats = {"dates": len(date_groups), "null_count": 0, "written": 0}

    for td in sorted(date_groups.keys()):
        items = date_groups[td]
        raw_vals = [v for _, _, v in items]

        # 只有1条数据的截面无法归一化
        if len(raw_vals) <= 1:
            for symbol, calc_date, factor_val in items:
                # 单条截面给 0.5（中间值）
                batch.append((factor_code, symbol, calc_date, round(factor_val, 8), 0.5, 0.0))
            continue

        mean = sum(raw_vals) / len(raw_vals)
        variance = sum((v - mean) ** 2 for v in raw_vals) / len(raw_vals)
        std = math.sqrt(variance) if variance > 0 else 0
        sorted_vals = sorted(raw_vals)

        for symbol, calc_date, factor_val in items:
            z = 0.0 if std == 0 else (factor_val - mean) / std
            rank = sorted_vals.index(factor_val)
            pct = rank / (len(sorted_vals) - 1)

            batch.append((factor_code, symbol, calc_date, round(factor_val, 8), round(pct, 6), round(z, 6)))

    print(f"  归一化完成: {len(batch):,} 条")

    # 写入（或 dry-run）
    if dry_run:
        print(f"  [DRY-RUN] 跳过写入")
        # 打印几个样本
        print(f"  样本 (前5条):")
        for row in batch[:5]:
            print(f"    {row[0]} | {row[1]} | {row[2]} | fv={row[3]} | rank={row[4]} | z={row[5]}")
        return

    print(f"  [3/3] 写入 ClickHouse (ReplacingMergeTree 覆盖)...")
    batch_size = 5000
    total_written = 0
    for i in range(0, len(batch), batch_size):
        chunk = batch[i:i + batch_size]
        ch.insert("factor_value", chunk,
                  column_names=["factor_code", "symbol", "calc_date", "factor_val", "rank_value", "z_score"])
        total_written += len(chunk)

    print(f"  写入完成: {total_written:,} 条")

    # 验证
    rv = ch.query(
        f"SELECT countIf(rank_value IS NOT NULL) "
        f"FROM stock.factor_value FINAL "
        f"WHERE factor_code = '{factor_code}'"
    )
    not_null_count = rv.result_rows[0][0]
    print(f"  验证: rank_value 非空 {not_null_count:,} 条")


if __name__ == "__main__":
    main()
