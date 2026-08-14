#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""获取10年国债收益率（新浪财经·中债国债收益率曲线，实时）

此前使用 akshare bond_china_yield()，但该接口自某版本起返回的数据
卡在 2020 年（最新一行仍为 2020-02），已失效，导致股债收益比分母
长期停留在 2.68% 附近、系统性低估风险偏好。
现改用 ak.bond_gb_zh_sina()（新浪财经中债国债收益率曲线），返回每日
序列，close 列为 10 年期收益率(%)。取最新一日作为实时值。
"""
import sys
import os

# 确保输出UTF-8
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

try:
    import akshare as ak
    df = ak.bond_gb_zh_sina()
    if df is not None and len(df) > 0 and 'close' in df.columns and 'date' in df.columns:
        # 按日期升序后取最新一行（close 即 10Y 收益率，单位 %）
        df = df.copy()
        df['date'] = df['date'].astype(str)
        df = df.sort_values('date')
        latest = df.iloc[-1]
        val = latest['close']
        if val is not None:
            try:
                fv = float(val)
            except (ValueError, TypeError):
                fv = float('nan')
            # 合理性校验：10Y 国债收益率历史上极少超出 0.5%~5% 区间
            if fv == fv and 0.5 <= fv <= 5.0:  # fv==fv 排除 NaN
                print(round(fv, 4))
            else:
                print('N/A')
        else:
            print('N/A')
    else:
        print('N/A')
except Exception as e:
    print(f'ERROR:{e}')
