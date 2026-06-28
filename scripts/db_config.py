#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
db_config.py
============
统一数据库配置模块。

所有凭据通过环境变量读取，请勿将密码/Key 写入此文件。
本地开发请创建 .env 文件（已被 .gitignore 排除），参考 .env.example。

用法:
    from db_config import DB_BACKEND, get_db_params
"""
import os
import warnings
from pathlib import Path

# ─── 自动加载 .env 文件（纯 Python，零外部依赖）────────────────
_env_file = Path(__file__).resolve().parent.parent / ".env"
if _env_file.exists():
    _loaded = 0
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        # 跳过空行和注释
        if not _line or _line.startswith("#"):
            continue
        # 解析 KEY=VALUE（等号分隔，值可能包含 =）
        if "=" not in _line:
            continue
        _eq = _line.index("=")
        _key = _line[:_eq].strip()
        _val = _line[_eq + 1:].strip()
        # 去掉引号包裹（单引号或双引号）
        if len(_val) >= 2:
            if (_val[0] == '"' and _val[-1] == '"') or (_val[0] == "'" and _val[-1] == "'"):
                _val = _val[1:-1]
        # 不覆盖已有环境变量
        if _key and _key not in os.environ:
            os.environ[_key] = _val
            _loaded += 1

# ─── 后端选择 ─────────────────────────────────────────────────
# 优先级: 环境变量 > 默认值
DB_BACKEND = os.environ.get("DB_BACKEND", "clickhouse").lower()
assert DB_BACKEND in ("clickhouse", "mysql"), f"DB_BACKEND 必须是 clickhouse 或 mysql，当前: {DB_BACKEND}"


# ─── MySQL 配置（从环境变量读取，无硬编码密码）─────────────────
# 本地开发请在项目根目录创建 .env 文件（已有 .env.example 模板）
_mysql_password = os.environ.get("MYSQL_PASSWORD")
if not _mysql_password:
    raise ValueError("MYSQL_PASSWORD 未设置！请在 .env 文件中设置，或设置环境变量")

MYSQL_CONFIG = dict(
    host=os.environ.get("MYSQL_HOST", "localhost"),
    port=int(os.environ.get("MYSQL_PORT", "3306")),
    user=os.environ.get("MYSQL_USER", "root"),
    password=_mysql_password,
    database=os.environ.get("MYSQL_DATABASE", "stock"),
    charset="utf8mb4",
)


# ─── ClickHouse 配置（从环境变量读取，无硬编码密码）────────────
_clickhouse_password = os.environ.get("CLICKHOUSE_PASSWORD")
if not _clickhouse_password:
    raise ValueError("CLICKHOUSE_PASSWORD 未设置！请在 .env 文件中设置，或设置环境变量")

# 本地开发默认 host（仅 host 有默认值，密码必须显式设置）
# 注意：默认改为 172.19.72.140（内网 ClickHouse 地址），localhost 可能无法访问
_clickhouse_host = os.environ.get("CLICKHOUSE_HOST", "172.19.72.140")
if "CLICKHOUSE_HOST" not in os.environ and _clickhouse_host == "localhost":
    # 如果没显式设置，且是默认值，记录一下
    pass

CLICKHOUSE_CONFIG = dict(
    host=_clickhouse_host,
    port=int(os.environ.get("CLICKHOUSE_PORT", "8123")),
    username=os.environ.get("CLICKHOUSE_USER", "default"),
    password=_clickhouse_password,
    database=os.environ.get("CLICKHOUSE_DATABASE", "stock"),
)


# ─── stock_info 表始终从 MySQL 读取（ClickHouse 无此表）───────
STOCK_INFO_DB = MYSQL_CONFIG


# ─── 东方财富 API Token（公开接口 token，从环境变量读取）─────────
# 东方财富 push2/push2his 接口的 token 和 ut 参数
# 注意：这是公开 API 的 token（非用户密码），但仍建议通过环境变量配置
EASTMONEY_TOKEN = os.environ.get("EASTMONEY_TOKEN", "")
if not EASTMONEY_TOKEN:
    warnings.warn("EASTMONEY_TOKEN 未设置，东方财富接口可能无法访问")

EASTMONEY_UT = os.environ.get("EASTMONEY_UT", "")
if not EASTMONEY_UT:
    warnings.warn("EASTMONEY_UT 未设置，东方财富接口可能无法访问")


def get_db_params():
    """获取当前后端的连接参数"""
    if DB_BACKEND == "clickhouse":
        return CLICKHOUSE_CONFIG
    else:
        return MYSQL_CONFIG


def get_backend_label():
    """获取当前后端的中文标签"""
    return "ClickHouse" if DB_BACKEND == "clickhouse" else "MySQL"
