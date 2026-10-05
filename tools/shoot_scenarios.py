"""批量跑 UI 预览场景并截图。

验收环境里鼠标注入不可用（`SetCursorPos` / `mouse_event` / `PostMessage` 全被系统忽略），
所以没法「点进设置 → 点账号 → 点登录」这样走一遍。改成：每个场景起一次预览进程，
用 `-Ppreview=<场景>` 直接落到目标状态，截完就杀进程。

用法：
  python shoot_scenarios.py <输出目录> [场景=文件名 ...]
  python shoot_scenarios.py <输出目录> sync           # 同步卡全部 8 个状态
  python shoot_scenarios.py <输出目录> sync:done=sync-done.png   # 只跑一个状态

不传场景就按内置清单跑一遍。

环境变量：
  STUMATE_THEME=light|dark   预览主题（默认 dark）
  STUMATE_JAVA=<path>        指定 java 可执行文件
  STUMATE_EMAIL=<邮箱>       需要真实登录的场景（signedin / setpwd / modpwd）用这个账号
  STUMATE_PASSWORD=<密码>
  STUMATE_SYNC_STATE=<状态>  配合 `sync` 参数只跑指定状态

⚠️ **别手工 `taskkill /IM java.exe` 来清预览进程** —— 那会连带杀掉 Gradle
守护进程，之后 `./gradlew` 会静默复用旧 class，截出来的图看着像「改了没生效」。
本脚本按 PID 精确杀（`taskkill /F /T /PID`），互不干扰。
"""
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import sys
import time

from PIL import ImageGrab

user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WINDOW_TITLE = "StuMatePreview"

# 直接用 java 起进程，不走 Gradle：
#  1) 每次 Gradle 冷启动要十几秒，跑十几个场景太亏；
#  2) 从 Python 调 `cmd /c gradlew.bat` 会被安全策略拦掉（且实测静默失败）。
# classpath 由 `./gradlew printRuntimeClasspath` 生成，缓存在 .preview-classpath。
JAVA = os.environ.get("STUMATE_JAVA") or r"E:\DevTools\Java\jdk-17.0.12+7\bin\java.exe"
CLASSPATH_CACHE = os.path.join(PROJECT, ".preview-classpath")

# 预览进程的主题。以前这里写死 dark，导致「浅色验收」只能绕过本脚本、手敲 java 命令；
# 而浅色/深色双模式恰恰是视觉验收的常规要求（网格线、描边这类改动尤其要看两遍）。
# 现在由 STUMATE_THEME 环境变量覆盖，默认仍是 dark（保持既有调用行为不变）。
THEME = os.environ.get("STUMATE_THEME") or "dark"


def runtime_classpath():
    if os.path.isfile(CLASSPATH_CACHE):
        with open(CLASSPATH_CACHE, encoding="utf-8") as f:
            cp = f.read().strip()
            if cp:
                check_fresh(cp)
                return cp
    raise SystemExit(
        "缺少 .preview-classpath。先跑：\n"
        "  ./gradlew printRuntimeClasspath --offline -q | grep RUNTIME_CLASSPATH= "
        "| sed 's/^RUNTIME_CLASSPATH=//' > .preview-classpath"
    )


def check_fresh(cp):
    """拦住「改了源码但 classpath 还指着旧 class 目录」。

    这个假阴性非常贵：截图看起来一切正常，只是**新加的东西一个都不在**，
    于是很容易得出「改了没生效」的错误结论，再去瞎改代码。
    实测踩过一次 —— 项目里同时存在 `build/` 和隔离构建目录
    `F:/DownloadQQ/stumate-altbuild/`，而 `.preview-classpath` 里存的是
    前者（旧的），最后一次真正编译却发生在后者。

    判据：classpath 里的 classes 目录，其最新 class 必须比 `src/main/kotlin`
    里最新的 .kt 还新。不满足就**直接报错**，不截一张假的图。
    """
    import glob

    dirs = [p for p in cp.split(os.pathsep) if p and "classes" in p.lower()]
    srcs = glob.glob(os.path.join(PROJECT, "src", "main", "kotlin", "**", "*.kt"),
                     recursive=True)
    if not dirs or not srcs:
        return
    newest_src = max(os.path.getmtime(p) for p in srcs)
    newest_cls = 0.0
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for base, _dirs, files in os.walk(d):
            for f in files:
                if f.endswith(".class"):
                    t = os.path.getmtime(os.path.join(base, f))
                    if t > newest_cls:
                        newest_cls = t
    if newest_cls and newest_cls < newest_src:
        raise SystemExit(
            "❌ .preview-classpath 指向的 class 目录比源码旧，截出来会是「改了没生效」。\n"
            f"   最新 .kt   {time.strftime('%H:%M:%S', time.localtime(newest_src))}\n"
            f"   最新 .class{time.strftime('%H:%M:%S', time.localtime(newest_cls))}\n"
            "   先重新编译，再刷新缓存（注意用你真正在用的那个构建目录）：\n"
            "     ./gradlew compileKotlin --offline\n"
            "     ./gradlew printRuntimeClasspath --offline -q "
            "| grep RUNTIME_CLASSPATH= | sed 's/^RUNTIME_CLASSPATH=//' > .preview-classpath"
        )

