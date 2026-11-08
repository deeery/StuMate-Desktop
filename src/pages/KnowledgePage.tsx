/**
 * 知识库页：库 = 磁盘上 `<库根>/Stumate/` 这棵目录树。
 *
 * ## 一句话的模型
 *
 * **目录结构就是知识结构**。没有「科目」字段、没有父子关系表、没有索引文件 ——
 * 树是每次扫描现算出来的（`buildTree`），所以「在资源管理器里拖了个文件夹」
 * 和「在应用里改了个文件夹」走的是同一条路，不存在两边对不上、需要修索引的情况。
 *
 * ## 三处必须记住的取舍
 *
 * 1. **标题就是文件名**。改标题 = 改文件名，不是改某个字段。所以重命名走
 *    `vaultRename`（磁盘操作）而不是写库。
 * 2. **正文直接写进 `.md`**，frontmatter 只放 uid / created / updated，
 *    不认识的键原样带回去（见 `model/vaultNote.ts`）。
 * 3. **保存前必查 mtime**。库多半放在网盘目录里，别人（或另一台机器）改过的文件
 *    会自己冒出来；没有这道检查，按一次保存就把对方盖掉了，而且不会有任何提示。
 *
 * ## 与旧版知识库的关系
 *
 * 旧的 `knowledge` 表已经冻结（第一次启用时把行铺成文件，之后不再写）。
 * 因此这一页完全不读 `ctx.knowledge` —— 除了迁移那一次。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { PageTopBar, type AppContextValue } from "../App";
import { Dialog } from "../components/ui";
import { usePresence } from "../components/Presence";
import { LinkSection } from "../components/LinkSection";
import { MindView } from "../components/MindView";
import { QuickOpen } from "../components/QuickOpen";
import { TrashDialog } from "../components/TrashDialog";
import { VaultTree } from "../components/VaultTree";
import {
  IconChevronLeft,
  IconCollapse,
  IconEdit,
  IconExternal,
  IconFile,
  IconFolder,
  IconList,
  IconMap,
  IconPlus,
  IconSearch,
  IconSort,
  IconTrash,
} from "../components/Icons";
import { api, onVaultChanged, type GrepHitWire, type VaultEntryWire } from "../api";
import { buildVaultFiles, sanitizeSegment } from "../model/vault";
import { composeNote, parseNote, type NoteMeta } from "../model/vaultNote";
import { reconcileMind } from "../model/mindMap";
import { toKnowledgeEntities, vaultUid } from "../model/vaultMind";
import type { MindNode } from "../model/types";
import {
  NOTE_EXT,
  ancestorsOf,
  baseTitle,
  buildTree,
  findNode,
  flattenNotes,
  joinPath,
  movePath,
  parentOf,
  renamePath,
  takenNames,
  uniqueTitle,
  type TreeSort,
  type TreeNode,
} from "../model/vaultTree";

/** 自动保存的静默期。太短会在连续打字时反复写盘，太长则切窗丢字的窗口变大 */
const AUTOSAVE_MS = 800;
/** 树的外部变化轮询间隔 */
const POLL_MS = 2000;

