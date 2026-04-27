#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
db_helper.py
============
统一的 stock_daily 数据库操作封装层。

屏蔽 MySQL / ClickHouse 的 SQL 差异，提供统一接口：
- get_stocks()          — 从 stock_info 获取股票列表
- get_prev_close()      — 获取前一日收盘价
- upsert_daily()        — 插入/更新日线数据（批量）
- get_latest_date()     — 获取最新交易日
- get_latest_date_by_code() — 获取单只股票最新交易日
- complete_change()     — SQL 级补全 change 字段
- get_daily_stats()     — 获取数据概况

注意：已移除 market_cap 和 circ_market_cap 字段相关操作

用法:
    from db_helper import StockDailyDB
    db = StockDailyDB()
    stocks = db.get_stocks(market="SH")
    db.upsert_daily(rows)  # rows: list of dict
    db.close()
"""

import time
from datetime import date, datetime, timedelta

from db_config import DB_BACKEND, MYSQL_CONFIG, CLICKHOUSE_CONFIG, get_backend_label


class StockDailyDB:
    """stock_daily 统一数据库操作类"""

    # ClickHouse 表名
    CH_TABLE = "stock.stock_daily"

    # stock_daily 的 17 个字段（业务15列 + create_time + update_time）
    # 注意：已移除 market_cap 和 circ_market_cap 字段
    DAILY_COLUMNS = [
        "code", "trade_date", "name", "open_price", "close_price",
        "high_price", "low_price", "pre_close", "volume", "amount",
        "change_percent", "change_amount", "turnover_rate",
        "pe_ttm", "pb",
        "create_time", "update_time",
    ]

    def __init__(self):
        self.backend = DB_BACKEND
        self.mysql_conn = None
        self.ch_client = None
        self.mysql_info_conn = None  # stock_info 专用 MySQL 连接

        if self.backend == "clickhouse":
            import clickhouse_connect
            self.ch_client = clickhouse_connect.get_client(**CLICKHOUSE_CONFIG)
        else:
            import pymysql
            self.mysql_conn = pymysql.connect(
                **MYSQL_CONFIG,
                cursorclass=pymysql.cursors.DictCursor,
            )

        # stock_info 始终从 MySQL 读取
        import pymysql
        self.mysql_info_conn = pymysql.connect(
            **MYSQL_CONFIG,
            cursorclass=pymysql.cursors.DictCursor,
        )

    def close(self):
        """关闭所有连接"""
        if self.ch_client:
            self.ch_client.close()
            self.ch_client = None
        if self.mysql_conn:
            self.mysql_conn.close()
            self.mysql_conn = None
        if self.mysql_info_conn:
            self.mysql_info_conn.close()
            self.mysql_info_conn = None

    def query(self, sql, params=None):
        """
        通用查询接口，自动适配 ClickHouse / MySQL。

        ClickHouse: 使用 %(name)s 命名占位符，params 传 dict
        MySQL:     使用 %s 顺序占位符，params 传 tuple/list

        返回: [(col1, col2, ...), ...]  元组列表
        """
        if self.backend == "clickhouse":
            if params is None:
                r = self.ch_client.query(sql)
            else:
                r = self.ch_client.query(sql, parameters=params)
            return r.result_rows
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(sql, params)
                return list(cur.fetchall())

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    # ─── stock_info 查询（始终 MySQL）─────────────────────────

    def _info_cursor(self):
        """获取 stock_info 连接的 cursor"""
        return self.mysql_info_conn.cursor()

    def get_stocks(self, market=None, limit=0, code=None, pool=None):
        """
        从 stock_info 获取股票列表。

        参数:
            market: 过滤市场 (SH/SZ/BJ)，None 表示全市场
            limit: 返回上限，0 表示不限
            code:   精确匹配单个股票
            pool:   股票池 (SH300/SZ50/ZZ500/ZZ1000/STAR50)，None 表示全部

        股票池筛选规则（与 DataUpdateService.applyStockPool 保持一致）:
            - SH300:  按 total_market_cap 降序 LIMIT 300
            - SZ50:   代码前缀 000/60 开头，按市值 LIMIT 50
            - ZZ500:  按 total_market_cap 降序 LIMIT 500
            - ZZ1000: 按 total_market_cap 降序 LIMIT 1000
            - STAR50: 代码前缀 688 开头（科创板），不限制数量

        返回: [(code, name, market), ...]
        """
        conditions = []
        params = []

        if code:
            conditions.append("code = %s")
            params.append(code)
        elif market and not pool:
            # 有 pool 时由 pool 自身决定范围，不再叠加 market 过滤
            conditions.append("market = %s")
            params.append(market)

        # 股票池筛选
        pool_limit = 0
        if pool:
            if pool == "SH300":
                conditions.append("total_market_cap IS NOT NULL")
                order = "total_market_cap DESC"
                pool_limit = 300
            elif pool == "SZ50":
                conditions.append("(code LIKE '000%%' OR code LIKE '60%%')")
                order = "total_market_cap DESC"
                pool_limit = 50
            elif pool == "ZZ500":
                conditions.append("total_market_cap IS NOT NULL")
                order = "total_market_cap DESC"
                pool_limit = 500
            elif pool == "ZZ1000":
                conditions.append("total_market_cap IS NOT NULL")
                order = "total_market_cap DESC"
                pool_limit = 1000
            elif pool == "STAR50":
                conditions.append("code LIKE '688%%'")
                order = "code"
                pool_limit = 0
            else:
                order = "code"
        else:
            order = "code"

        where = (" WHERE " + " AND ".join(conditions)) if conditions else ""
        final_limit = pool_limit if pool_limit > 0 else (limit if limit > 0 else 0)
        sql = f"SELECT code, name, market FROM stock_info{where} ORDER BY {order}"
        if final_limit > 0:
            sql += f" LIMIT {final_limit}"

        with self._info_cursor() as cur:
            cur.execute(sql, params)
            return [(r["code"], r["name"], r["market"]) for r in cur.fetchall()]

    def get_market_by_code(self, code):
        """获取股票的市场标识"""
        with self._info_cursor() as cur:
            cur.execute("SELECT market FROM stock_info WHERE code = %s", (code,))
            row = cur.fetchone()
            return row["market"] if row else None

    # ─── stock_daily 查询 ─────────────────────────────────────

    def get_prev_close(self, code, trade_date):
        """
        获取某股票在指定日期之前的最近收盘价（昨收）。
        ClickHouse 回查最近 30 个交易日（应对 CH 数据不连续的情况）；
        MySQL 仍查最近一条。
        返回: float 或 None
        """
        if self.backend == "clickhouse":
            # 回查 30 个交易日，避免 CH 数据有缺口时找不到前收
            r = self.ch_client.query(
                f"SELECT close_price FROM {self.CH_TABLE} "
                f"WHERE code = %(code)s "
                f"  AND trade_date < %(td)s "
                f"  AND trade_date >= %(td)s - INTERVAL 30 DAY "
                f"ORDER BY trade_date DESC LIMIT 1",
                parameters={"code": code, "td": trade_date},
            )
            rows = r.result_rows
            return float(rows[0][0]) if rows and rows[0][0] is not None else None
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT close_price FROM stock_daily "
                    "WHERE code = %s AND trade_date < %s ORDER BY trade_date DESC LIMIT 1",
                    (code, trade_date),
                )
                row = cur.fetchone()
                return float(row["close_price"]) if row and row["close_price"] else None

    def get_latest_date(self):
        """获取 stock_daily 中最新交易日"""
        if self.backend == "clickhouse":
            r = self.ch_client.query(f"SELECT MAX(trade_date) FROM {self.CH_TABLE}")
            val = r.result_rows[0][0]
            return val if val else None
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute("SELECT MAX(trade_date) as max_date FROM stock_daily")
                row = cur.fetchone()
                return row["max_date"] if row and row["max_date"] else None

    def get_latest_date_by_code(self, code):
        """获取某只股票的最新交易日"""
        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT MAX(trade_date) FROM {self.CH_TABLE} WHERE code = %(code)s",
                parameters={"code": code},
            )
            val = r.result_rows[0][0]
            return val if val else None
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT MAX(trade_date) as max_date FROM stock_daily WHERE code = %s",
                    (code,),
                )
                row = cur.fetchone()
                return row["max_date"] if row and row["max_date"] else None

    def get_latest_dates_batch(self, codes):
        """
        批量获取多只股票的最新交易日，一次查询代替 N 次。
        返回: dict { code: latest_date_str or None }
        """
        if not codes:
            return {}
        result = {code: None for code in codes}

        if self.backend == "clickhouse":
            # ClickHouse: GROUP BY 一次查完
            # CH 表中代码不带前缀（如 600328），但传入的 codes 可能带前缀（如 SH600328）
            # 需要建立映射：normalized_code -> original_code
            code_map = {}  # normalized_code -> original_code
            normalized_codes = []
            for c in codes:
                # 去掉 SH/SZ/BJ 前缀
                nc = c[2:] if len(c) > 2 and c[:2] in ("SH", "SZ", "BJ") else c
                code_map[nc] = c
                normalized_codes.append(nc)

            placeholders = ", ".join([f"'{c}'" for c in normalized_codes])
            r = self.ch_client.query(
                f"SELECT code, MAX(trade_date) AS max_date "
                f"FROM {self.CH_TABLE} WHERE code IN ({placeholders}) GROUP BY code"
            )
            for row in r.result_rows:
                original_code = code_map.get(row[0], row[0])
                result[original_code] = row[1]
        else:
            # MySQL: IN clause 批量查
            placeholders = ", ".join(["%s"] * len(codes))
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    f"SELECT code, MAX(trade_date) AS max_date "
                    f"FROM stock_daily WHERE code IN ({placeholders}) GROUP BY code",
                    tuple(codes),
                )
                for row in cur.fetchall():
                    result[row["code"]] = row["max_date"]
        return result

    def get_latest_date_in_range(self, code, start_date, end_date):
        """获取指定日期范围内的最新交易日"""
        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT MAX(trade_date) FROM {self.CH_TABLE} "
                f"WHERE code = %(code)s AND trade_date BETWEEN %(sd)s AND %(ed)s",
                parameters={"code": code, "sd": start_date, "ed": end_date},
            )
            val = r.result_rows[0][0]
            return val if val else None
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT MAX(trade_date) as max_date FROM stock_daily "
                    "WHERE code = %s AND trade_date BETWEEN %s AND %s",
                    (code, start_date, end_date),
                )
                row = cur.fetchone()
                return row["max_date"] if row and row["max_date"] else None

    def get_latest_dates_in_range_batch(self, codes, start_date, end_date):
        """
        批量获取多只股票在指定日期范围内的最新交易日，一次查询。
        返回: dict { code: latest_date_str or None }
        """
        if not codes:
            return {}
        result = {code: None for code in codes}

        if self.backend == "clickhouse":
            # CH 表中代码不带前缀，需要建立映射
            code_map = {}  # normalized_code -> original_code
            normalized_codes = []
            for c in codes:
                nc = c[2:] if len(c) > 2 and c[:2] in ("SH", "SZ", "BJ") else c
                code_map[nc] = c
                normalized_codes.append(nc)

            placeholders = ", ".join([f"'{c}'" for c in normalized_codes])
            r = self.ch_client.query(
                f"SELECT code, MAX(trade_date) AS max_date "
                f"FROM {self.CH_TABLE} "
                f"WHERE code IN ({placeholders}) AND trade_date BETWEEN '{start_date}' AND '{end_date}' "
                f"GROUP BY code"
            )
            for row in r.result_rows:
                original_code = code_map.get(row[0], row[0])
                result[original_code] = row[1]
        else:
            placeholders = ", ".join(["%s"] * len(codes))
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    f"SELECT code, MAX(trade_date) AS max_date "
                    f"FROM stock_daily "
                    f"WHERE code IN ({placeholders}) AND trade_date BETWEEN %s AND %s "
                    f"GROUP BY code",
                    tuple(codes) + (start_date, end_date),
                )
                for row in cur.fetchall():
                    result[row["code"]] = row["max_date"]
        return result

    def get_last_trading_day_before(self, end_date):
        """
        返回 end_date 之前（含）的最后一个交易日。
        直接查全表 MAX(trade_date) WHERE trade_date <= end_date，
        不依赖特定指数是否有数据。
        如果查不到，返回 end_date 本身（兜底）。
        """
        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT MAX(trade_date) AS d FROM {self.CH_TABLE} "
                f"WHERE trade_date <= %(d)s",
                parameters={"d": end_date},
            )
            if r.result_rows and r.result_rows[0][0]:
                d = r.result_rows[0][0]
                if isinstance(d, str):
                    from datetime import datetime
                    d = datetime.strptime(d, "%Y-%m-%d").date()
                return d
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT MAX(trade_date) FROM stock_daily "
                    "WHERE trade_date <= %s",
                    (end_date,),
                )
                row = cur.fetchone()
                if row and row[0]:
                    return row[0]
        return end_date

    def has_daily_record(self, code, trade_date):
        """检查某股票某日是否已有记录"""
        # CH 表中代码不带前缀，需要规范化
        ch_code = code[2:] if len(code) > 2 and code[:2] in ("SH", "SZ", "BJ") else code

        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT count() FROM {self.CH_TABLE} "
                f"WHERE code = %(code)s AND trade_date = %(td)s",
                parameters={"code": ch_code, "td": trade_date},
            )
            return r.result_rows[0][0] > 0
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT COUNT(*) as cnt FROM stock_daily WHERE code = %s AND trade_date = %s",
                    (code, trade_date),
                )
                return cur.fetchone()["cnt"] > 0

    # ─── stock_daily 写入 ─────────────────────────────────────

    def upsert_daily(self, rows):
        """
        批量 upsert 日线数据。

        ClickHouse: 先 DELETE 旧版本再 INSERT，保证 (code, trade_date) 唯一。
        MySQL: ON DUPLICATE KEY UPDATE 覆盖。

        返回: 插入的行数
        """
        if not rows:
            return 0

        if self.backend == "clickhouse":
            self._ch_delete_rows(rows)
            self._ch_insert_rows(rows)
            return len(rows)
        else:
            return self._mysql_upsert_rows(rows)

    def _ch_delete_rows(self, rows):
        """ClickHouse: 批量删除旧数据（兼容旧 MergeTree 引擎）"""
        if not rows:
            return

        # 分批删除，每批 1000 条避免 SQL 过长
        batch_size = 1000
        for i in range(0, len(rows), batch_size):
            batch = rows[i : i + batch_size]
            conditions = []
            params = {}
            for idx, row in enumerate(batch):
                conditions.append(f"(%(code{idx})s, %(td{idx})s)")
                td = row.get("trade_date")
                if isinstance(td, date):
                    td = td.strftime("%Y-%m-%d") if hasattr(td, "strftime") else str(td)
                params[f"code{idx}"] = row["code"]
                params[f"td{idx}"] = td

            where_clause = ", ".join(conditions)
            sql = f"ALTER TABLE {self.CH_TABLE} DELETE WHERE (code, trade_date) IN ({where_clause})"
            try:
                self.ch_client.command(sql, parameters=params)
            except Exception as e:
                # ALTER TABLE DELETE 在某些版本可能不支持，回退为忽略
                print(f"  [WARN] ClickHouse DELETE 失败（数据可能有重复）: {e}")

    def _ch_insert_rows(self, rows):
        """ClickHouse: 批量插入数据（行式）。调用方需先调 _ch_delete_rows 删除旧版本。"""
        if not rows:
            return

        now_dt = datetime.now()

        # 业务字段（不含时间）
        # 注意：已移除 market_cap 和 circ_market_cap 字段
        biz_cols = [
            "code", "trade_date", "name", "open_price", "close_price",
            "high_price", "low_price", "pre_close", "volume", "amount",
            "change_percent", "change_amount", "turnover_rate",
            "pe_ttm", "pb",
        ]

        # 转换为行式数据（list of list）
        all_rows = []
        for row in rows:
            vals = []
            for col in biz_cols:
                val = row.get(col)
                # 类型转换
                if col == "trade_date":
                    # ClickHouse Date 列需要 date 对象
                    if isinstance(val, str):
                        val = date.fromisoformat(val)
                    elif isinstance(val, datetime):
                        val = val.date()
                elif col in ("volume",) and val is not None:
                    val = int(val)
                elif col != "code" and col != "name" and val is not None:
                    val = float(val)
                vals.append(val)

            # create_time: Nullable(DateTime)，允许 NULL
            ct = row.get("create_time")
            if ct is None:
                vals.append(now_dt)
            elif isinstance(ct, str):
                vals.append(datetime.fromisoformat(ct))
            elif isinstance(ct, date) and not isinstance(ct, datetime):
                vals.append(datetime.combine(ct, datetime.min.time()))
            else:
                vals.append(ct)

            # update_time: DateTime NOT NULL（ReplacingMergeTree 的 version 列）
            ut = row.get("update_time")
            if ut is None:
                vals.append(now_dt)
            elif isinstance(ut, str):
                vals.append(datetime.fromisoformat(ct) if ut == ct else datetime.now())
            elif isinstance(ut, date) and not isinstance(ut, datetime):
                vals.append(datetime.combine(ut, datetime.min.time()))
            else:
                vals.append(ut)

            all_rows.append(vals)

        # 分批插入，每批 5000 条
        batch_size = 5000
        total = len(all_rows)
        for i in range(0, total, batch_size):
            batch = all_rows[i : i + batch_size]
            try:
                self.ch_client.insert(self.CH_TABLE, batch, column_names=self.DAILY_COLUMNS)
            except Exception as e:
                print(f"  [ERROR] ClickHouse INSERT 失败 (批次 {i//batch_size + 1}): {e}")

    def _mysql_upsert_rows(self, rows):
        """MySQL: 批量 UPSERT"""
        # 注意：已移除 market_cap 和 circ_market_cap 字段
        INSERT_SQL = """
        INSERT INTO stock_daily
        (code, name, trade_date, open_price, close_price,
         high_price, low_price, pre_close, volume, amount, change_percent,
         change_amount, turnover_rate, pe_ttm, pb,
         create_time, update_time)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            open_price = VALUES(open_price),
            close_price = VALUES(close_price),
            high_price = VALUES(high_price),
            low_price = VALUES(low_price),
            pre_close = VALUES(pre_close),
            volume = VALUES(volume),
            amount = VALUES(amount),
            change_percent = VALUES(change_percent),
            change_amount = VALUES(change_amount),
            turnover_rate = VALUES(turnover_rate),
            pe_ttm = VALUES(pe_ttm),
            pb = VALUES(pb),
            update_time = NOW()
        """

        total = 0
        with self.mysql_conn.cursor() as cur:
            for row in rows:
                td = row.get("trade_date")
                if isinstance(td, (date, datetime)):
                    td = td if isinstance(td, date) else td.date()

                values = (
                    row.get("code"),
                    row.get("name"),
                    td,
                    row.get("open_price"),
                    row.get("close_price"),
                    row.get("high_price"),
                    row.get("low_price"),
                    row.get("pre_close"),
                    row.get("volume"),
                    row.get("amount"),
                    row.get("change_percent"),
                    row.get("change_amount"),
                    row.get("turnover_rate"),
                    row.get("pe_ttm"),
                    row.get("pb"),
                )
                cur.execute(INSERT_SQL, values)
                total += 1
        self.mysql_conn.commit()
        return total

    # ─── 字段补全 ──────────────────────────────────────────────

    def complete_change_fields(self, code=None, stock_list=None):
        """
        补全 change_percent / change_amount / pre_close。
        通过前一行 close_price 计算（纯 SQL 级）。
        返回: 修复的记录数

        参数:
            code: 指定单只股票（可选）
            stock_list: [(code, market), ...] 限制范围（可选）
        """
        if self.backend == "clickhouse":
            return self._ch_fix_change(code, stock_list)
        else:
            return self._mysql_fix_change(code, stock_list)

    def _mysql_fix_change(self, code=None, stock_list=None):
        """MySQL: 补全 change 字段"""
        if code:
            codes = [code]
        elif stock_list:
            codes = [s[0] for s in stock_list]
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT DISTINCT code FROM stock_daily "
                    "WHERE (pre_close IS NULL OR change_percent IS NULL OR change_amount IS NULL) "
                    "ORDER BY code"
                )
                codes = [r["code"] for r in cur.fetchall()]

        if not codes:
            return 0

        total_fixed = 0
        for c in codes:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    """SELECT id, trade_date, close_price, pre_close,
                              change_percent, change_amount
                       FROM stock_daily WHERE code = %s ORDER BY trade_date""",
                    (c,),
                )
                rows = cur.fetchall()

            if len(rows) < 2:
                continue

            updates = []
            prev_close_price = None
            for row in rows:
                rid = row["id"]
                close_p = float(row["close_price"]) if row["close_price"] else None
                cur_pre = float(row["pre_close"]) if row["pre_close"] else None
                cur_pct = float(row["change_percent"]) if row["change_percent"] else None
                cur_amt = float(row["change_amount"]) if row["change_amount"] else None

                need = False
                new_pre, new_pct, new_amt = prev_close_price, cur_pct, cur_amt

                if prev_close_price is not None and close_p is not None:
                    exp_pct = round((close_p - prev_close_price) / prev_close_price * 100, 2)
                    exp_amt = round(close_p - prev_close_price, 2)
                    if cur_pct is None or cur_pct == 0:
                        new_pct = exp_pct; need = True
                    if cur_amt is None or cur_amt == 0:
                        new_amt = exp_amt; need = True
                    if cur_pre is None or cur_pre == 0:
                        new_pre = prev_close_price; need = True

                if need:
                    updates.append((new_pre, new_pct, new_amt, rid))
                if close_p is not None:
                    prev_close_price = close_p

            if updates:
                with self.mysql_conn.cursor() as cur:
                    cur.executemany(
                        "UPDATE stock_daily SET pre_close=%s, change_percent=%s, change_amount=%s WHERE id=%s",
                        updates,
                    )
                self.mysql_conn.commit()
                total_fixed += len(updates)
        return total_fixed

    def _ch_fix_change(self, code=None, stock_list=None):
        """ClickHouse: 补全 change 字段。

        策略: 先 DELETE 该 code 全量数据，再重新 INSERT 全量（包含修复值）。
        全量写入确保 (code, trade_date) 唯一，无重复。
        """
        if code:
            codes = [code]
        elif stock_list:
            codes = [s[0] for s in stock_list]
        else:
            r = self.ch_client.query(
                f"SELECT DISTINCT code FROM {self.CH_TABLE} "
                f"WHERE pre_close IS NULL OR change_percent IS NULL OR change_amount IS NULL "
                f"ORDER BY code"
            )
            codes = [row[0] for row in r.result_rows]

        if not codes:
            return 0

        total_fixed = 0
        for c in codes:
            # Step 1: 读该 code 全部行（ORDER BY trade_date 保证连续计算正确）
            r = self.ch_client.query(
                f"SELECT * FROM {self.CH_TABLE} WHERE code = %(code)s ORDER BY trade_date",
                parameters={"code": c},
            )
            rows = r.result_rows
            if len(rows) < 2:
                continue

            # 解析列名
            raw_cols = r.column_names
            col_names = [c[0] if isinstance(c, (list, tuple)) else c for c in raw_cols]

            # Step 2: 计算修复值，并构造全量重新写入的行列表
            rows_to_insert = []
            prev_close = None
            fixed_count = 0

            for row in rows:
                row_dict = {col_names[i]: row[i] for i in range(len(col_names))}

                close_p = float(row_dict["close_price"]) if row_dict.get("close_price") else None
                cur_pre = float(row_dict["pre_close"]) if row_dict.get("pre_close") else None
                cur_pct = float(row_dict["change_percent"]) if row_dict.get("change_percent") else None
                cur_amt = float(row_dict["change_amount"]) if row_dict.get("change_amount") else None

                new_pre, new_pct, new_amt = cur_pre, cur_pct, cur_amt

                if prev_close is not None and close_p is not None:
                    exp_pct = round((close_p - prev_close) / prev_close * 100, 2)
                    exp_amt = round(close_p - prev_close, 2)
                    if cur_pct is None or cur_pct == 0:
                        new_pct = exp_pct
                        fixed_count += 1
                    if cur_amt is None or cur_amt == 0:
                        new_amt = exp_amt
                        fixed_count += 1
                    if cur_pre is None or cur_pre == 0:
                        new_pre = prev_close
                        fixed_count += 1
                elif close_p is not None and prev_close is None:
                    # 第一行：prev_close 无历史数据。用 pctChg 反算；
                    # pctChg=0 时 fallback 为 pre_close=close（跨年/长假首日常见）
                    if cur_pct is not None and cur_pct != 0:
                        new_pre = round(close_p / (1 + cur_pct / 100), 2)
                        new_pct = cur_pct
                        fixed_count += 1
                    elif cur_pre is None or cur_pre == 0:
                        new_pre = close_p  # fallback：平盘，pre_close=close
                        new_pct = 0.0
                        fixed_count += 1

                # 构造重新写入的行（只修改变动的字段，update_time 由 _ch_insert_rows 用当前时间填充）
                new_dict = dict(row_dict)
                new_dict["pre_close"] = new_pre
                new_dict["change_percent"] = new_pct
                new_dict["change_amount"] = new_amt
                new_dict.pop("update_time", None)  # 触发 _ch_insert_rows 使用 now_dt
                rows_to_insert.append(new_dict)

                if close_p is not None:
                    prev_close = close_p

            if fixed_count > 0:
                # Step 3: 先 DELETE 该 code 的所有旧数据
                self.ch_client.command(
                    f"ALTER TABLE {self.CH_TABLE} DELETE WHERE code = %(code)s",
                    parameters={"code": c},
                )
                # 等待 mutation 完成（超时 120s）
                for _ in range(120):
                    r_check = self.ch_client.query(
                        f"SELECT count() FROM {self.CH_TABLE} WHERE code = %(code)s",
                        parameters={"code": c},
                    )
                    if r_check.result_rows[0][0] == 0:
                        break
                    time.sleep(1)
                else:
                    print(f"  [WARN] {c}: DELETE mutation 超时，跳过")
                    continue

                # Step 4: 重新 INSERT 全量行（包含修复值）
                self._ch_insert_rows(rows_to_insert)
                total_fixed += fixed_count
                print(f"  [fix_change] {c}: 修复 {fixed_count} 个字段，DELETE 后重新写入 {len(rows_to_insert)} 条")

            time.sleep(0.01)

        return total_fixed

    def get_missing_pe_pb_dates(self, code):
        """
        获取某股票所有缺失 PE/PB 的交易日列表。
        用于将腾讯实时估值回填到所有缺失日期。

        返回: [trade_date_str, ...]
        """
        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT DISTINCT toString(trade_date) FROM {self.CH_TABLE} "
                f"WHERE code = %(code)s "
                f"AND (pe_ttm IS NULL OR pb IS NULL) "
                f"ORDER BY trade_date",
                parameters={"code": code},
            )
            return [row[0] for row in r.result_rows]
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT DISTINCT trade_date FROM stock_daily "
                    "WHERE code = %s "
                    "AND (pe_ttm IS NULL OR pb IS NULL) "
                    "ORDER BY trade_date",
                    (code,),
                )
                return [str(row["trade_date"]) for row in cur.fetchall()]

    def get_all_dates_for_code(self, code):
        """
        获取某股票在数据库中的所有交易日列表。
        用于腾讯快照值批量覆盖所有日期的估值字段。

        返回: [trade_date_str, ...]
        """
        # CH 表中代码不带前缀
        ch_code = code[2:] if len(code) > 2 and code[:2] in ("SH", "SZ", "BJ") else code

        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT toString(trade_date) FROM {self.CH_TABLE} "
                f"WHERE code = %(code)s ORDER BY trade_date",
                parameters={"code": ch_code},
            )
            return [row[0] for row in r.result_rows]
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT trade_date FROM stock_daily "
                    "WHERE code = %s ORDER BY trade_date",
                    (code,),
                )
                return [str(row["trade_date"]) for row in cur.fetchall()]

    def get_dates_for_codes(self, codes):
        """
        批量获取多只股票的所有交易日列表（一次DB请求）。

        返回: {code: [trade_date_str, ...], ...}
        """
        if not codes:
            return {}
        # CH 表中代码不带前缀，需要建立映射
        code_map = {}  # normalized_code -> original_code
        normalized_codes = []
        for c in codes:
            nc = c[2:] if len(c) > 2 and c[:2] in ("SH", "SZ", "BJ") else c
            code_map[nc] = c
            normalized_codes.append(nc)

        if self.backend == "clickhouse":
            placeholders = ", ".join(f"'{c}'" for c in normalized_codes)
            r = self.ch_client.query(
                f"SELECT code, toString(trade_date) as td FROM {self.CH_TABLE} "
                f"WHERE code IN ({placeholders}) ORDER BY code, trade_date"
            )
            result = {c: [] for c in codes}
            for ch_code, td in r.result_rows:
                original_code = code_map.get(ch_code, ch_code)
                result.setdefault(original_code, []).append(td)
            return result
        else:
            result = {c: [] for c in codes}
            with self.mysql_conn.cursor() as cur:
                placeholders = ", ".join(["%s"] * len(codes))
                cur.execute(
                    f"SELECT code, trade_date FROM stock_daily "
                    f"WHERE code IN ({placeholders}) ORDER BY code, trade_date",
                    codes,
                )
                for row in cur.fetchall():
                    result.setdefault(row["code"], []).append(str(row["trade_date"]))
            return result

    def get_pe_pb_missing_range(self, codes):
        """
        批量获取多只股票缺失 PE/PB 的日期范围（一次DB请求）。

        返回: {code: (min_date_str, max_date_str), ...}
        """
        if not codes:
            return {}
        # CH 表中代码不带前缀，需要建立映射
        code_map = {}  # normalized_code -> original_code
        normalized_codes = []
        for c in codes:
            nc = c[2:] if len(c) > 2 and c[:2] in ("SH", "SZ", "BJ") else c
            code_map[nc] = c
            normalized_codes.append(nc)

        if self.backend == "clickhouse":
            placeholders = ", ".join(f"'{c}'" for c in normalized_codes)
            r = self.ch_client.query(
                f"SELECT code, MIN(trade_date) as mn, MAX(trade_date) as mx "
                f"FROM {self.CH_TABLE} "
                f"WHERE code IN ({placeholders}) "
                f"AND (pe_ttm IS NULL OR pb IS NULL) "
                f"GROUP BY code"
            )
            result = {}
            for row in r.result_rows:
                if row[1]:
                    original_code = code_map.get(row[0], row[0])
                    result[original_code] = (str(row[1]), str(row[2]))
            return result
        else:
            result = {}
            with self.mysql_conn.cursor() as cur:
                placeholders = ", ".join(["%s"] * len(codes))
                cur.execute(
                    f"SELECT code, MIN(trade_date) as mn, MAX(trade_date) as mx "
                    f"FROM stock_daily "
                    f"WHERE code IN ({placeholders}) "
                    f"AND (pe_ttm IS NULL OR pb IS NULL) "
                    f"GROUP BY code",
                    codes,
                )
                for row in cur.fetchall():
                    if row[1]:
                        result[row["code"]] = (str(row["mn"]), str(row["mx"]))
            return result

    def get_codes_with_missing_pe_pb(self, trade_date):
        """
        获取指定日期缺失 PE 或 PB 的股票列表（用于 Baostock 历史补全）。

        返回: [(code, market), ...]
        market 从 stock_info 表获取。
        """
        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT DISTINCT d.code FROM {self.CH_TABLE} d "
                f"WHERE d.trade_date = %(td)s "
                f"AND (d.pe_ttm IS NULL OR d.pb IS NULL)",
                parameters={"td": trade_date}
            )
            codes = [row[0] for row in r.result_rows]
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT DISTINCT code FROM stock_daily "
                    "WHERE trade_date = %s AND (pe_ttm IS NULL OR pb IS NULL)",
                    (trade_date,)
                )
                codes = [row["code"] for row in cur.fetchall()]

        if not codes:
            return []
        # 获取 market
        market_map = self.get_market_for_codes(codes)
        return [(c, market_map.get(c, "SZ")) for c in codes]

    def get_all_codes_with_missing_pe_pb(self):
        """
        获取所有日期中任意一天缺失 PE 或 PB 的股票列表（不限日期，全量扫描）。
        排除指数数据（code 含 '.' 的如 sh.000001）。

        返回: [(code, market), ...]
        用于更新完成后对全量股票做补全。
        """
        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT DISTINCT code FROM {self.CH_TABLE} "
                f"WHERE (pe_ttm IS NULL OR pb IS NULL) "
                f"AND code NOT LIKE '%.%' "
                f"ORDER BY code"
            )
            codes = [row[0] for row in r.result_rows]
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT DISTINCT code FROM stock_daily "
                    "WHERE pe_ttm IS NULL OR pb IS NULL "
                    "AND code NOT LIKE '%.%' "
                    "ORDER BY code"
                )
                codes = [row["code"] for row in cur.fetchall()]

        if not codes:
            return []
        market_map = self.get_market_for_codes(codes)
        return [(c, market_map.get(c, "SZ")) for c in codes]

    def get_market_for_codes(self, codes):
        """批量获取股票 market (SH/SZ/BJ)，始终从 MySQL stock_info 查询"""
        if not codes:
            return {}
        # 无论后端是什么，stock_info 始终在 MySQL
        with self._info_cursor() as cur:
            placeholders = ", ".join(["%s"] * len(codes))
            cur.execute(
                f"SELECT code, market FROM stock_info WHERE code IN ({placeholders})",
                codes,
            )
            return {row["code"]: row["market"] for row in cur.fetchall()}

    # 注意：update_valuation 相关方法已删除，因为不再使用 market_cap 和 circ_market_cap 字段
    # 如需更新 pe_ttm 和 pb，请直接在日线数据写入时提供

    # ─── 数据概况 ──────────────────────────────────────────────

    def get_daily_stats(self):
        """获取 stock_daily 数据概况"""
        if self.backend == "clickhouse":
            r = self.ch_client.query(
                f"SELECT "
                f"  count() as total, "
                f"  count(DISTINCT code) as stocks, "
                f"  MIN(trade_date) as min_date, "
                f"  MAX(trade_date) as max_date "
                f"FROM {self.CH_TABLE}"
            )
            row = r.result_rows[0]
            return {
                "total": row[0],
                "stocks": row[1],
                "min_date": str(row[2]) if row[2] else None,
                "max_date": str(row[3]) if row[3] else None,
            }
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    "SELECT COUNT(*) as total, COUNT(DISTINCT code) as stocks, "
                    "MIN(trade_date) as min_date, MAX(trade_date) as max_date "
                    "FROM stock_daily"
                )
                return cur.fetchone()

    def get_field_coverage(self, since="2025-01-01"):
        """获取字段覆盖率统计"""
        if self.backend == "clickhouse":
            fields = [
                ("pre_close", "昨收"), ("change_percent", "涨跌幅"),
                ("change_amount", "涨跌额"), ("turnover_rate", "换手率"),
                ("pe_ttm", "市盈率TTM"), ("pb", "市净率"),
            ]
            r_total = self.ch_client.query(
                f"SELECT count() FROM {self.CH_TABLE} WHERE trade_date >= '{since}'"
            )
            total = r_total.result_rows[0][0]
            result = []
            for col, label in fields:
                r = self.ch_client.query(
                    f"SELECT count() FROM {self.CH_TABLE} "
                    f"WHERE trade_date >= '{since}' AND {col} IS NOT NULL AND {col} != 0"
                )
                cnt = r.result_rows[0][0]
                result.append((label, cnt, total))
            return result
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    f"SELECT COUNT(*) as total FROM stock_daily WHERE trade_date >= '{since}'"
                )
                total = cur.fetchone()["total"]
                fields = [
                    ("pre_close", "昨收"), ("change_percent", "涨跌幅"),
                    ("change_amount", "涨跌额"), ("turnover_rate", "换手率"),
                    ("pe_ttm", "市盈率TTM"), ("pb", "市净率"),
                ]
                result = []
                for col, label in fields:
                    cur.execute(
                        f"SELECT COUNT(*) as cnt FROM stock_daily "
                        f"WHERE trade_date >= '{since}' AND {col} IS NOT NULL AND {col} != 0"
                    )
                    cnt = cur.fetchone()["cnt"]
                    result.append((label, cnt, total))
                return result

    def get_codes_with_missing_pe_pb(self, trade_date):
        """
        获取指定交易日缺失 PE/PB 的股票列表。
        CH-only 模式: 直接从 CH 查当天有日线数据的 code，再筛缺失估值的。
        MySQL 模式: JOIN stock_info。
        """
        if self.backend == "clickhouse":
            # 直接从 CH 查当天所有 code（不依赖 MySQL stock_daily）
            r_all = self.ch_client.query(
                f"SELECT DISTINCT code FROM {self.CH_TABLE} "
                f"WHERE trade_date = toDate('{trade_date}') ORDER BY code"
            )
            all_codes = [row[0] for row in r_all.result_rows]

            if not all_codes:
                return []

            # 从 MySQL stock_info 查 market 信息（CH 无此表）
            with self._info_cursor() as cur:
                placeholders = ",".join(["%s"] * len(all_codes))
                cur.execute(
                    f"SELECT code, market FROM stock_info WHERE code IN ({placeholders})",
                    all_codes,
                )
                code_market = {r["code"]: r["market"] for r in cur.fetchall()}

            # 在 CH 中查缺失 PE/PB 的 code
            codes_str = ",".join(f"'{c}'" for c in all_codes)
            r = self.ch_client.query(
                f"SELECT DISTINCT code FROM {self.CH_TABLE} "
                f"WHERE trade_date = toDate('{trade_date}') "
                f"AND code IN ({codes_str}) "
                f"AND (pe_ttm IS NULL OR pb IS NULL) "
                f"ORDER BY code"
            )
            missing_codes = set(row[0] for row in r.result_rows)

            # 拼接结果（附带 market 信息）
            return [(c, code_market.get(c, "SZ")) for c in all_codes if c in missing_codes]
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    """SELECT DISTINCT sd.code, si.market
                       FROM stock_daily sd
                       JOIN stock_info si ON sd.code = si.code
                       WHERE sd.trade_date = %s
                       AND (sd.pe_ttm IS NULL OR sd.pb IS NULL)
                       ORDER BY sd.code""",
                    (trade_date,),
                )
                return [(r["code"], r["market"]) for r in cur.fetchall()]

    def get_codes_with_any_missing_pe_pb(self):
        """
        获取所有日线中有任意日期缺失 PE/PB 的股票（不限最新日期）。
        CH 模式: 从 CH 查有日线数据但任意字段为空的 code，再关联 stock_info 的 market。
        MySQL 模式: 从 stock_daily JOIN stock_info。
        """
        if self.backend == "clickhouse":
            # 查出所有有日线数据但 PE/PB 为空的 code（排除指数）
            r = self.ch_client.query(
                f"SELECT DISTINCT code FROM {self.CH_TABLE} "
                f"WHERE (pe_ttm IS NULL OR pb IS NULL) "
                f"  AND close_price IS NOT NULL AND close_price > 0 "
                f"  AND code NOT LIKE '%.%' "
                f"ORDER BY code"
            )
            all_codes = [row[0] for row in r.result_rows]
            if not all_codes:
                return []

            # 从 MySQL stock_info 查 market 信息
            with self._info_cursor() as cur:
                placeholders = ",".join(["%s"] * len(all_codes))
                cur.execute(
                    f"SELECT code, market FROM stock_info WHERE code IN ({placeholders})",
                    all_codes,
                )
                code_market = {row["code"]: row["market"] for row in cur.fetchall()}
            return [(c, code_market.get(c, "SZ")) for c in all_codes]
        else:
            with self.mysql_conn.cursor() as cur:
                cur.execute(
                    """SELECT DISTINCT sd.code, si.market
                       FROM stock_daily sd
                       JOIN stock_info si ON sd.code = si.code
                       WHERE (sd.pe_ttm IS NULL OR sd.pb IS NULL)
                         AND sd.close_price IS NOT NULL AND sd.close_price > 0
                       ORDER BY sd.code"""
                )
                return [(r["code"], r["market"]) for r in cur.fetchall()]


# ─── 工具函数 ──────────────────────────────────────────────────

def _chunk(lst, size):
    """列表分块"""
    for i in range(0, len(lst), size):
        yield lst[i : i + size]
