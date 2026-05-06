"""
更新概念板块映射表 stock_concept
数据源: 东财 akshare stock_board_concept_name_em + stock_board_concept_cons_em
策略:
  --mode full   全量拉取所有486个板块（约40分钟，建议首次或每月跑一次）
  --mode hot    只拉取指定热门板块（快速，约2~3分钟）
  --mode clean  清空表后重建（配合 full 使用）
  --concepts    自定义板块名列表，逗号分隔（用于 hot 模式）

用法:
  python scripts/update_stock_concept.py --mode hot
  python scripts/update_stock_concept.py --mode full
  python scripts/update_stock_concept.py --mode hot --concepts "AI芯片,存储芯片,储能"
  python scripts/update_stock_concept.py --mode full --clean
"""

import argparse
import time
import sys
import os
from datetime import datetime
import pymysql
import akshare as ak
import pandas as pd
from concurrent.futures import ThreadPoolExecutor, as_completed

# ── 默认热门板块（覆盖主要科技/消费/周期方向）─────────────────────────────────
DEFAULT_HOT_CONCEPTS = [
    # 半导体/芯片
    "半导体概念", "存储芯片", "AI芯片", "汽车芯片", "国产芯片",
    "第三代半导体", "第四代半导体", "先进封装", "EDA概念",
    # AI/算力
    "人工智能", "MLOps概念", "数字经济",
    # 新能源
    "储能概念", "光伏概念", "新能源", "固态电池", "新能源车",
    "充电桩", "氢能源", "钠离子电池",
    # 消费/医疗
    "白酒", "创新药", "CRO", "医疗器械概念", "消费电子概念",
    # 军工/安全
    "军工", "北斗导航", "卫星互联网", "信创",
    # 其他热门
    "低空经济", "机器人概念", "人形机器人", "工业母机", "量子科技",
    "稀土永磁", "黄金概念", "锂电池概念", "锂矿概念",
]


def get_db_conn():
    return pymysql.connect(
        host="localhost", port=3306,
        user="root", password="123456",
        database="stock", charset="utf8mb4",
        autocommit=False
    )


def fetch_concept_list():
    """获取所有概念板块列表"""
    print("正在获取东财概念板块列表...", flush=True)
    df = ak.stock_board_concept_name_em()
    print(f"  共 {len(df)} 个概念板块", flush=True)
    return df["板块名称"].tolist()


def fetch_concept_stocks(concept_name: str, retry: int = 2):
    """获取单个概念板块的成分股，返回 [(code, name), ...]"""
    for attempt in range(retry + 1):
        try:
            df = ak.stock_board_concept_cons_em(symbol=concept_name)
            if df is None or df.empty:
                return []
            # 只取代码和名称，过滤掉非主板/科创/创业板代码（北交所也保留）
            rows = []
            for _, row in df.iterrows():
                code = str(row["代码"]).strip()
                name = str(row["名称"]).strip()
                # 过滤掉带后缀的（-UW等）以及非6位代码
                if len(code) == 6 and code.isdigit():
                    rows.append((code, name))
            return rows
        except Exception as e:
            if attempt < retry:
                time.sleep(2)
            else:
                print(f"  [警告] 获取 '{concept_name}' 成分股失败: {e}", flush=True)
                return []


def upsert_concept_rows(conn, concept_name: str, rows: list):
    """将成分股写入 stock_concept，INSERT IGNORE 跳过重复"""
    if not rows:
        return 0
    now = datetime.now()
    sql = """
        INSERT IGNORE INTO stock_concept (concept_name, code, name, create_time)
        VALUES (%s, %s, %s, %s)
    """
    data = [(concept_name, code, name, now) for code, name in rows]
    cur = conn.cursor()
    cur.executemany(sql, data)
    inserted = cur.rowcount
    conn.commit()
    return inserted


def delete_concept(conn, concept_name: str):
    """删除某概念下的旧数据（用于全量重建）"""
    cur = conn.cursor()
    cur.execute("DELETE FROM stock_concept WHERE concept_name = %s", (concept_name,))
    conn.commit()


