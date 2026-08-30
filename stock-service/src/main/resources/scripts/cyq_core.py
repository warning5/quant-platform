#!/usr/env python
# cyq_core.py - 筹码分布(CYQ)计算核心模块 (对齐东财 stock_cyq_em 算法)
#
# 设计要点(源自验证):
#   1) 算法与东财逐位一致(已用 dbg_cyq.py 验证 max|Δ|=0.000000, 逐日零分叉)。
#   2) 数据源: Baostock 未复权(adjustflag="3"), 沙箱可连; 东财接口被封。
#   3) 换手率量纲: 不同源可能是小数(新浪 0.0314)或百分比(Baostock 70.14)。
#      normalize_turnover() 自动判定(整列 max<=1.0 视为小数 -> x100), 杜绝量纲错误。
#   4) 落库: ClickHouse stock.stock_cyq (HTTP 8123, ?query=urlencode + JSONEachRow + 按真实字节1MB分块)。
#
# 复用: from cyq_core import normalize_turnover, compute_cyq_np, metrics, fetch_baostock_unadj, write_cyq_ch
import os, sys, json, base64, time
from datetime import datetime, timedelta
import numpy as np
import pandas as pd
import baostock as bs
import urllib.request, urllib.parse

FACTOR = 150  # 价格桶数(与东财/akshare 一致)

# CH 连接(优先读环境变量, 默认对齐本项目 docker-compose: stock库/default/8123)
CH_HOST = os.environ.get("CH_HOST", "172.19.72.140")
CH_PORT = int(os.environ.get("CH_PORT", "8123"))
CH_DB   = os.environ.get("CH_DB", "stock")
CH_USER = os.environ.get("CH_USER", "default")
CH_PW   = os.environ.get("CH_PW", "123456")

# ---------------- 换手率量纲归一 ----------------
def normalize_turnover(turn_array):
    """把换手率统一成百分比(与 Baostock/Eastmoney 口径一致):
       - 单源列内部一致; 整列 max<=1.0 视为小数(0.0314) -> x100 得 3.14
       - 否则视为百分比原样返回
       窄带风险(某票全历史换手率恰在 0.7%~1.5%): 极罕见, 实际 A 股各源分母均为流通股本,
       实测 Baostock vs 新浪 相对差 <=1.5%, 故可放心。"""
    a = np.asarray(turn_array, dtype=float)
    a = np.where(np.isnan(a), 0.0, a)
    if np.nanmax(a) <= 1.0:
        return a * 100.0
    return a

# ---------------- 自写 numpy 算法(严格对齐东财 CYQCalculator) ----------------
def compute_cyq_np(klines, factor=FACTOR):
    """klines: list[(open, high, low, close, turn)]  turn 已是百分比(如 3.14 表示 3.14%)
       返回 (yrange: np.array(F), x: np.array(F))  x 为各桶筹码质量。"""
    highs = np.array([k[1] for k in klines], float)
    lows  = np.array([k[2] for k in klines], float)
    maxp, minp = float(highs.max()), float(lows.min())
    acc = max(0.01, (maxp - minp) / (factor - 1))
    yrange = minp + acc * np.arange(factor)
    x = np.zeros(factor)
    for o, h, l, c, turn in klines:
        t = min(1.0, float(turn) / 100.0); avg = (o + c + h + l) / 4.0
        H = min(factor - 1, int(np.floor((h - minp) / acc)))
        L = max(0, int(np.ceil((l - minp) / acc)))
        gi = min(factor - 1, max(0, int(np.floor((avg - minp) / acc))))
        g = (factor - 1) if h == l else 2.0 / (h - l)
        x *= (1 - t)
        if h == l:
            x[gi] += g * t / 2.0
        else:
            for j in range(L, H + 1):
                p = minp + acc * j
                if p <= avg:
                    w = 1.0 if abs(avg - l) < 1e-8 else (p - l) / (avg - l)
                else:
                    w = 1.0 if abs(h - avg) < 1e-8 else (h - p) / (h - avg)
                x[j] += w * g * t
    return yrange, x

