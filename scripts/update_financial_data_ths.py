#!/usr/bin/env python3 -u
# -*- coding: utf-8 -*-
"""
财务数据采集脚本（THS 版）
======================
使用同花顺 akshare API 采集三大表，替代新浪 API（已被限速）。

数据源：
  1. stock_yjbb_em                  - 东方财富业绩报表（批量，全市场）
  2. stock_financial_abstract_ths   - 同花顺财务摘要（逐只/并发，补充指标）
  3. stock_financial_cash_ths       - 同花顺现金流量表（按报告期）
  4. stock_financial_benefit_ths    - 同花顺利润表（按报告期）
  5. stock_financial_debt_ths       - 同花顺资产负债表（按报告期）

与原版 update_financial_data.py 的区别：
  - Step 3 三大表改用 THS API（稳定，无 456 限速）
  - 支持 --ths-workers N 控制 THS 并发数（默认 8）
  - 保留原有 Step 1/2/4 逻辑不变

用法：
  python update_financial_data_ths.py [--year-start 2022] [--year-end 2026]
  python update_financial_data_ths.py --step yjbb          # 只跑 Step 1
  python update_financial_data_ths.py --step ths           # 只跑 Step 2
  python update_financial_data_ths.py --step ths-cash      # 只跑 Step 3 现金流量表
  python update_financial_data_ths.py --step ths-all       # 只跑 Step 3 三大表
  python update_financial_data_ths.py --step tushare       # 只跑 Step 4
  python update_financial_data_ths.py --force              # 强制重采
  python update_financial_data_ths.py --ths-workers 4     # THS 并发数
"""

import argparse
import re
import sys
import time
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

import akshare as ak
import pymysql
from dbutils.pooled_db import PooledDB

from db_config import MYSQL_CONFIG


# ─────────────────────────────────────────────
# 工具函数（与原版相同）
# ─────────────────────────────────────────────

def get_conn():
    return pymysql.connect(**MYSQL_CONFIG)

def parse_number(val):
    """将各种格式的数值字符串转为 float 或 None"""
    import math
    if val is None:
        return None
    try:
        if isinstance(val, float) and math.isnan(val):
            return None
    except (TypeError, ValueError):
        pass
    s = str(val).strip()
    if s == '' or s == 'False' or s == '-' or s == '--' or s == 'nan' or s == 'NaN':
        return None
    s = s.replace(',', '')
    if s.endswith('%'):
        try:
            return float(s[:-1])
        except ValueError:
            return None
    m = re.match(r'^([+-]?[\d.]+)\s*(亿|万)$', s)
    if m:
        num = float(m.group(1))
        unit = m.group(2)
        return num * 1e8 if unit == '亿' else num * 1e4
    try:
        return float(s)
    except (ValueError, TypeError):
        return None

def report_type_from_date(report_date_str):
    s = str(report_date_str).replace('-', '')
    month = int(s[4:6])
    if month == 12:
        return 4
    elif month == 6:
        return 2
    elif month == 9:
        return 3
    elif month == 3:
        return 1
    return 0

def end_date_from_report(report_date_str):
    s = str(report_date_str).replace('-', '')
    return f"{s[:4]}-{s[4:6]}-{s[6:8]}"


# ─────────────────────────────────────────────
# Step 1. 东方财富业绩报表（与原版相同）
# ─────────────────────────────────────────────

def fetch_yjbb(year):
    current_year = datetime.now().year
    if year >= current_year:
        print(f"  跳过 {year} 年报（尚未发布）")
        return None
    date_str = f'{year}1231'
    print(f"  [{datetime.now().strftime('%H:%M:%S')}] 拉取东方财富业绩报表 {date_str} ...")
    for attempt in range(3):
        try:
            df = ak.stock_yjbb_em(date=date_str)
            print(f"  获取到 {len(df)} 条记录")
            return df
        except Exception as e:
            print(f"  第{attempt+1}次失败: {e}")
            if attempt < 2:
                time.sleep(5)
    return None

