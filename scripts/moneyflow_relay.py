#!/usr/bin/env python3
"""
东方财富资金流 API 中继服务器
部署在能访问 push2.eastmoney.com 的云服务器上
"""

from flask import Flask, request, Response
import requests

app = Flask(__name__)

UPSTREAM_CLIST = "https://push2.eastmoney.com"       # 实时资金流
UPSTREAM_FFLOW = "https://push2.eastmoney.com"        # 历史资金流（push2his 也被墙，改用 push2）

# 简单 token 鉴权，防止被扫到后滥用
AUTH_TOKEN = "95508b664a61ff632843c2d25ef6dfcb"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://www.eastmoney.com/",
    "Accept": "*/*",
    "Accept-Language": "zh-CN,zh;q=0.9",
}


@app.route("/api/qt/clist/get", methods=["GET"])
def relay_clist():
    """资金流实时数据"""
    return _forward(UPSTREAM_CLIST, request.args)


@app.route("/api/qt/stock/fflow/daykline/get", methods=["GET"])
def relay_fflow():
    """个股资金流 K 线（历史）"""
    return _forward(UPSTREAM_FFLOW, request.args)


@app.route("/health", methods=["GET"])
def health():
    return {"status": "ok", "upstreams": [UPSTREAM_CLIST, UPSTREAM_FFLOW]}


def _forward(upstream, params):
    # 鉴权
    if request.args.get("token") != AUTH_TOKEN:
        return Response('{"error":"unauthorized"}', status=403,
                        content_type="application/json")

    # 构造上游请求
    url = f"{upstream}{request.path}"
    clean_params = {k: v for k, v in params.items() if k != "token"}

    try:
        resp = requests.get(url, params=clean_params, headers=HEADERS,
                            timeout=30, verify=True)
        return Response(resp.content, status=resp.status_code,
                        content_type=resp.headers.get("Content-Type", "application/json"))
    except requests.RequestException as e:
        return Response(f'{{"error":"{str(e)}"}}', status=502,
                        content_type="application/json")


if __name__ == "__main__":
    # 云服务器上 bind 0.0.0.0 供外部访问
    # 生产环境建议前面套一层 nginx 做 HTTPS
    app.run(host="0.0.0.0", port=58080, debug=False)
