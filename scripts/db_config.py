#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
db_config.py
============
统一数据库配置模块。

通过 DB_BACKEND 环境变量或命令行参数切换后端:
  - clickhouse (默认): 读写 ClickHouse
  - mysql: 读写 MySQL（兼容模式）

用法:
    from db_config import DB_BACKEND, get_db_params

    if DB_BACKEND == "clickhouse":
        client = clickhouse_connect.get_client(**get_db_params())
    else:
        conn = pymysql.connect(**get_db_params())
"""

import os

# ─── 后端选择 ──────────────────────────────────────────────────
# 优先级: 环境变量 > 默认值
DB_BACKEND = os.environ.get("DB_BACKEND", "clickhouse").lower()
assert DB_BACKEND in ("clickhouse", "mysql"), f"DB_BACKEND 必须是 clickhouse 或 mysql，当前: {DB_BACKEND}"


# ─── MySQL 配置 ────────────────────────────────────────────────
MYSQL_CONFIG = dict(
    host="localhost",
    port=3306,
    user="root",
    password="123456",
    database="stock",
    charset="utf8mb4",
)


# ─── ClickHouse 配置 ──────────────────────────────────────────
CLICKHOUSE_CONFIG = dict(
    host="localhost",
    port=8123,
    username="default",
    password="123456",
    database="stock",
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