def save_yjbb_to_indicator(df, conn, report_year, force=False):
    cursor = conn.cursor()
    existing = set()
    if not force:
        cursor.execute("SELECT code, report_date FROM stock_financial_indicator")
        existing = {(r[0], r[1]) for r in cursor.fetchall()}

    rd = f'{report_year}1231'
    rt = 4
    ed = f'{report_year}-12-31'
    inserted = 0
    for _, row in df.iterrows():
        code = str(row.get('股票代码', '')).strip()
        if not code or len(code) != 6:
            continue
        if (code, rd) in existing:
            continue
        bps = parse_number(row.get('每股净资产'))
        revenue_yoy = parse_number(row.get('营业总收入-同比增长'))
        net_profit_yoy = parse_number(row.get('净利润-同比增长'))
        roe = parse_number(row.get('净资产收益率'))
        gross_margin = parse_number(row.get('销售毛利率'))
        try:
            cursor.execute("""
                INSERT IGNORE INTO stock_financial_indicator
                    (code, report_date, report_type, end_date,
                     bps, revenue_yoy, net_profit_yoy, roe,
                     gross_profit_margin)
                VALUES (%s,%s,%s,%s, %s,%s,%s,%s, %s)
            """, (code, rd, rt, ed,
                  bps, revenue_yoy, net_profit_yoy, roe, gross_margin))
            inserted += 1
        except Exception as e:
            print(f"    ERR {code} {rd}: {e}")
    conn.commit()
    print(f"  东方财富指标 {report_year}: 新增/更新 {inserted} 条")
    return inserted


# ─────────────────────────────────────────────
# Step 2. 同花顺财务摘要（与原版相同）
# ─────────────────────────────────────────────

def fetch_ths_abstract(code):
    try:
        df = ak.stock_financial_abstract_ths(symbol=code)
        return df
    except Exception as e:
        return None

def save_ths_to_tables(df, code, conn, force=False):
    if df is None or df.empty:
        return 0
    cursor = conn.cursor()
    existing = set()
    if not force:
        cursor.execute("SELECT code, report_date FROM stock_financial_indicator WHERE code = %s", (code,))
        existing = {(r[0], r[1]) for r in cursor.fetchall()}

    COL_MAP_INDICATOR = {
        '销售净利率': 'net_profit_margin',
        '销售毛利率': 'gross_profit_margin',
        '净资产收益率': 'roe',
        '净资产收益率-摊薄': 'roe',
        '存货周转率': 'inventory_turnover',
        '存货周转天数': 'inventory_turnover_days',
        '应收账款周转天数': 'ar_turnover_days',
        '流动比率': 'current_ratio',
        '速动比率': 'quick_ratio',
        '资产负债率': 'debt_to_asset_ratio',
    }
    COL_MAP_INCOME = {
        '净利润-净利润': 'net_profit',
        '净利润': 'net_profit',
        '营业总收入': 'total_revenue',
        '扣非净利润': 'deducted_np_parent_company',
        '基本每股收益': 'eps_basic',
    }

    inserted = 0
    for _, row in df.iterrows():
        report_date_raw = str(row.get('报告期', ''))
        if not report_date_raw:
            continue
        rd = report_date_raw.replace('-', '')[:8]
        if len(rd) != 8:
            continue
        if (code, rd) in existing:
            continue
        rt = report_type_from_date(rd)
        ed = end_date_from_report(rd)

        indicator_vals = {}
        for col_name, db_field in COL_MAP_INDICATOR.items():
            if col_name in row.index:
                val = parse_number(row[col_name])
                if val is not None:
                    indicator_vals[db_field] = val
        income_vals = {}
        for col_name, db_field in COL_MAP_INCOME.items():
            if col_name in row.index:
                val = parse_number(row[col_name])
                if val is not None:
                    income_vals[db_field] = val

        indicator_cols = ['net_profit_margin', 'gross_profit_margin', 'roe',
                          'inventory_turnover', 'inventory_turnover_days', 'ar_turnover_days',
                          'current_ratio', 'quick_ratio', 'debt_to_asset_ratio']
        if indicator_vals:
            set_parts = []
            insert_cols = []
            insert_vals = []
            for col in indicator_cols:
                if col in indicator_vals:
                    set_parts.append(f"{col} = VALUES({col})")
                    insert_cols.append(col)
                    insert_vals.append(indicator_vals[col])
            if insert_cols:
                sql = f"""
                    INSERT IGNORE INTO stock_financial_indicator
                        (code, report_date, report_type, end_date, {', '.join(insert_cols)})
                    VALUES (%s,%s,%s,%s, {', '.join(['%s']*len(insert_cols))})
                """
                try:
                    cursor.execute(sql, [code, rd, rt, ed] + insert_vals)
                    inserted += 1
                except Exception as e:
                    print(f"    ERR indicator {code} {rd}: {e}")

        if income_vals:
            income_cols = [col for col in income_vals.keys()]
            set_parts = []
            insert_cols = []
            insert_vals = []
            for col in income_cols:
                if col in income_vals:
                    insert_cols.append(col)
                    insert_vals.append(income_vals[col])
            if insert_cols:
                sql = f"""
                    INSERT IGNORE INTO stock_income
                        (code, report_date, report_type, end_date, {', '.join(insert_cols)})
                    VALUES (%s,%s,%s,%s, {', '.join(['%s']*len(insert_cols))})
                """
                try:
                    cursor.execute(sql, [code, rd, rt, ed] + insert_vals)
                except Exception as e:
                    print(f"    ERR income {code} {rd}: {e}")
    conn.commit()
    return inserted

