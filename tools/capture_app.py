"""按「进程」截取应用的全部窗口（含弹出的对话框）。

`capture_window.py` 只截一个顶层窗口，但 Compose Desktop 的 `Dialog` 是
**独立的顶层窗口**，只截主窗口会把对话框整个漏掉。

这里改成：先用标题子串找到主窗口 → 取出它所属进程 PID → 枚举该进程所有可见顶层窗口
→ 取它们的并集外接矩形 → 一次抓下来。这样「主窗口 + 对话框」能在一张图里。

用法：
  python capture_app.py <标题子串> <输出路径> [--pad 16]
"""
import ctypes
import ctypes.wintypes as wt
import sys
import time

from PIL import ImageGrab

user32 = ctypes.windll.user32

user32.SetProcessDPIAware()
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

SW_MINIMIZE = 6
SW_RESTORE = 9
HWND_TOPMOST = -1
HWND_NOTOPMOST = -2
SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_SHOWWINDOW = 0x0040


def force_top(hwnd):
    user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    user32.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    user32.BringWindowToTop(hwnd)


def enum_visible_windows():
    """[(hwnd, title, pid)]"""
    out = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def proc(hwnd, _l):
        if user32.IsWindowVisible(hwnd):
            n = user32.GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(n + 1)
            user32.GetWindowTextW(hwnd, buf, n + 1)
            pid = wt.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            out.append((hwnd, buf.value, pid.value))
        return True

    user32.EnumWindows(proc, 0)
    return out


def rect_of(hwnd):
    r = wt.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    return r.left, r.top, r.right, r.bottom


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    title = sys.argv[1]
    out = sys.argv[2]
    pad = 16
    if "--pad" in sys.argv:
        pad = int(sys.argv[sys.argv.index("--pad") + 1])

    windows = enum_visible_windows()
    main_win = next((w for w in windows if title.lower() in w[1].lower()), None)
    if main_win is None:
        print(f"NOT FOUND: 没有标题包含「{title}」的可见窗口。当前窗口：")
        for _, t, _ in windows:
            if t:
                print("  -", t)
        return 1

    hwnd, found, pid = main_win
    print(f"主窗口：{found}  hwnd={hwnd}  pid={pid}")

    # 把主窗口提到最前（对话框是它的子窗口，会跟着一起上来）
    for _ in range(3):
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
        else:
            user32.ShowWindow(hwnd, SW_MINIMIZE)
            time.sleep(0.25)
            user32.ShowWindow(hwnd, SW_RESTORE)
        time.sleep(0.3)
        force_top(hwnd)
        user32.SetForegroundWindow(hwnd)
        time.sleep(0.35)
        if user32.GetForegroundWindow() == hwnd:
            break

    # 对话框窗口也要单独提一下（SetForegroundWindow 对它们可能失败）
    for h, t, p in windows:
        if p == pid and h != hwnd and not user32.IsIconic(h):
            force_top(h)
    time.sleep(0.4)

    # 主窗口只取**客户区**：Compose Desktop 的无边框窗口外面还有约 16px 的透明缩放边框，
    # 连它一起截会在四边留下一圈「能看见桌面」的透明边，看起来像窗口没画满。
    def client_rect(h):
        cr = wt.RECT()
        user32.GetClientRect(h, ctypes.byref(cr))
        origin = wt.POINT(0, 0)
        user32.ClientToScreen(h, ctypes.byref(origin))
        return (origin.x, origin.y, origin.x + cr.right, origin.y + cr.bottom)

    rects = [client_rect(hwnd)]
    # 对话框是独立顶层窗口（有边框），按窗口矩形取
    for h, _, p in enum_visible_windows():
        if p == pid and h != hwnd and user32.IsWindowVisible(h):
            r = rect_of(h)
            if r[2] > r[0] and r[3] > r[1]:
                rects.append(r)
    rects = [r for r in rects if r[2] > r[0] and r[3] > r[1]]
    if not rects:
        print("没有取到任何窗口矩形")
        return 1

    left = min(r[0] for r in rects) - pad
    top = min(r[1] for r in rects) - pad
    right = max(r[2] for r in rects) + pad
    bottom = max(r[3] for r in rects) + pad
    print(f"合并 {len(rects)} 个窗口，bbox=({left},{top})-({right},{bottom})")

    img = ImageGrab.grab(bbox=(left, top, right, bottom), all_screens=True)
    img.save(out)
    print(f"已保存：{out}  {img.width}x{img.height}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
