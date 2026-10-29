"""验收：打开→关掉，文件必须一个字节都不变。

为什么单独测这条：桥接层要在「frontmatter + 正文」和「只有正文」之间来回拆合。
拆合只要不完全互逆，每次打开窗口就会静默改一次文件 —— 用户看不到内容变化，
但 mtime 一直在动，网盘就会反复上传、同步冲突提示随之而来。
"""
from __future__ import annotations

import json
import os
import pathlib
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import cdp  # noqa: E402

PORT = 9333
NOTE = os.path.join(os.environ["APPDATA"], "StuMate", "Stumate", "高数", "极限.md")


def targets():
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list", timeout=5) as r:
        return json.loads(r.read().decode())


def stamp():
    st = os.stat(NOTE)
    return {"mtime": st.st_mtime_ns, "size": st.st_size,
            "text": open(NOTE, "r", encoding="utf-8").read()}


def main() -> int:
    before = stamp()
    print(f"打开前：{before['size']} B, mtime={before['mtime']}")

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
    time.sleep(1.0)
    s.eval("""(() => {
        const b = [...document.querySelectorAll('button')]
          .find((x) => (x.title || '').toLowerCase().includes('markdowner'));
        if (b) b.click(); return !!b; })()""")
    print("已打开 Markdowner 窗口，静置 5s 观察是否偷偷写盘…")
    time.sleep(5.0)

    mid = stamp()
    print(f"打开后：{mid['size']} B, mtime={mid['mtime']}")
    print(f"  内容未变: {mid['text'] == before['text']}")
    print(f"  mtime 未变: {mid['mtime'] == before['mtime']}")

    # 再开一次同一篇（应只聚焦、不重建），看会不会又写一次
    s.eval("""(() => {
        const b = [...document.querySelectorAll('button')]
          .find((x) => (x.title || '').toLowerCase().includes('markdowner'));
        if (b) b.click(); return !!b; })()""")
    time.sleep(3.0)
    again = stamp()
    print(f"二次打开后：mtime 未变: {again['mtime'] == before['mtime']}")

    s.close()
    ok = (mid["text"] == before["text"] and mid["mtime"] == before["mtime"]
          and again["mtime"] == before["mtime"])
    print("\n幂等:", "通过" if ok else "未通过")
    if not ok:
        print("  差异长度:", len(mid["text"]) - len(before["text"]))
        print("  前 120 字:", repr(mid["text"][:120]))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
