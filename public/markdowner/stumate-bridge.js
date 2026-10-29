/* StuMate ↔ MarkDowner 桥接
 *
 * MarkDowner 本身是个独立的 localStorage 应用：文档存在 `markdowner.v1.library`
 * 里，保存 = 写 localStorage。这里要把它接到 StuMate 的文件库上，也就是
 * 「一篇 .md 文件」，保存 = 调 Tauri 的 vault_write 落盘。
 *
 * 接入点选在 `MD.library.update` —— 不是随意挑的：MarkDowner 里所有持久化路径
 * （600ms 自动保存的 persistActive、Ctrl+S 的 saveDocument、切换文档前的 flush）
 * 最终都汇到这一个函数。patch 它一处，三条路径一起接管，不需要去改它的快捷键表。
 *
 * 只在 URL 带 `base` + `rel` 时生效。不带参数打开就是原版 MarkDowner，
 * 这样同一份静态资源既能当 StuMate 的编辑器，也能单独用。
 */
(function () {
  "use strict";

  // 参数有两个来源，按优先级：
  //   1. `window.__STUMATE_ARGS` —— Rust 建窗口时用 initialization_script 注入，
  //      在页面任何脚本之前就绪，是 StuMate 里的正常路径；
  //   2. URL query —— 留给「手动带参数打开」的场景（调试、或直接丢个链接）。
  var injected = window.__STUMATE_ARGS || null;
  var params = new URLSearchParams(location.search);
  var base = (injected && injected.base) || params.get("base") || "";
  var rel = (injected && injected.rel) || params.get("rel") || "";
  if (!base || !rel) return;

  /** Tauri 2 把 invoke 注入在 __TAURI_INTERNALS__ 上，无需 withGlobalTauri */
  function invoke(cmd, args) {
    var t = window.__TAURI_INTERNALS__;
    if (!t || typeof t.invoke !== "function") {
      return Promise.reject(new Error("Tauri 桥不可用"));
    }
    return t.invoke(cmd, args);
  }

  var fileName = rel.split(/[\\/]/).pop() || rel;
  var docId = "stumate-file";
  var mtime = null;
  var lastSent = "";
  var saving = false;
  var queued = null;
  var blocked = false;

  /* ── YAML frontmatter ────────────────────────────────────────────
   *
   * StuMate 的笔记文件开头有一段 `---` 包起来的元数据（stumate / stumate_uid /
   * created）。MarkDowner 不认这种块，会把它当成正文：分隔线 + 一堆粗体标题，
   * 打开就是满屏噪音。
   *
   * 所以载入时剥掉、保存时原样拼回去。整块文本原封不动地带着（不解析、不重排），
   * 就不会因为某个字段格式特殊而在往返中被改写。
   */
  var FRONTMATTER_RE = /^(---[ \t]*\r?\n[\s\S]*?\r?\n---[ \t]*\r?\n?)/;
  var frontmatter = "";
  // frontmatter 与正文之间那个空行。必须单独记着、保存时原样补回 ——
  // 只「剥掉不补回」的话，打开一次少一行、保存一次写一次盘：内容看着没变，
  // 文件的 mtime 却在每次打开时都被刷新，网盘同步会跟着反复上传。
  var fmGap = "";

  function splitFrontmatter(text) {
    var m = FRONTMATTER_RE.exec(text);
    if (!m) {
      frontmatter = "";
      fmGap = "";
      return text;
    }
    frontmatter = m[1];
    var rest = text.slice(m[1].length);
    var gap = /^\r?\n/.exec(rest);
    fmGap = gap ? gap[0] : "";
    return fmGap ? rest.slice(fmGap.length) : rest;
  }

  function joinFrontmatter(body) {
    return frontmatter + fmGap + body;
  }

  // 赶在 MarkDowner 的 boot 读设置之前关掉文档库侧栏：
  // 这里只有「一个外部文件」，侧栏里那一堆 localStorage 文档只会误导。
  try {
    if (window.MD && window.MD.settings) {
      window.MD.settings.set("showSidebar", false);
    }
  } catch (_) {}

  document.documentElement.setAttribute("data-stumate-file", "1");

  /* ── 状态角标 ────────────────────────────────────────────────── */

  var badge = null;

  function setStatus(text, kind) {
    if (!badge) {
      badge = document.createElement("div");
      badge.id = "stumate-status";
      badge.style.cssText = [
        "position:fixed",
        "right:14px",
        "bottom:12px",
        "z-index:2147483000",
        "padding:3px 9px",
        "border-radius:5px",
        "font:11px/1.7 ui-monospace,Consolas,monospace",
        "pointer-events:none",
        "transition:opacity .18s",
        "background:rgba(0,0,0,.58)",
        "color:#e6e6e6",
        "border:1px solid rgba(255,255,255,.12)",
      ].join(";");
      document.body.appendChild(badge);
    }
    badge.textContent = text;
    badge.style.color =
      kind === "bad" ? "#ff99a4" : kind === "ok" ? "#8fdc8f" : "#e6e6e6";
    badge.style.opacity = "1";
    if (kind === "ok") {
      window.clearTimeout(setStatus._t);
      setStatus._t = window.setTimeout(function () {
        if (badge) badge.style.opacity = "0.35";
      }, 1600);
    }
  }

  /* ── 写盘 ────────────────────────────────────────────────────── */

  function push(body) {
    if (blocked) return;
    // 编辑器里只有正文，落盘的必须是「frontmatter + 正文」。
    // 比较的也是拼好的全文，否则每次保存都会因为长度不同而重复写盘。
    var content = joinFrontmatter(body);
    if (content === lastSent) return;
    if (saving) {
      queued = content;
      return;
    }

    saving = true;
    setStatus("保存中…");

    invoke("vault_write", {
      base: base,
      rel: rel,
      content: content,
      expected: mtime,
    })
      .then(function (ack) {
        saving = false;

        // 冲突：磁盘上的文件在别处被改过。一个字节都不写，把选择权交给人。
        if (ack && ack.conflict) {
          blocked = true;
          setStatus("磁盘版本已被改动，本次未写入", "bad");
          badge.style.pointerEvents = "auto";
          badge.style.cursor = "pointer";
          badge.title = "点击载入磁盘版本";
          badge.addEventListener("click", function () {
            location.reload();
          });
          return;
        }

        mtime = ack.mtime;
        lastSent = content;
        setStatus("已保存", "ok");

        // 通知主窗口刷新，否则主窗口会把这次外部写入当成冲突弹横幅。
        // 事件名沿用主窗口那边的 `stumate://` 命名空间。
        invoke("plugin:event|emit", {
          event: "stumate://vault-changed",
          payload: { rel: rel, mtime: mtime },
        }).catch(function () {});

        if (queued !== null) {
          var next = queued;
          queued = null;
          push(next);
        }
      })
      .catch(function (err) {
        saving = false;
        setStatus("保存失败：" + (err && err.message ? err.message : err), "bad");
      });
  }

  /* ── 接管 library.update ─────────────────────────────────────── */

  function patch() {
    var lib = window.MD && MD.library;
    if (!lib || lib.__stumatePatched) return;
    lib.__stumatePatched = true;

    var orig = lib.update;
    lib.update = function (id, changes) {
      var ret = orig.apply(lib, arguments);
      if (id === docId && changes && typeof changes.content === "string") {
        push(changes.content);
      }
      return ret;
    };
  }

  /* ── 载入 ────────────────────────────────────────────────────── */

  function boot() {
    invoke("vault_read", { base: base, rel: rel })
      .then(function (note) {
        mtime = note.mtime;
        lastSent = note.content;

        var body = splitFrontmatter(note.content);
        var lib = MD.library;
        var existing = lib.get(docId);
        if (existing) {
          lib.update(docId, { content: body, title: fileName });
        } else {
          lib.insert({ id: docId, title: fileName, content: body });
        }

        patch();
        MD.app.openDocument(docId, { silent: true });

        // 「标题即文件名」：这个输入框改的是 MarkDowner 内部的文档名，
        // 跟磁盘文件名无关。留着可编辑只会让人以为能重命名文件。
        var title = document.getElementById("doc-title");
        if (title) {
          title.value = fileName;
          title.readOnly = true;
          title.title = rel;
          title.style.cursor = "default";
        }

        document.title = fileName + " — Markdowner";
        setStatus("已载入", "ok");
      })
      .catch(function (err) {
        setStatus("打不开这个文件：" + (err && err.message ? err.message : err), "bad");
      });
  }

  // 等 MarkDowner 的 boot 跑完（它会先打开最后一次的文档），再接管。
  (function wait() {
    var tries = 0;
    (function poll() {
      if (window.MD && MD.app && MD.app.editor && MD.app.activeDocId) {
        setTimeout(boot, 30);
        return;
      }
      if (++tries > 250) {
        setStatus("MarkDowner 没有启动起来", "bad");
        return;
      }
      setTimeout(poll, 40);
    })();
  })();

  // 关窗口前尽量把最后一次改动送出去（异步写盘可能赶不上，聊胜于无）
  window.addEventListener("beforeunload", function () {
    try {
      if (window.MD && MD.app && MD.app.editor && !blocked) {
        var v = MD.app.editor.getValue();
        if (v !== lastSent) push(v);
      }
    } catch (_) {}
  });
})();