DEFAULT_SCENARIOS = [
    ("account", "acc-01-account-signedout.png"),
    ("firstrun", "acc-00-first-run.png"),
    ("overlay", "overlay-01-reminder-card.png"),
    ("auth", "acc-02-login-dialog.png"),
    ("authfail", "acc-03-login-failed.png"),
    ("register", "acc-04-register-dialog.png"),
    ("registerfail", "acc-05-register-failed.png"),
    ("forgot", "acc-06-reset-dialog.png"),
    ("devices", "acc-07-devices-dialog.png"),
    ("oauth", "acc-08-oauth-waiting.png"),
]

# 同步卡的 8 个状态。`preview` 是场景名（都走 sync），`state` 走 -Dstumate.sync。
# 单独列出来是因为它们要传第 4 个系统属性，而 run_scenario 只认 (场景, 文件名) 两元组。
SYNC_STATES = [
    ("offline", "sync-01-offline.png"),
    ("idle", "sync-02-idle.png"),
    ("done", "sync-03-done.png"),
    ("busy", "sync-04-busy.png"),
    ("override", "sync-05-override.png"),
    ("failed", "sync-06-failed.png"),
    ("skipped", "sync-07-skipped.png"),
    ("preinit", "sync-08-preinit-backup.png"),
]

# 这些场景要靠真实登录态才能渲染出目标界面，必须提供 STUMATE_EMAIL / STUMATE_PASSWORD
NEEDS_LOGIN = ("signedin", "setpwd", "modpwd")

# setpwd 走令牌注入而不是登录：无密码账号登不进去，
# 而「已登录 + 还没有密码」正是那个场景要验的状态
NEEDS_TOKEN = ("setpwd",)


def enum_visible_windows():
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


def find_preview(want_ready=False):
    """找预览窗口。

    want_ready=True 时只认标题带 ' ready' 后缀的窗口 ——
    预览进程登录成功后才会在标题上打这个标记。死等固定秒数会出假阴性：
    登录慢一点就截到「未登录」，看起来像功能没做。
    """
    for h, t, p in enum_visible_windows():
        if WINDOW_TITLE.lower() not in t.lower():
            continue
        if want_ready and "ready" not in t.lower():
            continue
        return h, p
    return None, None


def client_rect(h):
    cr = wt.RECT()
    user32.GetClientRect(h, ctypes.byref(cr))
    origin = wt.POINT(0, 0)
    user32.ClientToScreen(h, ctypes.byref(origin))
    return (origin.x, origin.y, origin.x + cr.right, origin.y + cr.bottom)


def rect_of(h):
    r = wt.RECT()
    user32.GetWindowRect(h, ctypes.byref(r))
    return r.left, r.top, r.right, r.bottom


def shoot(hwnd, pid, out_path, pad=0):
    """主窗口取客户区；同进程的其它窗口（对话框）按窗口矩形并入

    pad 必须是 0。预览窗口是 undecorated 的，客户区**就是**可视区域的全部，
    没有边框也没有投影；往外多抓 1 个像素就会把窗口后面的终端内容拍进来
    （表现为四边各一圈杂色 + 顶部半行别人家的文字）。
    """
    rects = [client_rect(hwnd)]
    for h, _, p in enum_visible_windows():
        if p == pid and h != hwnd and user32.IsWindowVisible(h):
            r = rect_of(h)
            if r[2] > r[0] and r[3] > r[1]:
                rects.append(r)

    left = min(r[0] for r in rects) - pad
    top = min(r[1] for r in rects) - pad
    right = max(r[2] for r in rects) + pad
    bottom = max(r[3] for r in rects) + pad
    img = ImageGrab.grab(bbox=(left, top, right, bottom), all_screens=True)
    img.save(out_path)
    return len(rects), img.size


