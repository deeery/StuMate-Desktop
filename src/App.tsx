/**
 * 应用根：数据装载、主题、导航、全局 Toast。
 *
 * 状态管理刻意保持「一个 hook + 显式 reload」：这套应用的数据量是几百行，
 * 引入状态库的收益抵不过「改一处要跨三个文件」的成本。每个写操作都是
 * 「调后端 → 重新拉那一张表」，与原版 `MainViewModel` 的 `_classes.value = dao.getAll()` 同构。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, onCheckNow, onCloseRequested, type AppInfo } from "./api";
import { Sidebar, WindowControls, type PageKey } from "./components/Chrome";
import { Checkbox, Dialog, ToastHost, type ToastMessage } from "./components/ui";
import { usePresence } from "./components/Presence";
import { ReminderBanner } from "./components/ReminderBanner";
import { useReminder } from "./useReminder";
import { reminderModeOf, type ReminderAlert } from "./model/reminder";
import { accentOf } from "./model/accent";
import { themeNameOf, tintOf } from "./model/theme";
import type { RecurringItem } from "./model/recurring";
import type {
  ClassEntity,
  KnowledgeEntity,
  LinkRef,
  MindNode,
  NoteEntity,
  NoteLink,
  Prefs,
} from "./model/types";
import { TodayPage } from "./pages/TodayPage";
import { WeekPage } from "./pages/WeekPage";
import { NotesPage } from "./pages/NotesPage";
import { KnowledgePage } from "./pages/KnowledgePage";
import { RecurringPage } from "./pages/RecurringPage";
import { SettingsPage } from "./pages/SettingsPage";

export interface AppContextValue {
  info: AppInfo;
  prefs: Prefs;
  setPref: (key: string, value: unknown) => Promise<void>;
  /** 预览用：`STUMATE_LITE_VIEW` 指定的初始视图，正常启动为 null */
  startView: string | null;
  classes: ClassEntity[];
  notes: NoteEntity[];
  knowledge: KnowledgeEntity[];
  links: LinkRef[];
  /** 循环提醒规则（recurring.json，Lite 专属） */
  recurring: RecurringItem[];
  /** 便签 ↔ 课程 / 循环提醒关联（note_links.json，Lite 专属） */
  noteLinks: NoteLink[];
  reloadClasses: () => Promise<void>;
  reloadNotes: () => Promise<void>;
  reloadKnowledge: () => Promise<void>;
  reloadLinks: () => Promise<void>;
  reloadRecurring: () => Promise<void>;
  /** 就地保存附件列表：写盘 + 更新内存，页面不必等一次全量 reload */
  saveLinks: (links: LinkRef[]) => Promise<void>;
  /** 就地保存规则列表：轮询每秒都可能改 lastFiredAt，不能等全量 reload */
  saveRecurring: (items: RecurringItem[]) => Promise<void>;
  /** 就地保存便签关联 */
  saveNoteLinks: (links: NoteLink[]) => Promise<void>;
  toast: (text: string, tone?: "info" | "error") => void;
  theme: "light" | "dark";
  /**
   * 直接推一条「窗口内提醒」横幅。设置页的「预览」按钮用它在当前模式是
   * 窗口内时试播一次 —— 否则预览按钮只会弹全局浮窗，看到的不是用户选的东西。
   */
  showReminderBanner: (alert: ReminderAlert) => void;
}

