"""Windows 输入模拟 + 窗口信息查询（纯 ctypes，无需第三方依赖）。

子命令：
  info <标题子串>                 打印窗口位置与尺寸
  click <x> <y>                   在屏幕绝对坐标点击一次
  click-win <标题子串> <x> <y>    在窗口内的相对坐标点击（自动换算绝对坐标并置顶窗口）
  move-win <标题子串> <x> <y>     只把光标移到窗口内相对坐标（不点击），用于验证 hover
  dblclick-win <标题子串> <x> <y> 在窗口内相对坐标双击（两次按下间隔 120ms）
  key <名称> [标题子串]           发送一个按键，如 enter / esc / tab

坐标以「截图像素」为准：截图脚本会外扩 8px，所以 click-win 内部按 -8 偏移还原。
"""
import ctypes
import ctypes.wintypes as wt
import sys
import time

user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
KEYEVENTF_KEYUP = 0x0002

CAPTURE_MARGIN = 8
SW_MINIMIZE = 6
SW_RESTORE = 9
HWND_TOPMOST = -1
HWND_NOTOPMOST = -2
SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_SHOWWINDOW = 0x0040


def force_top(hwnd):
    """SetWindowPos 强制提到 z 序最前，绕过 Windows 的前台锁"""
    user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    user32.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    user32.BringWindowToTop(hwnd)

VK = {
    "enter": 0x0D, "esc": 0x1B, "tab": 0x09, "space": 0x20,
    "backspace": 0x08, "delete": 0x2E,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
}


def find_window(title_substr):
    result = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def enum_proc(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        n = user32.GetWindowTextLengthW(hwnd)
        if n == 0:
            return True
        buf = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, buf, n + 1)
        if title_substr.lower() in buf.value.lower():
            result.append((hwnd, buf.value))
            return False
        return True

    user32.EnumWindows(enum_proc, 0)
    return result[0] if result else (None, None)


def rect_of(hwnd):
    r = wt.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    return r.left, r.top, r.right, r.bottom


def focus(hwnd) -> bool:
    """把窗口提到最前。

    单靠 SetForegroundWindow 常被 Windows 的前台锁拒掉，所以配合 SetWindowPos 强制置顶。
    已经在前台且没最小化时直接返回 —— 避免多余的「最小化→还原」把窗口焦点打断，
    那会让紧接着的第一次点击被系统当成「激活窗口」而吃掉。
    """
    if not user32.IsIconic(hwnd) and user32.GetForegroundWindow() == hwnd:
        return True
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
        time.sleep(0.4)
        if user32.GetForegroundWindow() == hwnd:
            return True
    # 前台抢不到也要保证它压在最上层，否则点击会落到别的窗口上
    force_top(hwnd)
    time.sleep(0.4)
    return False


def click_abs(x, y):
    user32.SetCursorPos(int(x), int(y))
    time.sleep(0.25)
    user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
    time.sleep(0.08)
    user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
    time.sleep(0.5)


def press(vk):
    user32.keybd_event(vk, 0, 0, 0)
    time.sleep(0.06)
    user32.keybd_event(vk, 0, KEYEVENTF_KEYUP, 0)
    time.sleep(0.3)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]

    if cmd == "info":
        hwnd, title = find_window(sys.argv[2])
        if hwnd is None:
            print("NOT FOUND")
            return 1
        l, t, r, b = rect_of(hwnd)
        print(f"title={title}\nhwnd={hwnd}\nrect=({l},{t})-({r},{b})\nsize={r - l}x{b - t}")
        return 0

    if cmd == "click":
        click_abs(float(sys.argv[2]), float(sys.argv[3]))
        print("clicked")
        return 0

    if cmd == "click-win":
        hwnd, title = find_window(sys.argv[2])
        if hwnd is None:
            print("NOT FOUND")
            return 1
        if not focus(hwnd):
            print("警告：窗口没能提到最前，点击可能落到别的窗口上")
        time.sleep(0.3)
        l, t, _r, _b = rect_of(hwnd)
        # 「热身」点击：窗口刚被最小化→还原时，Windows 会把紧接着的第一次点击当成
        # 「激活窗口」吃掉，不传给应用。先点一下侧栏的空白死区，把这一下消耗掉。
        click_abs(l - CAPTURE_MARGIN + 150, t - CAPTURE_MARGIN + 400)
        time.sleep(0.2)
        x = l - CAPTURE_MARGIN + float(sys.argv[3])
        y = t - CAPTURE_MARGIN + float(sys.argv[4])
        click_abs(x, y)
        print(f"clicked window({sys.argv[3]},{sys.argv[4]}) -> screen({x:.0f},{y:.0f})")
        return 0

    if cmd == "dblclick-win":
        hwnd, title = find_window(sys.argv[2])
        if hwnd is None:
            print("NOT FOUND")
            return 1
        if not focus(hwnd):
            print("警告：窗口没能提到最前，点击可能落到别的窗口上")
        time.sleep(0.3)
        l, t, _r, _b = rect_of(hwnd)
        click_abs(l - CAPTURE_MARGIN + 150, t - CAPTURE_MARGIN + 400)  # 热身
        time.sleep(0.2)
        x = l - CAPTURE_MARGIN + float(sys.argv[3])
        y = t - CAPTURE_MARGIN + float(sys.argv[4])
        user32.SetCursorPos(int(x), int(y))
        time.sleep(0.25)
        for i in range(2):
            user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
            time.sleep(0.03)
            user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
            if i == 0:
                time.sleep(0.12)
        time.sleep(0.6)
        print(f"double-clicked window({sys.argv[3]},{sys.argv[4]}) -> screen({x:.0f},{y:.0f})")
        return 0

    if cmd == "move-win":
        hwnd, title = find_window(sys.argv[2])
        if hwnd is None:
            print("NOT FOUND")
            return 1
        if not focus(hwnd):
            print("警告：窗口没能提到最前，hover 可能落到别的窗口上")
        time.sleep(0.3)
        l, t, _r, _b = rect_of(hwnd)
        x = l - CAPTURE_MARGIN + float(sys.argv[3])
        y = t - CAPTURE_MARGIN + float(sys.argv[4])
        # 分两步挪：Compose 的 hover 需要指针真的「移动」才派发 Enter/Move，
        # 直接瞬移有时不触发，所以先挪到附近再落到目标点。
        user32.SetCursorPos(int(x) - 6, int(y) - 6)
        time.sleep(0.12)
        user32.SetCursorPos(int(x), int(y))
        time.sleep(0.5)
        print(f"moved to window({sys.argv[3]},{sys.argv[4]}) -> screen({x:.0f},{y:.0f})")
        return 0

    if cmd == "key":
        if len(sys.argv) > 3:
            hwnd, _ = find_window(sys.argv[3])
            if hwnd is not None:
                focus(hwnd)
        name = sys.argv[2].lower()
        vk = VK.get(name)
        if vk is None:
            print(f"未知按键：{name}")
            return 1
        press(vk)
        print(f"sent {name}")
        return 0

    print(f"未知子命令：{cmd}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