def _process_ths_stock(code, force, existing_set):
    conn = None
    try:
        conn = pymysql.connect(**MYSQL_CONFIG)
        df = fetch_ths_abstract(code)
        if df is None or df.empty:
            return (code, 0, 'no_data')
        n = save_ths_to_tables(df, code, conn, force)
        return (code, n, None)
    except Exception as e:
        return (code, 0, str(e))
    finally:
        if conn:
            conn.close()


# ─────────────────────────────────────────────
# Step 3. 同花顺三大表（THS API，替代新浪）
# ─────────────────────────────────────────────

# THS 现金流量表字段映射（* 前缀 = 汇总值，优先取）
_THS_CASH_MAP = {
    '*经营活动产生的现金流量净额':    'net_operate_cf',
    '经营活动产生的现金流量净额':     'net_operate_cf',
    '*投资活动产生的现金流量净额':    'net_invest_cf',
    '投资活动产生的现金流量净额':     'net_invest_cf',
    '*筹资活动产生的现金流量净额':    'net_finance_cf',
    '筹资活动产生的现金流量净额':     'net_finance_cf',
    '汇率变动对现金及现金等价物的影响': 'exchange_rate_effect',
    '*现金及现金等价物净增加额':      'net_cash_increase',
    '现金及现金等价物净增加额':       'net_cash_increase',
    '期初现金及现金等价物余额':       'cash_at_beginning',
    '*期末现金及现金等价物余额':      'cash_at_end',
    '期末现金及现金等价物余额':       'cash_at_end',
    '销售商品、提供劳务收到的现金':   'cash_received_sales',
    '收到的税费返还':                'tax_refund_received',
    '购买商品、接受劳务支付的现金':   'cash_paid_goods_services',
    '支付给职工以及为职工支付的现金': 'cash_paid_employee',
    '支付的各项税费':                'cash_paid_tax',
    '收回投资收到的现金':            'cash_received_invest_income',
    '取得投资收益收到的现金':        'cash_received_invest_return',
    '处置固定资产、无形资产和其他长期资产收回的现金净额': 'dispose_invest_income',
    '投资支付的现金':                'cash_paid_invest',
    '购建固定资产、无形资产和其他长期资产支付的现金': 'cash_paid_acquisition',
    '吸收投资收到的现金':            'cash_received_absorb_invest',
    '取得借款收到的现金':            'cash_received_borrowing',
    '偿还债务支付的现金':            'cash_paid_borrowing',
    '分配股利、利润或偿付利息支付的现金': 'cash_paid_dividend',
}

