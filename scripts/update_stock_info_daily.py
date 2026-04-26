#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
update_stock_info_daily.py
==========================
基于当天收盘数据更新 stock_info 表中的动态字段：
  - 股票简称 (name) —— 处理 ST 变更、摘帽等情况
  - 总市值 (total_market_cap) —— 单位：元
  - 流通市值 (float_market_cap) —— 单位：元
  - 市盈率TTM (pe_ttm)
  - 市净率 (pb)
  - is_st —— 根据最新名称自动判断

数据来源：腾讯财经行情接口（qt.gtimg.cn）
  - 不依赖东方财富，无需翻墙，请求稳定
  - 支持批量拉取（每批最多 50 只股票）

字段映射（以 ~ 分隔）：
  [1]  股票名称
  [3]  当前价/收盘价
  [39] PE TTM（近12个月滚动市盈率）← 正确位置
  [44] 总市值（亿元）
  [45] 流通市值（亿元）
  [46] PB（市净率）
  [52] PE（动态/预测）              ← 注意：不是TTM！
  [53] PE（静态）                   ← 注意：不是PB！

用法：
  python update_stock_info_daily.py             # 更新全部股票
  python update_stock_info_daily.py --skip-bj   # 跳过北交所
  python update_stock_info_daily.py --dry-run   # 仅打印，不写库
  python update_stock_info_daily.py --batch-size 30  # 调整批次大小

依赖：pip install pymysql requests
"""

import sys
import time
import argparse
import datetime
import traceback

import requests
import pymysql

# ─── 数据库配置 ──────────────────────────────────────────────
DB_CONFIG = dict(
    host="localhost",
    port=3306,
    db="stock",
    user="root",
    password="123456",
    charset="utf8mb4",
    cursorclass=pymysql.cursors.DictCursor,
)

# 腾讯财经接口
QQ_URL = "https://qt.gtimg.cn/q={codes}"
QQ_HEADERS = {
    "Referer": "https://finance.qq.com",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
}

# ─── 更新 SQL ─────────────────────────────────────────────────
UPDATE_SQL = """
UPDATE stock_info
SET
    name             = %(name)s,
    is_st            = %(is_st)s,
    total_market_cap = %(total_market_cap)s,
    float_market_cap = %(float_market_cap)s,
    pe_ttm           = %(pe_ttm)s,
    pb               = %(pb)s,
    update_time      = %(update_time)s