export default function App() {
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [prefs, setPrefs] = useState<Prefs | null>(null);
  const [classes, setClasses] = useState<ClassEntity[]>([]);
  const [notes, setNotes] = useState<NoteEntity[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeEntity[]>([]);
  const [links, setLinks] = useState<LinkRef[]>([]);
  const [recurring, setRecurring] = useState<RecurringItem[]>([]);
  const [noteLinks, setNoteLinks] = useState<NoteLink[]>([]);
  const [page, setPage] = useState<PageKey>("today");
  /**
   * v1.7.28：知识库是实验性功能，默认不进侧栏。关掉只是藏入口 ——
   * 库文件、库根偏好、已选的目录一个都不动，重新打开还是原来那份。
   *
   * 声明得比 `prefs` 的正式读取更早：Ctrl+4 的键盘监听和首屏 `?page=` 分发
   * 都要用它，而那两个 effect 在 `prefs` 判定之前。预加载完成前按 `false` 算。
   */
  const knowledgeOn = prefs?.experimentalKnowledge === true;
  /**
   * 跨页定位便签：课程 / 循环提醒详情栏里点一条关联便签时，切到便签页并选中它。
   * 用 `{ id, seq }` 而不是裸 id —— 连点同一条时 seq 变化才能重新触发选中效果。
   */
  const [noteFocus, setNoteFocus] = useState<{ id: number; seq: number } | null>(null);
  const [bootError, setBootError] = useState<string | null>(null);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const toastSeq = useRef(1);
  // 关闭确认框（closeAction = 0「询问」时才出现）
  const [closeAsk, setCloseAsk] = useState(false);
  const [dontAskAgain, setDontAskAgain] = useState(false);

  const toast = useCallback((text: string, tone: "info" | "error" = "info") => {
    const id = toastSeq.current++;
    setToasts((prev) => [...prev, { id, text, tone }]);
    const life = tone === "error" ? 5200 : 2600;
    window.setTimeout(() => {
      // 分两段：先标记退场（140ms 收起动画），再真正从列表里摘掉。
      // 一次性 filter 的话提示条会在消失的那一帧凭空蒸发。
      setToasts((prev) => prev.map((t) => (t.id === id ? { ...t, leaving: true } : t)));
      window.setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
      }, 160);
    }, life);
  }, []);

  const reloadClasses = useCallback(async () => {
    setClasses(await api.classes());
  }, []);
  const reloadNotes = useCallback(async () => {
    setNotes(await api.notes());
  }, []);
  const reloadKnowledge = useCallback(async () => {
    setKnowledge(await api.knowledge());
  }, []);
  const reloadLinks = useCallback(async () => {
    const payload = await api.linksLoad();
    setLinks(Array.isArray(payload?.links) ? payload.links : []);
  }, []);
  const reloadRecurring = useCallback(async () => {
    const payload = await api.recurringLoad();
    setRecurring(Array.isArray(payload?.items) ? payload.items : []);
  }, []);
  const reloadNoteLinks = useCallback(async () => {
    const payload = await api.noteLinksLoad();
    setNoteLinks(Array.isArray(payload?.links) ? payload.links : []);
  }, []);
  const saveLinks = useCallback(async (next: LinkRef[]) => {
    await api.linksSave(next);
    setLinks(next);
  }, []);
  const saveRecurring = useCallback(async (next: RecurringItem[]) => {
    await api.recurringSave(next);
    setRecurring(next);
  }, []);
  const saveNoteLinks = useCallback(async (next: NoteLink[]) => {
    await api.noteLinksSave(next);
    setNoteLinks(next);
  }, []);
  const openNote = useCallback((id: number) => {
    setNoteFocus((f) => ({ id, seq: (f?.seq ?? 0) + 1 }));
    setPage("notes");
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const [
          appInfo,
          prefsValue,
          classList,
          noteList,
          knowledgeList,
          linkPayload,
          recurringPayload,
          noteLinkPayload,
        ] = await Promise.all([
          api.appInfo(),
          api.prefs(),
          api.classes(),
          api.notes(),
          api.knowledge(),
          api.linksLoad(),
          api.recurringLoad(),
          api.noteLinksLoad(),
        ]);
        setInfo(appInfo);
        setPrefs(prefsValue);
        setClasses(classList);
        setNotes(noteList);
        setKnowledge(knowledgeList);
        setLinks(Array.isArray(linkPayload?.links) ? linkPayload.links : []);
        setRecurring(Array.isArray(recurringPayload?.items) ? recurringPayload.items : []);
        setNoteLinks(Array.isArray(noteLinkPayload?.links) ? noteLinkPayload.links : []);
      } catch (err) {
        setBootError(errorText(err));
      } finally {
        // 启动窗等的就是这一票。七张表有没有拉成功都要投 —— 失败时主窗口会画
        // 错误页，不投的话用户会对着启动窗干等 watchdog 的 12 秒。
        void api.splashDone().catch(() => {});
      }
    })();
  }, []);

  // 预览用：环境变量指定初始页（验收截图时不必再点导航）。
  // 正常启动 startPage 为 null，这一整段是空转。
  //
  // URL 里的 `?page=` 优先于环境变量：同一个进程里开第二个窗口时，两个窗口
  // 共享同一份环境变量，只有 URL 能让它们各说各话（`--kb` 与「在新窗口打开」
  // 都靠这个）。
  const startApplied = useRef(false);
  useEffect(() => {
    if (!info || startApplied.current) return;
    startApplied.current = true;
    const allowed: PageKey[] = ["today", "week", "notes", "knowledge", "recurring", "settings"];
    const fromUrl = new URLSearchParams(window.location.search).get("page");
    const target = (fromUrl ?? info.startPage) as PageKey | null;
    if (target && allowed.includes(target)) setPage(target);
  }, [info]);

  const setPref = useCallback(async (key: string, value: unknown) => {
    const next = await api.setPref(key, value);
    setPrefs(next);
  }, []);

  // 关闭请求：`closeAction == 0`（询问）时 Rust 端会拦下关闭并发这个事件。
  // 订阅只建一次 —— 它不依赖任何 state，重建没有意义。
  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | null = null;
    onCloseRequested(() => setCloseAsk(true))
      .then((fn) => {
        if (alive) unlisten = fn;
        // 组件在订阅完成前就卸载了，把订阅立刻退掉，否则会泄漏
        else fn();
      })
      .catch(() => {
        // 订阅失败不该挡住应用：Rust 侧有「事件发不出去就退出」的兜底
      });
    return () => {
      alive = false;
      unlisten?.();
    };
  }, []);

  const resolveClose = useCallback(
    async (action: number) => {
      // 先收起对话框：「退出」那一档会立刻结束进程，来不及再走一次渲染
      setCloseAsk(false);
      try {
        await api.closeResolve(action, dontAskAgain);
      } catch (err) {
        toast(errorText(err), "error");
      }
      setDontAskAgain(false);
    },
    [dontAskAgain, toast],
  );

  // 提醒轮询：上课提醒与循环提醒共用托盘状态、浮窗/横幅、系统通知三通道
  // 窗口内提醒的横幅状态放这里 —— 它要跟着主窗口一起渲染，浮窗则完全在另一个窗口里
  const [banner, setBanner] = useState<ReminderAlert | null>(null);
  const { checkNow } = useReminder(
    classes,
    prefs,
    Boolean(info && prefs),
    toast,
    recurring,
    saveRecurring,
    setBanner,
  );

  // 关闭确认框的退场：`closeAsk` 变 false 之后还要留 120ms 播收起动画。
  // 必须在所有 early return 之前调用 —— 否则 hooks 顺序会在「启动中」和
  // 「已就绪」两个分支之间变来变去。
  const closePresence = usePresence(closeAsk);

  // 托盘菜单「立即检查一次」
  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | null = null;
    onCheckNow(() => checkNow())
      .then((fn) => {
        if (alive) unlisten = fn;
        else fn();
      })
      .catch(() => {});
    return () => {
      alive = false;
      unlisten?.();
    };
  }, [checkNow]);

  // 主题：0 跟随系统（要监听系统切换，否则改了 Windows 主题应用不跟）
  const [systemDark, setSystemDark] = useState(
    () => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false,
  );
  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const theme: "light" | "dark" = useMemo(() => {
    // 预览用：环境变量强制主题，优先于偏好设置（截图时不去改用户的 settings 文件）
    if (info?.startTheme === "light") return "light";
    if (info?.startTheme === "dark") return "dark";
    return themeNameOf(prefs?.themeMode, systemDark);
  }, [info?.startTheme, prefs?.themeMode, systemDark]);

  /**
   * 「颜色正在整体切换」的那 260ms。主题和主题色都用它买时间：
   * 给 <html> 挂临时类，让全站颜色类属性走一条统一过渡（规则在 app.css 过渡层）。
   * 共用同一个定时器 —— 两个 effect 各自计时会把对方的类提前摘掉。
   */
  const switchTimer = useRef<number | null>(null);
  const flashSwitching = useCallback(() => {
    const root = document.documentElement;
    root.classList.add("theme-switching");
    if (switchTimer.current !== null) window.clearTimeout(switchTimer.current);
    switchTimer.current = window.setTimeout(() => {
      root.classList.remove("theme-switching");
      switchTimer.current = null;
    }, 260);
  }, []);
  useEffect(
    () => () => {
      if (switchTimer.current !== null) window.clearTimeout(switchTimer.current);
      document.documentElement.classList.remove("theme-switching");
    },
    [],
  );

  /**
   * 配色方案（v1.7.28）：主题档位为「主题色（亮）/（暗）」时，中性面按 `--accent`
   * 掺一遍；其余档位保持原来的中性灰。掺色本身全在 tokens.css 里，
   * 这里只负责把 `data-tint` 挂上去。
   */
  const tint = tintOf(prefs?.themeMode);
  const lastTint = useRef<string | null>(null);
  useEffect(() => {
    document.documentElement.dataset.tint = tint;
    const prev = lastTint.current;
    lastTint.current = tint;
    if (prev === null || prev === tint) return;
    flashSwitching();
  }, [tint, flashSwitching]);

  /**
   * 主题落地。切 data-theme 的那一帧，所有 CSS 变量一起换值 —— 底色、文字、
   * 描边、课程块的洗色全部瞬变，观感就是「啪」地翻了一页。
   *
   * 解决办法是切换前后 240ms 内给 <html> 挂一个临时类，让全站的颜色类属性
   * 走一条统一过渡（规则在 app.css 的过渡层里）。CSS 变量本身不能过渡，
   * 但变量算完之后落到 background-color / color 上的**计算值**可以。
   *
   * 首次挂载不播：启动时从浅到深渐变一遍，看起来像闪屏。
   */
  const lastTheme = useRef<string | null>(null);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    const prev = lastTheme.current;
    lastTheme.current = theme;
    if (prev === null || prev === theme) return;
    flashSwitching();
  }, [theme, flashSwitching]);

  /**
   * 主题色（v1.7.28）。和主题同一套路：只改 `<html>` 上的 `data-accent`，
   * 由 tokens.css 里那一组选择器去覆盖 `--accent` / `--on-accent`。
   *
   * 换色也要走那条统一过渡 —— 强调色铺在按钮、选中态、正在上课的主卡上，
   * 硬切会像被人抽掉了一层。所以和主题共用同一个「临时类 + 定时器」，
   * 谁最后改谁重新计时，不会出现两个定时器互相把类摘掉。
   */
  const accentId = accentOf(prefs?.accent).id;
  const lastAccent = useRef<string | null>(null);
  useEffect(() => {
    document.documentElement.dataset.accent = accentId;
    const prev = lastAccent.current;
    lastAccent.current = accentId;
    if (prev === null || prev === accentId) return;
    flashSwitching();
  }, [accentId, flashSwitching]);

  // Ctrl+1..6 切页：桌面端习惯。1..5 沿用旧映射，新页补在 6，
  // 免得老用户按 Ctrl+5 时设置的落点突然变掉。
  //
  // v1.7.28：知识库关着时 Ctrl+4 直接吞掉。不改成「跳到设置」——
  // 那是把一次误触变成一次跳转，比没反应更烦。
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.ctrlKey || e.altKey) return;
      const map: Record<string, PageKey> = {
        "1": "today",
        "2": "week",
        "3": "notes",
        "4": "knowledge",
        "5": "settings",
        "6": "recurring",
      };
      const target = map[e.key];
      if (!target) return;
      if (target === "knowledge" && !knowledgeOn) return;
      e.preventDefault();
      setPage(target);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [knowledgeOn]);

  if (bootError) {
    return (
      <div className="app-shell">
        <div className="empty" style={{ height: "100vh" }}>
          <strong style={{ color: "var(--error)" }}>StuMate Lite 启动失败</strong>
          <pre
            style={{
              whiteSpace: "pre-wrap",
              maxWidth: 620,
              fontFamily: "var(--font-mono)",
              fontSize: 12,
              color: "var(--on-surface-variant)",
            }}
          >
            {bootError}
          </pre>
        </div>
      </div>
    );
  }

  if (!info || !prefs) {
    return (
      <div className="app-shell">
        <div className="empty" style={{ height: "100vh" }}>
          正在打开本地数据…
        </div>
      </div>
    );
  }

  const ctx: AppContextValue = {
    info,
    prefs,
    setPref,
    startView: info.startView ?? null,
    classes,
    notes,
    knowledge,
    links,
    recurring,
    noteLinks,
    reloadClasses,
    reloadNotes,
    reloadKnowledge,
    reloadLinks,
    reloadRecurring,
    saveLinks,
    saveRecurring,
    saveNoteLinks,
    toast,
    theme,
    showReminderBanner: setBanner,
  };

  // v1.7.21：左下角改回原版口径。Compose 版那一行显示的是**提醒服务状态**
  //（`AppShell.kt`：running → 「提醒服务运行中」，否则「提醒服务未启动」），
  // 不是数据源信息；这里跟着对齐。Lite 没有独立后台进程，提醒轮询就在 WebView
  // 里跑。
  // v1.7.28：提醒现在有两个闸门 —— 视觉浮窗（`reminderMode` ≠ 2）与系统通知
  //（`notifyEnabled`）。任一条通就算「在跑」；只认 notifyEnabled 的话，用户在
  // 「窗口内提醒」下关掉通知，左下角会写着「未启动」却照样弹横幅。
  const reminderMode = reminderModeOf(prefs);
  const remindersOn = prefs.notifyEnabled || reminderMode !== 2;
  const status = remindersOn ? "提醒服务运行中" : "提醒服务未启动";
  const statusHint = `${status} · ${
    reminderMode === 2
      ? "不弹提醒"
      : reminderMode === 1
        ? "窗口内提醒"
        : "全局提醒"
  } · ${info.sharedWithOriginal ? "共用原版数据" : "独立数据"} · schema v${info.schemaVersion}`;

  // v1.7.28：知识库关着时（见 `knowledgeOn`），侧栏不出这一项。

  // `?solo=1` = 知识库专用窗口：藏掉侧栏，只留「树 + 编辑器」。
  // 读一次即可 —— 窗口创建之后 URL 不会再变，没必要进响应式状态。
  const solo = new URLSearchParams(window.location.search).get("solo") === "1";

  return (
    <div className="app-shell">
      <div className="app-body">
        {/* `?solo=1` = 知识库专用窗口（`--kb` 或「在新窗口打开」）：
            藏掉侧栏，只剩一棵树 + 一个编辑器。窗口标题栏与窗口按钮仍然保留，
            否则这个窗口既没法拖也没法关。 */}
        {solo ? null : (
          <Sidebar
            page={page}
            onNavigate={setPage}
            collapsed={prefs.sidebarCollapsed}
            onToggleCollapse={() => void setPref("sidebarCollapsed", !prefs.sidebarCollapsed)}
            status={status}
            statusOn={remindersOn}
            statusHint={statusHint}
            version={info.version}
            noteCount={notes.length}
            recurringCount={recurring.filter((r) => r.enabled).length}
            experimentalOn={knowledgeOn}
          />
        )}
        <main className="page">
          {/* `key={page}`：切页时整块重挂载，入场动画随之重播。
              旧页在同一帧被移除 —— 单边入场，不做交叉淡入（两屏文字叠在一起
              时中间那一段不可读，代价还是一次双份测量）。 */}
          <div className="page-stage" key={page}>
            {page === "today" && <TodayPage ctx={ctx} onNavigate={setPage} />}
            {page === "week" && <WeekPage ctx={ctx} onOpenNote={openNote} />}
            {page === "notes" && <NotesPage ctx={ctx} focusNote={noteFocus} />}
            {page === "knowledge" &&
              (knowledgeOn || solo ? (
                <KnowledgePage ctx={ctx} solo={solo} />
              ) : (
                // 兜底：只有从 URL（`?page=knowledge`）或刚关掉开关时才会走到这里。
                // 直接甩回「今天」会让人以为按错了，给一块说明 + 去设置的入口。
                <KnowledgeLocked onOpenSettings={() => setPage("settings")} />
              ))}
            {page === "recurring" && <RecurringPage ctx={ctx} />}
            {page === "settings" && <SettingsPage ctx={ctx} onNavigate={setPage} />}
          </div>
        </main>
      </div>
      {closePresence.mounted ? (
        <CloseConfirmDialog
          leaving={closePresence.leaving}
          dontAskAgain={dontAskAgain}
          onDontAskAgain={setDontAskAgain}
          onCancel={() => {
            setCloseAsk(false);
            setDontAskAgain(false);
          }}
          onMinimize={() => void resolveClose(1)}
          onQuit={() => void resolveClose(2)}
        />
      ) : null}
      <ToastHost toasts={toasts} />
      {/* 窗口内提醒（`reminderMode === 1`）：从主窗口上沿滑下来。
          全局模式与「不提醒」下 `banner` 恒为 null，这里渲染不出任何东西。 */}
      <ReminderBanner
        alert={banner}
        onDismiss={() => setBanner(null)}
        onSkip={(ruleId) => {
          // 与浮窗上的「跳过本次」走同一条路：Rust 只广播 ruleId，
          // 相位推进仍由 useReminder 落盘（规则只在前端有一份实现）
          setBanner(null);
          void api.overlaySkip(ruleId);
        }}
      />
    </div>
  );
}

