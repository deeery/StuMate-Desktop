"""验收脚本：提醒浮窗是否跟着「主窗口所在那块屏」走。

背景：定位逻辑原来直接用 `window.current_monitor()`，但混合 DPI 下它不一定跟着窗口走
（实测踩过：主窗口明明在副屏，它仍报主屏，卡片就弹回主屏去了）。
改成「窗口矩形 × 各屏矩形求交、取重叠面积最大者」之后，这个脚本用来证明它真的生效。

流程：
  1. 启动 release 版（清掉上一个实例）
  2. 等主窗口出现
  3. 用 SetWindowPos 把主窗口搬到副屏（绕过应用自己的初始定位，直接改窗口矩形）
  4. 等提醒轮询触发（首次检查在启动后 5 秒）
  5. 读 trace 日志里那条 "overlay 定位"，并读出浮窗真实矩形

期望（本机布局：主屏 0,0 2560x1440@1.0 / 副屏 2560,435 1600x2560@2.0）：
  主窗口在副屏时 → 浮窗应落在副屏水平中线，物理 x≈3130，而不是主屏的 x≈1050
"""

import ctypes
import os
import re
import subprocess
import sys
import time
from ctypes import wintypes

EXE = r"E:\Projects\Stumate\Stumate-desktop-lite-v1.7.6\src-tauri\target\release\stumate-lite.exe"
TRACE = os.path.join(os.environ["APPDATA"], "StuMate", "lite-trace.log")
MAIN_TITLE = "StuMate Lite"
OVERLAY_TITLE = "StuMate Lite 提醒"

user32 = ctypes.windll.user32
user32.SetProcessDpiAwarenessContext(-4)

SWP_NOSIZE = 0x0001
SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010


def find_window(title):
    """按标题精确匹配，返回 hwnd（0 表示没找到）"""
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
    def cb(hwnd, _):
        n = user32.GetWindowTextLengthW(hwnd)
        if n:
            buf = ctypes.create_unicode_buffer(n + 1)
            user32.GetWindowTextW(hwnd, buf, n + 1)
            if buf.value == title:
                found.append(hwnd)
        return True

    user32.EnumWindows(cb, 0)
    return found[0] if found else 0


def rect_of(hwnd):
    r = wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def visible(hwnd):
    return bool(user32.IsWindowVisible(hwnd))


def kill_running():
    subprocess.run(
        ["taskkill", "/F", "/IM", "stumate-lite.exe"],
        capture_output=True,
        creationflags=0x08000000,
    )


def main():
    target_x = int(sys.argv[1]) if len(sys.argv) > 1 else 2580
    target_y = int(sys.argv[2]) if len(sys.argv) > 2 else 500

    kill_running()
    time.sleep(1.0)

    # 记下日志长度，只看本次新增的行
    offset = os.path.getsize(TRACE) if os.path.isfile(TRACE) else 0

    errlog = os.path.join(os.path.dirname(TRACE), "lite-stderr.log")
    proc = subprocess.Popen(
        [EXE],
        creationflags=0x00000008,  # DETACHED_PROCESS
        # trace 是显式开关：不设这个变量时应用完全不写日志（生产默认）
        env={**os.environ, "STUMATE_LITE_TRACE": "1"},
        # 主线程里的 panic 只会打到 stderr，窗口照样建出来 —— 不接住就完全看不见
        stdout=open(errlog, "w", encoding="utf-8", errors="replace"),
        stderr=subprocess.STDOUT,
    )

    main_hwnd = 0
    for _ in range(100):
        time.sleep(0.2)
        main_hwnd = find_window(MAIN_TITLE)
        if main_hwnd:
            break
    if not main_hwnd:
        print("FAIL 主窗口没出现")
        return 1
    print(f"主窗口 hwnd={main_hwnd} rect={rect_of(main_hwnd)}")

    ok = user32.SetWindowPos(
        main_hwnd, 0, target_x, target_y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
    )
    time.sleep(0.5)
    print(f"移动到 ({target_x},{target_y}) -> {'ok' if ok else 'FAILED'}，实际 rect={rect_of(main_hwnd)}")

    # 首次检查在 5 秒后，轮询 30 秒；给足余量
    overlay_hwnd = 0
    for _ in range(80):
        time.sleep(0.5)
        overlay_hwnd = find_window(OVERLAY_TITLE)
        if overlay_hwnd and visible(overlay_hwnd):
            break
    if not overlay_hwnd:
        print("FAIL 浮窗没出现（提醒没触发？）")
        kill_running()
        return 1

    time.sleep(1.0)
    print(f"浮窗 hwnd={overlay_hwnd} rect={rect_of(overlay_hwnd)}")
    # 再等一会儿：如果 build() 只是慢而不是卡死，这段时间里 trace 会补上「建窗后」
    time.sleep(6.0)
    print(f"6 秒后 rect={rect_of(overlay_hwnd)}")

    with open(TRACE, "r", encoding="utf-8", errors="replace") as f:
        f.seek(offset)
        lines = f.read().splitlines()
    hits = [l for l in lines if "overlay 定位" in l]
    print("--- trace ---")
    for l in hits:
        print(" ", l)

    # 判定：浮窗中心是否落在主窗口所在的那块屏上
    ox, oy, ow, _oh = rect_of(overlay_hwnd)
    ocx = ox + ow // 2
    print(f"浮窗中心 x={ocx}")

    kill_running()
    if 2560 <= ocx <= 4160:
        print("PASS 浮窗在副屏（跟随主窗口）")
        return 0
    print(f"FAIL 浮窗不在副屏，仍在主屏（中心 x={ocx}）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
