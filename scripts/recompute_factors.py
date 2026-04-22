"""
全量重算 factor_value 表 — 高效 Python 实现
替代 Java FactorComputeEngine 的 Demo 数据

策略:
  1. 一次加载所有 stock_daily 数据到内存（按 code 分组，时间排序）
  2. 逐股票、逐因子计算，批量 INSERT
  3. 横截面归一化（Z-Score + 百分位排名）

因子列表 (与 BuiltinFactors.java 保持一致):
  - MOM20:  20日动量 = (close[-1] - close[-21]) / close[-21]
  - MOM60:  60日动量 = (close[-1] - close[-61]) / close[-61]
  - VOL20:  20日年化波动率 = std(ln_returns[-20:]) * sqrt(252)
  - TURN20: 20日平均换手率 = mean(turnover_rate[-20:])
  - SIZE:   市值因子 = log(market_cap)
  - RSI5:   5日RSI
  - BOLL_POS: 布林带位置 = (price - lower) / (upper - lower)
  - VPCORR20: 20日量价相关性 = pearson(close[-20:], volume[-20:])

用法:
  python update_data/recompute_factors.py
  python update_data/recompute_factors.py --factors MOM20 MOM60
  python update_data/recompute_factors.py --start-date 2024-01-01 --end-date 2026-04-14
  python update_data/recompute_factors.py --dry-run
"""

import argparse
import math
import sys
import time
from collections import defaultdict
from datetime import date

import pymysql

DB_CONFIG = {
    "host": "localhost",
    "port": 3306,
    "user": "root",
    "password": "123456",
    "database": "stock",
    "charset": "utf8mb4",
}

ALL_FACTORS = ["MOM20", "MOM60", "VOL20", "TURN20", "SIZE", "RSI5", "BOLL_POS", "VPCORR20"]


def load_stock_daily(conn):
    """加载全部 stock_daily，按 code 分组，按 trade_date 排序"""
    print("[1/4] 加载 stock_daily 数据...")
    cur = conn.cursor()
    cur.execute("""
        SELECT code, trade_date, close_price, turnover_rate, volume, market_cap
        FROM stock_daily
        WHERE close_price IS NOT NULL AND close_price > 0
        ORDER BY code, trade_date
    """)
    rows = cur.fetchall()
    # 按 code 分组
    data = defaultdict(list)
    for code, td, close, turnover, vol, mcap in rows:
        data[code].append({
            "date": td,
            "close": float(close),
            "turnover": float(turnover) if turnover else 0.0,
            "volume": float(vol) if vol else 0.0,
            "market_cap": float(mcap) if mcap else 0.0,
        })
    print(f"  加载 {len(rows):,} 条, {len(data)} 只股票")
    return data


def load_code_market(conn):
    """加载 code -> market 映射"""
    cur = conn.cursor()
    cur.execute("SELECT code, market FROM stock_info")
    return {code: market for code, market in cur.fetchall()}


# ==================== 因子计算函数 ====================

def calc_mom(history, period):
    """动量因子"""
    if len(history) < period + 1:
        return None
    latest = history[-1]["close"]
    past = history[-(period + 1)]["close"]
    if past == 0:
        return None
    return (latest - past) / past


def calc_vol20(history):
    """20日年化波动率"""
    if len(history) < 21:
        return None
    window = history[-21:]
    returns = []
    for i in range(20):
        prev = window[i]["close"]
        curr = window[i + 1]["close"]
        if prev <= 0:
            return None
        returns.append(math.log(curr / prev))
    if not returns:
        return None
    mean = sum(returns) / len(returns)
    var = sum((r - mean) ** 2 for r in returns) / (len(returns) - 1)
    return math.sqrt(var) * math.sqrt(252)


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
    gains = 0.0
    losses = 0.0
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
    sum_x = sum(prices)
    sum_y = sum(vols)
    sum_xy = sum(p * v for p, v in zip(prices, vols))
    sum_x2 = sum(p * p for p in prices)
    sum_y2 = sum(v * v for v in vols)
    num = n * sum_xy - sum_x * sum_y
    den = math.sqrt((n * sum_x2 - sum_x ** 2) * (n * sum_y2 - sum_y ** 2))
    if den == 0:
        return 0.0
    return num / den


FACTOR_FUNCS = {
    "MOM20": lambda h: calc_mom(h, 20),
    "MOM60": lambda h: calc_mom(h, 60),
    "VOL20": calc_vol20,
    "TURN20": calc_turn20,
    "SIZE": calc_size,
    "RSI5": calc_rsi5,
    "BOLL_POS": calc_boll_pos,
    "VPCORR20": calc_vp_corr20,
}


