"""真实窗口的极端几何探测：把窗口推到「理论上最刁钻」的位置和尺寸，再断言它没崩。

## 为什么纯函数单测不够

`WindowGeometryTest` 已经把 `resizeGeometry` / `clampDragPosition` /
`restoreGeometry` 的算术穷举过了（248 个用例全绿）。但它验不了这几件事：

  1. **dp ↔ 物理像素的换算真的对**。单测里 scale 是手传的参数；
     真实窗口上 scale 来自 `GraphicsConfiguration.defaultTransform`，
     而 Compose 的 `windowState.size` 走的是 density —— 两者错一档，
     就会表现成「拖 100 像素窗口长 200 像素」，单测完全看不见。
  2. **Compose 会不会在夹取之后又把尺寸改回去**。`windowState.size` 写进去之后，
     AWT 窗口的实际尺寸由 Compose 重新计算，中间可能被 `minimumSize`、
     布局约束、甚至 `setBounds` 的取整再动一次。断言必须读 `GetWindowRect`。
  3. **AWT 的 minimumSize 有没有真的报给系统**（`WM_GETMINMAXINFO` 的
     `ptMinTrackSize`）—— 用户拖边时系统靠这个值挡下限。

## ⚠️ 这个脚本**不能**验证「按住鼠标拖缩放热区」

试过三条路，全部无效，原因都在这台验收环境而不是代码：

  · `SetCursorPos` + `mouse_event` —— 窗口拿不到前台焦点
    （`SetForegroundWindow` 恒返回 False），指针事件被系统丢弃。
  · `PostMessage(WM_LBUTTONDOWN / WM_MOUSEMOVE)` —— AWT 的事件循环
    不把合成的鼠标消息当成真实指针事件，Compose 收不到。
  · `SendMessage(WM_NCHITTEST)` —— 无边框窗口恒返回 `HTCLIENT`。
    这是**正确行为**：`undecorated = true` 之后系统边框不存在，
    缩放完全由 Compose 那 5dp 热区自己处理，压根不经过 Win32 命中测试。
    所以这条路在原理上就验不了这件事，别指望它。

拖拽缩放的算术由 `WindowGeometryTest` 兜底（逐帧重算、方向符号、边框固定边等）。
本脚本负责的是「窗口被外部改成极端尺寸后，应用有没有跟着错」这一类。

## 它怎么施加极端条件

`SetWindowPos` 把窗口硬塞到极端矩形（最小、超屏宽、负坐标、贴边），
然后断言**应用自己**没有把它弹回去或放大 —— 也就是
「外部把窗口设成 X，应用接受的是 X，而不是 2X 或 X/2」。
这一条直接盖住 dp/px 换算错的整类 bug。

⚠️ `SetWindowPos` **故意**绕过 `WM_GETMINMAXINFO`，所以它能成功把窗口设成
300x200。这**不是**应用缺陷（系统级强制改尺寸走的就是这条路）。
最小尺寸的正确验法是问 `ptMinTrackSize`，见 `min_track_size()`。

## 用法

    python tools/probe_window_geometry.py

退出码 0 = 全部通过；1 = 有失败项（每条都打期望值 / 实际值）。
"""
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import sys
import time

user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WINDOW_TITLE = "StuMatePreview"
JAVA = os.environ.get("STUMATE_JAVA") or r"E:\DevTools\Java\jdk-17.0.12+7\bin\java.exe"
CLASSPATH_CACHE = os.path.join(PROJECT, ".preview-classpath")

SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010
SPI_GETWORKAREA = 0x0030

# 生产里 Main.kt 给窗口设的 minimumSize = 900x600 (dp)。
# 100% 缩放下物理像素也是 900x600；150% 时是 1350x900。
MIN_W_DP, MIN_H_DP = 900, 600

results = []


# ── 基础查询 ──────────────────────────────────────────────────────


def work_area():
    r = wt.RECT()
    user32.SystemParametersInfoW(SPI_GETWORKAREA, 0, ctypes.byref(r), 0)
    return r.left, r.top, r.right, r.bottom


def window_rect(h):
    r = wt.RECT()
    user32.GetWindowRect(h, ctypes.byref(r))
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def find_window():
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def proc(h, _l):
        if not user32.IsWindowVisible(h):
            return True
        n = user32.GetWindowTextLengthW(h)
        if n == 0:
            return True
        buf = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(h, buf, n + 1)
        if WINDOW_TITLE.lower() in buf.value.lower():
            found.append((h, buf.value))
            return False
        return True

    user32.EnumWindows(proc, 0)
    return found[0] if found else (None, None)


