"""
缠论计算引擎验证脚本
用 ClickHouse 中的真实股票日线数据验证缠论计算的每一步
"""

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from chan_theory.calculator import (
    calculate_from_ohlcv, merge_bars, find_fractals,
    build_pens, build_segments, build_hubs, build_trends,
    find_buy_sell_points
)
from chan_theory import (
    RawBar, Direction, FractalType, TrendType, BuySellType
)

try:
    import clickhouse_connect
    HAS_CH = True
except ImportError:
    HAS_CH = False

try:
    import pymysql
    HAS_MYSQL = True
except ImportError:
    HAS_MYSQL = False


def get_bars_from_clickhouse(code: str, start_date: str = '2025-01-01', end_date: str = '2026-04-28'):
    """从 ClickHouse 获取日线数据"""
    client = clickhouse_connect.get_client(
        host='localhost', port=8123, database='stock',
        username='default', password='123456'
    )
    sql = f"""
        SELECT trade_date, open, high, low, close, vol, amount
        FROM stock_daily
        WHERE code = '{code}' AND trade_date >= '{start_date}' AND trade_date <= '{end_date}'
        ORDER BY trade_date ASC
    """
    rows = client.query(sql).result_rows
    client.close()
    return rows


def get_bars_from_mysql(code: str, start_date: str = '2025-01-01', end_date: str = '2026-04-28'):
    """从 MySQL 获取日线数据"""
    conn = pymysql.connect(host='localhost', port=3306, user='root', password='123456', database='stock')
    cursor = conn.cursor()
    sql = f"""
        SELECT trade_date, open_price, high_price, low_price, close_price, volume, amount
        FROM stock_daily
        WHERE code = '{code}' AND trade_date >= '{start_date}' AND trade_date <= '{end_date}'
        ORDER BY trade_date ASC
    """
    cursor.execute(sql)
    rows = cursor.fetchall()
    cursor.close()
    conn.close()
    return rows


def test_single_stock(code: str, name: str = ''):
    """测试单只股票的缠论计算"""
    print(f"\n{'='*60}")
    print(f"测试股票: {code} {name}")
    print(f"{'='*60}")

    # 获取数据
    rows = None
    if HAS_CH:
        try:
            rows = get_bars_from_clickhouse(code)
            print(f"数据源: ClickHouse, 共 {len(rows)} 条")
        except Exception as e:
            print(f"ClickHouse 查询失败: {e}")

    if rows is None and HAS_MYSQL:
        try:
            rows = get_bars_from_mysql(code)
            print(f"数据源: MySQL, 共 {len(rows)} 条")
        except Exception as e:
            print(f"MySQL 查询失败: {e}")

    if not rows:
        print("无法获取数据,跳过")
        return

    # 构造OHLCV
    dates = [str(r[0]) for r in rows]
    opens = [float(r[1]) if r[1] else 0 for r in rows]
    highs = [float(r[2]) if r[2] else 0 for r in rows]
    lows = [float(r[3]) if r[3] else 0 for r in rows]
    closes = [float(r[4]) if r[4] else 0 for r in rows]
    vols = [float(r[5]) if r[5] else 0 for r in rows]
    amounts = [float(r[6]) if r[6] else 0 for r in rows]

    # 全流程计算
    result = calculate_from_ohlcv(dates, opens, highs, lows, closes, vols, amounts)

    # 逐步输出
    print(f"\n--- Step 1: K线合并 ---")
    print(f"  原始K线: {len(result.raw_bars)} 根")
    print(f"  合并K线: {len(result.merged_bars)} 根 (减少 {len(result.raw_bars) - len(result.merged_bars)} 根)")

    print(f"\n--- Step 2: 分型识别 ---")
    top_count = sum(1 for f in result.fractals if f.fractal_type == FractalType.TOP)
    bot_count = sum(1 for f in result.fractals if f.fractal_type == FractalType.BOTTOM)
    print(f"  顶分型: {top_count} 个")
    print(f"  底分型: {bot_count} 个")
    print(f"  合计: {len(result.fractals)} 个")

    print(f"\n--- Step 3: 笔 ---")
    up_pens = [p for p in result.pens if p.direction == Direction.UP]
    down_pens = [p for p in result.pens if p.direction == Direction.DOWN]
    print(f"  上升笔: {len(up_pens)} 个")
    print(f"  下降笔: {len(down_pens)} 个")
    print(f"  合计: {len(result.pens)} 个")
    if result.pens:
        avg_len = sum(p.bar_count for p in result.pens) / len(result.pens)
        print(f"  平均笔长: {avg_len:.1f} 根合并K线")
        # 打印最近5笔
        print(f"  最近5笔:")
        for p in result.pens[-5:]:
            dir_str = "↑" if p.direction == Direction.UP else "↓"
            print(f"    {dir_str} {p.start_date}~{p.end_date} | "
                  f"{p.start_price:.2f}→{p.end_price:.2f} | "
                  f"{p.bar_count}根")

    print(f"\n--- Step 4: 线段 ---")
    print(f"  线段数: {len(result.segments)}")
    if result.segments:
        for s in result.segments[-3:]:
            dir_str = "↗" if s.direction == Direction.UP else "↘"
            print(f"    {dir_str} {s.start_date}~{s.end_date} | "
                  f"{s.start_price:.2f}→{s.end_price:.2f} | "
                  f"{s.pen_count}笔")

    print(f"\n--- Step 5: 中枢 ---")
    print(f"  中枢数: {len(result.hubs)}")
    for h in result.hubs:
        print(f"    ZG={h.high:.2f} ZD={h.low:.2f} ZZ={h.zz:.2f} | "
              f"{h.start_date}~{h.end_date} | "
              f"{h.segment_count}段 震荡{h.oscillation_count}次 级别{h.level}")

    print(f"\n--- Step 6: 走势类型 ---")
    print(f"  走势数: {len(result.trends)}")
    for t in result.trends:
        type_map = {
            TrendType.CONSOLIDATION: "盘整",
            TrendType.UPTREND: "上涨趋势",
            TrendType.DOWNTREND: "下跌趋势"
        }
        print(f"    {type_map[t.trend_type]} | "
              f"{t.start_date}~{t.end_date} | "
              f"{len(t.hubs)}个中枢")

    print(f"\n--- Step 7: 买卖点 ---")
    print(f"  买卖点数: {len(result.buy_sell_points)}")
    type_names = {
        BuySellType.FIRST_BUY: "一买",
        BuySellType.SECOND_BUY: "二买",
        BuySellType.THIRD_BUY: "三买",
        BuySellType.FIRST_SELL: "一卖",
        BuySellType.SECOND_SELL: "二卖",
        BuySellType.THIRD_SELL: "三卖",
    }
    for bp in result.buy_sell_points:
        name = type_names.get(bp.buy_sell_type, str(bp.buy_sell_type))
        print(f"    {name} | {bp.date} | 价格={bp.price:.2f}")

    return result