def compute_all_factors(stock_data, factors, start_date, end_date):
    """
    计算全部因子值
    返回: {factor_code: [(symbol, calc_date, factor_val), ...]}
    """
    print(f"[2/4] 计算因子值: {factors}")
    results = {f: [] for f in factors}

    total_stocks = len(stock_data)
    processed = 0
    start_time = time.time()

    for code, history in stock_data.items():
        # 只计算 start_date ~ end_date 范围内的
        # 因子计算需要历史数据，但只输出范围内的日期
        for i, bar in enumerate(history):
            td = bar["date"]
            if td < start_date:
                continue
            if td > end_date:
                break

            hist_slice = history[: i + 1]  # 到当前日为止的历史
            for fc in factors:
                val = FACTOR_FUNCS[fc](hist_slice)
                if val is not None and not (math.isnan(val) or math.isinf(val)):
                    results[fc].append((code, td, val))

        processed += 1
        if processed % 500 == 0:
            elapsed = time.time() - start_time
            speed = processed / elapsed if elapsed > 0 else 0
            print(f"  进度: {processed}/{total_stocks} 股票  ({speed:.0f} 只/秒)")

    elapsed = time.time() - start_time
    for fc in factors:
        print(f"  {fc}: {len(results[fc]):,} 条  (耗时 {elapsed:.1f}s)")
    return results


def normalize_factors(conn, factor_data, code_market_map, batch_size=5000):
    """
    横截面归一化: Z-Score + 百分位排名
    写入 factor_value 表
    """
    print("[3/4] 横截面归一化并写入数据库...")
    cur = conn.cursor()

    for fc, values in factor_data.items():
        # 按日期分组
        date_groups = defaultdict(list)
        for code, td, val in values:
            date_groups[td].append((code, val))

        batch = []
        total_written = 0

        for td in sorted(date_groups.keys()):
            items = date_groups[td]
            raw_vals = [v for _, v in items]

            # Z-Score
            mean = sum(raw_vals) / len(raw_vals)
            std = math.sqrt(sum((v - mean) ** 2 for v in raw_vals) / len(raw_vals)) if len(raw_vals) > 1 else 0

            # 百分位排名
            sorted_vals = sorted(raw_vals)

            for code, val in items:
                z = 0.0 if std == 0 else (val - mean) / std
                # 百分位排名
                rank = sorted_vals.index(val)
                pct = rank / (len(sorted_vals) - 1) if len(sorted_vals) > 1 else 0.5

                market = code_market_map.get(code, "")
                symbol = f"{code}.{market}" if market else code

                batch.append((fc, symbol, td, round(val, 8), round(pct, 6), round(z, 6)))

                if len(batch) >= batch_size:
                    _insert_batch(cur, conn, batch)
                    total_written += len(batch)
                    batch = []

        if batch:
            _insert_batch(cur, conn, batch)
            total_written += len(batch)

        print(f"  {fc}: 写入 {total_written:,} 条")


def _insert_batch(cur, conn, batch):
    """批量 INSERT"""
    sql = """
        INSERT INTO factor_value (factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, NOW())
    """
    cur.executemany(sql, batch)
    conn.commit()


def main():
    parser = argparse.ArgumentParser(description="全量重算因子值")
    parser.add_argument("--factors", nargs="+", default=ALL_FACTORS, help="要计算的因子列表")
    parser.add_argument("--start-date", default="2018-01-02", help="起始日期 (YYYY-MM-DD)")
    parser.add_argument("--end-date", default=None, help="结束日期 (YYYY-MM-DD), 默认=数据最新日")
    parser.add_argument("--batch-size", type=int, default=5000, help="INSERT 批量大小")
    parser.add_argument("--dry-run", action="store_true", help="只计算不写入")
    parser.add_argument("--skip-clear", action="store_true", help="不清空旧数据")
    args = parser.parse_args()

    start_date = date.fromisoformat(args.start_date)
    end_date = date.fromisoformat(args.end_date) if args.end_date else None

    conn = pymysql.connect(**DB_CONFIG)
    try:
        code_market_map = load_code_market(conn)
        stock_data = load_stock_daily(conn)

        # 确定结束日期
        if end_date is None:
            max_date = max(h[-1]["date"] for h in stock_data.values())
            end_date = max_date
            print(f"  数据范围: {start_date} ~ {end_date}")

        # 计算因子
        factor_data = compute_all_factors(stock_data, args.factors, start_date, end_date)

        if args.dry_run:
            print("[DRY-RUN] 跳过数据库写入")
            for fc, values in factor_data.items():
                print(f"  {fc}: {len(values):,} 条")
            return

        # 清空旧数据
        if not args.skip_clear:
            print("[3/4] 清空旧 factor_value 数据...")
            cur = conn.cursor()
            for fc in args.factors:
                cur.execute("DELETE FROM factor_value WHERE factor_code = %s", (fc,))
                print(f"  删除 {fc}: {cur.rowcount:,} 条")
            conn.commit()

        # 写入并归一化
        normalize_factors(conn, factor_data, code_market_map, batch_size=args.batch_size)

        print("\n[DONE] 因子全量计算完成!")

    finally:
        conn.close()


if __name__ == "__main__":
    main()