# THS 利润表字段映射
_THS_INCOME_MAP = {
    '*营业总收入':          'total_revenue',
    '营业总收入':           'total_revenue',
    '营业收入':            'revenue',
    '*营业总成本':          'total_cost',
    '营业总成本':           'total_cost',
    '*营业成本':            'operating_cost',
    '营业成本':             'operating_cost',
    '研发费用':             'rd_expense',
    '销售费用':             'selling_expense',
    '管理费用':             'admin_expense',
    '财务费用':             'finance_expense',
    '*营业利润':            'operating_profit',
    '营业利润':             'operating_profit',
    '*利润总额':            'total_profit',
    '利润总额':             'total_profit',
    '*所得税费用':          'income_tax',
    '所得税费用':           'income_tax',
    '*净利润':              'net_profit_incl_minority',
    '净利润':               'net_profit_incl_minority',
    '*归属于母公司所有者的净利润': 'np_parent_company_owners',
    '归属于母公司所有者的净利润':  'np_parent_company_owners',
    '少数股东损益':          'np_minority',
    '*基本每股收益':        'eps_basic',
    '基本每股收益':         'eps_basic',
    '*稀释每股收益':        'eps_diluted',
    '稀释每股收益':         'eps_diluted',
    '其他综合收益':          'other_comprehensive_income',
    '综合收益总额':          'total_comprehensive_income',
}

# THS 资产负债表字段映射
_THS_BALANCE_MAP = {
    # 资产
    '*资产总计':            'total_assets',
    '资产总计':             'total_assets',
    '*流动资产合计':        'total_current_assets',
    '流动资产合计':         'total_current_assets',
    '*非流动资产合计':      'total_non_current_assets',
    '非流动资产合计':       'total_non_current_assets',
    '*货币资金':            'cash_and_equivalents',
    '货币资金':             'cash_and_equivalents',
    '交易性金融资产':       'trading_assets',
    '应收票据':             'notes_receivable',
    '应收票据及应收账款':    'notes_receivable',
    '应收账款':             'accounts_receivable',
    '预付款项':             'prepayments',
    '其他应收款':           'other_receivable',
    '存货':                 'inventory',
    '合同资产':             'contract_assets',
    '长期股权投资':         'long_term_equity_invest',
    '*固定资产净额':        'fixed_assets',
    '固定资产净额':         'fixed_assets',
    '在建工程合计':         'construction_in_progress',
    '无形资产':             'intangible_assets',
    '商誉':                 'goodwill',
    '长期待摊费用':         'long_term_prepaid_expense',
    '递延所得税资产':       'deferred_tax_assets',
    # 负债
    '*负债合计':            'total_liabilities',
    '负债合计':             'total_liabilities',
    '*流动负债合计':        'total_current_liabilities',
    '流动负债合计':         'total_current_liabilities',
    '*非流动负债合计':      'total_non_current_liabilities',
    '非流动负债合计':       'total_non_current_liabilities',
    '短期借款':             'short_term_borrowing',
    '应付票据':             'notes_payable',
    '应付账款':             'accounts_payable',
    '预收款项':             'advance_peceipts',
    '合同负债':             'contract_liabilities',
    '应付职工薪酬':         'employee_benefit_payable',
    '应交税费':             'taxs_payable',
    '其他应付款':           'other_payable',
    '长期借款':             'long_term_borrowing',
    '应付债券':             'bonds_payable',
    '租赁负债':             'lease_liabilities',
    '递延所得税负债':       'deferred_tax_liabilities',
    # 所有者权益
    '*所有者权益合计':      'total_equity',
    '所有者权益合计':       'total_equity',
    '*归属于母公司所有者权益合计': 'parent_equity',
    '归属于母公司所有者权益合计':  'parent_equity',
    '少数股东权益':          'minority_interests',
    '*实收资本(或股本)':   'paid_in_capital',
    '实收资本(或股本)':    'paid_in_capital',
    '资本公积':             'capital_reserve',
    '减:库存股':           'treasury_stock',
    '盈余公积':             'surplus_reserve',
    '未分配利润':           'undistributed_profit',
}


def _fetch_ths_table(code, table_type, timeout=30):
    """拉取 THS 单只股票的单张表，带超时控制

    table_type: 'cash' / 'income' / 'balance'
    """
    api_map = {
        'cash':    ak.stock_financial_cash_ths,
        'income':   ak.stock_financial_benefit_ths,
        'balance':  ak.stock_financial_debt_ths,
    }
    api_func = api_map[table_type]

    from concurrent.futures import ThreadPoolExecutor as TPE, TimeoutError as TOT
    executor = TPE(max_workers=1)
    future = executor.submit(api_func, symbol=code, indicator="按报告期")
    try:
        df = future.result(timeout=timeout)
        return df
    except TOT:
        print(f"    TIMEOUT {code} {table_type} ({timeout}s)")
        return None
    except Exception as e:
        return None
    finally:
        executor.shutdown(wait=False)


