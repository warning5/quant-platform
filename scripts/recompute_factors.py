"""
全量重算 factor_value 表 — 高效 Python 实现
替代 Java FactorComputeEngine 的 Demo 数据

策略:
  1. 一次加载所有 stock_daily 数据到内存（按 code 分组，时间排序）
  2. 逐股票、逐因子计算，批量 INSERT
  3. 横截面归一化（Z-Score + 百分位排名）

因子分类:
  技术因子 (TECHNICAL, MOMENTUM, VOLATILITY):
    MOM20, MOM60, VOL20, TURN20, SIZE, RSI5, BOLL_POS, VPCORR20
    VOL5, VOL60, VOL_RATIO, AMIHUD, TURNOVER_CHANGE, VOLUME_RATIO

  财务因子 (FUNDAMENTAL) — 从 stock_financial_indicator 加载:
    盈利能力: GROSS_MARGIN, NET_MARGIN, ROE, ROA, EBIT_MARGIN,
              TOTAL_COST_RATIO, PERIOD_EXPENSE_RATIO
    成长能力: REVENUE_YOY, NET_PROFIT_YOY, OPERATING_PROFIT_YOY,
              TOTAL_ASSETS_YOY, EPS_YOY
    偿债能力: CURRENT_RATIO, QUICK_RATIO, DEBT_TO_ASSET, DEBT_TO_EQUITY
    营运能力: AR_TURNOVER, AR_TURNOVER_DAYS, ASSETS_TURNOVER,
              INVENTORY_TURNOVER, INVENTORY_TURNOVER_DAYS
    现金流:   CF_TO_NP, CF_PER_SHARE, CF_TO_REVENUE,
              FCF, FCF_TO_OPCF, FCF_TO_NP
    每股指标: BPS

用法:
  python scripts/recompute_factors.py                              # 全部技术+波动率因子
  python scripts/recompute_factors.py --factors MOM20 MOM60         # 指定因子
  python scripts/recompute_factors.py --fin                        # 财务因子
  python scripts/recompute_factors.py --all-factors             # 全部因子(技术+财务)
  python scripts/recompute_factors.py --all-factors --incremental  # 增量: 只补新增日期
  python scripts/recompute_factors.py --start-date 2024-01-01
  python scripts/recompute_factors.py --dry-run
"""

import argparse
import math
import sys
import time
from collections import defaultdict
from datetime import date

import pymysql
import clickhouse_connect

from db_config import CLICKHOUSE_CONFIG, MYSQL_CONFIG

# ---- 因子分组 ----
TECH_FACTORS = ["MOM20", "MOM60", "VOL20", "TURN20", "SIZE", "RSI5", "BOLL_POS", "VPCORR20"]
VOL_LIQ_FACTORS = ["VOL5", "VOL60", "VOL_RATIO", "AMIHUD", "TURNOVER_CHANGE", "VOLUME_RATIO"]
ALL_TECH_FACTORS = TECH_FACTORS + VOL_LIQ_FACTORS  # 14个日频技术/波动率/流动性因子

