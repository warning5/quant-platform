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
import clickhouse_connect

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

# ClickHouse 配置
CH_CONFIG = dict(
    host="localhost",
    port=8123,
    username="default",
    password="123456",
    database="stock",
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

def load_stocks_from_db(conn, skip_bj: bool = False) -> tuple:
    """
    从数据库读取所有需要更新的股票。
    返回: ([(code, market), ...], {code: {name, is_st, total_market_cap, float_market_cap, pe_ttm, pb}})
    """
    with conn.cursor() as cur:
        if skip_bj:
            cur.execute("SELECT code, market FROM stock_info WHERE market IN ('SH','SZ') ORDER BY code")
        else:
            cur.execute("SELECT code, market FROM stock_info ORDER BY code")
        rows = cur.fetchall()
    stocks = [(r["code"], r["market"]) for r in rows]
    print(f"[INFO] 从数据库加载 {len(stocks)} 只股票")

    # 加载旧值用于变更统计
    old_vals = {}
    if stocks:
        codes = [s[0] for s in stocks]
        with conn.cursor() as cur:
            for i in range(0, len(codes), 500):
                batch = codes[i:i + 500]
                placeholders = ",".join(["%s"] * len(batch))
                cur.execute(
                    f"SELECT code, name, is_st, total_market_cap, float_market_cap, pe_ttm, pb "
                    f"FROM stock_info WHERE code IN ({placeholders})",
                    batch,
                )
                for r in cur.fetchall():
                    old_vals[r["code"]] = {
                        "name": r.get("name"),
                        "is_st": r.get("is_st"),
                        "total_market_cap": r.get("total_market_cap"),
                        "float_market_cap": r.get("float_market_cap"),
                        "pe_ttm": r.get("pe_ttm"),
                        "pb": r.get("pb"),
                    }
    print(f"[INFO] 加载旧值 {len(old_vals)} 条用于变更对比")

    return stocks, old_vals


def batch_update_db(conn, stocks_data: dict, old_vals: dict, dry_run: bool = False) -> tuple:
    """
    批量更新数据库，并统计各字段变更数量。
    stocks_data: {code: {name, total_market_cap, float_market_cap, pe_ttm, pb}}
    old_vals: {code: {name, is_st, total_market_cap, float_market_cap, pe_ttm, pb}}
    返回: (成功数, 未命中数, 失败数, 变更统计dict)
    """
    now = datetime.datetime.now()
    ok = miss = err = 0

    # 字段变更统计
    FIELD_LABELS = {
        "name": "名称",
        "is_st": "ST状态",
        "total_market_cap": "总市值",
        "float_market_cap": "流通市值",
        "pe_ttm": "PE(TTM)",
        "pb": "PB",
    }
    change_counts = {k: 0 for k in FIELD_LABELS}

    def values_differ(old_v, new_v):
        """比较新旧值，None 和 0 视为等效于无值"""
        if old_v is None and new_v is None:
            return False
        if old_v is None or new_v is None:
            return True
        if isinstance(old_v, float) and isinstance(new_v, (int, float)):
            return abs(old_v - float(new_v)) > 1e-6
        return str(old_v) != str(new_v)

    if dry_run:
        print(f"[DRY-RUN] 共 {len(stocks_data)} 条数据，不写入数据库")
        samples = list(stocks_data.items())[:3]
        for code, d in samples:
            print(f"  示例 {code}: {d}")
        return 0, 0, 0, change_counts

    with conn.cursor() as cur:
        for code, d in stocks_data.items():
            new_name = d.get("name")
            new_is_st = is_st_stock(new_name or "")
            rec = {
                "code":             code,
                "name":             new_name,
                "is_st":            new_is_st,
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
                    # 对比旧值统计变更
                    old = old_vals.get(code, {})
                    if values_differ(old.get("name"), new_name):
                        change_counts["name"] += 1
                    if values_differ(old.get("is_st"), new_is_st):
                        change_counts["is_st"] += 1
                    if values_differ(old.get("total_market_cap"), d.get("total_market_cap")):
                        change_counts["total_market_cap"] += 1
                    if values_differ(old.get("float_market_cap"), d.get("float_market_cap")):
                        change_counts["float_market_cap"] += 1
                    if values_differ(old.get("pe_ttm"), d.get("pe_ttm")):
                        change_counts["pe_ttm"] += 1
                    if values_differ(old.get("pb"), d.get("pb")):
                        change_counts["pb"] += 1
                else:
                    miss += 1
            except Exception as e:
                err += 1
                if err <= 5:
                    print(f"[ERROR] 更新失败 code={code}：{e}")

    conn.commit()

    # 输出变更统计（始终显示所有字段，0 变更的也输出）
    parts = [f"{FIELD_LABELS[k]}:{v}" for k, v in change_counts.items()]
    print(f"[FIELD_CHANGES] {' | '.join(parts)}")

    return ok, miss, err, change_counts


# ─── ClickHouse 双写 ───────────────────────────────────────────────────────────
def _ch_batch_update_stock_info(conn, stocks_data: dict, dry_run: bool = False) -> int:
    """
    ClickHouse 双写：以 MySQL 全量为基准，用 stocks_data 覆盖最新值。
    确保所有股票都写入 CH（API 拉取失败的用 MySQL 旧值兜底）。
    """
    now = datetime.datetime.now()

    # 1. 从 MySQL 加载全量 stock_info（不含 symbol，MySQL 无此列）
    mysql_rows = []
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, code, name, market, industry, list_date, is_hs, is_st, "
            "total_share, float_share, total_market_cap, float_market_cap, "
            "pe_ttm, pb, create_time, update_time "
            "FROM stock_info ORDER BY code"
        )
        mysql_rows = cur.fetchall()

    if not mysql_rows:
        print("[CH] MySQL 中无数据，跳过 CH 双写")
        return 0

    # 2. 构建 CH rows，用 stocks_data 覆盖最新值
    rows = []
    for r in mysql_rows:
        code = r["code"]
        d = stocks_data.get(code, {})

        new_name = d.get("name") or r["name"]
        new_is_st = is_st_stock(new_name or "")
        m = r["market"] or ""

        pe = d.get("pe_ttm")
        pb = d.get("pb")
        pe_final = float(pe) if pe is not None else (float(r["pe_ttm"]) if r["pe_ttm"] is not None else 0.0)
        pb_final = float(pb) if pb is not None else (float(r["pb"]) if r["pb"] is not None else 0.0)

        # symbol: MySQL 无此列，实时计算
        sym = f"{code}.{m}" if m else code

        rows.append([
            r["id"] if r["id"] is not None else 0,
            code,
            new_name or "",
            m,
            r["industry"] or "",
            r["list_date"] or datetime.date(1970, 1, 1),
            r["is_hs"] if r["is_hs"] is not None else 0,
            new_is_st,
            float(r["total_share"] or 0),
            float(r["float_share"] or 0),
            float(d.get("total_market_cap") or r["total_market_cap"] or 0),
            float(d.get("float_market_cap") or r["float_market_cap"] or 0),
            pe_final,
            pb_final,
            r["create_time"],
            now,
            sym,
        ])

    if dry_run:
        print(f"[DRY-RUN] ClickHouse 模拟写入 {len(rows)} 条（MySQL 全量 {len(mysql_rows)} 条）")
        return len(rows)

    try:
        ch = clickhouse_connect.get_client(**CH_CONFIG)

        # 删除所有 MySQL 中存在的 code
        codes_to_del = [r["code"] for r in mysql_rows]
        for i in range(0, len(codes_to_del), 500):
            chunk = codes_to_del[i:i + 500]
            ph = ", ".join(f"'{c}'" for c in chunk)
            ch.command(f"ALTER TABLE stock_info DELETE WHERE code IN ({ph})")

        # 批量写入
        ch.insert(
            "stock_info",
            rows,
            column_names=[
                "id", "code", "name", "market", "industry",
                "list_date", "is_hs", "is_st",
                "total_share", "float_share",
                "total_market_cap", "float_market_cap",
                "pe_ttm", "pb",
                "create_time", "update_time", "symbol",
            ]
        )
        ch.close()
        print(f"[CH] ClickHouse 双写完成 {len(rows)} 条（MySQL 全量兜底）")
        return len(rows)
    except Exception as e:
        print(f"[ERROR] ClickHouse 双写失败: {e}")
        traceback.print_exc()
        return 0


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
        # 1. 从数据库读取股票列表 + 旧值
        stocks, old_vals = load_stocks_from_db(conn, skip_bj=args.skip_bj)

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

        # 3. 写入数据库（含变更统计）
        ok, miss, err, change_counts = batch_update_db(conn, stocks_data, old_vals, dry_run=args.dry_run)

        # 4. ClickHouse 双写（以 MySQL 全量兜底）
        if not args.dry_run:
            _ch_batch_update_stock_info(conn, stocks_data, dry_run=False)

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