export function KnowledgePage({
  ctx,
  solo = false,
}: {
  ctx: AppContextValue;
  /** true = 这个窗口就是「知识库专用窗口」（`--kb` / 新窗口打开），不再提供「打开新窗口」 */
  solo?: boolean;
}) {
  const base = ctx.prefs.vaultRoot ?? "";

  const [entries, setEntries] = useState<VaultEntryWire[]>([]);
  const [sort, setSort] = useState<TreeSort>("name");
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [paneOpen, setPaneOpen] = useState(true);

  const [open, setOpen] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [meta, setMeta] = useState<NoteMeta | null>(null);
  const [savedMtime, setSavedMtime] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [diskMtime, setDiskMtime] = useState<number | null>(null);

  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<GrepHitWire[]>([]);
  const [quickOpen, setQuickOpen] = useState(false);
  const [renaming, setRenaming] = useState<{ path: string; value: string } | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; node: TreeNode } | null>(null);
  const [moving, setMoving] = useState<TreeNode | null>(null);
  const [deleting, setDeleting] = useState<TreeNode | null>(null);
  const [error, setError] = useState<string | null>(null);

  // v1.7.14：视图模式、导图节点、回收站、同名冲突确认
  const [viewMode, setViewMode] = useState<"tree" | "map">("tree");
  const [mapNodes, setMapNodes] = useState<MindNode[]>([]);
  const [mapReady, setMapReady] = useState(false);
  const [trashOpen, setTrashOpen] = useState(false);
  /** `desired` 是用户输的名字，`next` 是顺延后的名字；不等就先问一句 */
  const [nameConflict, setNameConflict] = useState<{
    kind: "rename" | "move";
    from: string;
    desired: string;
    next: string;
  } | null>(null);

  const trashPresence = usePresence(trashOpen);
  const conflictPresence = usePresence(nameConflict !== null);
  const retainedConflict = useRef<typeof nameConflict>(null);
  if (nameConflict) retainedConflict.current = nameConflict;
  const shownConflict = nameConflict ?? retainedConflict.current;

  const revRef = useRef("");
  /** 导图节点的最新值。派生要读它，但不该把 mapNodes 塞进依赖造成自激循环 */
  const mindRef = useRef<MindNode[]>([]);
  const tree = useMemo(() => buildTree(entries, sort), [entries, sort]);
  const notes = useMemo(() => flattenNotes(tree), [tree]);

  /**
   * 三个浮层的退场保留：快速跳转、移动到…、删除确认。
   *
   * 关闭时状态同帧置空，节点却还要再画 120ms —— 用 ref 记住最后一份可渲染的
   * 值，否则退场那一帧会先塌成空白（对话框标题突然变成 undefined）。
   */
  const quickPresence = usePresence(quickOpen);

  const movingPresence = usePresence(moving !== null);
  const retainedMoving = useRef<TreeNode | null>(null);
  if (moving) retainedMoving.current = moving;
  const shownMoving = moving ?? retainedMoving.current;

  const deletingPresence = usePresence(deleting !== null);
  const retainedDeleting = useRef<TreeNode | null>(null);
  if (deleting) retainedDeleting.current = deleting;
  const shownDeleting = deleting ?? retainedDeleting.current;

  // ── 扫描 ────────────────────────────────────────────────────────

  const refresh = useCallback(async () => {
    if (!base) {
      setEntries([]);
      return;
    }
    try {
      const list = await api.vaultScan(base);
      setEntries(list);
      revRef.current = await api.vaultRev(base);
      setError(null);
    } catch (e) {
      setError(String(e));
    }
  }, [base]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // ── 导图（v1.7.14 重新接回死代码 MindCanvas）─────────────────────────

  const persistMind = useCallback((nodes: MindNode[]) => {
    mindRef.current = nodes;
    setMapNodes(nodes);
    // mindmap.json 是整份重写；写失败不阻塞界面，但要把错误露出来
    void api.mindSave(nodes).catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    if (!base) return;
    setMapReady(false);
    void api
      .mindLoad()
      .then((payload) => {
        mindRef.current = payload.nodes ?? [];
        setMapNodes(mindRef.current);
        setMapReady(true);
      })
      .catch((e) => {
        setError(String(e));
        setMapReady(true); // 读失败也让它进空图，别把整页卡住
      });
  }, [base]);

  /**
   * 文件库是真相：树变了就把新笔记补进导图。
   * `reconcileMind` 只补缺失、不拉回被用户拖走的节点，所以每轮跑都安全。
   * 读 `mindRef.current` 而不是 `mapNodes`，避免「保存 → 重跑 → 又保存」自激。
   */
  useEffect(() => {
    if (viewMode !== "map" || !mapReady || !base) return;
    const result = reconcileMind(mindRef.current, toKnowledgeEntities(entries));
    if (result.changed) persistMind(result.nodes);
  }, [viewMode, mapReady, base, entries, persistMind]);

  /** 「重建」＝丢掉手工排布，按当前文件库重新派生一份，不删任何 .md */
  const rebuildMind = useCallback(() => {
    if (!base) return;
    persistMind(reconcileMind([], toKnowledgeEntities(entries)).nodes);
  }, [base, entries, persistMind]);

  /**
   * 轮询版本号，变了才重扫。
   *
   * 不用文件系统事件：库一般在 OneDrive / 坚果云 / Syncthing 目录里，
   * 这些同步盘是后台进程延迟落盘的，`ReadDirectoryChangesW` 收不到。
   * 见 `vault::rev` 的注释。
   */
  useEffect(() => {
    if (!base) return;
    const timer = window.setInterval(async () => {
      try {
        const rev = await api.vaultRev(base);
        if (rev && rev !== revRef.current) {
          revRef.current = rev;
          setEntries(await api.vaultScan(base));
        }
      } catch {
        // 轮询失败不打扰用户：下一轮会自己好，真出问题会在保存时报出来
      }
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [base]);

  // ── 保存 ────────────────────────────────────────────────────────

  const save = useCallback(
    async (force = false) => {
      if (!base || !open || !meta) return;
      setBusy(true);
      try {
        const body = composeNote({ ...meta, updated: Date.now() }, draft);
        const ack = await api.vaultWrite(base, open, body, force ? null : savedMtime);
        if (ack.conflict) {
          // 不覆盖、不丢稿：把选择权交回用户
          setConflict(true);
          setDiskMtime(ack.mtime);
          return;
        }
        setSavedMtime(ack.mtime);
        setDirty(false);
        setConflict(false);
        setDiskMtime(null);
        await refresh();
      } catch (e) {
        setError(String(e));
      } finally {
        setBusy(false);
      }
    },
    [base, open, meta, draft, savedMtime, refresh],
  );

  // 自动保存：只在「有改动」且「没有待决冲突」时挂表
  useEffect(() => {
    if (!open || !dirty || conflict) return;
    const timer = window.setTimeout(() => void save(), AUTOSAVE_MS);
    return () => window.clearTimeout(timer);
  }, [draft, open, dirty, conflict, save]);

  /**
   * 跳到 Markdowner 子窗口编辑。
   *
   * **必须先落盘再跳**：Markdowner 读的是磁盘上的那个 `.md`，不是内存里的 draft。
   * 不先保存，用户刚敲的内容在子窗口里就是空白的，看着像丢稿。
   */
  const openInMarkdowner = useCallback(async () => {
    if (!base || !open) return;
    if (dirty) await save();
    try {
      await api.openMarkdownWindow(base, open);
    } catch (e) {
      setError(String(e));
    }
  }, [base, open, dirty, save]);

  /**
   * Markdowner 写回磁盘后立刻刷新一次树。
   *
   * 不加这条也不会错 —— 2 秒轮询会兜住 —— 但用户从子窗口切回来时，
   * 左边树的修改时间还在显示旧值，像是没保存成功。
   */
  useEffect(() => {
    let alive = true;
    const pending = onVaultChanged(() => {
      if (alive) void refresh();
    });
    return () => {
      alive = false;
      void pending.then((off) => off());
    };
  }, [refresh]);

  // ── 打开 / 关掉一篇 ─────────────────────────────────────────────

  const loadFromDisk = useCallback(async () => {
    if (!base || !open) return;
    const file = await api.vaultRead(base, open);
    const parsed = parseNote(file.content);
    setDraft(parsed.body);
    setMeta({ uid: parsed.uid, created: parsed.created, updated: parsed.updated, extra: parsed.extra });
    setSavedMtime(file.mtime);
    setDirty(false);
    setConflict(false);
    setDiskMtime(null);
  }, [base, open]);

  const openNote = useCallback(
    async (path: string) => {
      if (path === open) return;
      if (open && dirty) await save();
      if (!base) return;
      try {
        const file = await api.vaultRead(base, path);
        const parsed = parseNote(file.content);
        setOpen(path);
        setDraft(parsed.body);
        setMeta({ uid: parsed.uid, created: parsed.created, updated: parsed.updated, extra: parsed.extra });
        setSavedMtime(file.mtime);
        setDirty(false);
        setConflict(false);
        setDiskMtime(null);
        // 跳转过来的笔记可能藏在折叠目录里，把祖先展开
        setCollapsed((prev) => {
          const next = new Set(prev);
          for (const a of ancestorsOf(path)) next.delete(a);
          return next;
        });
      } catch (e) {
        setError(String(e));
      }
    },
    [base, open, dirty, save],
  );

  /**
   * 外部改动：**没在编辑就静默跟随，正在编辑就问**。
   *
   * 判据是 `> savedMtime` 而不是 `!==`：我们自己写完到重扫之间，树里还是旧 mtime，
   * 用不等号会在每次保存之后自己弹一次「文件被外部改了」。
   */
  useEffect(() => {
    if (!open) return;
    const node = findNode(tree, open);
    if (!node) return;
    const newer = node.mtime > savedMtime;
    setDiskMtime((prev) => (newer ? node.mtime : null));
    if (newer && !dirty) void loadFromDisk();
  }, [tree, open, savedMtime, dirty, loadFromDisk]);

  // ── 增删改 ──────────────────────────────────────────────────────

  const createNote = useCallback(
    async (dir: string) => {
      if (!base) return;
      if (open && dirty) await save();
      const title = uniqueTitle("未命名", takenNames(tree, dir));
      const rel = joinPath(dir, `${title}${NOTE_EXT}`);
      const now = Date.now();
      try {
        await api.vaultWrite(
          base,
          rel,
          composeNote({ uid: newUid(), created: now, updated: now, extra: [] }, ""),
          null,
        );
        await refresh();
        await openNote(rel);
        // 立刻进重命名态：新建之后几乎总要改名，省一次点击
        setRenaming({ path: rel, value: title });
      } catch (e) {
        setError(String(e));
      }
    },
    [base, tree, open, dirty, save, refresh, openNote],
  );

  const createFolder = useCallback(
    async (dir: string) => {
      if (!base) return;
      const taken = takenNames(tree, dir);
      let name = sanitizeSegment("新建文件夹");
      if (taken.has(name.toLowerCase())) {
        let n = 2;
        while (taken.has(`${name} ${n}`.toLowerCase())) n += 1;
        name = `${name} ${n}`;
      }
      const rel = joinPath(dir, name);
      try {
        await api.vaultMkdir(base, rel);
        await refresh();
        setCollapsed((prev) => {
          const next = new Set(prev);
          if (dir) next.delete(dir);
          return next;
        });
        setRenaming({ path: rel, value: name });
      } catch (e) {
        setError(String(e));
      }
    },
    [base, tree, refresh],
  );

  const commitRename = useCallback(async () => {
    const r = renaming;
    setRenaming(null);
    if (!r || !base) return;
    const next = renamePath(r.path, r.value, takenNames(tree, parentOf(r.path)));
    if (next === r.path) return;
    // 顺延了就先别落盘：v1.7.14 起同名不再静默变成「 2」，把选择权交回用户
    if (next !== desiredRenamePath(r.path, r.value)) {
      setNameConflict({ kind: "rename", from: r.path, desired: r.value, next });
      return;
    }
    try {
      await api.vaultRename(base, r.path, next);
      if (open === r.path) setOpen(next);
      await refresh();
    } catch (e) {
      setError(String(e));
    }
  }, [renaming, base, tree, open, refresh]);

  const doDelete = useCallback(async () => {
    const node = deleting;
    setDeleting(null);
    if (!node || !base) return;
    try {
      await api.vaultTrash(base, node.path);
      const inside = open === node.path || (open ?? "").startsWith(`${node.path}/`);
      if (inside) {
        setOpen(null);
        setDraft("");
        setMeta(null);
        setDirty(false);
      }
      await refresh();
      ctx.toast("已移到 .trash 目录");
    } catch (e) {
      setError(String(e));
    }
  }, [deleting, base, open, refresh, ctx]);

  const doMove = useCallback(
    async (targetDir: string) => {
      const node = moving;
      setMoving(null);
      if (!node || !base) return;
      const next = movePath(node.path, targetDir, tree);
      if (next === node.path) return;
      // 目标目录里有同名项：先问，不要悄悄把文件改成「xx 2.md」
      if (next !== joinPath(targetDir, node.path.slice(node.path.lastIndexOf("/") + 1))) {
        setNameConflict({
          kind: "move",
          from: node.path,
          desired: targetDir || "根目录",
          next,
        });
        return;
      }
      try {
        await api.vaultRename(base, node.path, next);
        if (open === node.path) setOpen(next);
        await refresh();
      } catch (e) {
        setError(String(e));
      }
    },
    [moving, base, tree, open, refresh],
  );

  /** 冲突对话框里点了「用这个名字」：这时才真正动磁盘 */
  const applyNameConflict = useCallback(async () => {
    const c = nameConflict;
    setNameConflict(null);
    if (!c || !base) return;
    try {
      await api.vaultRename(base, c.from, c.next);
      if (open === c.from) setOpen(c.next);
      await refresh();
      ctx.toast(`已保存为「${baseTitle(c.next)}」`);
    } catch (e) {
      setError(String(e));
    }
  }, [nameConflict, base, open, refresh, ctx]);

  // ── 迁移（一次性）───────────────────────────────────────────────

  useEffect(() => {
    if (!base || ctx.prefs.vaultMigrated) return;
    let alive = true;
    void (async () => {
      try {
        const list = await api.vaultScan(base);
        const hasNote = list.some((e) => !e.isDir);
        if (!hasNote && ctx.knowledge.length > 0) {
          // `buildVaultFiles` 铺出来的路径自带 `Stumate/` 前缀（那是导出功能的口径），
          // 而落点已经是 `Stumate/` 本身，所以把这一层剥掉，否则会套两层目录。
          const files = buildVaultFiles(ctx.knowledge).map((f) => ({
            ...f,
            path: f.path.replace(/^Stumate\//, ""),
          }));
          const root = await api.vaultRoot(base);
          const report = await api.vaultExport(root, files);
          if (alive) ctx.toast(`已把 ${report.written} 条旧知识铺成 .md 文件`);
        }
        await ctx.setPref("vaultMigrated", true);
        if (alive) await refresh();
      } catch (e) {
        if (alive) setError(String(e));
      }
    })();
    return () => {
      alive = false;
    };
    // 只在「换了库根」或「还没迁过」时跑；ctx.knowledge 是冻结值，不必跟进
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [base, ctx.prefs.vaultMigrated]);

  // ── 搜索 ────────────────────────────────────────────────────────

  useEffect(() => {
    if (!base || !query.trim()) {
      setHits([]);
      return;
    }
    const timer = window.setTimeout(() => {
      void api
        .vaultGrep(base, query)
        .then(setHits)
        .catch(() => setHits([]));
    }, 250);
    return () => window.clearTimeout(timer);
  }, [base, query]);

  // ── 快捷键 ──────────────────────────────────────────────────────

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      const key = e.key.toLowerCase();
      if (key === "p") {
        e.preventDefault();
        setQuickOpen(true);
      } else if (key === "s") {
        e.preventDefault();
        void save();
      } else if (key === "n") {
        e.preventDefault();
        void createNote(parentOf(open ?? ""));
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [save, createNote, open]);

  // 右键菜单：点别处就关。用 mousedown 而不是 click —— click 要等菜单项自己的
  // onClick 跑完才冒到 window，会在「点了菜单项」的同一次事件里又把它关掉。
  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    window.addEventListener("mousedown", close);
    window.addEventListener("blur", close);
    return () => {
      window.removeEventListener("mousedown", close);
      window.removeEventListener("blur", close);
    };
  }, [menu]);

  // ── 渲染 ────────────────────────────────────────────────────────

  if (!base) {
    return (
      <>
        <PageTopBar title="知识库" subtitle="还没选库目录" />
        <div className="page-content">
          <div className="card kb-setup">
            <h3>选一个目录当作知识库</h3>
            <p>
              库会放在这个目录下的 <code>Stumate/</code> 子文件夹里，一篇笔记就是一个{" "}
              <code>.md</code> 文件。目录结构就是知识结构：顶层文件夹 = 科目，
              可以往下建任意多层子文件夹。
            </p>
            <p className="kb-setup-note">
              选一个已经在用网盘同步的目录，同步这件事就不用再管了；要用 Obsidian 打开，
              把这个库根添加成 vault 即可。
            </p>
            <div style={{ display: "flex", gap: 8, marginTop: 4 }}>
              <button
                className="btn primary"
                onClick={async () => {
                  const picked = await api.pickVaultDir();
                  if (!picked) return;
                  await ctx.setPref("vaultRoot", picked);
                  ctx.toast("已设置知识库目录");
                }}
              >
                选择目录…
              </button>
              <button
                className="btn"
                onClick={async () => {
                  await ctx.setPref("vaultRoot", ctx.info.dataDir);
                  ctx.toast("已使用应用数据目录");
                }}
              >
                用应用数据目录
              </button>
            </div>
          </div>
        </div>
      </>
    );
  }

  const menuDir = menu
    ? menu.node.kind === "folder"
      ? menu.node.path
      : parentOf(menu.node.path)
    : parentOf(open ?? "");
  const stateText = conflict ? "有冲突" : busy ? "保存中…" : dirty ? "未保存" : "已保存";

  return (
    <>
      <PageTopBar title="知识库" subtitle={`${notes.length} 篇`}>
        <div className="kb-view-switch" role="group" aria-label="知识库视图">
          <button
            className={`kb-view-btn${viewMode === "tree" ? " on" : ""}`}
            title="文件树视图"
            aria-pressed={viewMode === "tree"}
            onClick={() => setViewMode("tree")}
          >
            <IconList size={13} /> 列表
          </button>
          <button
            className={`kb-view-btn${viewMode === "map" ? " on" : ""}`}
            title="思维导图视图"
            aria-pressed={viewMode === "map"}
            onClick={() => setViewMode("map")}
          >
            <IconMap size={13} /> 导图
          </button>
        </div>
        <button className="btn ghost" onClick={() => setTrashOpen(true)} title="回收站：还原或彻底删除">
          <IconTrash size={13} /> 回收站
        </button>
        <div className="kb-search">
          <span className="kb-search-icon">
            <IconSearch size={13} />
          </span>
          <input
            className="input"
            style={{ width: 208, paddingLeft: 28 }}
            placeholder="搜索正文…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <button className="btn ghost" onClick={() => setQuickOpen(true)} title="Ctrl+P">
          快速跳转
        </button>
        {solo ? null : (
          <button
            className="btn ghost"
            title="在独立窗口里打开（只有知识库）"
            onClick={() => void api.openKnowledgeWindow()}
          >
            新窗口
          </button>
        )}
        <button className="btn primary" onClick={() => void createNote(parentOf(open ?? ""))}>
          <IconPlus size={14} /> 新建笔记
        </button>
      </PageTopBar>

      <div className="page-content" style={{ overflow: "hidden", padding: 0 }}>
        {viewMode === "map" ? (
          <MindView
            nodes={mapNodes}
            onNodesChange={persistMind}
            onOpenNote={(path) => {
              setViewMode("tree");
              void openNote(path);
            }}
            onNewNote={(dir) => {
              setViewMode("tree");
              void createNote(dir);
            }}
            onRebuild={rebuildMind}
          />
        ) : (
        <div className="kb-split">
          {paneOpen ? (
            <aside className="kb-tree">
              <div className="kb-tree-head">
                <span className="kb-tree-title">资源管理器</span>
                <span className="kb-grow" />
                <button className="kb-tool" title="新建笔记" onClick={() => void createNote(menuDir)}>
                  <IconEdit size={14} />
                </button>
                <button className="kb-tool" title="新建文件夹" onClick={() => void createFolder(menuDir)}>
                  <IconFolder size={14} />
                </button>
                <button
                  className="kb-tool"
                  title={sort === "name" ? "改成按修改时间" : "改成按名称"}
                  onClick={() => setSort((s) => (s === "name" ? "mtime" : "name"))}
                >
                  <IconSort size={14} />
                </button>
                <button
                  className="kb-tool"
                  title="全部折叠"
                  onClick={() =>
                    setCollapsed(new Set(tree.filter((n) => n.kind === "folder").map((n) => n.path)))
                  }
                >
                  <IconCollapse size={14} />
                </button>
                <button className="kb-tool" title="收起面板" onClick={() => setPaneOpen(false)}>
                  <IconChevronLeft size={14} />
                </button>
              </div>

              <div className="kb-tree-body">
                {query.trim() ? (
                  <div className="kb-hits">
                    {hits.length === 0 ? (
                      <div className="kb-empty">没有命中「{query.trim()}」的笔记</div>
                    ) : (
                      hits.map((h) => (
                        <button
                          key={h.path}
                          className={`kb-hit${open === h.path ? " on" : ""}`}
                          onClick={() => void openNote(h.path)}
                        >
                          <div className="kb-hit-title">
                            {baseTitle(h.path)}
                            <span className="kb-hit-count">{h.count}</span>
                          </div>
                          <div className="kb-hit-sub">{h.preview}</div>
                        </button>
                      ))
                    )}
                  </div>
                ) : (
                  <VaultTree
                    tree={tree}
                    current={open}
                    collapsed={collapsed}
                    renaming={renaming}
                    onToggle={(path) =>
                      setCollapsed((prev) => {
                        const next = new Set(prev);
                        if (next.has(path)) next.delete(path);
                        else next.add(path);
                        return next;
                      })
                    }
                    onOpen={(path) => void openNote(path)}
                    onMenu={(e, node) => {
                      e.preventDefault();
                      setMenu({ x: e.clientX, y: e.clientY, node });
                    }}
                    onRenameChange={(value) => setRenaming((r) => (r ? { ...r, value } : r))}
                    onRenameCommit={() => void commitRename()}
                    onRenameCancel={() => setRenaming(null)}
                  />
                )}
              </div>

              <div className="kb-tree-foot">Stumate/</div>
            </aside>
          ) : (
            <button className="kb-unfold" title="展开资源管理器" onClick={() => setPaneOpen(true)}>
              <IconChevronLeft size={13} />
            </button>
          )}

          <section className="kb-editor">
            {/* 换一篇笔记 = 整块内容替换（没有过渡时，标题、正文、状态一行行同时跳变）。
                `key={open}` 让它重新挂载并播一次淡入；空态与正文用同一个 key 空间，
                所以「选中一篇」和「关掉返回空态」走的是同一段过渡。 */}
            <div
              key={open ?? "__empty"}
              className="enter-fade"
              style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}
            >
            {open ? (
              <>
                <div className="kb-head">
                  <span
                    className="kb-title"
                    title="双击改名"
                    onDoubleClick={() => setRenaming({ path: open, value: baseTitle(open) })}
                  >
                    {baseTitle(open)}
                  </span>
                  <span className="kb-path">{parentOf(open) || "根目录"}</span>
                  <span className="kb-grow" />
                  <button
                    className="kb-tool kb-head-tool"
                    title="用 Markdowner 编辑（语法高亮 / 实时预览 / 公式）"
                    onClick={() => void openInMarkdowner()}
                  >
                    <IconExternal size={14} />
                  </button>
                  <span className={`kb-state${conflict ? " bad" : ""}`}>{stateText}</span>
                </div>

                {conflict ? (
                  <div className="kb-banner">
                    <span>
                      这个文件在磁盘上被改过（可能是网盘同步下来的版本），这次保存没有覆盖它。
                    </span>
                    <button className="btn" onClick={() => void loadFromDisk()}>
                      载入磁盘版本
                    </button>
                    <button className="btn" onClick={() => void save(true)}>
                      用我的覆盖
                    </button>
                  </div>
                ) : null}

                <textarea
                  className="kb-textarea"
                  value={draft}
                  spellCheck={false}
                  placeholder="写点什么…"
                  onChange={(e) => {
                    setDraft(e.target.value);
                    setDirty(true);
                  }}
                />
                <div className="kb-foot">
                  <span>{draft.length} 字符</span>
                  <span className="kb-grow" />
                  <span>
                    {diskMtime ? `磁盘版本 ${new Date(diskMtime).toLocaleTimeString()}` : ""}
                  </span>
                </div>
                {/* 附件走旁路 knowledge_links.json，不碰 knowledge 表、不加列。
                    Obsidian 手工建的裸 .md 没有 frontmatter uid，退化成按路径绑定。 */}
                {meta ? (
                  <LinkSection
                    uid={meta.uid || vaultUid(open)}
                    links={ctx.links}
                    onSave={ctx.saveLinks}
                    onError={setError}
                    hint={
                      meta.uid
                        ? undefined
                        : "这篇笔记没有 frontmatter 标识，附件暂按路径绑定；改名后需重新添加。"
                    }
                  />
                ) : null}
              </>
            ) : (
              <div className="empty" style={{ flex: 1 }}>
                <span className="empty-icon">
                  <IconFile size={22} />
                </span>
                <span className="empty-title">
                  {paneOpen ? "左边选一篇，或者新建一篇" : "展开资源管理器选一篇"}
                </span>
                <span className="empty-hint">
                  笔记以 Markdown 存在库里的 Stumate/ 目录下，用别的编辑器打开同一份文件也不会冲突。
                </span>
              </div>
            )}
            </div>
          </section>
        </div>
        )}
      </div>

      {error ? (
        <div className="kb-error" onClick={() => setError(null)}>
          {error}
        </div>
      ) : null}

      {menu ? (
        <div
          className="kb-menu"
          style={{ left: menu.x, top: menu.y }}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <button
            onClick={() => {
              void createNote(menuDir);
              setMenu(null);
            }}
          >
            新建笔记
          </button>
          <button
            onClick={() => {
              void createFolder(menuDir);
              setMenu(null);
            }}
          >
            新建文件夹
          </button>
          <div className="kb-menu-sep" />
          <button
            onClick={() => {
              setRenaming({
                path: menu.node.path,
                value: menu.node.kind === "note" ? baseTitle(menu.node.path) : menu.node.name,
              });
              setMenu(null);
            }}
          >
            重命名
          </button>
          <button
            onClick={() => {
              setMoving(menu.node);
              setMenu(null);
            }}
          >
            移动到…
          </button>
          <div className="kb-menu-sep" />
          <button
            className="danger"
            onClick={() => {
              setDeleting(menu.node);
              setMenu(null);
            }}
          >
            删除
          </button>
        </div>
      ) : null}

      {quickPresence.mounted ? (
        <QuickOpen
          leaving={quickPresence.leaving}
          notes={notes}
          onPick={(path) => {
            setQuickOpen(false);
            void openNote(path);
          }}
          onClose={() => setQuickOpen(false)}
        />
      ) : null}

      {movingPresence.mounted && shownMoving ? (
        <Dialog
          leaving={movingPresence.leaving}
          title={`移动「${shownMoving.kind === "note" ? baseTitle(shownMoving.path) : shownMoving.name}」`}
          subtitle="选一个目标文件夹"
          onClose={() => setMoving(null)}
          width={360}
        >
          <div className="kb-move-list">
            <button className="kb-move-row" onClick={() => void doMove("")}>
              根目录
            </button>
            {foldersOf(tree).map((f) => (
              <button key={f.path} className="kb-move-row" onClick={() => void doMove(f.path)}>
                {f.path}
              </button>
            ))}
          </div>
        </Dialog>
      ) : null}

      {deletingPresence.mounted && shownDeleting ? (
        <Dialog
          leaving={deletingPresence.leaving}
          title={`删除「${shownDeleting.kind === "note" ? baseTitle(shownDeleting.path) : shownDeleting.name}」？`}
          subtitle="会移到库里的 .trash 目录，不会真的删掉文件"
          onClose={() => setDeleting(null)}
          actions={
            <>
              <button className="btn" onClick={() => setDeleting(null)}>
                取消
              </button>
              <button className="btn danger" onClick={() => void doDelete()}>
                移到回收目录
              </button>
            </>
          }
        />
      ) : null}

      {trashPresence.mounted ? (
        <TrashDialog
          base={base}
          leaving={trashPresence.leaving}
          onClose={() => setTrashOpen(false)}
          onChanged={refresh}
          toast={ctx.toast}
        />
      ) : null}

      {conflictPresence.mounted && shownConflict ? (
        <Dialog
          leaving={conflictPresence.leaving}
          title={shownConflict.kind === "rename" ? "名字已经被占用" : "目标目录里有同名项"}
          subtitle={
            shownConflict.kind === "rename"
              ? `「${shownConflict.desired}」已存在`
              : `${shownConflict.desired} 里已经有同名的笔记或文件夹`
          }
          onClose={() => setNameConflict(null)}
          width={420}
          actions={
            <>
              <button className="btn" onClick={() => setNameConflict(null)}>
                取消
              </button>
              <button className="btn primary" onClick={() => void applyNameConflict()}>
                用这个名字
              </button>
            </>
          }
        >
          <div className="kb-conflict-body">
            继续的话会保存为
            <code>{baseTitle(shownConflict.next)}</code>
            。取消则不动磁盘，可以回去换个名字再来。
          </div>
        </Dialog>
      ) : null}
    </>
  );
}

/**
 * 「没撞名时本该落到的路径」。
 *
 * `renamePath` 只返回最终路径，顺延过没顺延过从结果上看不出来；要弹确认
 * 就得把用户输入按同一套清洗规则（sanitize / 去 .md / 补 .md）折算一遍，
 * 再和它比。规则必须与 `vaultTree.renamePath` 的前半段保持一致，否则会出现
 * 「用户只是打了 `.md`，却被问一遍要不要改名」这种假阳性。
 */
function desiredRenamePath(path: string, value: string): string {
  const isNote = path.toLowerCase().endsWith(NOTE_EXT);
  const stem = isNote ? baseTitle(path) : path.slice(path.lastIndexOf("/") + 1);
  let name = sanitizeSegment(value, stem);
  if (isNote) {
    if (name.toLowerCase().endsWith(NOTE_EXT)) name = name.slice(0, -NOTE_EXT.length);
    name = `${name}${NOTE_EXT}`;
  }
  return joinPath(parentOf(path), name);
}

/** 所有文件夹节点，按显示顺序（「移动到…」的候选） */
function foldersOf(nodes: TreeNode[]): TreeNode[] {
  const out: TreeNode[] = [];
  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.kind !== "folder") continue;
      out.push(n);
      walk(n.children);
    }
  };
  walk(nodes);
  return out;
}

function newUid(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