# 财务因子（季频，从 stock_financial_indicator 加载）
# report_type: 1=Q1, 2=中报, 3=三季报, 4=年报
# 因子计算优先用年报(report_type=4)，数据最全最准；年报未出时用最新季报补充
FIN_FACTORS = [
    # 盈利能力 (7)
    "FIN_GROSS_MARGIN",      # 毛利率
    "FIN_NET_MARGIN",        # 净利率
    "FIN_ROE",               # 净资产收益率
    "FIN_ROA",               # 总资产收益率
    "FIN_ROIC",              # 投入资本回报率（新增）
    "FIN_EBIT_MARGIN",       # EBIT利润率
    "FIN_TOTAL_COST_RATIO",  # 营业成本率
    "FIN_PERIOD_EXPENSE_RATIO",  # 期间费用率
    # 成长能力 (5)
    "FIN_REVENUE_YOY",       # 营收同比增长率
    "FIN_NET_PROFIT_YOY",    # 净利润同比增长率
    "FIN_OPERATING_PROFIT_YOY",  # 营业利润同比增长率
    "FIN_TOTAL_ASSETS_YOY",  # 总资产同比增长率
    "FIN_EPS_YOY",           # 每股收益同比增长率
    # 偿债能力 (5)  — 新增 interest_coverage_ratio
    "FIN_CURRENT_RATIO",     # 流动比率
    "FIN_QUICK_RATIO",       # 速动比率
    "FIN_DEBT_TO_ASSET",     # 资产负债率
    "FIN_DEBT_TO_EQUITY",    # 产权比率
    "FIN_INTEREST_COVERAGE", # 利息保障倍数（新增）
    # 营运能力 (5)
    "FIN_AR_TURNOVER",       # 应收账款周转率
    "FIN_AR_TURNOVER_DAYS",  # 应收账款周转天数
    "FIN_ASSETS_TURNOVER",   # 总资产周转率
    "FIN_INVENTORY_TURNOVER",    # 存货周转率
    "FIN_INVENTORY_TURNOVER_DAYS",# 存货周转天数
    # 现金流 (7)  — 新增 operating_cf_to_debt
    "FIN_CF_TO_NP",          # 经营现金流/净利润
    "FIN_CF_PER_SHARE",      # 每股经营现金流
    "FIN_CF_TO_REVENUE",     # 经营现金流/营收
    "FIN_FCF",               # 自由现金流
    "FIN_FCF_TO_OPCF",       # FCF/经营CF
    "FIN_FCF_TO_NP",         # FCF/净利润
    "FIN_OPERATING_CF_TO_DEBT", # 经营现金流/总负债（新增）
    # 每股指标 (1)
    "FIN_BPS",               # 每股净资产
]  # 共32个财务因子


def load_stock_daily():
    """加载全部 stock_daily，按 code 分组，按 trade_date 排序"""
    print("[1/5] 加载 stock_daily 数据...")
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
    mysql_conn = pymysql.connect(**MYSQL_CONFIG)

    try:
        mysql_cur = mysql_conn.cursor()
        mysql_cur.execute("SELECT code, total_market_cap FROM stock_info WHERE total_market_cap IS NOT NULL AND total_market_cap > 0")
        info_mcap = {code: float(mcap) / 10000 for code, mcap in mysql_cur.fetchall()}
        mysql_cur.close()

        result = ch_client.query("""
            SELECT code, trade_date, close_price, turnover_rate, volume
            FROM stock_daily FINAL
            WHERE close_price IS NOT NULL AND close_price > 0
            ORDER BY code, trade_date
        """)
        rows = result.result_rows

        data = defaultdict(list)
        for code, td, close, turnover, vol in rows:
            data[code].append({
                "date": td,
                "close": float(close),
                "turnover": float(turnover) if turnover else 0.0,
                "volume": float(vol) if vol else 0.0,
                "market_cap": info_mcap.get(code, 0.0),
            })
        print(f"  加载 {len(rows):,} 条, {len(data)} 只股票")
        return data
    finally:
        ch_client.close()
        mysql_conn.close()


def load_code_market():
    """加载 code -> market 映射"""
    conn = pymysql.connect(**MYSQL_CONFIG)
    try:
        cur = conn.cursor()
        cur.execute("SELECT code, market FROM stock_info")
        result = {code: market for code, market in cur.fetchall()}
        cur.close()
        return result
    finally:
        conn.close()


