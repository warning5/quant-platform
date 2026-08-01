#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""获取10年国债收益率（东方财富/akshare）"""
import sys
import os

# 确保输出UTF-8
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

try:
    import akshare as ak
    df = ak.bond_china_yield()
    # 按曲线名称过滤：取"中债国债收益率曲线"，取"10年"列
    # 列名：['曲线名称', '日期', '3月', '6月', '1年', '3年', '5年', '7年', '10年', '30年']
    gov = df[df.iloc[:, 0] == '中债国债收益率曲线']
    if len(gov) > 0:
        val = gov['10年'].values[0]
        if val is not None and not (hasattr(val, '__float__') and float(val) != float(val)):  # not NaN
            print(float(val))
        else:
            print('N/A')
    else:
        print('N/A')
except Exception as e:
    print(f'ERROR:{e}')
