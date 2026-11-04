/**
 * 便签页：左侧表格 + 右侧编辑面板（与原版 `NotesPage.kt` 同一套版式）。
 *
 * 这一页在 v1.7.11 之前是「表格 + 弹窗」：点一行弹一个居中对话框。原版不是这样 ——
 * 原版点行只是**选中**，编辑发生在右列那块 340dp 的面板里，表格本身一直是可见的。
 * 差别不只是好看：弹窗盖住表格后没法对照上下条，也看不到「刚才那行现在什么状态」。
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
import { IconEdit, IconNote, IconPlus, IconSearch, IconTrash } from "../components/Icons";
import type { NoteEntity } from "../model/types";
import { EMPTY_NOTE } from "../model/types";
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

export function NotesPage({ ctx }: { ctx: AppContextValue }) {
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<number | null>(null);
  const [sort, setSort] = useState<SortMode>("manual");
  /** 右列面板当前编辑哪一条；`null` 且 `creating` 为假时面板收起 */
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [now, setNow] = useState(Date.now());

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

  // 选中的那条被删掉 / 被筛掉时，面板跟着收起。
  // ⚠️ 区分「被筛掉」和「库里还没有」：首帧 notes 是空数组，那时不能判成删除。
  useEffect(() => {
    if (selectedId === null) return;
    if (ctx.notes.length === 0) return;
    if (!visible.some((n) => n.id === selectedId)) setSelectedId(null);
  }, [visible, selectedId, ctx.notes.length]);

  const select = (note: NoteEntity) => {
    setCreating(false);
    setSelectedId(note.id);
  };

  const startCreate = () => {
    setSelectedId(null);
    setCreating(true);
  };

  const closePanel = () => {
    setCreating(false);
    setSelectedId(null);
  };

  const save = async (entity: NoteEntity) => {
    try {
      await api.saveNote(entity);
      await ctx.reloadNotes();
      closePanel();
      ctx.toast("已保存");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  const remove = async (id: number) => {
    try {
      await api.deleteNote(id);
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
  const panelOpen = creating || selected !== null;

  /**
   * 面板的「退场保留」。
   *
   * 关闭时 `creating` / `selectedId` **同一帧**就置空了，但面板还要再画 120ms。
   * 直接读当前值会让它先塌成空白再淡出（一帧白框），所以用 ref 记住最后一份
   * 可渲染的内容与身份（key / isNew）。只在「当前值为空」时才回退到记忆值 ——
   * 否则切到另一条便签时会先闪一帧上一条。
   *
   * 与 Compose 端 `RightPanelTransition` 的 `retained` 是同一个思路。
   */
  const panelEntity = creating ? { ...EMPTY_NOTE } : selected;
  const retainedEntity = useRef<NoteEntity | null>(null);
  const retainedKey = useRef("creating");
  const retainedIsNew = useRef(true);
  if (panelEntity) {
    retainedEntity.current = panelEntity;
    retainedKey.current = creating ? "creating" : `edit-${panelEntity.id}`;
    retainedIsNew.current = creating;
  }
  const panelPresence = usePresence(panelOpen);
  const shownEntity = panelEntity ?? retainedEntity.current;

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

      <div
        className="page-content"
        style={{ overflow: "hidden", display: "flex", gap: 16 }}
      >
        <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", minHeight: 0 }}>
          {/* ── 工具行 ──
              原版把它放在卡片**外面**、表格上方，并用 Spacer 把排序按钮推到最右。
              v1.7.11 之前这几件控件是塞在卡片顶部的，读起来像「表头的一部分」。 */}
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

          {/* ── 表格卡片 ── */}
          <div
            className="card"
            style={{
              flex: 1,
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
              onEdit={select}
              onDelete={remove}
              onReorder={commitOrder}
            />
          </div>
        </div>

        {/* ── 右侧编辑面板 ──
            和原版一样宽 340、与表格间隔 16。收起时**整块不渲染**（不是 display:none）：
            这样面板里的输入状态不会在后台偷偷留着，下次选中新条目时不会看到上一条的残留。 */}
        {panelPresence.mounted && shownEntity ? (
          <NoteEditorPanel
            key={retainedKey.current}
            leaving={panelPresence.leaving}
            entity={shownEntity}
            isNew={panelEntity ? creating : retainedIsNew.current}
            onSave={save}
            onDelete={remove}
            onCancel={closePanel}
          />
        ) : null}
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
 * 右侧编辑面板（原版 `NoteEditorPanel`）。
 *
 * 三个约束是踩出来的，别改回去：
 * - 表单区**必须可滚**。选「Deadline」+「自定义」时字段从 4 组涨到 6 组，
 *   600dp 高的窗口装不下，溢出后保存按钮会被挤出可视区（点不到）。
 * - 所以按钮固定贴在底部，不能靠 `flex` 把 spacer 撑开 —— 内容一长 spacer 先归零。
 * - 宽度固定 340，与表格之间靠父级 16px gap 分隔，面板自己不画背景（原版也是裸列）。
 */
function NoteEditorPanel({
  entity,
  isNew,
  leaving,
  onSave,
  onDelete,
  onCancel,
}: {
  entity: NoteEntity;
  isNew: boolean;
  leaving?: boolean;
  onSave: (n: NoteEntity) => void;
  onDelete: (id: number) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<NoteEntity>(entity);
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
    onSave(next);
  };

  return (
    <div className={`note-panel${leaving ? " leaving" : ""}`}>
      <div style={{ fontSize: 14, fontWeight: 600, color: "var(--on-surface)", paddingBottom: 14 }}>
        {isNew ? "新建便签" : "编辑便签"}
      </div>

      <div style={{ flex: 1, minHeight: 0, overflowY: "auto" }} className="note-panel-body">
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