def load_financial_data():
    """
    加载 stock_financial_indicator 全部报告期数据 + 联查 stock_income 计算派生指标
    返回: {code: [report_dict, ...]} 按 end_date 排序
    取所有报告期(report_type IN (1,2,3,4))，覆盖最新财报（含一季报）
    """
    print("[2/5] 加载财务指标数据...")
    conn = pymysql.connect(**MYSQL_CONFIG)
    try:
        cur = conn.cursor()
        # 主表: financial indicator
        # report_type: 1=Q1, 2=中报, 3=三季报, 4=年报
        # 取所有报告期，保证财务因子覆盖最新财报（含2026Q1）
        cur.execute("""
            SELECT fi.code, fi.report_date, fi.report_type, fi.end_date,
                   fi.gross_profit_margin, fi.net_profit_margin, fi.roe, fi.roa,
                   fi.revenue_yoy, fi.net_profit_yoy, fi.operating_profit_yoy,
                   fi.total_assets_yoy,
                   fi.current_ratio, fi.quick_ratio,
                   fi.debt_to_asset_ratio, fi.debt_to_equity_ratio,
                   fi.accounts_receivable_turnover, fi.ar_turnover_days,
                   fi.inventory_turnover, fi.inventory_turnover_days,
                   fi.total_assets_turnover,
                   fi.operating_cf_to_np, fi.free_cash_flow, fi.net_operate_cf,
                   fi.bps, fi.eps_basic,
                   fi.interest_coverage_ratio, fi.roic, fi.operating_cf_to_debt,
                   si.total_revenue, si.total_cost, si.operating_cost,
                   si.operating_profit, si.selling_expense,
                   si.admin_expense, si.finance_expense, si.rd_expense,
                   si.net_profit
            FROM stock_financial_indicator fi
            LEFT JOIN stock_income si ON fi.code = si.code
                AND fi.report_date = si.report_date
                AND si.report_type = fi.report_type
            WHERE fi.report_type IN (1, 2, 3, 4)
            ORDER BY fi.code, fi.end_date
        """)
        cols = [d[0] for d in cur.description]
        rows = cur.fetchall()

        data = defaultdict(list)
        for row in rows:
            d = dict(zip(cols, row))
            code = d["code"]
            data[code].append(d)

        print(f"  加载 {len(rows):,} 条年报数据, {len(data)} 只股票")
        return data
    finally:
        conn.close()


# ==================== 日频技术/波动率/流动性因子计算 ====================

def _calc_volatility(history, period):
    """通用年化波动率计算"""
    if len(history) < period + 1:
        return None
    window = history[-(period + 1):]
    returns = []
    for i in range(period):
        prev = window[i]["close"]
        curr = window[i + 1]["close"]
        if prev <= 0:
            return None
        returns.append(math.log(curr / prev))
    if not returns:
        return None
    mean_r = sum(returns) / len(returns)
    var = sum((r - mean_r) ** 2 for r in returns) / (len(returns) - 1)
    return math.sqrt(var) * math.sqrt(252)


def calc_mom(history, period):
    """动量因子"""
    if len(history) < period + 1:
        return None
    latest = history[-1]["close"]
    past = history[-(period + 1)]["close"]
    if past == 0:
        return None
    return (latest - past) / past


def calc_vol20(history):  return _calc_volatility(history, 20)

def calc_turn20(history):
    """20日平均换手率"""
    if len(history) < 20:
        return None
    window = history[-20:]
    return sum(d["turnover"] for d in window) / 20


def calc_size(history):
    """市值因子 = log(market_cap)"""
    if not history:
        return None
    mc = history[-1]["market_cap"]
    if mc <= 0:
        return None
    return math.log(mc)


def calc_rsi5(history):
    """5日RSI"""
    if len(history) < 6:
        return None
    window = history[-6:]
    gains = losses = 0.0
    for i in range(1, len(window)):
        change = window[i]["close"] - window[i - 1]["close"]
        if change > 0:
            gains += change
        else:
            losses += abs(change)
    if losses == 0:
        return 100.0
    rs = gains / losses
    return 100 - (100 / (1 + rs))


def calc_boll_pos(history):
    """布林带位置"""
    if len(history) < 20:
        return None
    window = history[-20:]
    closes = [d["close"] for d in window]
    mean = sum(closes) / len(closes)
    std = math.sqrt(sum((c - mean) ** 2 for c in closes) / len(closes))
    if std == 0:
        return 0.5
    current = closes[-1]
    upper = mean + 2 * std
    lower = mean - 2 * std
    return (current - lower) / (upper - lower)


def calc_vp_corr20(history):
    """20日量价 Pearson 相关"""
    if len(history) < 20:
        return None
    window = history[-20:]
    prices = [d["close"] for d in window]
    vols = [d["volume"] for d in window]
    n = len(prices)
    sx, sy, sxy, sx2, sy2 = sum(prices), sum(vols), sum(p * v for p, v in zip(prices, vols)), sum(p * p for p in prices), sum(v * v for v in vols)
    num = n * sxy - sx * sy
    den_sq = (n * sx2 - sx ** 2) * (n * sy2 - sy ** 2)
    if den_sq <= 0:
        return 0.0
    den = math.sqrt(den_sq)
    return num / den if den != 0 else 0.0


