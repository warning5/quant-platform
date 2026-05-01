#!/usr/bin/env python3
"""
同步 MySQL stock_info → ClickHouse stock.stock_info
用法: python sync_stock_info_to_ch.py
"""
import pymysql
import requests
import json

MYSQL_CFG = dict(host="localhost", user="root", password="123456", database="stock")
CH_URL = "http://localhost:8123/?user=default&password=123456"
CH_TABLE = "stock.stock_info"

def fetch_mysql():
    conn = pymysql.connect(**MYSQL_CFG)
    cur = conn.cursor()
    cur.execute("""
        SELECT id, code, name, market, industry, list_date,
               is_hs, is_st, total_share, float_share,
               total_market_cap, float_market_cap, pe_ttm, pb,
               create_time, update_time
        FROM stock_info
    """)
    rows = cur.fetchall()
    conn.close()
    return rows

def build_symbol(code, market):
    if not market:
        return code
    return f"{code}.{market.upper()}"

def insert_ch(rows):
    # 构造 TSV 数据
    lines = []
    for r in rows:
        symbol = build_symbol(r[1], r[3])
        # 处理 NULL 值
        def fmt(v):
            if v is None:
                return "\\N"
            if isinstance(v, (bytearray, bytes)):
                v = v.decode()
            return str(v)
        line = "\t".join([
            fmt(r[0]),  # id
            fmt(r[1]),  # code
            fmt(r[2]),  # name
            fmt(r[3]),  # market
            fmt(r[4]),  # industry
            fmt(str(r[5]) if r[5] else None),  # list_date
            fmt(r[6]),  # is_hs
            fmt(r[7]),  # is_st
            fmt(r[8]),  # total_share
            fmt(r[9]),  # float_share
            fmt(r[10]), # total_market_cap
            fmt(r[11]), # float_market_cap
            fmt(r[12]), # pe_ttm
            fmt(r[13]), # pb
            fmt(str(r[14]) if r[14] else None),  # create_time
            fmt(str(r[15]) if r[15] else None),  # update_time
            symbol  # symbol = code.market
        ])
        lines.append(line)
    tsv = "\n".join(lines)

    # 先清空 CH 表
    resp = requests.post(CH_URL, params={"query": f"TRUNCATE TABLE {CH_TABLE}"})
    resp.raise_for_status()
    print(f"已清空 {CH_TABLE}")

    # 写入 CH（TSV 格式）
    url = f"{CH_URL}&query=INSERT+INTO+{CH_TABLE}+FORMAT+TSV"
    resp = requests.post(url, data=tsv.encode("utf-8"))
    resp.raise_for_status()
    print(f"已同步 {len(rows)} 条到 {CH_TABLE}")

if __name__ == "__main__":
    print("读取 MySQL stock_info ...")
    rows = fetch_mysql()
    print(f"读到 {len(rows)} 条，开始同步到 ClickHouse ...")
    insert_ch(rows)
    print("完成！")
