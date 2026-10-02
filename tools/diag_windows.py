"""诊断：现在到底有哪些可见窗口、谁盖住了整块屏幕。

背景：用户反馈「还是占用了整个屏幕」。不要再靠猜 —— 把每个可见顶层窗口的
标题 / PID / 矩形 / 扩展样式全列出来，并把进程的可执行路径与**启动时间**一起打出来，
就能判断：
  1. 是全屏窗口盖住了屏幕，还是别的；
  2. 那个进程跑的是哪份代码（build/classes 开发版 vs 安装目录），以及是不是改动之前的旧进程。
"""
import ctypes
import ctypes.wintypes as wt
from datetime import datetime, timedelta, timezone

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

user32.SetProcessDPIAware()
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

GWL_EXSTYLE = -20
WS_EX_LAYERED = 0x00080000
WS_EX_TOOLWINDOW = 0x00000080
WS_EX_TOPMOST = 0x00000008

PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

SPI_GETWORKAREA = 0x0030


def work_area():
    r = wt.RECT()
    user32.SystemParametersInfoW(SPI_GETWORKAREA, 0, ctypes.byref(r), 0)
    return r.left, r.top, r.right, r.bottom


def window_rect(h):
    r = wt.RECT()
    user32.GetWindowRect(h, ctypes.byref(r))
    return r.left, r.top, r.right, r.bottom


def title_of(h):
    n = user32.GetWindowTextLengthW(h)
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(h, buf, n + 1)
    return buf.value


def pid_of(h):
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(h, ctypes.byref(pid))
    return pid.value


def process_info(pid):
    h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return "(打不开进程)", ""
    try:
        buf = ctypes.create_unicode_buffer(2048)
        size = ctypes.c_uint(2048)
        path = "(未知)"
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            path = buf.value
        creation = wt.FILETIME()
        exit_t = wt.FILETIME()
        kern = wt.FILETIME()
        user = wt.FILETIME()
        started = ""
        if kernel32.GetProcessTimes(
            h,
            ctypes.byref(creation),
            ctypes.byref(exit_t),
            ctypes.byref(kern),
            ctypes.byref(user),
        ):
            ticks = (creation.dwHighDateTime << 32) | creation.dwLowDateTime
            if ticks:
                # FILETIME 是 1601-01-01 起的 100ns
                started = (
                    datetime(1601, 1, 1) + timedelta(microseconds=ticks / 10)
                ).strftime("%Y-%m-%d %H:%M:%S")
        return path, started
    finally:
        kernel32.CloseHandle(h)


def main():
    wa = work_area()
    wa_w, wa_h = wa[2] - wa[0], wa[3] - wa[1]
    print(f"工作区（物理像素） {wa}   {wa_w}x{wa_h}\n")

    rows = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def proc(h, _l):
        if not user32.IsWindowVisible(h):
            return True
        t = title_of(h)
        if not t.strip():
            return True
        r = window_rect(h)
        w, hh = r[2] - r[0], r[3] - r[1]
        if w <= 0 or hh <= 0:
            return True
        ex = user32.GetWindowLongW(h, GWL_EXSTYLE) & 0xFFFFFFFF
        rows.append((h, t, pid_of(h), r, w, hh, ex))
        return True

    user32.EnumWindows(proc, 0)

    # 全屏（≥ 工作区 90% 面积）的排前面，其余按面积降序
    def coverage(r):
        x0 = max(r[3][0], wa[0])
        y0 = max(r[3][1], wa[1])
        x1 = min(r[3][2], wa[2])
        y1 = min(r[3][3], wa[3])
        if x1 <= x0 or y1 <= y0:
            return 0.0
        return (x1 - x0) * (y1 - y0) / float(wa_w * wa_h)

    rows.sort(key=lambda r: -coverage(r))

    seen = {}
    print(f"{'hwnd':>10} {'pid':>7} {'尺寸':>12} {'覆盖率':>7}  {'样式':<22} 标题")
    for h, t, p, r, w, hh, ex in rows[:25]:
        flags = []
        if ex & WS_EX_LAYERED:
            flags.append("LAYERED")
        if ex & WS_EX_TOOLWINDOW:
            flags.append("TOOLWIN")
        if ex & WS_EX_TOPMOST:
            flags.append("TOPMOST")
        cov = coverage((h, t, p, r, w, hh, ex))
        mark = "  <== 全屏" if cov >= 0.9 else ""
        print(
            f"{h:>10} {p:>7} {w:>5}x{hh:<6} {cov:>6.1%}  {','.join(flags):<22} {t[:48]}{mark}"
        )
        seen.setdefault(p, t)

    print("\n相关进程：")
    for p in seen:
        path, started = process_info(p)
        print(f"  pid={p:<7} 启动于 {started or '(未知)':<20} {path}")


if __name__ == "__main__":
    main()