# ---- 第一批新增：波动率/流动性因子 (ID 13-18) ----

def calc_vol5(history):
    """5日年化波动率"""
    return _calc_volatility(history, 5)


def calc_vol60(history):
    """60日年化波动率"""
    return _calc_volatility(history, 60)


def calc_vol_ratio(history):
    """波动率比 = 短期5日 / 长期60日"""
    v5 = calc_vol5(history)
    v60 = calc_vol60(history)
    if v5 is None or v60 is None or v60 == 0:
        return None
    return v5 / v60


def calc_amihud(history):
    """
    Amihud非流动性 = mean(|return| / amount) 对数
    用 volume * close_price 近似成交额
    """
    if len(history) < 21:
        return None
    window = history[-21:]  # 取20个交易日
    illiq_list = []
    for i in range(1, len(window)):
        prev_c = window[i - 1]["close"]
        curr_c = window[i]["close"]
        vol = window[i]["volume"]
        if prev_c <= 0 or vol <= 0:
            continue
        ret = abs(curr_c - prev_c) / prev_c
        amount = vol * prev_c  # 成交额近似
        if amount > 0:
            illiq_list.append(ret / amount)
    if not illiq_list:
        return None
    avg_illiq = sum(illiq_list) / len(illiq_list)
    return math.log(avg_illiq + 1e-10)


def calc_turnover_change(history):
    """换手率变化 = 近5日均换手 / 前20日均换手"""
    if len(history) < 25:
        return None
    recent = sum(d["turnover"] for d in history[-5:]) / 5
    prior = sum(d["turnover"] for d in history[-25:-5]) / 20
    if prior == 0:
        return None
    return recent / prior


def calc_volume_ratio(history):
    """量比 = 近5日均量 / 前20日均量"""
    if len(history) < 25:
        return None
    recent = sum(d["volume"] for d in history[-5:]) / 5
    prior = sum(d["volume"] for d in history[-25:-5]) / 20
    if prior == 0:
        return None
    return recent / prior


# ---- 日频因子函数注册表 ----
TECH_FACTOR_FUNCS = {
    "MOM20": lambda h: calc_mom(h, 20),
    "MOM60": lambda h: calc_mom(h, 60),
    "VOL20": calc_vol20,
    "TURN20": calc_turn20,
    "SIZE": calc_size,
    "RSI5": calc_rsi5,
    "BOLL_POS": calc_boll_pos,
    "VPCORR20": calc_vp_corr20,
    # 第一批新增
    "VOL5": calc_vol5,
    "VOL60": calc_vol60,
    "VOL_RATIO": calc_vol_ratio,
    "AMIHUD": calc_amihud,
    "TURNOVER_CHANGE": calc_turnover_change,
    "VOLUME_RATIO": calc_volume_ratio,
}


# ==================== 季频财务因子计算 ====================

def _fval(row, col):
    """安全取浮点值，NULL/异常返回 None"""
    val = row.get(col)
    if val is None:
        return None
    try:
        f = float(val)
        return f if not (math.isnan(f) or math.isinf(f)) else None
    except (ValueError, TypeError):
        return None


