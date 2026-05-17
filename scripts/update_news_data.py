#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_news_data.py
采集个股新闻数据（来源：akshare → 东方财富 stock_news_em）
支持事件标签自动识别（印度高温/Q1业绩/建厂等）

数据表：MySQL stock.stock_news
数据来源：ak.stock_news_em(symbol=code)

用法：
  python update_news_data.py 600519                  # 单只股票
  python update_news_data.py --batch                # 批量（stock_daily有数据的股票）
  python update_news_data.py --batch --days 90      # 近90天新闻
  python update_news_data.py --days 30 --code 600519

依赖：
  pip install akshare pymysql
"""
import sys
import os
import time
import warnings
import akshare as ak

os.environ["DISABLE_TQDM"] = "1"  # 禁用 tqdm 进度条，避免日志乱码
import pymysql
import math
from datetime import datetime, date, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed

warnings.filterwarnings('ignore')

# ── 数据库配置 ──────────────────────────────────────────────
DB_CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "123456",
    "database": "stock",
    "charset": "utf8mb4",
    "autocommit": True,
}

# 事件关键词标签映射（参考报告6类事件）
EVENT_TAGS = {
    "INDIA_HOT": ["印度高温", "极端高温", "热浪", "印度空调", "印度市场"],
    "PERFORMANCE": ["业绩", "净利润", "扣非", "营收", "季报", "半年报", "年报", "超预期", "不及预期", "增长", "下滑"],
    "EXPANSION": ["建厂", "扩产", "投产", "产能", "海外布局", "泰国建厂", "海外产能", "出口"],
    "POLICY_RISK": ["关税", "本土化", "政策", "限制", "加征", "贸易战", "制裁"],
    "RAW_MATERIAL": ["原材料", "大宗商品", "铜价", "铝价", "钢价", "成本"],
    "M_A": ["定增", "并购", "收购", "重组", "资产注入", "股权转让"],
    "UNLOCK": ["解禁", "减持", "限售股"],
    "INCENTIVE": ["股权激励", "员工持股", "期权激励"],
    "GOODWILL": ["商誉", "减值", "资产减值"],
    "INVENTORY": ["存货", "库存"],
    "FUND": ["基金持仓", "机构增持", "机构减持", "北向", "外资"],
}

# 情感词典（简单关键词匹配）
POSITIVE_WORDS = ["增持", "买入", "推荐", "超预期", "超配", "业绩大增", "大幅增长", "扭亏", "创新高", "突破", "中标", "订单", "扩产", "建厂", "高温", "热销", "强劲", "加速", "同比", "增长", "上升", "新高", "首超", "超10倍", "超预期", "超配", "强势"]
NEGATIVE_WORDS = ["减持", "卖出", "下调", "不及预期", "亏损", "预警", "警示", "风险", "调查", "处罚", "违约", "诉讼", "商誉减值", "业绩下滑", "终止", "违规", "造假", "暴雷", "造假", "虚增", "涉嫌", "被查", "ST", "*ST"]


def get_conn():
    cfg = DB_CONFIG.copy()
    cfg["charset"] = "utf8mb4"
    cfg["autocommit"] = False
    return pymysql.connect(**cfg)


def classify_sentiment(title, content=""):
    """基于关键词的情感分类：positive/negative/neutral，返回分数 -1~1"""
    text = (str(title) + " " + str(content))[:500]
    pos_count = sum(1 for w in POSITIVE_WORDS if w in text)
    neg_count = sum(1 for w in NEGATIVE_WORDS if w in text)
    total = pos_count + neg_count
    if total == 0:
        return 0.0, "neutral"
    score = (pos_count - neg_count) / total
    if score > 0.3:
        return round(score, 4), "positive"
    elif score < -0.3:
        return round(score, 4), "negative"
    return 0.0, "neutral"


def extract_event_tags(title, content=""):
    """基于关键词提取事件标签"""
    text = (str(title) + " " + str(content))[:1000]
    tags = []
    for tag, keywords in EVENT_TAGS.items():
        for kw in keywords:
            if kw in text:
                tags.append(tag)
                break
    return ",".join(tags) if tags else None


def fetch_news_for_stock(code, days=90):
    """
    从 akshare 获取个股新闻
    """
    records = []
    cutoff = datetime.now() - timedelta(days=days)
    try:
        df = ak.stock_news_em(symbol=code)
    except Exception as e:
        return records

    if df is None or df.empty:
        return records

    for _, row in df.iterrows():
        # 解析发布时间
        pub_raw = row.get("发布时间", "")
        if isinstance(pub_raw, str) and pub_raw:
            try:
                pub_date = datetime.strptime(pub_raw[:19], "%Y-%m-%d %H:%M:%S")
            except ValueError:
                try:
                    pub_date = datetime.strptime(pub_raw[:10], "%Y-%m-%d")
                except ValueError:
                    pub_date = None
        else:
            pub_date = None

        # 过滤太旧的新闻
        if pub_date and pub_date < cutoff:
            continue

        title = str(row.get("新闻标题", "")).strip()
        if not title:
            continue

        content = str(row.get("新闻内容", "") or "")[:1000]
        source = str(row.get("文章来源", "") or "")[:50]
        url = str(row.get("新闻链接", "") or "")[:500]

        sentiment, news_type = classify_sentiment(title, content)
        event_tag = extract_event_tags(title, content)

        records.append({
            "code": code,
            "title": title[:500],
            "content": content[:2000],
            "source": source,
            "publish_date": pub_date,
            "news_type": news_type,
            "sentiment_score": sentiment,
            "event_tag": event_tag,
            "url": url,
        })
    return records


def upsert_news_batch(conn, records):
    """批量写入新闻数据"""
    if not records:
        return 0
    sql = """
    INSERT INTO stock_news
        (code, title, content, source, publish_date, news_type, sentiment_score, event_tag, url)
    VALUES
        (%s, %s, %s, %s, %s, %s, %s, %s, %s)
    ON DUPLICATE KEY UPDATE
        title = VALUES(title),
        content = VALUES(content),
        sentiment_score = VALUES(sentiment_score),
        event_tag = VALUES(event_tag)
    """
    cur = conn.cursor()
    batch = BATCH_SIZE
    total = 0
    for i in range(0, len(records), batch):
        b = records[i:i + batch]
        vals = [(r["code"], r["title"], r["content"], r["source"],
                 r["publish_date"], r["news_type"], r["sentiment_score"],
                 r["event_tag"], r["url"]) for r in b]
        cur.executemany(sql, vals)
        total += len(b)
    conn.commit()
    cur.close()
    return total


BATCH_SIZE = 200


def get_batch_stocks(conn, force_all=False):
    """获取股票列表，默认 stock_daily，全量时从 stock_info 取"""
    cur = conn.cursor()
    if force_all:
        cur.execute("SELECT code FROM stock_info ORDER BY code")
    else:
        cur.execute("SELECT DISTINCT code FROM stock_daily ORDER BY code LIMIT 2000")
    stocks = [r[0] for r in cur.fetchall()]
    cur.close()
    return stocks


def run_batch(stocks, days=90, workers=8):
    """批量采集"""
    conn = get_conn()
    ensure_table(conn)
    conn.close()

    total = 0
    failed = 0
    results = []

    def worker(code):
        try:
            recs = fetch_news_for_stock(code, days=days)
            if recs:
                conn2 = get_conn()
                n = upsert_news_batch(conn2, recs)
                conn2.close()
                return code, n, None
            return code, 0, None
        except Exception as e:
            return code, 0, str(e)[:50]

    print(f"批量采集 {len(stocks)} 只股票的近{days}天新闻，并发 {workers} 线程")
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(worker, c): c for c in stocks}
        done = 0
        for fut in as_completed(futures):
            code, n, err = fut.result()
            done += 1
            total += n
            if err:
                failed += 1
            if n > 0:
                results.append((code, n))
            if done % 100 == 0:
                print(f"  进度 {done}/{len(stocks)}，已写入 {total} 条...")

    print(f"\n✅ 完成：写入 {total} 条新闻（{len(results)} 只有效数据，失败 {failed} 只）")
    if results:
        print("样本（前10）：")
        for code, n in results[:10]:
            print(f"  {code}: {n} 条")


def ensure_table(conn):
    cur = conn.cursor()
    cur.execute("""
    CREATE TABLE IF NOT EXISTS stock_news (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        code VARCHAR(20) NOT NULL,
        title VARCHAR(500),
        content TEXT,
        source VARCHAR(50),
        publish_date DATETIME,
        news_type VARCHAR(20),
        sentiment_score DECIMAL(5,4),
        event_tag VARCHAR(200),
        url VARCHAR(500),
        fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_code (code),
        INDEX idx_publish_date (publish_date),
        INDEX idx_event_tag (event_tag(100)),
        INDEX idx_news_type (news_type)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)
    conn.commit()
    cur.close()


