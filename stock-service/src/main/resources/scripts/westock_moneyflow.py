"""
westock_moneyflow.py - 获取 A 股资金流向（主力/超大/大/中/小单），纯 westock 源

取数策略（优先 CLI，MCP 兜底）
------------------------------
1) westock-data CLI（默认，零鉴权）：
   `npx westock-data-clawhub@1.0.4 asfund <codes> --date YYYY-MM-DD`
   直连腾讯自选股公开接口，无需 OAuth / 连接器 / token，支持单日与历史日期。
   由 WESTOCK_NODE_BIN / WESTOCK_CLI_PKG 等环境变量控制；CLI 不可用或区间超过
   CLI_MAX_DAYS 时自动降级到方案 2。
2) westock-mcp（兜底，需 token）：远程 MCP `data_fund_flow`，支持历史区间，
   使用 westock-mcp 连接器 / 方式B OAuth 落盘的 Bearer token。

鉴权（仅 MCP 兜底路径需要）
--------------------------
- 优先读环境变量 WESTOCK_MCP_TOKEN（覆盖）。
- 否则读 WESTOCK_TOKEN_FILE 或 ~/.quant-platform/westock_token.json（方式 B）。
- 或读 ~/.workbuddy/credentials/westock-mcp/token.json（方式 A：WorkBuddy 连接器）。
- 过期时尝试用 refresh_token 刷新并写回。

字段映射（CLI 与 MCP 统一）
  ClosePrice/closePrice -> close
  MainNetFlow/mainNetFlow -> net_main（主力净流入，元）
  JumboNetFlow/jumboNetFlow -> net_huge（超大单）
  BlockNetFlow/blockNetFlow -> net_big（大单）
  MidNetFlow/midNetFlow -> net_medium（中单）
  SmallNetFlow/smallNetFlow -> net_small（小单）
  net_main_pct 由 MainInFlow/MainOutFlow 推算（缺失则为 0）

输出格式（供调用方无感复用）
  { WS_CODE_UPPER: { "YYYYMMDD": {close,net_main,net_main_pct,net_huge,net_big,net_medium,net_small} } }
"""

import json
import os
import re
import shutil
import subprocess
import time
import datetime
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path
from datetime import timedelta


# ── 远程 MCP 服务配置 ─────────────────────────────────────────────
WESTOCK_MCP_URL = "https://stockbuddy.qq.com/cgi/cgi-bin/openai/mcp/mcp"
WESTOCK_TOKEN_ENDPOINT = "https://stockbuddy.qq.com/cgi/cgi-bin/openai/mcp/oauth/token"
WESTOCK_AUTH_SERVER = "https://stockbuddy.qq.com/"
CRED_DIR = Path.home() / ".workbuddy" / "credentials" / "westock-mcp"
# 方式 B 默认落盘位置（westock_oauth.py 输出）；可被 WESTOCK_TOKEN_FILE 覆盖
METHOD_B_TOKEN_FILE = Path.home() / ".quant-platform" / "westock_token.json"

PROTOCOL_VERSION = "2024-11-05"
HTTP_TIMEOUT = 30

# ── westock-data CLI 配置（零鉴权、直连腾讯自选股公开接口）────────
WESTOCK_CLI_PKG = os.environ.get("WESTOCK_CLI_PKG", "westock-data-clawhub@1.0.4")
CLI_MAX_DAYS = int(os.environ.get("WESTOCK_CLI_MAX_DAYS", "45"))   # 区间交易日数超过则降级 MCP
CLI_SUBBATCH = int(os.environ.get("WESTOCK_CLI_SUBBATCH", "30"))  # 单次 asfund 最多 codes 数
CLI_TIMEOUT = int(os.environ.get("WESTOCK_CLI_TIMEOUT", "120"))   # 单次 CLI 调用超时（秒）


class WestockMcpError(Exception):
    """westock-mcp 鉴权/调用层面的错误（区别于网络瞬时错误）"""


