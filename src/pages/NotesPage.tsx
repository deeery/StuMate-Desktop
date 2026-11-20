/**
 * 便签页：左侧表格 + 右侧「详情 / 编辑」同一列（与原版 `NotesPage.kt` 同一套版式）。
 *
 * 这一页在 v1.7.11 之前是「表格 + 弹窗」：点一行弹一个居中对话框。原版不是这样 ——
 * 原版点行只是**选中**，编辑发生在右列那块 340dp 的面板里，表格本身一直是可见的。
 * 差别不只是好看：弹窗盖住表格后没法对照上下条，也看不到「刚才那行现在什么状态」。
 *
 * v1.7.28：右列改成**两步走**，与课表页（`WeekPage` 的 `ClassDetailPanel` → 编辑对话框）
 * 对齐 ——
 *   1. 点一行只打开**只读详情**（268 宽）；
 *   2. 详情里点「编辑」（或双击行 / 点行尾铅笔）才进编辑表单（340 宽）。
 * 为什么要拆：以前点一行直接是十几个输入框，想「看一眼这条写了什么」也得先面对表单，
 * 而且手一抖点到输入框就先改了草稿。详情是读，编辑是写，两件事分开。
 *
 * 拖动排序的几何全部走 `model/notes.ts`（v1.7.5 把那段从 Compose 闭包里抽出来
 * 修掉了三个缺陷：冻结的 index、拿自己行高当步长、筛选下标喂给全量列表）。
 * 这里只做三件事：量行高 → 喂像素 → 把结果画出去。
 *
 * ⚠️ 拖动期间**不动数据**，松手才落位。否则每跨一格都写一次库，
 * 后端 reload 会把正在拖的行重建，手势直接断掉。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { PageTopBar, type AppContextValue } from "../App";
import { Select } from "../components/ui";
import { usePresence } from "../components/Presence";
import { IconClose, IconEdit, IconNote, IconPlus, IconSearch, IconTrash } from "../components/Icons";
import type { ClassEntity, NoteEntity, NoteLink } from "../model/types";
import { EMPTY_NOTE } from "../model/types";
import type { RecurringItem } from "../model/recurring";
import { dropNoteLink, linkOf, upsertLink } from "../model/noteLinks";
import {
  NOTE_TYPES,
  NOTE_TYPE_DEADLINE,
  autoScrollVelocity,
  clampOffset,
  deadlineCountdown,
  deadlineTimeLabel,
  hasDeadline,
  moveInFullOrder,
  resolveDropIndex,
  shiftFor,
  slotTops,
  typeLabel,
} from "../model/notes";
import { api, errorText } from "../api";

type SortMode = "manual" | "deadline";

/**
 * 列宽。**表头与行必须共用同一组常量** —— 分开写死两份数值，改一边就会悄悄错位，
 * 而这种错位在截图里只表现为「表头文字没对齐」，很容易被当成字体问题查半天。
 * 数值照抄原版 `NotesPage.kt` 的五个 dp 常量。
 */
const COL_GRIP = 26;
const COL_COLOR = 20;
const COL_TYPE = 96;
const COL_DEADLINE = 168;
const COL_ACTION = 64;

/** 「分类」下拉里代表「全部」的取值。用字符串是为了和 `typeFilter === null` 区分开 */
const ALL_TYPES = "__all__";