def set_rect(h, x, y, w, hh):
    user32.SetWindowPos(h, 0, x, y, w, hh, SWP_NOZORDER | SWP_NOACTIVATE)
    time.sleep(0.45)


WM_GETMINMAXINFO = 0x0024
WM_NCHITTEST = 0x0084
HTCLIENT = 1


class MINMAXINFO(ctypes.Structure):
    _fields_ = [
        ("ptReserved", wt.POINT),
        ("ptMaxSize", wt.POINT),
        ("ptMaxPosition", wt.POINT),
        ("ptMinTrackSize", wt.POINT),
        ("ptMaxTrackSize", wt.POINT),
    ]


def min_track_size(h):
    """问窗口要「最小可拖拽尺寸」。

    Win32 的用户拖边/拖角路径会先发 `WM_GETMINMAXINFO`，回填的
    `ptMinTrackSize` 就是 AWT `minimumSize` 的物理像素表现。这是验证
    「最小尺寸真的生效了」的正确手段 —— 直接 `SetWindowPos` 到 300x200
    会成功，因为它**故意**绕过这层约束（系统级强制改尺寸走的就是这条路）。
    """
    mmi = MINMAXINFO()
    mmi.ptMinTrackSize.x = 0
    mmi.ptMinTrackSize.y = 0
    user32.SendMessageW(h, WM_GETMINMAXINFO, 0, ctypes.byref(mmi))
    return mmi.ptMinTrackSize.x, mmi.ptMinTrackSize.y


def nchittest(h, sx, sy):
    """对屏幕坐标 (sx, sy) 做一次命中测试，返回 HT* 值。

    无边框窗口应当恒为 `HTCLIENT` —— 见文件头的说明。
    """
    lp = ((sy & 0xFFFF) << 16) | (sx & 0xFFFF)
    return user32.SendMessageW(h, WM_NCHITTEST, 0, lp)


def ui_scale_of(h):
    """用「设进去的宽度 / 读出来的宽度」反推缩放比。

    DPI 感知进程里 GetWindowRect 给的是物理像素，而应用内部把
    windowState.size 当 dp 用，再乘 density 写回 AWT。若两者错一档，
    设 800 会读回 1600（200%）或 400（50%）。
    这里用一个安全的窄窗口试探，得到一个「换算是否恒等于 1」的结论。
    """
    set_rect(h, 200, 200, 1000, 700)
    _x, _y, w, _hh = window_rect(h)
    return w / 1000.0


# ── 断言 ──────────────────────────────────────────────────────────


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"   {detail}" if detail else ""))


def expect_rect(name, h, want_w, want_h, tol=4, check_size=True):
    """断言窗口被外部设成某尺寸后，应用没有把它改写。

    只比尺寸不比位置：位置会被 clampDragPosition / windows 的光标约束影响，
    位置正确性在 `expect_inside` 里单独验。
    """
    _x, _y, w, hh = window_rect(h)
    if check_size:
        ok = abs(w - want_w) <= tol and abs(hh - want_h) <= tol
        check(name, ok, f"期望 {want_w}x{want_h}±{tol}，实际 {w}x{hh}")
    return w, hh


def expect_inside(name, h, area, tol=6):
    """断言窗口至少有一部分留在工作区里（可被抓回来）。"""
    x, y, w, hh = window_rect(h)
    ax, ay, ar, ab = area
    # 横向：至少 40 像素可见；纵向：标题栏（顶 40 像素）必须在工作区内
    horizontally_visible = x + w > ax + 40 and x < ar - 40
    title_visible = ay <= y <= ab - 40
    ok = horizontally_visible and title_visible
    check(
        name,
        ok,
        f"rect=({x},{y})-({x + w},{y + hh}) 工作区=({ax},{ay})-({ar},{ab}) "
        f"横向可见={horizontally_visible} 标题栏可见={title_visible}",
    )
    return x, y, w, hh


# ── 用例 ──────────────────────────────────────────────────────────


