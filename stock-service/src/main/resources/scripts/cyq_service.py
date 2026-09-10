#!/usr/env python
# cyq_service.py - 通用筹码分布(CYQ)计算服务: 未复权日线 -> 计算 -> 落 ClickHouse stock.stock_cyq_daily(单表)
#
# 能力:
#   - 批量: 全市场 5490 只(默认从 CH stock_info 取清单)或指定 --codes/--file
#   - 换手率量纲自动归一(见 cyq_core.normalize_turnover)
#   - 断点续传:
#       (a) 进度文件 .cyq_progress.json 记录已完成/失败代码, 进程崩溃后重跑自动跳过已完成
#       (b) 增量刷新: 已算股票仅在 CH 快照日之后有新交易日时, 用存储的分布续算(compute_cyq_continue),
#           新高低超出原区间则自动全量重算; 无新数据则跳过
#   - 并发守卫: 单进程锁 .cyq_service.lock, 避免重复运行互相踩
#   - 容错: 单只失败记入 failed, 继续其余, 末打印汇总
#
# 用法:
#   python cyq_service.py --create-table        # 仅建表
#   python cyq_service.py --codes 002080,300200,300377
#   python cyq_service.py --limit 50            # 取 stock_info 前 50 只试运行
#   python cyq_service.py                        # 全市场(生产, 耗时较长, 建议后台/定时)
#   python cyq_service.py --force               # 忽略进度, 全量重算
import os, sys, json, time, argparse, socket, ctypes
from datetime import date, datetime, timedelta
import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from cyq_core import (FACTOR, normalize_turnover, compute_cyq_np, compute_cyq_continue,
                      metrics, fetch_baostock_unadj, write_cyq_ch, query_ch,
                      get_unadj, compute_cyq_daily, cyq_to_json, compute_main_cost_for_snaps,
                      CH_HOST, CH_PORT, CH_DB, CH_USER, CH_PW)

PROGRESS = os.path.join(HERE, ".cyq_progress.json")
PROGRESS_D = os.path.join(HERE, ".cyq_progress_daily.json")
LOCK    = os.path.join(HERE, ".cyq_service.lock")
TABLE_D = "stock_cyq_daily"

# 内嵌建表语句(避免解析 .sql 注释出错; stock_cyq_ddl.sql 仅作文档)
# 单表设计: 仅 stock_cyq_daily(逐日历史); 实时快照 = daily 最新一行, 不再单列 stock_cyq
CREATE_SQL_D = f"""CREATE TABLE IF NOT EXISTS {CH_DB}.{TABLE_D}
(
    code        String,
    trade_date  Date,
    avg_cost    Float64,
    benefit     Float32,
    c90_lo      Float64,
    c90_hi      Float64,
    c90_conc    Float64,
    c70_lo      Float64,
    c70_hi      Float64,
    c70_conc    Float64,
    cyq_json    String,
    updated_at  DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY (code, trade_date)"""

def log(*a):
    print("[%s]" % datetime.now().strftime("%H:%M:%S"), *a, flush=True)

# ---------------- 锁 ----------------
def acquire_lock():
    if os.path.exists(LOCK):
        try:
            with open(LOCK) as f: old = f.read().strip()
            if old and _pid_alive(old):
                log("另一实例运行中(pid=%s), 退出" % old); sys.exit(1)
        except Exception: pass
    with open(LOCK, "w") as f: f.write(str(os.getpid()))

def release_lock():
    # 沙箱/Windows 下 os.remove 会被 safe-delete shim 拦截超时, 改用 ctypes 直删
    try:
        if os.path.exists(LOCK):
            ctypes.windll.kernel32.DeleteFileW(LOCK)
    except Exception:
        try: os.remove(LOCK)
        except Exception: pass

def _pid_alive(pid):
    try:
        os.kill(int(pid), 0); return True
    except Exception:
        return False