# ── 工具函数 ─────────────────────────────────────────────────────
def to_float(v) -> float:
    if v is None:
        return 0.0
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip().replace(",", "")
    if not s or s == "-":
        return 0.0
    try:
        return float(s)
    except ValueError:
        return 0.0


def _parse_date(s):
    if isinstance(s, datetime.date):
        return s
    if s:
        try:
            return datetime.datetime.strptime(str(s)[:10], "%Y-%m-%d").date()
        except ValueError:
            return None
    return None


def _looks_like_code(k: str) -> bool:
    return bool(re.match(r"^[a-zA-Z]{2}\d{6}$", k or ""))


# ── Token 读取与刷新 ────────────────────────────────────────────
def _load_token():
    """返回 (access_token, has_refresh)。无 token 抛 WestockMcpError。

    优先级：
      1) WESTOCK_MCP_TOKEN 环境变量（直接覆盖，无需文件）
      2) WESTOCK_TOKEN_FILE 指定的文件（方式 B 显式路径）
      3) ~/.quant-platform/westock_token.json（方式 B 默认，westock_oauth.py 落盘）
      4) ~/.workbuddy/credentials/westock-mcp/token.json（方式 A：WorkBuddy 连接器落盘）
    """
    env = os.environ.get("WESTOCK_MCP_TOKEN")
    if env and env.strip():
        return env.strip(), False

    explicit = os.environ.get("WESTOCK_TOKEN_FILE")
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    candidates.append(METHOD_B_TOKEN_FILE)
    candidates.append(CRED_DIR / "token.json")

    for token_path in candidates:
        if not token_path.exists():
            continue
        try:
            d = json.loads(token_path.read_text(encoding="utf-8"))
        except Exception:
            continue  # 试下一个候选
        access = d.get("access_token") or d.get("accessToken") or d.get("token")
        refresh = d.get("refresh_token") or d.get("refreshToken")
        exp = d.get("expires_at") or d.get("expiresAt") or d.get("expiry")
        if exp is not None and _is_expired(exp):
            if not refresh:
                continue  # 过期且无刷新能力，试下一个候选
            try:
                access = _refresh_token(refresh, d, token_path)
            except WestockMcpError:
                continue
        if access:
            return access, bool(refresh)

    raise WestockMcpError(
        "未找到可用的 westock token。可选任一方式获取：\n"
        "  方式A：在 WorkBuddy 连接 westock-mcp 连接器（腾讯 OAuth 授权，自动落盘）；\n"
        "  方式B：运行 westock_oauth.py 自行注册 OAuth 客户端并登录；\n"
        "  或直接设置环境变量 WESTOCK_MCP_TOKEN=<access_token>。"
    )


def _is_expired(exp) -> bool:
    try:
        if isinstance(exp, (int, float)):
            return time.time() > float(exp)
        s = str(exp).strip()
        if s.isdigit():
            return time.time() > float(s)
        dt = datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))
        return datetime.datetime.now(datetime.timezone.utc) > dt
    except Exception:
        return False