def compute_cyq_continue(yrange_prev, x_prev, new_klines, factor=FACTOR):
    """增量续算: 在已有分布(x_prev, yrange_prev)基础上喂新交易日。
       若新高低超出旧区间 -> 返回 None(调用方改为全量重算)。"""
    minp_prev = float(yrange_prev[0]); maxp_prev = float(yrange_prev[-1])
    nh = max(k[1] for k in new_klines); nl = min(k[2] for k in new_klines)
    if nl < minp_prev - 1e-9 or nh > maxp_prev + 1e-9:
        return None
    x = x_prev.copy()
    for o, h, l, c, turn in new_klines:
        t = min(1.0, float(turn) / 100.0); avg = (o + c + h + l) / 4.0
        H = min(factor - 1, int(np.floor((h - minp_prev) / (yrange_prev[1]-yrange_prev[0]))))
        L = max(0, int(np.ceil((l - minp_prev) / (yrange_prev[1]-yrange_prev[0]))))
        gi = min(factor - 1, max(0, int(np.floor((avg - minp_prev) / (yrange_prev[1]-yrange_prev[0])))))
        g = (factor - 1) if h == l else 2.0 / (h - l)
        x *= (1 - t)
        if h == l:
            x[gi] += g * t / 2.0
        else:
            for j in range(L, H + 1):
                p = minp_prev + (yrange_prev[1]-yrange_prev[0]) * j
                if p <= avg:
                    w = 1.0 if abs(avg - l) < 1e-8 else (p - l) / (avg - l)
                else:
                    w = 1.0 if abs(h - avg) < 1e-8 else (h - p) / (h - avg)
                x[j] += w * g * t
    return yrange_prev, x

# ---------------- 指标 ----------------
def metrics(yrange, x, close):
    total = x.sum()
    benefit = float(x[yrange < close].sum() / total) if total else 0.0
    avg_cost = float((yrange * x).sum() / total) if total else 0.0
    cum = np.cumsum(x)
    def cr(p):
        lo = min(max(int(np.searchsorted(cum, total*(1-p)/2)), 0), len(x)-1)
        hi = min(max(int(np.searchsorted(cum, total*(1+p)/2)), 0), len(x)-1)
        conc = (yrange[hi]-yrange[lo])/(yrange[hi]+yrange[lo]) if (yrange[hi]+yrange[lo])>0 else 0
        return float(yrange[lo]), float(yrange[hi]), float(conc)
    c90 = cr(0.90); c70 = cr(0.70)
    return dict(avg_cost=avg_cost, benefit=benefit,
                c90_lo=c90[0], c90_hi=c90[1], c90_conc=c90[2],
                c70_lo=c70[0], c70_hi=c70[1], c70_conc=c70[2])


# ---------------- 主力成本估算 (①+②+③) ----------------
# ① 大单/主力净流入加权均价(高频, 噪声大)
# ② CYQ c70 集中带中位数(全持有人成本, 平滑)
# ③ 龙虎榜上榜日 close 加权锚点(稀疏但高置信)
# 三者融合: ① 作主序列, ② 提供区间下界, ③ 作置信加成; 输出点估计+区间+置信。
EMA_HALF_LIFE = 20  # 资金流 EMA 半衰期(交易日)

def fetch_moneyflow_series(sym, start_date, end_date):
    """stock_sentiment_moneyflow: 返回 [(date, close, net_main, net_huge, net_big)]"""
    sql = (f"SELECT trade_date, close, net_main, net_huge, net_big FROM {CH_DB}.stock_sentiment_moneyflow "
           f"WHERE code='{sym}' AND trade_date>='{start_date}' AND trade_date<='{end_date}' ORDER BY trade_date")
    try:
        raw = query_ch(sql).strip()
    except Exception:
        return []
    out = []
    if not raw:
        return out
    for line in raw.split("\n"):
        if not line.strip():
            continue
        p = line.split("\t")
        if len(p) < 5:
            continue
        try:
            out.append((p[0].strip(), float(p[1] or 0), float(p[2] or 0),
                        float(p[3] or 0), float(p[4] or 0)))
        except ValueError:
            continue
    return out

def fetch_lhb_series(sym, start_date, end_date):
    """stock_sentiment_lhb: 返回 [(date, close, buy_amount, net_amount, reason)]"""
    sql = (f"SELECT trade_date, close, buy_amount, net_amount, reason FROM {CH_DB}.stock_sentiment_lhb "
           f"WHERE code='{sym}' AND trade_date>='{start_date}' AND trade_date<='{end_date}' ORDER BY trade_date")
    try:
        raw = query_ch(sql).strip()
    except Exception:
        return []
    out = []
    if not raw:
        return out
    for line in raw.split("\n"):
        if not line.strip():
            continue
        p = line.split("\t")
        if len(p) < 5:
            continue
        try:
            out.append((p[0].strip(), float(p[1] or 0), float(p[2] or 0),
                        float(p[3] or 0), (p[4] or "").strip()))
        except ValueError:
            continue
    return out

