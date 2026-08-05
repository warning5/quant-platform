#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
策略真实 track 观察脚本（read-only，不写任何数据）。

用途：建议①落地后，观察实盘/模拟盘真实收益是否达到回测基线。
      等 DAILY_RECOMMENDATION 每日自动生成 + RECOMMENDATION_TRACK 回填
      next_day_excess_return 后，每天跑一次即可看积累。

用法：
  python monitor_track.py [strategy_id] [since_date]
  python monitor_track.py 73 2026-07-27

说明：只统计 next_day_excess_return 非空的真实 track 记录（重放数据该字段为空，
      因此本脚本天然只看"试点期后真实生成"的数据，不会混入回放）。
"""
import os
import pymysql
import sys

STRATEGY = int(sys.argv[1]) if len(sys.argv) > 1 else 73
SINCE = sys.argv[2] if len(sys.argv) > 2 else "2026-07-27"

# 建议①(ICW按regime分别算IC + ES白名单SIDEWAYS守卫) 回测基线，对照用
BASE = {
    "ALL": (0.23, 52.8),
    "BULL": (0.76, 58.4),
    "SIDEWAYS": (0.15, 51.6),
    "BEAR": (0.21, 58.1),
    ">=0.70": (0.57, 56.6),
    "<0.70": (-0.29, 46.3),
}


def stat(subset):
    vals = [r[3] for r in subset if r[3] is not None]
    if not vals:
        return None
    vals.sort()
    n = len(vals)
    med = vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2
    win = sum(1 for v in vals if v > 0)
    return n, med, win / n * 100.0


def main():
    conn = pymysql.connect(host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
                           user=os.environ.get("MYSQL_USER", "root"),
                           password=os.environ.get("MYSQL_PASSWORD", "123456"),
                           database=os.environ.get("MYSQL_DATABASE", "stock"),
                           charset="utf8mb4")
    try:
        cur = conn.cursor()
        cur.execute(
            """SELECT recommend_date, regime, final_score, next_day_excess_return
               FROM stock_recommendation
               WHERE strategy_id=%s AND weight_mode='ICW'
                 AND recommend_date >= %s
                 AND next_day_excess_return IS NOT NULL
               ORDER BY recommend_date""",
            (STRATEGY, SINCE),
        )
        rows = cur.fetchall()
    finally:
        conn.close()

    if not rows:
        print(f"[策略{STRATEGY}] 自 {SINCE} 暂无真实 track 数据(next_day_excess_return 非空)。")
        print("  等交易日 DAILY_RECOMMENDATION 触发 + RECOMMENDATION_TRACK 回填后再跑本脚本。")
        return

    by_regime = {}
    by_score = {"\u22650.70": [], "<0.70": []}
    for r in rows:
        by_regime.setdefault(r[1], []).append(r)
        by_score["\u22650.70" if r[2] >= 0.70 else "<0.70"].append(r)

    print(f"=== 策略{STRATEGY} 真实 track 观察 (since {SINCE}, n={len(rows)}) ===")
    for key in ["ALL", "BULL", "SIDEWAYS", "BEAR", "\u22650.70", "<0.70"]:
        if key == "ALL":
            subset = rows
        elif key in ("\u22650.70", "<0.70"):
            subset = by_score.get(key, [])
        else:
            subset = by_regime.get(key, [])
        s = stat(subset)
        if not s:
            print(f"  {key:9s} n=   0  (无样本)")
            continue
        n, med, wr = s
        base = BASE.get(key)
        flag = ""
        if base:
            ok = (med >= base[0]) and (wr >= base[1])
            flag = "  OK" if ok else "  \u26a0低于基线"
        print(f"  {key:9s} n={n:4d}  日中位={med:+.2f}%  胜率={wr:.1f}%{flag}")
    print("对照基线：ALL+0.23%/52.8%  BULL+0.76%/58.4%  SIDEWAYS+0.15%/51.6%  BEAR+0.21%/58.1%")
    print("回滚阈值：连续3日总体日中位<-0.5%，或SIDEWAYS连续5日为负，或高置信胜率<50% → 回滚到④+③。")


if __name__ == "__main__":
    main()