def run_scenario(scenario, out_path, timeout=150, settle=4.0, state=None):
    print(f"── 场景 {scenario}{'/' + state if state else ''} ──")
    cmd = [
        JAVA,
        "-Dfile.encoding=UTF-8",
        f"-Dstumate.preview={scenario}",
        f"-Dstumate.theme={THEME}",
    ]
    # sync 场景要第 4 个属性指定卡片状态。
    # 少了它 UiPreview 会回退到 offline（只显示入口行），
    # 截出来 8 张几乎一样的图 —— 看着像「改了没生效」，实际是参数没传。
    if state:
        cmd.append(f"-Dstumate.sync={state}")
    # 需要真实登录的场景（signedin / setpwd / modpwd）必须带上凭据，
    # 否则 UiPreview 里那次 login 会被跳过，截出来的是「未登录」那一屏，
    # 而且 setpwd/modpwd 的对话框根本不会组合出来（它依赖登录态）。
    if scenario in NEEDS_TOKEN or (
        scenario == "signedin" and os.environ.get("STUMATE_TOKEN")
    ):
        token = os.environ.get("STUMATE_TOKEN") or ""
        if not token:
            print("   跳过：该场景需要 STUMATE_TOKEN")
            return False
        cmd.append(f"-Dstumate.token={token}")
        email = os.environ.get("STUMATE_EMAIL") or ""
        if email:
            cmd.append(f"-Dstumate.email={email}")
    elif scenario in NEEDS_LOGIN:
        email = os.environ.get("STUMATE_EMAIL") or ""
        password = os.environ.get("STUMATE_PASSWORD") or ""
        if not email or not password:
            print("   跳过：该场景需要 STUMATE_EMAIL / STUMATE_PASSWORD")
            return False
        cmd.append(f"-Dstumate.email={email}")
        cmd.append(f"-Dstumate.password={password}")
    # 假装当前是更旧的版本，用来截「有新版本」那张图（`update` 场景）。
    # 不传就是真实版本 —— 那时 update 场景截到的是「已是最新」。
    # 两个都要截：只有「已是最新」能证明查得到，只有「有新版本」能证明比得对。
    override = os.environ.get("STUMATE_CURRENT_VERSION")
    if override:
        cmd.append(f"-Dstumate.currentVersion={override}")
    cmd += [
        "-cp", runtime_classpath(),
        "com.example.classreminder.dev.UiPreviewKt",
    ]
    proc = subprocess.Popen(
        cmd,
        cwd=PROJECT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
    )
    try:
        # 需要登录的场景：等窗口标题出现 ' ready'（预览进程登录成功后才打），
        # 超时了要当失败报出来 —— 截一张「未登录」比不截更糟。
        waits_ready = scenario in ("signedin", "setpwd", "modpwd", "firstrun", "update")
        deadline = time.time() + timeout
        hwnd = pid = None
        while time.time() < deadline:
            hwnd, pid = find_preview(want_ready=waits_ready)
            if hwnd:
                break
            time.sleep(1.0)
        if not hwnd:
            if waits_ready:
                print("   超时：等不到「登录就绪」信号（凭据不对或服务端不可达）")
            else:
                print("   超时：没等到预览窗口")
            return False

        # 等窗口稳定 + 首个网络请求回来。
        # authfail / oauth 要真的打一次服务端，多给几秒；纯静态场景 4 秒足够。
        time.sleep(settle)
        n, size = shoot(hwnd, pid, out_path)
        print(f"   已保存 {out_path}  {size[0]}x{size[1]}（合并 {n} 个窗口）")
        return True
    finally:
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        time.sleep(2.5)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)

    scenarios = DEFAULT_SCENARIOS
    if len(sys.argv) > 2:
        scenarios = []
        for arg in sys.argv[2:]:
            name, _, fname = arg.partition("=")
            # `sync:done=sync-done.png` —— 冒号后面是同步卡的状态
            scenario, _, state = name.partition(":")
            scenarios.append((scenario, fname or f"{scenario}.png", state or None))

    if len(sys.argv) == 2 or (len(sys.argv) > 2 and sys.argv[2] == "sync"):
        # 只跑同步卡：`-Psync` 走 STUMATE_SYNC_STATE 覆盖，或跑全部 8 个
        only = os.environ.get("STUMATE_SYNC_STATE")
        if only:
            scenarios = [("sync", f"sync-{only}.png", only)]
        else:
            scenarios = [("sync", fname, st) for st, fname in SYNC_STATES]

    ok = 0
    for item in scenarios:
        scenario, fname = item[0], item[1]
        state = item[2] if len(item) > 2 else None
        settle = 6.0 if scenario in ("authfail", "registerfail", "oauth") else 4.0
        if scenario in NEEDS_LOGIN:
            # 要跑完 login（含一次 scrypt 校验）+ /me，多给点时间
            settle = 9.0
        if run_scenario(
            scenario, os.path.join(out_dir, fname), settle=settle, state=state
        ):
            ok += 1
    total = len(scenarios)
    print(f"完成 {ok}/{total}")
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
