"""便签页「两步走」的现场验收：点行 → 只读详情 → 点编辑 → 表单。

为什么不是只跑单元测试：这一版改的是**交互顺序**（单击不再直接进表单），
单元测试只覆盖纯函数，`onClick` 到底挂在谁身上、面板宽度换没换、退场会不会闪白框，
只有真的点一次才知道。

用法（应用需带 CDP 端口启动）：
    set STUMATE_LITE_BROWSER_ARGS=--remote-debugging-port=9345
    npm run tauri dev
    python tools/verify_notes_two_step.py
"""
from __future__ import annotations

import base64
import os
import pathlib
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp  # noqa: E402

PORT = int(os.environ.get("STUMATE_CDP_PORT", "9345"))
OUT = pathlib.Path(os.environ.get("TEMP", "C:/Temp")) / "stumate-notes"
OUT.mkdir(parents=True, exist_ok=True)

ok_all = True


def check(ok: bool, what: str) -> None:
    global ok_all
    ok_all = ok_all and bool(ok)
    print(("  PASS  " if ok else "  FAIL  ") + what)


def shot(sess: cdp.Session, name: str) -> pathlib.Path:
    res = sess.call("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
    path = OUT / f"{name}.png"
    path.write_bytes(base64.b64decode(res["data"]))
    print(f"  shot  {path}")
    return path


def main() -> int:
    # overlay 那条（?overlay=1）是提醒浮窗，验收要看主窗口 —— 按 url 精确挑，
    # 不能用 find_page 的模糊匹配（它会先命中浮窗，然后整轮断言全跑在浮窗上）
    pages = []
    import json
    import urllib.request

    for t in json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=3).read()):
        if t.get("type") == "page":
            pages.append(t)
    main = next(
        (
            p
            for p in pages
            if p["url"].startswith("http://localhost:5173") and "overlay=1" not in p["url"]
        ),
        None,
    )
    if main is None:
        print("FAIL  找不到主窗口（只有浮窗）")
        return 1
    url = main["webSocketDebuggerUrl"]

    sess = cdp.Session(url)
    try:
        # 1) 去便签页（侧栏可能收起成图标态，文字被藏起来，所以按 title 找）
        sess.eval(
            """
            (() => {
              const el = document.querySelector('.nav-item[title="便签"]');
              if (!el) return 'no-nav';
              el.click();
              return 'ok';
            })()
            """
        )
        time.sleep(0.9)

        rows = sess.eval("document.querySelectorAll('.note-row').length")
        check(bool(rows), f"便签表格有行（{rows}）")
        shot(sess, "1-list")

        # 2) 点第一行 → 应该只出详情，不该出现输入框
        sess.eval("document.querySelector('.note-row').click()")
        time.sleep(0.5)
        state = sess.eval(
            """
            (() => {
              const dock = document.querySelector('.note-dock');
              const inner = document.querySelector('.note-dock-inner');
              return {
                dock: !!dock,
                width: dock ? Math.round(dock.getBoundingClientRect().width) : 0,
                inputs: inner ? inner.querySelectorAll('input,textarea').length : -1,
                hasEdit: inner ? [...inner.querySelectorAll('button')]
                  .some((b) => (b.textContent || '').trim() === '编辑') : false,
                title: inner ? (inner.querySelector('.card > div:nth-child(2)')?.textContent || '') : '',
              };
            })()
            """
        )
        check(state["dock"] is True, "右侧出现面板")
        check(state["width"] == 268, f"详情宽度 268（实际 {state['width']}）")
        check(state["inputs"] == 0, f"详情里没有输入框（实际 {state['inputs']} 个）")
        check(state["hasEdit"] is True, "详情里有「编辑」按钮")
        shot(sess, "2-detail")

        # 3) 点「编辑」→ 换成表单，宽度 340
        sess.eval(
            """
            (() => {
              const b = [...document.querySelectorAll('.note-dock-inner button')]
                .find((n) => (n.textContent || '').trim() === '编辑');
              b.click();
              return 'ok';
            })()
            """
        )
        time.sleep(0.5)
        state2 = sess.eval(
            """
            (() => {
              const dock = document.querySelector('.note-dock');
              const inner = document.querySelector('.note-dock-inner');
              return {
                width: dock ? Math.round(dock.getBoundingClientRect().width) : 0,
                inputs: inner ? inner.querySelectorAll('input,textarea').length : -1,
                save: inner ? [...inner.querySelectorAll('button')]
                  .some((b) => (b.textContent || '').trim() === '保存') : false,
              };
            })()
            """
        )
        check(state2["width"] == 340, f"编辑宽度 340（实际 {state2['width']}）")
        check(state2["inputs"] > 0, f"编辑里有输入框（{state2['inputs']} 个）")
        check(state2["save"] is True, "编辑里有「保存」")
        shot(sess, "3-editor")

        # 4) 「取消」应退回详情（不是整块收起）
        sess.eval(
            """
            (() => {
              const b = [...document.querySelectorAll('.note-dock-inner button')]
                .find((n) => (n.textContent || '').trim() === '取消');
              b.click();
              return 'ok';
            })()
            """
        )
        time.sleep(0.5)
        back = sess.eval(
            """
            (() => {
              const dock = document.querySelector('.note-dock');
              return {
                width: dock ? Math.round(dock.getBoundingClientRect().width) : 0,
                inputs: document.querySelectorAll('.note-dock-inner input, .note-dock-inner textarea').length,
              };
            })()
            """
        )
        check(back["width"] == 268, f"取消后回到详情 268（实际 {back['width']}）")
        check(back["inputs"] == 0, "取消后不再是表单")
        shot(sess, "4-back-to-detail")

        # 5) 双击行 = 直接进编辑（快捷方式，老用户的手感不能丢）
        sess.eval(
            """
            (() => {
              const row = document.querySelector('.note-row');
              row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, detail: 2 }));
              return 'ok';
            })()
            """
        )
        time.sleep(0.45)
        dbl = sess.eval(
            "document.querySelectorAll('.note-dock-inner input, .note-dock-inner textarea').length"
        )
        check(dbl > 0, f"双击行直接进编辑（{dbl} 个输入框）")

        # 6) 编辑栏的 X = 返回详情；详情栏的 X = 收起整块（两级各有各的「退一步」）
        sess.eval("document.querySelector('.note-dock-inner .icon-btn').click()")
        time.sleep(0.5)
        step_back = sess.eval(
            "Math.round(document.querySelector('.note-dock').getBoundingClientRect().width)"
        )
        check(step_back == 268, f"编辑栏 X → 退回详情 268（实际 {step_back}）")

        sess.eval("document.querySelector('.note-dock-inner .icon-btn').click()")
        time.sleep(0.6)
        closed = sess.eval("document.querySelector('.note-dock') === null")
        check(closed is True, "详情栏 X → 右列整块卸掉")
        shot(sess, "5-closed")

        # 7) 新建便签 → 直接进编辑（没有详情这一步）
        sess.eval(
            """
            (() => {
              const b = [...document.querySelectorAll('button')]
                .find((n) => (n.textContent || '').trim() === '新建便签');
              b.click();
              return 'ok';
            })()
            """
        )
        time.sleep(0.5)
        create = sess.eval(
            """
            (() => {
              const inner = document.querySelector('.note-dock-inner');
              return {
                width: Math.round(inner.parentElement.getBoundingClientRect().width),
                title: (inner.textContent || '').includes('新建便签'),
              };
            })()
            """
        )
        check(create["width"] == 340 and create["title"], "新建便签直接进 340 宽的编辑表单")
        shot(sess, "6-create")

        # 8) 新建时「取消」= 收起（没有详情可退）
        sess.eval(
            """
            (() => {
              const b = [...document.querySelectorAll('.note-dock-inner button')]
                .find((n) => (n.textContent || '').trim() === '取消');
              b.click();
              return 'ok';
            })()
            """
        )
        time.sleep(0.6)
        gone = sess.eval("document.querySelector('.note-dock') === null")
        check(gone is True, "新建时取消 → 右列收起")
    finally:
        sess.close()

    print("\nRESULT:", "PASS" if ok_all else "FAIL")
    return 0 if ok_all else 1


if __name__ == "__main__":
    raise SystemExit(main())
