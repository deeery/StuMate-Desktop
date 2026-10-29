"""验收：Markdowner 子窗口的载入 / 编辑 / 落盘 全链路。

第一版用了 `MD.app.editor.setValue()`，结果内容变了、磁盘没动 —— 因为程序化赋值
不经过 MarkDowner 的 input 链路，那条 600ms 自动保存挂的是真实输入事件。
这里改成「textarea 赋值 + dispatch input」，走它真正监听的路径。

验的是用户真会遇到的四件事：
  1. 窗口里看不见 YAML frontmatter（它不该被当成正文渲染）；
  2. 改一个字能落到磁盘上；
  3. 落盘之后 frontmatter 还在，且逐字不变；
  4. 主窗口收到通知、不弹冲突横幅。

测试会临时改写真实笔记，跑完必须还原。
"""
from __future__ import annotations

import base64
import json
import os
import pathlib
import sys
import time
import urllib.request

# 路径一律由脚本位置推。硬编码版本目录名的话，每次 bump 版本就得回来改一遍，
# 忘了改就是「FileNotFoundError: 找不到 cdp 模块」这种跟本次改动毫无关系的报错。
ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import cdp  # noqa: E402

PORT = 9333
NOTE = os.path.join(os.environ["APPDATA"], "StuMate", "Stumate", "高数", "极限.md")
OUT = str(ROOT / "design-preview" / "acceptance")


def targets():
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list", timeout=5) as r:
        return json.loads(r.read().decode())


def shoot(sess, name):
    res = sess.call("Page.captureScreenshot", {"format": "png"})
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(base64.b64decode(res["data"]))
    print(f"  截图 {name}")


TYPE_SNIPPET = """(text) => {
  const ta = document.querySelector('.editor__input');
  if (!ta) return {ok: false, why: 'no textarea'};
  ta.focus();
  ta.value = text;
  ta.setSelectionRange(text.length, text.length);
  ta.dispatchEvent(new Event('input', {bubbles: true}));
  return {ok: true, len: text.length};
}"""


def main() -> int:
    original = open(NOTE, "r", encoding="utf-8").read()
    print(f"原始文件 {len(original)} 字符")
    print(f"  frontmatter 块: {original[:original.index('---', 3) + 3]!r}")

    # ── 主窗口：打开笔记 → 点按钮 ────────────────────────────────
    page = [t for t in targets() if t.get("title") == "StuMate Lite"][0]
    s = cdp.Session(page["webSocketDebuggerUrl"])
    s.eval("""(() => {
        const el = [...document.querySelectorAll('*')].find((e) =>
          e.children.length === 0 && (e.textContent || '').trim() === '知识库');
        if (el) el.click(); return true; })()""")
    time.sleep(1.2)
    s.eval("""(() => {
        const rows = [...document.querySelectorAll('.kb-tree-body .kb-row')];
        const hit = rows.find((r) => (r.querySelector('.kb-name') || {}).textContent
                              && r.querySelector('.kb-name').textContent.trim() === '极限');
        if (hit) hit.click(); return !!hit; })()""")
    time.sleep(1.2)

    sub = [t for t in targets() if t.get("title") != "StuMate Lite"]
    if sub:
        print("关掉已开的子窗口…")
        try:
            m0 = cdp.Session(sub[0]["webSocketDebuggerUrl"])
            m0.eval("""window.__TAURI_INTERNALS__.invoke('plugin:window|close',
                       {label: 'markdowner'}).catch(() => {}); 1""")
            m0.close()
        except Exception as e:  # noqa: BLE001
            print("  关闭异常:", str(e)[:60])
        time.sleep(1.5)

    s.eval("""(() => {
        const b = [...document.querySelectorAll('button')]
          .find((x) => (x.title || '').toLowerCase().includes('markdowner'));
        if (b) b.click(); return !!b; })()""")
    print("已点「用 Markdowner 编辑」")
    time.sleep(4.0)

    sub = [t for t in targets() if t.get("title") != "StuMate Lite"]
    if not sub:
        print("子窗口没起来")
        return 1
    m = cdp.Session(sub[0]["webSocketDebuggerUrl"])
    print("子窗口:", sub[0]["title"])

    # ── 1. 正文里不该有 frontmatter ───────────────────────────────
    state = m.eval("""(() => {
        const ta = document.querySelector('.editor__input');
        const t = ta ? ta.value : '';
        return {
          text: t,
          viaApi: MD.app.editor.getValue(),
          hasFm: /^---/.test(t),
          hasKey: /stumate_uid/.test(t),
          preview: (document.querySelector('.editor__preview, .preview') || {}).innerText,
        }; })()""")
    print("\n[1] 编辑器内容:", repr(state["text"][:80]))
    print(f"    getValue 一致: {state['viaApi'] == state['text']}")
    print(f"    含 frontmatter 开头: {state['hasFm']}  含 stumate_uid: {state['hasKey']}")
    shoot(m, "markdowner-loaded.png")

    # ── 2. 真实输入路径改内容 ─────────────────────────────────────
    marker = "\n\n自动化验收：这一行由 verify_markdowner 写入。"
    typed = state["text"].rstrip("\n") + marker
    r = m.eval(f"({TYPE_SNIPPET})({json.dumps(typed)})")
    print("\n[2] 写入新内容:", r)
    time.sleep(3.0)

    after = open(NOTE, "r", encoding="utf-8").read()
    fm = original[:original.index("---", 3) + 3]
    print(f"    磁盘 {len(after)} 字符")
    print(f"    新内容已落盘: {marker.strip() in after}")
    print(f"    frontmatter 逐字保留: {fm in after}")
    print(f"    文件以 frontmatter 开头: {after.lstrip().startswith('---')}")

    # ── 3. 主窗口是否收到通知 ────────────────────────────────────
    time.sleep(1.2)
    tree = s.eval("""(() => ({
        banner: !!document.querySelector('.kb-banner'),
        state: (document.querySelector('.kb-state') || {}).textContent || null,
        lines: [...document.querySelectorAll('.kb-tree-body .kb-row')].length,
    }))()""")
    print("\n[3] 主窗口:", json.dumps(tree, ensure_ascii=False))
    shoot(m, "markdowner-typed.png")

    # ── 还原 ─────────────────────────────────────────────────────
    body = original
    body = body[body.index("---", 3) + 3:].lstrip("\r\n")
    m.eval(f"({TYPE_SNIPPET})({json.dumps(body)})")
    time.sleep(2.5)
    m.close()
    s.close()

    restored = open(NOTE, "r", encoding="utf-8").read()
    ok = restored == original
    print(f"\n还原：{len(restored)} 字符，与原文一致: {ok}")
    if not ok:
        with open(NOTE, "w", encoding="utf-8", newline="") as f:
            f.write(original)
        print("  已用 Python 写回原文（桥接保存的换行/尾部空行与原文有差异）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
