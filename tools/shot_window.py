"""把窗口内容截下来，用于界面验收。

优先用 `PrintWindow(PW_RENDERFULLCONTENT)`，失败再退回屏幕抓取。

为什么先试 PrintWindow
----------------------
屏幕抓取这条路有三个坑，每个都踩过：

1. **DPI** —— 不声明感知时 `GetWindowRect` 给逻辑像素、`ImageGrab` 给物理像素，
   裁切必然错位。`shcore.SetProcessDpiAwareness(2)` 在本机实测会**静默失败**
   （调用成功、坐标仍是逻辑值），副屏（scale 2.0）上表现为"截出来是另一块屏的内容"。
2. **遮挡** —— 窗口被别的窗口盖住就截到别人。`SetForegroundWindow` 从后台进程
   调用会被前台锁拒绝，只能靠 `SetWindowPos(HWND_TOPMOST)`。
3. **最小化** —— 最小化时 `GetWindowRect` 返回 `-32000` 哨兵，裁出来是屏幕角落。

PrintWindow 一次解决三个：不需要坐标（直接给窗口 DC）、不怕遮挡、窗口最小化时
也照样能渲染。**前提是内容走 CPU 合成** —— 本项目因为 WebView2 的 GPU 合成在
本机不出画面，已经默认走 SwiftShader 软件渲染，所以这条路是通的。
（早期 GPU 渲染时 `PrintWindow` 返回全黑，那时候只能靠屏幕抓取。）

代价：拿到的是**逻辑分辨率**（752×800 的窗口出 768×809 的图），比物理截图略糊，
但界面验收够用，而且不会错位。
"""
import ctypes
import ctypes.wintypes as wt
import sys
import time

from PIL import Image

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32

HWND_TOPMOST = -1
HWND_NOTOPMOST = -2
SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_SHOWWINDOW = 0x0040
SW_RESTORE = 9
PW_RENDERFULLCONTENT = 0x00000002
DPI_PER_MONITOR_V2 = ctypes.c_void_p(-4)


class RECT(ctypes.Structure):
    _fields_ = [("left", wt.LONG), ("top", wt.LONG),
                ("right", wt.LONG), ("bottom", wt.LONG)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wt.DWORD), ("biWidth", wt.LONG), ("biHeight", wt.LONG),
        ("biPlanes", wt.WORD), ("biBitCount", wt.WORD), ("biCompression", wt.DWORD),
        ("biSizeImage", wt.DWORD), ("biXPelsPerMeter", wt.LONG),
        ("biYPelsPerMeter", wt.LONG), ("biClrUsed", wt.DWORD),
        ("biClrImportant", wt.DWORD),
    ]


def dpi_aware():
    """按 per-monitor v2 声明感知；失败再退老接口（只为屏幕抓取兜底用）。"""
    try:
        if user32.SetProcessDpiAwarenessContext(DPI_PER_MONITOR_V2):
            return
    except Exception:
        pass
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        user32.SetProcessDPIAware()


def find_window(title_part):
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(hwnd, _):
        if user32.IsWindowVisible(hwnd):
            n = user32.GetWindowTextLengthW(hwnd)
            if n:
                buf = ctypes.create_unicode_buffer(n + 1)
                user32.GetWindowTextW(hwnd, buf, n + 1)
                if title_part.lower() in buf.value.lower():
                    found.append((hwnd, buf.value))
        return True

    user32.EnumWindows(cb, 0)
    return found


def capture_printwindow(hwnd, width, height):
    """把窗口画进内存位图。失败返回 None（调用方退屏幕抓取）。"""
    hdc = user32.GetWindowDC(hwnd)
    if not hdc:
        return None
    mdc = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, width, height)
    gdi32.SelectObject(mdc, bmp)
    try:
        if not user32.PrintWindow(hwnd, mdc, PW_RENDERFULLCONTENT):
            return None
        bi = BITMAPINFOHEADER()
        bi.biSize = ctypes.sizeof(bi)
        bi.biWidth = width
        bi.biHeight = -height  # 负值 = 自上而下，省一次翻转
        bi.biPlanes = 1
        bi.biBitCount = 32
        bi.biCompression = 0
        buf = ctypes.create_string_buffer(width * height * 4)
        if not gdi32.GetDIBits(mdc, bmp, 0, height, buf, ctypes.byref(bi), 0):
            return None
        return Image.frombuffer("RGBA", (width, height), buf, "raw", "BGRA", 0, 1).convert("RGB")
    finally:
        gdi32.DeleteObject(bmp)
        gdi32.DeleteDC(mdc)
        user32.ReleaseDC(hwnd, hdc)


def capture_screen(hwnd, width, height):
    """兜底：置顶后按窗口矩形从屏幕裁。坐标可能因 DPI 虚拟化而错位。"""
    from PIL import ImageGrab

    user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    time.sleep(0.35)
    r = RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    img = ImageGrab.grab(all_screens=True)
    out = img.crop((r.left, r.top, r.left + width, r.top + height))
    user32.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE)
    return out


def main():
    dpi_aware()
    title = sys.argv[1] if len(sys.argv) > 1 else "StuMate"
    out = sys.argv[2] if len(sys.argv) > 2 else "shot.png"
    delay = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0

    wins = find_window(title)
    if not wins:
        print(f"no visible window matching {title!r}")
        return 1
    hwnd, name = wins[0]
    print(f"window: {name!r} hwnd={hwnd}")

    user32.ShowWindow(hwnd, SW_RESTORE)
    time.sleep(delay)

    r = RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    w, h = r.right - r.left, r.bottom - r.top
    print(f"rect: {r.left},{r.top} {w}x{h}")
    if r.left <= -30000 or r.top <= -30000:
        print("window still minimized (-32000 sentinel); aborting")
        return 3
    if w <= 0 or h <= 0:
        print("bad rect")
        return 1

    img = capture_printwindow(hwnd, w, h)
    how = "printwindow"
    if img is None:
        print("PrintWindow failed -> falling back to screen grab")
        img = capture_screen(hwnd, w, h)
        how = "screengrab"

    img.save(out)
    colors = len(img.convert("RGB").getcolors(maxcolors=1 << 20) or [])
    print(f"saved {out} {img.size[0]}x{img.size[1]} via {how} "
          f"luma={img.convert('L').getextrema()} colors={colors}")
    return 0 if colors > 4 else 2


if __name__ == "__main__":
    sys.exit(main())