def run_cases(h, hwnd_scale):
    area = work_area()
    ax, ay, ar, ab = area
    aw, ah = ar - ax, ab - ay
    print(f"工作区 {area}  {aw}x{ah}   窗口缩放换算比 {hwnd_scale:.3f}\n")

    # ── 1. 尺寸透传：外部设多大就是多大（抓住 dp/px 错一档这类致命 bug）
    print("[尺寸透传 —— 应用有没有偷偷改写外部设定的尺寸]")
    for w, hh in ((1200, 800), (1400, 900), (2400, 1400), (800, 560)):
        set_rect(h, ax + 100, ay + 100, w, hh)
        expect_rect(f"设定 {w}x{hh} 后应保持", h, w, hh, tol=6)

    # ── 2. 最小尺寸：用户的路径是「拖缩放热区」，Win32 的 SetWindowPos 会绕过
    #      WM_GETMINMAXINFO 的 ptMinTrackSize，所以这里必须用 SendMessage(WM_SIZE)
    #      之外的方式 —— 直接调 SetWindowPos 得到的 300x200 是**系统行为**，
    #      不是应用缺陷。改为断言 ptMinTrackSize 本身报上去了。
    print("\n[最小尺寸 —— AWT minimumSize 有没有真的报给系统]")
    min_w = round(MIN_W_DP * hwnd_scale)
    min_h = round(MIN_H_DP * hwnd_scale)
    track_w, track_h = min_track_size(h)
    check(
        f"WM_GETMINMAXINFO 上报 ptMinTrackSize ≥{min_w}x{min_h}",
        track_w >= min_w - 2 and track_h >= min_h - 2,
        f"实际 {track_w}x{track_h}",
    )

    # ── 3. 超屏宽：比整个屏幕还宽，不能溢出成天文数字
    print("\n[超屏宽 —— 窗口比显示器还宽]")
    set_rect(h, ax - 400, ay, aw + 1200, ah - 100)
    w, hh = expect_rect(
        f"设成 {aw + 1200}x{ah - 100} 不应被放大", h, aw + 1200, ah - 100, tol=8
    )
    ok = w <= 20000
    check("宽度未超过 MAX_WINDOW_SIDE_PX=20000", ok, f"实际 {w}")

    # ── 4. 负坐标副屏：位置整个落在工作区左侧外面
    print("\n[负坐标 —— 模拟左上方副屏 / 拔掉副屏后的残留坐标]")
    set_rect(h, ax - 1500, ay - 800, 1200, 800)
    x, y, w, hh = window_rect(h)
    # 应用不主动搬回窗口（AWT 允许窗口在负坐标），但尺寸必须没被改写
    expect_rect("负坐标下尺寸不变", h, 1200, 800, tol=6)

    # ── 5. 高度超屏：纵向不能把标题栏顶出屏幕
    print("\n[超高 —— 高度超过工作区]")
    set_rect(h, ax + 50, ay, 1000, ah + 900)
    expect_rect(f"高度 {ah + 900} 不被改写", h, 1000, ah + 900, tol=8)
    x, y, w, hh = window_rect(h)
    check("超高时标题栏仍在工作区内", ay - 4 <= y <= ab - 40, f"y={y} 工作区 y∈[{ay},{ab}]")

    # ── 6. 极端长宽比：宽扁矩形下，应用不能把它改成 1:1 或 0 高
    print("\n[极端长宽比]")
    set_rect(h, ax + 100, ay + 100, 2000, 200)
    x, y, w, hh = window_rect(h)
    # SetWindowPos 绕过 minimumSize，所以这里只断言「没有额外失真」：
    # 读回的就是设进去的，既没被放大成 2 倍，也没被压成 0
    ok = w == 2000 and hh == 200
    check(
        "宽扁 2000x200 原样接受（SetWindowPos 绕过 min，故不期望被顶高）",
        ok,
        f"实际 {w}x{hh}",
    )

    # ── 7. 连读稳定性：连续设 20 次同尺寸，尺寸不能自己漂
    print("\n[累积漂移 —— 连续 20 次设定同一矩形]")
    set_rect(h, ax + 200, ay + 150, 1300, 850)
    base = window_rect(h)
    drift = 0
    for _ in range(20):
        set_rect(h, ax + 200, ay + 150, 1300, 850)
        cur = window_rect(h)
        drift = max(drift, abs(cur[2] - base[2]), abs(cur[3] - base[3]))
    check("20 次设定后尺寸漂移 ≤2px", drift <= 2, f"最大漂移 {drift}px  base={base[2]}x{base[3]}")

    # ── 8. 快速抖动：模拟拖动时每帧都用不同尺寸写，不能越写越大
    print("\n[快速抖动 —— 200 次随尺寸写入]")
    max_seen_w = 0
    max_seen_h = 0
    for i in range(200):
        w = 900 + (i * 37) % 900
        hh = 600 + (i * 29) % 500
        user32.SetWindowPos(h, 0, ax + 100, ay + 100, w, hh, SWP_NOZORDER | SWP_NOACTIVATE)
        rw, rh = window_rect(h)[2], window_rect(h)[3]
        max_seen_w = max(max_seen_w, rw)
        max_seen_h = max(max_seen_h, rh)
    # 循环里最大设定是 1799x1099，读回不该超出这个 + 容差
    ok = max_seen_w <= 1799 + 12 and max_seen_h <= 1099 + 12
    check(
        "抖动写入后最大读回未超标",
        ok,
        f"最大读回 {max_seen_w}x{max_seen_h}（设定上限 1799x1099）",
    )

    # ── 9. 归位：最后恢复到正常尺寸，证明前面的极端值没有留下坏状态
    print("\n[恢复 —— 极端值之后能否回到正常]")
    set_rect(h, ax + 120, ay + 80, 1280, 830)
    expect_rect("最终恢复到 1280x830", h, 1280, 830, tol=6)
    expect_inside("最终位置在工作区可视范围内", h, area)

    # ── 10. 无边框窗口的命中测试：确认「缩放不走 Win32」这个前提仍然成立
    #
    # 这一条是**元验证**：如果哪天有人把 `undecorated` 去掉，或给窗口加了
    # `WS_THICKFRAME`，下面这个断言会失败 —— 那是好事，说明缩放路径换了，
    # 本脚本和 WindowGeometryTest 的适用范围都得重新评估。
    print("\n[命中测试 —— 无边框窗口应恒返回 HTCLIENT]")
    win = window_rect(h)
    wx, wy, ww, wh = win
    probe_pts = [
        ("左上角", wx + 1, wy + 1),
        ("上边中", wx + ww // 2, wy + 1),
        ("中心", wx + ww // 2, wy + wh // 2),
    ]
    all_client = all(nchittest(h, px, py) == HTCLIENT for _n, px, py in probe_pts)
    check(
        "边上各点的 WM_NCHITTEST 均为 HTCLIENT（缩放由 Compose 热区负责）",
        all_client,
        f"探测点 {[(n, nchittest(h, px, py)) for n, px, py in probe_pts]}",
    )


# ── 进程管理 ──────────────────────────────────────────────────────


def runtime_classpath():
    with open(CLASSPATH_CACHE, encoding="utf-8") as f:
        return f.read().strip()


def main():
    want = sys.argv[1] if len(sys.argv) > 1 else ""

    if not os.path.isfile(CLASSPATH_CACHE):
        raise SystemExit(
            "缺少 .preview-classpath。先跑：\n"
            "  ./gradlew printRuntimeClasspath --offline -q "
            "| grep RUNTIME_CLASSPATH= | sed 's/^RUNTIME_CLASSPATH=//' > .preview-classpath"
        )

    print("── 启动 geometry 预览窗口 ──")
    proc = subprocess.Popen(
        [
            JAVA,
            "-Dfile.encoding=UTF-8",
            "-Dstumate.preview=geometry",
            "-Dstumate.theme=dark",
            "-cp", runtime_classpath(),
            "com.example.classreminder.dev.UiPreviewKt",
        ],
        cwd=PROJECT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
    )
    try:
        hwnd = None
        deadline = time.time() + 120
        while time.time() < deadline:
            hwnd, _t = find_window()
            if hwnd:
                break
            time.sleep(1.0)
        if not hwnd:
            raise SystemExit("超时：没等到预览窗口")
        time.sleep(5.0)

        scale = ui_scale_of(hwnd)
        print(f"hwnd={hwnd}\n")
        run_cases(hwnd, scale)
    finally:
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )

    total = len(results)
    passed = sum(1 for _n, ok, _d in results if ok)
    print(f"\n== {passed}/{total} 通过 ==")
    for n, ok, d in results:
        if not ok:
            print(f"  FAILED: {n}   {d}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
