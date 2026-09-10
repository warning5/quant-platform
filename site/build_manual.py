#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把系统的使用手册（frontend/public/manual-full.html）生成站点版 site/manual.html，
额外注入一个“返回首页”浮动按钮 —— 手册本体没有顶栏，新窗口打开后无法回到官网。

用法：
    python build_manual.py
手册源文件更新后，重新执行本脚本即可（不要手工拷贝 manual-full.html，会丢失返回按钮）。
"""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT.parent / "frontend" / "public" / "manual-full.html"
DST = ROOT / "manual.html"

CSS = """
  /* 站点版注入：返回官网首页 */
  .home-fab { position: fixed; top: 12px; right: 16px; z-index: 1200; display: inline-flex; align-items: center; gap: 6px;
    padding: 7px 15px; border-radius: 999px; background: #fff; border: 1px solid #d9e2ec;
    box-shadow: 0 4px 14px rgba(16,40,80,.14); color: #1677ff; font-size: 13.5px; font-weight: 600; text-decoration: none; }
  .home-fab:hover { background: #1677ff; color: #fff; border-color: #1677ff; }
"""

HTML = '<a class="home-fab" href="index.html">\u2190 \u8fd4\u56de\u9996\u9875</a>\n'


def main():
    html = SRC.read_text(encoding="utf-8")
    if ".home-fab" not in html:
        html = html.replace("</style>", CSS + "</style>", 1)
    if 'class="home-fab"' not in html:
        html = html.replace("<body>", "<body>\n" + HTML, 1)
    DST.write_text(html, encoding="utf-8")
    print("generated %s (%.0f KB)" % (DST, DST.stat().st_size / 1024))


if __name__ == "__main__":
    main()