def _save_ths_table(df, code, table_name, col_map, conn, force=False):
    """将 THS 报表数据存入指定表（通用函数）"""
    if df is None or df.empty:
        return 0

    cursor = conn.cursor()
    existing = set()
    if not force:
        cursor.execute(f"SELECT code, report_date FROM {table_name} WHERE code = %s", (code,))
        existing = {(r[0], r[1]) for r in cursor.fetchall()}

    target_cols = [db_field for _, db_field in col_map.items() if db_field is not None]
    inserted = 0

    for _, row in df.iterrows():
        # THS 报告期格式: '2026-03-31'
        report_date_raw = str(row.get('报告期', ''))
        if not report_date_raw or report_date_raw == 'nan':
            continue
        rd = report_date_raw.replace('-', '')[:8]
        if len(rd) != 8:
            continue
        if (code, rd) in existing:
            continue

        rt = report_type_from_date(rd)
        ed = end_date_from_report(rd)

        insert_cols = []
        insert_vals = []
        seen_fields = set()
        for src_col, db_field in col_map.items():
            if db_field is None:
                continue
            if db_field in seen_fields:
                continue
            # THS 列名可能带 * 前缀，尝试精确匹配，再尝试去掉 * 后匹配
            matched_col = None
            if src_col in df.columns:
                matched_col = src_col
            else:
                # 尝试去掉 * 后再匹配
                unstar = src_col.lstrip('*')
                for c in df.columns:
                    if c.lstrip('*') == unstar:
                        matched_col = c
                        break
            if matched_col is None:
                continue
            val = parse_number(row[matched_col])
            if val is not None:
                insert_cols.append(db_field)
                insert_vals.append(val)
                seen_fields.add(db_field)

        if not insert_cols:
            continue

        set_parts = [f"{c} = VALUES({c})" for c in insert_cols]
        sql = f"""
            INSERT IGNORE INTO {table_name}
                (code, report_date, report_type, end_date, {', '.join(insert_cols)})
            VALUES (%s,%s,%s,%s, {', '.join(['%s']*len(insert_cols))})
        """
        try:
            cursor.execute(sql, [code, rd, rt, ed] + insert_vals)
            inserted += 1
        except Exception as e:
            print(f"    ERR {table_name} {code} {rd}: {e}")

    conn.commit()

    # 现金流量表：计算 free_cash_flow
    if table_name == 'stock_cashflow' and inserted > 0:
        try:
            cursor.execute("""
                UPDATE stock_cashflow
                SET free_cash_flow = COALESCE(net_operate_cf, 0) + COALESCE(net_invest_cf, 0)
                WHERE code = %s AND free_cash_flow IS NULL
                  AND net_operate_cf IS NOT NULL AND net_invest_cf IS NOT NULL
            """, (code,))
            conn.commit()
        except Exception as e:
            print(f"    ERR free_cash_flow {code}: {e}")

    return inserted


def _process_ths_table_stock(args_tuple):
    """THS 三大表并发工作线程

    args_tuple: (code, table_type, table_name, col_map, force)
    """
    code, table_type, table_name, col_map, force = args_tuple
    conn = None
    try:
        conn = pymysql.connect(**MYSQL_CONFIG)
        df = _fetch_ths_table(code, table_type)
        if df is None or df.empty:
            return (code, table_type, 0, 'no_data')
        n = _save_ths_table(df, code, table_name, col_map, conn, force)
        return (code, table_type, n, None)
    except Exception as e:
        return (code, table_type, 0, str(e)[:200])
    finally:
        if conn:
            conn.close()


