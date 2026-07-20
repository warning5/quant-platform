#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
base.py
=======
数据提供者抽象基类。

定义统一接口契约，Baostock 和腾讯实现都遵循此接口，
实现数据源的可插拔替换。

统一 DataFrame 列格式（query_history 返回）:
    date, open, high, low, close, preclose,
    volume, amount, turn, tradestatus, pctChg, isST, peTTM, pbMRQ

统一 row dict 格式（build_daily_rows 返回，供 db.upsert_daily 使用）:
    code, name, trade_date, open_price, close_price, high_price, low_price,
    pre_close, volume, amount, change_percent, change_amount,
    turnover_rate, pe_ttm, pb, data_source
"""

from abc import ABC, abstractmethod
from datetime import date
from typing import List, Dict, Optional, Tuple

import pandas as pd


class DataProvider(ABC):
    """数据提供者抽象基类"""

    @abstractmethod
    def login(self) -> bool:
        """登录/初始化数据源。返回是否成功。"""
        ...

    @abstractmethod
    def logout(self):
        """登出/释放资源。"""
        ...

    @abstractmethod
    def query_history(self, code: str, market: str,
                      start_date: date, end_date: date,
                      adjustflag: str = "2") -> Optional[pd.DataFrame]:
        """
        查询历史日线数据。

        参数:
            code: 股票代码（纯数字，如 "600000"）
            market: 市场标识 "SH" / "SZ" / "BJ"
            start_date: 开始日期
            end_date: 结束日期
            adjustflag: 复权标志 "2"=前复权(默认), "1"=后复权, "3"=不复权

        返回:
            DataFrame，列: date, open, high, low, close, preclose,
            volume, amount, turn, tradestatus, pctChg, isST, peTTM, pbMRQ
            失败返回 None。
        """
        ...

    @abstractmethod
    def build_daily_rows(self, db, code: str, name: str, market: str,
                         df: pd.DataFrame) -> List[Dict]:
        """
        将 DataFrame 转换为 db.upsert_daily() 需要的 row dict list。
        子类实现各自的转换逻辑（PE/PB 来源不同）。
        """
        ...

    @abstractmethod
    def fetch_snapshot_pe_pb(self, codes_markets: List[Tuple[str, str]]
                             ) -> Dict[str, Dict]:
        """
        批量获取股票的 PE_TTM / PB 快照值。

        参数:
            codes_markets: [(code, market), ...]

        返回:
            {code: {"pe_ttm": float|None, "pb": float|None}}
        """
        ...

    @property
    @abstractmethod
    def source_name(self) -> str:
        """数据源标识名（写入 stock_daily.data_source 列）"""
        ...

    @property
    @abstractmethod
    def supports_full_history(self) -> bool:
        """是否支持全历史回刷（>2.5年）。Baostock=True, 腾讯=False(640日上限)"""
        ...

    @property
    @abstractmethod
    def supports_historical_pe_pb(self) -> bool:
        """是否支持历史每日 PE/PB 序列。Baostock=True, 腾讯=False(仅快照)"""
        ...

    @property
    def supports_parallel(self) -> bool:
        """是否支持多进程并行。Baostock=False(全局单socket), 腾讯=True"""
        return True

    def __enter__(self):
        self.login()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.logout()
        return False
