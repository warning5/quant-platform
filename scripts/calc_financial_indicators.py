#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
财务指标计算脚本
从 stock_income / stock_balance / stock_cashflow 表的原始数据，
计算 stock_financial_indicator 中无法直接采集的衍生指标。

公式：
  roa                          = 净利润(含少数) / 总资产平均 * 100
  roic                         = (净利润+利息费用*(1-税率)) / (总权益+总负债-现金) * 100
                                （利息费用≈财务费用；税率=所得税/利润总额）
  revenue_yoy                  = (本期营业总收入-上年同期) / |上年同期| * 100
  net_profit_yoy               = (本期净利润-上年同期) / |上年同期| * 100
  operating_profit_yoy         = (本期营业利润-上年同期) / |上年同期| * 100
  total_assets_yoy             = (本期总资产-上年同期) / |上年同期| * 100
  total_equity_yoy             = (本期归属母公司权益-上年同期) / |上年同期| * 100
  debt_to_equity_ratio         = 总负债 / 归属母公司权益 * 100
  interest_coverage_ratio      = (利润总额+财务费用) / |财务费用|
  accounts_receivable_turnover = 营业总收入 / 应收账款平均
  total_assets_turnover        = 营业总收入 / 总资产平均
  operating_cf_to_np           = 经营活动现金流净额 / |净利润(含少数)| * 100
  operating_cf_to_debt         = 经营活动现金流净额 / 总负债 * 100
  sales_cash_ratio             = 销售商品收到的现金 / 营业总收入 * 100
  operating_revenue_per_share  = 营业总收入 / 总股本
  bps                          = 归属母公司权益 / 总股本
  eps_basic                    = income表原始值 或 归母净利润/总股本
  debt_to_asset_ratio          = 总负债 / 总资产 * 100
  current_ratio                = 流动资产 / 流动负债
  net_profit_margin            = 净利润(含少数) / 营业总收入 * 100
  inventory_turnover           = 营业成本 / 存货（年化）
  ar_turnover_days             = 365 / 应收账款周转率

注意：
  - 同比增长率需要上一年度同期数据
  - 年化处理：一季报*4，中报*2，三季报*4/3
  - 计算前需确保三大表数据已采集完成