def test_synthetic():
    """用合成数据测试(验证算法正确性)"""
    print("\n" + "=" * 60)
    print("合成数据测试: 简单上升-下降-上升")
    print("=" * 60)

    # 构造一个有明确顶底分型的序列
    dates = [f"2025-01-{d:02d}" for d in range(1, 31)]
    # 上升5根 → 顶分型 → 下降5根 → 底分型 → 上升5根 → 顶分型 → 下降5根
    closes = [
        10, 11, 12, 13, 14,  # 上升
        15, 14, 13, 12, 11,  # 顶+下降
        10, 11, 12, 13, 14,  # 底+上升
        15, 14, 13, 12, 11,  # 顶+下降
        10, 11, 12, 13, 14,  # 底+上升
        15, 14, 13, 12, 11   # 顶+下降
    ]
    highs = [c + 0.5 for c in closes]
    lows = [c - 0.5 for c in closes]
    opens = [c - 0.2 for c in closes]

    result = calculate_from_ohlcv(dates, opens, highs, lows, closes)

    print(f"合并K线: {len(result.merged_bars)}")
    print(f"分型: {len(result.fractals)} (顶{sum(1 for f in result.fractals if f.fractal_type == FractalType.TOP)} 底{sum(1 for f in result.fractals if f.fractal_type == FractalType.BOTTOM)})")
    print(f"笔: {len(result.pens)}")
    print(f"线段: {len(result.segments)}")
    print(f"中枢: {len(result.hubs)}")
    print(f"走势: {len(result.trends)}")
    print(f"买卖点: {len(result.buy_sell_points)}")

    # 验证基本正确性
    assert len(result.merged_bars) <= len(result.raw_bars), "合并K线不应多于原始K线"
    assert len(result.fractals) >= 0, "分型数量应>=0"
    if result.pens:
        # 笔必须交替
        for i in range(1, len(result.pens)):
            assert result.pens[i].direction != result.pens[i-1].direction, \
                f"第{i}笔方向应与前一笔相反"
        print("\n✓ 笔方向交替性验证通过")

    print("\n合成数据测试完成 ✓")


if __name__ == '__main__':
    # 先跑合成数据测试
    test_synthetic()

    # 再跑真实股票测试
    test_stocks = [
        ('sh.600519', '贵州茅台'),
        ('sz.000001', '平安银行'),
        ('sh.601318', '中国平安'),
        ('sz.000858', '五粮液'),
    ]
    for code, name in test_stocks:
        try:
            test_single_stock(code, name)
        except Exception as e:
            print(f"\n✗ {code} 测试失败: {e}")
            import traceback
            traceback.print_exc()

    print(f"\n{'='*60}")
    print("所有测试完成")
    print(f"{'='*60}")