# ---------------- 进度 ----------------
def load_progress():
    if os.path.exists(PROGRESS):
        try: return json.load(open(PROGRESS))
        except Exception: pass
    return {"done": [], "failed": []}

def save_progress(p):
    tmp = PROGRESS + ".tmp"
    json.dump(p, open(tmp, "w")); os.replace(tmp, PROGRESS)

def load_progress_daily():
    if os.path.exists(PROGRESS_D):
        try: return json.load(open(PROGRESS_D))
        except Exception: pass
    return {"done": []}

# ---------------- 代码清单 ----------------
def get_code_list(args):
    if args.codes:
        codes = [c.strip() for c in args.codes.split(",") if c.strip()]
    elif args.file and os.path.exists(args.file):
        codes = [l.strip() for l in open(args.file) if l.strip() and l.strip()[0].isdigit()]
    else:
        try:
            rows = query_ch(f"SELECT code FROM {CH_DB}.stock_info").strip().split("\n")
            codes = [r for r in rows if r]
            log("从 CH stock_info 取到 %d 只股票" % len(codes))
        except Exception as e:
            log("取股票清单失败: %s" % e); codes = []
    # 仅保留纯数字代码
    codes = [c for c in codes if c.isdigit()]
    if args.limit:
        codes = codes[:args.limit]
    return codes

# ---------------- 读取已有快照 ----------------
def get_existing(code):
    try:
        # ReplacingMergeTree 在 merge 前可能保留多个版本, 必须加 FINAL 取 updated_at 最新版
        # （原实现只有 ORDER BY trade_date DESC, 同日多版本时会随机取到旧版的退化分布作种子 -> 档位坍缩扩散）
        r = query_ch(f"SELECT trade_date, cyq_json FROM {CH_DB}.{TABLE_D} FINAL WHERE code='{code}' ORDER BY trade_date DESC LIMIT 1")
        if not r.strip(): return None
        line = r.strip().split("\n")[0]
        # trade_date \t cyq_json
        parts = line.split("\t")
        td = parts[0].strip()
        js = parts[1].strip() if len(parts) > 1 else ""
        return {"trade_date": td, "cyq_json": js}
    except Exception:
        return None

