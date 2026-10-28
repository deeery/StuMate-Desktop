"""批量截取验收图。

每个视图单独启动一次应用，而不是在一个实例里点来点去 —— 页面状态由环境变量
（STUMATE_LITE_PAGE / VIEW / THEME）决定，这样每次都是干净的首屏，不受上一次
操作残留的影响。

关窗口用 WM_CLOSE 而不是 TerminateProcess：强杀会让 WebView2 在 Crashpad 里
留下崩溃转储，之后排查真问题时全是噪音。
"""
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import sys
import time

from PIL import ImageGrab

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXE = os.path.join(ROOT, "src-tauri", "target", "release", "stumate-lite.exe")
OUT = os.path.join(ROOT, "design-preview", "acceptance")

user32 = ctypes.windll.user32
WM_CLOSE = 0x0010

# (page, view, 输出名后缀)
# 知识库没有列表/导图两种视图了（v1.7.7 换成资源管理器树），所以去掉了 map。
VIEWS = [
    ("today", None, "today"),
    ("week", None, "week"),
    ("notes", None, "notes"),
    ("knowledge", None, "knowledge"),
    ("settings", None, "settings"),
]


class RECT(ctypes.Structure):
    _fields_ = [("left", wt.LONG), ("top", wt.LONG),
                ("right", wt.LONG), ("bottom", wt.LONG)]


def find_window(title="StuMate Lite"):
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, _):
        n = user32.GetWindowTextLengthW(h)
        if n:
            b = ctypes.create_unicode_buffer(n + 1)
            user32.GetWindowTextW(h, b, n + 1)
            if b.value.strip() == title:
                found.append(h)
        return True

    user32.EnumWindows(cb, 0)
    return found[0] if found else None


class POINT(ctypes.Structure):
    _fields_ = [("x", wt.LONG), ("y", wt.LONG)]


def capture(hwnd, path):
    # 用**客户区**而不是窗口矩形：窗口矩形把 DWM 阴影边也算进去了（实测多出 22×13 px），
    # 那几像素里会漏进窗口后面的桌面/别的程序 —— 截图里像是「右侧有一条杂色」，
    # 其实不是应用画的。客户区才是真正由 WebView 渲染的部分。
    rc = RECT()
    user32.GetClientRect(hwnd, ctypes.byref(rc))
    origin = POINT(0, 0)
    user32.ClientToScreen(hwnd, ctypes.byref(origin))
    w, h = rc.right - rc.left, rc.bottom - rc.top
    # 只截这个矩形，**不要** `all_screens=True` 再裁 ——
    # 那等于先把用户整个桌面抓进内存（可能是正在共享的屏幕/聊天窗口），
    # 只是最后没保存而已。按 bbox 取，越界的数据从一开始就不进来。
    crop = ImageGrab.grab(bbox=(origin.x, origin.y, origin.x + w, origin.y + h), all_screens=True)
    crop.save(path)
    colors = len(crop.convert("RGB").getcolors(maxcolors=1 << 20) or [])
    return w, h, colors


def close_window(hwnd):
    user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)


def run(theme, page, view, name):
    env = dict(os.environ)
    env["STUMATE_LITE_TOPMOST"] = "1"
    env["STUMATE_LITE_THEME"] = theme
    env["STUMATE_LITE_PAGE"] = page
    if view:
        env["STUMATE_LITE_VIEW"] = view
    else:
        env.pop("STUMATE_LITE_VIEW", None)

    proc = subprocess.Popen([EXE], cwd=ROOT, env=env)

    # 等窗口出现且稳定：先等句柄，再多给一秒让首屏渲染完成
    hwnd = None
    deadline = time.time() + 15
    while time.time() < deadline:
        hwnd = find_window()
        if hwnd:
            break
        time.sleep(0.2)
    if not hwnd:
        print(f"  !! {theme}/{name}: 窗口没出现")
        proc.kill()
        return False

    # 这台机器上窗口会在启动约 2 秒后自己变成「最小化」（ICONIC，位置 -32000,-32000），
    # 客户区跟着变成 144×19。应用本身是好的（同一时刻 CDP 里页面完全正常），
    # 所以这里先恢复一次再截 —— 恢复后不再被最小化。
    SW_RESTORE = 9
    user32.ShowWindow(hwnd, SW_RESTORE)
    time.sleep(0.3)

    time.sleep(2.2)

    out = os.path.join(OUT, f"{theme}-{name}.png")
    w, h, colors = capture(hwnd, out)
    print(f"  {theme}/{name:<16} {w}x{h} colors={colors}")

    close_window(hwnd)
    try:
        proc.wait(timeout=8)
    except subprocess.TimeoutExpired:
        proc.kill()
    time.sleep(0.8)
    return colors > 50


def main():
    user32.SetProcessDPIAware()
    os.makedirs(OUT, exist_ok=True)
    if not os.path.isfile(EXE):
        print(f"找不到 {EXE}，先 cargo build --release")
        return 1

    themes = sys.argv[1:] or ["dark", "light"]
    ok = 0
    total = 0
    for theme in themes:
        print(f"[{theme}]")
        for page, view, name in VIEWS:
            total += 1
            if run(theme, page, view, name):
                ok += 1
    print(f"done: {ok}/{total}")
    return 0 if ok == total else 2


if __name__ == "__main__":
    sys.exit(main())
