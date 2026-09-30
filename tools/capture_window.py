"""截取指定标题的窗口（Windows），用于 UI 验收。

不依赖 Add-Type / 反射加载，纯 ctypes + Pillow。
用法：python capture_window.py <窗口标题> <输出路径> [--fullscreen]

置顶用「最小化 → 还原 → BringWindowToTop → SetForegroundWindow」这一套：
单靠 SetForegroundWindow 在后台进程里经常被 Windows 拒掉，
结果截出来是压在它上面的别的窗口。
"""
import ctypes
import ctypes.wintypes as wt
import sys
import time

from PIL import ImageGrab

user32 = ctypes.windll.user32

user32.SetProcessDPIAware()
try:
    # Win8.1+ 每显示器 DPI 感知，避免高分屏下坐标被缩放
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
    """用 SetWindowPos 强制提到 z 序最前。

    比 SetForegroundWindow 可靠：后者在后台进程里会被 Windows 的前台锁拒掉
    （返回成功但窗口没上来），结果是截图截到了压在它上面的浏览器。
    """
    user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    user32.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    user32.BringWindowToTop(hwnd)


def find_window(title_substr: str):
    """按标题子串找第一个可见的顶层窗口"""
    result = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def enum_proc(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        if title_substr.lower() in buf.value.lower():
            result.append((hwnd, buf.value))
            return False
        return True

    user32.EnumWindows(enum_proc, 0)
    return result[0] if result else (None, None)


def window_rect(hwnd):
    rect = wt.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(rect))
    return rect.left, rect.top, rect.right, rect.bottom


def raise_window(hwnd) -> bool:
    """把窗口提到最前，返回是否真的成了前台窗口"""
    for _ in range(3):
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
        else:
            user32.ShowWindow(hwnd, SW_MINIMIZE)
            time.sleep(0.25)
            user32.ShowWindow(hwnd, SW_RESTORE)
        time.sleep(0.25)
        force_top(hwnd)
        user32.SetForegroundWindow(hwnd)
        time.sleep(0.35)
        if user32.GetForegroundWindow() == hwnd:
            return True
    # 前台抢不到也要保证它在最上层，否则截出来是别的窗口
    force_top(hwnd)
    time.sleep(0.35)
    return False


def list_windows():
    seen = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def proc(h, _l):
        if user32.IsWindowVisible(h):
            n = user32.GetWindowTextLengthW(h)
            if n:
                b = ctypes.create_unicode_buffer(n + 1)
                user32.GetWindowTextW(h, b, n + 1)
                seen.append(b.value)
        return True

    user32.EnumWindows(proc, 0)
    return seen


def main():
    title = sys.argv[1] if len(sys.argv) > 1 else "StuMate"
    out = sys.argv[2] if len(sys.argv) > 2 else "shot.png"
    full = "--fullscreen" in sys.argv

    hwnd, found = find_window(title)
    if hwnd is None:
        print(f"NOT FOUND: 没有找到标题包含「{title}」的窗口")
        print("当前可见窗口标题：")
        for t in list_windows():
            print("  -", t)
        return 1

    ok = raise_window(hwnd)
    print(f"找到窗口：{found}  hwnd={hwnd}  已置顶={ok}")
    if not ok:
        print("警告：窗口没能提到最前，截图可能被别的窗口遮挡")

    time.sleep(0.4)
    if full:
        img = ImageGrab.grab(all_screens=True)
    else:
        left, top, right, bottom = window_rect(hwnd)
        # 稍微外扩一点，把窗口阴影/圆角一起带上
        img = ImageGrab.grab(
            bbox=(left - 8, top - 8, right + 8, bottom + 8),
            all_screens=True,
        )

    img.save(out)
    print(f"已保存：{out}  {img.width}x{img.height}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