def run_step3_ths(codes, force=False, ths_workers=8):
    """Step 3：用 THS API 更新三大表"""
    if not codes:
        print("  未获取到股票列表")
        return

    print(f"  THS 三大表更新：{len(codes)} 只股票，并发数={ths_workers}")

    # 三张表配置：(table_type, table_name, col_map)
    tables = [
        ('cash',   'stock_cashflow',  _THS_CASH_MAP),
        ('income',  'stock_income',   _THS_INCOME_MAP),
        ('balance', 'stock_balance',   _THS_BALANCE_MAP),
    ]

    # 为每只股票 × 每张表生成任务
    tasks = []
    for code in codes:
        for table_type, table_name, col_map in tables:
            tasks.append((code, table_type, table_name, col_map, force))

    total_inserted = {'cash': 0, 'income': 0, 'balance': 0}
    errors = {'cash': 0, 'income': 0, 'balance': 0}
    t0 = time.time()

    with ThreadPoolExecutor(max_workers=ths_workers) as executor:
        futures = {executor.submit(_process_ths_table_stock, t): t for t in tasks}
        done = 0
        total = len(futures)
        for future in as_completed(futures):
            done += 1
            code, table_type, n, err = future.result()
            if err:
                errors[table_type] += 1
            else:
                total_inserted[table_type] += n
            if done % 200 == 0 or done == total:
                elapsed = time.time() - t0
                print(f"  [{datetime.now().strftime('%H:%M:%S')}] 进度 {done}/{total} ({done*100//total}%) "
                      f"耗时 {elapsed:.0f}s")

    elapsed = time.time() - t0
    print(f"\n  THS 三大表完成，耗时 {elapsed:.0f}s")
    print(f"  现金流量表: 新增 {total_inserted['cash']} 条，失败 {errors['cash']} 只")
    print(f"  利润表:     新增 {total_inserted['income']} 条，失败 {errors['income']} 只")
    print(f"  资产负债表: 新增 {total_inserted['balance']} 条，失败 {errors['balance']} 只")


def run_step3_ths_cash_only(codes, force=False, ths_workers=8):
    """Step 3 只跑现金流量表（用于快速补 Q1 现金流数据）"""
    if not codes:
        print("  未获取到股票列表")
        return

    print(f"  THS 现金流量表（仅现金）：{len(codes)} 只股票，并发数={ths_workers}")

    tasks = [(code, 'cash', 'stock_cashflow', _THS_CASH_MAP, force) for code in codes]
    total_inserted = 0
    errors = 0
    t0 = time.time()

    with ThreadPoolExecutor(max_workers=ths_workers) as executor:
        futures = {executor.submit(_process_ths_table_stock, t): t for t in tasks}
        done = 0
        total = len(futures)
        for future in as_completed(futures):
            done += 1
            code, table_type, n, err = future.result()
            if err:
                errors += 1
            else:
                total_inserted += n
            if done % 500 == 0 or done == total:
                elapsed = time.time() - t0
                print(f"  [{datetime.now().strftime('%H:%M:%S')}] 进度 {done}/{total} ({done*100//total}%) "
                      f"耗时 {elapsed:.0f}s")

    elapsed = time.time() - t0
    print(f"\n  THS 现金流量表完成，耗时 {elapsed:.0f}s")
    print(f"  新增 {total_inserted} 条，失败 {errors} 只")


# ─────────────────────────────────────────────
# Step 4. Tushare 补充（留空，与原版一致）
# ─────────────────────────────────────────────

def run_step4_tushare(all_codes, force=False):
    print("  Step 4 (Tushare 补充) 暂未实现")
    pass