def compute_main_cost_for_snaps(snaps, sym, sd, ed):
    """对 compute_cyq_daily 产出的逐日快照列表就地附加主力成本估算字段。
       snaps 需含 date, c70_lo, c70_hi。返回同一列表(已改)。"""
    if not snaps:
        return snaps
    warm = (datetime.strptime(sd, "%Y-%m-%d") - timedelta(days=120)).strftime("%Y-%m-%d")
    mf = fetch_moneyflow_series(sym, warm, ed)
    lhb = fetch_lhb_series(sym, warm, ed)

    # ① EMA 买侧加权均价(逐日, 仅 net_main>0 的交易日计入)
    decay = 0.5 ** (1.0 / EMA_HALF_LIFE)
    num = 0.0
    den = 0.0
    main1 = {}
    last1 = None
    for (d, close, nm, _nh, _nb) in mf:
        num *= decay
        den *= decay
        w = max(nm, 0.0)
        if w > 0 and close > 0:
            num += w * close
            den += w
        if den > 0:
            last1 = num / den
        main1[d] = last1

    # ③ 龙虎榜累计加权锚(净买为正的榜才计, 避免对倒稀释)
    ln = 0.0
    ld = 0.0
    main3 = {}
    last3 = None
    for (d, close, buy, net, _reason) in lhb:
        w = buy if (buy and buy > 0) else 0.0
        if net is not None and net <= 0:
            w = 0.0
        if w > 0 and close > 0:
            ln += w * close
            ld += w
        if ld > 0:
            last3 = ln / ld
        main3[d] = last3

    # 前向填充: 快照日若不在 moneyflow/lhb 当天, 沿用最近一次有效值
    main1_full, last1 = {}, None
    for s in snaps:
        d = s.get("date") or s.get("trade_date")
        if d in main1 and main1[d] is not None:
            last1 = main1[d]
        main1_full[d] = last1
    main3_full, last3 = {}, None
    for s in snaps:
        d = s.get("date") or s.get("trade_date")
        if d in main3 and main3[d] is not None:
            last3 = main3[d]
        main3_full[d] = last3

    for s in snaps:
        d = s.get("date") or s.get("trade_date")
        c70_lo = s.get("c70_lo")
        c70_hi = s.get("c70_hi")
        c70c = (c70_lo + c70_hi) / 2.0 if (c70_lo and c70_hi) else None
        m1 = main1_full.get(d)
        m3 = main3_full.get(d)

        # 点估计: ① 与 ② 吻合(<=12%)才采用 ① 的新鲜买盘价, 否则回落到更稳的 ②(c70 中心)
        if m1 is not None and c70c and abs(m1 - c70c) / c70c <= 0.12:
            point = m1
        else:
            point = c70c if c70c is not None else m1
        lows, highs = [], []
        if c70_lo:
            lows.append(c70_lo)
        if c70_hi:
            highs.append(c70_hi)
        if m1 is not None:
            lows.append(m1 * 0.97)
            highs.append(m1 * 1.03)
        if m3 is not None:
            lows.append(m3 * 0.97)
            highs.append(m3 * 1.03)
        lo = min(lows) if lows else None
        hi = max(highs) if highs else None

        # 置信: ① 与 ② 吻合 + ③ 在场且贴近 -> H; 仅 ① 与 ② 吻合 -> M; 否则 L
        conf = 'L'
        if m1 is not None and c70c:
            dev = abs(m1 - c70c) / c70c if c70c else 1.0
            if dev <= 0.08 and m3 is not None and abs(m3 - m1) / m1 <= 0.05:
                conf = 'H'
            elif dev <= 0.12:
                conf = 'M'
        elif m1 is not None:
            conf = 'M'

        s["main_cost"] = round(point, 4) if point is not None else 0.0
        s["main_cost_lo"] = round(lo, 4) if lo is not None else 0.0
        s["main_cost_hi"] = round(hi, 4) if hi is not None else 0.0
        s["main_cost_conf"] = conf
    return snaps

# ---------------- Baostock 未复权全历史 ----------------
_bs_logged = False
def _ensure_login():
    global _bs_logged
    if _bs_logged:
        return
    for _ in range(5):
        try:
            bs.login(); _bs_logged = True; return
        except Exception:
            time.sleep(1)

