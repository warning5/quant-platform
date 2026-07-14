"""
update_research_report.py
获取并存储个股研报数据（来源：akshare → 东方财富 eastmoney）

数据表：MySQL stock.stock_research_report
数据来源：ak.stock_research_report_em(symbol=code)

用法：
  python update_research_report.py              # 增量更新有财务数据的股票
  python update_research_report.py 000001      # 更新单只股票
  python update_research_report.py --all       # 强制重刷（删除旧数据后重写）
  python update_research_report.py --check     # 仅打印数据概览
  python update_research_report.py --start-date 2026-04-01 --end-date 2026-05-03

依赖：
  pip install akshare pymysql
  akshare >= 1.18.40
"""
import sys
import os
import time
import json
import pymysql
import warnings
import akshare as ak
from datetime import datetime, date, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed

warnings.filterwarnings('ignore')

# ── 数据库：MySQL ──────────────────────────────────────────────
from db_config import MYSQL_CONFIG


def get_mysql_conn():
    cfg = MYSQL_CONFIG.copy()
    cfg["charset"] = "utf8mb4"
    cfg["autocommit"] = True
    return pymysql.connect(**cfg)


def upsert_reports_batch(mysql_conn, rows):
    """
    批量写入研报数据到 MySQL。
    使用 REPLACE INTO：已存在则覆盖（按主键/唯一键判断），不存在则插入。
    """
    if not rows:
        return 0

    sql = """
    REPLACE INTO stock_research_report
        (code, name, report_title, rating, institution,
         eps_forecast, industry, report_date, pdf_url)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
    """
    params = [
        (
            r["code"], r["name"], r["report_title"], r["rating"], r["institution"],
            r["eps_forecast"], r["industry"],
            r["report_date"], r["pdf_url"],
        )
        for r in rows
    ]

    cursor = mysql_conn.cursor()
    try:
        cursor.executemany(sql, params)
        affected = cursor.rowcount
        mysql_conn.commit()
    except Exception as e:
        # 如果 REPLACE 失败，逐条重试（排查单条坏数据）
        print(f"  [WARN] 批量写入失败: {e}，逐条重试...")
        affected = 0
        for p in params:
            try:
                cursor.execute(sql, p)
                affected += max(0, cursor.rowcount)
            except Exception:
                pass
        mysql_conn.commit()
    cursor.close()
    return affected


def parse_float(val):
    """将值转换为 float 或 None"""
    if val is None or str(val).strip() == "" or str(val).lower() == "nan":
        return None
    try:
        return float(str(val).strip())
    except ValueError:
        return None


def fetch_stock_reports(code):
    """
    调用 akshare 获取单只股票研报列表。
    返回 list of dict（已解析字段）。
    """
    try:
        df = ak.stock_research_report_em(symbol=code)
    except Exception as e:
        return [], str(e)

    if df is None or df.empty:
        return [], None

    results = []
    for _, row in df.iterrows():
        report_title = (row.get("报告名称") or "").strip()
        if not report_title:
            continue

        institution = (row.get("机构") or "").strip()
        rating = (row.get("东财评级") or "").strip()

        # 解析报告日期
        report_date = None
        rd = row.get("日期")
        if rd and str(rd).strip():
            try:
                report_date = datetime.strptime(str(rd)[:10], "%Y-%m-%d").date()
            except Exception:
                pass

        pdf_url = (row.get("报告PDF链接") or "").strip()
        if pdf_url and not pdf_url.startswith("http"):
            pdf_url = ""

        # 解析盈利预测（动态年份，不限2026-2028）
        eps_forecast = {}
        for yr in range(2025, 2035):
            eps_key = f"{yr}-盈利预测-收益"
            pe_key = f"{yr}-盈利预测-市盈率"
            eps_val = parse_float(row.get(eps_key))
            pe_val = parse_float(row.get(pe_key))
            if eps_val is not None or pe_val is not None:
                entry = {}
                if eps_val is not None:
                    entry["eps"] = eps_val
                if pe_val is not None:
                    entry["pe"] = pe_val
                if entry:
                    eps_forecast[str(yr)] = entry

        results.append({
            "code": code,
            "name": (row.get("股票简称") or "").strip(),
            "report_title": report_title,
            "rating": rating,
            "institution": institution,
            "eps_forecast": json.dumps(eps_forecast, ensure_ascii=False) if eps_forecast else None,
            "industry": (row.get("行业") or "").strip(),
            "report_date": report_date,
            "pdf_url": pdf_url,
        })

    return results, None


