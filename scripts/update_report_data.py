#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_report_data.py
量化报告增强数据采集——补全 WorkflowReport 所需的新数据

新增数据项：
  1. stock_value_em              → PE/PB 当前值 + 历史分位
  2. stock_financial_abstract_ths → ROE / 销售净利率 / 毛利率 / 资产负债率
  3. stock_financial_cash_ths     → FCF 自由现金流（经营现金流净额 - 购建固定资产支付）
  4. stock_hsgt_individual_em     → 北向资金（持股数量/市值/增减持）
  5. stock_jgdy_tj_em + stock_research_report_em → 机构调研 + 研报覆盖
  6. stock_zh_a_gdhs             → 股东人数变化

ADX：TA-Lib 直接在 Java 中调用，不走 Python

用法：
  python update_report_data.py 600519
  python update_report_data.py 600519 --check        # 仅打印，不写库
  python update_report_data.py 600519 --store        # 写入 MySQL 表
  python update_report_data.py --batch                # 全量批量（有财务数据的股票）
"""

import argparse
import json
import re
import sys
import time
import math
import signal
import pymysql
from datetime import datetime, date, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed

import akshare as ak

# ── 数据库 ──────────────────────────────────────────────
from db_config import MYSQL_CONFIG

# ── 全局配置 ──────────────────────────────────────────────
BATCH_SIZE = 20
SLEEP_SEC = 1.5
MAX_WORKERS = 8


# ══════════════════════════════════════════════════════════════════════════════
# 工具函数
# ══════════════════════════════════════════════════════════════════════════════

class TimeoutError(Exception):
    pass


def with_timeout(seconds, func, *args, **kwargs):
    """跨平台超时包装器（使用 threading）"""
    import threading

    result = [None]
    exc = [None]

    def target():
        try:
            result[0] = func(*args, **kwargs)
        except Exception as e:
            exc[0] = e

    t = threading.Thread(target=target)
    t.daemon = True
    t.start()
    t.join(timeout=seconds)
    if t.is_alive():
        raise TimeoutError(f"函数执行超过 {seconds}s")
    if exc[0] is not None:
        raise exc[0]
    return result[0]


def get_conn():
    cfg = MYSQL_CONFIG.copy()
    cfg["charset"] = "utf8mb4"
    cfg["autocommit"] = True
    return pymysql.connect(**cfg)


def parse_number(val):
    """将各种格式的数值字符串转为 float 或 None"""
    if val is None:
        return None
    try:
        if isinstance(val, float) and math.isnan(val):
            return None
    except (TypeError, ValueError):
        pass
    s = str(val).strip()
    if s in ('', 'False', '-', '--', 'nan', 'NaN', 'None'):
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
        return num * 1e8 if m.group(2) == '亿' else num * 1e4
    try:
        return float(s)
    except (ValueError, TypeError):
        return None


def to_yuan(val_str):
    """"2.72亿" → 272000000.0，"3456万" → 34560000.0"""
    if val_str is None:
        return None
    v = parse_number(val_str)
    if v is None:
        return None
    # 如果值 < 10000 且原值含亿/万，说明已经是亿/万单位，转元
    s = str(val_str).strip()
    if '亿' in s and v < 10000:
        return v * 1e8
    if '万' in s and v < 10000:
        return v * 1e4
    return v


def stock_code_fmt(code):
    """统一格式：6位字符串，沪市加 sh，深市加 sz 前缀"""
    c = str(code).strip().zfill(6)
    return c


def em_code(code):
    """东方财富格式：600519.SH / 000001.SZ"""
    c = stock_code_fmt(code)
    return f"{c}.SH" if c.startswith(('6', '9')) else f"{c}.SZ"


# ══════════════════════════════════════════════════════════════════════════════
# 1. PE/PB 当前值 + 历史分位
#   - 历史分位：stock_value_em(symbol=code) 历史序列（2018至今，部分股票有缺失）
#   - 当前值：近3年年报EPS × 最新收盘价，或直接用历史序列末值
# ══════════════════════════════════════════════════════════════════════════════

def fetch_pe_pb_percentile(code, years=3):
    """
    stock_value_em(symbol=code) 返回单只股票历史 PE/PB 时间序列（约2018至今）。
    数据格式：['数据日期','当日收盘价','PE(TTM)','市净率'...]
    部分股票数据截至2018年（akshare数据滞后）。

    返回:
      pe_percentile: float (0~100)，PE(TTM) 历史分位
      pb_percentile: float (0~100)，市净率历史分位
      pe_current:    float，当前 PE(TTM)
      pb_current:    float，当前 PB
      pe_hist_count: int，PE 历史数据条数
      pb_hist_count: int，PB 历史数据条数
      data_fresh:    bool，末条数据是否在近30天内（判断数据新鲜度）
    """
    result = {
        'pe_percentile': None, 'pb_percentile': None,
        'pe_current': None, 'pb_current': None,
        'pe_hist_count': 0, 'pb_hist_count': 0,
        'pe_fetched': False, 'pb_fetched': False,
        'data_fresh': False,
    }

    date_col = '数据日期'
    pe_col = 'PE(TTM)'
    pb_col = '市净率'
    price_col = '当日收盘价'

    try:
        c = stock_code_fmt(code)
        df = ak.stock_value_em(symbol=c)   # symbol 接受6位代码
    except Exception as e:
        print(f"  [WARN] stock_value_em 失败: {e}")
        return result

    if df is None or df.empty:
        print(f"  [WARN] stock_value_em 返回空")
        return result

    if date_col not in df.columns or pe_col not in df.columns:
        print(f"  [WARN] stock_value_em 列名不符: {list(df.columns)}")
        return result

    # 转换日期
    import pandas as pd
    df[date_col] = pd.to_datetime(df[date_col], errors='coerce').dt.date

    # 排序：升序（最旧在前）
    df = df.sort_values(date_col)

    # 判断数据新鲜度（末条是否在近30天）
    latest_date = df[date_col].max()
    if latest_date:
        from datetime import date as date_cls
        result['data_fresh'] = (date.today() - latest_date).days <= 30

    # 近 N 年过滤
    cutoff = date.today() - timedelta(days=years * 365)
    hist_df = df[df[date_col] >= cutoff].copy()

    if hist_df.empty:
        print(f"  [INFO] 近 {years} 年无数据（数据最旧: {latest_date}），使用全部历史")
        hist_df = df

    if hist_df.empty:
        return result

    # 当前值：优先用末行（最接近当前的记录）
    last = hist_df.iloc[-1]
    result['pe_current'] = parse_number(last.get(pe_col))
    result['pb_current'] = parse_number(last.get(pb_col))

    # 历史分位
    if result['pe_current'] is not None and result['pe_current'] > 0:
        pe_series = hist_df[pe_col].dropna().apply(parse_number).dropna()
        if len(pe_series) > 10:
            pct = (pe_series <= result['pe_current']).sum() / len(pe_series) * 100
            result['pe_percentile'] = round(pct, 2)
            result['pe_hist_count'] = int(len(pe_series))

    if result['pb_current'] is not None and result['pb_current'] > 0:
        pb_series = hist_df[pb_col].dropna().apply(parse_number).dropna()
        if len(pb_series) > 10:
            pct = (pb_series <= result['pb_current']).sum() / len(pb_series) * 100
            result['pb_percentile'] = round(pct, 2)
            result['pb_hist_count'] = int(len(pb_series))

    result['pe_fetched'] = result['pe_hist_count'] > 0
    result['pb_fetched'] = result['pb_hist_count'] > 0

    # 打印信息
    fresh_note = '（数据可能偏旧）' if not result['data_fresh'] else ''
    if result['pe_fetched']:
        print(f"       PE={result['pe_current']:.2f} → 分位 {result['pe_percentile']}% "
              f"({result['pe_hist_count']}个数据点) {fresh_note}")
    if result['pb_fetched']:
        print(f"       PB={result['pb_current']:.2f} → 分位 {result['pb_percentile']}% "
              f"({result['pb_hist_count']}个数据点) {fresh_note}")
    if not result['pe_fetched'] and not result['pb_fetched']:
        print(f"  [INFO] 无有效 PE/PB 历史数据（数据截至 {latest_date}）")

    return result


# ══════════════════════════════════════════════════════════════════════════════
# 2. 财务摘要（ROE / 净利率 / 毛利率 / 资产负债率）
# ══════════════════════════════════════════════════════════════════════════════

def fetch_financial_abstract(code):
    """
    stock_financial_abstract_ths(symbol=code)
    返回列名：销售净利率 / 销售毛利率 / 净资产收益率(ROE) / 资产负债率
    格式："22.34%" → 22.34

    取最新一期数据。
    """
    result = {
        'roe': None,
        'net_margin': None,
        'gross_margin': None,
        'debt_ratio': None,
        'report_date': None,
        'fetched': False,
    }

    try:
        df = ak.stock_financial_abstract_ths(symbol=code)
    except Exception as e:
        print(f"  [WARN] stock_financial_abstract_ths 失败: {e}")
        return result

    if df is None or df.empty:
        return result

    # 取最近一期年报（报告期以 12-31 结尾），若无则降级取最新一期
    annual_df = df[df.iloc[:, 0].astype(str).str.endswith('12-31')]
    if not annual_df.empty:
        row = annual_df.iloc[-1]   # 年报升序，取最新年报
    else:
        row = df.iloc[-1]          # 降级：取最新一期

    col_map = [
        # 优先取年报（12-31）的加权ROE
        ('净资产收益率', 'roe'),
        ('净资产收益率-摊薄', 'roe'),
        ('净资产收益率(ROE)', 'roe'),
        ('销售净利率', 'net_margin'),
        ('销售毛利率', 'gross_margin'),
        ('资产负债率', 'debt_ratio'),
    ]

    for col_name, field in col_map:
        if col_name in df.columns and result[field] is None:
            v = parse_number(row.get(col_name))
            if v is not None:
                result[field] = v

    # 报告期
    for col in df.columns:
        if '报告期' in str(col):
            rd = row.get(col)
            if rd:
                result['report_date'] = str(rd)[:10]
            break

    result['fetched'] = any(v is not None for k, v in result.items()
                            if k != 'fetched' and k != 'report_date')
    return result


# ══════════════════════════════════════════════════════════════════════════════
# 3. 现金流量表 → FCF 自由现金流
# ══════════════════════════════════════════════════════════════════════════════

def fetch_free_cash_flow(code):
    """
    stock_financial_cash_ths(symbol=code)
    FCF = 经营活动产生的现金流量净额 - 购建固定资产...支付的现金

    取最新一期。
    """
    result = {
        'fcf': None,           # 元
        'net_operate_cf': None,
        'cash_paid_acquisition': None,
        'report_date': None,
        'fetched': False,
    }

    try:
        df = ak.stock_financial_cash_ths(symbol=code)
    except Exception as e:
        print(f"  [WARN] stock_financial_cash_ths 失败: {e}")
        return result

    if df is None or df.empty:
        return result

    # 数据降序排列（最新季度在前），取首行
    row = df.iloc[0]

    net_cf_key = None
    capex_key = None
    for col in df.columns:
        c = str(col).strip()
        if '经营活动产生的现金流量净额' in c and net_cf_key is None:
            net_cf_key = col
        elif '购建固定资产' in c and capex_key is None:
            capex_key = col

    if net_cf_key is not None:
        result['net_operate_cf'] = to_yuan(row.get(net_cf_key))
    if capex_key is not None:
        result['cash_paid_acquisition'] = to_yuan(row.get(capex_key))

    if result['net_operate_cf'] is not None:
        capex = result['cash_paid_acquisition'] or 0
        result['fcf'] = result['net_operate_cf'] - capex

    for col in df.columns:
        if '报告期' in str(col):
            rd = row.get(col)
            if rd:
                result['report_date'] = str(rd)[:10]
            break

    result['fetched'] = result['fcf'] is not None
    return result


# ══════════════════════════════════════════════════════════════════════════════
# 4. 北向资金（持股/增减持历史）
# ══════════════════════════════════════════════════════════════════════════════

def fetch_hsgt_funds(code):
    """
    stock_hsgt_individual_em(symbol=code)
    返回：持股日期 / 持股数量(万股) / 持股市值(万元) / 增持股数(万股) / 占总股本比例(%)
    取最近一条。
    """
    result = {
        'hold_date': None,
        'hold_shares': None,       # 万股
        'hold_value': None,        # 万元
        'change_shares': None,     # 万股，增持为正
        'hold_ratio': None,        # %
        'hold_value_yuan': None,   # 元（换算后）
        'fetched': False,
    }

    try:
        df = ak.stock_hsgt_individual_em(symbol=code)
    except Exception as e:
        # 北向资金仅沪股通+深股通标的，非标的股票会抛异常
        result['error'] = str(e)[:80]
        return result

    if df is None or df.empty:
        return result

    # 数据升序排列，取末行（最新一期）
    row = df.iloc[-1]

    for col in df.columns:
        c = str(col).strip()
        if '持股日期' in c:
            rd = row.get(col)
            if rd:
                result['hold_date'] = str(rd)[:10]
        elif '持股数量' in c:
            result['hold_shares'] = parse_number(row.get(col))
        elif '市值' in c or '持股市值' in c:
            v = parse_number(row.get(col))
            if v is not None:
                result['hold_value'] = v
                result['hold_value_yuan'] = v * 10000
        elif '增减持' in c or '增减' in c:
            result['change_shares'] = parse_number(row.get(col))
        elif '占总股本' in c or '股本比例' in c:
            result['hold_ratio'] = parse_number(row.get(col))

    result['fetched'] = result['hold_shares'] is not None or result['hold_value'] is not None
    return result


# ══════════════════════════════════════════════════════════════════════════════
# 5. 机构调研 + 研报覆盖
#    - stock_research_report_em(symbol=code) → 个股研报覆盖（评级/机构/EPS预测）
#    - stock_jgdy_tj_em(date='YYYYMMDD') → 全市场机构调研（需缓存后过滤）
# ══════════════════════════════════════════════════════════════════════════════

# 全局缓存：机构调研全量数据（每进程只拉一次）
_jgdy_cache = {'date': None, 'df': None}


def _ensure_jgdy_cache(days=365):
    """确保机构调研全量数据已缓存（按需拉取，按股票代码过滤）"""
    global _jgdy_cache
    today_str = date.today().strftime('%Y%m%d')
    cutoff_str = (date.today() - timedelta(days=days)).strftime('%Y%m%d')

    if _jgdy_cache['df'] is not None and _jgdy_cache['date'] == today_str:
        return _jgdy_cache['df']

    print(f"    [缓存] 拉取近 {days} 天机构调研全量数据（耗时约 10~15s）...")
    try:
        df = ak.stock_jgdy_tj_em(date=cutoff_str)
        # 预过滤：只保留近2年的，减少内存占用
        import pandas as pd
        cutoff_2y = (date.today() - timedelta(days=730))
        if '接待日期' in df.columns:
            df['_dt'] = pd.to_datetime(df['接待日期'], errors='coerce')
            df = df[df['_dt'] >= pd.Timestamp(cutoff_2y)].drop(columns=['_dt'])
        _jgdy_cache['df'] = df
        _jgdy_cache['date'] = today_str
        print(f"    [缓存] 机构调研共 {len(df)} 条记录")
        return df
    except Exception as e:
        print(f"    [WARN] stock_jgdy_tj_em 失败: {e}")
        return None


def fetch_institution_research(code):
    """
    两条数据源：
    1. stock_research_report_em → 研报覆盖（评级/机构/EPS预测）
    2. stock_jgdy_tj_em 缓存过滤 → 机构调研（接待日期/机构数量/方式）

    返回合并结果。
    """
    result = {
        'research_count': 0,    # 研报覆盖数量
        'research_reports': [], # 研报列表（机构+评级）
        'jgdy_count': 0,        # 机构调研次数
        'jgdy_last_date': None,
        'jgdy_last_orgs': None,
        'fetched': False,
    }

    c = stock_code_fmt(code)

    # ── 5a. 研报覆盖 ──────────────────────────────────────────────
    try:
        df_rep = ak.stock_research_report_em(symbol=c)
        if df_rep is not None and not df_rep.empty:
            result['research_count'] = int(len(df_rep))
            # 按日期降序取前5条（最新研报）
            date_col_rep = None
            for col in df_rep.columns:
                if '日期' in str(col):
                    date_col_rep = col
                    break
            if date_col_rep:
                import pandas as pd
                df_rep['_dt'] = pd.to_datetime(df_rep[date_col_rep], errors='coerce')
                df_rep = df_rep.dropna(subset=['_dt']).sort_values('_dt', ascending=False)
                df_rep = df_rep.drop(columns=['_dt'])
            for _, row in df_rep.head(5).iterrows():
                item = {}
                for col in df_rep.columns:
                    cn = str(col).strip()
                    if '日期' in cn and 'date' not in item:
                        rd = row.get(col)
                        if rd:
                            item['date'] = str(rd)[:10]
                    elif '报告名称' in cn:
                        item['report_name'] = str(row.get(col) or '')[:80]
                    elif '东财评级' in cn:
                        item['rating'] = str(row.get(col) or '')[:20]
                    elif '机构' in cn and '评级' not in cn:
                        item['org'] = str(row.get(col) or '')[:50]
                    elif '近一月' in cn:
                        item['recent_count'] = row.get(col)
                if item:
                    result['research_reports'].append(item)
    except Exception as e:
        result['error_rep'] = str(e)[:60]

    # ── 5b. 机构调研（从缓存过滤）────────────────────────────────
    df_jgdy = _ensure_jgdy_cache(days=365)
    if df_jgdy is not None and not df_jgdy.empty:
        try:
            mask = df_jgdy['代码'].astype(str).str.zfill(6) == c
            stock_jgdy = df_jgdy[mask]
            result['jgdy_count'] = int(len(stock_jgdy))
            if result['jgdy_count'] > 0:
                last = stock_jgdy.iloc[0]
                result['jgdy_last_date'] = str(last.get('接待日期', ''))[:10]
                orgs = last.get('接待机构数量', '')
                result['jgdy_last_orgs'] = str(orgs) if orgs else None
        except Exception as e:
            result['error_jgdy'] = str(e)[:60]

    result['fetched'] = result['research_count'] > 0 or result['jgdy_count'] > 0
    return result


# ══════════════════════════════════════════════════════════════════════════════
# 6. 股东人数（超时保护）
# ══════════════════════════════════════════════════════════════════════════════

SHAREHOLDER_TIMEOUT = 15  # 秒，接口较慢

def fetch_shareholder_count(code):
    """
    stock_zh_a_gdhs_detail_em(symbol=code) — 股东人数详情（约1秒返回）。
    返回：股东户数 / 较上期变化 / 户均持股市值。
    取最新一期。
    """
    result = {
        'report_date': None,
        'holder_count': None,       # 户
        'avg_shares': None,         # 股/户
        'change_pct': None,         # %
        'change_count': None,       # 人数变化
        'fetched': False,
    }

    try:
        df = ak.stock_zh_a_gdhs_detail_em(symbol=code)
    except Exception as e:
        result['error'] = str(e)[:80]
        return result

    if df is None or df.empty:
        return result

    # 数据升序排列，取末行（最新一期）
    row = df.iloc[-1]

    for col in df.columns:
        c = str(col).strip()
        if '统计截止日' in c:
            rd = row.get(col)
            if rd:
                result['report_date'] = str(rd)[:10]
        elif '股东户数-本次' in c:
            result['holder_count'] = parse_number(row.get(col))
        elif '户均持股数量' in c:
            result['avg_shares'] = parse_number(row.get(col))
        elif '增减比例' in c:
            result['change_pct'] = parse_number(row.get(col))
        elif '股东户数-增减' in c and '比例' not in c:
            result['change_count'] = parse_number(row.get(col))

    result['fetched'] = result['holder_count'] is not None
    return result


# ══════════════════════════════════════════════════════════════════════════════
# 聚合函数：单只股票全部数据
# ══════════════════════════════════════════════════════════════════════════════

def fetch_all_report_data(code, check_only=False):
    """
    拉取单只股票的全部报告增强数据。
    返回 dict，所有字段平铺。
    """
    c = stock_code_fmt(code)
    print(f"\n{'='*50}")
    print(f"  {c} 报告增强数据")
    print(f"{'='*50}")

    data = {'code': c, 'fetch_time': datetime.now().isoformat()}

    # 1. PE/PB 分位
    print("  [1/6] PE/PB 历史分位...")
    pe_pb = fetch_pe_pb_percentile(c, years=3)
    data.update({f'pe_{k}': v for k, v in pe_pb.items()})

    # 2. 财务摘要
    print("  [2/6] 财务摘要（ROE/净利率/毛利率/负债率）...")
    fin = fetch_financial_abstract(c)
    data.update({f'fin_{k}': v for k, v in fin.items()})
    if fin.get('fetched'):
        print(f"       ROE={fin['roe']}% 净利率={fin['net_margin']}%  "
              f"毛利率={fin['gross_margin']}% 负债率={fin['debt_ratio']}%")
    else:
        print("       无数据")

    # 3. FCF
    print("  [3/6] 自由现金流...")
    fcf = fetch_free_cash_flow(c)
    data.update({f'fcf_{k}': v for k, v in fcf.items()})
    if fcf.get('fetched'):
        print(f"       FCF={fcf['fcf']:,.0f}元 "
              f"(经营现金流={fcf['net_operate_cf']:,.0f} 资本支出={fcf['cash_paid_acquisition']:,.0f})")
    else:
        print("       无数据")

    # 4. 北向资金
    print("  [4/6] 北向资金...")
    hsgt = fetch_hsgt_funds(c)
    data.update({f'hsgt_{k}': v for k, v in hsgt.items()})
    if hsgt.get('fetched'):
        print(f"       持股={hsgt['hold_shares']}万股  "
              f"市值={hsgt['hold_value']}万元  增持={hsgt['change_shares']}万股")
    else:
        err = hsgt.get('error', '无数据')
        print(f"       {err}（非沪/深股通标的或暂无数据）")

    # 5. 机构调研
    print("  [5/6] 机构调研...")
    research = fetch_institution_research(c)
    data.update({f'research_{k}': v for k, v in research.items()})
    if research.get('fetched'):
        print(f"       研报覆盖: {research['research_count']} 条  "
              f"机构调研: {research['jgdy_count']} 次")
    else:
        err = research.get('error_rep') or research.get('error_jgdy') or '无数据'
        print(f"       {err}")

    # 6. 股东人数
    print("  [6/6] 股东人数...")
    holder = fetch_shareholder_count(c)
    data.update({f'holder_{k}': v for k, v in holder.items()})
    if holder.get('fetched'):
        print(f"       股东户数={holder['holder_count']:,.0f}  "
              f"变化={holder['change_pct']}%")
    else:
        err = holder.get('error', '无数据')
        print(f"       {err}")

    print(f"{'='*50}")
    return data


# ══════════════════════════════════════════════════════════════════════════════
# 写入 MySQL
# ══════════════════════════════════════════════════════════════════════════════

def ensure_tables(conn):
    """建表（如果不存在）"""
    cursor = conn.cursor()

    # 北向资金
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS stock_hsgt_snapshot (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        code VARCHAR(6) NOT NULL,
        hold_date DATE,
        hold_shares DECIMAL(20,4),
        hold_value DECIMAL(20,4),
        hold_value_yuan DOUBLE,
        change_shares DECIMAL(20,4),
        hold_ratio DECIMAL(10,4),
        fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uk_code_date (code, hold_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)

    # 机构调研
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS stock_institution_research (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        code VARCHAR(6) NOT NULL,
        report_date DATE,
        org_name VARCHAR(200),
        content_summary VARCHAR(500),
        fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_code (code),
        INDEX idx_report_date (report_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)

    # 股东人数
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS stock_shareholder (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        code VARCHAR(6) NOT NULL,
        report_date DATE,
        holder_count BIGINT,
        avg_shares DECIMAL(20,4),
        change_pct DECIMAL(10,4),
        change_count BIGINT,
        fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uk_code_date (code, report_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)

    # 估值快照
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS stock_valuation_snapshot (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        code VARCHAR(6) NOT NULL,
        snapshot_date DATE,
        pe_current DECIMAL(20,4),
        pb_current DECIMAL(20,4),
        pe_percentile_3y DECIMAL(10,4),
        pb_percentile_3y DECIMAL(10,4),
        pe_hist_count INT,
        pb_hist_count INT,
        fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uk_code_date (code, snapshot_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)

    conn.commit()
    cursor.close()


def store_all_data(conn, data):
    """将采集数据写入各表"""
    code = data['code']
    today = date.today()

    cursor = conn.cursor()

    # 1. 估值快照
    if data.get('pe_pe_fetched') or data.get('pe_pb_fetched'):
        cursor.execute("""
            INSERT INTO stock_valuation_snapshot
                (code, snapshot_date, pe_current, pb_current,
                 pe_percentile_3y, pb_percentile_3y,
                 pe_hist_count, pb_hist_count)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE
                pe_current = VALUES(pe_current),
                pb_current = VALUES(pb_current),
                pe_percentile_3y = VALUES(pe_percentile_3y),
                pb_percentile_3y = VALUES(pb_percentile_3y)
        """, (
            code, today,
            data.get('pe_pe_current'), data.get('pe_pb_current'),
            data.get('pe_pe_percentile'), data.get('pe_pb_percentile'),
            data.get('pe_pe_hist_count', 0), data.get('pe_pb_hist_count', 0),
        ))

    # 2. 北向资金
    if data.get('hsgt_fetched') and data.get('hsgt_hold_date'):
        cursor.execute("""
            INSERT INTO stock_hsgt_snapshot
                (code, hold_date, hold_shares, hold_value, hold_value_yuan,
                 change_shares, hold_ratio)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE
                hold_shares = VALUES(hold_shares),
                hold_value = VALUES(hold_value),
                hold_value_yuan = VALUES(hold_value_yuan),
                change_shares = VALUES(change_shares),
                hold_ratio = VALUES(hold_ratio)
        """, (
            code, data['hsgt_hold_date'],
            data.get('hsgt_hold_shares'), data.get('hsgt_hold_value'),
            data.get('hsgt_hold_value_yuan'), data.get('hsgt_change_shares'),
            data.get('hsgt_hold_ratio'),
        ))

    # 3. 机构调研（研报覆盖 + 机构调研）
    # 研报覆盖或机构调研任一有数据就写入
    if data.get('research_research_count', 0) > 0 or data.get('research_jgdy_count', 0) > 0:
        # 先删旧数据再插入新数据
        cursor.execute("DELETE FROM stock_institution_research WHERE code=%s", (code,))
        for rep in data.get('research_research_reports', []):
            if rep.get('report_name'):
                cursor.execute("""
                    INSERT INTO stock_institution_research
                        (code, report_date, org_name, content_summary)
                    VALUES (%s, %s, %s, %s)
                """, (code, rep.get('date') or None,
                      rep.get('org', ''), f"评级:{rep.get('rating','')} {rep.get('report_name','')}"))

    # 4. 股东人数
    if data.get('holder_holder_fetched') and data.get('holder_report_date'):
        cursor.execute("""
            INSERT INTO stock_shareholder
                (code, report_date, holder_count, avg_shares, change_pct, change_count)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE
                holder_count = VALUES(holder_count),
                avg_shares = VALUES(avg_shares),
                change_pct = VALUES(change_pct),
                change_count = VALUES(change_count)
        """, (
            code, data['holder_report_date'],
            data.get('holder_holder_count'), data.get('holder_avg_shares'),
            data.get('holder_change_pct'), data.get('holder_change_count'),
        ))

    conn.commit()
    cursor.close()


# ══════════════════════════════════════════════════════════════════════════════
# 批量入口
# ══════════════════════════════════════════════════════════════════════════════

def get_batch_stocks(conn):
    """返回有研报覆盖的股票列表（优先采集目标，兼顾数据完整性）"""
    cursor = conn.cursor()
    # 有研报 → 有分析师覆盖 → 数据价值高
    cursor.execute("""
        SELECT DISTINCT code
        FROM stock_research_report
        ORDER BY code
    """)
    rows = cursor.fetchall()
    cursor.close()
    codes = [r[0] for r in rows]
    print(f"  [范围] 有研报覆盖股票: {len(codes)} 只")
    return codes


def run_batch(conn, stocks, check_only=False):
    """多线程批量采集"""
    ensure_tables(conn)

    total = len(stocks)
    done = [0]
    stored = [0]
    errors = [0]

    def worker(code):
        try:
            data = fetch_all_report_data(code, check_only=check_only)
            if not check_only:
                store_all_data(conn, data)
            done[0] += 1
            if data.get('pe_fetched') or data.get('hsgt_fetched'):
                stored[0] += 1
            return code, None
        except Exception as e:
            done[0] += 1
            errors[0] += 1
            return code, str(e)

    print(f"\n开始批量采集，共 {total} 只股票，{MAX_WORKERS} 线程")
    print(f"{'='*60}")

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(worker, c): c for c in stocks}
        for future in as_completed(futures):
            code, err = future.result()
            if err:
                print(f"  [{done[0]}/{total}] {code}  ✗  {err}")
            else:
                if done[0] % 50 == 0 or done[0] == total:
                    print(f"  [{done[0]}/{total}] {code}  ✓  "
                          f"(累计有效 {stored[0]}, 错误 {errors[0]})")
            if done[0] < total:
                time.sleep(0.1)

    print(f"\n{'='*60}")
    print(f"  完成: {done[0]} 只  有效数据: {stored[0]}  错误: {errors[0]}")
    print(f"{'='*60}\n")


# ══════════════════════════════════════════════════════════════════════════════
# 主入口
# ══════════════════════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(description="量化报告增强数据采集")
    parser.add_argument("code", nargs="?", default=None, help="股票代码（如 600519）")
    parser.add_argument("--check", action="store_true",
                        help="仅打印数据，不写库（用于验证接口）")
    parser.add_argument("--batch", action="store_true",
                        help="批量模式：采集全量有财务数据的股票")
    parser.add_argument("--max-workers", type=int, default=8,
                        help="并发线程数（默认 8）")
    parser.add_argument("--years", type=int, default=3,
                        help="PE/PB 分位历史年数（默认 3 年）")
    args = parser.parse_args()

    if args.code:
        data = fetch_all_report_data(args.code, check_only=args.check)
        if not args.check:
            conn = get_conn()
            ensure_tables(conn)
            store_all_data(conn, data)
            conn.close()
            print(f"  ✓ 数据已写入 MySQL")
        # 打印 JSON 汇总
        print("\n【JSON 输出】")
        print(json.dumps(data, ensure_ascii=False, indent=2,
                         default=lambda x: None if x is None else x))
        return

    if args.batch:
        conn = get_conn()
        stocks = get_batch_stocks(conn)
        conn.close()
        print(f"待采集股票: {len(stocks)} 只")
        conn = get_conn()
        run_batch(conn, stocks, check_only=args.check)
        conn.close()
        return

    # 无参数：单只交互
    code = input("请输入股票代码（如 600519，按回车确认）: ").strip()
    if code:
        data = fetch_all_report_data(code, check_only=args.check)
        if not args.check:
            conn = get_conn()
            ensure_tables(conn)
            store_all_data(conn, data)
            conn.close()
        print("\n【JSON 输出】")
        print(json.dumps(data, ensure_ascii=False, indent=2,
                         default=lambda x: None if x is None else x))


if __name__ == "__main__":
    main()
