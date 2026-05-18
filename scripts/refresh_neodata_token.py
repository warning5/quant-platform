#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
refresh_neodata_token.py
========================
检查并自动刷新 NeoData token（12小时有效期）。

退出码约定:
  0 = token 有效，无需刷新
  42 = token 已过期，需要 AI 执行刷新
  43 = token 快过期（剩余 < 1小时），给出警告但继续

当退出码为 42 时，stdout 输出 JSON 供 AI 解析：
  {
    "status": "expired"|"warn",
    "remaining_seconds": <int>,
    "expired_at": "<iso时间>",
    "mcp_tool_call": {
      "tool": "connect_cloud_service",
      "description": "获取新的 NeoData tempToken"
    },
    "save_command": "python <query.py路径> --save-token <token>",
    "after_save_hint": "token 保存后可重新运行数据更新脚本"
  }

用法（由 AI 调用，无需手动执行）:
  python scripts/refresh_neodata_token.py
  echo $?           # 0=正常, 42=需刷新, 43=警告
  # 若退出码为 42，AI 自动执行刷新流程
"""

import sys
import json
import time
from pathlib import Path

TOKEN_FILE = Path.home() / ".workbuddy" / ".neodata_token"
TOKEN_TTL = 12 * 3600    # 12 小时（秒）
WARN_THRESHOLD = 1 * 3600 # 剩余 < 1小时 视为"快过期"
QUERY_PY = Path(__file__).parent / "neodata-financial-search" / "scripts" / "query.py"


def _guess_query_py():
    """尝试在多个可能路径中找到 query.py"""
    candidates = [
        Path.home() / ".workbuddy" / "plugins" / "marketplaces" / "cb_teams_marketplace" / "plugins" / "finance-data" / "skills" / "neodata-financial-search" / "scripts" / "query.py",
        Path.home() / ".codebuddy" / "plugins" / "marketplaces" / "cb_teams_marketplace" / "plugins" / "finance-data" / "skills" / "neodata-financial-search" / "scripts" / "query.py",
        Path(__file__).parent / ".." / ".workbuddy" / "plugins" / "marketplaces" / "cb_teams_marketplace" / "plugins" / "finance-data" / "skills" / "neodata-financial-search" / "scripts" / "query.py",
    ]
    for p in candidates:
        if p.exists():
            return str(p)
    return None


def read_token_info():
    """读取 token 文件，返回 (token, saved_at) 或 (None, None)"""
    if not TOKEN_FILE.exists():
        return None, None
    try:
        raw = TOKEN_FILE.read_text().strip()
        if not raw:
            return None, None
        data = json.loads(raw)
        token = data.get("token", "")
        saved_at = data.get("saved_at", 0)
        return token, saved_at
    except (json.JSONDecodeError, KeyError):
        return None, None


def check_token():
    """检查 token 状态，返回 (status, remaining_seconds, expired_at)

    status: "valid" | "warn" | "expired"
    """
    token, saved_at = read_token_info()
    if not token or not saved_at:
        return "expired", 0, None

    remaining = saved_at + TOKEN_TTL - time.time()
    expired_at = time.strftime("%Y-%m-%dT%H:%M:%S+08:00", time.localtime(saved_at + TOKEN_TTL))

    if remaining <= 0:
        return "expired", 0, expired_at
    elif remaining < WARN_THRESHOLD:
        return "warn", int(remaining), expired_at
    else:
        return "valid", int(remaining), expired_at


def main():
    status, remaining, expired_at = check_token()
    query_py = _guess_query_py() or "<query.py路径>"

    if status == "valid":
        print(f"[NeoData token] 状态正常，剩余 {remaining // 60} 分钟有效")
        sys.exit(0)

    elif status == "warn":
        print(f"[NeoData token] ⚠️ 即将过期，剩余 {remaining // 60} 分钟（{expired_at}）", file=sys.stderr)
        print(f"  建议：可在下次长任务前对 AI 说「刷新 NeoData token」预热", file=sys.stderr)
        sys.exit(43)

    else:  # expired
        print(f"[NeoData token] ❌ 已过期（超过 12 小时），无法继续使用", file=sys.stderr)
        print(f"  请让 AI 执行以下流程刷新 token:", file=sys.stderr)
        print(f"  1. 调用 connect_cloud_service 工具获取新 tempToken", file=sys.stderr)
        print(f"  2. 执行: python \"{query_py}\" --save-token <tempToken>", file=sys.stderr)

        # 输出 AI 友好 JSON（stdout，AI 可解析）
        ai_json = {
            "status": "expired",
            "remaining_seconds": remaining,
            "expired_at": expired_at,
            "mcp_tool_call": {
                "tool": "connect_cloud_service",
                "args": {},
                "description": "获取新的 NeoData tempToken（16小时有效期）"
            },
            "save_command_template": f'python \"{query_py}\" --save-token <tempToken>',
            "after_save_hint": "token 保存后可重新运行数据更新脚本"
        }
        print("\n__AI_JSON_START__", file=sys.stdout)
        print(json.dumps(ai_json, ensure_ascii=False, indent=2), file=sys.stdout)
        print("__AI_JSON_END__", file=sys.stdout)
        sys.exit(42)


if __name__ == "__main__":
    main()