def get_stock_list(mysql_conn, force_all=False, skip_recent_days=0):
    """
    获取需要更新的股票列表
    - force_all=True: 返回全部股票
    - skip_recent_days>0: 只返回最近 N 天没有研报记录的股票（增量模式）
                        分两步查，避免 stock_info.code 和 stock_research_report.code
                        的 collation 不同导致 Illegal mix of collations 错误
    """
    cursor = mysql_conn.cursor()
    if force_all:
        cursor.execute("SELECT code, name FROM stock_info ORDER BY code")
        rows = cursor.fetchall()
        cursor.close()
        return rows

    if skip_recent_days > 0:
        # 增量模式：两步查，避开 SQL collation 冲突
        cutoff = (date.today() - timedelta(days=skip_recent_days)).isoformat()

        # 第1步：有财务数据的全部股票
        cursor.execute("""
            SELECT s.code, s.name
            FROM stock_info s
            WHERE EXISTS (SELECT 1 FROM stock_financial_indicator f WHERE f.code = s.code)
            ORDER BY s.code
        """)
        all_stocks = cursor.fetchall()

        # 第2步：最近 N 天有研报的 code 集合（单独查，不做跨表比较）
        cursor.execute(
            "SELECT DISTINCT code FROM stock_research_report WHERE report_date >= %s",
            (cutoff,)
        )
        recent_codes = {r[0] for r in cursor.fetchall()}
        cursor.close()

        # 第3步：Python 层过滤
        return [s for s in all_stocks if s[0] not in recent_codes]

    # 全量模式（有财务数据的股票）
    cursor.execute("""
        SELECT DISTINCT s.code, s.name
        FROM stock_info s
        WHERE EXISTS (SELECT 1 FROM stock_financial_indicator f WHERE f.code = s.code)
        ORDER BY s.code
    """)
    rows = cursor.fetchall()
    cursor.close()
    return rows


def print_overview(mysql_conn):
    """打印研报数据概览（等效 --check）"""
    cursor = mysql_conn.cursor()

    cursor.execute("SELECT COUNT(*) FROM stock_research_report")
    total = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(DISTINCT code) FROM stock_research_report")
    stocks = cursor.fetchone()[0]

    cursor.execute("SELECT MIN(report_date), MAX(report_date) FROM stock_research_report")
    dr = cursor.fetchone()

    cursor.execute("""
        SELECT COUNT(*) FROM stock_research_report
        WHERE eps_forecast IS NULL OR eps_forecast = '' OR eps_forecast = '{}'
    """)
    null_eps = cursor.fetchone()[0]

    cursor.execute("""
        SELECT institution, COUNT(*) AS cnt
        FROM stock_research_report
        WHERE institution IS NOT NULL AND institution != ''
        GROUP BY institution ORDER BY cnt DESC LIMIT 10
    """)
    top_inst = cursor.fetchall()

    cursor.execute("""
        SELECT rating, COUNT(*) AS cnt
        FROM stock_research_report
        WHERE rating IS NOT NULL AND rating != ''
        GROUP BY rating ORDER BY cnt DESC
    """)
    rating_dist = cursor.fetchall()

    cursor.close()

    print(f"\n{'='*52}")
    print(f"  研报数据概览")
    print(f"{'='*52}")
    print(f"  总记录数    : {total:,}")
    print(f"  覆盖股票数  : {stocks:,}")
    if null_eps > 0:
        print(f"  ⚠ 缺EPS/PE  : {null_eps:,} 条（可用 --fix-null-eps 补采）")
    if dr and dr[0]:
        print(f"  日期范围    : {dr[0]} ~ {dr[1]}")
    print()
    print("  机构分布 TOP10:")
    for inst, cnt in top_inst:
        print(f"    {inst[:24]:<24} {cnt:>6} 条")
    print()
    print("  评级分布:")
    for rt, cnt in rating_dist:
        print(f"    {rt:<8} {cnt:>6} 条")
    print(f"{'='*52}\n")


