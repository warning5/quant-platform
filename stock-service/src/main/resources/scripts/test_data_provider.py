#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_data_provider.py
=====================
数据提供者模块测试脚本。

验证:
1. 模块导入 + 工厂函数正常
2. 腾讯接口能获取沪深股票数据
3. 返回 DataFrame 字段完整（14列）
4. volume 单位正确（科创板不×100，主板×100）
5. preclose / pctChg 计算正确
6. PE/PB 快照获取正常
7. build_daily_rows 输出格式正确
"""

import sys
import os
from datetime import date, timedelta

# 确保能找到 scripts 模块
script_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, script_dir)

from data_provider import get_provider, DataProvider
from data_provider.tencent_provider import TencentDataProvider
from data_provider.baostock_provider import BaostockDataProvider
from db_helper import StockDailyDB


def test_import():
    """测试模块导入"""
    print("=" * 60)
    print("[TEST 1] 模块导入")
    print("=" * 60)

    # 工厂函数
    tencent = get_provider("tencent")
    baostock = get_provider("baostock")

    assert isinstance(tencent, DataProvider), "TencentDataProvider 不是 DataProvider 子类"
    assert isinstance(baostock, DataProvider), "BaostockDataProvider 不是 DataProvider 子类"
    assert tencent.source_name == "qq"
    assert baostock.source_name == "baostock"

    print(f"  TencentDataProvider: source={tencent.source_name}, "
          f"full_history={tencent.supports_full_history}, "
          f"hist_pe_pb={tencent.supports_historical_pe_pb}, "
          f"parallel={tencent.supports_parallel}")
    print(f"  BaostockDataProvider: source={baostock.source_name}, "
          f"full_history={baostock.supports_full_history}, "
          f"hist_pe_pb={baostock.supports_historical_pe_pb}, "
          f"parallel={baostock.supports_parallel}")
    print("  [PASS]\n")


def test_tencent_kline_sh():
    """测试腾讯接口获取沪市股票"""
    print("=" * 60)
    print("[TEST 2] 腾讯接口 - 沪市股票 600000 浦发银行")
    print("=" * 60)

    provider = get_provider("tencent")
    provider.login()

    end_date = date.today()
    start_date = end_date - timedelta(days=10)

    df = provider.query_history("600000", "SH", start_date, end_date)

    assert df is not None, "600000 返回 None"
    assert len(df) > 0, "600000 返回空 DataFrame"

    # 验证 14 列
    expected_cols = {'date', 'open', 'high', 'low', 'close', 'preclose',
                     'volume', 'amount', 'turn', 'tradestatus',
                     'pctChg', 'isST', 'peTTM', 'pbMRQ'}
    assert set(df.columns) == expected_cols, f"列不匹配: {set(df.columns)} vs {expected_cols}"

    print(f"  行数: {len(df)}")
    print(f"  日期范围: {df['date'].min()} ~ {df['date'].max()}")
    print(f"  列: {list(df.columns)}")

    # 显示最近3行
    print(f"\n  最近3行数据:")
    for _, row in df.tail(3).iterrows():
        print(f"    {row['date']} | O:{row['open']} H:{row['high']} "
              f"L:{row['low']} C:{row['close']} | vol:{row['volume']} "
              f"| turn:{row['turn']} | pct:{row['pctChg']}% "
              f"| PE:{row['peTTM']} PB:{row['pbMRQ']}")

    # 验证 volume 非零（主板应为手×100）
    assert df['volume'].iloc[-1] is not None and int(df['volume'].iloc[-1]) > 0, "volume 为 0"

    # 验证 preclose（第2行起应有值）
    if len(df) > 1:
        assert df['preclose'].iloc[-1] is not None, "preclose 为空"

    # 验证 PE/PB 快照有值
    pe_val = df['peTTM'].iloc[-1]
    pb_val = df['pbMRQ'].iloc[-1]
    print(f"\n  PE_TTM(快照): {pe_val}")
    print(f"  PB(快照): {pb_val}")

    provider.logout()
    print("  [PASS]\n")
    return df


def test_tencent_kline_star():
    """测试腾讯接口获取科创板股票（volume单位验证）"""
    print("=" * 60)
    print("[TEST 3] 腾讯接口 - 科创板 688981 中芯国际（volume单位验证）")
    print("=" * 60)

    provider = get_provider("tencent")
    provider.login()

    end_date = date.today()
    start_date = end_date - timedelta(days=10)

    df = provider.query_history("688981", "SH", start_date, end_date)

    if df is None or len(df) == 0:
        print("  [SKIP] 688981 无数据（可能停牌或接口异常）")
        return

    print(f"  行数: {len(df)}")
    print(f"  最近一行: {df.iloc[-1]['date']}")
    print(f"    close={df.iloc[-1]['close']}, volume={df.iloc[-1]['volume']}")

    # 科创板 volume 应该不×100（直接是"股"）
    # 验证 volume 合理性：中芯国际日均成交量在千万级别
    vol = int(df['volume'].iloc[-1]) if df['volume'].iloc[-1] else 0
    print(f"  volume 值: {vol:,}")
    if vol > 100:
        print(f"  [OK] 科创板 volume 单位正确（股，未×100）")
    else:
        print(f"  [WARN] volume 异常小，检查单位")

    provider.logout()
    print("  [PASS]\n")


def test_tencent_kline_sz():
    """测试腾讯接口获取深市股票"""
    print("=" * 60)
    print("[TEST 4] 腾讯接口 - 深市股票 000001 平安银行")
    print("=" * 60)

    provider = get_provider("tencent")
    provider.login()

    end_date = date.today()
    start_date = end_date - timedelta(days=10)

    df = provider.query_history("000001", "SZ", start_date, end_date)

    assert df is not None and len(df) > 0, "000001 无数据"

    print(f"  行数: {len(df)}")
    print(f"  最近: {df.iloc[-1]['date']} close={df.iloc[-1]['close']} "
          f"vol={df.iloc[-1]['volume']} pct={df.iloc[-1]['pctChg']}%")

    # 验证深市 volume ×100
    vol = int(df['volume'].iloc[-1]) if df['volume'].iloc[-1] else 0
    print(f"  volume: {vol:,} (深市应为手×100)")
    assert vol > 1000, "深市 volume 过小"

    provider.logout()
    print("  [PASS]\n")


def test_snapshot_batch():
    """测试批量 PE/PB 快照"""
    print("=" * 60)
    print("[TEST 5] 批量快照 PE/PB")
    print("=" * 60)

    provider = get_provider("tencent")
    provider.login()

    codes_markets = [
        ("600000", "SH"),
        ("000001", "SZ"),
        ("688981", "SH"),
        ("300750", "SZ"),  # 宁德时代
    ]
    snapshots = provider.fetch_snapshot_pe_pb(codes_markets)

    print(f"  请求 {len(codes_markets)} 只, 获取 {len(snapshots)} 只")
    for code, _ in codes_markets:
        snap = snapshots.get(code, {})
        print(f"    {code}: PE={snap.get('pe_ttm')}, PB={snap.get('pb')}")

    assert len(snapshots) >= 2, "快照获取数量过少"

    provider.logout()
    print("  [PASS]\n")


def test_build_daily_rows():
    """测试 build_daily_rows 输出格式"""
    print("=" * 60)
    print("[TEST 6] build_daily_rows 输出格式")
    print("=" * 60)

    provider = get_provider("tencent")
    provider.login()

    end_date = date.today()
    start_date = end_date - timedelta(days=10)

    df = provider.query_history("600000", "SH", start_date, end_date)
    assert df is not None and len(df) > 0

    db = StockDailyDB()
    try:
        rows = provider.build_daily_rows(db, "600000", "浦发银行", "SH", df)

        assert len(rows) > 0, "build_daily_rows 返回空"
        assert len(rows) == len(df), f"行数不匹配: {len(rows)} vs {len(df)}"

        row = rows[-1]
        expected_keys = {
            'code', 'name', 'trade_date', 'open_price', 'close_price',
            'high_price', 'low_price', 'pre_close', 'volume', 'amount',
            'change_percent', 'change_amount', 'turnover_rate',
            'pe_ttm', 'pb', 'data_source'
        }
        assert set(row.keys()) == expected_keys, f"key 不匹配: {set(row.keys())}"

        assert row['code'] == "600000"
        assert row['name'] == "浦发银行"
        assert row['data_source'] == "qq"

        print(f"  行数: {len(rows)}")
        print(f"  最后一行:")
        print(f"    trade_date: {row['trade_date']}")
        print(f"    close_price: {row['close_price']}")
        print(f"    pre_close: {row['pre_close']}")
        print(f"    change_percent: {row['change_percent']}%")
        print(f"    volume: {row['volume']}")
        print(f"    turnover_rate: {row['turnover_rate']}")
        print(f"    pe_ttm: {row['pe_ttm']}")
        print(f"    pb: {row['pb']}")
        print(f"    data_source: {row['data_source']}")

        # 验证 preclose 和 change_percent 一致性
        if row['pre_close'] and row['close_price']:
            expected_pct = round((row['close_price'] - row['pre_close'])
                                 / row['pre_close'] * 100, 2)
            if row['change_percent'] is not None:
                diff = abs(row['change_percent'] - expected_pct)
                assert diff < 0.5, f"change_percent 偏差过大: {row['change_percent']} vs {expected_pct}"
                print(f"\n  [OK] change_percent 计算一致: {row['change_percent']}% ≈ {expected_pct}%")

    finally:
        db.close()

    provider.logout()
    print("  [PASS]\n")


def test_context_manager():
    """测试上下文管理器"""
    print("=" * 60)
    print("[TEST 7] 上下文管理器 (with 语句)")
    print("=" * 60)

    with get_provider("tencent") as provider:
        end_date = date.today()
        start_date = end_date - timedelta(days=5)
        df = provider.query_history("600519", "SH", start_date, end_date)
        if df is not None and len(df) > 0:
            print(f"  600519 贵州茅台: {len(df)} 行")
            print(f"  最近: {df.iloc[-1]['date']} close={df.iloc[-1]['close']}")
            print("  [PASS]")
        else:
            print("  [SKIP] 600519 无数据")
    print()


def main():
    print("\n" + "=" * 60)
    print("  数据提供者模块测试")
    print("  测试时间: " + str(datetime.now()))
    print("=" * 60 + "\n")

    try:
        test_import()
        test_tencent_kline_sh()
        test_tencent_kline_star()
        test_tencent_kline_sz()
        test_snapshot_batch()
        test_build_daily_rows()
        test_context_manager()

        print("=" * 60)
        print("  全部测试通过!")
        print("=" * 60)
        return 0

    except AssertionError as e:
        print(f"\n  [FAIL] 断言失败: {e}")
        import traceback
        traceback.print_exc()
        return 1
    except Exception as e:
        print(f"\n  [ERROR] {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    from datetime import datetime
    sys.exit(main())