export function NotesPage({
  ctx,
  focusNote,
}: {
  ctx: AppContextValue;
  /** 从课程 / 循环提醒详情栏「点关联便签」跳过来时要选中的那一条 */
  focusNote?: { id: number; seq: number } | null;
}) {
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<number | null>(null);
  const [sort, setSort] = useState<SortMode>("manual");
  /** 右列「只读详情」看的是哪一条（点一行进来的就是这个） */
  const [selectedId, setSelectedId] = useState<number | null>(null);
  /** 右列「编辑表单」改的是哪一条；`null` 表示没在改已有便签（新建看 `creating`） */
  const [editingId, setEditingId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [now, setNow] = useState(Date.now());

  /*
   * 跨页跳转：切到这一页时选中详情栏点的那条便签。
   * 用 `seq` 判「这一跳有没有处理过」—— 只依赖 `ctx.notes` 的话，保存后
   * reloadNotes 会让 effect 重跑一次，把用户刚改选的另一条又抢回去。
   * 查询与筛选一并清空：被筛掉的便签即使选中了也看不见，用户会以为跳转失败。
   */
  const focusSeen = useRef(0);
  useEffect(() => {
    if (!focusNote || focusSeen.current === focusNote.seq) return;
    if (!ctx.notes.some((n) => n.id === focusNote.id)) return;
    focusSeen.current = focusNote.seq;
    setQuery("");
    setTypeFilter(null);
    setCreating(false);
    setEditingId(null);
    setSelectedId(focusNote.id);
  }, [focusNote, ctx.notes]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    let list = ctx.notes.filter((n) => {
      if (typeFilter !== null && n.typeIndex !== typeFilter) return false;
      if (!q) return true;
      return n.title.toLowerCase().includes(q) || n.content.toLowerCase().includes(q);
    });
    if (sort === "deadline") {
      list = list.slice().sort((a, b) => {
        const da = hasDeadline(a) ? a.deadlineAt : Number.MAX_SAFE_INTEGER;
        const db = hasDeadline(b) ? b.deadlineAt : Number.MAX_SAFE_INTEGER;
        return da - db;
      });
    }
    return list;
  }, [ctx.notes, query, typeFilter, sort]);

  // 截止排序下禁止手动拖：位置是算出来的，拖了也不会保留
  const canDrag = sort === "manual";

  const selected = ctx.notes.find((n) => n.id === selectedId) ?? null;
  const editing = ctx.notes.find((n) => n.id === editingId) ?? null;

  // 选中的那条被删掉 / 被筛掉时，面板跟着收起。
  // ⚠️ 区分「被筛掉」和「库里还没有」：首帧 notes 是空数组，那时不能判成删除。
  useEffect(() => {
    if (selectedId === null) return;
    if (ctx.notes.length === 0) return;
    if (!visible.some((n) => n.id === selectedId)) setSelectedId(null);
  }, [visible, selectedId, ctx.notes.length]);

  // 正在编辑的那条被删掉（自己按删除也走这里）时退出编辑。
  // 这里用**未筛选**的 ctx.notes：搜索把行筛掉了不等于这条没了，不该打断手上的编辑。
  useEffect(() => {
    if (editingId === null || ctx.notes.length === 0) return;
    if (!ctx.notes.some((n) => n.id === editingId)) setEditingId(null);
  }, [ctx.notes, editingId]);

  /** 点一行 = 打开只读详情（仿课表：单击只选中，不直接进编辑） */
  const select = (note: NoteEntity) => {
    setCreating(false);
    setEditingId(null);
    setSelectedId(note.id);
  };

  /** 进编辑：详情栏「编辑」、双击行、或行尾铅笔。编辑完退回这一条的详情 */
  const startEdit = (note: NoteEntity) => {
    setCreating(false);
    setSelectedId(note.id);
    setEditingId(note.id);
  };

  const startCreate = () => {
    setSelectedId(null);
    setEditingId(null);
    setCreating(true);
  };

  const closePanel = () => {
    setCreating(false);
    setEditingId(null);
    setSelectedId(null);
  };

  /**
   * 编辑表单的「取消」：改已有便签 → 退回这一条的详情，而不是整块收起
   * （收起会像「点错了跳转」）；新建没有详情可退，直接收起。
   */
  const cancelEdit = () => {
    setCreating(false);
    setEditingId(null);
  };

  const save = async (entity: NoteEntity, link: NoteLink) => {
    try {
      // 先落便签本身：新建时 id 由数据库生成，关联要用落盘后的真实 id
      const saved = await api.saveNote(entity);
      const noteId = saved?.id ?? entity.id;
      await ctx.saveNoteLinks(upsertLink(ctx.noteLinks, { ...link, noteId }));
      // 新建后清掉搜索与分类筛选：否则刚存下的那条可能正好被筛掉，详情栏一闪就没了
      if (creating) {
        setQuery("");
        setTypeFilter(null);
      }
      await ctx.reloadNotes();
      // 存完落回详情而不是收起：用户想立刻确认「存成什么样了」
      setCreating(false);
      setEditingId(null);
      setSelectedId(noteId);
      ctx.toast("已保存");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  const remove = async (id: number) => {
    try {
      await api.deleteNote(id);
      // 便签没了，关联也顺手清掉 —— 否则文件里会攒一堆指向不存在 id 的行
      await ctx.saveNoteLinks(dropNoteLink(ctx.noteLinks, id));
      await ctx.reloadNotes();
      closePanel();
      ctx.toast("已删除");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  const commitOrder = useCallback(
    async (nextFull: NoteEntity[]) => {
      try {
        await api.reorderNotes(nextFull.map((n) => n.id));
        await ctx.reloadNotes();
      } catch (err) {
        ctx.toast(errorText(err), "error");
      }
    },
    [ctx],
  );

  const deadlineCount = ctx.notes.filter(hasDeadline).length;

  /**
   * 右列三种内容互斥，共用同一列（`--dock-w` 决定宽度：详情 268 / 编辑 340）：
   *   · 详情 —— 点一行只**看**，字段全只读（仿课表 `ClassDetailPanel`）；
   *   · 编辑 —— 详情栏点「编辑」、双击行、或行尾铅笔才进来（仿课表双击进对话框）；
   *   · 新建 —— 顶栏「新建便签」直接进编辑，没有详情可看。
   */
  type PanelTarget =
    | { kind: "detail"; note: NoteEntity }
    | { kind: "editor"; entity: NoteEntity; isNew: boolean };
  const liveTarget: PanelTarget | null = creating
    ? { kind: "editor", entity: { ...EMPTY_NOTE }, isNew: true }
    : editing
      ? { kind: "editor", entity: editing, isNew: false }
      : selected
        ? { kind: "detail", note: selected }
        : null;

  /**
   * 面板的「退场保留」。
   *
   * 关闭时三个 state **同一帧**就置空了，但面板还要再画一段退场动画。
   * 直接读当前值会让它先塌成空白再淡出（一帧白框），所以用 ref 记住最后一份
   * 可渲染的内容与身份。只在「当前值为空」时才回退到记忆值 ——
   * 否则切到另一条便签时会先闪一帧上一条。
   *
   * 与 Compose 端 `RightPanelTransition` 的 `retained` 是同一个思路。
   */
  const retainedTarget = useRef<PanelTarget | null>(null);
  if (liveTarget) retainedTarget.current = liveTarget;
  const shownTarget = liveTarget ?? retainedTarget.current;
  const panelOpen = liveTarget !== null;
  const panelPresence = usePresence(panelOpen, 260);
  /** 详情 268 / 编辑 340：右列宽度（外层 `--dock-w`，内层固定这么宽） */
  const dockWidth = shownTarget?.kind === "editor" ? 340 : 268;
  /** 抽屉真的在场上（退场期间不算）—— 只有这时两列之间才该留 12px 缝 */
  const dockShowing = panelPresence.mounted && !panelPresence.leaving;

  /**
   * 展开动画只播一次。
   *
   * 右列宽度 0 → `--dock-w` 走关键帧 `note-dock-in`。换档（详情 268 ↔ 编辑 340）
   * 时若还挂着这条动画，宽度会被关键帧的 `to` 钉住，transition 根本不跑；
   * 所以首次展开一结束就切到 `.no-enter`（静态宽度 + transition），
   * 之后换档才是平滑让出/收回那 72px。收起时随节点卸载一起复位。
   */
  const [dockEntered, setDockEntered] = useState(false);
  useEffect(() => {
    if (!panelPresence.mounted) setDockEntered(false);
  }, [panelPresence.mounted]);

  return (
    <>
      <PageTopBar
        title="便签"
        subtitle={
          deadlineCount > 0
            ? `共 ${ctx.notes.length} 条 · ${deadlineCount} 条带截止时间`
            : `共 ${ctx.notes.length} 条`
        }
      >
        <button className="btn primary" onClick={startCreate}>
          <IconPlus size={14} /> 新建便签
        </button>
      </PageTopBar>

      <div className="page-content" style={{ display: "flex", flexDirection: "column" }}>
        {/* ── 工具行 ──
            原版把它放在卡片**外面**、表格上方，并用 Spacer 把排序按钮推到最右。
            v1.7.11 之前这几件控件是塞在卡片顶部的，读起来像「表头的一部分」。
            v1.7.28：提到左右两列**之上**（与课表 `.week-toolbar` 同一层级）——
            之前它在左列里面，右列第一张卡片就顶到页头上沿，两列顶线差 42px。 */}
        <div className="notes-toolrow">
          <div style={{ position: "relative", display: "flex", alignItems: "center" }}>
            <span
              style={{
                position: "absolute",
                left: 8,
                color: "var(--on-surface-faint)",
                display: "flex",
              }}
            >
              <IconSearch size={13} />
            </span>
            <input
              className="input"
              style={{ width: 260, paddingLeft: 28 }}
              placeholder="搜索便签..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <Select<string>
            value={typeFilter === null ? ALL_TYPES : String(typeFilter)}
            options={[
              { value: ALL_TYPES, label: "分类：全部" },
              ...NOTE_TYPES.map((t, i) => ({ t, i }))
                .filter(({ t }) => t.kind !== "none")
                .map(({ t, i }) => ({ value: String(i), label: `分类：${t.label}` })),
            ]}
            onChange={(v) => setTypeFilter(v === ALL_TYPES ? null : Number(v))}
          />
          <span style={{ flex: 1 }} />
          {/* 排序在原版里是右对齐的一行文字按钮（不是下拉）：
              「按截止时间排序」。手动排序是默认态，所以按钮文字表示「点下去做什么」。 */}
          <button
            className="notes-sort"
            onClick={() => setSort((s) => (s === "deadline" ? "manual" : "deadline"))}
          >
            {sort === "deadline" ? "按手动顺序" : "按截止时间排序"}
          </button>
        </div>

        {/* 表格 + 右列并排。右列宽度从 0 撑到 `--dock-w`，`gap` 也交给 CSS 过渡，
            所以收放时左侧表格是**连续**变宽变窄的（与课表 `.week-split` 同一套抽屉）。 */}
        <div
          className={`note-split${dockShowing ? " has-panel" : ""}`}
          style={{ flex: 1, minHeight: 0, display: "flex" }}
        >
          {/* ── 表格卡片 ── */}
          <div
            className="card"
            style={{
              flex: 1,
              minWidth: 0,
              minHeight: 0,
              display: "flex",
              flexDirection: "column",
              overflow: "hidden",
            }}
          >
            <NoteTableHeader />
            <NoteList
              notes={visible}
              all={ctx.notes}
              now={now}
              canDrag={canDrag}
              selectedId={creating ? null : selectedId}
              onSelect={select}
              onEdit={startEdit}
              onDelete={remove}
              onReorder={commitOrder}
            />
          </div>

          {/* ── 右列：详情 / 编辑 ──
              同一列、同一块 DOM：外层 `.note-dock` 只管宽度（`--dock-w`，从 0 撑开），
              内层固定宽、超出裁掉，所以详情 ↔ 编辑换档只是旁边表格让出 72px。
              收起时**整块不渲染**（不是 display:none）：这样输入状态不会在后台偷偷留着，
              下次选中新条目时不会看到上一条的残留。 */}
          {panelPresence.mounted && shownTarget ? (
            <div
              className={`note-dock${dockEntered && !panelPresence.leaving ? " no-enter" : ""}${
                panelPresence.leaving ? " leaving" : ""
              }`}
              style={{ "--dock-w": `${dockWidth}px` } as React.CSSProperties}
              onAnimationEnd={(e) => {
                // 只认外层自己的展开动画：内层卡片的 enter-fade 会冒泡上来
                if (e.target === e.currentTarget && !panelPresence.leaving) {
                  setDockEntered(true);
                }
              }}
            >
              <div className="note-dock-inner">
                {shownTarget.kind === "detail" ? (
                  <NoteDetailPanel
                    key={`detail-${shownTarget.note.id}`}
                    note={shownTarget.note}
                    link={linkOf(ctx.noteLinks, shownTarget.note.id)}
                    classes={ctx.classes}
                    rules={ctx.recurring}
                    now={now}
                    onEdit={() => startEdit(shownTarget.note)}
                    onDelete={() => remove(shownTarget.note.id)}
                    onClose={closePanel}
                  />
                ) : (
                  <NoteEditorPanel
                    key={shownTarget.isNew ? "creating" : `edit-${shownTarget.entity.id}`}
                    entity={shownTarget.entity}
                    isNew={shownTarget.isNew}
                    link={linkOf(ctx.noteLinks, shownTarget.entity.id)}
                    classes={ctx.classes}
                    rules={ctx.recurring}
                    onSave={save}
                    onDelete={remove}
                    onCancel={cancelEdit}
                  />
                )}
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </>
  );
}

/** 表头：标题 / 内容 ｜ 分类 ｜ 截止 ｜ 操作（与原版 `NoteTableHeader` 同列宽、同字号） */
function NoteTableHeader() {
  const cell: React.CSSProperties = {
    fontSize: 11.5,
    fontWeight: 600,
    color: "var(--on-surface-faint)",
  };
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        background: "var(--surface2)",
        padding: "9px 12px",
        flex: "0 0 auto",
      }}
    >
      <span style={{ width: COL_GRIP, flex: `0 0 ${COL_GRIP}px` }} />
      <span style={{ width: COL_COLOR, flex: `0 0 ${COL_COLOR}px` }} />
      <span style={{ flex: 1, minWidth: 0, ...cell }}>标题 / 内容</span>
      <span style={{ width: COL_TYPE, flex: `0 0 ${COL_TYPE}px`, ...cell }}>分类</span>
      <span style={{ width: COL_DEADLINE, flex: `0 0 ${COL_DEADLINE}px`, ...cell }}>截止</span>
      <span style={{ width: COL_ACTION, flex: `0 0 ${COL_ACTION}px` }} />
    </div>
  );
}

interface DragState {
  id: number;
  startIndex: number;
  offset: number;
  tops: number[];
  heights: number[];
  pointerY: number;
}

function NoteList({
  notes,
  all,
  now,
  canDrag,
  selectedId,
  onSelect,
  onEdit,
  onDelete,
  onReorder,
}: {
  notes: NoteEntity[];
  all: NoteEntity[];
  now: number;
  canDrag: boolean;
  selectedId: number | null;
  onSelect: (n: NoteEntity) => void;
  onEdit: (n: NoteEntity) => void;
  onDelete: (id: number) => void;
  onReorder: (nextFull: NoteEntity[]) => void;
}) {
  const rowRefs = useRef(new Map<number, HTMLDivElement>());
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [drag, setDrag] = useState<DragState | null>(null);
  const dragRef = useRef<DragState | null>(null);

  const measure = () => {
    const heights = notes.map((n) => rowRefs.current.get(n.id)?.getBoundingClientRect().height ?? 40);
    return { heights, tops: slotTops(heights) };
  };

  const onPointerDown = (e: React.PointerEvent, note: NoteEntity, index: number) => {
    if (!canDrag) return;
    // 只认主键，右键/中键不拖
    if (e.button !== 0) return;
    // 把手不会顺带选中那一行：拖动和「选中看详情」是两件事，
    // 混在一起会让「想调顺序」变成「右侧面板跳来跳去」。
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    const { heights, tops } = measure();
    const state: DragState = {
      id: note.id,
      startIndex: index,
      offset: 0,
      tops,
      heights,
      pointerY: e.clientY,
    };
    dragRef.current = state;
    setDrag(state);
  };

  const onPointerMove = (e: React.PointerEvent) => {
    const state = dragRef.current;
    if (!state) return;
    e.stopPropagation();
    const offset = clampOffset(e.clientY - state.pointerY, state.tops, state.startIndex);
    const next = { ...state, offset };
    dragRef.current = next;
    setDrag(next);

    // 边缘自动滚动：拖到上下热区时列表自己滚，鼠标不动也持续滚
    const scroller = scrollRef.current;
    if (scroller) {
      const rect = scroller.getBoundingClientRect();
      const rowRect = rowRefs.current.get(state.id)?.getBoundingClientRect();
      if (rowRect) {
        const velocity = autoScrollVelocity(
          rowRect.top - rect.top,
          rowRect.bottom - rect.top,
          rect.height,
          48,
          900,
        );
        if (velocity !== 0) scroller.scrollTop += velocity / 60;
      }
    }
  };

  const finish = () => {
    const state = dragRef.current;
    dragRef.current = null;
    setDrag(null);
    if (!state) return;
    const target = resolveDropIndex(state.tops, state.startIndex, state.offset);
    if (target === state.startIndex) return;
    const dragged = notes[state.startIndex];
    onReorder(moveInFullOrder(all, notes, dragged, target));
  };

  const targetIndex = drag ? resolveDropIndex(drag.tops, drag.startIndex, drag.offset) : -1;

  return (
    <div
      ref={scrollRef}
      style={{
        flex: 1,
        minHeight: 0,
        overflow: "auto",
        position: "relative",
        // 空态要能落在卡片中间，而不是贴着表头下面一小块：
        // 列成 flex 柱子，空态占满剩下的高度（.empty 的 flex:1）
        display: "flex",
        flexDirection: "column",
      }}
    >
      {notes.length === 0 ? (
        <div className="empty">
          <span className="empty-icon">
            <IconNote size={22} />
          </span>
          <span className="empty-title">{all.length === 0 ? "还没有便签" : "没有匹配的便签"}</span>
          <span className="empty-hint">
            {all.length === 0 ? "点右上角「新建便签」随手记一条" : "换个关键词，或把分类筛选改回「全部」"}
          </span>
        </div>
      ) : null}

      {notes.map((note, index) => {
        const active = drag?.id === note.id;
        const dropTarget = drag !== null && targetIndex !== drag.startIndex && targetIndex === index;
        const shift = drag ? shiftFor(index, drag.startIndex, targetIndex, drag.heights[drag.startIndex] ?? 40) : 0;
        const translate = active ? drag?.offset ?? 0 : shift;
        const color = `var(--note-${((note.colorIndex % 8) + 8) % 8})`;
        const sel = !active && note.id === selectedId;

        return (
          <div
            key={note.id}
            ref={(el) => {
              if (el) rowRefs.current.set(note.id, el);
              else rowRefs.current.delete(note.id);
            }}
            style={{
              position: "relative",
              transform: `translateY(${translate}px)`,
              transition: active ? "none" : "transform 140ms cubic-bezier(0.33,0,0,1)",
              zIndex: active ? 5 : 1,
              // 三种底色共用一个属性：被拖动 > 落点 > 选中 > 悬停。
              // 拖行必须有**不透明**底（accent-tint 只有 9%，铺上去会透出下面那一行），
              // 所以它用 surface3，其余两态交给 CSS 类。
              background: active
                ? "var(--surface3)"
                : dropTarget
                  ? "var(--accent-hover)"
                  : undefined,
              boxShadow: active ? "var(--shadow-flyout)" : undefined,
              outline: active ? "1.5px solid var(--accent)" : undefined,
              borderRadius: active ? 4 : 0,
            }}
          >
            <div
              className={`note-row${sel ? " on" : ""}`}
              onClick={() => onSelect(note)}
              onDoubleClick={() => onEdit(note)}
              style={{
                display: "flex",
                alignItems: "stretch",
                borderBottom: "1px solid var(--outline)",
                minHeight: 38,
                padding: "0 12px",
              }}
            >
              <div
                onPointerDown={(e) => onPointerDown(e, note, index)}
                onPointerMove={onPointerMove}
                onPointerUp={finish}
                onPointerCancel={finish}
                title={canDrag ? "拖动排序" : "截止排序下不能拖动"}
                style={{
                  width: COL_GRIP,
                  flex: `0 0 ${COL_GRIP}px`,
                  cursor: canDrag ? "grab" : "not-allowed",
                  display: "grid",
                  placeItems: "center",
                  color: "var(--on-surface-faint)",
                  opacity: canDrag ? 0.7 : 0.25,
                  touchAction: "none",
                }}
              >
                <svg width="8" height="14" viewBox="0 0 8 14" fill="currentColor">
                  <circle cx="2" cy="3" r="1" />
                  <circle cx="6" cy="3" r="1" />
                  <circle cx="2" cy="7" r="1" />
                  <circle cx="6" cy="7" r="1" />
                  <circle cx="2" cy="11" r="1" />
                  <circle cx="6" cy="11" r="1" />
                </svg>
              </div>
              {/* 色条放在固定 20px 的列里居中：列宽固定，行与表头的「标题」起点才对得上 */}
              <div
                style={{
                  width: COL_COLOR,
                  flex: `0 0 ${COL_COLOR}px`,
                  display: "grid",
                  placeItems: "center",
                }}
              >
                <div style={{ width: 3, height: "100%", background: color, opacity: 0.9 }} />
              </div>
              {/* 标题 + 正文摘要两行。正文为空时第二行**整行不占位** ——
                  否则只有标题的便签下面会挂一段空白，行高忽高忽低。 */}
              <div style={{ flex: 1, minWidth: 0, padding: "6px 8px 6px 0" }}>
                <div
                  style={{
                    fontSize: 13,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                    color: "var(--on-surface)",
                  }}
                >
                  {note.title}
                </div>
                {note.content ? (
                  <div
                    style={{
                      fontSize: 11.5,
                      color: "var(--on-surface-variant)",
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      whiteSpace: "nowrap",
                      marginTop: 1,
                    }}
                  >
                    {note.content.replace(/\s+/g, " ")}
                  </div>
                ) : null}
              </div>
              <div
                style={{
                  width: COL_TYPE,
                  flex: `0 0 ${COL_TYPE}px`,
                  display: "flex",
                  alignItems: "center",
                }}
              >
                {/* 有截止时间的分类标签走 .chip.danger：淡红底 + 红字 + 加粗。
                    原来是实心红胶囊 —— 它是「类型」不是「错误」，实心色块会把整张表
                    的视觉重心吸到这一列，标题反而没人看。该跳出来的仍然跳出来。 */}
                {typeLabel(note) ? (
                  <span className={hasDeadline(note) ? "chip danger" : "chip"}>
                    {typeLabel(note)}
                  </span>
                ) : null}
              </div>
              <div
                style={{
                  width: COL_DEADLINE,
                  flex: `0 0 ${COL_DEADLINE}px`,
                  display: "flex",
                  alignItems: "center",
                  fontSize: 12,
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  color: hasDeadline(note) && note.deadlineAt < now
                    ? "var(--error)"
                    : "var(--on-surface-variant)",
                }}
              >
                {hasDeadline(note)
                  ? `${deadlineTimeLabel(note.deadlineAt, now)} · ${deadlineCountdown(note.deadlineAt, now)}`
                  : "—"}
              </div>
              {/* 行内动作：只在悬停/选中时出现，但**占位宽度恒定** 64px ——
                  常驻会让 10 行排出 10 组图标（远看像错误列表），占位不固定会让整行文字随悬停左右抖动。 */}
              <div className="note-row-actions">
                <button
                  className="icon-btn"
                  style={{ width: 24, height: 24 }}
                  title="编辑便签"
                  onClick={(e) => {
                    e.stopPropagation();
                    onEdit(note);
                  }}
                >
                  <IconEdit size={13} />
                </button>
                <button
                  className="icon-btn"
                  style={{ width: 24, height: 24, color: "var(--error)" }}
                  title="删除便签"
                  onClick={(e) => {
                    e.stopPropagation();
                    onDelete(note.id);
                  }}
                >
                  <IconTrash size={13} />
                </button>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

/**
 * 右侧只读详情（v1.7.28）—— 仿课表页 `ClassDetailPanel` 那副骨架：
 * 一张身份卡（色条 + 关闭 + 标题 + 字段表 + 编辑/删除），正文另起一张卡。
 *
 * 为什么要它：以前点一行直接就是十五个输入框，「看一眼这条写了什么」也得先面对表单，
 * 手一抖碰到输入框就改了草稿。详情是**读**，编辑是**写**，分开之后点错也不会改到东西。
 */
function NoteDetailPanel({
  note,
  link,
  classes,
  rules,
  now,
  onEdit,
  onDelete,
  onClose,
}: {
  note: NoteEntity;
  /** 这一条现在关联的课程 / 循环提醒（没有就是 0 / 空串） */
  link: NoteLink;
  classes: ClassEntity[];
  rules: RecurringItem[];
  now: number;
  onEdit: () => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const color = `var(--note-${((note.colorIndex % 8) + 8) % 8})`;
  const cls = classes.find((c) => c.id === link.classId) ?? null;
  const rule = rules.find((r) => r.id === link.ruleId) ?? null;
  const deadline = hasDeadline(note)
    ? `${deadlineTimeLabel(note.deadlineAt, now)} · ${deadlineCountdown(note.deadlineAt, now)}`
    : "—";

  return (
    <div className="enter-fade" style={{ width: "100%" }}>
      <div className="card" style={{ padding: 16 }}>
        <div style={{ display: "flex", alignItems: "center" }}>
          {/* 便签色条：与表格行首那条是同一个色号，指认的就是同一条便签 */}
          <span aria-hidden style={{ width: 4, height: 30, borderRadius: 2, background: color }} />
          <button
            className="icon-btn"
            style={{ marginLeft: "auto", width: 24, height: 24 }}
            title="取消选中"
            onClick={onClose}
          >
            <IconClose size={12} />
          </button>
        </div>

        <div style={{ fontSize: 16, fontWeight: 600, marginTop: 10, color: "var(--on-surface)" }}>
          {note.title || "（无标题）"}
        </div>
        <div style={{ fontSize: 12.5, marginTop: 4, color: "var(--on-surface-variant)" }}>
          {typeLabel(note) || "未分类"}
        </div>

        <dl style={{ margin: "12px 0 0" }}>
          <NoteDetailRow
            label="截止"
            value={deadline}
            danger={hasDeadline(note) && note.deadlineAt < now}
          />
          <NoteDetailRow label="创建" value={dateLabel(note.createdAt)} />
          <NoteDetailRow label="课程" value={cls?.title || "—"} />
          <NoteDetailRow label="提醒" value={rule?.title || "—"} />
        </dl>

        <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
          <button className="btn primary" onClick={onEdit}>
            <IconEdit size={13} /> 编辑
          </button>
          <button className="btn danger" onClick={onDelete}>
            <IconTrash size={13} /> 删除
          </button>
        </div>
      </div>

      {note.content.trim() ? (
        <div className="card" style={{ padding: 16 }}>
          <div className="detail-section-label">内容</div>
          <div
            style={{
              fontSize: 12.5,
              marginTop: 8,
              color: "var(--on-surface)",
              lineHeight: 1.65,
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
            }}
          >
            {note.content}
          </div>
        </div>
      ) : null}

      <div className="card" style={{ padding: 16 }}>
        <div className="detail-section-label">操作提示</div>
        <div style={{ fontSize: 12, marginTop: 6, color: "var(--on-surface-variant)", lineHeight: 1.6 }}>
          单击便签查看详情，双击直接编辑。
        </div>
      </div>
    </div>
  );
}

/** 详情里的一行「标签 : 值」（与课表页 `DetailRow` 同一套样式类，但那边没导出） */
function NoteDetailRow({
  label,
  value,
  danger,
}: {
  label: string;
  value: string;
  danger?: boolean;
}) {
  return (
    <div className="detail-row">
      <dt>{label}</dt>
      <dd style={danger ? { color: "var(--error)" } : undefined}>{value}</dd>
    </div>
  );
}

/** epoch ms → `2026/11/16`（详情里的「创建」用，不显示时分） */
function dateLabel(millis: number): string {
  if (!millis) return "—";
  const d = new Date(millis);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`;
}

/**
 * 右侧编辑面板（原版 `NoteEditorPanel`）。
 *
 * 三个约束是踩出来的，别改回去：
 * - 表单区**必须可滚**。选「Deadline」+「自定义」时字段从 4 组涨到 6 组，
 *   600dp 高的窗口装不下，溢出后保存按钮会被挤出可视区（点不到）。
 * - 所以按钮固定贴在底部，不能靠 `flex` 把 spacer 撑开 —— 内容一长 spacer 先归零。
 * - 宽度由父级 `.note-dock` 给（340），面板自己不画背景（原版也是裸列）。
 */
function NoteEditorPanel({
  entity,
  isNew,
  link,
  classes,
  rules,
  onSave,
  onDelete,
  onCancel,
}: {
  entity: NoteEntity;
  isNew: boolean;
  /** 这一条现在关联的课程 / 循环提醒（没有就是 0 / 空串） */
  link: NoteLink;
  classes: ClassEntity[];
  rules: RecurringItem[];
  onSave: (n: NoteEntity, link: NoteLink) => void;
  onDelete: (id: number) => void;
  /** 取消 = 退回详情（新建时 = 收起整个右列） */
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<NoteEntity>(entity);
  const [linkDraft, setLinkDraft] = useState<NoteLink>(link);
  const patch = (p: Partial<NoteEntity>) => setDraft((d) => ({ ...d, ...p }));

  const type = NOTE_TYPES[draft.typeIndex] ?? NOTE_TYPES[0];
  const isDeadline = type.kind === "deadline";
  // 编辑已有便签时永远可保存：标题留空会保留原标题，所以「清空标题」不是非法输入，
  // 用户可能只是想改个颜色。新建则必须给个标题。
  const canSave = draft.title.trim().length > 0 || !isNew;

  const submit = () => {
    const next: NoteEntity = {
      ...draft,
      title: draft.title.trim() || entity.title,
      // 「哪些类型才有标签 / 截止时间」只有面板知道，提交前统一归一化，
      // 否则切到别的分类再切回来会看到一个不显示的孤儿时间戳
      customLabel: type.editableLabel ? draft.customLabel : "",
      deadlineAt: isDeadline ? draft.deadlineAt : 0,
    };
    onSave(next, linkDraft);
  };

  return (
    <div
      className="enter-fade"
      style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", width: "100%" }}
    >
      <div style={{ display: "flex", alignItems: "center", paddingBottom: 14 }}>
        <div style={{ fontSize: 14, fontWeight: 600, color: "var(--on-surface)" }}>
          {isNew ? "新建便签" : "编辑便签"}
        </div>
        <span style={{ flex: 1 }} />
        {/* 表单里已经有「取消」，这里再给一个 X：详情栏是这么关的，编辑栏不该只有一种关法 */}
        <button
          className="icon-btn"
          style={{ width: 24, height: 24 }}
          title={isNew ? "放弃新建" : "返回详情"}
          onClick={onCancel}
        >
          <IconClose size={12} />
        </button>
      </div>

      <div className="note-editor-body" style={{ flex: 1, minHeight: 0, overflowY: "auto" }}>
        <PanelLabel>标题</PanelLabel>
        <input
          className="input"
          autoFocus={isNew}
          value={draft.title}
          onChange={(e) => patch({ title: e.target.value })}
          placeholder="一句话说清这条便签"
        />

        <PanelLabel top>内容</PanelLabel>
        <textarea
          className="input"
          style={{ minHeight: 72, resize: "vertical" }}
          value={draft.content}
          onChange={(e) => patch({ content: e.target.value })}
          placeholder="补充细节，可留空"
        />

        <PanelLabel top>颜色</PanelLabel>
        <div style={{ display: "flex", gap: 8 }}>
          {Array.from({ length: 8 }).map((_, i) => (
            <button
              key={i}
              onClick={() => patch({ colorIndex: i })}
              title={`色号 ${i}`}
              style={{
                width: 26,
                height: 26,
                borderRadius: 4,
                background: `var(--note-${i})`,
                outline: draft.colorIndex === i ? "2px solid var(--on-surface)" : "none",
                outlineOffset: 1,
              }}
            />
          ))}
        </div>

        <PanelLabel top>分类</PanelLabel>
        <Select<number>
          value={draft.typeIndex}
          options={NOTE_TYPES.map((t, i) => ({ value: i, label: t.label }))}
          onChange={(v) =>
            patch({
              typeIndex: v,
              // 从 Deadline 切走时清掉时间，否则会留下一个不显示的孤儿时间戳
              deadlineAt: NOTE_TYPES[v]?.kind === "deadline" ? draft.deadlineAt : 0,
            })
          }
        />

        {/* 分类相关的字段是条件挂载的：从「作业」切到「Deadline」会凭空多出
            两组输入，直接出现像页面被塞了一下。给整组一个 160ms 的淡入。 */}
        {type.editableLabel ? (
          <div className="enter-fade">
            <PanelLabel top>分类名</PanelLabel>
            <input
              className="input"
              value={draft.customLabel}
              onChange={(e) => patch({ customLabel: e.target.value })}
              placeholder={type.kind === "deadline" ? "例如「期末论文」" : "例如「科研」"}
            />
          </div>
        ) : null}

        {isDeadline ? (
          <div className="enter-fade">
            <PanelLabel top>截止时间</PanelLabel>
            <input
              className="input"
              type="datetime-local"
              value={toLocalInput(draft.deadlineAt)}
              onChange={(e) => {
                const v = e.target.value;
                patch({ deadlineAt: v ? new Date(v).getTime() : 0 });
              }}
            />
            {draft.deadlineAt ? (
              <div style={{ fontSize: 12, color: "var(--error)", marginTop: 8 }}>
                {deadlineTimeLabel(draft.deadlineAt, Date.now())} ·{" "}
                {deadlineCountdown(draft.deadlineAt, Date.now())}
              </div>
            ) : (
              <div style={{ fontSize: 11, color: "var(--on-surface-faint)", marginTop: 8 }}>
                留空也能存，只是不显示倒计时
              </div>
            )}
          </div>
        ) : null}

        {draft.typeIndex === NOTE_TYPE_DEADLINE && !draft.deadlineAt ? (
          <div style={{ fontSize: 11, color: "var(--warning)", marginTop: 8 }}>
            选了 Deadline 但没填时间
          </div>
        ) : null}

        {/* 关联：一门课、一条循环提醒各最多一个。
            关联本身存 Lite 专属的 note_links.json，不改共用数据库的 notes 表。 */}
        <PanelLabel top>关联课程</PanelLabel>
        <Select<number>
          value={linkDraft.classId}
          options={[
            { value: 0, label: "不关联" },
            ...classes.map((c) => ({ value: c.id, label: c.title })),
          ]}
          onChange={(classId) => setLinkDraft((l) => ({ ...l, classId }))}
        />

        <PanelLabel top>关联循环提醒</PanelLabel>
        <Select<string>
          value={linkDraft.ruleId}
          options={[
            { value: "", label: "不关联" },
            ...rules.map((r) => ({ value: r.id, label: r.title })),
          ]}
          onChange={(ruleId) => setLinkDraft((l) => ({ ...l, ruleId }))}
        />
        <div style={{ fontSize: 11, color: "var(--on-surface-faint)", marginTop: 8 }}>
          关联后，对应课程 / 提醒的右侧详情里会列出这条便签
        </div>
      </div>

      <div className="divider" style={{ margin: "12px 0" }} />
      <div style={{ display: "flex", alignItems: "center", gap: 8, paddingBottom: 4 }}>
        {!isNew ? (
          <button className="btn danger" onClick={() => onDelete(entity.id)}>
            删除
          </button>
        ) : null}
        <span style={{ flex: 1 }} />
        <button className="btn ghost" onClick={onCancel}>
          取消
        </button>
        <button className="btn primary" onClick={submit} disabled={!canSave}>
          保存
        </button>
      </div>
    </div>
  );
}

function PanelLabel({ children, top }: { children: React.ReactNode; top?: boolean }) {
  return (
    <div
      style={{
        fontSize: 11.5,
        fontWeight: 600,
        color: "var(--on-surface-faint)",
        marginTop: top ? 14 : 0,
        marginBottom: 6,
      }}
    >
      {children}
    </div>
  );
}

/** epoch ms → `<input type="datetime-local">` 需要的本地时间串 */
function toLocalInput(millis: number): string {
  if (!millis) return "";
  const d = new Date(millis);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}