"""

import sys
import pymysql

from db_config import MYSQL_CONFIG


def get_conn():
    return pymysql.connect(**MYSQL_CONFIG)


def annual_factor(report_date):
    """年化系数：年报=1, 中报=2, 一季报=4, 三季报=4/3"""
    month = int(report_date[4:6])
    if month == 12:
        return 1.0
    elif month == 6:
        return 2.0
    elif month == 3:
        return 4.0
    elif month == 9:
        return 4.0 / 3.0
    return 1.0


def safe_div(numerator, denominator, default=None):
    """安全除法，分母为0返回None"""
    if numerator is None or denominator is None or denominator == 0:
        return default
    return numerator / denominator


def calc_yoy(current, previous):
    """计算同比增长率(%)"""
    if current is None or previous is None or previous == 0:
        return None
    return (current - previous) / abs(previous) * 100


def to_float(v):
    """将 Decimal 或其他类型转为 float"""
    if v is None:
        return None
    return float(v)


def main():
    code_filter = sys.argv[1] if len(sys.argv) > 1 else None
    conn = get_conn()
    cur = conn.cursor()

    # 获取所有 indicator 的 (code, report_date)
    if code_filter:
        cur.execute(
            "SELECT code, report_date FROM stock_financial_indicator WHERE code = %s",
            (code_filter,))
    else:
        cur.execute("SELECT code, report_date FROM stock_financial_indicator WHERE LEFT(report_date,4) >= '2010'")
    indicator_keys = cur.fetchall()
    print(f"共 {len(indicator_keys)} 条 indicator 记录待计算")

    # 加载 income 数据: key=(code,report_date)
    print("加载 income 数据...")
    cur.execute("SELECT code, report_date, total_revenue, operating_profit, operating_cost, "
                "net_profit_incl_minority, net_profit, total_profit, finance_expense, income_tax, eps_basic, "
                "deducted_np_parent_company, np_parent_company_owners FROM stock_income")
    income_data = {}
    for r in cur.fetchall():
        income_data[(r[0], r[1])] = {
            'total_revenue': to_float(r[2]),
            'operating_profit': to_float(r[3]),
            'operating_cost': to_float(r[4]),
            'net_profit_incl_minority': to_float(r[5]),
            'net_profit': to_float(r[6]),
            'total_profit': to_float(r[7]),
            'finance_expense': to_float(r[8]),
            'income_tax': to_float(r[9]),
            'eps_basic': to_float(r[10]),
            'deducted_np_parent_company': to_float(r[11]),
            'np_parent_company_owners': to_float(r[12]),
        }

    # 加载 balance 数据
    print("加载 balance 数据...")
    cur.execute("SELECT code, report_date, total_assets, total_liabilities, "
                "total_equity, parent_equity, accounts_receivable, paid_in_capital, "
                "total_current_assets, total_current_liabilities, inventory "
                "FROM stock_balance")
    balance_data = {}
    for r in cur.fetchall():
        balance_data[(r[0], r[1])] = {
            'total_assets': to_float(r[2]),
            'total_liabilities': to_float(r[3]),
            'total_equity': to_float(r[4]),
            'parent_equity': to_float(r[5]),
            'accounts_receivable': to_float(r[6]),
            'paid_in_capital': to_float(r[7]),
            'total_current_assets': to_float(r[8]),
            'total_current_liabilities': to_float(r[9]),
            'inventory': to_float(r[10]),
        }

    # 加载 cashflow 数据
    print("加载 cashflow 数据...")
    cur.execute("SELECT code, report_date, net_operate_cf, cash_received_sales, free_cash_flow "
                "FROM stock_cashflow")
    cashflow_data = {}
    for r in cur.fetchall():
        cashflow_data[(r[0], r[1])] = {
            'net_operate_cf': to_float(r[2]),
            'cash_received_sales': to_float(r[3]),
            'free_cash_flow': to_float(r[4]),
        }

    print("开始计算...")
    updated = 0
    errors = 0

    for code, report_date in indicator_keys:
        inc = income_data.get((code, report_date), {})
        bal = balance_data.get((code, report_date), {})
        cf = cashflow_data.get((code, report_date), {})

        total_revenue = inc.get('total_revenue')
        operating_profit = inc.get('operating_profit')
        operating_cost = inc.get('operating_cost')
        # 含少数股东净利润（用于 ROA/ROIC/CF/NP 等指标，与总资产/总权益口径匹配）
        net_profit = inc.get('net_profit_incl_minority') or inc.get('net_profit')
        # 归母净利润（用于利润增速，与官方披露口径一致）
        net_profit_attr = inc.get('net_profit')
        total_profit = inc.get('total_profit')
        finance_expense = inc.get('finance_expense')
        income_tax = inc.get('income_tax')
        eps_basic_raw = inc.get('eps_basic')

        total_assets = bal.get('total_assets')
        total_liabilities = bal.get('total_liabilities')
        total_equity = bal.get('total_equity')
        parent_equity = bal.get('parent_equity')
        accounts_receivable = bal.get('accounts_receivable')
        paid_in_capital = bal.get('paid_in_capital')
        total_current_assets = bal.get('total_current_assets')
        total_current_liabilities = bal.get('total_current_liabilities')
        inventory = bal.get('inventory')

        net_operate_cf = cf.get('net_operate_cf')
        cash_received_sales = cf.get('cash_received_sales')

        af = annual_factor(report_date)

        # ── 上年同期数据（用于同比增长率）──
        prev_year_rd = str(int(report_date[:4]) - 1) + report_date[4:]
        prev_inc = income_data.get((code, prev_year_rd), {})
        prev_bal = balance_data.get((code, prev_year_rd), {})

        prev_operating_profit = prev_inc.get('operating_profit')
        prev_total_revenue = prev_inc.get('total_revenue')
        # 上年同期归母净利润（用于利润增速）
        prev_net_profit_attr = prev_inc.get('net_profit')
        # 上年同期含少数股东净利润（用于营业利润增速的参照等）
        prev_net_profit = prev_inc.get('net_profit_incl_minority') or prev_inc.get('net_profit')
        prev_total_assets = prev_bal.get('total_assets')
        prev_parent_equity = prev_bal.get('parent_equity')

        # ── 计算各指标 ──

        # 1. roa = 净利润 / 总资产平均 * 100（年化）
        #    平均总资产 ≈ 期末总资产（简化，无期初数据时）
        roa = safe_div(net_profit, total_assets, 0) * 100 * af if net_profit and total_assets else None

        # 2. roic = (净利润 + 利息费用*(1-税率)) / (总权益 + 有息负债) * 100
        #    简化：有息负债 ≈ 总负债 - 无息部分，这里用 总权益+总负债-现金 作为投入资本近似
        #    更简化版本：用 EBIT / 投入资本
        tax_rate = safe_div(income_tax, total_profit, 0) if income_tax and total_profit and total_profit != 0 else 0.25
        ebit = (net_profit or 0) + (finance_expense or 0) * (1 - tax_rate)
        invested_cap = (total_equity or 0) + (total_liabilities or 0) - (bal.get('cash_and_equivalents') or 0)
        # 由于没加载 cash_and_equivalents，用总权益+总负债替代
        invested_cap2 = (total_equity or 0) + (total_liabilities or 0)
        roic = safe_div(ebit, invested_cap2, 0) * 100 * af if invested_cap2 else None
        if roic is not None:
            roic = round(roic, 4)

        # 3. revenue_yoy
        revenue_yoy = calc_yoy(total_revenue, prev_total_revenue)

        # 4. net_profit_yoy（归母净利润口径，与官方披露一致）
        net_profit_yoy = calc_yoy(net_profit_attr, prev_net_profit_attr)

        # 5. operating_profit_yoy
        operating_profit_yoy = calc_yoy(operating_profit, prev_operating_profit)

        # 4. total_assets_yoy
        total_assets_yoy = calc_yoy(total_assets, prev_total_assets)

        # 5. total_equity_yoy（用归属母公司权益）
        total_equity_yoy = calc_yoy(parent_equity, prev_parent_equity)

        # 6. debt_to_equity_ratio = 总负债 / 归属母公司权益 * 100
        debt_to_equity_ratio = safe_div(total_liabilities, parent_equity, 0) * 100 if total_liabilities and parent_equity else None

        # 7. interest_coverage_ratio = (利润总额 + 财务费用) / |财务费用|
        if finance_expense and finance_expense != 0 and total_profit is not None:
            interest_coverage_ratio = (total_profit + finance_expense) / abs(finance_expense)
        else:
            interest_coverage_ratio = None

        # 8. accounts_receivable_turnover = 营收 / 应收账款平均（年化）
        if total_revenue and accounts_receivable and accounts_receivable != 0:
            accounts_receivable_turnover = total_revenue / accounts_receivable * af
        else:
            accounts_receivable_turnover = None

        # 9. total_assets_turnover = 营收 / 总资产平均（年化）
        if total_revenue and total_assets and total_assets != 0:
            total_assets_turnover = total_revenue / total_assets * af
        else:
            total_assets_turnover = None

        # 10. operating_cf_to_np = 经营现金流 / |归母净利润| * 100
        # 用 np_parent_company_owners（新浪三大表来源）作为分母，与归母净利润口径一致
        np_attr = inc.get('np_parent_company_owners') or net_profit_attr  # 优先用归母净利润
        if net_operate_cf is not None and np_attr and np_attr != 0:
            operating_cf_to_np = net_operate_cf / abs(np_attr) * 100
        else:
            operating_cf_to_np = None

        # 11. operating_cf_to_debt = 经营现金流 / 总负债 * 100
        operating_cf_to_debt = safe_div(net_operate_cf, total_liabilities, 0) * 100 if net_operate_cf and total_liabilities else None

        # 12. sales_cash_ratio = 销售收现 / 营收 * 100
        sales_cash_ratio = safe_div(cash_received_sales, total_revenue, 0) * 100 if cash_received_sales and total_revenue else None

        # 13. operating_revenue_per_share = 营收 / 总股本
        operating_revenue_per_share = safe_div(total_revenue, paid_in_capital) if total_revenue and paid_in_capital else None

        # 16. bps = 归母权益 / 总股本
        bps = safe_div(parent_equity, paid_in_capital) if parent_equity and paid_in_capital else None

        # ── 新增指标 ──

        # 17. eps_basic: 优先取 income 表原始值，无则用归母净利润/总股本
        if eps_basic_raw:
            eps_basic = eps_basic_raw
        elif net_profit_attr and paid_in_capital and paid_in_capital != 0:
            eps_basic = net_profit_attr / paid_in_capital
        else:
            eps_basic = None

        # 18. debt_to_asset_ratio = 总负债 / 总资产 * 100
        debt_to_asset_ratio = safe_div(total_liabilities, total_assets) * 100 if total_liabilities and total_assets else None

        # 19. current_ratio = 流动资产 / 流动负债
        current_ratio = safe_div(total_current_assets, total_current_liabilities) if total_current_assets and total_current_liabilities else None

        # 20. net_profit_margin = 净利润(含少数) / 营业总收入 * 100（年化）
        if net_profit and total_revenue and total_revenue != 0:
            net_profit_margin = net_profit / total_revenue * 100 * af
        else:
            net_profit_margin = None

        # 21. inventory_turnover = 营业成本 / 存货平均（年化）
        if operating_cost and inventory and inventory != 0:
            inventory_turnover = operating_cost / inventory * af
        else:
            inventory_turnover = None

        # 22. ar_turnover_days = 365 / 应收账款周转率
        if total_revenue and accounts_receivable and accounts_receivable != 0:
            ar_ratio = total_revenue / accounts_receivable * af
            ar_turnover_days = 365 / ar_ratio if ar_ratio > 0 else None
        else:
            ar_turnover_days = None

        # 23. ar_to_np_ratio = 应收账款 / 净利润（含少数）* 100（年化）
        #    > 1000% 表明应收账款远大于盈利质量，危险信号
        #    参考报告：海立股份 5653% = 57.39亿应收 / 0.72亿净利 * 100
        if accounts_receivable and net_profit and net_profit != 0:
            ar_to_np_ratio = accounts_receivable / abs(net_profit) * 100 * af
        else:
            ar_to_np_ratio = None

        # 四舍五入
        def r4(v):
            return round(v, 4) if v is not None else None

        updates = {
            'roa': r4(roa),
            'roic': r4(roic),
            'revenue_yoy': r4(revenue_yoy),
            'net_profit_yoy': r4(net_profit_yoy),
            'operating_profit_yoy': r4(operating_profit_yoy),
            'total_assets_yoy': r4(total_assets_yoy),
            'total_equity_yoy': r4(total_equity_yoy),
            'debt_to_equity_ratio': r4(debt_to_equity_ratio),
            'interest_coverage_ratio': r4(interest_coverage_ratio),
            'accounts_receivable_turnover': r4(accounts_receivable_turnover),
            'total_assets_turnover': r4(total_assets_turnover),
            'operating_cf_to_np': r4(operating_cf_to_np),
            'operating_cf_to_debt': r4(operating_cf_to_debt),
            'sales_cash_ratio': r4(sales_cash_ratio),
            'operating_revenue_per_share': r4(operating_revenue_per_share),
            'bps': r4(bps),
            'eps_basic': r4(eps_basic),
            'debt_to_asset_ratio': r4(debt_to_asset_ratio),
            'current_ratio': r4(current_ratio),
            'net_profit_margin': r4(net_profit_margin),
            'inventory_turnover': r4(inventory_turnover),
            'ar_turnover_days': r4(ar_turnover_days),
            'ar_to_np_ratio': r4(ar_to_np_ratio),
            'net_operate_cf': cf.get('net_operate_cf'),
            'free_cash_flow': cf.get('free_cash_flow'),
        }

        # 只更新非空的值
        set_parts = []
        vals = []
        for col, val in updates.items():
            if val is not None:
                set_parts.append(f"{col} = %s")
                vals.append(val)

        if set_parts:
            sql = f"UPDATE stock_financial_indicator SET {', '.join(set_parts)} WHERE code = %s AND report_date = %s"
            vals.extend([code, report_date])
            try:
                cur.execute(sql, vals)
                updated += 1
            except Exception as e:
                errors += 1
                if errors <= 5:
                    print(f"  ERR {code} {report_date}: {e}")

    conn.commit()
    conn.close()
    print(f"\n计算完成: 更新 {updated} 条, 错误 {errors} 条")


def compute_ttm_indicators(code_filter=None):
    """
    计算 TTM（滚动12个月）指标并更新 stock_financial_indicator。

    指标:
      roe_ttm          = SUM(近4季单季归母净利润) / 最新季度 parent_equity * 100
      revenue_ttm_yoy  = (SUM(近4季单季营收) / SUM(去年同期4季营收) - 1) * 100
      net_profit_ttm_yoy = (SUM(近4季单季归母净利润) / SUM(去年同期4季归母净利润) - 1) * 100

    数据源:
      stock_income   — 累计值 (report_type: 1=Q1, 2=H1, 3=9M, 4=全年)
      stock_balance  — 时点值 (parent_equity)
    """
    from collections import defaultdict

    conn = get_conn()
    cur = conn.cursor()

    # ── 加载 stock_income (累计值) ──
    print("[TTM] 加载 stock_income...")
    cur.execute("""
        SELECT code, report_date, report_type, revenue, np_parent_company_owners
        FROM stock_income
        ORDER BY code, report_date
    """)
    income_raw = defaultdict(list)
    for code, rd, rt, rev, np_attr in cur.fetchall():
        income_raw[code].append({
            'report_date': rd,
            'report_type': rt,
            'revenue': to_float(rev),
            'np_attr': to_float(np_attr),
        })
    print(f"  共 {sum(len(v) for v in income_raw.values())} 条, {len(income_raw)} 只股票")

    # ── 加载 stock_balance (parent_equity) ──
    print("[TTM] 加载 stock_balance...")
    cur.execute("""
        SELECT code, report_date, parent_equity
        FROM stock_balance
        ORDER BY code, report_date
    """)
    balance_data = defaultdict(dict)
    for code, rd, pe in cur.fetchall():
        balance_data[code][rd] = to_float(pe)

    # ── 去累计 → 单季度 ──
    print("[TTM] 去累计 → 单季度...")
    single_q = defaultdict(list)  # {code: [{report_date, revenue, np_attr}, ...]}
    for code, rows in income_raw.items():
        # Assume sorted by report_date
        prev = None
        for r in rows:
            rt = r['report_type']
            rev = r['revenue'] or 0
            np_val = r['np_attr'] or 0

            if rt == 1:
                # Q1 is already single quarter
                sq_rev = rev
                sq_np = np_val
            else:
                # type=2/3/4: cumulative, subtract previous cumulative
                prev_rev = (prev['revenue'] or 0) if prev else 0
                prev_np = (prev['np_attr'] or 0) if prev else 0
                sq_rev = rev - prev_rev
                sq_np = np_val - prev_np

            single_q[code].append({
                'report_date': r['report_date'],
                'revenue': sq_rev,
                'np_attr': sq_np,
            })
            prev = r

    # ── 计算 TTM ──
    print("[TTM] 计算 TTM 指标...")
    updated = 0
    errors = 0

    # 获取所有需要计算的 (code, report_date)
    if code_filter:
        cur.execute(
            "SELECT code, report_date FROM stock_financial_indicator WHERE code = %s",
            (code_filter,))
    else:
        cur.execute(
            "SELECT code, report_date FROM stock_financial_indicator WHERE LEFT(report_date,4) >= '2010'")
    target_keys = cur.fetchall()

    for code, report_date in target_keys:
        sq_list = single_q.get(code, [])
        if len(sq_list) < 4:
            continue

        # 找到 report_date 在 sq_list 中的位置
        idx = None
        for i, sq in enumerate(sq_list):
            if sq['report_date'] == report_date:
                idx = i
                break
        if idx is None or idx < 3:
            continue

        # 近4个单季度: idx-3, idx-2, idx-1, idx
        q4_rev = sum(sq_list[j]['revenue'] for j in range(idx - 3, idx + 1))
        q4_np = sum(sq_list[j]['np_attr'] for j in range(idx - 3, idx + 1))

        # 去年同期4个单季度: idx-7, idx-6, idx-5, idx-4
        if idx >= 7:
            prev_q4_rev = sum(sq_list[j]['revenue'] for j in range(idx - 7, idx - 3))
            prev_q4_np = sum(sq_list[j]['np_attr'] for j in range(idx - 7, idx - 3))
        else:
            prev_q4_rev = None
            prev_q4_np = None

        # parent_equity at this report_date
        pe = balance_data.get(code, {}).get(report_date)

        # ROE TTM
        roe_ttm = None
        if q4_np and pe and pe != 0:
            roe_ttm = round(q4_np / pe * 100, 4)

        # Revenue TTM YoY
        revenue_ttm_yoy = None
        if q4_rev and prev_q4_rev and prev_q4_rev != 0:
            revenue_ttm_yoy = round((q4_rev / prev_q4_rev - 1) * 100, 4)

        # Net profit TTM YoY
        net_profit_ttm_yoy = None
        if q4_np and prev_q4_np and prev_q4_np != 0:
            net_profit_ttm_yoy = round((q4_np / prev_q4_np - 1) * 100, 4)

        # UPDATE
        set_parts = []
        vals = []
        if roe_ttm is not None:
            set_parts.append("roe_ttm = %s")
            vals.append(roe_ttm)
        if revenue_ttm_yoy is not None:
            set_parts.append("revenue_ttm_yoy = %s")
            vals.append(revenue_ttm_yoy)
        if net_profit_ttm_yoy is not None:
            set_parts.append("net_profit_ttm_yoy = %s")
            vals.append(net_profit_ttm_yoy)

        if set_parts:
            sql = f"UPDATE stock_financial_indicator SET {', '.join(set_parts)} WHERE code = %s AND report_date = %s"
            vals.extend([code, report_date])
            try:
                cur.execute(sql, vals)
                updated += 1
            except Exception as e:
                errors += 1
                if errors <= 5:
                    print(f"  ERR {code} {report_date}: {e}")

    conn.commit()
    conn.close()
    print(f"\n[TTM] 计算完成: 更新 {updated} 条, 错误 {errors} 条")


if __name__ == '__main__':
    main()
