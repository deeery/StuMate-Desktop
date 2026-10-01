"""把已运行的 StuMate 窗口拉到前台。

为什么不用 win_input.focus()：单靠 SetForegroundWindow 会被 Windows 前台锁拒掉
（本会话实测过），必须先把本线程挂到目标窗口线程上（AttachThreadInput）才有效。
"""
import ctypes
import ctypes.wintypes as wt
import sys
import time

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

SW_RESTORE = 9
HWND_TOPMOST = -1
HWND_NOTOPMOST = -2
SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_SHOWWINDOW = 0x0040


PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def _proc_name(pid):
    h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return ""
    try:
        size = wt.DWORD(260)
        buf = ctypes.create_unicode_buffer(260)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            return buf.value.rsplit("\\", 1)[-1].lower()
        return ""
    finally:
        kernel32.CloseHandle(h)


def find_window(substr, proc_filter="java.exe"):
    """按标题子串找窗口。

    proc_filter 很关键：项目路径本身含 "StuMate-Desktop"，
    资源管理器窗口标题里也有 "StuMate"，不过滤就会把 Explorer 也当成目标。
    """
    hits = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def enum_proc(hwnd, _l):
        if not user32.IsWindowVisible(hwnd):
            return True
        n = user32.GetWindowTextLengthW(hwnd)
        if n == 0:
            return True
        buf = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, buf, n + 1)
        if substr.lower() not in buf.value.lower():
            return True
        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if proc_filter and _proc_name(pid.value) != proc_filter:
            return True
        hits.append((hwnd, buf.value))
        return False

    user32.EnumWindows(enum_proc, 0)
    return hits


def force_foreground(hwnd):
    fg = user32.GetForegroundWindow()
    tgt_thread = user32.GetWindowThreadProcessId(hwnd, None)
    fg_thread = user32.GetWindowThreadProcessId(fg, None) if fg else 0
    my_thread = kernel32.GetCurrentThreadId()

    attached = []
    for t in {tgt_thread, fg_thread}:
        if t and t != my_thread:
            if user32.AttachThreadInput(my_thread, t, True):
                attached.append(t)

    try:
        user32.ShowWindow(hwnd, SW_RESTORE)
        user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                            SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
        user32.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
                            SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
        user32.SetActiveWindow(hwnd)
        time.sleep(0.3)
    finally:
        for t in attached:
            user32.AttachThreadInput(my_thread, t, False)

    return user32.GetForegroundWindow() == hwnd


def main():
    hits = find_window("StuMate")
    if not hits:
        print("没找到 StuMate 窗口")
        return 1
    for hwnd, title in hits:
        r = wt.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        ok = force_foreground(hwnd)
        print(f"hwnd={hwnd} title={title!r} "
              f"rect=({r.left},{r.top},{r.right},{r.bottom}) "
              f"前台={'是' if ok else '否'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