def main():
    import argparse
    parser = argparse.ArgumentParser(description="个股新闻采集")
    parser.add_argument("code", nargs="?", default=None, help="股票代码")
    parser.add_argument("--days", type=int, default=90, help="采集近N天新闻（默认90）")
    parser.add_argument("--batch", action="store_true", help="批量模式")
    parser.add_argument("--workers", type=int, default=8, help="并发数（默认8）")
    parser.add_argument("--all", action="store_true", help="全量采集：从 stock_info 取所有股票")
    args = parser.parse_args()

    if args.code:
        print(f"采集 {args.code} 近 {args.days} 天新闻...")
        recs = fetch_news_for_stock(args.code, days=args.days)
        if not recs:
            print("无数据")
            return
        print(f"获取 {len(recs)} 条新闻，写入 MySQL...")
        conn = get_conn()
        ensure_table(conn)
        n = upsert_news_batch(conn, recs)
        conn.commit()
        conn.close()
        print(f"✅ 写入 {n} 条")
        # 打印样例
        print("\n样本（前5条）：")
        for r in recs[:5]:
            print(f"  [{r['news_type']}] {r['title'][:60]} | 情感={r['sentiment_score']} | 标签={r['event_tag']}")
        return

    if args.batch:
        conn = get_conn()
        stocks = get_batch_stocks(conn, force_all=args.all)
        conn.close()
        run_batch(stocks, days=args.days, workers=args.workers)
        return

    print("用法：python update_news_data.py 600519")
    print("      python update_news_data.py --batch")


if __name__ == "__main__":
    main()