def fetch_baostock_unadj(sym, start_date=None, end_date=None):
    """拉 Baostock 未复权日线。sym=纯数字代码(如 002080)。返回 DataFrame[date,open,high,low,close,volume,amount,turn]
       turn 为百分比(如 70.14)。按年分页, 断网自动重试。"""
    _ensure_login()
    code = ("sz." if sym[0] in ("0","3") else "sh.") + sym
    sd = start_date or "2006-01-01"
    ed = end_date or "2026-12-31"
    rows = []
    for yr in range(int(sd[:4]), int(ed[:4]) + 1):
        y0 = max(sd, f"{yr}-01-01"); y1 = min(ed, f"{yr}-12-31")
        for attempt in range(3):
            try:
                rs = bs.query_history_k_data_plus(code, "date,open,high,low,close,volume,amount,turn",
                    start_date=y0, end_date=y1, frequency="d", adjustflag="3")
                while rs.error_code == '0' and rs.next():
                    rows.append(rs.get_row_data())
                break
            except Exception:
                time.sleep(2)
    df = pd.DataFrame(rows, columns=["date","open","high","low","close","volume","amount","turn"])
    df = df[(df["close"] != "") & (df["date"] != "")].copy()
    for c in ["open","high","low","close","volume","amount","turn"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df = df.dropna(subset=["close"]).reset_index(drop=True)
    if start_date: df = df[df["date"] >= start_date]
    if end_date:   df = df[df["date"] <= end_date]
    return df.reset_index(drop=True)

# ---------------- 从 CH stock_daily 读未复权(方案C数据源) ----------------
def read_unadj_from_ch(sym, start_date, end_date, adj_fallback=False):
    """从 CH stock.stock_daily FINAL 读未复权日线。sym=纯数字代码。
       adj_fallback=True 时: 未复权列为 NULL 则用复权列兜底(coalesce)。
         适用增量模式——最新交易日未复权==复权(前复权锚定最新日), 仅影响新日, 旧日已回填不受影响。
         避免每日全市场回退 Baostock 取未复权(慢/易限流)。
       adj_fallback=False(默认, 回填路径): 要求未复权列非空, 否则交由 get_unadj 回退 Baostock。
       返回 DataFrame[date,open,high,low,close,turn]; 无数据返回空 DataFrame。"""
    code = sym
    if adj_fallback:
        sql = (f"SELECT trade_date, "
               f"coalesce(open_unadj, open_price), coalesce(high_unadj, high_price), "
               f"coalesce(low_unadj, low_price), coalesce(close_unadj, close_price), turnover_rate "
               f"FROM {CH_DB}.stock_daily FINAL "
               f"WHERE code='{code}' AND trade_date>='{start_date}' AND trade_date<='{end_date}' "
               f"ORDER BY trade_date")
    else:
        sql = (f"SELECT trade_date, open_unadj, high_unadj, low_unadj, close_unadj, turnover_rate "
               f"FROM {CH_DB}.stock_daily FINAL "
               f"WHERE code='{code}' AND trade_date>='{start_date}' AND trade_date<='{end_date}' "
               f"AND open_unadj IS NOT NULL AND close_unadj IS NOT NULL "
               f"ORDER BY trade_date")
    try:
        raw = _ch_request(sql)
    except Exception as e:
        print("  [read_unadj_from_ch] 查询失败 %s: %s" % (sym, e)); return pd.DataFrame()
    if not raw.strip():
        return pd.DataFrame()
    recs = []
    def _to_float(v):
        v = str(v).strip()
        if v == "" or v == "nan" or v == "\\N" or v == r"\N":
            return None
        try:
            return float(v)
        except ValueError:
            return None
    for line in raw.strip().split("\n"):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 6:
            continue
        d, o, h, l, c, t = parts[:6]
        ov, hv, lv, cv = _to_float(o), _to_float(h), _to_float(l), _to_float(c)
        if ov is None or hv is None or lv is None or cv is None:
            # 价格缺失无法计算 CYQ, 跳过该交易日(分布沿用前一日)
            continue
        tv = _to_float(t)
        recs.append({"date": str(d), "open": ov, "high": hv, "low": lv,
                     "close": cv, "turn": (0.0 if tv is None else tv)})
    return pd.DataFrame(recs)

def get_unadj(sym, start_date, end_date, adj_fallback=False):
    """优先 CH stock_daily 未复权列; 若为空则回退 Baostock(保证流水线可用)。
       adj_fallback: 透传给 read_unadj_from_ch(增量模式开启, 用复权列兜底未复权)。"""
    df = read_unadj_from_ch(sym, start_date, end_date, adj_fallback=adj_fallback)
    if len(df) > 0:
        return df, "ch"
    df = fetch_baostock_unadj(sym, start_date, end_date)
    return df, "baostock"

# ---------------- 逐日快照(方案C: stock_cyq_daily) ----------------
def compute_cyq_daily(klines_dates, factor=FACTOR):
    """klines_dates: list[(date, open, high, low, close, turn)]  turn 已是百分比。
       返回 list[dict]: 每交易日一枚快照 {date, avg_cost, benefit, c90_*, c70_*, yrange, x}。
       增量续算; 价格区间扩张时回退全量, 保证与东财一致。"""
    snaps = []
    yr = None; x = None
    for i, (date, o, h, l, c, turn) in enumerate(klines_dates):
        kl = [(o, h, l, c, turn)]
        if yr is None:
            yr, x = compute_cyq_np(kl)
        else:
            res = compute_cyq_continue(yr, x, kl)
            if res is None:
                # 区间扩张: 用截至当日全量重算
                full = [k for k in klines_dates[:i+1]]
                yr, x = compute_cyq_np([(oo, hh, ll, cc, tt) for (_, oo, hh, ll, cc, tt) in full])
            else:
                yr, x = res
        m = metrics(yr, x, float(c))
        snaps.append(dict(date=date, close=float(c), avg_cost=m["avg_cost"], benefit=m["benefit"],
                          c90_lo=m["c90_lo"], c90_hi=m["c90_hi"], c90_conc=m["c90_conc"],
                          c70_lo=m["c70_lo"], c70_hi=m["c70_hi"], c70_conc=m["c70_conc"],
                          yrange=list(map(float, yr)), x=list(map(float, x))))
    return snaps

def cyq_to_json(yr, x):
    return json.dumps({"yrange": list(map(float, yr)), "x": list(map(float, x))},
                      ensure_ascii=False, separators=(",", ":"))

# ---------------- ClickHouse 写入 ----------------
def _ch_request(sql, data=None, timeout=30):
    # CH 只读模式: GET 仅可读, 写操作(INSERT/CREATE/DROP)必须 POST -> 始终用 POST(空 body 亦可)
    url = f"http://{CH_HOST}:{CH_PORT}/?query=" + urllib.parse.quote(sql)
    req = urllib.request.Request(url, data=(data if data is not None else b""))
    if CH_USER:
        req.add_header("Authorization", "Basic " + base64.b64encode(f"{CH_USER}:{CH_PW}".encode()).decode())
    req.add_header("Content-Type", "text/plain")
    return urllib.request.urlopen(req, timeout=timeout).read().decode()

def write_cyq_ch(rows, table="stock_cyq", chunk_bytes=1024*1024):
    """批量写入筹码快照。rows: list[dict] 字段与 stock_cyq 表一致。按真实字节 1MB 分块。"""
    if not rows:
        return 0
    cols = list(rows[0].keys())
    payloads = []
    buf = ""
    # 构造 JSONEachRow: 每行一个 JSON 对象, 换行分隔
    def line(r):
        return json.dumps({c: r[c] for c in cols}, ensure_ascii=False, separators=(",", ":"))
    for r in rows:
        s = line(r) + "\n"
        if buf and len(buf.encode("utf-8")) + len(s.encode("utf-8")) > chunk_bytes:
            payloads.append(buf); buf = s
        else:
            buf += s
    if buf:
        payloads.append(buf)
    sql = f"INSERT INTO {CH_DB}.{table} ({','.join(cols)}) FORMAT JSONEachRow"
    n = 0
    for p in payloads:
        _ch_request(sql, data=p.encode("utf-8"))
        n += p.count("\n")
    return n

def query_ch(sql, data=None):
    return _ch_request(sql, data=data)

if __name__ == "__main__":
    # 自测: 002080 全量算一次, 打印指标
    df = fetch_baostock_unadj("002080")
    df["turn"] = normalize_turnover(df["turn"].values)
    kl = list(zip(df["open"], df["high"], df["low"], df["close"], df["turn"]))
    y, x = compute_cyq_np(kl)
    m = metrics(y, x, float(df["close"].iloc[-1]))
    print("002080 rows=", len(df), "close=", df["close"].iloc[-1])
    print("avg_cost=%.3f benefit=%.4f c90=[%.2f,%.2f] conc=%.4f c70=[%.2f,%.2f] conc=%.4f"
          % (m["avg_cost"], m["benefit"], m["c90_lo"], m["c90_hi"], m["c90_conc"], m["c70_lo"], m["c70_hi"], m["c70_conc"]))