# ─────────────────────────────────────────────
# 主流程
# ─────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description='财务数据采集脚本（THS 版）')
    parser.add_argument('--year-start', type=int, default=None,
                        help='采集起始年份（默认当前年份-3）')
    parser.add_argument('--year-end', type=int, default=None,
                        help='采集结束年份（默认当前年份）')
    parser.add_argument('--year', type=int, default=None,
                        help='指定年份（兼容旧调用）')
    parser.add_argument('--force', action='store_true', help='强制重新采集')
    parser.add_argument('--validate', action='store_true', default=True, help='采集后数据校验')
    parser.add_argument('--no-validate', action='store_false', dest='validate', help='跳过校验')
    parser.add_argument('--ths-workers', type=int, default=8,
                        help='THS 步骤并发数（默认 8，0=串行）')
    parser.add_argument('--step', type=str, default=None,
                        help='只执行指定步骤: yjbb / ths / ths-cash / ths-all / sina / tushare')
    parser.add_argument('--code', type=str, default=None, help='指定股票代码（仅 ths/ths-all/ths-cash 步骤有效）')
    args = parser.parse_args()

    # 年份参数兼容
    current_year = datetime.now().year
    if args.year:
        year_start = args.year
        year_end = args.year
    else:
        year_start = args.year_start if args.year_start else max(2020, current_year - 3)
        year_end = args.year_end if args.year_end else current_year

    print(f"财务数据采集（THS 版）")
    print(f"  年份范围: {year_start} ~ {year_end}")
    print(f"  THS 并发数: {args.ths_workers}")
    print(f"  强制重采: {args.force}")
    print("=" * 60)

    t0 = time.time()

    # 获取股票列表（供 Step 2/3/4 使用）
    conn = get_conn()
    cursor = conn.cursor()
    if args.code:
        cursor.execute("SELECT code FROM stock_info WHERE length(code)=6 AND code = %s ORDER BY code", (args.code,))
    else:
        cursor.execute("SELECT code FROM stock_info WHERE length(code)=6 ORDER BY code")
    all_codes = [r[0] for r in cursor.fetchall()]
    conn.close()
    if args.code:
        print(f"单只股票模式: {all_codes[0] if all_codes else '未找到'}")
    else:
        print(f"股票总数: {len(all_codes)}")

    # ── Step 1: 东方财富业绩报表 ──────────────
    if not args.step or args.step == 'yjbb':
        print(f"\n── Step 1: 东方财富业绩报表 ──")
        for yr in range(year_start, year_end + 1):
            df = fetch_yjbb(yr)
            if df is not None:
                conn = get_conn()
                save_yjbb_to_indicator(df, conn, yr, args.force)
                conn.close()
        print(f"  Step 1 完成，耗时 {time.time()-t0:.0f}s")

    # ── Step 2: 同花顺财务摘要 ──────────────
    if not args.step or args.step == 'ths':
        print(f"\n── Step 2: 同花顺财务摘要（并发 {args.ths_workers}） ──")
        t2 = time.time()
        success = 0
        failed = 0
        if args.ths_workers > 0:
            with ThreadPoolExecutor(max_workers=args.ths_workers) as executor:
                futures = {executor.submit(_process_ths_stock, code, args.force, None): code
                           for code in all_codes}
                done = 0
                for future in as_completed(futures):
                    done += 1
                    code, n, err = future.result()
                    if err and err != 'no_data':
                        failed += 1
                    else:
                        success += 1
                    if done % 500 == 0 or done == len(all_codes):
                        print(f"  [{datetime.now().strftime('%H:%M:%S')}] Step 2 进度 {done}/{len(all_codes)} ({done*100//len(all_codes)}%)")
        else:
            for code in all_codes:
                code, n, err = _process_ths_stock(code, args.force, None)
                if err and err != 'no_data':
                    failed += 1
                else:
                    success += 1
        print(f"  Step 2 完成: 成功 {success}，失败 {failed}，耗时 {time.time()-t2:.0f}s")

    # ── Step 3: THS 三大表 ──────────────────
    if not args.step or args.step == 'ths-all' or args.step == 'ths-cash' or args.step == 'sina':
        # --step=sina 作为兼容入口，也走 THS（原新浪已限速）
        print(f"\n── Step 3: 同花顺三大表（THS API）──")
        t3 = time.time()
        if args.step == 'ths-cash':
            run_step3_ths_cash_only(all_codes, args.force, args.ths_workers)
        else:
            run_step3_ths(all_codes, args.force, args.ths_workers)
        print(f"  Step 3 完成，耗时 {time.time()-t3:.0f}s")

    # ── Step 4: Tushare 补充 ────────────────
    if not args.step or args.step == 'tushare':
        print(f"\n── Step 4: Tushare 补充 ──")
        run_step4_tushare(all_codes, args.force)

    # ── 校验 ─────────────────────────────────
    if args.validate and not args.step:
        print(f"\n── 数据校验 ──")
        conn = get_conn()
        cursor = conn.cursor()
        for tbl in ['stock_financial_indicator', 'stock_income', 'stock_cashflow', 'stock_balance']:
            cursor.execute(f"SELECT COUNT(*) FROM {tbl}")
            cnt = cursor.fetchone()[0]
            print(f"  {tbl}: {cnt} 条")
        conn.close()

    print(f"\n全部完成，总耗时 {time.time()-t0:.0f}s")


if __name__ == '__main__':
    main()
