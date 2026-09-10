#!/usr/env python
# recompute_cyq_range.py - 定点续算重算指定日期段的 CYQ（以真实前日分布为种子，不污染后续日）
#
# 背景: UI「全量」= cyq_service.py --force --daily --start X --end Y, 会从 X 当天单日K线作假种子
#       重算 X..Y, 导致 X 分布失真、Y 在其上续算也被污染。本脚本改用「前一交易日真实分布」作种子,
#       逐日 compute_cyq_continue, 数学上等价于正常增量计算, 绝不污染其他日。
#
# 用法:
#   python recompute_cyq_range.py --start 2026-09-07 --end 2026-09-08
#   python recompute_cyq_range.py --start 2026-09-07 --end 2026-09-08 --dry-run
#   python recompute_cyq_range.py --start 2026-09-07 --end 2026-09-08 --codes 600000,000001
#   python recompute_cyq_range.py --start 2026-09-07 --end 2026-09-08 --limit 50   # 试运行前50只
#
# 注意: 首日的种子取「前一交易日」在 stock_cyq_daily 的 cyq_json；若不存在(如新股)回退250日长窗口全量。
#       ReplacingMergeTree 按 (code, trade_date) 去重, 写回幂等安全。
import os, sys, json, argparse
from datetime import datetime, timedelta
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np
import cyq_core as C

TABLE_D = "stock_cyq_daily"

def ch_query(sql):
    return C.query_ch(sql).strip()

def get_code_list(args):
    if args.codes:
        return [c.strip() for c in args.codes.split(",") if c.strip().isdigit()]
    if getattr(args, "codes_file", None):
        try:
            with open(args.codes_file, encoding="utf-8") as f:
                return [l.strip() for l in f if l.strip().isdigit()]
        except Exception:
            return []
    rows = ch_query(f"SELECT code FROM {C.CH_DB}.stock_info").strip().split("\n")
    return [r.strip() for r in rows if r.strip()]

def read_seed(code, date):
    """读某股票指定交易日的 cyq_json 作续算种子。无则返回 None。

    注意: stock_cyq_daily 是 ReplacingMergeTree, 同一 (code,trade_date) 可能有多个未合并版本,
    必须带 FINAL 取 update_time 最新版, 否则会随机取到旧版本的退化分布作种子。
    """
    raw = ch_query(f"SELECT cyq_json FROM {C.CH_DB}.{TABLE_D} FINAL WHERE code='{code}' AND trade_date='{date}'")
    if not raw:
        return None
    try:
        d = json.loads(raw.split("\n")[0].strip())
        return np.array(d["yrange"], float), np.array(d["x"], float)
    except Exception:
        return None

def prev_trading_day(date_str, max_lookback=15):
    """跳过周末, 返回 date_str 之前最近的一个交易日(yyyy-mm-dd)。"""
    d = datetime.strptime(date_str, "%Y-%m-%d")
    for _ in range(max_lookback):
        d -= timedelta(days=1)
        if d.weekday() >= 5:   # 5=Sat, 6=Sun
            continue
        return d.strftime("%Y-%m-%d")
    return None

def dates_in_range(start, end):
    sd, ed = datetime.strptime(start, "%Y-%m-%d"), datetime.strptime(end, "%Y-%m-%d")
    out, d = [], sd
    while d <= ed:
        out.append(d.strftime("%Y-%m-%d"))
        d += timedelta(days=1)
    return out

def _long_window_snap(code, date):
    """回退: 取 date 前 250 日至 date 的K线, 用 compute_cyq_daily 算到 date 的快照。"""
    sd = (datetime.strptime(date, "%Y-%m-%d") - timedelta(days=250)).strftime("%Y-%m-%d")
    df, _ = C.get_unadj(code, sd, date, adj_fallback=True)
    if df is None or df.empty:
        return None
    df["turn"] = C.normalize_turnover(df["turn"].values)
    kl = list(zip(df["date"], df["open"], df["high"], df["low"], df["close"], df["turn"]))
    all_snaps = C.compute_cyq_daily(kl)
    s = next((x for x in all_snaps if x["date"] == date), None)
    if s is None:
        return None
    return np.array(s["yrange"], float), np.array(s["x"], float)

