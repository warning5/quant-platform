#!/usr/bin/env python3
"""
补全 stock_sentiment_moneyflow 缺失的资金流向数据
通过 NeoData 查询历史资金流向，填补 2026-05-07 ~ 2026-05-17 的缺口
"""
import subprocess, json, re, time, sys, os

import pymysql
from pymysql import IntegrityError

# NeoData query.py 绝对路径
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
NEO_QUERY = r"C:\Users\warning5\.workbuddy\plugins\marketplaces\cb_teams_marketplace\plugins\finance-data\skills\neodata-financial-search\scripts\query.py"
PYTHON = sys.executable


def query_neodata(q):
    r = subprocess.run([PYTHON, NEO_QUERY, "--query", q], capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        return None
    try:
        return json.loads(r.stdout)
    except:
        return None


def extract(data):
    """从 NeoData 响应中提取资金流向"""
    result = {}
    for recall in data.get("data", {}).get("apiData", {}).get("apiRecall", []):
        if recall.get("type") != "历史资金流向":
            continue
        content = recall.get("content", "")
        # 匹配代码: "代码：002084.SZ" 或 "代码：600519.SH"
        code_m = re.search(r"代码[：:]\s*([0-9]{6}\.[A-Z]{2})", content)
        if not code_m:
            continue
        ts_code = code_m.group(1)  # e.g. "002084.SZ"
        code = ts_code.split(".")[0]

        # ⚠️ 过滤 B 股（900xxx/200xxx），避免 A/B 股数据混淆
        if code.startswith("900") or code.startswith("200"):
            continue

        for line in content.split("\n"):
            if not line.strip().startswith("|"):
                continue
            cols = [c.strip() for c in line.split("|")][1:]
            if len(cols) < 13:
                continue
            date_str = cols[0]
            if not re.match(r"^\d{8}$", date_str):
                continue
            trade_date = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:8]}"
            try:
                # NeoData 表格列顺序: 日期|中单净流入|主力流入|主力流入占比(%)|主力净流入|主力流出|主力流出占比(%)|散户流入|小单净流入|散户净流入|散户流出|超大单净流入|大单净流入
                result.setdefault(ts_code, {})[trade_date] = {
                    "net_main":     float(cols[4])  if cols[4]  else 0.0,   # 主力净流入
                    "net_main_pct": float(cols[3])  if cols[3]  else 0.0,   # 主力流入占比
                    "net_huge":     float(cols[11]) if cols[11] else 0.0,   # 超大单净流入
                    "net_big":      float(cols[12]) if cols[12] else 0.0,   # 大单净流入
                    "net_medium":   float(cols[1])  if cols[1]  else 0.0,   # 中单净流入
                    "net_small":    float(cols[8])  if cols[8]  else 0.0,   # 小单净流入
                    "close":        None,
                    "pct_change":   None,
                }
            except (ValueError, IndexError):
                continue
    return result


def get_missing_stocks():
    """获取缺失的股票列表"""
    conn = pymysql.connect(host="localhost", port=3306, user="root", password="123456", database="stock")
    cur = conn.cursor()
    cur.execute("SELECT DISTINCT ts_code FROM stock_sentiment_moneyflow WHERE trade_date > '2026-05-06'")
    existing = set(r[0] for r in cur.fetchall())
    cur.execute("SELECT code FROM stock_info")
    all_stocks = set(r[0] for r in cur.fetchall())
    cur.close()
    conn.close()
    # 排除北交所
    bex = {s for s in all_stocks if s.startswith("8") or s.startswith("4")}
    target = set()
    for s in all_stocks - bex:
        if len(s) == 6:
            prefix = ".SH" if s.startswith(("6", "5")) else ".SZ"
            target.add(s + prefix)
    return sorted(target - existing)


def get_name_map():
    conn = pymysql.connect(host="localhost", port=3306, user="root", password="123456", database="stock")
    cur = conn.cursor()
    cur.execute("SELECT code, name FROM stock_info")
    result = {r[0]: r[1] for r in cur.fetchall()}
    cur.close()
    conn.close()
    return result


def main():
    print("=" * 60)
    print("补全资金流向数据（NeoData）")
    print("=" * 60)

    missing = get_missing_stocks()
    print(f"缺失股票: {len(missing)} 只")

    if not missing:
        print("无需补全")
        return

    name_map = get_name_map()
    ok_count = 0
    fail_count = 0

    # 复用连接，每只股票查完即写
    conn = pymysql.connect(host="localhost", port=3306, user="root", password="123456", database="stock")

    for i, ts_code in enumerate(missing):
        code = ts_code.split(".")[0]
        name = name_map.get(code, code)
        label = f"{code}.{name}" if name != code else code

        print(f"[{i+1}/{len(missing)}] {label}...", end=" ", flush=True)
        data = query_neodata(f"{name}资金流向2026年5月7日到5月17日")
        if not data:
            print("✗ 查询失败")
            fail_count += 1
            time.sleep(0.2)
            continue

        parsed = extract(data)
        if not parsed:
            print("✗ 无数据")
            fail_count += 1
            time.sleep(0.2)
            continue

        # 查一只写一只
        cur = conn.cursor()
        saved = 0
        for t_code, dates in parsed.items():
            c = t_code.split(".")[0]
            for trade_date, vals in dates.items():
                try:
                    cur.execute("""
                        INSERT INTO stock_sentiment_moneyflow
                        (ts_code, trade_date, code, name, close, pct_change,
                         net_main, net_main_pct, net_huge, net_big, net_medium, net_small)
                        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        ON DUPLICATE KEY UPDATE
                        net_main=VALUES(net_main), net_main_pct=VALUES(net_main_pct),
                        net_huge=VALUES(net_huge), net_big=VALUES(net_big),
                        net_medium=VALUES(net_medium), net_small=VALUES(net_small)
                    """, (t_code, trade_date, c, name,
                          vals.get("close"), vals.get("pct_change"),
                          vals["net_main"], vals["net_main_pct"], vals["net_huge"],
                          vals["net_big"], vals["net_medium"], vals["net_small"]))
                    saved += 1
                except Exception:
                    pass
        conn.commit()
        cur.close()
        print(f"✓ {saved}条")
        ok_count += 1
        time.sleep(0.2)

    conn.close()
    print(f"\n完成: 成功 {ok_count} 只, 失败 {fail_count} 只")


if __name__ == "__main__":
    main()
