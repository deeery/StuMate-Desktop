"""侧栏收起态（rail）的几何体检 + 截图。

为什么单独一个脚本：`shoot_preview.py` 只截「展开态」的五页。侧栏收起后图标
是否还落在栏心，是这个 UI 唯一没法从源码看出来的东西 —— padding / gap /
`align-items: center` 三者叠加，任何一处改错都只表现为「图标歪几像素」。
所以这里既出图，也把每个图标的包围盒读成数字（图标中心相对栏心偏移）。

用法：
    python tools/shoot_rail.py            # 浅色 + 深色，today 页，收起态
    python tools/shoot_rail.py dark       # 只跑深色
产物：`design-preview/preview/{theme}-today-rail.png`
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

MEASURE = """
(() => {
  const r = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return {
      x: +b.x.toFixed(2), y: +b.y.toFixed(2),
      w: +b.width.toFixed(2), h: +b.height.toFixed(2),
      cx: +(b.x + b.width / 2).toFixed(2),
      cy: +(b.y + b.height / 2).toFixed(2),
      pl: cs.paddingLeft, pr: cs.paddingRight,
      gap: cs.gap, jc: cs.justifyContent, ai: cs.alignItems,
    };
  };
  const side = document.querySelector('.sidebar');
  const sb = side.getBoundingClientRect();
  const items = [...document.querySelectorAll('.nav-item')].map((el) => {
    const svg = el.querySelector('svg');
    const ib = svg ? svg.getBoundingClientRect() : null;
    return {
      label: (el.querySelector('.nav-label') || {}).textContent || '',
      box: r(el),
      icon: r(svg),
      iconCxInBox: ib ? +(ib.x + ib.width / 2 - el.getBoundingClientRect().x).toFixed(2) : null,
      iconCyInBox: ib ? +(ib.y + ib.height / 2 - el.getBoundingClientRect().y).toFixed(2) : null,
    };
  });
  const brand = document.querySelector('.brand-row');
  const mark = brand ? brand.querySelector('svg') : null;
  const foot = document.querySelector('.sidebar-foot');
  const dot = document.querySelector('.status-dot');
  return {
    rail: side.classList.contains('rail'),
    sidebar: r(side),
    sidebarCx: +(sb.x + sb.width / 2).toFixed(2),
    brand: r(brand),
    mark: r(mark),
    markCxInSidebar: mark ? +(mark.getBoundingClientRect().x + mark.getBoundingClientRect().width / 2 - sb.x).toFixed(2) : null,
    items,
    foot: r(foot),
    dot: r(dot),
    dotCxInSidebar: dot ? +(dot.getBoundingClientRect().x + dot.getBoundingClientRect().width / 2 - sb.x).toFixed(2) : null,
  };
})()
"""


TRACE = """
(() => {
  const side = document.querySelector('.sidebar');
  const sb = side.getBoundingClientRect();
  const mid = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return +(b.x + b.width / 2 - sb.x).toFixed(1);
  };
  return {
    t: Math.round(performance.now()),
    rail: side.classList.contains('rail'),
    w: +sb.width.toFixed(1),
    mark: mid(document.querySelector('.brand-row svg')),
    icon: mid(document.querySelector('.nav-item svg')),
    dot: mid(document.querySelector('.status-dot')),
  };
})()
"""


def main() -> None:
    args = [a for a in sys.argv[1:] if a in ("light", "dark")] or ["light", "dark"]
    sp.prepare()
    os.makedirs(sp.OUT, exist_ok=True)

    exe = next((p for p in sp.EDGE_CANDIDATES if os.path.exists(p)), None)
    if not exe:
        raise SystemExit("找不到 Edge/Chrome")

    http_port = sp.free_port(5300)
    httpd = sp.serve(http_port)
    url_base = f"http://127.0.0.1:{http_port}"

    cdp_port = sp.free_port(9500)
    profile = tempfile.mkdtemp(prefix="stumate-rail-")
    proc = sp.launch_browser(exe, cdp_port, profile)
    try:
        session = Session(find_page(cdp_port, timeout=25))
        session.call("Page.enable")
        session.call(
            "Emulation.setDeviceMetricsOverride",
            {"width": sp.WIDTH, "height": sp.HEIGHT, "deviceScaleFactor": 1, "mobile": False},
        )
        for theme in args:
            session.call("Page.navigate", {"url": f"{url_base}/index.html?page=today&theme={theme}"})
            sp.settle(session)
            # 先量一遍展开态：这次改的虽然是 .rail 下的规则，但 padding / gap /
            # width 都是会串味的属性，展开态必须证明没被顺手改坏。
            before = session.eval(MEASURE, await_promise=False)
            print(f"=== {theme} (expanded) ===")
            print(
                json.dumps(
                    {
                        "rail": before["rail"],
                        "sidebarW": before["sidebar"]["w"],
                        "brandX": before["brand"]["x"],
                        "brandW": before["brand"]["w"],
                        "brandGap": before["brand"]["gap"],
                        "markCxInSidebar": before["markCxInSidebar"],
                        "firstIconCxInBox": before["items"][0]["iconCxInBox"],
                        "footPl": before["foot"]["pl"],
                        "footW": before["foot"]["w"],
                        "dotCxInSidebar": before["dotCxInSidebar"],
                    },
                    ensure_ascii=False,
                )
            )
            # 点「收起侧栏」
            session.eval(
                "(() => { const b = document.querySelector('.nav-toggle');"
                " if (b) b.click(); return document.querySelector('.sidebar').classList.contains('rail'); })()",
                await_promise=False,
            )
            # 收起动画中途的采样：终态正确不代表过程不跳。.sidebar.rail 上是
            # align-items:center，子项宽度先钉成 48、栏宽再过渡，若没把子项
            # 贴左，图标会先被「居中」到栏心、再随栏宽收缩一路滑到左边。
            trace = []
            for _ in range(7):
                trace.append(session.eval(TRACE, await_promise=False))
                time.sleep(0.04)
            time.sleep(0.4)  # 等 220ms 宽度过渡 + 文字收拢
            data = session.eval(MEASURE, await_promise=False)
            print(f"=== {theme} ===")
            print(json.dumps(data, ensure_ascii=False, indent=1))
            print(f"=== {theme} (collapse trace) ===")
            for row in trace:
                print(
                    "  t=%4dms w=%6.1f rail=%-5s mark=%6.1f icon=%6.1f dot=%s"
                    % (row["t"], row["w"], row["rail"], row["mark"], row["icon"], row["dot"])
                )
            shot = session.call("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
            path = os.path.join(sp.OUT, f"{theme}-today-rail.png")
            with open(path, "wb") as f:
                f.write(base64.b64decode(shot["data"]))
            print("saved", os.path.relpath(path, sp.ROOT))
            # 回到展开态，下一轮从干净状态开始
            session.eval(
                "(() => { const b = document.querySelector('.nav-toggle'); if (b) b.click(); return true; })()",
                await_promise=False,
            )
            time.sleep(0.5)
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