/**
 * 关闭确认框。文案与按钮布局对齐原版 `Main.kt` 的 `CloseConfirmDialog`：
 * 「退出」单独靠左（危险动作，离其他按钮远一点，别误点），
 * 「取消」和「最小化到托盘」靠右，默认动线是"最小化"。
 */
function CloseConfirmDialog({
  leaving,
  dontAskAgain,
  onDontAskAgain,
  onCancel,
  onMinimize,
  onQuit,
}: {
  leaving?: boolean;
  dontAskAgain: boolean;
  onDontAskAgain: (v: boolean) => void;
  onCancel: () => void;
  onMinimize: () => void;
  onQuit: () => void;
}) {
  return (
    <Dialog
      leaving={leaving}
      title="关闭窗口"
      subtitle="最小化到托盘后应用继续在后台运行，到点仍会提醒；直接退出则停止提醒。"
      width={440}
      onClose={onCancel}
      actions={
        <>
          <button className="btn danger" style={{ marginRight: "auto" }} onClick={onQuit}>
            退出
          </button>
          <button className="btn ghost" onClick={onCancel}>
            取消
          </button>
          <button className="btn primary" onClick={onMinimize}>
            最小化到托盘
          </button>
        </>
      }
    >
      <Checkbox checked={dontAskAgain} onChange={onDontAskAgain} label="下次不再提问" />
    </Dialog>
  );
}

