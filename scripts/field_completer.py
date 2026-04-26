#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
field_completer.py
==================
stock_daily 字段补全工具模块。

供日线更新脚本调用，确保写入的数据字段完整：
1. change_percent / change_amount / pre_close — SQL 级计算（前一行 close）
2. pe_ttm / pb — Baostock 历史序列接口（每日不同值，不依赖腾讯快照）

⚠️ market_cap / circ_market_cap 已于 2026-04-25 从 stock_daily 表删除，
市值数据统一从 stock_info.total_market_cap 获取。

通过 db_helper 操作数据库，自动适配 ClickHouse / MySQL。

用法:
    from field_completer import complete_fields
    complete_fields(db, code="600000")
    complete_fields(db, stock_list=[("000001", "SZ"), ...])
"""

import time
import datetime
import decimal
import requests
import warnings




# ─── 腾讯行情接口配置 ──────────────────────────────────────────
QQ_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://gu.qq.com/",
}

MARKET_PREFIX = {"SH": "sh", "SZ": "sz", "BJ": "bj"}

import baostock as _bs
_HAS_BAOSTOCK = True

# ─── 调试日志 ─────────────────────────────────────────────────
import os as _os
_DEBUG_LOG = _os.path.join(_os.path.dirname(__file__), "debug_valuation.log")

def _dlog(msg):
    ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    line = f"[{ts}] {msg}"
    print(line, flush=True)
    try:
        with open(_DEBUG_LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def _parse_val(s):
    """安全解析腾讯接口的数值"""
    if not s or s in ("-", "0.00", ""):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _bs_code(code, market):
    """转换为 Baostock 代码格式"""
    prefix = {"SH": "sh.", "SZ": "sz.", "BJ": "bj."}.get(market, "sz.")
    return f"{prefix}{code}"


def _bs_login():
    """安全登录 baostock"""
    lg = _bs.login()
    if lg.error_code != "0":
        raise RuntimeError(f"Baostock 登录失败: {lg.error_msg}")
    return lg


def fix_valuation_by_qq(db, codes=None, batch_size=200, delay=0.1, max_workers=30):
    """
    通过 Baostock 历史接口补全 pe_ttm / pb（按日历史序列，不依赖腾讯快照）。

    此函数只负责 PE / PB 的历史补全。

    参数:
        db: StockDailyDB 实例
        codes: [(code, market), ...] 列表。None = 自动查询缺失 PE/PB 的股票
        batch_size: 每批处理的股票数
    返回: 补全的记录数
    """
    latest_date = db.get_latest_date()
    print(f"[fix_valuation_by_qq] ENTRY | backend={db.backend} latest_date={latest_date}")

    if not latest_date:
        print("[fix_valuation_by_qq] ABORT: latest_date is None")
        return 0

    # ── 确定待补全的股票列表 ──────────────────────────────────────
    if codes is None:
        codes = db.get_all_codes_with_missing_pe_pb()
        print(f"[fix_valuation_by_qq] auto mode (全量扫描): {len(codes)} 只股票缺失 PE/PB")
    else:
        print(f"[fix_valuation_by_qq] manual mode: {len(codes)} 只股票")

    if not codes:
        print("[fix_valuation_by_qq] 无缺失 PE/PB 的股票")
        return 0

    codes = list(dict.fromkeys(codes))
    print(f"[fix_valuation_by_qq] 待处理: {len(codes)} 只")

    # ── 第一步：批量查询每只股票缺失 PE/PB 的日期范围 ────────────
    print("[fix_valuation_by_qq] 批量查询缺失 PE/PB 的日期范围...")
    code_list = [c for c, m in codes]
    date_range_map = db.get_pe_pb_missing_range(code_list) if hasattr(db, 'get_pe_pb_missing_range') else {}
    if not date_range_map:
        # 兜底：逐只查
        date_range_map = {}
        for code, market in codes:
            rows = db.query(
                f"SELECT MIN(trade_date) as min_d, MAX(trade_date) as max_d "
                f"FROM {db.CH_TABLE} "
                f"WHERE code = %(code)s AND (pe_ttm IS NULL OR pb IS NULL)",
                {"code": code}
            )
            if rows and rows[0][0]:
                # ClickHouse 的 MIN/MAX 在全 NULL 时返回 1970-01-01，跳过
                mn_val = rows[0][0]
                mx_val = rows[0][1]
                mn_str = str(mn_val)
                mx_str = str(mx_val)
                # 判断是否是 ClickHouse NULL 默认值（< 1970-01-03）
                mn_is_null = hasattr(mn_val, 'year') and mn_val.year < 1975
                if not mn_is_null:
                    date_range_map[code] = (mn_str, mx_str)

    codes_with_range = [(c, date_range_map.get(c)) for c, m in codes]
    codes_need_fetch = [(c, m) for c, m in codes if c in date_range_map]
    print(f"  {len(codes_need_fetch)} 只有缺失日期（需要抓取）")

    if not codes_need_fetch:
        return 0

    # ── 第二步：Baostock 串行抓取历史 PE/PB（baostock 不支持并发！全局 socket）──
    BS_BATCH = 100
    total_fixed = 0
    done_stocks = 0

    # 一次 login，整个批次内复用（baostock 全局单连接）
    _bs.login()
    try:
        for code, market in codes_need_fetch:
            if code not in date_range_map:
                done_stocks += 1
                continue
            mn, mx = date_range_map[code]
            bs_code = _bs_code(code, market)
            BS_MIN_DATE = "20050101"
            mn_fmt = mn.replace("-", "")
            mx_fmt = mx.replace("-", "")
            if mn_fmt < BS_MIN_DATE:
                mn_fmt = BS_MIN_DATE

            raw_rows = []
            attempt = 0
            last_error = None
            while attempt < 3:
                try:
                    rs = _bs.query_history_k_data_plus(
                        bs_code,
                        "date,close,peTTM,pbMRQ",
                        start_date=mn_fmt,
                        end_date=mx_fmt,
                        frequency="d"
                    )
                    while rs.next():
                        raw_rows.append(rs.get_row_data())
                    break
                except Exception as e:
                    last_error = str(e)
                    # 编码/压缩错误直接跳过，不重试
                    if "codec can't decode" in last_error or "decompressing data" in last_error:
                        print(f"    [SKIP] {code} Baostock 返回数据编码异常，跳过: {last_error[:60]}")
                        raw_rows = []
                        break
                    attempt += 1
                    if attempt >= 3:
                        print(f"    [WARN] {code} Baostock 查询失败({attempt}次): {last_error[:60]}")
                        break
                    # 重新 login（连接可能已断开）
                    try:
                        _bs.logout()
                    except Exception:
                        pass
                    time.sleep(1)
                    _bs.login()

            date_map = {}
            for row in raw_rows:
                d, pe_str, pb_str = row[0], row[2], row[3]
                if not d:
                    continue
                pe = float(pe_str) if pe_str and pe_str not in ("", "-") else None
                pb = float(pb_str) if pb_str and pb_str not in ("", "-") else None
                if pe is not None and pe < 0:
                    pe = None
                if pb is not None and pb < 0:
                    pb = None
                if pe is not None or pb is not None:
                    date_map[d] = (pe, pb)

            if date_map:
                batch_updates = []
                for d, (pe, pb) in date_map.items():
                    batch_updates.append((pe, None, None, pb, code, d))
                if batch_updates:
                    n = db.update_valuation_batch(batch_updates)
                    total_fixed += n

            done_stocks += 1
            if done_stocks % 50 == 0 or done_stocks == len(codes_need_fetch):
                pct = done_stocks * 100 // len(codes_need_fetch)
                print(f"  进度 {done_stocks}/{len(codes_need_fetch)} ({pct}%) | "
                      f"累计写入 {total_fixed}")

            time.sleep(delay)
    finally:
        try:
            _bs.logout()
        except Exception:
            pass

    print(f"[fix_valuation_by_qq] DONE | total_fixed={total_fixed}")
    return total_fixed



def complete_fields(db, code=None, stock_list=None, skip_valuation=False, force_full_scan=False):
    """
    执行字段补全，确保当天所有数据完整。

    参数:
        db: StockDailyDB 实例
        code: 指定股票代码（可选）
        stock_list: [(code, market), ...] 股票列表（可选，用于估值补全）
        skip_valuation: True 则跳过估值补全（估值已在插入时由 ValuationFetcher 写入）
        force_full_scan: True 则忽略 code/stock_list，强制全量扫描所有缺失估值的股票
    """
    import traceback as _tb
    # Step 1: SQL 补全 change_percent / change_amount / pre_close
    # 传入 stock_list 限制范围，避免全表扫描
    n1 = db.complete_change_fields(code=code, stock_list=stock_list)
    print(f"  [补全] change 字段: {n1:,} 条")

    n2 = 0

    # Step 2: Baostock 补全 pe_ttm / pb（历史序列，不依赖腾讯快照）
    if not skip_valuation:
        if force_full_scan:
            # 强制全量扫描：忽略 code/stock_list，扫描所有缺失 PE/PB 的股票
            codes = None
        elif code:
            market = db.get_market_by_code(code)
            codes = [(code, market)] if market else None
        elif stock_list:
            # stock_list 可能是 3-tuples (code, name, market) 或 2-tuples (code, market)
            # 统一转换为 2-tuples (code, market)
            codes = [(s[0], s[2] if len(s) > 2 else s[1]) for s in stock_list]
        else:
            codes = None  # fix_valuation_by_qq 内部会调用 get_codes_with_missing_pe_pb

        try:
            n2 = fix_valuation_by_qq(db, codes=codes)
            print(f"  [补全] 估值字段 (Baostock 历史 pe_ttm/pb): {n2:,} 条")
        except Exception as e:
            print(f"  [WARN] fix_valuation_by_qq 异常: {e}")
            _tb.print_exc()

    return n1 + n2