def run_hot(concepts: list, clean: bool = False):
    """只拉取热门/指定板块"""
    conn = get_db_conn()
    total_inserted = 0
    total_concepts = len(concepts)

    print(f"\n[HOT模式] 更新 {total_concepts} 个概念板块", flush=True)
    for i, concept in enumerate(concepts, 1):
        if clean:
            delete_concept(conn, concept)
        rows = fetch_concept_stocks(concept)
        inserted = upsert_concept_rows(conn, concept, rows)
        total_inserted += inserted
        print(f"  [{i:3d}/{total_concepts}] {concept:20s} {len(rows):4d}只 (新增{inserted})", flush=True)
        time.sleep(0.3)  # 礼貌请求

    conn.close()
    print(f"\n[完成] 共写入 {total_inserted} 条记录", flush=True)


def run_full(clean: bool = False, max_workers: int = 3):
    """全量拉取所有486个概念板块（并发，但限速避免封禁）"""
    all_concepts = fetch_concept_list()
    conn = get_db_conn()

    if clean:
        print("清空 stock_concept 表...", flush=True)
        cur = conn.cursor()
        cur.execute("TRUNCATE TABLE stock_concept")
        conn.commit()

    total = len(all_concepts)
    total_inserted = 0
    failed = []

    print(f"\n[FULL模式] 全量更新 {total} 个概念板块（并发={max_workers}）", flush=True)
    print("预计耗时: 30~50分钟\n", flush=True)

    def process_one(idx_concept):
        idx, concept = idx_concept
        rows = fetch_concept_stocks(concept)
        return idx, concept, rows

    batch_size = max_workers * 5
    for batch_start in range(0, total, batch_size):
        batch = [(i + 1, all_concepts[i]) for i in range(batch_start, min(batch_start + batch_size, total))]

        with ThreadPoolExecutor(max_workers=max_workers) as ex:
            futures = {ex.submit(process_one, item): item for item in batch}
            for fut in as_completed(futures):
                idx, concept, rows = fut.result()
                if rows:
                    delete_concept(conn, concept)  # 先删再插保证幂等
                    inserted = upsert_concept_rows(conn, concept, rows)
                    total_inserted += inserted
                    print(f"  [{idx:3d}/{total}] {concept:25s} {len(rows):4d}只", flush=True)
                else:
                    failed.append(concept)
                    print(f"  [{idx:3d}/{total}] {concept:25s} [跳过/空]", flush=True)

        time.sleep(1.5)  # 每批次间隔

    conn.close()
    print(f"\n[完成] 共写入 {total_inserted} 条记录", flush=True)
    if failed:
        print(f"[失败] {len(failed)} 个板块获取失败: {failed[:20]}", flush=True)


def show_stats():
    """查看当前 stock_concept 统计"""
    conn = get_db_conn()
    cur = conn.cursor()
    cur.execute("SELECT COUNT(DISTINCT concept_name) FROM stock_concept")
    concept_cnt = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM stock_concept")
    row_cnt = cur.fetchone()[0]
    cur.execute("SELECT COUNT(DISTINCT code) FROM stock_concept")
    stock_cnt = cur.fetchone()[0]
    print(f"\n当前 stock_concept 统计:")
    print(f"  概念板块数: {concept_cnt}")
    print(f"  总记录数:   {row_cnt}")
    print(f"  覆盖股票数: {stock_cnt}")
    cur.execute("""
        SELECT concept_name, COUNT(*) as cnt
        FROM stock_concept
        GROUP BY concept_name
        ORDER BY cnt DESC
        LIMIT 15
    """)
    print(f"\n  TOP15 板块（按成分股数）:")
    for r in cur.fetchall():
        print(f"    {r[0]:25s} {r[1]}只")
    conn.close()


def main():
    parser = argparse.ArgumentParser(description="更新 stock_concept 概念板块映射表")
    parser.add_argument("--mode", choices=["hot", "full", "stats"], default="hot",
                        help="hot=热门板块, full=全量拉取, stats=只看统计")
    parser.add_argument("--concepts", type=str, default="",
                        help="自定义板块名称，逗号分隔（hot模式下覆盖默认列表）")
    parser.add_argument("--clean", action="store_true",
                        help="写入前先删除旧数据（保证幂等）")
    parser.add_argument("--workers", type=int, default=3,
                        help="全量模式并发数（默认3，太高容易被限频）")
    args = parser.parse_args()

    if args.mode == "stats":
        show_stats()
        return

    if args.mode == "hot":
        if args.concepts:
            concepts = [c.strip() for c in args.concepts.split(",") if c.strip()]
        else:
            concepts = DEFAULT_HOT_CONCEPTS
        run_hot(concepts, clean=args.clean)

    elif args.mode == "full":
        run_full(clean=args.clean, max_workers=args.workers)

    show_stats()


if __name__ == "__main__":
    main()
