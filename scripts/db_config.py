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

# ─── 后端选择 ─────────────────────────────────────────────────
# 优先级: 环境变量 > 默认值
DB_BACKEND = os.environ.get("DB_BACKEND", "clickhouse").lower()
assert DB_BACKEND in ("clickhouse", "mysql"), f"DB_BACKEND 必须是 clickhouse 或 mysql，当前: {DB_BACKEND}"


# ─── MySQL 配置（从环境变量读取，无默认值仅用于本地开发兜底）───
MYSQL_CONFIG = dict(
    host=os.environ.get("MYSQL_HOST", "localhost"),
    port=int(os.environ.get("MYSQL_PORT", "3306")),
    user=os.environ.get("MYSQL_USER", "root"),
    password=os.environ.get("MYSQL_PASSWORD", "123456"),
    database=os.environ.get("MYSQL_DATABASE", "stock"),
    charset="utf8mb4",
)


# ─── ClickHouse 配置（从环境变量读取）────────────────────────
CLICKHOUSE_CONFIG = dict(
    host=os.environ.get("CLICKHOUSE_HOST", "172.19.72.140"),
    port=int(os.environ.get("CLICKHOUSE_PORT", "8123")),
    username=os.environ.get("CLICKHOUSE_USER", "default"),
    password=os.environ.get("CLICKHOUSE_PASSWORD", "123456"),
    database=os.environ.get("CLICKHOUSE_DATABASE", "stock"),
)


# ─── stock_info 表始终从 MySQL 读取（ClickHouse 无此表）───────
STOCK_INFO_DB = MYSQL_CONFIG


def get_db_params():
    """获取当前后端的连接参数"""
    if DB_BACKEND == "clickhouse":
        return CLICKHOUSE_CONFIG
    else:
        return MYSQL_CONFIG


def get_backend_label():
    """获取当前后端的中文标签"""
    return "ClickHouse" if DB_BACKEND == "clickhouse" else "MySQL"
