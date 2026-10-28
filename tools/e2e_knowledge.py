"""知识库的端到端验收：真的点按钮、真的落盘、再从磁盘读回来核对。

为什么需要它：单元测试盖住了树构建、路径重写、frontmatter 这些纯函数，
Rust 测试盖住了扫描/写盘/冲突/回收站，但**「按钮接没接上」只有真点一次才知道**。
验收环境拿不到前台焦点，鼠标点不动，所以走 WebView2 的 CDP 端口直接派发事件。

用法：
  python tools/e2e_knowledge.py            # 跑一遍，打印 PASS/FAIL
  python tools/e2e_knowledge.py --keep      # 跑完不关窗口（看现场）
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXE = ROOT / "src-tauri" / "target" / "release" / "stumate-lite.exe"
PORT = 9333
VAULT = pathlib.Path(os.environ.get("TEMP", "C:/Temp")) / "StuMateE2E"
SETTINGS = pathlib.Path(os.environ["APPDATA"]) / "StuMate" / "settings.lite.json"

results: list[tuple[bool, str]] = []


def check(ok: bool, what: str) -> bool:
    results.append((bool(ok), what))
    print(("  PASS  " if ok else "  FAIL  ") + what)
    return bool(ok)


def fill(sess: cdp.Session, selector: str, value: str) -> None:
    """写进受控输入框。

    必须先拿原型上的原生 setter：React 会在 input 元素上挂一个 value 的
    getter/setter 用来追踪「上次渲染的值」，直接赋值 `el.value = x` 会被它
    记成「没变」，`onChange` 根本不会触发 —— 表现就是「脚本跑了，页面没动」。
    """
    sess.eval(
        """
        (() => {
          const el = document.querySelector(%s);
          if (!el) return 'no-el';
          const proto = el.tagName === 'TEXTAREA'
            ? window.HTMLTextAreaElement.prototype
            : window.HTMLInputElement.prototype;
          Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, %s);
          el.dispatchEvent(new Event('input', { bubbles: true }));
          return 'ok';
        })()
        """
        % (json.dumps(selector), json.dumps(value))
    )


def press(sess: cdp.Session, selector: str, key: str) -> None:
    sess.eval(
        """
        (() => {
          const el = document.querySelector(%s);
          if (!el) return 'no-el';
          el.dispatchEvent(new KeyboardEvent('keydown',
            { key: %s, bubbles: true, cancelable: true }));
          return 'ok';
        })()
        """
        % (json.dumps(selector), json.dumps(key))
    )


def click_text(sess: cdp.Session, text: str, scope: str = "button") -> bool:
    return (
        sess.eval(
            """
            (() => {
              const hit = [...document.querySelectorAll(%s)]
                .find((b) => (b.textContent || '').trim().includes(%s));
              if (!hit) return false;
              hit.click();
              return true;
            })()
            """
            % (json.dumps(scope), json.dumps(text))
        )
        is True
    )


def click_title(sess: cdp.Session, title: str) -> bool:
    """按 `title` 属性点（工具栏上的图标按钮没有文字，只能靠 title 认）。"""
    return (
        sess.eval(
            """
            (() => {
              const hit = document.querySelector(%s);
              if (!hit) return false;
              hit.click();
              return true;
            })()
            """
            % json.dumps(f'[title="{title}"]')
        )
        is True
    )


def wait_for(sess: cdp.Session, expression: str, timeout: float = 6.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if sess.eval(expression):
                return True
        except cdp.CdpError:
            pass
        time.sleep(0.15)
    return False


def main() -> int:
    keep = "--keep" in sys.argv
    if not EXE.is_file():
        print(f"找不到 {EXE}")
        return 1

    # 干净的库 + 不触发迁移（vaultMigrated=true 表示「已经迁过了」）
    shutil.rmtree(VAULT, ignore_errors=True)
    VAULT.mkdir(parents=True, exist_ok=True)
    prefs = json.loads(SETTINGS.read_text(encoding="utf-8"))
    prefs["vaultRoot"] = str(VAULT)
    prefs["vaultMigrated"] = True
    SETTINGS.write_text(json.dumps(prefs, ensure_ascii=False), encoding="utf-8")

    env = dict(os.environ)
    env["STUMATE_LITE_TOPMOST"] = "1"
    env["STUMATE_LITE_PAGE"] = "knowledge"
    env["STUMATE_LITE_THEME"] = "dark"
    env["STUMATE_LITE_BROWSER_ARGS"] = f"--remote-debugging-port={PORT}"

    proc = subprocess.Popen([str(EXE)], cwd=str(ROOT), env=env)
    sess = None
    try:
        sess = cdp.Session(cdp.find_page(PORT))
        sess.call("Runtime.enable")
        if not wait_for(sess, "!!document.querySelector('.kb-split')", 20):
            print("页面没渲染出知识库布局")
            return 1

        # 1) 空库
        check(sess.eval("document.querySelectorAll('.kb-row').length") == 0, "空库时树里没有行")

        # 2) 新建笔记：应当立刻进重命名态
        check(click_text(sess, "新建笔记"), "点到「新建笔记」")
        check(wait_for(sess, "!!document.querySelector('.kb-rename')"), "新笔记直接进重命名态")
        check((VAULT / "Stumate" / "未命名.md").is_file(), "未命名.md 已落盘")

        # 3) 改名
        fill(sess, ".kb-rename", "端到端测试")
        press(sess, ".kb-rename", "Enter")
        time.sleep(0.6)
        check((VAULT / "Stumate" / "端到端测试.md").is_file(), "改名后文件名跟着变")
        check(not (VAULT / "Stumate" / "未命名.md").exists(), "旧文件名已消失")

        # 4) 写正文 → 自动保存
        fill(sess, ".kb-textarea", "第一行\n第二行\n")
        time.sleep(1.6)
        body = (VAULT / "Stumate" / "端到端测试.md").read_text(encoding="utf-8")
        check("第一行" in body and "第二行" in body, "正文自动保存进 .md")
        check(body.startswith("---\n") and "stumate_uid:" in body, "写入带 frontmatter 与 uid")

        # 5) 新建文件夹 + 改名
        check(click_title(sess, "新建文件夹"), "点到「新建文件夹」")
        check(wait_for(sess, "!!document.querySelector('.kb-rename')"), "新文件夹进重命名态")
        fill(sess, ".kb-rename", "测试科目")
        press(sess, ".kb-rename", "Enter")
        time.sleep(0.6)
        check((VAULT / "Stumate" / "测试科目").is_dir(), "文件夹已建出来")

        # 6) 全文搜索
        fill(sess, ".kb-search input", "第二行")
        check(wait_for(sess, "document.querySelectorAll('.kb-hit').length === 1"), "全文搜索命中 1 篇")
        check(
            sess.eval("(document.querySelector('.kb-hit-sub')||{}).textContent||''").find("第二行") >= 0,
            "命中行显示的是正文摘要",
        )
        fill(sess, ".kb-search input", "")
        time.sleep(0.4)

        # 7) Ctrl+P 快速跳转
        sess.eval(
            "window.dispatchEvent(new KeyboardEvent('keydown',{key:'p',ctrlKey:true,bubbles:true}))"
        )
        check(wait_for(sess, "!!document.querySelector('.qo-input')"), "Ctrl+P 打开快速跳转")
        fill(sess, ".qo-input", "端到")
        time.sleep(0.3)
        check(
            sess.eval("document.querySelectorAll('.qo-row').length >= 1"), "快速跳转列出了候选"
        )
        press(sess, ".qo-input", "Escape")
        check(wait_for(sess, "!document.querySelector('.qo')"), "Esc 关掉快速跳转")

        # 8) 外部改动（模拟网盘同步下来的版本）→ 保存必须被拦下
        click_text(sess, "新建笔记")
        wait_for(sess, "!!document.querySelector('.kb-rename')")
        fill(sess, ".kb-rename", "冲突测试")
        press(sess, ".kb-rename", "Enter")
        time.sleep(0.6)
        fill(sess, ".kb-textarea", "本地的版本")
        time.sleep(1.6)
        conflict_file = VAULT / "Stumate" / "冲突测试.md"
        check("本地的版本" in conflict_file.read_text(encoding="utf-8"), "冲突测试的初稿已保存")

        # 从「外部」改写这个文件，并把 mtime 推后 —— 等价于网盘把一个更新的版本放下来
        conflict_file.write_text(
            "---\nstumate: true\nstumate_uid: from-outside\n---\n\n外部的版本\n",
            encoding="utf-8",
        )
        future = time.time() + 5
        os.utime(conflict_file, (future, future))

        fill(sess, ".kb-textarea", "我的新改动")
        check(
            wait_for(sess, "!!document.querySelector('.kb-banner')", 8),
            "外部改动后保存被拦下（出现冲突条）",
        )
        check(
            "外部的版本" in conflict_file.read_text(encoding="utf-8"),
            "磁盘上的版本没有被覆盖",
        )
        click_text(sess, "载入磁盘版本")
        time.sleep(0.8)
        loaded = sess.eval("(document.querySelector('.kb-textarea')||{}).value || ''")
        check(loaded.strip() == "外部的版本", "载入磁盘版本后正文换成磁盘内容")

        # 9) 右键 → 删除 → 进 .trash
        sess.eval(
            """
            (() => {
              const row = [...document.querySelectorAll('.kb-row')]
                .find((r) => r.textContent.includes('端到端测试'));
              if (!row) return false;
              row.dispatchEvent(new MouseEvent('contextmenu',
                { bubbles: true, clientX: 200, clientY: 200 }));
              return true;
            })()
            """
        )
        check(wait_for(sess, "!!document.querySelector('.kb-menu')"), "右键弹出菜单")
        click_text(sess, "删除", ".kb-menu button")
        check(wait_for(sess, "!!document.querySelector('.dialog')"), "删除前有确认框")
        click_text(sess, "移到回收目录")
        time.sleep(0.8)
        check(not (VAULT / "Stumate" / "端到端测试.md").exists(), "文件已从库里移走")
        trash = list((VAULT / "Stumate" / ".trash").glob("*端到端测试*"))
        check(len(trash) == 1, "文件躺在 .trash 里（没被真删）")

        # 9) 树里不该出现 .trash
        check(
            sess.eval(
                "[...document.querySelectorAll('.kb-row')].every(r => !r.textContent.includes('trash'))"
            )
            is True,
            ".trash 不出现在树里",
        )

    finally:
        if sess:
            sess.close()
        if not keep:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()

    bad = [w for ok, w in results if not ok]
    print(f"\n{len(results) - len(bad)}/{len(results)} 通过")
    for w in bad:
        print(f"  未通过：{w}")
    return 0 if not bad else 2


if __name__ == "__main__":
    sys.exit(main())
