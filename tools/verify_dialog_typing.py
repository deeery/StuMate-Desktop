"""回归：对话框里连续输入不能被打断，容器也不该被描上焦点环。

背景（v1.7.28 修复的 bug）：`ui.tsx` 的 `Dialog` 把 `onClose` 写进了 useEffect 的
deps，而父组件传的是内联箭头函数 —— 每敲一个字父组件重渲染一次、effect 重跑一次、
`ref.current?.focus()` 把焦点从输入框抢回 `.dialog`。表现是：
  1. 对话框里只能输入一个字符（第二个键落到容器上丢掉）；
  2. 容器带 `tabIndex={-1}`，吃到全局 `[tabindex]:focus-visible` 的强调色外环，
     整扇窗口被描一圈蓝边。

断言：连打 3 个字符后 activeElement 仍是输入框、三个字符都在、`.dialog` 无 outline；
Esc 仍然能关窗（Esc 由 window 监听 + closeRef 转一手，这条防的是"修焦点把 Esc 修坏"）。

用法：先 `npm run tauri dev` 且带 `STUMATE_LITE_BROWSER_ARGS=--remote-debugging-port=9345`，
然后 `python tools/verify_dialog_typing.py`。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.request

import cdp

PORT = 9345


def main() -> int:
    pages = [
        t
        for t in json.loads(
            urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=3).read()
        )
        if t.get("type") == "page"
    ]
    main_page = next(
        (
            p
            for p in pages
            if p["url"].startswith("http://localhost:5173") and "overlay=1" not in p["url"]
        ),
        None,
    )
    if main_page is None:
        print("FAIL  找不到主窗口")
        return 1

    sess = cdp.Session(main_page["webSocketDebuggerUrl"])
    failures = 0

    def check(ok: bool, label: str) -> None:
        nonlocal failures
        if ok:
            print(f"  PASS  {label}")
        else:
            failures += 1
            print(f"  FAIL  {label}")

    try:
        # 去循环提醒页 → 新建规则（这个对话框里有 5 个受控输入，最适合复现）
        sess.eval(
            """
            (() => {
              const el = document.querySelector('.nav-item[title="循环提醒"]');
              if (el) el.click();
              return !!el;
            })()
            """
        )
        time.sleep(0.9)
        opened = sess.eval(
            """
            (() => {
              const b = [...document.querySelectorAll('button')]
                .find((x) => (x.textContent || '').trim() === '新建规则');
              if (!b) return false;
              b.click();
              return true;
            })()
            """
        )
        check(bool(opened), "打开「新建规则」对话框")
        time.sleep(0.6)

        probe = """(() => {
          const d = document.querySelector('.dialog');
          if (!d) return {ok: false};
          const ae = document.activeElement;
          const inp = d.querySelector('input.input');
          return {
            ok: true,
            activeTag: ae.tagName,
            activeCls: ae.className,
            value: inp ? inp.value : null,
            outline: getComputedStyle(d).outlineStyle + ' ' + getComputedStyle(d).outlineWidth,
          };
        })()"""

        sess.eval("document.querySelector('.dialog input.input').focus()")
        time.sleep(0.3)
        for ch in ["a", "b", "c"]:
            sess.call(
                "Input.dispatchKeyEvent",
                {
                    "type": "keyDown",
                    "text": ch,
                    "key": ch,
                    "code": "Key" + ch.upper(),
                    "windowsVirtualKeyCode": ord(ch.upper()),
                },
            )
            sess.call(
                "Input.dispatchKeyEvent",
                {
                    "type": "keyUp",
                    "key": ch,
                    "code": "Key" + ch.upper(),
                    "windowsVirtualKeyCode": ord(ch.upper()),
                },
            )
            time.sleep(0.25)

        state = sess.eval(probe)
        check(state.get("activeTag") == "INPUT", f"焦点还在输入框（实际 {state.get('activeTag')}）")
        check(state.get("value") == "abc", f"三个字符都进去了（实际 {state.get('value')!r}）")
        check(
            str(state.get("outline", "")).startswith("none"),
            f"对话框容器没有焦点外环（实际 {state.get('outline')}）",
        )

        # Esc 仍然关得掉（closeRef 转手后不能坏）
        sess.call(
            "Input.dispatchKeyEvent",
            {"type": "keyDown", "key": "Escape", "code": "Escape", "windowsVirtualKeyCode": 27},
        )
        sess.call(
            "Input.dispatchKeyEvent",
            {"type": "keyUp", "key": "Escape", "code": "Escape", "windowsVirtualKeyCode": 27},
        )
        time.sleep(0.8)
        check(not sess.eval("!!document.querySelector('.dialog')"), "Esc 关闭对话框")

        # 回到今天页，别把窗口停在测试留下的页面
        sess.eval(
            """
            (() => {
              const el = document.querySelector('.nav-item[title="今天"]');
              if (el) el.click();
              return !!el;
            })()
            """
        )
    finally:
        sess.close()

    print()
    print("RESULT:", "PASS" if failures == 0 else f"FAIL ({failures})")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
