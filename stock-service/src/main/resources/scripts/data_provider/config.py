#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
config.py
=========
数据源配置。通过环境变量 DATA_SOURCE 切换 baostock / tencent。

优先级:
    1. 环境变量 DATA_SOURCE
    2. .env 文件中的 DATA_SOURCE
    3. 默认值 "baostock"（向后兼容）

用法:
    # .env 文件中设置:
    DATA_SOURCE=tencent

    # 或运行时指定:
    DATA_SOURCE=tencent python update_stock_daily.py

    # 或代码中指定:
    from data_provider import get_provider
    provider = get_provider("tencent")
"""

import os
import warnings
from pathlib import Path

# ─── 自动加载 .env（与 db_config.py 一致的查找顺序）──────────────
_env_candidates = [
    Path.cwd() / ".env",
    Path.home() / ".quant-platform" / ".env",
    Path(__file__).resolve().parent.parent / ".env",
    Path(__file__).resolve().parent.parent.parent / ".env",
]
_env_file = next((p for p in _env_candidates if p.exists()), None)
if _env_file is not None:
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if not _line or _line.startswith("#") or "=" not in _line:
            continue
        _eq = _line.index("=")
        _key = _line[:_eq].strip()
        _val = _line[_eq + 1:].strip()
        if len(_val) >= 2:
            if (_val[0] == '"' and _val[-1] == '"') or (_val[0] == "'" and _val[-1] == "'"):
                _val = _val[1:-1]
        if _key and _key not in os.environ:
            os.environ[_key] = _val

# ─── 数据源选择 ─────────────────────────────────────────────────
DATA_SOURCE = os.environ.get("DATA_SOURCE", "baostock").lower()
assert DATA_SOURCE in ("baostock", "tencent"), \
    f"DATA_SOURCE 必须是 baostock 或 tencent，当前: {DATA_SOURCE}"


def get_data_source() -> str:
    """获取当前数据源标识"""
    return DATA_SOURCE


def is_tencent() -> bool:
    """是否使用腾讯接口"""
    return DATA_SOURCE == "tencent"


def is_baostock() -> bool:
    """是否使用 Baostock"""
    return DATA_SOURCE == "baostock"
