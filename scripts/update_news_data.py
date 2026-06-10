#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
update_news_data.py
采集个股新闻数据（来源：akshare → 东方财富 stock_news_em）

数据表：MySQL stock.stock_news

用法：
  python update_news_data.py 600519                  # 单只股票
  python update_news_data.py --batch                  # 批量（增量）
  python update_news_data.py --batch --all             # 全量批量
  python update_news_data.py --refresh                 # 用新模型重刷已有新闻的情感分类
  python update_news_data.py --refresh --days 30       # 只刷新近30天

依赖：
  pip install akshare pymysql transformers torch
  模型：hw2942/bert-base-chinese-finetuning-financial-news-sentiment-v2
  模型缓存路径见代码中 _FIN_NEWS_MODEL_PATH 常量
"""
import sys
import os

# 必须在 import transformers 之前设置，彻底禁用 tqdm（否则 non-TTY 环境下会阻塞）
os.environ["TQDM_DISABLE"] = "1"
os.environ["DISABLE_TQDM"] = "1"
os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
os.environ["TRANSFORMERS_VERBOSITY"] = "error"
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
os.environ["HF_HUB_HTTP_ENDPOINT"] = "https://hf-mirror.com"

import time
import threading
import warnings
warnings.filterwarnings('ignore')

import akshare as ak
import pymysql
from datetime import datetime, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed

# ── 情感分类模型（直接用 model.forward，绕过 pipeline 避免 tqdm 阻塞）───
_sentiment_model = None
_sentiment_tokenizer = None
_refresh_model = None
_refresh_tokenizer = None

# 金融新闻专用模型（采集时用，已本地缓存）
_FIN_NEWS_MODEL_PATH = "C:/Users/warning5/.cache/huggingface/hub/models--hw2942--bert-base-chinese-finetuning-financial-news-sentiment-v2/snapshots/ac74343a25387f28cf3dcb5402e734b297bc182e"
# JD情感模型本地路径（refresh用）
_JD_MODEL_PATH = "C:/Users/warning5/.cache/huggingface/hub/models--uer--roberta-base-finetuned-jd-binary-chinese/snapshots/133367c1beb2d5b04e6df3e7ec218a49575bc437"


def _load_model_local(model_path):
    """加载模型到全局（本地路径，无网络调用），返回 (model, tokenizer）
    
    强制 use_safetensors=False + 重定向 stderr，
    彻底跳过 tqdm 进度条，避免 captured stdout 阻塞。
    """
    from transformers import AutoTokenizer, AutoModelForSequenceClassification
    import sys, threading, contextlib, os
    os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
    os.environ["DISABLE_TQDM"] = "1"
    tokenizer = AutoTokenizer.from_pretrained(model_path)
    model_holder = [None]
    model_error = [None]
    def load():
        # 将 stderr 重定向到 devnull，彻底屏蔽 tqdm 输出
        devnull = os.open(os.devnull, os.O_RDWR)
        old_stderr = os.dup(2)
        os.dup2(devnull, 2)
        try:
            model_holder[0] = AutoModelForSequenceClassification.from_pretrained(
                model_path
            )
        except Exception as e:
            model_error[0] = e
        finally:
            os.dup2(old_stderr, 2)
            os.close(devnull)
            os.close(old_stderr)
    t = threading.Thread(target=load)
    t.start()
    t.join()
    if model_error[0] is not None:
        raise model_error[0]
    if model_holder[0] is None:
        raise RuntimeError(f"模型加载失败: {model_path}")
    return model_holder[0], tokenizer


_model_lock = threading.Lock()

def _init_sentiment():
    """采集时初始化（金融新闻专用模型），多线程安全"""
    global _sentiment_model, _sentiment_tokenizer
    if _sentiment_model is not None:
        return
    with _model_lock:
        if _sentiment_model is not None:
            return
        print("[NLP] 加载金融新闻情感模型（本地）...", flush=True)
        _sentiment_model, _sentiment_tokenizer = _load_model_local(_FIN_NEWS_MODEL_PATH)
        print("[NLP] 模型就绪", flush=True)


def _init_refresh():
    """refresh 时初始化（JD模型），多线程安全"""
    global _refresh_model, _refresh_tokenizer
    if _refresh_model is not None:
        return
    with _model_lock:
        if _refresh_model is not None:
            return
        print("[NLP] 加载情感模型（本地）...", flush=True)
        _refresh_model, _refresh_tokenizer = _load_model_local(_JD_MODEL_PATH)
        print("[NLP] 模型就绪", flush=True)


def _batch_infer(texts, model, tokenizer, batch_size=256):
    """
    批量推理，直接用 model.forward() + torch.no_grad()，无 tqdm。
    texts: list of str
    返回: list of (score, type)
    
    自动适配:
    - 2分类模型 (num_labels=2): 0=negative, 1=positive
    - 3分类模型 (num_labels=3): 0=negative, 1=neutral, 2=positive
    """
    import torch
    num_labels = model.config.num_labels
    results = []
    for i in range(0, len(texts), batch_size):
        batch = texts[i:i + batch_size]
        inputs = tokenizer(batch, return_tensors='pt', truncation=True,
                           padding=True, max_length=128)
        with torch.no_grad():
            outputs = model(**inputs)
            probs = torch.softmax(outputs.logits, dim=-1)
        if num_labels == 3:
            neg_probs = probs[:, 0].tolist()
            neu_probs = probs[:, 1].tolist()
            pos_probs = probs[:, 2].tolist()
            for neg_p, neu_p, pos_p in zip(neg_probs, neu_probs, pos_probs):
                neg_p, neu_p, pos_p = float(neg_p), float(neu_p), float(pos_p)
                if pos_p >= neg_p and pos_p >= neu_p:
                    score = round(pos_p, 4)
                    ntype = "positive"
                elif neg_p >= pos_p and neg_p >= neu_p:
                    score = round(neg_p, 4)
                    ntype = "negative"
                else:
                    score = round(neu_p, 4)
                    ntype = "neutral"
                results.append((score, ntype))
        else:
            # 2分类: 0=negative, 1=positive
            pos_probs = probs[:, 1].tolist()
            for p in pos_probs:
                p = float(p)
                score = round(p, 4)
                ntype = "positive" if score >= 0.5 else "negative"
                if score < 0.5:
                    score = round(1.0 - score, 4)
                results.append((score, ntype))
    return results

# ── 数据库配置 ──────────────────────────────────────────────
DB_CONFIG = {
    "host": "127.0.0.1", "port": 3306, "user": "root",
    "password": "123456", "database": "stock", "charset": "utf8mb4",
}

# 事件标签关键词
EVENT_TAGS = {
    "INDUSTRY_EVENT": ["行业", "板块", "概念", "景气", "周期", "产能过剩", "供给侧", "需求端"],
    "PERFORMANCE": ["业绩", "净利润", "扣非", "营收", "季报", "半年报", "年报", "超预期", "不及预期", "增长", "下滑"],
    "EXPANSION": ["建厂", "扩产", "投产", "产能", "海外布局", "泰国建厂", "出口"],
    "POLICY_RISK": ["关税", "本土化", "政策", "限制", "加征", "贸易战", "制裁"],
    "RAW_MATERIAL": ["原材料", "大宗商品", "铜价", "铝价", "钢价", "成本"],
    "M_A": ["定增", "并购", "收购", "重组", "资产注入", "股权转让"],
    "UNLOCK": ["解禁", "减持", "限售股"],
    "INCENTIVE": ["股权激励", "员工持股", "期权激励"],
    "GOODWILL": ["商誉", "减值", "资产减值"],
    "FUND": ["基金持仓", "机构增持", "机构减持", "北向", "外资"],
}

# 情感关键词
POSITIVE_KW = [
    "增持", "买入", "超配", "超预期", "扭亏", "创新高", "突破", "中标", "订单",
    "扩产", "建厂", "热销", "强劲", "加速", "翻倍", "超10倍", "超亿元",
    "同比上升", "同比上涨", "同比增长", "上涨", "增长", "上升", "新高",
    "净流入", "资金流入", "涨停", "大涨", "强势", "首超", "推荐", "业绩大增", "大幅增长",
]
NEGATIVE_KW = [
    "减持", "卖出", "下调", "亏损", "预警", "警示", "调查", "处罚", "违约", "诉讼",
    "商誉减值", "业绩下滑", "终止", "违规", "造假", "暴雷", "涉嫌", "被查",
    "ST", "*ST", "戴帽", "退市", "风险", "下滑", "下跌", "净流出", "流出", "大跌", "跌停",
    "裁员", "不及预期", "虚增",
]


def get_conn():
    cfg = dict(DB_CONFIG)
    cfg["autocommit"] = False
    return pymysql.connect(**cfg)


def _kw_classify(text):
    """关键词情感分类：命中返回 (score, type)，未命中返回 (None, 'neutral')"""
    for kw in NEGATIVE_KW:
        if kw in text:
            return 0.0, "negative"
    for kw in POSITIVE_KW:
        if kw in text:
            return 1.0, "positive"
    return None, "neutral"


def classify_sentiment(title, content=""):
    """混合分类：关键词优先 + 情感模型兜底（直接 model.forward，无 tqdm）
    
    自动适配:
    - 2分类模型 (num_labels=2): 0=negative, 1=positive
    - 3分类模型 (num_labels=3): 0=negative, 1=neutral, 2=positive
    """
    text = (str(title) + " " + str(content))[:300]
    score, ntype = _kw_classify(text)
    if score is not None:
        return score, ntype
    try:
        import torch
        _init_sentiment()
        inputs = _sentiment_tokenizer(title[:200], return_tensors='pt',
                                      truncation=True, max_length=128)
        with torch.no_grad():
            outputs = _sentiment_model(**inputs)
            probs = torch.softmax(outputs.logits, dim=-1)
        num_labels = _sentiment_model.config.num_labels
        if num_labels == 3:
            neg_p = float(probs[0, 0])
            neu_p = float(probs[0, 1])
            pos_p = float(probs[0, 2])
            if pos_p >= neg_p and pos_p >= neu_p:
                score = round(pos_p, 4)
                ntype = "positive"
            elif neg_p >= pos_p and neg_p >= neu_p:
                score = round(neg_p, 4)
                ntype = "negative"
            else:
                score = round(neu_p, 4)
                ntype = "neutral"
        else:
            pos_p = float(probs[0, 1])
            score = round(pos_p, 4)
            ntype = "positive" if pos_p >= 0.5 else "negative"
            if ntype == "negative":
                score = round(1.0 - pos_p, 4)
        return score, ntype
    except Exception:
        return 0.5, "neutral"


def extract_event_tags(title, content=""):
    text = (str(title) + " " + str(content))[:1000]
    tags = []
    for tag, kws in EVENT_TAGS.items():
        for kw in kws:
            if kw in text:
                tags.append(tag)
                break
    return ",".join(tags) if tags else None


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


def fetch_news_for_stock(code, days=90, timeout=30):
    records = []
    cutoff = datetime.now() - timedelta(days=days)
    try:
        import signal
        # Windows 不支持 signal.alarm，用线程超时
        result_holder = [None]
        error_holder = [None]
        def _fetch():
            try:
                result_holder[0] = ak.stock_news_em(symbol=code)
            except Exception as e:
                error_holder[0] = e
        t = threading.Thread(target=_fetch, daemon=True)
        t.start()
        t.join(timeout=timeout)
        if t.is_alive():
            return records  # 超时，跳过
        if error_holder[0] is not None:
            return records
        df = result_holder[0]
    except Exception:
        return records
    if df is None or df.empty:
        return records
    for _, row in df.iterrows():
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
        if pub_date and pub_date < cutoff:
            continue
        title = str(row.get("新闻标题", "")).strip()
        if not title:
            continue
        content = str(row.get("新闻内容", "") or "")[:1000]
        sentiment, news_type = classify_sentiment(title, content)
        event_tag = extract_event_tags(title, content)
        records.append({
            "code": code,
            "title": title[:500],
            "content": content[:2000],
            "source": str(row.get("文章来源", "") or "")[:50],
            "publish_date": pub_date,
            "news_type": news_type,
            "sentiment_score": sentiment,
            "event_tag": event_tag,
            "url": str(row.get("新闻链接", "") or "")[:500],
        })
    return records


def upsert_news_batch(conn, records):
    if not records:
        return 0
    sql = """
    INSERT INTO stock_news (code, title, content, source, publish_date,
                             news_type, sentiment_score, event_tag, url)
    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
    ON DUPLICATE KEY UPDATE
        title = VALUES(title), content = VALUES(content),
        sentiment_score = VALUES(sentiment_score), event_tag = VALUES(event_tag)
    """
    cur = conn.cursor()
    total = 0
    for i in range(0, len(records), 200):
        b = records[i:i + 200]
        vals = [(r["code"], r["title"], r["content"], r["source"],
                 r["publish_date"], r["news_type"], r["sentiment_score"],
                 r["event_tag"], r["url"]) for r in b]
        cur.executemany(sql, vals)
        total += len(b)
    conn.commit()
    cur.close()
    return total


def get_batch_stocks(conn, force_all=False, stale_days=7):
    """获取待采集新闻的股票列表。
    - force_all: 全量（所有股票）
    - 增量模式: 从未采集过的股票 + 最新新闻超过 stale_days 天的股票
    """
    cur = conn.cursor()
    if force_all:
        cur.execute("SELECT code FROM stock_info ORDER BY code")
    else:
        cur.execute("""
            SELECT si.code FROM stock_info si
            LEFT JOIN (
                SELECT code, MAX(publish_date) as latest_date FROM stock_news GROUP BY code
            ) sn ON sn.code = si.code COLLATE utf8mb4_unicode_ci
            WHERE sn.code IS NULL
               OR sn.latest_date < DATE_SUB(NOW(), INTERVAL %s DAY)
            ORDER BY si.code
        """, (stale_days,))
    stocks = [r[0] for r in cur.fetchall()]
    cur.close()
    return stocks


def run_batch(stocks, days=90, workers=8):
    conn = get_conn()
    ensure_table(conn)
    conn.close()

    # 预加载模型，避免多线程同时触发加载
    _init_sentiment()

    total, failed, results = 0, 0, []

    def worker(code):
        try:
            recs = fetch_news_for_stock(code, days=days)
            if recs:
                c2 = get_conn()
                n = upsert_news_batch(c2, recs)
                c2.close()
                return code, n, None
            return code, 0, None
        except Exception as e:
            return code, 0, str(e)[:50]

    print(f"[批量] {len(stocks)} 只 | 近 {days} 天 | {workers} 线程", flush=True)
    t0 = time.time()
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
            if done % 50 == 0 or done == 1:
                print(f"  {done}/{len(stocks)} | {total} 条 | {time.time()-t0:.0f}s", flush=True)

    print(f"\n✅ {total} 条（{len(results)} 只有效，失败 {failed} 只）| {time.time()-t0:.0f}s", flush=True)
    for code, n in results[:10]:
        print(f"  {code}: {n} 条", flush=True)


def run_refresh(days=90):
    """用新模型重刷已有记录的的情感分类（不重采新闻）"""
    print(f"[情感刷新] 近 {days} 天新闻重新分类...", flush=True)
    conn = get_conn()
    ensure_table(conn)
    conn.commit()

    cur = conn.cursor()
    cur.execute("""
        SELECT id, code, title, content, news_type, sentiment_score
        FROM stock_news
        WHERE title IS NOT NULL AND title != ''
          AND publish_date >= DATE_SUB(NOW(), INTERVAL %s DAY)
        ORDER BY code, publish_date DESC
    """, (days,))
    rows = cur.fetchall()
    cur.close()

    if not rows:
        print("  无待刷新记录", flush=True)
        conn.close()
        return

    print(f"  {len(rows)} 条记录待刷新...", flush=True)

    # 第一步：全部走关键词分类，收集需要模型推理的 neutral 记录索引
    kw_results = []  # [(row_idx, score, ntype)]
    neutral_indices = []  # row_idx that need model inference
    for i, (rid, code, title, content, old_type, old_score) in enumerate(rows):
        text = (str(title or "") + " " + str(content or ""))[:300]
        score, ntype = _kw_classify(text)
        kw_results.append((score, ntype))
        if ntype == "neutral":
            neutral_indices.append(i)

    # 第二步：批量模型推理（直接 model.forward，无 tqdm）
    neutral_texts = [rows[i][2][:200] for i in neutral_indices]  # title only, 200 char
    model_scores = [None] * len(neutral_indices)

    if neutral_texts:
        _init_refresh()
        print(f"  模型推理 0/{len(neutral_texts)} ...", flush=True)
        batch_results = _batch_infer(neutral_texts, _refresh_model, _refresh_tokenizer, batch_size=64)
        for j, (score, ntype) in enumerate(batch_results):
            model_scores[j] = (score, ntype)
        print(f"  模型推理 {len(neutral_texts)}/{len(neutral_texts)} ✅", flush=True)

    # 第三步：合并结果，写入数据库
    final_results = []
    model_idx = 0
    for i, (rid, code, title, content, old_type, old_score) in enumerate(rows):
        score, ntype = kw_results[i]
        if ntype == "neutral":
            score, ntype = model_scores[model_idx]
            model_idx += 1
        final_results.append((ntype, score, rid))

    # 批量写入
    updated, changed = len(final_results), 0
    samples = []
    sql = "UPDATE stock_news SET news_type=%s, sentiment_score=%s WHERE id=%s"
    cur2 = conn.cursor()
    batch_size = 500
    for i in range(0, len(final_results), batch_size):
        batch = final_results[i:i + batch_size]
        batch_rows = rows[i:i + batch_size]
        vals = []
        for (ntype, score, rid), (rid2, code, title, content, old_type, old_score) in zip(batch, batch_rows):
            if old_type != ntype or (old_score is None and score != 0.5):
                changed += 1
                if len(samples) < 5:
                    samples.append((code, (title or "")[:40], str(old_type or ""), ntype, score))
            vals.append((ntype, score, rid))
        cur2.executemany(sql, vals)
        conn.commit()
        if (i + batch_size) % 2000 == 0:
            print(f"    写入 {min(i + batch_size, len(final_results))}/{len(final_results)}", flush=True)

    cur2.close()
    conn.close()

    print(f"  ✅ 更新 {updated} 条，变化 {changed} 条", flush=True)
    if samples:
        print("  变化样本（前5条）:", flush=True)
        for code, title, old, new, score in samples:
            print(f"    {code:<10} | {title:<42} | {old:>8} → {new:<8} | {score:.4f}", flush=True)


def main():
    import argparse
    p = argparse.ArgumentParser(description="新闻采集 + 情感分类")
    p.add_argument("code", nargs="?", default=None)
    p.add_argument("--days", type=int, default=90)
    p.add_argument("--batch", action="store_true")
    p.add_argument("--all", action="store_true")
    p.add_argument("--workers", type=int, default=8)
    p.add_argument("--refresh", action="store_true", help="重刷已有新闻的情感分类")
    args = p.parse_args()

    if args.refresh:
        run_refresh(days=args.days)
        return

    if args.code:
        recs = fetch_news_for_stock(args.code, days=args.days)
        if not recs:
            print("无数据", flush=True)
            return
        conn = get_conn()
        ensure_table(conn)
        upsert_news_batch(conn, recs)
        conn.close()
        print(f"✅ 写入 {len(recs)} 条", flush=True)
        for r in recs[:5]:
            print(f"  [{r['news_type']:8}] {r['title'][:60]}", flush=True)
        return

    if args.batch:
        conn = get_conn()
        stocks = get_batch_stocks(conn, force_all=args.all, stale_days=7)
        conn.close()
        run_batch(stocks, days=args.days, workers=args.workers)
        return

    print("用法：")
    print("  python update_news_data.py 600519")
    print("  python update_news_data.py --batch")
    print("  python update_news_data.py --batch --all")
    print("  python update_news_data.py --refresh")
    print("  python update_news_data.py --refresh --days 30")


if __name__ == "__main__":
    main()