def get_stocks_with_null_eps(mysql_conn):
    """获取 eps_forecast 为空的股票列表（用于补采）"""
    cursor = mysql_conn.cursor()
    cursor.execute("""
        SELECT DISTINCT r.code, s.name
        FROM stock_research_report r
        LEFT JOIN stock_info s ON r.code = s.code
        WHERE r.eps_forecast IS NULL OR r.eps_forecast = '' OR r.eps_forecast = '{}'
    """)
    rows = cursor.fetchall()
    cursor.close()
    return rows


def run_update(mysql_conn, stocks, batch_size=20, sleep_sec=1.0,
              start_date=None, end_date=None, force_all=False,
              max_workers=10, skip_recent_days=7):
    """
    主更新循环（多线程版）
    - max_workers: 并发线程数（默认10，避免被封）
    - skip_recent_days: 增量跳过天数（默认7天，与 get_stock_list 配合使用）
    """
    total_new = 0
    total_err = 0
    start_time = datetime.now()

    # 日期过滤参数解析
    dt_start = None
    dt_end = None
    if start_date:
        try:
            dt_start = datetime.strptime(start_date, "%Y-%m-%d").date()
        except ValueError:
            print(f"[WARN] --start-date 格式无效: {start_date}")
    if end_date:
        try:
            dt_end = datetime.strptime(end_date, "%Y-%m-%d").date()
        except ValueError:
            print(f"[WARN] --end-date 格式无效: {end_date}")

    # 强制重刷：先删除日期范围内的已有记录
    if force_all and (dt_start or dt_end):
        where = "WHERE 1=1"
        params = []
        if dt_start:
            where += " AND report_date >= %s"
            params.append(str(dt_start))
        if dt_end:
            where += " AND report_date <= %s"
            params.append(str(dt_end))
        cur = mysql_conn.cursor()
        cur.execute(f"DELETE FROM stock_research_report {where}", params)
        deleted = cur.rowcount
        cur.close()
        mysql_conn.commit()
        print(f"[FORCE] 已删除日期范围内已有记录: {deleted} 条")

    date_label = ""
    if dt_start or dt_end:
        date_label = f"（日期范围: {dt_start or '不限'} ~ {dt_end or '不限'}）"

    print(f"开始时间 : {start_time.strftime('%H:%M:%S')}")
    print(f"目标股票 : {len(stocks)} 只 {date_label}")
    print(f"并发线程 : {max_workers}")
    print(f"{'='*52}")

    # Worker 函数：处理单只股票
    def worker(item):
        code, name = item
        try:
            results, err = fetch_stock_reports(code)
            if err:
                return (code, name, 0, err[:60])
            if not results:
                return (code, name, 0, "无研报")

            # 按日期范围过滤
            if dt_start or dt_end:
                filtered = [
                    r for r in results
                    if r["report_date"] is not None
                    and (not dt_start or r["report_date"] >= dt_start)
                    and (not dt_end or r["report_date"] <= dt_end)
                ]
                skipped = len(results) - len(filtered)
                results = filtered
                if skipped > 0:
                    pass  # 日期过滤跳过不打印，避免刷屏

            if not results:
                return (code, name, 0, "无匹配日期")

            # 每个线程独立连接写入
            conn = get_mysql_conn()
            n = upsert_reports_batch(conn, results)
            conn.close()
            return (code, name, n, None)
        except Exception as e:
            return (code, name, 0, str(e)[:60])

    # 并发执行
    completed = 0
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(worker, item): item for item in stocks}
        for future in as_completed(futures):
            code, name, n, err = future.result()
            completed += 1

            prefix = f"[{completed}/{len(stocks)}] {code} {name or ''}"
            if err:
                if "无研报" in err or "无匹配日期" in err:
                    pass  # 静默跳过
                else:
                    total_err += 1
                    print(f"  {prefix}  ✗  {err}")
            else:
                total_new += n
                if completed % 50 == 0 or completed == len(stocks):
                    print(f"  {prefix}  ✓  +{n} 条  (累计 {total_new})")

    end_time = datetime.now()
    duration = (end_time - start_time).total_seconds()

    print(f"\n{'='*52}")
    print(f"  完成时间 : {end_time.strftime('%H:%M:%S')}  (用时 {duration:.0f}s)")
    print(f"  新增/更新 : {total_new:,} 条")
    print(f"  错误次数 : {total_err}")
    print(f"{'='*52}\n")