def compute_finance_factors(fin_data, fin_factor_codes, start_date, end_date):
    """
    计算财务因子（季频，基于财报报告日）
    返回: {factor_code: [(symbol, calc_date, factor_val), ...]}
    财务因子特点：
    - 不是日频，而是基于 report_date（季度末日期）
    - 一只股票一年只有4条数据（4个季度），但只取年报
    - 横截面归一化在同一 report_date 下进行
    """
    print(f"[3/5] 计算财务因子: {fin_factor_codes}")
    results = {f: [] for f in fin_factor_codes}
    processed = 0
    start_t = time.time()

    # 财务字段映射: factor_code -> (提取函数, 描述)
    FIN_EXTRACTORS = {
        # 盈利能力
        "FIN_GROSS_MARGIN":          (lambda r: _fval(r, "gross_profit_margin"),           "毛利率"),
        "FIN_NET_MARGIN":            (lambda r: _fval(r, "net_profit_margin"),             "净利率"),
        "FIN_ROE":                   (lambda r: _fval(r, "roe"),                          "ROE"),
        "FIN_ROA":                   (lambda r: _fval(r, "roa"),                          "ROA"),
        "FIN_EBIT_MARGIN":           (lambda r: _calc_ebit_margin(r),                     "EBIT利润率"),
        "FIN_TOTAL_COST_RATIO":      (lambda r: _calc_cost_ratio(r, "total_cost"),         "营业成本率"),
        "FIN_PERIOD_EXPENSE_RATIO":  (lambda r: _calc_period_expense_ratio(r),             "期间费用率"),
        "FIN_ROIC":                  (lambda r: _fval(r, "roic"),                       "ROIC"),
        # 成长能力
        "FIN_REVENUE_YOY":           (lambda r: _fval(r, "revenue_yoy"),                  "营收增速"),
        "FIN_NET_PROFIT_YOY":        (lambda r: _fval(r, "net_profit_yoy"),               "净利润增速"),
        "FIN_OPERATING_PROFIT_YOY":  (lambda r: _fval(r, "operating_profit_yoy"),          "营业利润增速"),
        "FIN_TOTAL_ASSETS_YOY":      (lambda r: _fval(r, "total_assets_yoy"),            "总资产增速"),  # 字段名修正
        "FIN_EPS_YOY":               (lambda r: _calc_eps_yoy(r),                         "EPS增速"),
        # 偿债能力 (5)  — 新增 interest_coverage_ratio
        "FIN_CURRENT_RATIO":         (lambda r: _fval(r, "current_ratio"),                "流动比率"),
        "FIN_QUICK_RATIO":           (lambda r: _fval(r, "quick_ratio"),                  "速动比率"),
        "FIN_DEBT_TO_ASSET":         (lambda r: _fval(r, "debt_to_asset_ratio"),          "资产负债率"),
        "FIN_DEBT_TO_EQUITY":        (lambda r: _fval(r, "debt_to_equity_ratio"),         "产权比率"),
        "FIN_INTEREST_COVERAGE":     (lambda r: _fval(r, "interest_coverage_ratio"),     "利息保障倍数"),
        # 营运能力
        "FIN_AR_TURNOVER":           (lambda r: _fval(r, "accounts_receivable_turnover"),  "应收周转率"),
        "FIN_AR_TURNOVER_DAYS":      (lambda r: _fval(r, "ar_turnover_days"),             "应收周转天数"),
        "FIN_ASSETS_TURNOVER":       (lambda r: _fval(r, "total_assets_turnover"),        "总资产周转率"),
        "FIN_INVENTORY_TURNOVER":    (lambda r: _fval(r, "inventory_turnover"),           "存货周转率"),
        "FIN_INVENTORY_TURNOVER_DAYS":(lambda r: _fval(r, "inventory_turnover_days"),     "存货周转天数"),
        # 现金流 (7)  — 新增 operating_cf_to_debt
        "FIN_CF_TO_NP":              (lambda r: _fval(r, "operating_cf_to_np"),           "经营CF/净利润"),
        "FIN_CF_PER_SHARE":          (lambda r: _fval(r, "net_operate_cf") / _fval(r, "eps_basic") if _fval(r, "eps_basic") and _fval(r, "eps_basic") != 0 else None, "每股经营CF"),
        "FIN_CF_TO_REVENUE":         (lambda r: _cf_div(r, "net_operate_cf", "total_revenue"), "经营CF/营收"),
        "FIN_FCF":                   (lambda r: _fval(r, "free_cash_flow"),               "FCF"),
        "FIN_FCF_TO_OPCF":           (lambda r: _cf_div(r, "free_cash_flow", "net_operate_cf"), "FCF/经营CF"),
        "FIN_FCF_TO_NP":             (lambda r: _cf_div(r, "free_cash_flow", "net_profit") if _fval(r, "net_profit") is not None else None, "FCF/净利润"),
        "FIN_OPERATING_CF_TO_DEBT":  (lambda r: _fval(r, "operating_cf_to_debt"),        "经营CF/总负债"),
        # 每股指标
        "FIN_BPS":                   (lambda r: _fval(r, "bps"),                          "每股净资产"),
    }

    for code, reports in fin_data.items():
        for rpt in reports:
            rd = rpt.get("end_date") or rpt.get("report_date")
            if rd is None:
                continue
            # 日期过滤
            if isinstance(rd, date):
                calc_d = rd
            elif isinstance(rd, str):
                calc_d = date.fromisoformat(str(rd)[:10])
            else:
                continue
            if calc_d < start_date or calc_d > end_date:
                continue

            for fc in fin_factor_codes:
                if fc not in FIN_EXTRACTORS:
                    continue
                extractor, _ = FIN_EXTRACTORS[fc]
                try:
                    val = extractor(rpt)
                    if val is not None and not (math.isnan(val) if isinstance(val, float) else False):
                        results[fc].append((code, calc_d, val))
                except Exception:
                    pass

        processed += 1
        if processed % 500 == 0:
            elapsed = time.time() - start_t
            print(f"  进度: {processed}/{len(fin_data)} 股票")

    elapsed = time.time() - start_t
    for fc in fin_factor_codes:
        print(f"  {fc}: {len(results[fc]):,} 条  ({elapsed:.1f}s)")
    return results


