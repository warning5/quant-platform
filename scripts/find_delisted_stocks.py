#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
find_delisted_stocks.py
=======================
检测系统中已退市股票，并自动将退市日期写入 stock_info.delist_date 字段。

检测来源（多源互补）：
1. akshare 官方退市数据（沪市+深市历史退市股）
2. ClickHouse stock_daily 数据缺失检测（最近N天无交易数据）
3. stock_info 中名称包含"退"的标记股

用法:
  python find_delisted_stocks.py [inactive_days] [--mark-only]

参数:
  inactive_days: 停牌天数阈值（默认60）
  --mark-only: 仅执行标记退市，不输出JSON

输出格式（JSON 数组）:
[
  {
    "code": "600001",
    "name": "邯郸钢铁",
    "market": "SH",
    "out_date": "2009-12-29",
    "max_date": "2009-12-25",
    "days_inactive": 6000,
    "daily_rows": 2500,
    "factor_rows": 0,
    "moneyflow_rows": 0,
    "source": "akshare"
  },
  ...
]
"""
import warnings
import json
import sys
import os
import pymysql
from datetime import datetime, timedelta

warnings.filterwarnings("ignore")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

from db_config import MYSQL_CONFIG, CLICKHOUSE_CONFIG


def get_stock_info():
    """从 MySQL stock_info 获取所有股票（含已有的退市日期）"""
    stocks = {}
    try:
        conn = pymysql.connect(**MYSQL_CONFIG)
        with conn.cursor() as cur:
            cur.execute("SELECT code, name, market, delist_date FROM stock_info")
            for row in cur.fetchall():
                code, name, market, delist_date = row
                stocks[str(code).strip()] = {
                    "name": str(name).strip() if name else "",
                    "market": str(market).strip().upper() if market else "",
                    "delist_date": str(delist_date) if delist_date else None
                }
        conn.close()
    except Exception as e:
        print(f"查询 stock_info 失败: {e}", file=sys.stderr)
        sys.exit(1)
    return stocks


def get_delisted_from_akshare():
    """从 akshare 获取历史退市股票数据（沪市+深市）"""
    import akshare as ak
    delisted = {}

    # 沪市退市
    try:
        sh_df = ak.stock_info_sh_delist()
        for _, row in sh_df.iterrows():
            code = str(row.get("公司代码", "")).strip().zfill(6)
            name = str(row.get("公司简称", "")).strip()
            # 沪市列名为"暂停上市日期"
            out_date = str(row.get("暂停上市日期", "")).strip()
            if code and len(code) == 6 and code.isdigit():
                delisted[code] = {
                    "name": name,
                    "market": "SH",
                    "out_date": out_date if out_date and out_date != "nan" else "",
                    "source": "akshare"
                }
    except Exception as e:
        print(f"akshare 沪市退市数据获取失败: {e}", file=sys.stderr)

    # 深市退市
    try:
        sz_df = ak.stock_info_sz_delist()
        for _, row in sz_df.iterrows():
            code = str(row.get("证券代码", "")).strip().zfill(6)
            name = str(row.get("证券简称", "")).strip()
            # 深市列名为"终止上市日期"
            out_date = str(row.get("终止上市日期", "")).strip()
            if code and len(code) == 6 and code.isdigit():
                delisted[code] = {
                    "name": name,
                    "market": "SZ",
                    "out_date": out_date if out_date and out_date != "nan" else "",
                    "source": "akshare"
                }
    except Exception as e:
        print(f"akshare 深市退市数据获取失败: {e}", file=sys.stderr)

    print(f"akshare 退市数据: 沪市+深市共 {len(delisted)} 只", file=sys.stderr)
    return delisted


def get_inactive_from_clickhouse(threshold_days=60):
    """从 ClickHouse 查询最近 threshold_days 天无交易数据的股票"""
    import clickhouse_connect

    client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)

    sql = f"""
    SELECT
        code,
        count() as daily_rows,
        max(trade_date) as max_date
    FROM stock_daily
    GROUP BY code
    HAVING max(trade_date) < today() - {threshold_days}
    ORDER BY max_date ASC
    """

    result = client.query(sql)
    inactive = {}
    for row in result.result_rows:
        code, daily_rows, max_date = row
        inactive[str(code).strip()] = {
            "daily_rows": int(daily_rows),
            "max_date": str(max_date) if max_date else None
        }

    # 因子数据量
    if inactive:
        codes = "','".join(inactive.keys())
        sql2 = f"""
        SELECT symbol, count() as factor_rows
        FROM factor_value
        WHERE symbol IN ('{codes}')
        GROUP BY symbol
        """
        result2 = client.query(sql2)
        for row in result2.result_rows:
            symbol, factor_rows = row
            code = str(symbol).strip()
            if code in inactive:
                inactive[code]["factor_rows"] = int(factor_rows)

        # 资金流数据量
        sql3 = f"""
        SELECT code, count() as moneyflow_rows
        FROM stock_sentiment_moneyflow
        WHERE code IN ('{codes}')
        GROUP BY code
        """
        result3 = client.query(sql3)
        for row in result3.result_rows:
            code, moneyflow_rows = row
            code = str(code).strip()
            if code in inactive:
                inactive[code]["moneyflow_rows"] = int(moneyflow_rows)

    client.close()
    print(f"ClickHouse 数据缺失(>{threshold_days}天): {len(inactive)} 只", file=sys.stderr)
    return inactive


def merge_delisted_candidates(stock_info, akshare_delisted, ch_inactive, threshold_days=60):
    """合并多来源退市候选股（含已标记的）"""
    today = datetime.now().date()
    candidates = {}
    # 已标记退市的股票单独收集，最后追加到结果中（不重复）
    already_marked = {}

    # 来源1: akshare 官方退市数据
    for code, info in akshare_delisted.items():
        if code not in stock_info:
            continue
        si = stock_info[code]
        if si.get("delist_date"):
            # 已标记的也纳入结果，标为已处理
            out_date = si["delist_date"]
            days_inactive = 0
            if out_date:
                try:
                    d = datetime.strptime(out_date, "%Y-%m-%d").date()
                    days_inactive = (today - d).days
                except:
                    days_inactive = 0
            already_marked[code] = {
                "code": code,
                "name": info.get("name") or si["name"],
                "market": info.get("market") or si["market"],
                "out_date": out_date,
                "max_date": "",
                "days_inactive": days_inactive,
                "daily_rows": 0,
                "factor_rows": 0,
                "moneyflow_rows": 0,
                "source": "akshare",
                "marked": True
            }
            continue

        # 根据退市日期计算停牌天数
        out_date = info.get("out_date", "")
        days_inactive = 0
        if out_date:
            try:
                d = datetime.strptime(out_date, "%Y-%m-%d").date()
                days_inactive = (today - d).days
            except:
                days_inactive = 0

        candidates[code] = {
            "code": code,
            "name": info.get("name") or si["name"],
            "market": info.get("market") or si["market"],
            "out_date": out_date,
            "max_date": "",
            "days_inactive": days_inactive,
            "daily_rows": 0,
            "factor_rows": 0,
            "moneyflow_rows": 0,
            "source": "akshare",
            "marked": False
        }

    # 来源2: ClickHouse 数据缺失
    for code, info in ch_inactive.items():
        if code not in stock_info:
            continue
        si = stock_info[code]
        if code in already_marked or code in candidates:
            continue

        max_date = info.get("max_date")
        days_inactive = 0
        if max_date:
            try:
                d = datetime.strptime(str(max_date), "%Y-%m-%d").date()
                days_inactive = (today - d).days
            except:
                pass

        candidates[code] = {
            "code": code,
            "name": si["name"],
            "market": si["market"],
            "out_date": "",
            "max_date": max_date or "",
            "days_inactive": days_inactive,
            "daily_rows": info.get("daily_rows", 0),
            "factor_rows": info.get("factor_rows", 0),
            "moneyflow_rows": info.get("moneyflow_rows", 0),
            "source": "clickhouse",
            "marked": False
        }

    # 来源3: stock_info 名称含"退"但未标记 delist_date 的
    for code, si in stock_info.items():
        name = si.get("name", "")
        if "退" in name and code not in candidates and code not in already_marked:
            # 尝试从 ClickHouse 缺失数据中获取最后交易日
            max_date = ""
            days_inactive = 0
            if code in ch_inactive:
                max_date = ch_inactive[code].get("max_date", "")
                if max_date:
                    try:
                        d = datetime.strptime(str(max_date), "%Y-%m-%d").date()
                        days_inactive = (today - d).days
                    except:
                        pass

            candidates[code] = {
                "code": code,
                "name": name,
                "market": si["market"],
                "out_date": "",
                "max_date": max_date or "",
                "days_inactive": days_inactive,
                "daily_rows": ch_inactive.get(code, {}).get("daily_rows", 0),
                "factor_rows": ch_inactive.get(code, {}).get("factor_rows", 0),
                "moneyflow_rows": ch_inactive.get(code, {}).get("moneyflow_rows", 0),
                "source": "name_pattern",
                "marked": False
            }

    return candidates, already_marked


def update_delist_dates(candidates):
    """将退市日期写入 stock_info.delist_date 字段"""
    if not candidates:
        print("没有需要更新的退市记录", file=sys.stderr)
        return 0

    updated = 0
    try:
        conn = pymysql.connect(**MYSQL_CONFIG)
        with conn.cursor() as cur:
            for code, info in candidates.items():
                # 优先使用 out_date，其次 max_date+1天
                delist_date = info.get("out_date")
                if not delist_date and info.get("max_date"):
                    try:
                        d = datetime.strptime(info["max_date"], "%Y-%m-%d").date()
                        delist_date = (d + timedelta(days=1)).strftime("%Y-%m-%d")
                    except:
                        delist_date = None

                # 如果仍无日期，用今天作为默认退市日期（用户手动触发的标记）
                if not delist_date or delist_date.strip() == '':
                    delist_date = datetime.now().strftime("%Y-%m-%d")
                    print(f"  {code} 无退市日期,使用默认 {delist_date}", file=sys.stderr)

                cur.execute(
                    "UPDATE stock_info SET delist_date = %s WHERE code = %s AND delist_date IS NULL",
                    (delist_date, code)
                )
                updated += cur.rowcount
        conn.commit()
        conn.close()
        print(f"已更新 {updated} 条退市日期到 stock_info", file=sys.stderr)
    except Exception as e:
        print(f"更新退市日期失败: {e}", file=sys.stderr)
    return updated


def main():
    threshold_days = 60
    mark_only = False

    for arg in sys.argv[1:]:
        if arg == "--mark-only":
            mark_only = True
        elif arg.isdigit():
            threshold_days = int(arg)

    print(f"正在查询 stock_info...", file=sys.stderr)
    stock_info = get_stock_info()
    print(f"stock_info 共 {len(stock_info)} 只", file=sys.stderr)

    # 已标记退市的数量
    already_marked = sum(1 for s in stock_info.values() if s.get("delist_date"))
    print(f"已标记退市: {already_marked} 只", file=sys.stderr)

    print(f"正在查询 akshare 退市数据...", file=sys.stderr)
    akshare_delisted = get_delisted_from_akshare()

    print(f"正在查询 ClickHouse（停牌>{threshold_days}天）...", file=sys.stderr)
    ch_inactive = get_inactive_from_clickhouse(threshold_days)

    candidates, already_marked = merge_delisted_candidates(stock_info, akshare_delisted, ch_inactive, threshold_days)
    print(f"合并后退市候选: {len(candidates)} 只 (已标记: {len(already_marked)} 只)", file=sys.stderr)

    # 更新 stock_info.delist_date（只更新未标记的候选）
    updated = update_delist_dates(candidates)

    if mark_only:
        print(f"标记完成: 新增 {updated} 只", file=sys.stderr)
        return

    # 输出 JSON：已标记 + 未标记合并，按停牌天数降序
    result = list(candidates.values()) + list(already_marked.values())
    result.sort(key=lambda x: x["days_inactive"], reverse=True)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"输出 {len(result)} 只 (已标记 {len(already_marked)}, 待处理 {len(candidates)})", file=sys.stderr)


if __name__ == "__main__":
    main()
