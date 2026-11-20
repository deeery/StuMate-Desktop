# -*- coding: utf-8 -*-
"""验收：知识库是「实验性功能」，默认关闭、可在设置里打开（v1.7.28）。

跑之前先 `npm run tauri dev`，并带 `STUMATE_LITE_BROWSER_ARGS=--remote-debugging-port=9345`。

覆盖：
  1. 设置页有「实验性功能」分区，里面有一个「知识库」开关
  2. 打开时：侧栏有知识库、页面标题带「实验性」角标
  3. 关掉后：侧栏立刻没有知识库，「数据」分区也不再有「知识库目录」那一行
  4. 关掉后 Ctrl+4 不跳页
  5. 关掉后带 `?page=knowledge` 打开：落到说明页，不是白屏、不静默跳走
  6. 在说明页点「去设置里启用」→ 开关打开 → 侧栏知识库回来了
最后把开关恢复成原来的状态（本机旧数据有库根，默认是「开」）。
"""

from __future__ import annotations

import base64
import json
import os
import pathlib
import sys
import time
import urllib.request

sys.path.insert(0, r"E:\Projects\Stumate\Stumate-desktop-lite-v1.7.27\tools")
import cdp  # noqa: E402

PORT = 9345
OUT = pathlib.Path(os.environ.get("TEMP", ".")) / "stumate-kb-flag"
OUT.mkdir(parents=True, exist_ok=True)

failures: list[str] = []


def check(ok: bool, msg: str) -> None:
    print(("  PASS  " if ok else "  FAIL  ") + msg)
    if not ok:
        failures.append(msg)


def shot(sess, name: str) -> None:
    res = sess.call("Page.captureScreenshot", {"format": "png"})
    path = OUT / f"{name}.png"
    path.write_bytes(base64.b64decode(res["data"]))
    print(f"  shot  {path}")


def page_main() -> str:
    pages = [
        t
        for t in json.loads(
            urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=3).read()
        )
        if t.get("type") == "page"
    ]
    main = next(
        (
            p
            for p in pages
            if p["url"].startswith("http://localhost:5173") and "overlay=1" not in p["url"]
        ),
        None,
    )
    if main is None:
        raise SystemExit("找不到主窗口")
    return main["webSocketDebuggerUrl"]


# 侧栏项名称列表：收起成图标栏时文字被 CSS 压掉，但 title 还在
NAV_LIST = """
(() => [...document.querySelectorAll('.nav-item')]
  .map((b) => b.getAttribute('title') || (b.querySelector('.nav-label') || {}).textContent || '')
  .map((s) => (s || '').trim()))()
"""

# 找到开关（`ui.tsx` 的 Toggle 带 role="switch" + aria-label），返回它开着没有
KB_TOGGLE = """
(() => {
  const box = document.querySelector('[role="switch"][aria-label="知识库"]');
  if (!box) return { found: false, count: document.querySelectorAll('[role="switch"]').length };
  return { found: true, on: box.getAttribute('aria-checked') === 'true' };
})()
"""

CLICK_KB_TOGGLE = """
(() => {
  const box = document.querySelector('[role="switch"][aria-label="知识库"]');
  if (!box) return 'no-toggle';
  box.click();
  return 'ok';
})()
"""


def goto(sess, label: str) -> bool:
    return bool(
        sess.eval(
            "(() => { const el = document.querySelector('.nav-item[title=\"%s\"]');"
            " if (!el) return false; el.click(); return true; })()" % label
        )
    )


def main() -> int:
    sess = cdp.Session(page_main())
    try:
        time.sleep(1.0)
        # 先把开关确认成「开」，让起点确定
        goto(sess, "设置")
        time.sleep(0.8)
        state = sess.eval(KB_TOGGLE)
        check(bool(state and state.get("found")), "设置里有「实验性功能 → 知识库」开关")
        if not (state and state.get("found")):
            return 1
        if not state["on"]:
            sess.eval(CLICK_KB_TOGGLE)
            time.sleep(0.6)
        check(bool(sess.eval(KB_TOGGLE)["on"]), "开关处于打开状态")
        shot(sess, "1-settings-on")

        # 打开时：侧栏有知识库，页面标题有「实验性」角标
        nav = sess.eval(NAV_LIST)
        check("知识库" in nav, f"侧栏有知识库（{nav}）")
        check(goto(sess, "知识库"), "点侧栏能进知识库")
        time.sleep(1.0)
        tags = sess.eval(
            "(() => [...document.querySelectorAll('.page-title-area .chip')].map((e) => e.textContent.trim()))()"
        )
        check("实验性" in (tags or []), f"页头有「实验性」角标（{tags}）")
        shot(sess, "2-knowledge-tagged")

        # 关掉：侧栏立刻消失，数据分区不再有「知识库目录」
        goto(sess, "设置")
        time.sleep(0.7)
        sess.eval(CLICK_KB_TOGGLE)
        time.sleep(0.7)
        check(not sess.eval(KB_TOGGLE)["on"], "开关已关")
        nav = sess.eval(NAV_LIST)
        check("知识库" not in nav, f"侧栏不再有知识库（{nav}）")
        has_row = sess.eval(
            "(() => (document.body.textContent || '').includes('知识库目录'))()"
        )
        check(not has_row, "「数据」分区不再出现知识库目录")
        shot(sess, "3-settings-off")

        # Ctrl+4 在关着时不该跳页
        before = sess.eval("document.querySelector('.page-title').textContent")
        sess.eval(
            "(() => { window.dispatchEvent(new KeyboardEvent('keydown',"
            " { key: '4', ctrlKey: true, bubbles: true })); return 'ok'; })()"
        )
        time.sleep(0.5)
        after = sess.eval("document.querySelector('.page-title').textContent")
        check(before == after, f"Ctrl+4 关着时不跳页（{before} → {after}）")

        # `?page=knowledge`：落到说明页
        sess.eval("location.href = 'http://localhost:5173/?page=knowledge'")
        time.sleep(3.0)
        text = sess.eval("document.body.textContent || ''")
        check("知识库还没启用" in text, "带 ?page=knowledge 打开时给出说明页")
        check("去设置里启用" in text, "说明页有「去设置里启用」按钮")
        shot(sess, "4-locked")

        # 从说明页一键回设置并打开
        sess.eval(
            """
            (() => {
              const b = [...document.querySelectorAll('button')]
                .find((n) => (n.textContent || '').includes('去设置里启用'));
              if (!b) return 'no-btn';
              b.click();
              return 'ok';
            })()
            """
        )
        time.sleep(0.9)
        check(
            bool(sess.eval("document.querySelector('.page-title').textContent === '设置'")),
            "点按钮跳到设置页",
        )
        sess.eval(CLICK_KB_TOGGLE)
        time.sleep(0.7)
        check(bool(sess.eval(KB_TOGGLE)["on"]), "重新打开开关")
        nav = sess.eval(NAV_LIST)
        check("知识库" in nav, f"侧栏知识库回来了（{nav}）")
        shot(sess, "5-settings-on-again")

        # 回到干净的 URL，别把 ?page= 留给下一次启动
        sess.eval("location.href = 'http://localhost:5173/'")
        time.sleep(2.5)
    finally:
        sess.close()

    print()
    if failures:
        print(f"RESULT: FAIL（{len(failures)} 项）")
        for f in failures:
            print("  - " + f)
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
