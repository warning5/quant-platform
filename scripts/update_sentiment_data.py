#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
update_sentiment_data.py
========================
拉取市场情绪数据并双写 ClickHouse + MySQL：
  1. 涨跌停池（涨停/跌停/炸板）—— 东方财富
  2. 北向资金（指数级别 + 个股持股）—— 东方财富
  3. 资金情绪代理指标（从涨停池提取换手率/量比）

ClickHouse 表：
  - stock.stock_sentiment_zt         （涨跌停池）
  - stock.stock_sentiment_hsgt       （北向资金指数）
  - stock.stock_sentiment_hsgt_hold  （北向持股）
  - stock.stock_sentiment_moneyflow  （资金情绪代理）

MySQL 表（与 ClickHouse 同名，字段一致）：
  - stock_sentiment_zt
  - stock_sentiment_hsgt
  - stock_sentiment_hsgt_hold
  - stock_sentiment_moneyflow

用法：
  python update_sentiment_data.py                    # 全部
  python update_sentiment_data.py --skip-zt          # 跳过涨跌停
  python update_sentiment_data.py --date 20260430    # 指定日期
  python update_sentiment_data.py --dry-run          # 不写库
"""

import sys
import time
import datetime
import traceback
import argparse

import pandas as pd
import clickhouse_connect
import requests

# ─── 配置 ──────────────────────────────────────────────────────
CH_CONFIG = dict(
    host="localhost", port=8123,
    username="default", password="123456", database="stock",
)

MYSQL_CONFIG = dict(
    host="localhost", port=3306,
    user="root", password="123456", database="stock",
    charset="utf8mb4",
)

QQ_HEADERS = {
    "Referer": "https://finance.qq.com",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
}
QQ_URL = "https://qt.gtimg.cn/q={codes}"

# ─── 工具 ──────────────────────────────────────────────────────

def to_float(val, default=0.0):
    if val is None or str(val).strip() in ("", "-", "None"):
        return default
    try:
        f = float(val)
        return f if f == f else default  # NaN → default
    except (ValueError, TypeError):
        return default


def to_date(val):
    """将 '2026-04-30' / 'YYYYMMDD' / date 对象转为 date"""
    if isinstance(val, datetime.date):
        return val
    s = str(val).strip()
    if not s or s in ("None", "-"):
        return None
    # YYYYMMDD
    if len(s) == 8 and s.isdigit():
        return datetime.date(int(s[:4]), int(s[4:6]), int(s[6:8]))
    # YYYY-MM-DD
    if len(s) >= 10:
        return datetime.date.fromisoformat(s[:10])
    return None


def code_to_symbol(code: str) -> tuple:
    """返回 (symbol, market)"""
    if code.startswith(("60", "68", "90")):
        return f"{code}.SH", "SH", f"sh{code}"
    elif code.startswith(("00", "30", "20")):
        return f"{code}.SZ", "SZ", f"sz{code}"
    else:
        return f"{code}.BJ", "BJ", f"bj{code}"


# ─── ClickHouse 写入 ───────────────────────────────────────────

def _ch_batch_insert(table: str, rows: list, column_names: list, dry_run: bool = False) -> int:
    """通用 CH 批量写入（ReplacingMergeTree，直接 INSERT 覆盖）"""
    if not rows:
        print(f"[CH] {table}: 无数据，跳过")
        return 0
    if dry_run:
        print(f"[DRY-RUN] {table}: CH 模拟写入 {len(rows)} 条")
        return len(rows)
    try:
        client = clickhouse_connect.get_client(**CH_CONFIG)
        client.insert(table, rows, column_names=column_names)
        client.close()
        print(f"[CH] {table}: 写入 {len(rows)} 条")
        return len(rows)
    except Exception as e:
        print(f"[ERROR] {table} CH 写入失败: {e}")
        traceback.print_exc()
        return 0


# ─── MySQL 写入 ─────────────────────────────────────────────────

def _mysql_batch_upsert(table: str, rows: list, column_names: list, unique_cols: list, dry_run: bool = False) -> int:
    """MySQL 批量 REPLACE INTO（基于唯一键覆盖）"""
    if not rows:
        return 0
    if dry_run:
        print(f"[DRY-RUN] {table}: MySQL 模拟写入 {len(rows)} 条")
        return len(rows)
    try:
        import pymysql
        conn = pymysql.connect(**MYSQL_CONFIG)
        cur = conn.cursor()

        placeholders = ", ".join(["%s"] * len(column_names))
        sql = f"REPLACE INTO {table} ({', '.join(column_names)}) VALUES ({placeholders})"

        # 批量写入
        cur.executemany(sql, rows)
        conn.commit()
        affected = cur.rowcount
        cur.close()
        conn.close()
        print(f"[MySQL] {table}: 写入 {len(rows)} 条（affected={affected}）")
        return affected
    except Exception as e:
        print(f"[ERROR] {table} MySQL 写入失败: {e}")
        traceback.print_exc()
        return 0


# ─── 双写入口 ──────────────────────────────────────────────────

def _dual_write(
        table: str, rows: list, ch_columns: list,
        mysql_columns: list, mysql_unique: list, dry_run: bool = False) -> None:
    """同时写入 CH + MySQL"""
    if not rows:
        print(f"[SKIP] {table}: 无数据")
        return
    ch_ok = _ch_batch_insert(table, rows, ch_columns, dry_run)
    mysql_ok = _mysql_batch_upsert(table, rows, mysql_columns, mysql_unique, dry_run)
    if ch_ok > 0 and mysql_ok > 0:
        print(f"  ✓ {table}: CH({ch_ok}) + MySQL({mysql_ok}) 写入完成")
    elif ch_ok > 0:
        print(f"  ⚠ {table}: CH 成功，MySQL 失败")
    elif mysql_ok > 0:
        print(f"  ⚠ {table}: MySQL 成功，CH 失败")


# ─── 涨跌停数据 ────────────────────────────────────────────────

def fetch_zt_data(date_str: str) -> tuple:
    """
    获取涨跌停池（涨停强势 + 跌停 + 炸板）。
    返回 (zt_rows, dt_rows, zbgc_rows)
    """
    import akshare as ak

    zt_rows, dt_rows, zbgc_rows = [], [], []

    # 1) 涨停强势池
    try:
        df = ak.stock_zt_pool_strong_em(date=date_str)
        if df is not None and not df.empty:
            for _, r in df.iterrows():
                code = str(r.get("代码", "")).zfill(6)
                # 涨停统计 1/2 → is_new=1, zt_type=zt
                zt_stat = str(r.get("涨停统计", ""))
                is_new = 1 if zt_stat.startswith("1/") else 0
                zt_rows.append([
                    code, to_date(date_str), code, str(r.get("名称", "")),
                    to_float(r.get("最新价")), to_float(r.get("涨跌幅")),
                    "zt", str(r.get("入选理由", ""))[:200], is_new,
                    datetime.datetime.now(),
                ])
            print(f"  涨停强势池: {len(zt_rows)} 条")
    except Exception as e:
        print(f"  涨停强势池获取失败: {e}")

    # 2) 跌停池
    try:
        df2 = ak.stock_zt_pool_dtgc_em(date=date_str)
        if df2 is not None and not df2.empty:
            for _, r in df2.iterrows():
                code = str(r.get("代码", "")).zfill(6)
                zt_rows.append([
                    code, to_date(date_str), code, str(r.get("名称", "")),
                    to_float(r.get("最新价")), to_float(r.get("涨跌幅")),
                    "dt", str(r.get("所属行业", ""))[:200], 0,
                    datetime.datetime.now(),
                ])
            print(f"  跌停池: {len(df2)} 条")
    except Exception as e:
        print(f"  跌停池获取失败: {e}")

    # 3) 炸板池（已涨停又打开）
    try:
        df3 = ak.stock_zt_pool_zbgc_em(date=date_str)
        if df3 is not None and not df3.empty:
            for _, r in df3.iterrows():
                code = str(r.get("代码", "")).zfill(6)
                zt_rows.append([
                    code, to_date(date_str), code, str(r.get("名称", "")),
                    to_float(r.get("最新价")), to_float(r.get("涨跌幅")),
                    "zbgc", str(r.get("所属行业", ""))[:200], 0,
                    datetime.datetime.now(),
                ])
            print(f"  炸板池: {len(df3)} 条")
    except Exception as e:
        print(f"  炸板池获取失败: {e}")

    return zt_rows, dt_rows, zbgc_rows


# ─── 北向资金数据 ──────────────────────────────────────────────

def fetch_hsgt_index(date_str: str) -> list:
    """获取北向资金指数级别数据"""
    import akshare as ak

    rows = []
    try:
        df = ak.stock_hsgt_hist_em(symbol="北向资金")
        if df is None or df.empty:
            return rows
        # 取最近 5 天
        for _, r in df.head(5).iterrows():
            d = to_date(r.get("日期"))
            if d is None:
                continue
            # 单位：亿元 → 元；负数自动转
            net = to_float(r.get("当日成交净买额")) * 1e8
            rows.append([
                d, net, 0.0, 0.0,  # shanghai_net / shenzhen_net 暂无单独数据
                datetime.datetime.now(),
            ])
        print(f"  北向资金历史: {len(rows)} 条（最近5天）")
    except Exception as e:
        print(f"  北向资金指数获取失败: {e}")
    return rows


def fetch_hsgt_hold(date_str: str) -> list:
    """获取北向持股数据"""
    import akshare as ak

    rows = []
    # 尝试多个 indicator 指标，任一成功即可
    for indicator in ["5日排行", "10日排行", "3日排行"]:
        try:
            df = ak.stock_hsgt_hold_stock_em(market="北向", indicator=indicator)
            if df is None or df.empty:
                continue
            trade_date = to_date(df.iloc[0].get("日期")) if len(df) > 0 else None
            for _, r in df.iterrows():
                code = str(r.get("代码", "")).strip().zfill(6)
                if not code or code == "000000":
                    continue
                hold_amount = to_float(r.get("今日持股-市值")) * 1e4
                hold_pct = to_float(r.get("今日持股-占流通股比"))
                rows.append([
                    trade_date, code, code, str(r.get("名称", ""))[:50],
                    hold_amount, hold_pct,
                    datetime.datetime.now(),
                ])
            print(f"  北向持股 [{indicator}]: {len(rows)} 条")
            break  # 成功则退出
        except Exception as e:
            print(f"  北向持股 [{indicator}] 失败: {e}")
            continue
    if not rows:
        print("  北向持股: 所有指标均失败（可能被反爬），跳过")
    return rows


# ─── 个股资金流（腾讯接口）─────────────────────────────────────

def fetch_moneyflow_from_zt(df: pd.DataFrame, date_str: str) -> list:
    """
    从涨停池 DataFrame 提取资金情绪指标，作为资金流代理。
    不依赖 eastmoney 资金流接口（该接口被反爬封锁）。
    涨停池已有：换手率、量比、成交额、流通市值、总市值 等。
    """
    rows = []
    for _, r in df.iterrows():
        code = str(r.get("代码", "")).strip().zfill(6)
        if not code or code == "000000":
            continue
        # 换手率(%)、量比、成交额(元)
        turnover = to_float(r.get("换手率"))
        vol_ratio = to_float(r.get("量比"))
        amount = to_float(r.get("成交额"))
        total_cap = to_float(r.get("总市值"))
        float_cap = to_float(r.get("流通市值"))

        rows.append([
            code,
            to_date(date_str),
            code,
            str(r.get("名称", "")),
            to_float(r.get("最新价")),
            to_float(r.get("涨跌幅")),
            0.0,    # net_main (暂无)
            0.0,    # net_main_pct
            0.0,    # net_huge
            0.0,    # net_big
            turnover * 1e8 if turnover else 0.0,
            vol_ratio * 1e8 if vol_ratio else 0.0,
            datetime.datetime.now(),
        ])
    return rows


# ─── 东方财富人气榜 ────────────────────────────────────────
def fetch_hot_rank(date_str: str) -> list:
    """
    获取东方财富个股人气榜（市场热度排名）。
    返回 rows: [rank, ts_code, code, name, trade_date, hot_value, update_time]
    """
    import akshare as ak

    rows = []
    today = to_date(date_str) or datetime.date.today()
    try:
        df = ak.stock_hot_rank_em()
        if df is None or df.empty:
            print("  人气榜: 无数据")
            return rows
        for _, r in df.iterrows():
            code = str(r.get("代码", "")).strip().zfill(6)
            if not code or code == "000000":
                continue
            rows.append([
                int(r.get("当前排名", 0) or 0),
                code,
                code,
                str(r.get("股票名称", ""))[:50],
                today,
                to_float(r.get("热度值")),
                datetime.datetime.now(),
            ])
        print(f"  人气榜: {len(rows)} 条")
    except Exception as e:
        print(f"  人气榜获取失败: {e}")
    return rows


# ─── 东方财富公告 ──────────────────────────────────────────
def fetch_notice_report(date_str: str) -> list:
    """
    获取东方财富沪深京A股公告。
    返回 rows: [ts_code, code, name, notice_type, notice_date, title, update_time]
    """
    import akshare as ak

    rows = []
    date_display = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:]}"
    try:
        df = ak.stock_notice_report(symbol="全部", date=date_str)
        # 接口返回空时 big_df["代码"] 会抛 KeyError，需要健壮处理
        if df is None or df.empty:
            print(f"  公告({date_display}): 无数据")
            return rows
        # 安全地获取列名
        col_code = "代码" if "代码" in df.columns else (
            "股票代码" if "股票代码" in df.columns else None)
        col_name = "股票简称" if "股票简称" in df.columns else (
            "名称" if "名称" in df.columns else None)
        if not col_code:
            print(f"  公告({date_display}): 未知列名 {list(df.columns)}")
            return rows
        for _, r in df.iterrows():
            code = str(r.get(col_code, "")).strip().zfill(6)
            if not code:
                continue
            rows.append([
                code,
                code,
                str(r.get(col_name, ""))[:50],
                str(r.get("公告类型", ""))[:50],
                to_date(r.get("公告日期") or date_str),
                str(r.get("公告标题", ""))[:500],
                datetime.datetime.now(),
            ])
        print(f"  公告({date_display}): {len(rows)} 条")
    except Exception as e:
        print(f"  公告获取失败: {e}")
    return rows


# ─── CCTV新闻情绪 ─────────────────────────────────────────
def fetch_cctv_news() -> list:
    """
    获取CCTV新闻标题作为市场情绪代理。
    返回 rows: [news_date, title, update_time]
    """
    import akshare as ak

    rows = []
    try:
        df = ak.news_cctv()
        if df is None or df.empty:
            print("  CCTV新闻: 无数据")
            return rows
        for _, r in df.iterrows():
            news_date = str(r.get("date", ""))[:10]
            if not news_date:
                continue
            rows.append([
                news_date,
                str(r.get("title", ""))[:500],
                datetime.datetime.now(),
            ])
        print(f"  CCTV新闻: {len(rows)} 条")
    except Exception as e:
        print(f"  CCTV新闻获取失败: {e}")
    return rows


# ─── A股新闻情绪指数（数库，接口可能不可用）──────────────
def fetch_news_sentiment_scope() -> list:
    """
    获取A股新闻情绪指数（数库数据）。
    注：该接口依赖外部数据源，可能不可用。
    返回 rows: [trade_date, sentiment_index, update_time]
    """
    import akshare as ak

    rows = []
    try:
        df = ak.index_news_sentiment_scope()
        if df is None or df.empty:
            print("  新闻情绪指数: 无数据（数据源可能不可用）")
            return rows
        # 取最近 5 天
        for _, r in df.head(5).iterrows():
            d = to_date(r.get("日期"))
            if d is None:
                continue
            rows.append([
                d,
                to_float(r.get("情绪指数")),
                datetime.datetime.now(),
            ])
        print(f"  新闻情绪指数: {len(rows)} 条")
    except Exception as e:
        print(f"  新闻情绪指数获取失败（数据源不可用）: {e}")
    return rows


# ─── 主流程 ────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="市场情绪数据采集")
    parser.add_argument("--date", default=None, help="指定日期 YYYYMMDD（默认今日）")
    parser.add_argument("--dry-run", action="store_true", help="不写入数据库")
    parser.add_argument("--skip-zt", action="store_true", help="跳过涨跌停")
    parser.add_argument("--skip-hsgt", action="store_true", help="跳过北向资金")
    parser.add_argument("--skip-moneyflow", action="store_true", help="跳过资金流")
    parser.add_argument("--skip-hot-rank", action="store_true", help="跳过热度排名")
    parser.add_argument("--skip-notice", action="store_true", help="跳过公告数据")
    parser.add_argument("--skip-cctv-news", action="store_true", help="跳过CCTV新闻")
    args = parser.parse_args()

    if args.date:
        date_str = args.date  # YYYYMMDD
        date_display = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:]}"
    else:
        today = datetime.date.today()
        date_str = today.strftime("%Y%m%d")
        date_display = today.strftime("%Y-%m-%d")

    start = datetime.datetime.now()
    print("=" * 60)
    print(f"  市场情绪数据采集  {date_display}")
    print("=" * 60)

    # ── 1. 涨跌停 ───────────────────────────────────────────────
    zt_rows = []
    if not args.skip_zt:
        print(f"[INFO] 涨跌停池 ({date_str})...")
        zt_rows, _, _ = fetch_zt_data(date_str)
        total_zt = len(zt_rows)
        if total_zt > 0:
            _dual_write(
                "stock_sentiment_zt", zt_rows,
                ["ts_code", "trade_date", "code", "name", "close",
                 "pct_change", "zt_type", "reason", "is_new", "update_time"],
                ["ts_code", "trade_date", "code", "name", "close",
                 "pct_change", "zt_type", "reason", "is_new", "update_time"],
                ["code", "trade_date", "zt_type"],
                dry_run=args.dry_run,
            )
        else:
            print(f"  当日 ({date_str}) 无涨跌停数据（可能非交易日）")
    else:
        print("[SKIP] 涨跌停已跳过")

    # ── 2. 北向资金指数 ─────────────────────────────────────────
    if not args.skip_hsgt:
        print("[INFO] 北向资金指数...")
        hsgt_rows = fetch_hsgt_index(date_str)
        if hsgt_rows:
            _dual_write(
                "stock_sentiment_hsgt", hsgt_rows,
                ["trade_date", "north_net_inflow",
                 "shanghai_net", "shenzhen_net", "update_time"],
                ["trade_date", "north_net_inflow",
                 "shanghai_net", "shenzhen_net", "update_time"],
                ["trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无北向资金数据")
    else:
        print("[SKIP] 北向资金已跳过")

    # ── 3. 北向持股 ─────────────────────────────────────────────
    if not args.skip_hsgt:
        print("[INFO] 北向持股...")
        hold_rows = fetch_hsgt_hold(date_str)
        if hold_rows:
            _dual_write(
                "stock_sentiment_hsgt_hold", hold_rows,
                ["trade_date", "ts_code", "code", "name",
                 "hold_amount", "hold_pct", "update_time"],
                ["trade_date", "ts_code", "code", "name",
                 "hold_amount", "hold_pct", "update_time"],
                ["code", "trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  无北向持股数据")
    else:
        print("[SKIP] 北向持股已跳过")

    # ── 4. 资金情绪（从涨停池提取换手率/量比/成交额作为代理）─────
    if not args.skip_moneyflow and not args.skip_zt:
        print("[INFO] 资金情绪（涨停池量价代理指标）...")
        if zt_rows:
            import akshare as ak
            try:
                df_strong = ak.stock_zt_pool_strong_em(date=date_str)
                if df_strong is not None and not df_strong.empty:
                    mf_rows = fetch_moneyflow_from_zt(df_strong, date_str)
                    if mf_rows:
                        _dual_write(
                            "stock_sentiment_moneyflow", mf_rows,
                            ["ts_code", "trade_date", "code", "name", "close",
                             "pct_change", "net_main", "net_main_pct",
                             "net_huge", "net_big", "net_medium", "net_small", "update_time"],
                            ["ts_code", "trade_date", "code", "name", "close",
                             "pct_change", "net_main", "net_main_pct",
                             "net_huge", "net_big", "net_medium", "net_small", "update_time"],
                            ["code", "trade_date"],
                            dry_run=args.dry_run,
                        )
                    else:
                        print("  资金情绪: 无有效数据")
                else:
                    print("  涨停强势池为空，跳过资金情绪")
            except Exception as e:
                print(f"  资金情绪获取失败: {e}")
        else:
            print("  无涨停池数据，跳过资金情绪")
    else:
        if args.skip_moneyflow:
            print("[SKIP] 资金情绪已跳过")

    # ── 5. 东方财富人气榜 ───────────────────────────────────
    if not args.skip_hot_rank:
        print("[INFO] 东方财富人气榜...")
        hr_rows = fetch_hot_rank(date_str)
        if hr_rows:
            _dual_write(
                "stock_sentiment_hot_rank", hr_rows,
                ["rank", "ts_code", "code", "name", "trade_date", "hot_value", "update_time"],
                ["rank", "ts_code", "code", "name", "trade_date", "hot_value", "update_time"],
                ["code", "trade_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  人气榜: 无数据")
    else:
        print("[SKIP] 人气榜已跳过")

    # ── 6. 东方财富公告 ─────────────────────────────────────
    if not args.skip_notice:
        print(f"[INFO] 东方财富公告 ({date_str})...")
        notice_rows = fetch_notice_report(date_str)
        if notice_rows:
            _dual_write(
                "stock_sentiment_notice", notice_rows,
                ["ts_code", "code", "name", "notice_type", "notice_date", "title", "update_time"],
                ["ts_code", "code", "name", "notice_type", "notice_date", "title", "update_time"],
                ["code", "notice_date"],
                dry_run=args.dry_run,
            )
        else:
            print(f"  公告({date_str}): 无数据")
    else:
        print("[SKIP] 公告已跳过")

    # ── 7. CCTV新闻 ─────────────────────────────────────────
    if not args.skip_cctv_news:
        print("[INFO] CCTV新闻...")
        cctv_rows = fetch_cctv_news()
        if cctv_rows:
            _dual_write(
                "stock_sentiment_cctv_news", cctv_rows,
                ["news_date", "title", "update_time"],
                ["news_date", "title", "update_time"],
                ["news_date"],
                dry_run=args.dry_run,
            )
        else:
            print("  CCTV新闻: 无数据")
    else:
        print("[SKIP] CCTV新闻已跳过")

    elapsed = (datetime.datetime.now() - start).total_seconds()
    print("=" * 60)
    print(f"  采集完成  耗时 {elapsed:.1f}s")
    print("=" * 60)


if __name__ == "__main__":
    main()
