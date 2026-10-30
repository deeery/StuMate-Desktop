"""预览截图：把 `dist` 拷成 `dist-preview/`、注入预览桥、用 Edge 无头模式截 5 页 × 2 主题。

为什么不用 `shoot_cdp.py`：那条链路驱动的是 release exe，而这台机器上**没有
Rust 工具链**（cargo/rustc 都不在），改了前端重建不了 exe。截图是改 UI 时的
唯一判断依据（Refactoring UI 的「看像素，别看源码」），不能因为环境缺件就跳过，
于是换成纯前端渲染这条链路：数据由 `tools/preview_bridge.js` 的 fixtures 提供，
样式与组件是真的、打包产物也是真的，只有后端是假的。

产物：`design-preview/preview/{theme}-{page}.png`（1200×800，与 acceptance 同尺寸）。

用法：
    python tools/shoot_preview.py              # 浅色 + 深色，5 页全截
    python tools/shoot_preview.py dark week    # 只截深色课表
"""
from __future__ import annotations

import functools
import http.server
import json
import os
import shutil
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cdp import CdpError, Session, find_page  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")
PREVIEW = os.path.join(ROOT, "dist-preview")
OUT = os.path.join(ROOT, "design-preview", "preview")
BRIDGE = os.path.join(ROOT, "tools", "preview_bridge.js")
EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
]
PAGES = ["today", "week", "notes", "knowledge", "settings"]
WIDTH, HEIGHT = 1200, 800


def prepare() -> None:
    """拷 dist → dist-preview，把预览桥塞进入口 script 之前。"""
    if not os.path.isdir(DIST):
        raise SystemExit("没有 dist/，先跑 npm run build")
    if os.path.isdir(PREVIEW):
        shutil.rmtree(PREVIEW)
    shutil.copytree(DIST, PREVIEW)
    shutil.copy2(BRIDGE, os.path.join(PREVIEW, "preview-bridge.js"))

    index_path = os.path.join(PREVIEW, "index.html")
    with open(index_path, "r", encoding="utf-8") as f:
        html = f.read()
    tag = '<script src="/preview-bridge.js"></script>'
    if tag in html:
        return
    # 入口脚本带 crossorigin，用它的开头定位比匹配整串更稳
    anchor = '<script type="module"'
    pos = html.find(anchor)
    if pos < 0:
        raise SystemExit("index.html 里找不到 module 入口脚本")
    html = html[:pos] + tag + "\n    " + html[pos:]
    with open(index_path, "w", encoding="utf-8") as f:
        f.write(html)


def free_port(start: int) -> int:
    port = start
    while port < start + 80:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return port
        port += 1
    raise SystemExit("找不到空闲端口")


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_args):  # 静音：不然每张图都要刷三行日志
        pass


def serve(port: int) -> socketserver.ThreadingTCPServer:
    handler = functools.partial(Quiet, directory=PREVIEW)
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", port), handler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def launch_browser(exe: str, port: int, profile: str) -> subprocess.Popen:
    args = [
        exe,
        "--headless=new",
        f"--remote-debugging-port={port}",
        f"--user-data-dir={profile}",
        f"--window-size={WIDTH},{HEIGHT}",
        "--hide-scrollbars",
        "--disable-extensions",
        "--no-first-run",
        "--no-default-browser-check",
        "about:blank",
    ]
    return subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def settle(session: Session, timeout: float = 12.0) -> None:
    """等 React 把页面画完：入口加载完 + 顶层标题出现。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            ready = session.eval(
                "(() => {"
                "  const root = document.querySelector('.page-title, .app-shell');"
                "  const nav = document.querySelectorAll('.nav-item').length;"
                "  return Boolean(root) && nav > 0;"
                "})()",
                await_promise=False,
            )
            if ready:
                time.sleep(0.6)  # 字体与过渡收尾
                return
        except CdpError:
            pass
        time.sleep(0.2)
    raise SystemExit("页面没画出来（React 没挂上？看 dist-preview/index.html 的桥有没有注入）")


def shoot(session: Session, page: str, theme: str, url_base: str, extra: str = "") -> str:
    url = f"{url_base}/index.html?page={page}&theme={theme}{extra}"
    session.call("Page.navigate", {"url": url})
    settle(session)
    if page == "knowledge":
        # 选中一条笔记，让编辑器与工具栏以「有内容」的状态入镜
        session.eval(
            "(() => {"
            "  const row = [...document.querySelectorAll('.kb-row')].find("
            "    (el) => el.textContent.includes('第一章 力学'));"
            "  if (row) row.dispatchEvent(new MouseEvent('click', { bubbles: true }));"
            "  return Boolean(row);"
            "})()",
            await_promise=False,
        )
        time.sleep(0.8)
    shot = session.call(
        "Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False}
    )
    import base64

    suffix = ""
    if extra:
        tag = "".join(ch if ch.isalnum() else "-" for ch in extra).strip("-")
        suffix = f"-{tag}"
    path = os.path.join(OUT, f"{theme}-{page}{suffix}.png")
    with open(path, "wb") as f:
        f.write(base64.b64decode(shot["data"]))
    return path


def main() -> None:
    args = sys.argv[1:]
    # 含 "=" 的参数当作额外的查询串（调参用，例如 wash=6% ），会带进截图文案里
    extras = [a for a in args if "=" in a]
    extra = "".join("&" + e for e in extras)
    args = [a for a in args if "=" not in a]
    themes = [t for t in args if t in ("light", "dark")] or ["light", "dark"]
    pages = [p for p in args if p in PAGES] or PAGES

    prepare()
    os.makedirs(OUT, exist_ok=True)

    exe = next((p for p in EDGE_CANDIDATES if os.path.exists(p)), None)
    if not exe:
        raise SystemExit("找不到 Edge/Chrome")

    http_port = free_port(5300)
    httpd = serve(http_port)
    url_base = f"http://127.0.0.1:{http_port}"

    cdp_port = free_port(9500)
    profile = tempfile.mkdtemp(prefix="stumate-preview-")
    proc = launch_browser(exe, cdp_port, profile)
    try:
        ws_url = find_page(cdp_port, timeout=25)
        session = Session(ws_url)
        session.call("Page.enable")
        session.call(
            "Emulation.setDeviceMetricsOverride",
            {
                "width": WIDTH,
                "height": HEIGHT,
                "deviceScaleFactor": 1,
                "mobile": False,
            },
        )
        for theme in themes:
            for page in pages:
                path = shoot(session, page, theme, url_base, extra)
                print("saved", os.path.relpath(path, ROOT))
        session.close()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
        httpd.shutdown()
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    main()
