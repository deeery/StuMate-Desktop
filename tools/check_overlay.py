"""验证「上课提醒只占右下角一块，其余区域完全没被遮挡」。

上一版提醒是**全屏 + 卡片以外透明**，那种效果 `BitBlt` 截不出来
（色键挖出的洞返回未初始化白），只能请用户肉眼确认 —— 也因此白跑过一轮。
现在提醒是右下角小窗，遮挡范围是个**几何事实**，可以断言，不需要人看。

本脚本做四件事，任一条不成立就报 FAIL 并退出码 1：
  1. 提醒窗口的矩形**必须**在工作区右下角，且面积占比 < 10%；
  2. 提醒窗口必须带 `WS_EX_TOOLWINDOW`（不占任务栏、不进 Alt+Tab）；
  3. 截图左上角（离提醒最远的地方）必须是**底窗的绿色**，不是卡片色 ——
     这条直接证明「提醒没有覆盖整个桌面」；
  4. 提醒窗口正左方 40px 处也必须是绿色（证明遮挡边界就在卡片边上）。

用法：
  python check_overlay.py [输出目录]
"""
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import sys
import time

from PIL import Image, ImageGrab

user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WINDOW_TITLE = "StuMatePreview"
TOAST_TITLE = "StuMate 提醒"

JAVA = os.environ.get("STUMATE_JAVA") or r"E:\DevTools\Java\jdk-17.0.12+7\bin\java.exe"
THEME = os.environ.get("STUMATE_THEME") or "dark"
CLASSPATH_CACHE = os.path.join(PROJECT, ".preview-classpath")

# 底窗的替身色，见 UiPreview.kt 的 `Color(0xFF1B7F4B)`
BACKDROP = (0x1B, 0x7F, 0x4B)

GWL_EXSTYLE = -20
WS_EX_TOOLWINDOW = 0x00000080
SPI_GETWORKAREA = 0x0030

failures = []


def check(ok, label, detail=""):
    print(f"   [{'OK' if ok else 'FAIL'}] {label}{('  ' + detail) if detail else ''}")
    if not ok:
        failures.append(label)
    return ok


def runtime_classpath():
    with open(CLASSPATH_CACHE, encoding="utf-8") as f:
        return f.read().strip()


def enum_windows():
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


def client_rect(h):
    cr = wt.RECT()
    user32.GetClientRect(h, ctypes.byref(cr))
    o = wt.POINT(0, 0)
    user32.ClientToScreen(h, ctypes.byref(o))
    return o.x, o.y, o.x + cr.right, o.y + cr.bottom


def window_rect(h):
    r = wt.RECT()
    user32.GetWindowRect(h, ctypes.byref(r))
    return r.left, r.top, r.right, r.bottom


def work_area():
    r = wt.RECT()
    user32.SystemParametersInfoW(SPI_GETWORKAREA, 0, ctypes.byref(r), 0)
    return r.left, r.top, r.right, r.bottom


def exstyle(h):
    return user32.GetWindowLongW(h, GWL_EXSTYLE) & 0xFFFFFFFF


def is_green(px, tol=40):
    return all(abs(px[i] - BACKDROP[i]) <= tol for i in range(3))


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(PROJECT, "build", "preview")
    os.makedirs(out_dir, exist_ok=True)

    cmd = [
        JAVA, "-Dfile.encoding=UTF-8",
        "-Dstumate.preview=overlay",
        f"-Dstumate.theme={THEME}",
        "-cp", runtime_classpath(),
        "com.example.classreminder.dev.UiPreviewKt",
    ]
    proc = subprocess.Popen(
        cmd, cwd=PROJECT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
    )
    try:
        # 等窗口出现（不死等固定秒数：慢一点就截到还没画完的图）
        deadline = time.time() + 120
        main_hwnd = None
        while time.time() < deadline:
            for h, t, _ in enum_windows():
                if WINDOW_TITLE.lower() in t.lower():
                    main_hwnd = h
                    break
            if main_hwnd:
                break
            time.sleep(1.0)
        if not main_hwnd:
            print("超时：没等到预览窗口")
            return 1

        # 让动画（180ms 滑入）与首帧渲染都落定
        time.sleep(4.0)

        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(main_hwnd, ctypes.byref(pid))
        pid = pid.value

        toast_hwnd = None
        for h, t, p in enum_windows():
            if p == pid and TOAST_TITLE.lower() in t.lower():
                toast_hwnd = h
                break
        if not toast_hwnd:
            print(f"FAIL：本进程里找不到「{TOAST_TITLE}」窗口")
            return 1

        wa = work_area()
        tr = window_rect(toast_hwnd)
        mr = client_rect(main_hwnd)
        wa_w, wa_h = wa[2] - wa[0], wa[3] - wa[1]
        t_w, t_h = tr[2] - tr[0], tr[3] - tr[1]
        coverage = (t_w * t_h) / float(wa_w * wa_h)

        print(f"工作区 {wa}  底窗客户区 {mr}")
        print(f"提醒窗口 rect={tr}  {t_w}x{t_h}  占工作区 {coverage:.2%}")

        print("\n① 遮挡范围")
        check(coverage < 0.10, "提醒窗口面积 < 工作区 10%", f"{coverage:.2%}")
        # 右下角：右边与下边各留 ~16dp 的边距（允许 DPI / 边框误差 60px）
        check(abs(tr[2] - wa[2]) <= 60, "贴着工作区右边", f"右差 {tr[2] - wa[2]}px")
        check(abs(tr[3] - wa[3]) <= 60, "贴着工作区下边", f"下差 {tr[3] - wa[3]}px")
        check(t_w <= 900 and t_h <= 500, "尺寸是「小窗」而不是铺满", f"{t_w}x{t_h}")

        print("\n② 窗口样式")
        ex = exstyle(toast_hwnd)
        check(bool(ex & WS_EX_TOOLWINDOW), "带 WS_EX_TOOLWINDOW（不占任务栏）", f"exstyle=0x{ex:08X}")

        print("\n③ 像素：卡片以外必须还能看见底窗")
        img = ImageGrab.grab(bbox=mr, all_screens=True)
        shot = os.path.join(out_dir, f"overlay-toast-verify-{THEME}.png")
        img.save(shot)

        def sample(x, y):
            return img.getpixel((max(0, min(img.width - 1, x)), max(0, min(img.height - 1, y))))[:3]

        # 截图坐标 = 底窗客户区坐标；换算成屏幕坐标再定位
        def at(screen_x, screen_y):
            return sample(screen_x - mr[0], screen_y - mr[1])

        far = at(mr[0] + 24, mr[1] + 24)
        check(is_green(far), "左上角是底窗绿色（= 提醒没盖住那里）", f"rgb{far}")

        near = at(tr[0] - 40, (tr[1] + tr[3]) // 2)
        check(is_green(near), "提醒窗口正左方 40px 是绿色（遮挡边界就在卡片边上）", f"rgb{near}")

        above = at((tr[0] + tr[2]) // 2, tr[1] - 40)
        check(is_green(above), "提醒窗口正上方 40px 是绿色", f"rgb{above}")

        inside = at((tr[0] + tr[2]) // 2, (tr[1] + tr[3]) // 2)
        check(not is_green(inside), "提醒窗口内部不是绿色（卡片真的画出来了）", f"rgb{inside}")

        print(f"\n截图：{shot}")
        if failures:
            print(f"\n失败 {len(failures)} 项：{failures}")
            return 1
        print("\n全部通过：提醒只占右下角，其余区域未被遮挡。")
        return 0
    finally:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    sys.exit(main())
