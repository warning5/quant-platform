#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
财务数据采集脚本
采集来源：
  1. stock_yjbb_em        - 东方财富业绩报表（批量，全市场）
  2. stock_financial_abstract_ths - 同花顺财务摘要（逐只，补充指标）

用法：
  python update_data/update_financial_data.py [--year-start 2022] [--year-end 2024] [--force] [--validate]
  --year-start  采集起始年份（默认当前年份-3）
  --year-end    采集结束年份（默认当前年份）
  --year        指定年份（已被 year-start/year-end 取代，兼容旧调用）
  --force       强制重新采集（默认跳过已有数据）
  --validate    采集完成后进行数据校验（默认启用）
  --no-validate 跳过数据校验
"""

import argparse
import re
import sys
import time
from datetime import datetime

import akshare as ak
import pymysql

DB_CONFIG = {
    'host': 'localhost',
    'port': 3306,
    'user': 'root',
    'password': '123456',
    'database': 'stock',
    'charset': 'utf8mb4',
}


# ─────────────────────────────────────────────
# 工具函数
# ─────────────────────────────────────────────

def get_conn():
    return pymysql.connect(**DB_CONFIG)

def parse_number(val):
    """将各种格式的数值字符串转为 float 或 None"""
    import math
    if val is None:
        return None
    # 处理 numpy NaN / float NaN
    try:
        if isinstance(val, float) and math.isnan(val):
            return None
    except (TypeError, ValueError):
        pass
    s = str(val).strip()
    if s == '' or s == 'False' or s == '-' or s == '--' or s == 'nan' or s == 'NaN':
        return None
    s = s.replace(',', '')
    # 去掉百分号
    if s.endswith('%'):
        try:
            return float(s[:-1])
        except ValueError:
            return None
    # 去掉 '亿' / '万' 单位
    m = re.match(r'^([+-]?[\d.]+)\s*(亿|万)$', s)
    if m:
        num = float(m.group(1))
        unit = m.group(2)
        if unit == '亿':
            return num * 1e8
        else:
            return num * 1e4
    try:
        return float(s)
    except (ValueError, TypeError):
        return None

def report_type_from_date(report_date_str):
    """
    从报告期字符串推导 report_type
    '20241231' -> 4(年报), '20240630' -> 2(中报), '20240930' -> 3(三季报), '20240331' -> 1(一季报)
    """
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
    """从报告期字符串提取截止日期"""
    s = str(report_date_str).replace('-', '')
    return f"{s[:4]}-{s[4:6]}-{s[6:8]}"


# ─────────────────────────────────────────────
# 1. 东方财富业绩报表（批量全市场）
# ─────────────────────────────────────────────

def fetch_yjbb(year):
    """拉取指定年份的业绩报表，返回 DataFrame"""
    # 当前年份的年报要到次年4月才发布完毕，当年直接跳过
    current_year = datetime.now().year
    if year >= current_year:
        print(f"  跳过 {year} 年报（尚未发布）")
        return None
    date_str = f'{year}1231'
    print(f"  拉取东方财富业绩报表 {date_str} ...")
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
    """将业绩报表数据存入 stock_financial_indicator"""
    cursor = conn.cursor()

    # 检查已存在的 (code, report_date) 对
    existing = set()
    if not force:
        cursor.execute("SELECT code, report_date FROM stock_financial_indicator")
        existing = {(r[0], r[1]) for r in cursor.fetchall()}

    # yjbb date 参数对应年报报告期
    rd = f'{report_year}1231'
    rt = 4  # 年报
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
                INSERT INTO stock_financial_indicator
                    (code, report_date, report_type, end_date,
                     bps, revenue_yoy, net_profit_yoy, roe,
                     gross_profit_margin)
                VALUES (%s,%s,%s,%s, %s,%s,%s,%s, %s)
                ON DUPLICATE KEY UPDATE
                    bps = VALUES(bps),
                    revenue_yoy = VALUES(revenue_yoy),
                    net_profit_yoy = VALUES(net_profit_yoy),
                    roe = VALUES(roe),
                    gross_profit_margin = VALUES(gross_profit_margin)
            """, (code, rd, rt, ed,
                  bps, revenue_yoy, net_profit_yoy, roe, gross_margin))
            inserted += 1
        except Exception as e:
            print(f"    ERR {code} {rd}: {e}")

    conn.commit()
    print(f"  东方财富指标 {report_year}: 新增/更新 {inserted} 条")
    return inserted