WHERE code = %(code)s
"""


# ─── 工具函数 ─────────────────────────────────────────────────

def to_float(val, multiplier=1.0):
    """安全转换为 float，支持乘以系数，NaN/空 → None"""
    if val is None or str(val).strip() == "":
        return None
    try:
        f = float(val) * multiplier
        return None if f != f else f
    except (ValueError, TypeError):
        return None


def is_st_stock(name: str) -> int:
    if not name:
        return 0
    return 1 if "ST" in name.upper() else 0


def market_prefix(code: str, market: str) -> str:
    """转换为腾讯财经的市场代码前缀"""
    m = market.upper() if market else ""
    if m == "SH":
        return f"sh{code}"
    elif m == "SZ":
        return f"sz{code}"
    elif m == "BJ":
        return f"bj{code}"
    else:
        # 按代码前缀自动判断
        if code.startswith(("60", "68", "90")):
            return f"sh{code}"
        elif code.startswith(("00", "30", "20")):
            return f"sz{code}"
        else:
            return f"bj{code}"


# ─── 腾讯接口批量拉取 ─────────────────────────────────────────

def fetch_qq_batch(qq_codes: list, timeout: int = 10) -> dict:
    """
    批量拉取腾讯财经行情数据。
    qq_codes: ['sz000001', 'sh600000', ...]
    返回: {原始代码（如'sz000001'): {name, price, total_market_cap, float_market_cap, pe_ttm, pb}}
    """
    codes_str = ",".join(qq_codes)
    url = QQ_URL.format(codes=codes_str)
    result = {}

    try:
        resp = requests.get(url, headers=QQ_HEADERS, timeout=timeout)
        resp.encoding = "gbk"

        for line in resp.text.strip().split("\n"):
            line = line.strip()
            if not line or "=" not in line:
                continue
            try:
                key = line.split("=")[0].strip().replace("v_", "")
                data = line.split('"')[1]
                fields = data.split("~")

                if len(fields) < 54:
                    continue

                name = fields[1].replace("\u3000", "").strip()  # 去掉全角空格
                result[key] = {
                    "name":             name,
                    # 腾讯字段: [44]=流通市值(亿元), [45]=总市值(亿元)
                    # 交叉验证(601318/601398)：[44]/[45]比值≈流通/总股比，确认正确
                    "total_market_cap": to_float(fields[45], 1e8),   # 总市值(亿元→元)
                    "float_market_cap": to_float(fields[44], 1e8),   # 流通市值(亿元→元)
                    "pb":               to_float(fields[46]),         # 市净率（PB）
                    "pe_ttm":           to_float(fields[39]),         # PE TTM（滚动12个月）
                }
            except Exception:
                continue

    except requests.exceptions.RequestException as e:
        print(f"[WARN] 腾讯接口请求失败（{len(qq_codes)} 只）：{e}")

    return result


def fetch_all_stocks_qq(stocks: list, batch_size: int = 50, delay: float = 0.2) -> dict:
    """
    分批从腾讯财经拉取所有股票行情数据。
    stocks: [(code, market), ...]
    返回: {code: {name, total_market_cap, float_market_cap, pe_ttm, pb}}
    """
    total = len(stocks)
    all_data = {}

    # 构建腾讯代码列表
    qq_code_map = {}  # qq_code → original_code
    for code, market in stocks:
        qq = market_prefix(code, market)
        qq_code_map[qq] = code

    qq_codes = list(qq_code_map.keys())
    batches = [qq_codes[i:i + batch_size] for i in range(0, len(qq_codes), batch_size)]
    total_batches = len(batches)

    print(f"[INFO] 共 {total} 只股票，分 {total_batches} 批拉取（每批 {batch_size} 只）")

    for i, batch in enumerate(batches, 1):
        batch_result = fetch_qq_batch(batch)

        for qq_code, data in batch_result.items():
            orig_code = qq_code_map.get(qq_code)
            if orig_code:
                all_data[orig_code] = data

        if i % 10 == 0 or i == total_batches:
            print(f"[INFO]   进度 {i}/{total_batches} 批，已获取 {len(all_data)} 条")

        if i < total_batches:
            time.sleep(delay)

    print(f"[INFO] 腾讯接口拉取完成，获取 {len(all_data)} 条有效数据")
    return all_data


# ─── 数据库操作 ───────────────────────────────────────────────

def load_stocks_from_db(conn, skip_bj: bool = False) -> list:
    """从数据库读取所有需要更新的股票 [(code, market), ...]"""
    with conn.cursor() as cur:
        if skip_bj:
            cur.execute("SELECT code, market FROM stock_info WHERE market IN ('SH','SZ') ORDER BY code")
        else:
            cur.execute("SELECT code, market FROM stock_info ORDER BY code")
        rows = cur.fetchall()
    stocks = [(r["code"], r["market"]) for r in rows]
    print(f"[INFO] 从数据库加载 {len(stocks)} 只股票")
    return stocks


def batch_update_db(conn, stocks_data: dict, dry_run: bool = False) -> tuple:
    """
    批量更新数据库。
    stocks_data: {code: {name, total_market_cap, float_market_cap, pe_ttm, pb}}
    返回: (成功数, 未命中数, 失败数)
    """
    now = datetime.datetime.now()
    ok = miss = err = 0

    if dry_run:
        print(f"[DRY-RUN] 共 {len(stocks_data)} 条数据，不写入数据库")
        samples = list(stocks_data.items())[:3]
        for code, d in samples:
            print(f"  示例 {code}: {d}")
        return 0, 0, 0

    with conn.cursor() as cur:
        for code, d in stocks_data.items():
            rec = {
                "code":             code,
                "name":             d.get("name"),
                "is_st":            is_st_stock(d.get("name", "")),
                "total_market_cap": d.get("total_market_cap"),
                "float_market_cap": d.get("float_market_cap"),
                "pe_ttm":           d.get("pe_ttm"),
                "pb":               d.get("pb"),
                "update_time":      now,
            }
            try:
                affected = cur.execute(UPDATE_SQL, rec)
                if affected > 0:
                    ok += 1
                else:
                    miss += 1
            except Exception as e:
                err += 1
                if err <= 5:
                    print(f"[ERROR] 更新失败 code={code}：{e}")

    conn.commit()
    return ok, miss, err


# ─── 主流程 ───────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="基于当天收盘数据更新 stock_info 表动态字段（腾讯财经数据源）"
    )
    parser.add_argument("--skip-bj",    action="store_true", help="跳过北交所股票")
    parser.add_argument("--dry-run",    action="store_true", help="仅打印，不写入数据库")
    parser.add_argument("--batch-size", type=int, default=50, help="每批请求股票数（默认50）")
    parser.add_argument("--delay",      type=float, default=0.2, help="批次间延迟秒数（默认0.2）")
    args = parser.parse_args()

    start = datetime.datetime.now()
    print("=" * 65)
    print(f"  stock_info 每日收盘数据更新（腾讯财经）")
    print(f"  {start.strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 65)

    # 连接数据库
    try:
        conn = pymysql.connect(**DB_CONFIG)
        print("[INFO] 数据库连接成功")
    except Exception as e:
        print(f"[ERROR] 数据库连接失败：{e}")
        sys.exit(1)

    try:
        # 1. 从数据库读取股票列表
        stocks = load_stocks_from_db(conn, skip_bj=args.skip_bj)

        if not stocks:
            print("[WARN] 数据库中无股票数据，退出")
            return

        # 2. 批量拉取腾讯财经行情
        stocks_data = fetch_all_stocks_qq(
            stocks,
            batch_size=args.batch_size,
            delay=args.delay
        )

        if not stocks_data:
            print("[ERROR] 未能获取任何行情数据，请检查网络连接")
            return

        # 3. 写入数据库
        ok, miss, err = batch_update_db(conn, stocks_data, dry_run=args.dry_run)

        elapsed = (datetime.datetime.now() - start).total_seconds()
        print("=" * 65)
        if args.dry_run:
            print(f"  [DRY-RUN] 模拟完成  获取 {len(stocks_data)} 条  耗时 {elapsed:.1f}s")
        else:
            print(f"  更新完成  命中 {ok} 条 | 未命中 {miss} 条 | 失败 {err} 条")
            print(f"  总耗时 {elapsed:.1f}s")
        print("=" * 65)

    except Exception:
        traceback.print_exc()
    finally:
        conn.close()


if __name__ == "__main__":
    main()
