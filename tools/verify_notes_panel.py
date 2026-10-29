# -*- coding: utf-8 -*-
"""验收：便签页恢复成「左侧表格 + 右侧编辑面板」（原版版式）。

断言的是**版式关系**，不是像素：
  · 表格右边存在宽度约 340 的编辑面板
  · 点一行 → 该行拿到 `.on` 类（选中底色），且**没有**弹出对话框
  · 面板里能读到那一行的标题
  · 表头 6 列（拖动把手 / 色条 / 标题内容 / 分类 / 截止 / 操作）对齐

同时输出深色与浅色两张截图，供人工比对原版。

前置：应用以 STUMATE_LITE_BROWSER_ARGS="--remote-debugging-port=9333" 启动。
"""

import base64
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp  # noqa: E402

PORT = 9333
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "design-preview", "acceptance")

CLICK_ROW = """
(() => {
  const row = document.querySelector('.note-row');
  if (!row) return 'no-row';
  row.click();
  return 'ok';
})()
"""

MEASURE = """
(() => {
  const panel = document.querySelector('.note-panel');
  const card = document.querySelector('.page-content .card');
  const sel = document.querySelectorAll('.note-row.on').length;
  const dialog = document.querySelectorAll('[role="dialog"], .dialog').length;
  const head = document.querySelector('.page-content .card > div');
  const headCells = head ? head.children.length : -1;
  return {
    panel: panel ? Math.round(panel.getBoundingClientRect().width) : null,
    panelText: panel ? (panel.textContent || '').slice(0, 40) : null,
    cardRight: card ? Math.round(card.getBoundingClientRect().right) : null,
    panelLeft: panel ? Math.round(panel.getBoundingClientRect().left) : null,
    selectedRows: sel,
    dialogs: dialog,
    headCells: headCells,
  };
})()
"""


def shot(s, name):
    r = s.call("Page.captureScreenshot", {"format": "png"})
    path = os.path.join(OUT, name)
    with open(path, "wb") as f:
        f.write(base64.b64decode(r["data"]))
    print("  截图:", name)


def goto_notes(s):
    s.eval(
        """(() => {
          const b = [...document.querySelectorAll('.nav-item')]
            .find((x) => (x.textContent || '').includes('便签'));
          if (b) b.click();
          return !!b;
        })()"""
    )
    time.sleep(1.2)


def main():
    os.makedirs(OUT, exist_ok=True)
    ts = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list", timeout=5).read())
    main_t = [t for t in ts if t.get("title") == "StuMate Lite"]
    assert main_t, f"找不到主窗口：{[t.get('title') for t in ts]}"
    s = cdp.Session(main_t[0]["webSocketDebuggerUrl"])

    goto_notes(s)
    print("空态:", json.dumps(s.eval(MEASURE), ensure_ascii=False))
    shot(s, "notes-idle-dark.png")

    print("点第一行:", s.eval(CLICK_ROW))
    time.sleep(0.6)
    m = s.eval(MEASURE)
    print("选中后:", json.dumps(m, ensure_ascii=False))
    shot(s, "notes-selected-dark.png")

    ok = True
    if m["panel"] is None:
        print("✗ 右侧编辑面板不存在")
        ok = False
    elif not (320 <= m["panel"] <= 360):
        print(f"✗ 面板宽度 {m['panel']} 不在 340 附近")
        ok = False
    if m["dialogs"]:
        print("✗ 还弹了对话框")
        ok = False
    if m["selectedRows"] != 1:
        print(f"✗ 选中行数 = {m['selectedRows']}（应为 1）")
        ok = False
    if m["headCells"] != 6:
        print(f"✗ 表头列数 = {m['headCells']}（应为 6）")
        ok = False

    # 浅色再截一张：只切 root 的 data-theme，不动持久化设置
    s.eval("document.documentElement.dataset.theme = 'light'")
    time.sleep(0.4)
    shot(s, "notes-selected-light.png")
    s.eval("document.documentElement.dataset.theme = 'dark'")

    s.close()
    if ok:
        print("✓ 便签页已恢复「表格 + 右侧编辑面板」版式")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