# ---- 财务因子辅助计算函数 ----

def _calc_ebit_margin(row):
    """EBIT利润率 = (营业利润 + 财务费用) / 营业收入"""
    op_profit = _fval(row, "operating_profit")
    fin_expense = _fval(row, "finance_expense")
    revenue = _fval(row, "total_revenue")
    if op_profit is None or revenue is None or revenue == 0:
        return None
    ebit = op_profit + (fin_expense or 0)
    return ebit / revenue


def _calc_cost_ratio(row, cost_col):
    """成本率 = cost / revenue"""
    cost = _fval(row, cost_col)
    revenue = _fval(row, "total_revenue")
    if cost is None or revenue is None or revenue == 0:
        return None
    return cost / revenue


def _calc_period_expense_ratio(row):
    """期间费用率 = (销售费用 + 管理费用 + 财务费用 + 研发费用) / 营业收入"""
    selling = _fval(row, "selling_expense") or 0
    admin = _fval(row, "admin_expense") or 0
    finance = _fval(row, "finance_expense") or 0
    rd = _fval(row, "rd_expense") or 0
    revenue = _fval(row, "total_revenue")
    if revenue is None or revenue == 0:
        return None
    return (selling + admin + finance + rd) / revenue


def _calc_eps_yoy(row):
    """EPS增速：表中无直接字段，用净利润增速近似或跳过"""
    # 表中无 eps_yoy 字段，用 net_profit_yoy 近似
    return _fval(row, "net_profit_yoy")


def _cf_div(row, numer_col, denom_col):
    """安全除法：现金流类比值"""
    n = _fval(row, numer_col)
    d = _fval(row, denom_col)
    if n is None or d is None or d == 0:
        return None
    return n / d


# ==================== 日频因子计算主流程 ====================

def compute_tech_factors(stock_data, tech_factors, start_date, end_date):
    """计算日频技术/波动率/流动性因子"""
    print(f"[3/5] 计算日频因子: {tech_factors}")
    results = {f: [] for f in tech_factors}
    total_stocks = len(stock_data)
    processed = 0
    start_time = time.time()

    for code, history in stock_data.items():
        for i, bar in enumerate(history):
            td = bar["date"]
            if td < start_date:
                continue
            if td > end_date:
                break

            hist_slice = history[:i + 1]
            for fc in tech_factors:
                func = TECH_FACTOR_FUNCS.get(fc)
                if func is None:
                    continue
                val = func(hist_slice)
                if val is not None and not (math.isnan(val) or math.isinf(val)):
                    results[fc].append((code, td, val))

        processed += 1
        if processed % 500 == 0:
            elapsed = time.time() - start_time
            speed = processed / elapsed if elapsed > 0 else 0
            print(f"  进度: {processed}/{total_stocks} 股票  ({speed:.0f} 只/秒)")

    elapsed = time.time() - start_time
    for fc in tech_factors:
        print(f"  {fc}: {len(results[fc]):,} 条  (耗时 {elapsed:.1f}s)")
    return results