def recompute_one(code, dates, dry_run=False, force_snap=False):
    """dates: 有序日期列表。逐日续算, 前一日用内存中已算快照/种子作下一日的基。

    force_snap=True: 跳过 read_seed(不读 CH 里可能已退化的旧种子), 首日强制 250 日长窗口全量,
    之后逐日 continue。用于脏数据整段重建。
    """
    first = dates[0]
    prev = prev_trading_day(first)
    seed = None if force_snap else (read_seed(code, prev) if prev else None)
    snaps = []   # {date, yrange, x, close, c70_lo, c70_hi}

    for date in dates:
        try:
            df, _ = C.get_unadj(code, date, date, adj_fallback=True)
        except Exception as e:
            return ("fetch-err", str(e)[:120])
        o = h = l = c = turn = None
        if df is not None and not df.empty:
            df["turn"] = C.normalize_turnover(df["turn"].values)
            kl = list(zip(df["date"], df["open"], df["high"], df["low"], df["close"], df["turn"]))
            o, h, l, c, turn = kl[0][1], kl[0][2], kl[0][3], kl[0][4], kl[0][5]

        if c is None:
            continue          # 非交易日(周末/节假日)或当日停牌 -> 跳过, 不计为失败

        if seed is not None:
            res = C.compute_cyq_continue(seed[0], seed[1], [(o, h, l, c, turn)])
            if res is not None:
                yr, x = res
                m = C.metrics(yr, x, float(c))
                snaps.append({"date": date, "yrange": yr, "x": x, "close": float(c),
                              "c70_lo": m["c70_lo"], "c70_hi": m["c70_hi"]})
                seed = (yr, x)
                continue

        # 无种子 / 续算失败(价格越界) -> 长窗口全量重算到该日
        lw = _long_window_snap(code, date)
        if lw is None:
            return "no-snap", date
        yr, x = lw
        close = float(c)
        m = C.metrics(yr, x, close)
        snaps.append({"date": date, "yrange": yr, "x": x, "close": close,
                      "c70_lo": m["c70_lo"], "c70_hi": m["c70_hi"]})
        seed = (yr, x)

    if not snaps:
        return "no-snaps", None

    # 主力成本(就地附加 main_cost* 字段)
    C.compute_main_cost_for_snaps(snaps, code, dates[0], dates[-1])

    rows = []
    for s in snaps:
        m = C.metrics(s["yrange"], s["x"], s["close"])
        rows.append(dict(
            code=code, trade_date=s["date"], close_price=round(s["close"], 4),
            avg_cost=round(m["avg_cost"], 4), benefit=round(float(m["benefit"]), 6),
            c90_lo=round(m["c90_lo"], 4), c90_hi=round(m["c90_hi"], 4), c90_conc=round(m["c90_conc"], 6),
            c70_lo=round(m["c70_lo"], 4), c70_hi=round(m["c70_hi"], 4), c70_conc=round(m["c70_conc"], 6),
            main_cost=round(s["main_cost"], 4), main_cost_lo=round(s["main_cost_lo"], 4),
            main_cost_hi=round(s["main_cost_hi"], 4), main_cost_conf=s["main_cost_conf"],
            cyq_json=C.cyq_to_json(s["yrange"], s["x"]),
        ))

    if dry_run:
        return ("dry", [(r["trade_date"], r["avg_cost"]) for r in rows])

    try:
        C.write_cyq_ch(rows, table=TABLE_D)
    except Exception as e:
        return ("write-err", str(e)[:150])
    return ("ok", len(rows))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", required=True, help="起点日期 yyyy-mm-dd")
    ap.add_argument("--end", required=True, help="终点日期 yyyy-mm-dd")
    ap.add_argument("--codes", help="逗号分隔代码, 不填则全市场")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="仅前 N 只(试运行)")
    ap.add_argument("--offset", type=int, default=0, help="跳过前 N 只(断点续跑)")
    ap.add_argument("--force-snap", action="store_true", help="首日强制长窗口全量(忽略旧种子, 用于脏数据重建)")
    ap.add_argument("--codes-file", help="从文件读取代码列表(每行一个, 用于批量重建退化股)")
    args = ap.parse_args()

    dates = dates_in_range(args.start, args.end)
    print(f"[配置] 重算日期={dates} dry_run={args.dry_run}", flush=True)
    codes = get_code_list(args)
    if args.offset:
        codes = codes[args.offset:]
    if args.limit:
        codes = codes[:args.limit]
    print(f"[配置] 待处理股票={len(codes)}", flush=True)

    ok = nok = 0
    for i, code in enumerate(codes, 1):
        r = recompute_one(code, dates, dry_run=args.dry_run, force_snap=args.force_snap)
        if isinstance(r, tuple) and r[0] in ("ok", "dry"):
            ok += 1
            if args.dry_run and i <= 10:
                info = ", ".join(f"{d}:avg={a}" for d, a in r[1])
                print(f"  [{code}] {info}")
        else:
            nok += 1
            if i <= 20:
                print(f"  [{code}] 跳过: {r}")
        if i % 200 == 0:
            print(f"  进度 {i}/{len(codes)} ok={ok} nok={nok}", flush=True)
    print(f"完成: ok={ok} nok={nok} / 共 {len(codes)}", flush=True)

if __name__ == "__main__":
    main()
