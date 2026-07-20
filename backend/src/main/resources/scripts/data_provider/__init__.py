#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
data_provider
=============
可插拔数据提供者模块。

通过 DATA_SOURCE 环境变量或函数参数切换 Baostock / 腾讯接口，
两者实现同一接口契约，可互相替换。

用法:
    # 方式1：自动从环境变量读取（DATA_SOURCE=baostock|tencent）
    from data_provider import get_provider
    provider = get_provider()
    provider.login()
    df = provider.query_history("600000", "SH", start, end)
    rows = provider.build_daily_rows(db, "600000", "浦发银行", "SH", df)
    db.upsert_daily(rows)
    provider.logout()

    # 方式2：显式指定数据源
    from data_provider import get_provider
    provider = get_provider("tencent")

    # 方式3：上下文管理器（自动 login/logout）
    from data_provider import get_provider
    with get_provider("baostock") as provider:
        df = provider.query_history("000001", "SZ", start, end)

环境变量配置（.env 文件）:
    DATA_SOURCE=baostock    # 默认，向后兼容
    DATA_SOURCE=tencent     # 切换到腾讯接口
"""

from .base import DataProvider
from .config import DATA_SOURCE, get_data_source, is_tencent, is_baostock


def get_provider(source: str = None) -> DataProvider:
    """
    工厂函数：获取数据提供者实例。

    参数:
        source: "baostock" / "tencent" / None
                None = 从环境变量 DATA_SOURCE 读取（默认 baostock）

    返回:
        DataProvider 实例

    示例:
        provider = get_provider()             # 从环境变量
        provider = get_provider("tencent")    # 显式指定腾讯
        provider = get_provider("baostock")   # 显式指定 Baostock
    """
    src = (source or DATA_SOURCE).lower()

    if src == "tencent":
        from .tencent_provider import TencentDataProvider
        return TencentDataProvider()
    elif src == "baostock":
        from .baostock_provider import BaostockDataProvider
        return BaostockDataProvider()
    else:
        raise ValueError(f"未知数据源: {src}，支持: baostock, tencent")


__all__ = [
    "DataProvider",
    "get_provider",
    "get_data_source",
    "is_tencent",
    "is_baostock",
    "DATA_SOURCE",
]