# ─────────────────────────────────────────────
# 2. 同花顺财务摘要（逐只股票）
# ─────────────────────────────────────────────

def fetch_ths_abstract(code):
    """拉取单只股票的同花顺财务摘要"""
    try:
        df = ak.stock_financial_abstract_ths(symbol=code)
        return df
    except Exception as e:
        return None

def save_ths_to_tables(df, code, conn, force=False):
    """将同花顺摘要数据存入 financial_indicator + income"""
    if df is None or df.empty:
        return 0

    cursor = conn.cursor()

    # 获取已有记录
    existing = set()
    if not force:
        cursor.execute("SELECT code, report_date FROM stock_financial_indicator WHERE code = %s", (code,))
        existing = {(r[0], r[1]) for r in cursor.fetchall()}

    # 列名映射到数据库字段
    COL_MAP_INDICATOR = {
        '销售净利率': 'net_profit_margin',
        '销售毛利率': 'gross_profit_margin',
        '净资产收益率': 'roe',
        '净资产收益率-摊薄': 'roe',  # 优先用摊薄
        '存货周转率': 'inventory_turnover',
        '存货周转天数': 'inventory_turnover_days',
        '应收账款周转天数': 'ar_turnover_days',
        '流动比率': 'current_ratio',
        '速动比率': 'quick_ratio',
        '资产负债率': 'debt_to_asset_ratio',
    }

    COL_MAP_INCOME = {
        '净利润-净利润': 'net_profit',            # akshare年报返回的母公司净利润
        '净利润': 'net_profit',                    # 同花顺摘要的母公司净利润
        '营业总收入': 'total_revenue',
        '扣非净利润': 'deducted_np_parent_company',
        '基本每股收益': 'eps_basic',
    }

    inserted = 0
    for _, row in df.iterrows():
        report_date_raw = str(row.get('报告期', ''))
        if not report_date_raw:
            continue

        # 标准化: '2024-12-31' -> '20241231'
        rd = report_date_raw.replace('-', '')[:8]
        if len(rd) != 8:
            continue

        if (code, rd) in existing:
            continue

        rt = report_type_from_date(rd)
        ed = end_date_from_report(rd)

        # 解析指标值
        indicator_vals = {}
        income_vals = {}
        for col_name, db_field in COL_MAP_INDICATOR.items():
            if col_name in row.index:
                val = parse_number(row[col_name])
                if val is not None:
                    indicator_vals[db_field] = val

        for col_name, db_field in COL_MAP_INCOME.items():
            if col_name in row.index:
                val = parse_number(row[col_name])
                if val is not None:
                    income_vals[db_field] = val

        # ─── 写入 stock_financial_indicator ───
        indicator_cols = ['net_profit_margin', 'gross_profit_margin', 'roe',
                          'inventory_turnover', 'inventory_turnover_days', 'ar_turnover_days',
                          'current_ratio', 'quick_ratio', 'debt_to_asset_ratio']
        if indicator_vals:
            # 构建 UPDATE 部分
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
                    INSERT INTO stock_financial_indicator
                        (code, report_date, report_type, end_date, {', '.join(insert_cols)})
                    VALUES (%s,%s,%s,%s, {', '.join(['%s']*len(insert_cols))})
                    ON DUPLICATE KEY UPDATE {', '.join(set_parts)}
                """
                try:
                    cursor.execute(sql, [code, rd, rt, ed] + insert_vals)
                    inserted += 1
                except Exception as e:
                    print(f"    ERR indicator {code} {rd}: {e}")

        # ─── 写入 stock_income（补充关键指标）───
        if income_vals:
            income_cols = ['net_profit', 'net_profit_incl_minority', 'total_revenue', 'deducted_np_parent_company', 'eps_basic']
            set_parts = []
            insert_cols = []
            insert_vals = []
            for col in income_cols:
                if col in income_vals:
                    set_parts.append(f"{col} = VALUES({col})")
                    insert_cols.append(col)
                    insert_vals.append(income_vals[col])

            if insert_cols:
                sql = f"""
                    INSERT INTO stock_income
                        (code, report_date, report_type, end_date, {', '.join(insert_cols)})
                    VALUES (%s,%s,%s,%s, {', '.join(['%s']*len(insert_cols))})
                    ON DUPLICATE KEY UPDATE {', '.join(set_parts)}
                """
                try:
                    cursor.execute(sql, [code, rd, rt, ed] + insert_vals)
                except Exception as e:
                    print(f"    ERR income {code} {rd}: {e}")

    conn.commit()
    return inserted


# ─────────────────────────────────────────────
# 3. 新浪三大表（逐只股票详细数据）
# ─────────────────────────────────────────────

def fetch_sina_report(code, symbol_type, timeout=15):
    """从新浪拉取单只股票的三大表之一
    symbol_type: '利润表' / '资产负债表' / '现金流量表'
    timeout: 单次请求超时秒数
    """
    from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError

    def _fetch():
        return ak.stock_financial_report_sina(stock=code, symbol=symbol_type)

    executor = ThreadPoolExecutor(max_workers=1)
    future = executor.submit(_fetch)
    try:
        df = future.result(timeout=timeout)
        return df
    except FuturesTimeoutError:
        print(f"    TIMEOUT {code} {symbol_type} ({timeout}s)")
        return None
    except Exception:
        return None
    finally:
        # 不 cancel —— cancel 只能阻止未开始的任务，无法终止运行中的线程
        # 直接 shutdown(wait=False) 丢弃后台线程，避免卡在 with 块等待
        executor.shutdown(wait=False)

# 利润表字段映射（新浪 -> 数据库）
# 2026-04-16 修正：补全缺失字段，修正名称不匹配
# 字段含义：
#   net_profit          = 归属母公司净利润（np_parent_company_owners）
#   net_profit_incl_minority = 合并报表净利润含少数股东（= net_profit + np_minority）
INCOME_MAP = {
    '营业总收入': 'total_revenue',
    '营业收入': 'revenue',
    '营业总成本': 'total_cost',
    '营业成本': 'operating_cost',
    '研发费用': 'rd_expense',
    '销售费用': 'selling_expense',
    '管理费用': 'admin_expense',
    '财务费用': 'finance_expense',
    '营业利润': 'operating_profit',
    '利润总额': 'total_profit',
    '所得税费用': 'income_tax',
    '净利润': 'net_profit_incl_minority',          # 合并报表净利润（含少数股东）
    '归属于母公司所有者的净利润': 'net_profit',      # 归属母公司净利润
    '少数股东损益': 'np_minority',
    '基本每股收益': 'eps_basic',
    '稀释每股收益': 'eps_diluted',
    '其他综合收益': 'other_comprehensive_income',
    '综合收益总额': 'total_comprehensive_income',
    # 以下不映射或跳过
    '归属于母公司所有者的其他综合收益': None,
    '归属于母公司所有者的综合收益总额': None,
    '归属于少数股东的其他综合收益': None,
    '归属于少数股东的综合收益总额': None,
}

# 资产负债表字段映射
# 2026-04-16 修正：修正名称不匹配，补全缺失字段
# 注意：多个新浪字段可映射到同一db_field，save_sina_to_table会取第一个非空值
# 排列顺序决定优先级：精确字段 > 合计字段 > 兜底字段
BALANCE_MAP = {
    # 资产
    '资产总计': 'total_assets',
    '流动资产合计': 'total_current_assets',
    '非流动资产合计': 'total_non_current_assets',
    '货币资金': 'cash_and_equivalents',
    '现金及存放中央银行款项': 'cash_and_equivalents',  # 银行股备选
    '交易性金融资产': 'trading_assets',
    '应收票据': 'notes_receivable',
    '应收票据及应收账款': 'notes_receivable',  # 合并字段兜底（优先取应收票据）
    '应收账款': 'accounts_receivable',
    '预付款项': 'prepayments',
    '其他应收款': 'other_receivable',
    '其他应收款(合计)': 'other_receivable',  # 汇总兜底
    '存货': 'inventory',
    '合同资产': 'contract_assets',
    '长期股权投资': 'long_term_equity_invest',
    '固定资产净额': 'fixed_assets',
    '固定资产及清理合计': 'fixed_assets',  # 兜底：含清理的合计
    '在建工程合计': 'construction_in_progress',  # 优先取合计
    '在建工程': 'construction_in_progress',
    '无形资产': 'intangible_assets',
    '商誉': 'goodwill',
    '长期待摊费用': 'long_term_prepaid_expense',
    '递延所得税资产': 'deferred_tax_assets',
    # 负债
    '负债合计': 'total_liabilities',
    '流动负债合计': 'total_current_liabilities',
    '非流动负债合计': 'total_non_current_liabilities',
    '短期借款': 'short_term_borrowing',
    '应付票据': 'notes_payable',
    '应付账款': 'accounts_payable',
    '预收款项': 'advance_peceipts',
    '合同负债': 'contract_liabilities',
    '应付职工薪酬': 'employee_benefit_payable',
    '应交税费': 'taxs_payable',
    '其他应付款': 'other_payable',
    '长期借款': 'long_term_borrowing',
    '应付债券': 'bonds_payable',
    '租赁负债': 'lease_liabilities',
    '递延所得税负债': 'deferred_tax_liabilities',
    # 所有者权益
    '所有者权益(或股东权益)合计': 'total_equity',
    '归属于母公司股东权益合计': 'parent_equity',
    '归属于母公司所有者权益合计': 'parent_equity',  # 新浪新旧格式兼容
    '少数股东权益': 'minority_interests',
    '实收资本(或股本)': 'paid_in_capital',
    '资本公积': 'capital_reserve',
    '减:库存股': 'treasury_stock',
    '盈余公积': 'surplus_reserve',
    '未分配利润': 'undistributed_profit',
    # 跳过（映射到None的字段不写入数据库）
    '负债和所有者权益(或股东权益)总计': None,
    '负债及股东权益总计': None,
    '负债及股东权益合计': None,
    '优先股': None,
    '永续债': None,
    '其他权益工具': None,
    '专项储备': None,
    '一般风险准备': None,
    '其他综合收益': None,
}

# 现金流量表字段映射
# 2026-04-16 修正：修正名称不匹配，补全缺失字段
CASHFLOW_MAP = {
    # 经营活动
    '经营活动产生的现金流量净额': 'net_operate_cf',
    '销售商品、提供劳务收到的现金': 'cash_received_sales',
    '收到的税费返还': 'tax_refund_received',
    '购买商品、接受劳务支付的现金': 'cash_paid_goods_services',
    '支付给职工以及为职工支付的现金': 'cash_paid_employee',
    '支付的各项税费': 'cash_paid_tax',
    # 投资活动
    '投资活动产生的现金流量净额': 'net_invest_cf',
    '收回投资所收到的现金': 'cash_received_invest_income',
    '取得投资收益收到的现金': 'cash_received_invest_return',
    '处置固定资产、无形资产和其他长期资产所收回的现金净额': 'dispose_invest_income',
    '投资所支付的现金': 'cash_paid_invest',
    '购建固定资产、无形资产和其他长期资产所支付的现金': 'cash_paid_acquisition',
    # 筹资活动
    '筹资活动产生的现金流量净额': 'net_finance_cf',
    '吸收投资收到的现金': 'cash_received_absorb_invest',
    '吸收投资所收到的现金': 'cash_received_absorb_invest',  # 新浪新旧格式兼容
    '取得借款收到的现金': 'cash_received_borrowing',
    '偿还债务支付的现金': 'cash_paid_borrowing',
    '分配股利、利润或偿付利息所支付的现金': 'cash_paid_dividend',
    # 汇率及现金
    '汇率变动对现金及现金等价物的影响': 'exchange_rate_effect',
    '汇率变动对现金的影响': 'exchange_rate_effect',  # 新浪新旧格式兼容
    '现金及现金等价物净增加额': 'net_cash_increase',
    '期初现金及现金等价物余额': 'cash_at_beginning',
    '期末现金及现金等价物余额': 'cash_at_end',
}

def save_sina_to_table(df, code, table_name, col_map, conn, force=False):
    """将新浪报表数据存入指定表"""
    if df is None or df.empty:
        return 0

    cursor = conn.cursor()

    existing = set()
    if not force:
        cursor.execute(f"SELECT code, report_date FROM {table_name} WHERE code = %s", (code,))
        existing = {(r[0], r[1]) for r in cursor.fetchall()}

    # 只取映射中存在的列
    target_cols = [db_field for _, db_field in col_map.items() if db_field is not None]

    inserted = 0
    for _, row in df.iterrows():
        report_date_raw = str(row.get('报告日', ''))
        rd = report_date_raw.replace('-', '')[:8]
        if len(rd) != 8:
            continue

        if (code, rd) in existing:
            continue

        rt = report_type_from_date(rd)
        ed = end_date_from_report(rd)

        # 解析值（同列取第一个非空值，优先匹配排在前面的别名）
        insert_cols = []
        insert_vals = []
        seen_fields = set()
        for src_col, db_field in col_map.items():
            if db_field is None:
                continue
            if db_field in seen_fields:
                continue  # 同一db_field只取第一个有值的
            if src_col in row.index:
                val = parse_number(row[src_col])
                if val is not None:
                    insert_cols.append(db_field)
                    insert_vals.append(val)
                    seen_fields.add(db_field)

        if not insert_cols:
            continue

        set_parts = [f"{c} = VALUES({c})" for c in insert_cols]
        sql = f"""
            INSERT INTO {table_name}
                (code, report_date, report_type, end_date, {', '.join(insert_cols)})
            VALUES (%s,%s,%s,%s, {', '.join(['%s']*len(insert_cols))})
            ON DUPLICATE KEY UPDATE {', '.join(set_parts)}
        """
        try:
            cursor.execute(sql, [code, rd, rt, ed] + insert_vals)
            inserted += 1
        except Exception as e:
            print(f"    ERR {table_name} {code} {rd}: {e}")

    conn.commit()

    # 写入后计算 free_cash_flow = net_operate_cf + net_invest_cf
    if table_name == 'stock_cashflow' and inserted > 0:
        try:
            cursor.execute("""
                UPDATE stock_cashflow
                SET free_cash_flow = COALESCE(net_operate_cf, 0) + COALESCE(net_invest_cf, 0)
                WHERE code = %s AND free_cash_flow IS NULL
                  AND net_operate_cf IS NOT NULL AND net_invest_cf IS NOT NULL
            """, (code,))
            conn.commit()
        except Exception:
            pass  # FCF 计算失败不影响主流程

    return inserted


# ─────────────────────────────────────────────
# 主流程
# ─────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description='财务数据采集')
    parser.add_argument('--year', type=int, default=None, help='指定年份（已被 year-start/year-end 取代）')
    parser.add_argument('--year-start', type=int, default=None, help='采集起始年份')
    parser.add_argument('--year-end', type=int, default=None, help='采集结束年份')
    parser.add_argument('--force', action='store_true', help='强制重新采集')
    parser.add_argument('--step', choices=['yjbb', 'ths', 'sina'], default=None,
                        help='只执行指定步骤')
    parser.add_argument('--code', type=str, default=None, help='指定股票代码（仅 ths/sina 步骤有效）')
    parser.add_argument('--start-code', type=str, default=None, help='从指定代码开始（仅 sina 步骤有效，跳过此代码之前的股票）')
    parser.add_argument('--validate', action='store_true', help='强制执行数据校验')
    parser.add_argument('--no-validate', action='store_true', help='跳过数据校验')
    args = parser.parse_args()

    conn = get_conn()

    # 确定要采集的年份
    if args.year:
        # 兼容旧版单个 --year 参数
        years = [args.year]
    elif args.year_start is not None or args.year_end is not None:
        current_year = datetime.now().year
        start_year = args.year_start if args.year_start is not None else (current_year - 3)
        end_year = args.year_end if args.year_end is not None else current_year
        years = [y for y in range(start_year, end_year + 1) if y >= 2010]
    else:
        current_year = datetime.now().year
        years = [y for y in range(current_year - 3, current_year) if y >= 2010]
    print(f"采集年份: {years}")
    print(f"强制模式: {args.force}")
    print()

    # ─── Step 1: 东方财富业绩报表（批量） ───
    if args.step is None or args.step == 'yjbb':
        print("=" * 60)
        print("Step 1: 东方财富业绩报表（批量）")
        print("=" * 60)
        global year
        for year in years:
            df = fetch_yjbb(year)
            if df is not None:
                save_yjbb_to_indicator(df, conn, year, args.force)
            time.sleep(1)

    # ─── Step 2: 同花顺财务摘要（逐只） ───
    if args.step is None or args.step == 'ths':
        print()
        print("=" * 60)
        print("Step 2: 同花顺财务摘要")
        print("=" * 60)

        cursor = conn.cursor()
        if args.code:
            codes = [args.code]
        else:
            cursor.execute("SELECT code FROM stock_info ORDER BY code")
            codes = [r[0] for r in cursor.fetchall()]
        print(f"共 {len(codes)} 只股票")

        total_inserted = 0
        for i, code in enumerate(codes):
            if (i + 1) % 50 == 0:
                print(f"  进度: {i+1}/{len(codes)}，已插入 {total_inserted} 条")
            if (i + 1) % 10 == 0:
                time.sleep(1)  # 控制频率

            df = fetch_ths_abstract(code)
            n = save_ths_to_tables(df, code, conn, args.force)
            total_inserted += n

        print(f"  同花顺摘要完成: 共新增/更新 {total_inserted} 条")

    # ─── Step 3: 新浪三大表（逐只） ───
    if args.step is None or args.step == 'sina':
        print()
        print("=" * 60)
        print("Step 3: 新浪三大表（详细数据，较慢）")
        print("=" * 60)

        cursor = conn.cursor()
        if args.code:
            codes = [args.code]
        else:
            cursor.execute("SELECT code FROM stock_info ORDER BY code")
            codes = [r[0] for r in cursor.fetchall()]
            # 支持 --start-code 断点续传
            if args.start_code:
                idx = next((i for i, c in enumerate(codes) if c >= args.start_code), len(codes))
                codes = codes[idx:]
                print(f"从 {args.start_code} 开始，剩余 {len(codes)} 只")
        print(f"共 {len(codes)} 只股票")

        total_inserted = 0
        for i, code in enumerate(codes):
            if (i + 1) % 20 == 0:
                print(f"  进度: {i+1}/{len(codes)}，已插入 {total_inserted} 条")
            if (i + 1) % 5 == 0:
                time.sleep(2)  # 新浪限制更严

            for symbol_type, table_name, col_map in [
                ('利润表', 'stock_income', INCOME_MAP),
                ('资产负债表', 'stock_balance', BALANCE_MAP),
                ('现金流量表', 'stock_cashflow', CASHFLOW_MAP),
            ]:
                df = fetch_sina_report(code, symbol_type)
                n = save_sina_to_table(df, code, table_name, col_map, conn, args.force)
                total_inserted += n

        print(f"  新浪三大表完成: 共新增/更新 {total_inserted} 条")

    conn.close()
    print("\n全部完成！")

    # ─── 数据校验 ───────────────────────────────────────────────────────────
    if args.no_validate:
        print("\n[校验] 已跳过数据校验（--no-validate）")
    else:
        print()
        validate_conn = get_conn()
        validate_financial_data(validate_conn, years)
        validate_conn.close()


# ─────────────────────────────────────────────────────────────────────────────
# 数据校验
# ─────────────────────────────────────────────────────────────────────────────

def validate_financial_data(conn, years):
    """采集完成后数据质量校验"""
    print("=" * 60)
    print("数据校验报告")
    print("=" * 60)

    cursor = conn.cursor(pymysql.cursors.DictCursor)

    # 1. 表级覆盖率统计
    print("\n【1】表级记录统计")
    print("-" * 50)
    tables = [
        ('stock_financial_indicator', '财务指标表'),
        ('stock_income',             '利润表'),
        ('stock_balance',            '资产负债表'),
        ('stock_cashflow',           '现金流量表'),
    ]
    for table, label in tables:
        cursor.execute(f"SELECT COUNT(*) as cnt, COUNT(DISTINCT code) as stock_cnt FROM {table}")
        r = cursor.fetchone()
        print(f"  {label:<12} {r['cnt']:>8,} 条  /  {r['stock_cnt']:>5,} 只股票")

    # 2. 各年份覆盖率
    print("\n【2】stock_financial_indicator 年份覆盖")
    print("-" * 50)
    cursor.execute("""
        SELECT report_year,
               COUNT(*) as record_cnt,
               COUNT(DISTINCT code) as stock_cnt
        FROM stock_financial_indicator
        GROUP BY report_year
        ORDER BY report_year DESC
        LIMIT 20
    """)
    rows = cursor.fetchall()
    if rows:
        print(f"  {'年份':<8} {'报告期数':>8} {'覆盖股票':>10} {'状态':>6}")
        for r in rows:
            status = "✓" if r['stock_cnt'] > 3000 else ("△" if r['stock_cnt'] > 1000 else "○")
            print(f"  {r['report_year']:<8} {r['record_cnt']:>8,} {r['stock_cnt']:>10,} {status:>6}")
        print("  注: ✓ >3000只  △ 1000-3000只  ○ <1000只")
    else:
        print("  无数据")

    # 3. 关键字段空值率
    print("\n【3】关键字段空值率（stock_financial_indicator）")
    print("-" * 50)
    key_fields = [
        'revenue',        # 营业收入
        'net_profit',     # 净利润
        'total_assets',   # 总资产
        'total_liabilities',  # 总负债
        'gross_margin',   # 毛利率
        'net_margin',     # 净利率
    ]
    cursor.execute("SELECT COUNT(*) as total FROM stock_financial_indicator")
    total = cursor.fetchone()['total'] or 1
    for field in key_fields:
        cursor.execute(f"""
            SELECT COUNT(*) as cnt
            FROM stock_financial_indicator
            WHERE {field} IS NOT NULL AND {field} != 0
        """)
        non_null = cursor.fetchone()['cnt']
        rate = non_null / total * 100
        bar = "█" * int(rate / 5) + "░" * (20 - int(rate / 5))
        mark = "✓" if rate >= 80 else ("△" if rate >= 50 else "✗")
        print(f"  {field:<22} {rate:>5.1f}%  [{bar}] {mark}")

    # 4. 净利润同比异常检测（变化超过10倍）
    print("\n【4】净利润同比异常检测（变化 > 10倍 或 扭亏/转亏）")
    print("-" * 50)
    cursor.execute("""
        SELECT a.code, a.report_year, a.report_type,
               a.net_profit as cur_profit,
               b.net_profit as prev_profit,
               ROUND(a.net_profit / NULLIF(b.net_profit, 0), 2) as yoy_ratio
        FROM stock_financial_indicator a
        LEFT JOIN stock_financial_indicator b
          ON a.code = b.code
         AND b.report_year = a.report_year - 1
         AND b.report_type = a.report_type
        WHERE a.report_year >= YEAR(CURDATE()) - 3
          AND a.report_type IN (1, 2, 4)
          AND a.net_profit IS NOT NULL AND b.net_profit IS NOT NULL
          AND (ABS(a.net_profit) > 1e8 OR ABS(b.net_profit) > 1e8)
          AND (
            ABS(a.net_profit / NULLIF(b.net_profit, 0)) > 10
            OR (a.net_profit > 0 AND b.net_profit < 0)
            OR (a.net_profit < 0 AND b.net_profit > 0)
          )
        ORDER BY ABS(COALESCE(yoy_ratio, 0)) DESC
        LIMIT 15
    """)
    anomalies = cursor.fetchall()
    if anomalies:
        print(f"  {'代码':<10} {'年份':<6} {'类型':<4} {'当年净利润':>14} {'上年净利润':>14} {'同比':>8}  原因")
        for r in anomalies:
            if r['cur_profit'] > 0 and r['prev_profit'] < 0:
                reason = "扭亏"
            elif r['cur_profit'] < 0 and r['prev_profit'] > 0:
                reason = "转亏"
            else:
                ratio = r['yoy_ratio'] or 0
                reason = f"激增{ratio:.0f}x" if ratio > 0 else f"骤降{abs(ratio):.0f}x"
            print(f"  {r['code']:<10} {r['report_year']:<6} {r['report_type']:<4} "
                  f"{r['cur_profit']:>14,.0f} {r['prev_profit']:>14,.0f} {reason:>10}")
        print(f"\n  共发现 {len(anomalies)} 条异常（仅显示前15条），通常因资产重组/会计政策变更/季节性因素")
    else:
        print("  未发现明显异常")

    # 5. 缺失年份的股票（应有时段但无数据的）
    print("\n【5】数据缺失警告（近3年有财报但无数据的股票）")
    print("-" * 50)
    current_year = datetime.now().year
    cursor.execute(f"""
        SELECT si.code, si.name, si.market,
               COUNT(sfi.report_date) as record_cnt
        FROM stock_info si
        LEFT JOIN stock_financial_indicator sfi
          ON si.code = sfi.code
         AND sfi.report_year >= {current_year - 2}
         AND sfi.report_type IN (1, 2, 4)
        WHERE si.list_status = 'L'
        GROUP BY si.code, si.name, si.market
        HAVING record_cnt = 0
        LIMIT 20
    """)
    missing = cursor.fetchall()
    if missing:
        print(f"  {'代码':<10} {'名称':<10} {'市场':<5}  备注")
        for r in missing:
            print(f"  {r['code']:<10} {r['name'][:8]:<10} {r['market']:<5}  近3年财报缺失")
        cursor.execute(f"""
            SELECT COUNT(*) as total FROM stock_info WHERE list_status = 'L'
        """)
        total_stocks = cursor.fetchone()['total']
        print(f"\n  共 {len(missing)} 只股票近3年财报完全缺失（显示前20条，共 {total_stocks} 只有效股票）")
    else:
        print("  无明显缺失")

    print("\n" + "=" * 60)
    print("校验完成")
    print("=" * 60)


if __name__ == '__main__':
    main()
