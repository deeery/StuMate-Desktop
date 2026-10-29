# -*- coding: utf-8 -*-
"""验收：关掉 Markdowner 子窗口时，主窗口与应用进程必须都还活着。

背景（v1.7.10）：`.on_window_event` 是应用级回调，`tray::handle_close_request`
是给主窗口写的策略。之前没按 label 分流，导致子窗口的 × 会走主窗口那套
（收托盘 / 退出应用），表现为「关子窗口 = 整个应用没了」。

这条脚本走的是**真实关闭路径**：给子窗口的 HWND 发 WM_CLOSE，
和用户点 × 或按 Alt+F4 完全同一条消息，不经过页面里的任何脚本。

前置：
    STUMATE_LITE_BROWSER_ARGS="--remote-debugging-port=9333"
    （不传就用默认参数，本脚本连不上 CDP 会直接报错退出）
"""

import ctypes
import ctypes.wintypes as wt
import json
import os
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp  # noqa: E402

PORT = 9333
MAIN_TITLE = "StuMate Lite"
SUB_PREFIX = "Markdowner"


def targets():
    raw = urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list", timeout=5).read()
    return json.loads(raw)


def app_pid():
    out = subprocess.run(
        ["tasklist", "/FI", "IMAGENAME eq stumate-lite.exe", "/FO", "CSV", "/NH"],
        capture_output=True, text=True, encoding="gbk", errors="replace",
    ).stdout
    for line in out.splitlines():
        cells = [c.strip('"') for c in line.split('","')]
        if cells and cells[0].lower() == "stumate-lite.exe":
            return int(cells[1])
    return None


def windows_of(pid):
    """枚举该进程的可见顶层窗口 -> [(hwnd, title)]。"""
    u = ctypes.windll.user32
    found = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)

    def cb(h, _):
        p = wt.DWORD()
        u.GetWindowThreadProcessId(h, ctypes.byref(p))
        if p.value == pid and u.IsWindowVisible(h):
            n = u.GetWindowTextLengthW(h)
            buf = ctypes.create_unicode_buffer(n + 1)
            u.GetWindowTextW(h, buf, n + 1)
            found.append((h, buf.value))
        return True

    u.EnumWindows(proc(cb), 0)
    return found


def main():
    pid = app_pid()
    assert pid, "应用没在跑"

    ts = targets()
    main_t = [t for t in ts if t.get("title") == MAIN_TITLE]
    assert main_t, f"CDP 里找不到主窗口：{[t.get('title') for t in ts]}"
    s = cdp.Session(main_t[0]["webSocketDebuggerUrl"])

    # 直接调命令开窗，绕开「要先点进知识库页选一篇」的交互。
    base = os.path.join(os.environ["APPDATA"], "StuMate")
    rel = "大学物理/例题/斜面滑块.md"
    s.eval("window.__TAURI_INTERNALS__.invoke('open_markdown_window', "
           f"{{ base: {json.dumps(base)}, rel: {json.dumps(rel)} }})")
    time.sleep(4)

    subs = [t for t in targets() if t.get("title") != MAIN_TITLE]
    print(f"子窗口 CDP target: {[(t.get('title'), t.get('url')[:50]) for t in subs]}")
    assert subs, "子窗口没建出来"

    wins = windows_of(pid)
    print(f"进程窗口: {[(hex(h), t) for h, t in wins]}")
    sub_win = [(h, t) for h, t in wins if t.startswith(SUB_PREFIX)]
    main_win = [(h, t) for h, t in wins if t == MAIN_TITLE]
    assert sub_win, f"没找到子窗口 HWND：{wins}"
    assert main_win, f"没找到主窗口 HWND：{wins}"

    # ── 真实关闭：给子窗口发 WM_CLOSE（等同点 × / Alt+F4）───────────
    hwnd = sub_win[0][0]
    WM_CLOSE = 0x0010
    ctypes.windll.user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
    time.sleep(3)

    still_pid = app_pid()
    alive = still_pid == pid
    wins_after = windows_of(pid) if alive else []
    titles_after = [t for _, t in wins_after]

    print("\n── 结果 ──")
    print(f"进程存活        : {alive}")
    print(f"关闭后可见窗口  : {titles_after}")

    ok = True
    if not alive:
        print("✗ 关子窗口把应用进程带走了")
        ok = False
    if MAIN_TITLE not in titles_after:
        print("✗ 主窗口不见了")
        ok = False
    if any(t.startswith(SUB_PREFIX) for t in titles_after):
        print("✗ 子窗口没关掉")
        ok = False

    if ok:
        # 主窗口还要能响应，不能是「窗口在、页面死了」
        ts2 = targets()
        mt = [t for t in ts2 if t.get("title") == MAIN_TITLE][0]
        s2 = cdp.Session(mt["webSocketDebuggerUrl"])
        print("主窗口 eval 1+1 =", s2.eval("1+1"))
        s2.close()
        print("✓ 子窗口独立关闭，主窗口与应用均存活")

    s.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