# ==================== 写入 ClickHouse ====================

def normalize_and_write(factor_data, code_market_map, batch_size=5000):
    """
    横截面归一化: Z-Score + 百分位排名
    写入 ClickHouse factor_value 表
    """
    print("[4/5] 横截面归一化并写入数据库...")
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

    try:
        for fc, values in factor_data.items():
            if not values:
                print(f"  {fc}: 无数据, 跳过")
                continue

            # 按日期分组
            date_groups = defaultdict(list)
            for code, td, val in values:
                date_groups[td].append((code, val))

            batch = []
            total_written = 0

            for td in sorted(date_groups.keys()):
                items = date_groups[td]
                raw_vals = [v for _, v in items]

                mean = sum(raw_vals) / len(raw_vals)
                std = math.sqrt(sum((v - mean) ** 2 for v in raw_vals) / len(raw_vals)) if len(raw_vals) > 1 else 0
                sorted_vals = sorted(raw_vals)

                for code, val in items:
                    z = 0.0 if std == 0 else (val - mean) / std
                    rank = sorted_vals.index(val)
                    pct = rank / (len(sorted_vals) - 1) if len(sorted_vals) > 1 else 0.5

                    market = code_market_map.get(code, "")
                    symbol = f"{code}.{market}" if market else code

                    batch.append((fc, symbol, td, round(val, 8), round(pct, 6), round(z, 6)))

                    if len(batch) >= batch_size:
                        _insert_batch(ch_client, batch)
                        total_written += len(batch)
                        batch = []

            if batch:
                _insert_batch(ch_client, batch)
                total_written += len(batch)

            print(f"  {fc}: 写入 {total_written:,} 条")
    finally:
        ch_client.close()


def _insert_batch(ch_client, batch):
    ch_client.insert("factor_value", batch, column_names=["factor_code", "symbol", "calc_date", "factor_val", "rank_value", "z_score"])


def clear_old_factors(factor_codes):
    """清空指定因子的旧 CH 数据"""
    print("[4/5] 清空旧 factor_value 数据...")
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
    try:
        for fc in factor_codes:
            result = ch_client.query(f"SELECT count() FROM factor_value FINAL WHERE factor_code = '{fc}'")
            count = result.first_row[0]
            if count > 0:
                ch_client.command(f"ALTER TABLE factor_value DELETE WHERE factor_code = '{fc}'")
                print(f"  删除 {fc}: {count:,} 条")
        print("  注意: ClickHouse 删除是异步的")
    finally:
        ch_client.close()


def get_last_computed_dates(factor_codes):
    """
    查询 CH factor_value 表，返回各因子已计算到的最新日期。
    用于增量计算: 从该日期之后开始，只补新增部分。
    """
    if not factor_codes:
        return {}
    ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
    try:
        codes_str = "', '".join(factor_codes)
        result = ch_client.query(f"""
            SELECT factor_code, max(calc_date) as last_date
            FROM factor_value
            WHERE factor_code IN ('{codes_str}')
            GROUP BY factor_code
        """)
        return {row[0]: row[1] for row in result.result_rows}
    finally:
        ch_client.close()


# ==================== Main ====================

