"""
westock_moneyflow.py - 用 westock-data asfund 替代 NeoData 获取资金流向
"""

import subprocess
import datetime
import re
import sys
from pathlib import Path


def _find_westock():
    """
    搜索 westock-data skill 的根目录（含 package.json 的目录）
    返回 (node_exe, script_path, cwd_dir) 或 (None, None, None)
    """
    wb_dir = Path.home() / ".workbuddy"

    # 找 westock-data 根目录（包含 package.json）
    skill_root = None
    for mp_name in ["marketplaces", "marketplaces/experts"]:
        mp_dir = wb_dir / "plugins" / mp_name
        if not mp_dir.exists():
            continue
        # 遍历所有含 package.json 的目录，找到 westock-data 的那个
        for pkg in mp_dir.rglob("package.json"):
            if pkg.parent.name == "westock-data":
                skill_root = pkg.parent
                break
        if skill_root:
            break

    if not skill_root:
        print("  [westock ERROR] 找不到 westock-data skill（无 package.json），请先安装 westock-data skill")
        return None, None, None

    script_path = skill_root / "scripts" / "index.js"
    if not script_path.exists():
        print(f"  [westock ERROR] 找不到 index.js: {script_path}")
        return None, None, None

    cwd_dir = str(skill_root)   # cwd = skill 根目录（有 package.json）
    script_str = str(script_path)

    # 找 node.exe（managed 版本优先）
    node_candidates = [
        wb_dir / "binaries" / "node" / "versions" / "22.22.2" / "node.exe",
        wb_dir / "binaries" / "node" / "versions" / "22.15.1" / "node.exe",
    ]
    node_exe = None
    for nc in node_candidates:
        if nc.exists():
            node_exe = str(nc)
            break
    if node_exe is None:
        node_exe = "node"
        print("  [westock WARN] 找不到 managed node.exe，尝试系统 node")

    print(f"  [westock DEBUG] node={node_exe}")
    print(f"  [westock DEBUG] script={script_str}")
    print(f"  [westock DEBUG] cwd={cwd_dir}")
    return node_exe, script_str, cwd_dir


def to_float(v) -> float:
    if v is None:
        return 0.0
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip().replace(",", "")
    if not s or s == "-":
        return 0.0
    try:
        return float(s)
    except ValueError:
        return 0.0


def query_westock(codes: list, start_date: str, end_date: str) -> str | None:
    """
    通过 westock-data asfund 查询资金流向，返回原始 stdout 文本
    codes: list of "sh600619" 风格代码（最多10只）
    start_date, end_date: "YYYY-MM-DD"
    返回: stdout 原始文本（含 markdown 表格），失败返回 None
    """
    if not codes:
        return None
    node_exe, script_path, cwd_dir = _find_westock()
    if node_exe is None:
        return None
    code_arg = ",".join(codes)
    try:
        r = subprocess.run(
            [node_exe, script_path, "asfund", code_arg,
             "--start", start_date, "--end", end_date],
            capture_output=True, timeout=180,
            cwd=cwd_dir,
        )
        if r.returncode != 0:
            stderr = r.stderr.decode("utf-8", errors="ignore")[:500]
            if stderr:
                print(f"  [westock stderr] {stderr}")
            return None
        return r.stdout.decode("utf-8", errors="ignore")
    except Exception as e:
        print(f"  [westock ERROR] {e}")
        return None


def extract_westock_moneyflow(md_text: str, ws_code_set: set = None) -> dict:
    """
    解析 westock-data asfund 的 markdown 表格输出
    返回 {ts_code: {trade_date_str: {close, net_main, net_main_pct, net_huge, net_big, net_medium, net_small}}}
    ws_code_set: 可选，仅保留指定代码（大写，如 {'SH600519'}）
    """
    if not md_text:
        return {}

    result = {}
    lines = md_text.split("\n")
    header_idx = None

    for i, line in enumerate(lines):
        if "MainNetFlow" in line and line.strip().startswith("|"):
            header_idx = i
            break
    if header_idx is None:
        return {}

    header_cols = [c.strip() for c in lines[header_idx].split("|")][1:]

    def _idx(col_name):
        try:
            return header_cols.index(col_name)
        except ValueError:
            return -1

    i_main_net  = _idx("MainNetFlow")
    i_close     = _idx("ClosePrice")
    i_block     = _idx("BlockNetFlow")
    i_jumbo     = _idx("JumboNetFlow")
    i_mid       = _idx("MidNetFlow")
    i_small     = _idx("SmallNetFlow")
    i_main_in   = _idx("MainInFlow")
    i_main_out  = _idx("MainOutFlow")
    i_date      = _idx("date")
    i_code      = _idx("code")

    if i_main_net < 0 or i_date < 0:
        return {}

    for line in lines[header_idx + 2:]:
        line = line.strip()
        if not line.startswith("|"):
            continue
        cols = [c.strip() for c in line.split("|")][1:]
        if len(cols) <= max(i_main_net, i_date, i_code):
            continue
        if not cols[i_date]:
            continue

        raw_code = cols[i_code]
        ts_code = raw_code.upper()
        if ws_code_set and ts_code not in ws_code_set:
            continue

        date_str = cols[i_date]
        m = re.match(r"^(\d{4})-(\d{2})-(\d{2})", date_str)
        if not m:
            continue
        try:
            td = datetime.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        except ValueError:
            continue

        trade_date = td.strftime("%Y%m%d")
        close     = to_float(cols[i_close])    if i_close >= 0    else 0.0
        main_net  = to_float(cols[i_main_net]) if i_main_net >= 0 else 0.0
        net_huge  = to_float(cols[i_jumbo])   if i_jumbo >= 0    else 0.0
        net_big   = to_float(cols[i_block])   if i_block >= 0    else 0.0
        net_mid   = to_float(cols[i_mid])     if i_mid >= 0      else 0.0
        net_small = to_float(cols[i_small])   if i_small >= 0   else 0.0

        main_in  = to_float(cols[i_main_in])  if i_main_in >= 0  else 0.0
        main_out = to_float(cols[i_main_out]) if i_main_out >= 0 else 0.0
        denom = main_in + main_out
        net_main_pct = round(main_net / denom * 100, 2) if denom > 0 else 0.0

        if ts_code not in result:
            result[ts_code] = {}
        result[ts_code][trade_date] = {
            "close":       close,
            "net_main":    main_net,
            "net_main_pct": net_main_pct,
            "net_huge":   net_huge,
            "net_big":    net_big,
            "net_medium": net_mid,
            "net_small":  net_small,
        }

    return result
