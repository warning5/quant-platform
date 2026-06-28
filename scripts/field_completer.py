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
import os as _os
import pymysql as _pymysql
import json


# ─── 腾讯行情接口配置 ──────────────────────────────────────────
QQ_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://gu.qq.com/",
}

MARKET_PREFIX = {"SH": "sh", "SZ": "sz", "BJ": "bj"}


import baostock as _bs
_HAS_BAOSTOCK = True

# ─── 调试日志 ────────────────────────────────────────────────
import os as _os
_DEBUG_LOG = _os.path.join(_os.path.dirname(__file__), "debug_valuation.log")

def _dlog(msg):
    ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    line = f"[{ts}] {msg}"
    # 只写文件，不打印到控制台（避免控制台被跳变日志刷屏）
    try:
        with open(_DEBUG_LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


# ─── 可补全股票缓存机制（方案C：每次查MySQL + 可选缓存）───
_COMPLETABLE_CACHE_FILE = _os.path.join(_os.path.dirname(__file__), "._pe_completable_cache.json")
_CACHE_HOURS = 24  # 缓存有效期（小时）


def get_all_completable_stocks(mysql_config=None, cache_hours=24, force_refresh=False):
    """
    从 MySQL 一次性获取所有可通过财务数据补全 PE 的股票代码集合。
    
    逻辑：
    1. 如果缓存文件存在且未过期 → 直接返回缓存
    2. 否则 → 查询 MySQL（单次批量查询），更新缓存
    
    参数:
        mysql_config: MySQL 配置（None 则用默认）
        cache_hours: 缓存有效期（小时），0=不缓存
        force_refresh: True=强制刷新缓存
    
    返回:
        set: 可补全 PE 的股票代码集合
    """
    if mysql_config is None:
        import os
        mysql_config = {
            "host": os.environ.get("MYSQL_HOST", "localhost"),
            "port": int(os.environ.get("MYSQL_PORT", "3306")),
            "user": os.environ.get("MYSQL_USER", "root"),
            "password": os.environ.get("MYSQL_PASSWORD", ""),
            "database": os.environ.get("MYSQL_DATABASE", "stock"),
            "charset": "utf8mb4"
        }
    
    # 1. 尝试从缓存加载
    if not force_refresh and cache_hours > 0 and _os.path.exists(_COMPLETABLE_CACHE_FILE):
        try:
            with open(_COMPLETABLE_CACHE_FILE, "r", encoding="utf-8") as f:
                cache = json.load(f)
            cache_time = datetime.datetime.fromisoformat(cache["timestamp"])
            if datetime.datetime.now() - cache_time < datetime.timedelta(hours=cache_hours):
                completable = set(cache["codes"])
                age_hours = int((datetime.datetime.now() - cache_time).total_seconds() // 3600)
                print(f"[缓存] 使用缓存: {len(completable)} 只可补全股票 (age={age_hours}h)")
                return completable
        except Exception as e:
            print(f"[缓存] 读取失败，重新查询: {e}")
    
    # 2. 查询 MySQL（单次批量查询，获取有>=4季度正EPS的股票）
    print(f"[MySQL] 查询可补全股票（有>=4季度正EPS）...")
    try:
        conn = _pymysql.connect(**mysql_config)
        with conn.cursor() as cursor:
            # 按股票分组，统计正EPS的季度数
            cursor.execute("""
                SELECT code, COUNT(*) as cnt
                FROM stock_financial_indicator
                WHERE eps_basic IS NOT NULL AND eps_basic > 0
                GROUP BY code
                HAVING cnt >= 4
            """)
            rows = cursor.fetchall()
            completable = set(row[0] for row in rows)
            
            print(f"[MySQL] 找到 {len(completable)} 只可补全股票")
            
            # 3. 写入缓存
            if cache_hours > 0:
                try:
                    cache = {
                        "timestamp": datetime.datetime.now().isoformat(),
                        "codes": sorted(completable)
                    }
                    with open(_COMPLETABLE_CACHE_FILE, "w", encoding="utf-8") as f:
                        json.dump(cache, f, ensure_ascii=False, indent=2)
                    print(f"[缓存] 已更新: {_COMPLETABLE_CACHE_FILE}")
                except Exception as e:
                    print(f"[缓存] 写入失败: {e}")
            
            return completable
    except Exception as e:
        print(f"[MySQL] 查询失败: {e}，返回空集合")
        return set()
    finally:
        try:
            conn.close()
        except:
            pass


def filter_completable_stocks(codes_with_market, mysql_config=None, cache_hours=24):
    """
    过滤出可补全 PE 的股票（方案C：每次运行都查 MySQL，带缓存）。
    
    参数:
        codes_with_market: [(code, market), ...]
        mysql_config: MySQL 配置
        cache_hours: 缓存有效期（小时），0=不缓存，24=默认
    
    返回:
        filtered_codes: 可补全的股票列表 [(code, market), ...]
    """
    # 一次性获取所有可补全股票（带缓存）
    completable_set = get_all_completable_stocks(mysql_config, cache_hours)
    
    if not completable_set:
        print(f"[过滤] 无可补全股票（MySQL查询失败或无数据）")
        return []
    
    # 过滤：只保留在可补全集合中的股票
    filtered = [(c, m) for c, m in codes_with_market if c in completable_set]
    skipped = len(codes_with_market) - len(filtered)
    
    if skipped > 0:
        skipped_codes = [c for c, m in codes_with_market if c not in completable_set]
        print(f"[过滤] 跳过 {skipped} 只不可补全股票（无>=4季度正EPS）")
        print(f"  示例: {skipped_codes[:5]}")
    
    print(f"[过滤] 可补全: {len(filtered)} 只，不可补全: {skipped} 只")
    return filtered


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


def fix_valuation_by_qq(db, codes=None, batch_size=200, delay=0.1, max_workers=30, target_dates=None, max_stocks=None, scan_mode="full"):
    """
    通过 Baostock 历史接口补全 pe_ttm / pb（按日历史序列，不依赖腾讯快照）。
    
    此函数只负责 PE / PB 的历史补全。
    
    参数:
        db: StockDailyDB 实例
        codes: [(code, market), ...] 列表。None = 自动查询缺失 PE/PB 的股票
        batch_size: 每批处理的股票数
        target_dates: 仅处理指定日期列表（如 ["2026-06-26"]），
                    为 None 时处理所有缺失日期（全量补缺模式）
        max_stocks: 最多处理 N 只股票（优先处理最近缺口的），None 则不限制。
                    用于日常渐进补缺：每天补 100 只，一个月补完所有历史缺口
        scan_mode: 扫描模式（仅当 codes=None 时生效）
          - "full": 扫描所有有任意一天缺失 PE/PB 的股票（用于日线更新补当天）
          - "never_processed": 只扫描从未被处理过的股票（所有日期 PE+PB 都 NULL）
            用于渐进历史补缺，避免重复处理已有部分 PE/PB 值的股票
    返回: 补全的记录数
    """
    latest_date = db.get_latest_date()
    print(f"[fix_valuation_by_qq] ENTRY | backend={db.backend} latest_date={latest_date}")

    if not latest_date:
        print("[fix_valuation_by_qq] ABORT: latest_date is None")
        return 0

    # ── 确定待补全的股票列表 ─────────────────────────────────────
    if codes is None:
        codes = db.get_all_codes_with_missing_pe_pb(scan_mode=scan_mode)
        mode_desc = "从未被处理" if scan_mode == "never_processed" else "全量扫描"
        print(f"[fix_valuation_by_qq] auto mode ({mode_desc}): {len(codes)} 只股票缺失 PE/PB")
    else:
        print(f"[fix_valuation_by_qq] manual mode: {len(codes)} 只股票")

    if not codes:
        print("[fix_valuation_by_qq] 无缺失 PE/PB 的股票")
        return 0

    codes = list(dict.fromkeys(codes))
    
    # ── 过滤不可补全的股票（无正EPS数据）──
    if codes:
        codes = filter_completable_stocks(codes)
        if not codes:
            print("[fix_valuation_by_qq] 所有股票都不可补全，退出")
            return 0

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
    # ── 如果指定了 target_dates，只处理那些日期 ────────────────────────────
    if target_dates:
        # target_dates: ["2026-06-26", ...]
        # 只保留 date_range_map 中与 target_dates 重叠的股票的日期范围
        # 并将 end_date 截断到 target_dates 的最大日期
        td_set = set(target_dates)
        td_min = min(target_dates)
        td_max = max(target_dates)
        # 计算上下文起始日（目标日前5天，给跳变检测留上下文）
        _td_min_dt = datetime.datetime.strptime(td_min, "%Y-%m-%d")
        _ctx_start = (_td_min_dt - datetime.timedelta(days=5)).strftime("%Y-%m-%d")
        filtered_range = {}
        for code, (mn, mx) in date_range_map.items():
            # 该股票的缺失范围与 target_dates 有重叠
            if mn <= td_max and mx >= td_min:
                filtered_range[code] = (max(mn, _ctx_start), td_max)
        date_range_map = filtered_range
        print(f"  [target_dates] 限定处理日期: {td_min} ~ {td_max}，涉及 {len(date_range_map)} 只股票")

    codes_with_range = [(c, m) for c, m in codes]
    codes_need_fetch = [(c, m) for c, m in codes if c in date_range_map]
    print(f"  {len(codes_need_fetch)} 只有缺失日期（需要抓取）")

    # ── max_stocks：日常渐进补缺，优先处理最近缺口 ────────────────────
    if max_stocks and len(codes_need_fetch) > max_stocks:
        # 按 gap 最近日期（max_date）降序排序，优先补最近的缺口
        codes_need_fetch.sort(key=lambda cm: date_range_map[cm[0]][1], reverse=True)
        codes_need_fetch = codes_need_fetch[:max_stocks]
        print(f"  [max_stocks] 截断至 {len(codes_need_fetch)} 只（优先最近缺口）")

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
            BS_MIN_DATE = "2005-01-01"
            # Baostock 要求 YYYY-MM-DD 格式
            if "-" not in mn:
                mn = f"{mn[:4]}-{mn[4:6]}-{mn[6:8]}"
            if "-" not in mx:
                mx = f"{mx[:4]}-{mx[4:6]}-{mx[6:8]}"
            if mn < BS_MIN_DATE:
                mn = BS_MIN_DATE

            raw_rows = []
            attempt = 0
            last_error = None
            while attempt < 3:
                try:
                    rs = _bs.query_history_k_data_plus(
                        bs_code,
                        "date,close,peTTM,pbMRQ",
                        start_date=mn,
                        end_date=mx,
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
                    # NoneType 错误（Baostock 返回 None，服务端问题），不重试
                    if "NoneType" in last_error or "has no attribute" in last_error:
                        print(f"    [WARN] {code} Baostock 查询失败({attempt+1}次): {last_error[:60]}")
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

            # P1-3.1: 合理性校验 — 跳变检测（PE 跳变 >100% 则丢弃异常值）
            if date_map:
                validated_map = {}
                sorted_dates = sorted(date_map.keys())
                prev_pe, prev_pb = None, None
                for i, d in enumerate(sorted_dates):
                    pe, pb = date_map[d]
                    keep = True
                    # PE 跳变检测：与前一日对比
                    if pe is not None and prev_pe is not None and prev_pe > 0:
                        jump = abs(pe - prev_pe) / prev_pe
                        if jump > 1.0:  # 跳变 >100%
                            # 看哪个值更可疑：PE<0.5 或 PE>50000 可能是坏值
                            if pe < 0.5 or pe > 50000:
                                pe = None  # 丢弃当前异常值
                            elif prev_pe < 0.5 or prev_pe > 50000:
                                # 前值可疑，但前值可能已写入，此处无法回滚，记录日志
                                _dlog(f"  [VALIDATE] {code} {d} PE跳变: {prev_pe:.2f}→{pe:.2f} (前值可能异常)")
                            elif jump > 2.0:
                                # 跳变 >200%：可能是财报公布导致TTM EPS剧烈变化（如600617 PE 84→782），
                                # 保留后值但标注警告，由数据质量监控后续验证多日连续性
                                _dlog(f"  [VALIDATE] {code} {d} PE剧烈跳变({jump*100:.0f}%): {prev_pe:.2f}→{pe:.2f} (保留，可能为财报驱动)")
                            else:
                                # 跳变 100%~200%：保留后值
                                _dlog(f"  [VALIDATE] {code} {d} PE跳变({jump*100:.0f}%): {prev_pe:.2f}→{pe:.2f} (保留后值)")
                    # PB 跳变检测
                    if pb is not None and prev_pb is not None and prev_pb > 0:
                        jump = abs(pb - prev_pb) / prev_pb
                        if jump > 1.0:
                            if pb < 0.01 or pb > 10000:
                                pb = None
                            elif prev_pb < 0.01 or prev_pb > 10000:
                                _dlog(f"  [VALIDATE] {code} {d} PB跳变: {prev_pb:.2f}→{pb:.2f} (前值可能异常)")
                            elif jump > 2.0:
                                # 跳变 >200%：可能是资产重估/并购导致，保留但标注警告
                                _dlog(f"  [VALIDATE] {code} {d} PB剧烈跳变({jump*100:.0f}%): {prev_pb:.2f}→{pb:.2f} (保留)")
                            else:
                                _dlog(f"  [VALIDATE] {code} {d} PB跳变({jump*100:.0f}%): {prev_pb:.2f}→{pb:.2f} (保留后值)")
                    # 绝对值合理性检查
                    if pe is not None and (pe < 0.5 or pe > 50000):
                        _dlog(f"  [VALIDATE] {code} {d} PE异常范围: {pe:.2f}，丢弃")
                        pe = None
                    if pb is not None and (pb < 0.01 or pb > 10000):
                        _dlog(f"  [VALIDATE] {code} {d} PB异常范围: {pb:.2f}，丢弃")
                        pb = None
                    # 更新 prev_pe/prev_pb（用于跳变检测上下文，即使当天不是目标日期）
                    if pe is not None:
                        prev_pe = pe
                    if pb is not None:
                        prev_pb = pb
                    # 只有以下情况才写入 DB：
                    # 1. target_dates=None（全量补缺模式）→ 所有日期都写
                    # 2. target_dates 包含当天 → 只写目标日期
                    in_target = target_dates is None or d in target_dates
                    if in_target and (pe is not None or pb is not None):
                        validated_map[d] = (pe, pb)

                batch_updates = []
                for d, (pe, pb) in validated_map.items():
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

    # ── 自动执行 OPTIMIZE TABLE FINAL 去重 ─────────────────────
    print(f"\n[OPTIMIZE] 开始合并去重（可能需要几分钟）...")
    try:
        import requests as _req
        ch_url = f"http://{CLICKHOUSE_CONFIG['host']}:{CLICKHOUSE_CONFIG['port']}/"
        ch_params = {
            'user': CLICKHOUSE_CONFIG['username'],
            'password': CLICKHOUSE_CONFIG['password'],
            'receive_timeout': 1800  # 30分钟超时
        }
        optimize_sql = "OPTIMIZE TABLE stock.stock_daily FINAL"
        resp = _req.post(ch_url, params=ch_params, data=optimize_sql, timeout=1800)
        if resp.status_code == 200:
            print(f"[OPTIMIZE] ✅ 合并去重完成")
        else:
            print(f"[OPTIMIZE] ⚠️ 合并失败: {resp.text}")
    except Exception as e:
        print(f"[OPTIMIZE] ⚠️ 合并失败: {e}")
        print(f"[OPTIMIZE] 请稍后手动执行: OPTIMIZE TABLE stock.stock_daily FINAL")

    return total_fixed



def cross_validate_ohlcv(db, sample_size=50, tolerance=0.02):
    """
    P1-4.1: 日线数据源交叉验证
    随机抽取沪深股票，对比 Baostock 和腾讯接口同日的 OHLCV 数据。
    偏差超过 tolerance（默认2%）的记录写入验证日志。

    参数:
        db: StockDailyDB 实例
        sample_size: 抽样股票数
        tolerance: 允许偏差（小数，0.02=2%）
    返回: (matched, diverged) 对比数和偏差数
    """
    import random
    import requests as _req

    print(f"\n[交叉验证] 抽样 {sample_size} 只沪深股票，对比 Baostock vs 腾讯 OHLCV...")

    # 获取最近交易日的沪深股票列表
    latest_date = db.get_latest_date()
    if not latest_date:
        print("[交叉验证] 无法获取最新交易日")
        return 0, 0

    codes = db.query(
        f"SELECT DISTINCT code FROM {db.CH_TABLE} "
        f"WHERE trade_date = %(date)s AND code REGEXP '^[0-9]{{6}}$' "
        f"AND code NOT LIKE '8%' AND code NOT LIKE '4%' ORDER BY rand() LIMIT %(n)s",
        {"date": latest_date, "n": sample_size}
    )
    codes = [r[0] for r in codes] if codes else []
    if len(codes) == 0:
        print("[交叉验证] 无可用抽样股票")
        return 0, 0

    print(f"  抽样 {len(codes)} 只: {codes[:5]}...")

    # 获取 Baostock 数据（已存在 CH 中）
    bs_rows = db.query(
        f"SELECT code, trade_date, open, high, low, close, volume FROM {db.CH_TABLE} "
        f"WHERE code IN ({','.join(['%s'] * len(codes))}) AND trade_date = %(date)s",
        {"date": latest_date, "codes": tuple(codes)}
    ) if db.backend == "mysql" else None

    if db.backend == "clickhouse":
        # ClickHouse 不支持 parameterized IN
        code_list_str = ",".join(f"'{c}'" for c in codes)
        bs_rows = db.query(
            f"SELECT code, trade_date, open, high, low, close, volume FROM {db.CH_TABLE} "
            f"WHERE code IN ({code_list_str}) AND trade_date = %(date)s",
            {"date": latest_date}
        )

    bs_map = {}
    for row in (bs_rows or []):
        c, d, o, h, l, cl, v = row
        bs_map[c] = {"open": float(o) if o else None, "high": float(h) if h else None,
                      "low": float(l) if l else None, "close": float(cl) if cl else None,
                      "volume": float(v) if v else None}

    # 从腾讯接口拉同一批股票（批量请求）
    matched, diverged = 0, 0
    for code in bs_map:
        try:
            market = "sh" if code.startswith(("6", "9")) else "sz"
            url = f"http://qt.gtimg.cn/q={market}{code}"
            resp = _req.get(url, headers=QQ_HEADERS, timeout=5)
            resp.encoding = "gbk"
            text = resp.text
            if "~" not in text:
                continue
            parts = text.split("~")
            if len(parts) < 50:
                continue
            # 腾讯快照字段: [1]name [3]current [4]昨收 [5]open [33]high [34]low [6]volume
            qq_open = _parse_val(parts[5]) if len(parts) > 5 else None
            qq_high = _parse_val(parts[33]) if len(parts) > 33 else None
            qq_low = _parse_val(parts[34]) if len(parts) > 34 else None
            qq_close = _parse_val(parts[3])
            qq_vol = _parse_val(parts[6])

            bs = bs_map[code]
            # 比较 OHLCV（close 优先，然后 high，然后 open）
            for field, bs_val in [("close", bs["close"]), ("high", bs["high"]),
                                   ("low", bs["low"]), ("open", bs["open"])]:
                qq_val = locals().get(f"qq_{field}")
                if bs_val and qq_val and bs_val != 0:
                    dev = abs(qq_val - bs_val) / bs_val
                    if dev > tolerance:
                        _dlog(f"  [CROSS] {code} {latest_date} {field}: "
                              f"BS={bs_val:.2f} QQ={qq_val:.2f} dev={dev*100:.1f}%")
                        diverged += 1
                    matched += 1
        except Exception as e:
            _dlog(f"  [CROSS] {code} 腾讯请求失败: {e}")

    print(f"  交叉验证完成: {len(codes)} 只股票, {matched} 次比较, {diverged} 次偏差>{tolerance*100:.0f}%")
    return matched, diverged



def complete_fields(db, code=None, stock_list=None, skip_valuation=False, force_full_scan=False, target_dates=None, scan_mode="full"):
    """
    执行字段补全，确保当天所有数据完整。

    参数:
        db: StockDailyDB 实例
        code: 指定股票代码（可选）
        stock_list: [(code, market), ...] 股票列表（可选，用于估值补全）
        skip_valuation: True 则跳过估值补全（估值已在插入时由 ValuationFetcher 写入）
        force_full_scan: 已废弃，保留参数兼容性但不影响逻辑
        target_dates: 仅处理指定日期列表（如 ["2026-06-26"]），
                    为 None 时处理所有缺失日期（全量补缺模式）
        scan_mode: "full" 扫描所有有NULL的股票（默认），"never_processed" 只扫描从未被处理的
    """
    import traceback as _tb
    # Step1: SQL 补全 change_percent / change_amount / pre_close
    # 传入 stock_list 限制范围，避免全表扫描
    n1 = db.complete_change_fields(code=code, stock_list=stock_list)
    print(f"  [补全] change 字段: {n1:,} 条")

    n2 = 0

    # Step2: Baostock 补全 pe_ttm / pb（历史序列，不依赖腾讯快照）
    if not skip_valuation:
        if code:
            market = db.get_market_by_code(code)
            codes = [(code, market)] if market else None
        elif stock_list:
            # stock_list 可能是 3-tuples (code, name, market) 或 2-tuples (code, market)
            # 统一转换为 2-tuples (code, market)
            codes = [(s[0], s[2] if len(s) > 2 else s[1]) for s in stock_list]
        else:
            codes = None  # fix_valuation_by_qq 内部会调用 get_codes_with_missing_pe_pb

        try:
            n2 = fix_valuation_by_qq(db, codes=codes, target_dates=target_dates, scan_mode=scan_mode)
            print(f"  [补全] 估值字段 (Baostock 历史 pe_ttm/pb): {n2:,} 条")
        except Exception as e:
            print(f"  [WARN] fix_valuation_by_qq 异常: {e}")
            _tb.print_exc()

    return n1 + n2

# ─── 直接运行入口 ─────────────────────────────────────────────
if __name__ == "__main__":
    import sys
    import argparse
    
    parser = argparse.ArgumentParser(description="字段补全工具（PE/PB缺失数据补全）")
    parser.add_argument("--phase", type=int, choices=[1, 2], default=1,
                        help="补全阶段：1=Baostock历史数据，2=财务数据计算（默认：1）")
    parser.add_argument("--max-stocks", type=int, default=None,
                        help="最大处理股票数（测试用）")
    args = parser.parse_args()
    
    print("=" * 70)
    print("字段补全工具（PE/PB 缺失数据补全）")
    print("=" * 70)
    
    if args.phase == 1:
        print("\n[阶段 1] Baostock 历史数据补全")
        total = fix_valuation_by_qq(max_stocks=args.max_stocks)
        print(f"\n✅ 阶段 1 完成：补全 {total} 条数据")
        print(f"   OPTIMIZE 已在函数末尾自动执行")
    else:
        print("\n[阶段 2] 财务数据计算 PE（未完成）")
        print("   请先完成阶段 1")
        sys.exit(1)
