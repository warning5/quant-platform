#!/usr/bin/env python3
"""财务数据采集进度监控 API 服务"""
import json
import pymysql
from http.server import HTTPServer, SimpleHTTPRequestHandler
import urllib.parse
import os

DB_CONFIG = {
    'host': 'localhost', 'port': 3306,
    'user': 'root', 'password': '123456',
    'database': 'stock', 'charset': 'utf8mb4',
}

class ProgressHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == '/api/progress':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.end_headers()
            data = self.get_progress()
            self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))
        else:
            super().do_GET()

    def get_progress(self):
        conn = pymysql.connect(**DB_CONFIG)
        cur = conn.cursor()
        tables = ['stock_income', 'stock_balance', 'stock_cashflow', 'stock_financial_indicator']
        table_data = {}
        total_records = 0
        total_stocks = 0
        for t in tables:
            cur.execute(f"SELECT COUNT(*), COUNT(DISTINCT code) FROM {t}")
            count, stocks = cur.fetchone()
            total_records += count
            total_stocks = max(total_stocks, stocks)
            table_data[t] = {'records': count, 'stocks': stocks}

        # Determine current step
        step = 0
        step_text = "等待开始"
        if table_data['stock_financial_indicator']['records'] > 0:
            step = 1
            step_text = "Step 1: 东方财富业绩报表"
        if table_data['stock_income']['stocks'] > 1:
            step = 2
            step_text = f"Step 2: 同花顺摘要 ({table_data['stock_income']['stocks']}/5490)"
        if table_data['stock_balance']['stocks'] > 1:
            step = 3
            step_text = f"Step 3: 新浪三大表 ({table_data['stock_balance']['stocks']}/5490)"

        # Check if done
        cur.execute("SELECT COUNT(*) FROM stock_info")
        total_stocks_target = cur.fetchone()[0]
        done = (table_data['stock_balance']['stocks'] >= total_stocks_target - 100
                and table_data['stock_income']['stocks'] >= total_stocks_target - 100)

        if done:
            step_text = "全部完成 ✓"

        conn.close()
        return {
            'step': step,
            'step_text': step_text,
            'total_records': total_records,
            'total_stocks': total_stocks,
            'target_stocks': total_stocks_target,
            'tables': table_data,
            'done': done,
        }

    def log_message(self, format, *args):
        pass  # suppress logs

if __name__ == '__main__':
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    server = HTTPServer(('0.0.0.0', 8899), ProgressHandler)
    print("进度监控服务已启动: http://localhost:8899")
    server.serve_forever()