def _refresh_token(refresh: str, d: dict, token_path: Path) -> str:
    # token_endpoint_auth_methods_supported: ["none"] -> 公开客户端，无 client_secret
    body = urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": refresh,
    }).encode("utf-8")
    req = urllib.request.Request(
        WESTOCK_TOKEN_ENDPOINT, data=body, method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
            tok = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        raise WestockMcpError("刷新 westock token 失败: %s" % e)

    new_access = tok.get("access_token") or tok.get("accessToken")
    if not new_access:
        raise WestockMcpError("刷新 westock token 返回中无 access_token。")
    d["access_token"] = new_access
    if tok.get("refresh_token"):
        d["refresh_token"] = tok["refresh_token"]
    if tok.get("expires_in"):
        d["expires_at"] = int(time.time()) + int(tok["expires_in"])
    try:
        token_path.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception:
        pass
    return new_access


# ── MCP streamable-HTTP 客户端 ──────────────────────────────────
class _McpClient:
    def __init__(self, url: str, token: str):
        self.url = url
        self.token = token
        self.session_id = None
        self._id = 0

    def _post(self, payload: dict):
        data = json.dumps(payload).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": "Bearer %s" % self.token,
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        req = urllib.request.Request(self.url, data=data, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
                sid = r.headers.get("Mcp-Session-Id")
                if sid:
                    self.session_id = sid
                ctype = r.headers.get("Content-Type", "")
                raw = r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise WestockMcpError("westock 鉴权失败（%s）：%s" % (e.code, e.read().decode("utf-8", "ignore")[:200]))
            raise
        if "text/event-stream" in ctype:
            return _parse_sse(raw)
        if not raw.strip():
            return None
        return json.loads(raw)

    def initialize(self):
        self._id += 1
        resp = self._post({
            "jsonrpc": "2.0", "id": self._id, "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "quant-moneyflow", "version": "1.0"},
            },
        })
        # 发送 initialized 通知（无响应体，忽略异常）
        try:
            self._post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        except Exception:
            pass
        return resp

    def call_tool(self, name: str, arguments: dict):
        self._id += 1
        return self._post({
            "jsonrpc": "2.0", "id": self._id, "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        })


def _parse_sse(text: str):
    out = None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            data = line[len("data:"):].strip()
            if data and data != "[DONE]":
                try:
                    out = json.loads(data)
                except Exception:
                    pass
    return out


_CLIENT = None


def _get_client():
    global _CLIENT
    if _CLIENT is None:
        token, _ = _load_token()
        _CLIENT = _McpClient(WESTOCK_MCP_URL, token)
        _CLIENT.initialize()
    return _CLIENT


def _reset_client():
    global _CLIENT
    _CLIENT = None


def _call_data_fund_flow(code: str, start, end):
    last_err = None
    for attempt in range(2):  # 首次 + 鉴权/会话失效后重建一次
        try:
            client = _get_client()
            args = {"code": code}
            if start and end:
                args["start"] = start.strftime("%Y-%m-%d")
                args["end"] = end.strftime("%Y-%m-%d")
            return client.call_tool("data_fund_flow", args)
        except WestockMcpError as e:
            last_err = e
            _reset_client()
            # 重新加载 token（可能已刷新）后重试
            try:
                _load_token()
            except Exception:
                pass
    raise last_err or WestockMcpError("data_fund_flow 调用失败")


# ── 响应解析 ────────────────────────────────────────────────────
def _unwrap(resp):
    """从 tools/call 的信封里取出 {ok,data,message}"""
    if not isinstance(resp, dict):
        raise WestockMcpError("westock 返回非预期结构: %r" % (resp,))
    result = resp.get("result")
    if result is None:
        err = resp.get("error")
        raise WestockMcpError("westock 返回错误: %r" % (err,))
    # 标准 MCP：content 列表里取 text
    if isinstance(result, dict):
        if result.get("isError"):
            # content 里可能有错误信息
            txt = _content_text(result)
            raise WestockMcpError("data_fund_flow 业务错误: %s" % (txt or result))
        txt = _content_text(result)
        if txt:
            try:
                payload = json.loads(txt)
                if isinstance(payload, dict) and ("ok" in payload or "data" in payload):
                    return payload
            except Exception:
                pass
            # 直接就是 data（某些实现不包 ok）
            try:
                return {"ok": True, "data": json.loads(txt)}
            except Exception:
                return {"ok": True, "data": txt}
    return {"ok": True, "data": result}


def _content_text(result: dict):
    content = result.get("content")
    if isinstance(content, list):
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                return item.get("text", "")
    return ""


def _add_record(out: dict, rec, default_code: str):
    if not isinstance(rec, dict):
        return
    low = {k.lower(): v for k, v in rec.items()}
    code = str(rec.get("code") or rec.get("Code") or default_code or "").upper()
    date_raw = rec.get("date") or rec.get("Date") or low.get("date") or low.get("tradedate")
    if not date_raw:
        return
    m = re.match(r"^(\d{4})[-/]?(\d{2})[-/]?(\d{2})", str(date_raw))
    if not m:
        return
    td = "%s%s%s" % (m.group(1), m.group(2), m.group(3))

    net_main = to_float(low.get("mainnetflow", low.get("main")))
    main_in = to_float(low.get("maininflow"))
    main_out = to_float(low.get("mainoutflow"))
    denom = main_in + main_out
    net_main_pct = round(net_main / denom * 100, 2) if denom > 0 else 0.0

    vals = {
        "close":       to_float(low.get("closeprice", low.get("close"))),
        "net_main":    net_main,
        "net_main_pct": net_main_pct,
        "net_huge":    to_float(low.get("jumbonetflow")),
        "net_big":     to_float(low.get("blocknetflow")),
        "net_medium":  to_float(low.get("midnetflow")),
        "net_small":   to_float(low.get("smallnetflow")),
    }
    out.setdefault(code, {})[td] = vals


def _normalize_data(data, default_code: str) -> dict:
    """把 data_fund_flow 的 data 归一化为 {WS_UPPER: {YYYYMMDD: vals}}"""
    records = []
    if isinstance(data, list):
        records = data
    elif isinstance(data, dict):
        # 可能直接是按代码分组的 dict
        if any(_looks_like_code(k) for k in data.keys()):
            out = {}
            for k, v in data.items():
                if isinstance(v, list):
                    for rec in v:
                        _add_record(out, rec, k)
            return out
        # 否则从常见列表字段里取
        for key in ("list", "records", "items", "data", "result"):
            if isinstance(data.get(key), list):
                records = data[key]
                break
        if not records:
            records = [data]  # 单条记录
    out = {}
    for rec in records:
        _add_record(out, rec, default_code)
    return out


def _parse_fund_flow_response(resp, code: str) -> dict:
    payload = _unwrap(resp)
    if payload.get("ok") is False:
        raise WestockMcpError("data_fund_flow: %s" % payload.get("message", "unknown error"))
    data = payload.get("data")
    return _normalize_data(data, (code or "").upper())


# ── 对外接口 ────────────────────────────────────────────────────
def query_westock(codes: list, start_str: str, end_str: str) -> dict:
    """
    获取 A 股资金流向（主力/超大/大/中/小单），纯 westock 源。

    策略：优先 westock-data CLI（零鉴权，直连腾讯自选股）；CLI 不可用或区间
    交易日数超过 CLI_MAX_DAYS 时，降级到 westock-mcp data_fund_flow（需 token）。

    codes: ["sh600619", "sz000001"]  westock 代码
    start_str, end_str: "YYYY-MM-DD"
    返回: { WS_CODE_UPPER: { "YYYYMMDD": {close,net_main,net_main_pct,net_huge,net_big,net_medium,net_small} } }
    """
    if not codes:
        return {}
    start = _parse_date(start_str)
    end = _parse_date(end_str)
    days = (end - start).days if (start and end) else 0

    cli = None
    if days <= CLI_MAX_DAYS:
        try:
            cli = _detect_cli()
        except Exception:
            cli = None
    if cli:
        try:
            return _query_cli(codes, start, end, cli)
        except WestockMcpError as e:
            print("  [westock] CLI 全部失败，降级 MCP: %s" % e)
        except Exception as e:
            print("  [westock] CLI 异常，降级 MCP: %s" % e)
    return _query_mcp(codes, start, end)


def _query_mcp(codes: list, start, end) -> dict:
    """
    westock-mcp data_fund_flow 兜底路径（需 token）。历史区间仅单 code 生效，
    这里按单只代码逐只查询并合并。失败的单只会被跳过并打印 WARN。
    """
    merged = {}
    failed = []
    for code in codes:
        try:
            resp = _call_data_fund_flow(code, start, end)
            part = _parse_fund_flow_response(resp, code)
            for k, v in part.items():
                merged.setdefault(k, {}).update(v)
        except Exception as e:
            failed.append(code)
            print("  [westock WARN] %s 查询失败: %s" % (code, e))
    if failed and not merged:
        pass
    return merged


def extract_westock_moneyflow(md_text):
    """
    兼容旧调用方：输入已是 dict（query_westock 现直接返回解析后 dict）时原样返回；
    若为 JSON 字符串则解析；其余返回 {}。
    """
    if isinstance(md_text, dict):
        return md_text
    if isinstance(md_text, str):
        s = md_text.strip()
        if s.startswith("{"):
            try:
                return json.loads(s)
            except Exception:
                return {}
    return {}


# ── westock-data CLI 实现（零鉴权，直连腾讯自选股）──────────────
class _CliError(Exception):
    """westock-data CLI 单次调用层面的错误（区别于网络瞬时错误）"""


_CLI_CACHE = None  # (node_exe, npx_js) 或 None


def _detect_cli_impl():
    """探测可用的 node + npx-cli.js，返回 (node_exe, npx_js) 或 None。"""
    candidates = []
    nb = os.environ.get("WESTOCK_NODE_BIN")
    if nb:
        candidates.append(nb)
    node_which = shutil.which("node")
    if node_which:
        candidates.append(os.path.dirname(node_which))
    # 常见 managed / 系统路径
    candidates.append(r"C:/Users/warning5/.workbuddy/binaries/node/versions/22.22.2")
    candidates.append(r"D:/Program Files/nodejs")
    for d in candidates:
        if not d or not os.path.isdir(d):
            continue
        node_exe = os.path.join(d, "node.exe") if os.name == "nt" else os.path.join(d, "node")
        npx_js = os.path.join(d, "node_modules", "npm", "bin", "npx-cli.js")
        if os.path.isfile(node_exe) and os.path.isfile(npx_js):
            return (node_exe, npx_js)
    # fallback：PATH 中的 npx 命令（Windows 下多为 npx.cmd，shell 方式更稳）
    npx_which = shutil.which("npx")
    if node_which and npx_which:
        return (node_which, npx_which)
    return None


def _detect_cli():
    global _CLI_CACHE
    if _CLI_CACHE is not None:
        return _CLI_CACHE
    found = _detect_cli_impl()
    _CLI_CACHE = found
    return found


def westock_cli_supported() -> bool:
    """当前环境是否能用 westock-data CLI（零鉴权）。"""
    return _detect_cli() is not None


def _parse_asfund_md(text: str) -> dict:
    """解析 westock-data asfund 输出的 markdown 表格。

    单只表头：| code | BlockNetFlow | ... | EndDate | ... |
    批量表头：| symbol | code | ... |（多一列 symbol）
    返回: { WS_CODE_UPPER: { "YYYYMMDD": {close,net_main,net_main_pct,net_huge,net_big,net_medium,net_small} } }
    """
    lines = [l.rstrip() for l in text.splitlines()]
    header = None
    hidx = -1
    for i, l in enumerate(lines):
        if l.strip().startswith("|") and "MainNetFlow" in l:
            header = l
            hidx = i
            break
    if header is None:
        return {}
    cols = [c.strip() for c in header.strip().strip("|").split("|")]
    out = {}
    for l in lines[hidx + 2:]:
        l = l.strip()
        if not l.startswith("|"):
            continue
        if l.startswith("| ---") or set(l) <= set("|- "):
            continue
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        if len(cells) < len(cols):
            continue
        rec = dict(zip(cols, cells))
        code = (rec.get("code") or rec.get("symbol") or "").upper()
        if not _looks_like_code(code):
            continue
        date_raw = rec.get("EndDate") or rec.get("endDate") or ""
        m = re.match(r"^(\d{4})[-/]?(\d{2})[-/]?(\d{2})", date_raw)
        if not m:
            continue
        td = "%s%s%s" % (m.group(1), m.group(2), m.group(3))
        main_in = to_float(rec.get("MainInFlow"))
        main_out = to_float(rec.get("MainOutFlow"))
        net_main = to_float(rec.get("MainNetFlow"))
        denom = main_in + main_out
        pct = round(net_main / denom * 100, 2) if denom > 0 else 0.0
        vals = {
            "close":       to_float(rec.get("ClosePrice") or rec.get("closePrice")),
            "net_main":    net_main,
            "net_main_pct": pct,
            "net_huge":    to_float(rec.get("JumboNetFlow")),
            "net_big":     to_float(rec.get("BlockNetFlow")),
            "net_medium":  to_float(rec.get("MidNetFlow")),
            "net_small":   to_float(rec.get("SmallNetFlow")),
        }
        out.setdefault(code, {})[td] = vals
    return out


def _kill_tree(proc):
    """终止整个进程树（含 npx 拉起的孤儿孙子进程），避免管道被霸占死锁。"""
    pid = proc.pid
    try:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/F", "/T", "/PID", str(pid)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
            )
        else:
            import signal as _sig
            os.killpg(os.getpgid(pid), _sig.SIGKILL)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def _cli_fetch_day(codes, day, cli) -> dict:
    """调用 `asfund <codes> --date <day>`，返回解析后的 dict（单日）。

    npx 内部会以 cmd /c 方式再 spawn 包二进制，因此必须把 node 所在目录
    注入子进程 PATH，否则在精简 PATH（如 Java 子进程环境）下会报
    “不是内部或外部命令”而失败。
    另：用 Popen + 手动超时终止整棵进程树，防止孙子进程孤儿化后霸占 stdout
    管道导致 subprocess 永久挂死（曾导致全量任务卡在第一批 8 分钟零进度）。
    """
    node_exe, npx_js = cli
    csv = ",".join(sorted(set(codes)))
    date_str = day.strftime("%Y-%m-%d")
    cmd = [node_exe, npx_js, "-y", WESTOCK_CLI_PKG, "asfund", csv, "--date", date_str]
    env = dict(os.environ)
    node_dir = os.path.dirname(node_exe)
    env["PATH"] = node_dir + os.pathsep + env.get("PATH", "")
    creationflags = 0
    if os.name == "nt":
        creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    try:
        proc = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env, creationflags=creationflags,
        )
    except Exception as e:
        raise _CliError("subprocess 启动失败: %s" % e)
    try:
        out, err = proc.communicate(timeout=CLI_TIMEOUT)
    except subprocess.TimeoutExpired:
        _kill_tree(proc)
        try:
            out, err = proc.communicate(timeout=5)
        except Exception:
            out, err = b"", b""
        raise _CliError("asfund 超时(%ds)，已终止进程树" % CLI_TIMEOUT)
    except Exception as e:
        _kill_tree(proc)
        raise _CliError("subprocess 调用失败: %s" % e)
    if proc.returncode != 0:
        err_txt = (err or out or b"").decode("utf-8", "replace")[:500]
        raise _CliError("asfund 退出码 %d: %s" % (proc.returncode, err_txt))
    return _parse_asfund_md(out.decode("utf-8", "replace"))


def _query_cli(codes, start, end, cli) -> dict:
    """按自然日循环，每天一次批量 asfund（按 CLI_SUBBATCH 再分组），
    合并为 {WS_UPPER:{YYYYMMDD:vals}}。全部交易日都失败才抛错（部分成功/空日正常返回）。
    """
    merged = {}
    total = 0
    err_days = 0
    groups = [codes[i:i + CLI_SUBBATCH] for i in range(0, len(codes), CLI_SUBBATCH)] or [codes]
    cur = start
    while cur <= end:
        if cur.weekday() >= 5:  # 跳过周末
            cur += timedelta(days=1)
            continue
        total += 1
        day_err = 0
        for g in groups:
            try:
                part = _cli_fetch_day(g, cur, cli)
                for k, v in part.items():
                    merged.setdefault(k, {}).update(v)
            except _CliError as e:
                day_err += 1
                print("  [westock CLI] %s 取数失败: %s" % (cur, e))
        if day_err >= len(groups):
            err_days += 1
        cur += timedelta(days=1)
    if total > 0 and err_days == total:
        raise WestockMcpError("westock-data CLI 在 %d 个交易日内全部取数失败" % total)
    return merged