def main():
    parser = argparse.ArgumentParser(description="全量重算因子值")
    parser.add_argument("--factors", nargs="+", default=None, help="指定要计算的因子代码列表")
    parser.add_argument("--fin", action="store_true", help="只计算财务因子")
    parser.add_argument("--tech", action="store_true", help="只计算技术/波动率/流动性因子")
    parser.add_argument("--all-factors", action="store_true", dest="all_factors", help="计算全部因子(技术+财务)")
    parser.add_argument("--start-date", default="2025-01-02", help="起始日期 (YYYY-MM-DD), 默认=2025-01-02")
    parser.add_argument("--end-date", default=None, help="结束日期 (YYYY-MM-DD), 默认=数据最新日")
    parser.add_argument("--batch-size", type=int, default=5000, help="INSERT 批量大小")
    parser.add_argument("--dry-run", action="store_true", help="只计算不写入")
    parser.add_argument("--skip-clear", action="store_true", help="不清空旧数据")
    parser.add_argument("--incremental", action="store_true", help="增量模式: 自动从各因子最新已有日期开始计算，不清空旧数据")
    args = parser.parse_args()

    start_date = date.fromisoformat(args.start_date)
    end_date = date.fromisoformat(args.end_date) if args.end_date else None

    # ---- 确定要算的因子 ----
    if args.factors:
        target_factors = args.factors
    elif args.fin:
        target_factors = list(FIN_FACTORS)
    elif args.tech:
        target_factors = list(ALL_TECH_FACTORS)
    elif args.all_factors:
        target_factors = ALL_TECH_FACTORS + FIN_FACTORS
    else:
        target_factors = list(ALL_TECH_FACTORS)  # 默认只算技术因子

    # 分离日频 vs 财务
    tech_fc = [f for f in target_factors if f in TECH_FACTOR_FUNCS]
    fin_fc = [f for f in target_factors if f in FIN_FACTORS]

    # ---- 增量模式: 自动从各因子最新已有日期开始 ----
    if args.incremental:
        last_dates = get_last_computed_dates(target_factors)
        if last_dates:
            from datetime import timedelta
            # 日频因子: 取所有技术因子的最新日期（统一用最早的那个，避免漏算）
            tech_last = min((last_dates[f] for f in tech_fc if f in last_dates), default=None)
            if tech_last:
                # 从最新日期的下一个交易日开始算（避免重复）
                # stock_daily 是交易日历，这里简化处理：加1天然后让 compute_tech_factors 自己过滤
                inc_start = tech_last + timedelta(days=1)
                if inc_start > start_date:
                    start_date = inc_start
                    print(f"[增量] 日频因子从 {start_date} 开始 (之前最新: {tech_last})")
            else:
                print("[增量] 未找到已有日频因子数据，将全量计算")
        else:
            print("[增量] 未找到已有因子数据，将全量计算")

    print(f"\n{'='*60}")
    print(f"因子计算任务:")
    print(f"  日频因子({len(tech_fc)}): {tech_fc}")
    print(f"  财务因子({len(fin_fc)}): {fin_fc}")
    print(f"  日期范围: {start_date} ~ {args.end_date or '最新'}")
    print(f"  模式: {'增量' if args.incremental else '全量'}")
    print(f"{'='*60}\n")

    # 增量模式下不执行 clear_old_factors（skip-clear=True）
    do_clear = not args.skip_clear and not args.incremental

    code_market_map = load_code_market()
    all_results = {}

    # ---- 日频因子 ----
    if tech_fc:
        stock_data = load_stock_daily()
        if end_date is None:
            max_date = max(h[-1]["date"] for h in stock_data.values())
            end_date = max_date
            print(f"  日线数据范围: ~{end_date}")

        factor_data = compute_tech_factors(stock_data, tech_fc, start_date, end_date)

        if args.dry_run:
            print("\n[DRY-RUN] 日频因子结果:")
            for fc, vals in factor_data.items():
                print(f"  {fc}: {len(vals):,} 条")
            all_results.update(factor_data)
        else:
            if do_clear:
                clear_old_factors(tech_fc)
            normalize_and_write(factor_data, code_market_map, batch_size=args.batch_size)

    # ---- 财务因子 ----
    if fin_fc:
        fin_data = load_financial_data()
        # 财务因子的 end_date 如果未指定，默认到当前
        fin_end = end_date or date.today()
        fin_factor_data = compute_finance_factors(fin_data, fin_fc, start_date, fin_end)

        if args.dry_run:
            print("\n[DRY-RUN] 财务因子结果:")
            for fc, vals in fin_factor_data.items():
                print(f"  {fc}: {len(vals):,} 条")
            all_results.update(fin_factor_data)
        else:
            if do_clear:
                clear_old_factors(fin_fc)
            normalize_and_write(fin_factor_data, code_market_map, batch_size=args.batch_size)

    mode_str = "增量" if args.incremental else "全量"
    print(f"\n[DONE] 因子计算完成 ({mode_str}模式)!")


if __name__ == "__main__":
    main()
