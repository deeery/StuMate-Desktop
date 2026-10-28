"""用 CDP 截页面（而不是抓窗口像素）。

为什么不沿用 `shoot_all.py` 的窗口抓取：这台机器上窗口会在启动约 2 秒后自己变成
「最小化」（`IsIconic` 为真、位置 -32000,-32000），客户区随之变成 144×19，
抓到的就是一张 144×19 的黑图。`ShowWindow(SW_RESTORE)` 也压不住。
而同一时刻页面本身完全正常（CDP 里能查到完整的 DOM 与尺寸），所以走
`Page.captureScreenshot` 直接拿渲染结果 —— 顺带还少了「DWM 阴影把邻居窗口
漏进截图」这类问题。

用法：
  python tools/shoot_cdp.py                       # 深色 + 浅色，全部页面
  python tools/shoot_cdp.py dark knowledge        # 只截一张
"""
from __future__ import annotations

import base64
import os
import pathlib
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXE = ROOT / "src-tauri" / "target" / "release" / "stumate-lite.exe"
OUT = ROOT / "design-preview" / "acceptance"

# (环境变量里的 page, 输出名)
PAGES = [
    ("today", "today"),
    ("week", "week"),
    ("notes", "notes"),
    ("knowledge", "knowledge"),
    ("settings", "settings"),
]

_port = 9400


# 截图前的准备脚本：让「知识库」这一页带着一篇打开的笔记入镜。
# 空状态一张图说明不了编辑器长什么样，而验收图的主要用途正是给人看排版。
PREP = {
    "knowledge": """
      (() => {
        const row = [...document.querySelectorAll('.kb-row')]
          .find((r) => r.textContent.trim() === '第一章 力学');
        if (row) row.click();
        return !!row;
      })()
    """,
}


def shoot(theme: str, page: str, name: str) -> bool:
    global _port
    _port += 1
    env = dict(os.environ)
    env["STUMATE_LITE_THEME"] = theme
    env["STUMATE_LITE_PAGE"] = page
    env["STUMATE_LITE_BROWSER_ARGS"] = f"--remote-debugging-port={_port}"
    proc = subprocess.Popen([str(EXE)], cwd=str(ROOT), env=env)
    try:
        sess = cdp.Session(cdp.find_page(_port, timeout=25))
        sess.call("Page.enable")
        # 等首帧稳定：页面 mount 完会自己 setPage，这里多给一点时间让过渡动画走完
        time.sleep(2.6)
        if page in PREP:
            sess.eval(PREP[page])
            time.sleep(0.9)
        data = sess.call("Page.captureScreenshot", {"format": "png", "fromSurface": True})["data"]
        raw = base64.b64decode(data)
        path = OUT / f"{theme}-{name}.png"
        path.write_bytes(raw)
        sess.close()
        print(f"  {theme}/{name:<12} {len(raw) // 1024} KB → {path.name}")
        return True
    except Exception as e:
        print(f"  !! {theme}/{name}: {e}")
        return False
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=6)
        except subprocess.TimeoutExpired:
            proc.kill()
        time.sleep(0.6)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if not EXE.is_file():
        print(f"找不到 {EXE}")
        return 1
    args = sys.argv[1:]
    if len(args) >= 2:
        targets = [(args[0], args[1], args[1])]
    else:
        themes = args or ["dark", "light"]
        targets = [(t, p, n) for t in themes for (p, n) in PAGES]

    ok = sum(1 for t, p, n in targets if shoot(t, p, n))
    print(f"done: {ok}/{len(targets)}")
    return 0 if ok == len(targets) else 2


if __name__ == "__main__":
    sys.exit(main())