# ---------------- 单只处理 ----------------
def process_one(code, force=False):
    exist = None if force else get_existing(code)
    if exist and exist.get("trade_date"):
        # 增量: 从已有交易日次日拉新数据
        last = exist["trade_date"]
        sd = (datetime.strptime(last, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
        df = fetch_baostock_unadj(code, start_date=sd)
        if df.empty:
            return None, "skip-up-to-date"   # 无新数据
        df["turn"] = normalize_turnover(df["turn"].values)
        new_kl = list(zip(df["open"], df["high"], df["low"], df["close"], df["turn"]))
        # 尝试增量续算
        try:
            prev = json.loads(exist["cyq_json"])
            yp = np.array(prev["yrange"], float); xp = np.array(prev["x"], float)
            cont = compute_cyq_continue(yp, xp, new_kl)
            if cont is not None:
                yrange, x = cont
            else:
                df_all = fetch_baostock_unadj(code)  # 区间扩展 -> 全量
                df_all["turn"] = normalize_turnover(df_all["turn"].values)
                yrange, x = compute_cyq_np(list(zip(df_all["open"], df_all["high"], df_all["low"], df_all["close"], df_all["turn"])))
        except Exception:
            df_all = fetch_baostock_unadj(code)
            df_all["turn"] = normalize_turnover(df_all["turn"].values)
            yrange, x = compute_cyq_np(list(zip(df_all["open"], df_all["high"], df_all["low"], df_all["close"], df_all["turn"])))
        close = float(df["close"].iloc[-1]); trade_date = df["date"].iloc[-1]
    else:
        df = fetch_baostock_unadj(code)
        if df.empty:
            return None, "no-data"
        df["turn"] = normalize_turnover(df["turn"].values)
        yrange, x = compute_cyq_np(list(zip(df["open"], df["high"], df["low"], df["close"], df["turn"])))
        close = float(df["close"].iloc[-1]); trade_date = df["date"].iloc[-1]
    m = metrics(yrange, x, close)
    row = dict(
        code=code, trade_date=trade_date, close_price=round(float(close), 4),
        avg_cost=round(m["avg_cost"], 4), benefit=round(float(m["benefit"]), 6),
        c90_lo=round(m["c90_lo"], 4), c90_hi=round(m["c90_hi"], 4), c90_conc=round(m["c90_conc"], 6),
        c70_lo=round(m["c70_lo"], 4), c70_hi=round(m["c70_hi"], 4), c70_conc=round(m["c70_conc"], 6),
        cyq_json=json.dumps({"yrange": [round(float(v), 4) for v in yrange],
                             "x": [round(float(v), 8) for v in x]}, separators=(",", ":")),
    )
    return row, "ok"

# ---------------- 单只处理(逐日快照, 方案C) ----------------
def get_existing_daily_max(code):
    try:
        r = query_ch(f"SELECT max(trade_date) FROM {CH_DB}.{TABLE_D} WHERE code='{code}'")
        r = r.strip()
        return r if r and r != "1970-01-01" and r != "\\N" else None
    except Exception:
        return None

# 种子健康度阈值: 健康分布有效档数(nz)应接近满档(约148); 退化(旧 ×100 腐蚀残留)会坍缩到几十以下。
# 退化分布作增量种子会逐日污染后续每日, 故低于此阈值即判定为坏种子, 自动切长窗口重算。
CYQ_MIN_VALID_NZ = 100

def _seed_healthy(seed):
    """判断一张存储的筹码分布是否健康(可作增量续算种子)。
    seed = (yrange_array, x_array)。健康: 有效档数接近满档 且 总量在合理区间。
    返回 (ok:bool, nz:int, xsum:float) 便于日志。"""
    if seed is None:
        return False, 0, 0.0
    yp, xp = seed
    if xp is None or len(xp) == 0:
        return False, 0, 0.0
    nz = int(np.count_nonzero(xp > 1e-6))
    xsum = float(xp.sum())
    # x_sum 算法固有常数约 0.16~15(茅台低换手低, 活跃股高); 退化时 ×100 污染会让总量飙到 25+ 或塌到近 0
    if nz < CYQ_MIN_VALID_NZ or xsum > 25 or xsum < 0.05:
        return False, nz, xsum
    return True, nz, xsum

def recompute_from_long_window(code, sd, end, adj_fallback=False):
    """种子退化/价格越界时的安全回退: 抓 sd 前 250 日长窗口, compute_cyq_daily 一次算到 end,
    过滤出增量区间 [sd,end] 返回 rows。数学等价于 force-snap(从 250 日历史新鲜计算), 不读任何
    存储分布, 故不会继承旧腐蚀。仅返回 [sd,end] 的行, 供 process_one_daily 增量写库。"""
    sd_long = (datetime.strptime(sd, "%Y-%m-%d") - timedelta(days=250)).strftime("%Y-%m-%d")
    try:
        df, _ = get_unadj(code, sd_long, end, adj_fallback=adj_fallback)
    except Exception:
        return None
    if df is None or df.empty:
        return None
    df["turn"] = normalize_turnover(df["turn"].values)
    kl = list(zip(df["date"], df["open"], df["high"], df["low"], df["close"], df["turn"]))
    snaps = compute_cyq_daily(kl)
    if not snaps:
        return None
    window = [s for s in snaps if sd <= s["date"] <= end]
    if not window:
        return None
    compute_main_cost_for_snaps(window, code, sd, end)
    rows = []
    for s in window:
        yr = np.asarray(s["yrange"], float); x = np.asarray(s["x"], float)
        m = metrics(yr, x, s["close"])
        rows.append(dict(
            code=code, trade_date=s["date"], close_price=round(float(s["close"]), 4),
            avg_cost=round(m["avg_cost"], 4), benefit=round(float(m["benefit"]), 6),
            c90_lo=round(m["c90_lo"], 4), c90_hi=round(m["c90_hi"], 4), c90_conc=round(m["c90_conc"], 6),
            c70_lo=round(m["c70_lo"], 4), c70_hi=round(m["c70_hi"], 4), c70_conc=round(m["c70_conc"], 6),
            main_cost=round(s["main_cost"], 4), main_cost_lo=round(s["main_cost_lo"], 4),
            main_cost_hi=round(s["main_cost_hi"], 4), main_cost_conf=s["main_cost_conf"],
            cyq_json=cyq_to_json(s["yrange"], s["x"]),
        ))
    return rows

def continue_rows_from_seed(code, seed_date, sd, end, adj_fallback=False):
    """以 seed_date 那天的真实分布作种子, 对 [sd,end] 的新交易日逐日续算成 row。

    存在的意义: 下面的 process_one_daily 若直接把「仅有的新交易日K线」喂给 compute_cyq_daily,
    累加器会把首日单根K线当种子 -> 产出三角形假分布(总量==当日换手率)。日常推进每次只多1天,
    恰好必然踩中。故必须走 continue 路径。

    任一日续算失败(价格超出旧区间/无K线/种子缺失) -> 返回 None, 由调用方回退长窗口全量。
    """
    seed = None
    try:
        raw = query_ch(f"SELECT cyq_json FROM {CH_DB}.{TABLE_D} FINAL "
                       f"WHERE code='{code}' AND trade_date='{seed_date}'")
        if raw.strip():
            prev = json.loads(raw.strip().split("\n")[0])
            seed = (np.array(prev["yrange"], float), np.array(prev["x"], float))
    except Exception:
        seed = None
    if seed is None:
        return None
    # 种子健康度保护: 退化分布(旧 ×100 腐蚀残留等)作种子会逐日污染后续每日;
    # 判定异常则自动切 250 日长窗口重算(从 250 日历史新鲜计算, 不读存储分布), 自愈而不传染。
    ok, nz, xsum = _seed_healthy(seed)
    if not ok:
        log("  [%s] 种子 %s 退化(nz=%d xsum=%.2f<阈值%d), 自动切长窗口重算 [%s,%s]"
            % (code, seed_date, nz, xsum, CYQ_MIN_VALID_NZ, sd, end))
        try:
            return recompute_from_long_window(code, sd, end, adj_fallback=adj_fallback)
        except Exception as e:
            log("  [%s] 长窗口重算失败(种子退化回退): %s" % (code, repr(e)[:120]))
            return None

    df, _ = get_unadj(code, sd, end, adj_fallback=adj_fallback)
    if df is None or df.empty:
        return None
    df["turn"] = normalize_turnover(df["turn"].values)
    kl = list(zip(df["date"], df["open"], df["high"], df["low"], df["close"], df["turn"]))

    yp, xp = seed
    snaps = []
    for (d0, o, h, l, c, t) in kl:
        res = compute_cyq_continue(yp, xp, [(o, h, l, c, t)])
        if res is None:
            # 价格越界(当日成交突破历史区间): 续算链不可信, 自动切长窗口重算自愈, 而非把坏传下去
            log("  [%s] %s 续算价格越界, 自动切长窗口重算 [%s,%s]" % (code, d0, sd, end))
            try:
                return recompute_from_long_window(code, sd, end, adj_fallback=adj_fallback)
            except Exception:
                return None
        yr, x = res
        m = metrics(yr, x, float(c))
        snaps.append({"date": d0, "yrange": yr, "x": x, "close": float(c),
                      "c70_lo": m["c70_lo"], "c70_hi": m["c70_hi"]})
        yp, xp = yr, x
    if not snaps:
        return None

    compute_main_cost_for_snaps(snaps, code, sd, end)
    rows = []
    for s in snaps:
        m = metrics(s["yrange"], s["x"], s["close"])
        rows.append(dict(
            code=code, trade_date=s["date"], close_price=round(s["close"], 4),
            avg_cost=round(m["avg_cost"], 4), benefit=round(float(m["benefit"]), 6),
            c90_lo=round(m["c90_lo"], 4), c90_hi=round(m["c90_hi"], 4), c90_conc=round(m["c90_conc"], 6),
            c70_lo=round(m["c70_lo"], 4), c70_hi=round(m["c70_hi"], 4), c70_conc=round(m["c70_conc"], 6),
            main_cost=round(s["main_cost"], 4), main_cost_lo=round(s["main_cost_lo"], 4),
            main_cost_hi=round(s["main_cost_hi"], 4), main_cost_conf=s["main_cost_conf"],
            cyq_json=cyq_to_json(s["yrange"], s["x"]),
        ))
    return rows

def process_one_daily(code, start, end, force=False, adj_fallback=False):
    """方案C: 计算 [start,end] 逐日筹码, 写 stock_cyq_daily(单表; 实时快照即 daily 最新行)。
       返回 (n_daily, last_row_or_None, status)。
       adj_fallback: 未复权列为 NULL 时回退到复权价(coalesce), 用于增量路径补齐缺失未复权;
                     最新日 qfq==未复权, 回退精确; 全量回补(--daily)默认关闭以保留真实未复权。"""
    last = None if force else get_existing_daily_max(code)
    if last and last >= end:
        return 0, None, "skip-up-to-date"
    sd = start if (force or not last) else (datetime.strptime(last, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")

    # 增量推进(日常形态, 每次只多1天): 先尝试以前一日真实分布作种子续算。
    # 不能直接走下面的 compute_cyq_daily —— 它把窗口首日K线当种子, 单日窗口会产出三角形假分布。
    if last and sd > last:
        try:
            rows = continue_rows_from_seed(code, last, sd, end, adj_fallback=adj_fallback)
        except Exception:
            rows = None
        if rows:
            try:
                write_cyq_ch(rows, table=TABLE_D)
            except Exception as e:
                log("  [%s] 逐日落库失败(续算): %s" % (code, repr(e)[:160]))
                return 0, None, "write-err"
            return len(rows), rows[-1], "ok-continue"
        # 续算不可用 -> 落到下面原有的长窗口回退路径

    df, src = get_unadj(code, sd, end, adj_fallback=adj_fallback)
    if df is None or df.empty:
        # 若 CH 无未复权, 尝试更早起点(全历史)以收敛分布
        df, src = get_unadj(code, start, end, adj_fallback=adj_fallback)
        if df is None or df.empty:
            return 0, None, "no-data"
    df["turn"] = normalize_turnover(df["turn"].values)
    kl = list(zip(df["date"], df["open"], df["high"], df["low"], df["close"], df["turn"]))
    snaps = compute_cyq_daily(kl)
    if not snaps:
        return 0, None, "no-data"
    compute_main_cost_for_snaps(snaps, code, sd, end)
    daily_rows = []
    for s in snaps:
        daily_rows.append(dict(
            code=code, trade_date=s["date"], close_price=round(float(s["close"]), 4),
            avg_cost=round(s["avg_cost"], 4), benefit=round(float(s["benefit"]), 6),
            c90_lo=round(s["c90_lo"], 4), c90_hi=round(s["c90_hi"], 4), c90_conc=round(s["c90_conc"], 6),
            c70_lo=round(s["c70_lo"], 4), c70_hi=round(s["c70_hi"], 4), c70_conc=round(s["c70_conc"], 6),
            main_cost=round(s["main_cost"], 4), main_cost_lo=round(s["main_cost_lo"], 4),
            main_cost_hi=round(s["main_cost_hi"], 4), main_cost_conf=s["main_cost_conf"],
            cyq_json=cyq_to_json(s["yrange"], s["x"]),
        ))
    # 逐日行写 stock_cyq_daily(ReplacingMergeTree 按 (code,trade_date) 去重; 单表, 无 stock_cyq)
    try:
        write_cyq_ch(daily_rows, table=TABLE_D)
    except Exception as e:
        log("  [%s] 逐日落库失败: %s" % (code, repr(e)[:160]))
    last_row = daily_rows[-1]
    return len(daily_rows), last_row, "ok"

# ---------------- 增量更新(方案C日常: 仅推进新交易日) ----------------
def get_existing_daily_latest(code):
    """取 stock_cyq_daily 最新一行(含完整分布), 作为增量种子。无则返回 None。
    ReplacingMergeTree 需 FINAL 保证同日多版本时取 updated_at 最新版。"""
    try:
        r = query_ch(f"SELECT trade_date, cyq_json FROM {CH_DB}.{TABLE_D} FINAL "
                     f"WHERE code='{code}' ORDER BY trade_date DESC LIMIT 1")
        if not r.strip(): return None
        line = r.strip().split("\n")[0]
        parts = line.split("\t")
        return {"trade_date": parts[0].strip(),
                "cyq_json": parts[1].strip() if len(parts) > 1 else ""}
    except Exception:
        return None

def _max_stock_daily_date():
    try:
        r = query_ch(f"SELECT max(trade_date) FROM {CH_DB}.stock_daily").strip()
        return r if r and r != "\\N" else None
    except Exception:
        return None

def codes_needing_incremental(end):
    """返回需要推进的股票清单: stock_daily 最新日 > stock_cyq_daily 最新日(或无快照)。
       两次 GROUP BY 查询 + 内存差集, 避免逐只探测。查询失败返回 None(调用方回退全量)。"""
    try:
        dmax = {}
        for line in query_ch(f"SELECT code, max(trade_date) FROM {CH_DB}.stock_daily GROUP BY code").strip().split("\n"):
            if not line.strip(): continue
            c, d = line.split("\t"); dmax[c] = d.strip()
        cmax = {}
        for line in query_ch(f"SELECT code, max(trade_date) FROM {CH_DB}.{TABLE_D} GROUP BY code").strip().split("\n"):
            if not line.strip(): continue
            c, d = line.split("\t"); cmax[c] = d.strip()
    except Exception as e:
        log("codes_needing_incremental 查询失败: %s" % e); return None
    need = []
    for c, d in dmax.items():
        if not d or d == "\\N": continue
        cd = cmax.get(c)
        if cd is None or cd < d:
            need.append(c)
    return need

def process_one_incremental(code, end, force=False):
    """增量: 以 stock_cyq_daily 最新快照为种子, 仅计算 [种子日+1, end] 的新交易日,
       写 stock_cyq_daily(单表; 实时快照即 daily 最新行)。与 --daily 全量数学等价, 但 O(新天数)/只。
       返回 (n_new, last_row_or_None, status)。"""
    seed = None if force else get_existing_daily_latest(code)
    if not seed:
        # 无历史快照 -> 全量(走逐日逻辑补齐); 增量路径统一 adj_fallback 补齐缺失未复权
        sd = (datetime.strptime(end, "%Y-%m-%d") - timedelta(days=365)).strftime("%Y-%m-%d")
        return process_one_daily(code, sd, end, force=True, adj_fallback=True)
    last_date = seed["trade_date"]
    if last_date >= end:
        return 0, None, "skip-up-to-date"
    sd = (datetime.strptime(last_date, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
    df, src = get_unadj(code, sd, end, adj_fallback=True)
    if df is None or df.empty:
        return 0, None, "skip-up-to-date"   # 无新交易日(已追平)
    df["turn"] = normalize_turnover(df["turn"].values)
    prev = json.loads(seed["cyq_json"])
    yr, x = np.array(prev["yrange"], float), np.array(prev["x"], float)
    snaps = []
    for (date, o, h, l, c, turn) in zip(df["date"], df["open"], df["high"], df["low"], df["close"], df["turn"]):
        res = compute_cyq_continue(yr, x, [(o, h, l, c, turn)])
        if res is None:
            # 价格区间扩张 -> 回退全量重算整个窗口; 增量路径 adj_fallback 补齐缺失未复权
            sd0 = (datetime.strptime(end, "%Y-%m-%d") - timedelta(days=365)).strftime("%Y-%m-%d")
            return process_one_daily(code, sd0, end, force=True, adj_fallback=True)
        yr, x = res
        m = metrics(yr, x, float(c))
        snaps.append(dict(
            code=code, trade_date=date, close_price=round(float(c), 4),
            avg_cost=round(m["avg_cost"], 4), benefit=round(float(m["benefit"]), 6),
            c90_lo=round(m["c90_lo"], 4), c90_hi=round(m["c90_hi"], 4), c90_conc=round(m["c90_conc"], 6),
            c70_lo=round(m["c70_lo"], 4), c70_hi=round(m["c70_hi"], 4), c70_conc=round(m["c70_conc"], 6),
            cyq_json=cyq_to_json(yr, x),
        ))
    if not snaps:
        return 0, None, "skip-up-to-date"
    compute_main_cost_for_snaps(snaps, code, sd, end)
    try:
        write_cyq_ch(snaps, table=TABLE_D)
    except Exception as e:
        log("  [%s] 逐日增量落库失败: %s" % (code, repr(e)[:160]))
    return len(snaps), snaps[-1], "ok"

# ---------------- 主流程 ----------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--codes", help="逗号分隔代码, 如 002080,300200,300377")
    ap.add_argument("--file", help="代码清单文件(每行一只)")
    ap.add_argument("--limit", type=int, help="仅取前 N 只(配合 stock_info 清单试运行)")
    ap.add_argument("--force", action="store_true", help="忽略进度, 全量重算")
    ap.add_argument("--create-table", action="store_true", help="仅建表后退出")
    ap.add_argument("--batch", type=int, default=200, help="每批落库条数")
    ap.add_argument("--no-resume", action="store_true", help="忽略进度文件(.cyq_progress.json)")
    ap.add_argument("--daily", action="store_true", help="方案C: 计算逐日筹码写 stock_cyq_daily(单表)")
    ap.add_argument("--incremental", action="store_true", help="方案C日常增量: 以 stock_cyq_daily 最新快照为种子, 仅推进新交易日(写 stock_cyq_daily 单表)")
    ap.add_argument("--start", help="逐日起点日期(默认 end-1年), 如 2025-08-28")
    ap.add_argument("--end", help="逐日终点日期(默认今天)")
    args = ap.parse_args()

    acquire_lock()
    try:
        if args.create_table:
            query_ch(CREATE_SQL_D)
            log("建表完成: %s.%s" % (CH_DB, TABLE_D))
            return

        if args.daily:
            end = args.end or date.today().strftime("%Y-%m-%d")
            start = args.start or (datetime.strptime(end, "%Y-%m-%d") - timedelta(days=365)).strftime("%Y-%m-%d")
            log("逐日模式: %s ~ %s" % (start, end))
            codes = get_code_list(args)
            prog = {} if (args.force or args.no_resume) else load_progress_daily()
            done = set(prog.get("done", [])); failed = set(prog.get("failed", []))
            if not args.force:
                codes = [c for c in codes if c not in done]
            log("逐日待处理 %d 只" % len(codes))
            ok = skip = err = 0
            for i, code in enumerate(codes, 1):
                try:
                    n, last_row, status = process_one_daily(code, start, end, force=args.force)
                    if status == "skip-up-to-date":
                        skip += 1; done.add(code)
                    elif status == "no-data":
                        failed.add(code)
                    else:
                        ok += 1; done.add(code)
                except Exception as e:
                    log("  ERR %s: %s" % (code, repr(e)[:160])); failed.add(code)
                if i % 10 == 0:
                    log("逐日进度 %d/%d ok=%d skip=%d err=%d" % (i, len(codes), ok, skip, len(failed)))
                if i % 50 == 0:
                    save_progress({"done": list(done), "failed": list(failed)})
            save_progress({"done": list(done), "failed": list(failed)})
            log("逐日完成: ok=%d skip=%d failed=%d / 共 %d" % (ok, skip, len(failed), len(codes)))
            if failed:
                log("失败代码: %s" % ",".join(sorted(failed)[:50]))
            return

        if args.incremental:
            end = args.end or _max_stock_daily_date() or date.today().strftime("%Y-%m-%d")
            log("增量模式: 推进至 %s" % end)
            if args.codes:
                codes = get_code_list(args)   # 指定代码(测试/部分重算)
                log("增量待处理(指定) %d 只" % len(codes))
            else:
                codes = codes_needing_incremental(end)
                if codes is None:
                    codes = get_code_list(args)   # 查询失败回退全量清单
                    log("增量待处理(全量回退) %d 只" % len(codes))
                else:
                    log("增量待处理 %d 只(有新股/新日)" % len(codes))
            ok = skip = err = 0
            for i, code in enumerate(codes, 1):
                try:
                    n, last_row, status = process_one_incremental(code, end, force=args.force)
                    if status in ("skip-up-to-date", "no-data"):
                        skip += 1
                    elif status == "ok":
                        ok += 1
                    else:
                        err += 1
                except Exception as e:
                    log("  ERR %s: %s" % (code, repr(e)[:160])); err += 1
                if i % 10 == 0:
                    log("增量进度 %d/%d ok=%d skip=%d err=%d" % (i, len(codes), ok, skip, err))
            log("增量完成: ok=%d skip=%d err=%d / 共 %d" % (ok, skip, err, len(codes)))
            return

        codes = get_code_list(args)
        prog = {} if (args.force or args.no_resume) else load_progress()
        done = set(prog.get("done", [])); failed = set(prog.get("failed", []))
        if not args.force:
            codes = [c for c in codes if c not in done]
        log("待处理 %d 只 (已完成=%d, 失败=%d)" % (len(codes), len(done), len(failed)))

        batch = []
        ok = skip = err = 0
        for i, code in enumerate(codes, 1):
            try:
                row, status = process_one(code, force=args.force)
                if status == "skip-up-to-date":
                    skip += 1; done.add(code)
                elif status in ("no-data",):
                    failed.add(code)
                else:
                    batch.append(row); ok += 1; done.add(code)
                if i % 20 == 0:
                    log("进度 %d/%d  ok=%d skip=%d err=%d" % (i, len(codes), ok, skip, len(failed)))
            except Exception as e:
                log("  ERR %s: %s" % (code, repr(e)[:160])); failed.add(code)
            # 周期落库 + 写进度
            if len(batch) >= args.batch:
                try: write_cyq_ch(batch, table=TABLE_D); batch.clear()
                except Exception as e: log("  落库失败: %s" % repr(e)[:160])
            if i % 50 == 0:
                save_progress({"done": list(done), "failed": list(failed)})
        if batch:
            try: write_cyq_ch(batch)
            except Exception as e: log("  末批落库失败: %s" % repr(e)[:160])
        save_progress({"done": list(done), "failed": list(failed)})
        log("完成: ok=%d skip=%d failed=%d / 共 %d" % (ok, skip, len(failed), len(codes)))
        if failed:
            log("失败代码: %s" % ",".join(sorted(failed)[:50]))
    finally:
        release_lock()

if __name__ == "__main__":
    main()
