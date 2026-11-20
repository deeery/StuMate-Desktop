"""窗口内横幅（.reminder-banner）的动效体检。

为什么不靠真机截图：横幅的入场是 CSS 关键帧，420ms 跑完就没了；
用 `CopyFromScreen` 连拍一是被别的窗口遮挡，二是 40ms 一张根本追不上
62% 那个回弹点。这里在无头 Edge 里直接读 `getBoundingClientRect().top`
与 `getComputedStyle().opacity`，一拍 16ms，拿到的是浏览器真实的合成结果。

触发路径刻意用**设置页的「预览提醒」按钮**（`previewOverlay()` → mode 1 →
`ctx.showReminderBanner`），这样不需要真的等到某节课开始，也不用改业务代码。

用法：
    python tools/measure_banner.py            # 浅色
    python tools/measure_banner.py dark
产物：`design-preview/preview/{theme}-banner-in.png` / `-banner-out.png`
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shoot_preview as sp  # noqa: E402
from cdp import Session, find_page  # noqa: E402

# 一次交互：这一步里点击、采样、截图都在浏览器内同步跑完。
# 采样放在浏览器里用 requestAnimationFrame 排队，比「外面 eval 一次 → 睡 16ms」
# 精度高一个量级：rAF 与关键帧走同一条渲染管线。
SAMPLE = """
window.__bannerTrace = [];
window.__vpid = 0;
window.__bannerDone = new Promise((resolve) => {
  window.__bannerResolve = resolve;
});
(() => {
  const sel = '.reminder-banner';
  const grab = () => {
    const el = document.querySelector(sel);
    if (!el) return { t: performance.now(), missing: true };
    const b = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return {
      t: +performance.now().toFixed(1),
      top: +b.top.toFixed(2),
      h: +b.height.toFixed(2),
      y: +b.y.toFixed(2),
      op: +parseFloat(cs.opacity).toFixed(3),
      an: cs.animationName,
    };
  };
  const tick = () => {
    const row = grab();
    window.__bannerTrace.push(row);
    if (window.__bannerEl && !document.contains(window.__bannerEl)) {
      // 元素被卸载，说明退场也结束了
      window.__bannerResolve(window.__bannerTrace);
      return;
    }
    requestAnimationFrame(tick);
  };
  window.__startTrace = () => {
    window.__bannerTrace = [];
    requestAnimationFrame(tick);
  };
  window.__bannerEl = null;
  return true;
})()
"""

CLICK_PREVIEW = """
(() => {
  // 按钮本身写的是「预览」，「预览提醒」是它左边那一行的文字
  const btn = [...document.querySelectorAll('button')].find(
    (b) => (b.textContent || '').trim() === '预览'
  );
  if (!btn) return 'no-button';
  const hasMode = [...document.querySelectorAll('select')].length;
  btn.click();
  // 等一帧让 React 提交，再取元素
  requestAnimationFrame(() => {
    window.__bannerEl = document.querySelector('.reminder-banner');
    window.__startTrace();
  });
  return 'clicked selects=' + hasMode;
})()
"""

CLICK_DISMISS = """
(() => {
  const el = document.querySelector('.reminder-banner');
  if (!el) return 'no-banner';
  const btn = [...el.querySelectorAll('button')].find((b) =>
    (b.textContent || '').includes('知道了')
  );
  if (!btn) return 'no-dismiss';
  btn.click();
  window.__startTrace();
  return 'dismissed';
})()
"""


def shot(session: Session, path: str) -> None:
    data = session.call("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
    with open(path, "wb") as f:
        f.write(base64.b64decode(data["data"]))


def report(trace: list[dict], label: str) -> None:
    rows = [r for r in trace if not r.get("missing")]
    if not rows:
        print(f"  {label}: 抓不到 .reminder-banner")
        return
    t0 = rows[0]["t"]
    print(f"  {label}: {len(rows)} 帧，起点 top={rows[0]['top']}，终点 top={rows[-1]['top']}")
    for r in rows:
        bar = int(max(0.0, min(1.0, (r["top"] + 40) / 60.0)) * 24)
        print(
            "    t=%6.1fms top=%8.2f op=%5.3f %s%s"
            % (r["t"] - t0, r["top"], r["op"], " " * bar, "|")
        )
    tops = [r["top"] for r in rows]
    print(f"  {label} 轨迹范围: min={min(tops):.2f} max={max(tops):.2f} 终值={tops[-1]:.2f}")


def main() -> None:
    themes = [a for a in sys.argv[1:] if a in ("light", "dark")] or ["light"]
    sp.prepare()
    os.makedirs(sp.OUT, exist_ok=True)

    exe = next((p for p in sp.EDGE_CANDIDATES if os.path.exists(p)), None)
    if not exe:
        raise SystemExit("找不到 Edge/Chrome")

    http_port = sp.free_port(5400)
    httpd = sp.serve(http_port)
    url_base = f"http://127.0.0.1:{http_port}"

    cdp_port = sp.free_port(9600)
    profile = tempfile.mkdtemp(prefix="stumate-banner-")
    proc = sp.launch_browser(exe, cdp_port, profile)
    try:
        session = Session(find_page(cdp_port, timeout=25))
        session.call("Page.enable")
        session.call(
            "Emulation.setDeviceMetricsOverride",
            {"width": sp.WIDTH, "height": sp.HEIGHT, "deviceScaleFactor": 1, "mobile": False},
        )
        for theme in themes:
            session.call(
                "Page.navigate",
                {"url": f"{url_base}/index.html?page=settings&theme={theme}&mode=1"},
            )
            sp.settle(session)
            session.eval(SAMPLE, await_promise=False)

            res = session.eval(CLICK_PREVIEW, await_promise=False)
            print(f"=== {theme} 点击预览提醒: {res}")
            time.sleep(0.18)  # 入场动画 420ms，0.18s 时正处在滑落中段
            shot(session, os.path.join(sp.OUT, f"{theme}-banner-in.png"))
            time.sleep(0.6)  # 等入场结束（含 62%/82% 的回弹）
            trace = session.eval("window.__bannerTrace", await_promise=False)
            report(trace, "入场")

            res = session.eval(CLICK_DISMISS, await_promise=False)
            print(f"  点击知道了: {res}")
            time.sleep(0.09)  # 退场 180ms
            shot(session, os.path.join(sp.OUT, f"{theme}-banner-out.png"))
            time.sleep(0.5)
            trace = session.eval("window.__bannerTrace", await_promise=False)
            report(trace, "退场")
            print("saved", os.path.relpath(os.path.join(sp.OUT, f"{theme}-banner-in.png"), sp.ROOT))
        session.close()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=8)
        except Exception:
            proc.kill()
        httpd.shutdown()
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    main()
