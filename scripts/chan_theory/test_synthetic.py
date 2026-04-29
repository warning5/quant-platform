"""
缠论计算引擎 — 丰富合成数据验证
模拟多种走势形态: 趋势上涨/趋势下跌/盘整/震荡
"""

import sys
sys.path.insert(0, '.')
from chan_theory.calculator import calculate_from_ohlcv
from chan_theory import Direction, FractalType, TrendType, BuySellType


def make_bars_from_prices(prices, start_date='2025-01-01'):
    """从收盘价列表生成OHLCV(带合理的OHLC范围)"""
    dates = []
    opens = []
    highs = []
    lows = []
    closes = []
    n = len(prices)
    for i, c in enumerate(prices):
        d = f'2025-01-{(i+1):02d}' if i < 28 else f'2025-02-{(i-27):02d}'
        dates.append(d)
        o = c * (1 - 0.003)
        h = c * (1 + 0.008)
        l = c * (1 - 0.008)
        opens.append(round(o, 2))
        highs.append(round(h, 2))
        lows.append(round(l, 2))
        closes.append(round(c, 2))
    return dates, opens, highs, lows, closes


def run_test(name, prices):
    """运行单个测试并输出摘要"""
    print(f"\n{'─'*60}")
    print(f"测试: {name} ({len(prices)} 根K线)")
    print(f"{'─'*60}")

    dates, opens, highs, lows, closes = make_bars_from_prices(prices)
    result = calculate_from_ohlcv(dates, opens, highs, lows, closes)

    top_f = sum(1 for f in result.fractals if f.fractal_type == FractalType.TOP)
    bot_f = sum(1 for f in result.fractals if f.fractal_type == FractalType.BOTTOM)
    up_pens = sum(1 for p in result.pens if p.direction == Direction.UP)
    dn_pens = sum(1 for p in result.pens if p.direction == Direction.DOWN)

    print(f"合并K线: {len(result.merged_bars)}")
    print(f"分型: {len(result.fractals)} (顶{top_f} 底{bot_f})")
    print(f"笔:   {len(result.pens)} (↑{up_pens} ↓{dn_pens})")
    print(f"线段: {len(result.segments)}")
    print(f"中枢: {len(result.hubs)}")

    if result.hubs:
        for h in result.hubs:
            print(f"  ZG={h.high:.2f} ZD={h.low:.2f} | "
                  f"{h.segment_count}段 振荡{h.oscillation_count}次 级别{h.level}")

    print(f"走势: {len(result.trends)}")
    type_map = {TrendType.CONSOLIDATION: '盘整', TrendType.UPTREND: '上涨', TrendType.DOWNTREND: '下跌'}
    for t in result.trends:
        print(f"  {type_map[t.trend_type]} | {len(t.hubs)}中枢 | "
              f"{t.start_date}~{t.end_date}")

    print(f"买卖点: {len(result.buy_sell_points)}")
    bp_names = {
        BuySellType.FIRST_BUY: '一买', BuySellType.SECOND_BUY: '二买', BuySellType.THIRD_BUY: '三买',
        BuySellType.FIRST_SELL: '一卖', BuySellType.SECOND_SELL: '二卖', BuySellType.THIRD_SELL: '三卖',
    }
    for bp in result.buy_sell_points:
        print(f"  {bp_names.get(bp.buy_sell_type, str(bp.buy_sell_type))} | "
              f"{bp.date} @ {bp.price:.2f}")

    # 基本校验
    errors = []
    # 笔方向必须交替
    for i in range(1, len(result.pens)):
        if result.pens[i].direction == result.pens[i-1].direction:
            errors.append(f"笔方向不交替: 笔{i}")
    # 中枢ZG > ZD
    for h in result.hubs:
        if h.high <= h.low:
            errors.append(f"中枢ZG({h.high})<=ZD({h.low})")
    # 合并K线 <= 原始
    if len(result.merged_bars) > len(result.raw_bars):
        errors.append("合并K线多于原始K线")

    if errors:
        print(f"  ✗ 校验失败:")
        for e in errors:
            print(f"    - {e}")
    else:
        print(f"  ✓ 校验全部通过")

    return result


if __name__ == '__main__':
    results = []

    # ── Test 1: 简单上升后下跌 ──
    results.append(run_test(
        "简单上升→下降",
        [10,11,12,13,14,15,14,13,12,11,10,9,10,11,12,13,14,15,
         16,17,18,19,20,19,18,17,16,15,14,13]
    ))

    # ── Test 2: 震荡盘整 ──
    base = [10+i*0.5 for i in range(6)]  # 10→12.5
    flat = []
    for _ in range(5):
        flat += base[::-1]  # 往返震荡
    flat += [10]*5
    results.append(run_test("盘整震荡", flat))

    # ── Test 3: 趋势上涨(阶梯式) ──
    trend_up = []
    level = 10
    for step in range(4):
        trend_up += [level + i for i in range(8)]  # 8根上升
        trend_up += [level + 7 - i for i in range(3)]  # 3根回调
        level += 3  # 每个台阶抬升3元
    trend_up += [trend_up[-1]+i for i in range(8)]
    results.append(run_test("阶梯上涨趋势", trend_up))

    # ── Test 4: 趋势下跌 ──
    trend_dn = [30-i for i in range(40)]
    # 加一些反弹制造分型
    trend_dn[10] += 2; trend_dn[11] += 3; trend_dn[12] += 1
    trend_dn[25] += 2; trend_dn[26] += 3; trend_dn[27] += 1
    results.append(run_test("趋势下跌", trend_dn))

    # ── Test 5: 大量随机数据(60根) ──
    import random
    random.seed(42)
    price = 100
    walk = []
    for _ in range(80):
        price *= 1 + random.uniform(-0.03, 0.03)
        price = round(price, 2)
        walk.append(price)
    results.append(run_test("随机游走(80根)", walk))

    # ── 汇总 ──
    print(f"\n{'═'*60}")
    print(f"汇总: {len(results)} 个测试场景")
    total_fractals = sum(len(r.fractals) for r in results)
    total_pens = sum(len(r.pens) for r in results)
    total_segments = sum(len(r.segments) for r in results)
    total_hubs = sum(len(r.hubs) for r in results)
    total_bs = sum(len(r.buy_sell_points) for r in results)
    print(f"总分型: {total_fractals} | 总笔: {total_pens} | 总线段: {total_segments} | "
          f"总中枢: {total_hubs} | 总买卖点: {total_bs}")
    print(f"{'═'*60}")