/**
 * 知识库关着时的落地页（v1.7.28）。
 *
 * 只在两种情况下会看到：从 `?page=knowledge`（旧快捷方式 / `--kb` 参数）打开，
 * 或者刚在设置里把它关掉、页面还停在知识库上。不做静默跳转 —— 那样用户
 * 只会觉得「手滑点错了」，不知道该去设置里开。
 */
function KnowledgeLocked({ onOpenSettings }: { onOpenSettings: () => void }) {
  return (
    <>
      <PageTopBar title="知识库" tag="实验性" subtitle="未启用" />
      <div className="page-content">
        <div className="empty" style={{ paddingTop: 56, gap: 10 }}>
          <strong style={{ fontSize: 15 }}>知识库还没启用</strong>
          <span style={{ maxWidth: 430, lineHeight: 1.75, textAlign: "center" }}>
            这是实验性功能：文件树、Markdown 编辑器和思维导图都还在打磨，
            所以默认不放进侧栏。在设置里打开后会常驻侧栏，库文件仍然放在你自己选的目录里。
          </span>
          <button className="btn primary" onClick={onOpenSettings}>
            去设置里启用
          </button>
        </div>
      </div>
    </>
  );
}

/**
 * 页头。它同时充当自绘标题栏（窗口是无边框的）。
 *
 * 从左到右：`[标题 + 副标题]` ── 弹性空白（可拖动、双击最大化）── `[页面操作]` `[窗口按钮]`
 *
 * 空白处用 `flex:1` 吃掉剩余宽度：一来它天然是拖动把手，二来页面操作与窗口按钮
 * 被稳定推到右端，**在六个页面之间保持同一水平位置**（与原版 `PageTopBar` 一致）。
 */
export function PageTopBar({
  title,
  tag,
  subtitle,
  children,
}: {
  title: string;
  /** 标题右侧的小标签，例如实验性功能的「实验性」。用 warning 色，比正文弱、比副标题强 */
  tag?: string;
  subtitle?: string;
  children?: React.ReactNode;
}) {
  return (
    <header className="page-topbar">
      <div className="page-title-area">
        <span className="page-title">{title}</span>
        {tag ? (
          <span className="chip warn" style={{ alignSelf: "center" }}>
            {tag}
          </span>
        ) : null}
        {subtitle ? <span className="page-sub">{subtitle}</span> : null}
      </div>
      {children ? <div className="page-actions">{children}</div> : null}
      <WindowControls />
    </header>
  );
}