def main():
    import argparse
    parser = argparse.ArgumentParser(description="研报数据更新（多线程优化版）")
    parser.add_argument("code", nargs="?", default=None, help="单只股票代码（如 000001）")
    parser.add_argument("--check", "-c", action="store_true", help="仅打印数据概览")
    parser.add_argument("--all", "-a", action="store_true", help="强制重刷全部股票（删除旧数据后重写）")
    parser.add_argument("--start-date", default=None, help="开始日期 YYYY-MM-DD（仅用于过滤研报日期）")
    parser.add_argument("--end-date", default=None, help="结束日期 YYYY-MM-DD")
    parser.add_argument("--max-workers", type=int, default=10, help="并发线程数（默认10）")
    parser.add_argument("--skip-recent-days", type=int, default=7, help="跳过最近N天已更新的股票（默认7天，0=不跳过）")
    parser.add_argument("--fix-null-eps", action="store_true", help="仅补采 eps_forecast 为空的股票（修复老数据）")
    args = parser.parse_args()

    check_only = args.check
    force_all = args.all
    fix_null_eps = args.fix_null_eps

    mysql_conn = get_mysql_conn()

    if check_only:
        print_overview(mysql_conn)
        mysql_conn.close()
        return

    # 获取股票列表
    skip_days = args.skip_recent_days if not force_all else 0
    if args.code:
        cursor = mysql_conn.cursor()
        cursor.execute("SELECT code, name FROM stock_info WHERE code=%s", (args.code,))
        stocks = cursor.fetchall()
        cursor.close()
        if not stocks:
            print(f"未找到股票: {args.code}")
            mysql_conn.close()
            return
        print(f"模式: 单只股票 {args.code}")
    elif fix_null_eps:
        stocks = get_stocks_with_null_eps(mysql_conn)
        print(f"模式: 补采 eps_forecast 为空的股票，共 {len(stocks)} 只")
        if not stocks:
            print("没有 eps_forecast 为空的记录，退出。")
            mysql_conn.close()
            return
    else:
        stocks = get_stock_list(mysql_conn, force_all=force_all, skip_recent_days=skip_days)
        mode_label = '全部股票' if force_all else f'有财务数据的股票（跳过{skip_days}天内已更新）'
        print(f"模式: {mode_label}，共 {len(stocks)} 只")
        if not stocks:
            print("无符合条件的股票，退出。")
            mysql_conn.close()
            return

    try:
        run_update(
            mysql_conn, stocks,
            start_date=args.start_date, end_date=args.end_date,
            force_all=force_all,
            max_workers=args.max_workers,
            skip_recent_days=skip_days,
        )
    except KeyboardInterrupt:
        print("\n用户中断")
    finally:
        mysql_conn.close()

    # 结束后打印概览
    mysql_conn = get_mysql_conn()
    print_overview(mysql_conn)
    mysql_conn.close()


if __name__ == "__main__":
    main()
