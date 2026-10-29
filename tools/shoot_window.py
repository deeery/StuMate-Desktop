"""按窗口标题截一张图（PrintWindow，不抓整屏）。

为什么不用 CDP：Markdowner 子窗口跑在**另一个 WebView2 环境**里，不挂在主窗口那个
调试端口上（`/json/list` 只有主窗口一个 target）。而 `ImageGrab.grab(all_screens=True)`
会把用户桌面上的聊天窗口一起截进来 —— 越界。PrintWindow 只向目标窗口要一帧，
是这里唯一既完整又不越界的办法。

用法：python tools/shoot_window.py "<标题片段>" 输出.png
"""

import ctypes
import ctypes.wintypes as w
import sys

from PIL import Image

u = ctypes.windll.user32
gdi = ctypes.windll.gdi32

PW_RENDERFULLCONTENT = 0x00000002
SRCCOPY = 0x00CC0020


def find_window(needle: str):
    """返回第一个标题包含 needle 的可见窗口句柄。"""
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, w.HWND, w.LPARAM)
    def cb(hwnd, _lparam):
        if not u.IsWindowVisible(hwnd):
            return True
        buf = ctypes.create_unicode_buffer(256)
        u.GetWindowTextW(hwnd, buf, 256)
        if needle in buf.value:
            found.append((hwnd, buf.value))
        return True

    u.EnumWindows(cb, 0)
    return found[0] if found else (None, None)


def shoot(hwnd, path: str):
    rect = w.RECT()
    u.GetWindowRect(hwnd, ctypes.byref(rect))
    width = rect.right - rect.left
    height = rect.bottom - rect.top
    if width <= 0 or height <= 0:
        raise RuntimeError(f"窗口尺寸异常：{width}x{height}")

    hdc_screen = u.GetDC(0)
    hdc_mem = gdi.CreateCompatibleDC(hdc_screen)
    hbmp = gdi.CreateCompatibleBitmap(hdc_screen, width, height)
    gdi.SelectObject(hdc_mem, hbmp)
    try:
        ok = u.PrintWindow(hwnd, hdc_mem, PW_RENDERFULLCONTENT)
        if not ok:
            raise RuntimeError("PrintWindow 返回 0")

        class BITMAPINFOHEADER(ctypes.Structure):
            _fields_ = [
                ("biSize", w.DWORD),
                ("biWidth", w.LONG),
                ("biHeight", w.LONG),
                ("biPlanes", w.WORD),
                ("biBitCount", w.WORD),
                ("biCompression", w.DWORD),
                ("biSizeImage", w.DWORD),
                ("biXPelsPerMeter", w.LONG),
                ("biYPelsPerMeter", w.LONG),
                ("biClrUsed", w.DWORD),
                ("biClrImportant", w.DWORD),
            ]

        bi = BITMAPINFOHEADER()
        bi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
        bi.biWidth = width
        bi.biHeight = -height  # 负数 = 自上而下，省一次翻转
        bi.biPlanes = 1
        bi.biBitCount = 32
        bi.biCompression = 0  # BI_RGB

        buf = ctypes.create_string_buffer(width * height * 4)
        gdi.GetDIBits(hdc_mem, hbmp, 0, height, buf, ctypes.byref(bi), 0)
        img = Image.frombuffer("RGBA", (width, height), buf.raw, "raw", "BGRA", 0, 1)
        img.convert("RGB").save(path)
        return width, height
    finally:
        gdi.DeleteObject(hbmp)
        gdi.DeleteDC(hdc_mem)
        u.ReleaseDC(0, hdc_screen)


if __name__ == "__main__":
    needle = sys.argv[1] if len(sys.argv) > 1 else "StuMate"
    out = sys.argv[2] if len(sys.argv) > 2 else "window.png"
    hwnd, title = find_window(needle)
    if not hwnd:
        print(f"没找到标题含「{needle}」的可见窗口")
        sys.exit(1)
    wh = shoot(hwnd, out)
    print(f"标题={title!r} 尺寸={wh[0]}x{wh[1]} -> {out}")
