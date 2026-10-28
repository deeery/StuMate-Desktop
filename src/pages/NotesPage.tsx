/**
 * 便签页：搜索 / 分类筛选 / 拖动排序 / 编辑。
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
import { Dialog, Field, Select } from "../components/ui";
import { IconPlus, IconSearch, IconTrash } from "../components/Icons";
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

export function NotesPage({ ctx }: { ctx: AppContextValue }) {
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<number | null>(null);
  const [sort, setSort] = useState<SortMode>("manual");
  const [editing, setEditing] = useState<NoteEntity | null>(null);
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

  const save = async (entity: NoteEntity) => {
    try {
      await api.saveNote(entity);
      await ctx.reloadNotes();
      setEditing(null);
      ctx.toast("已保存");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  const remove = async (id: number) => {
    try {
      await api.deleteNote(id);
      await ctx.reloadNotes();
      setEditing(null);
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
        {/* 顶栏只留「新建便签」一个动作（原版顶栏是「撤销 + 新建便签」）。
            搜索框与分类筛选不属于页面级动作，它们只作用于下面这张表，
            所以放进卡片顶部那一行 —— 放在顶栏里会让人以为它们过滤的是整个应用。 */}
        <button className="btn primary" onClick={() => setEditing({ ...EMPTY_NOTE })}>
          <IconPlus size={14} /> 新建便签
        </button>
      </PageTopBar>

      <div className="page-content" style={{ overflow: "hidden" }}>
        <div
          className="card"
          style={{
            display: "flex",
            flexDirection: "column",
            height: "100%",
            overflow: "hidden",
          }}
        >
          <div className="notes-filters">
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
                style={{ width: 200, paddingLeft: 28 }}
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
            {/* 排序在原版里是右对齐的一行文字按钮（不是下拉）：
                「按截止时间排序」。手动排序是默认态，所以按钮文字表示「点下去做什么」。 */}
            <button
              className="notes-sort"
              onClick={() => setSort((s) => (s === "deadline" ? "manual" : "deadline"))}
            >
              {sort === "deadline" ? "按手动顺序" : "按截止时间排序"}
            </button>
          </div>
          <NoteTableHeader />
          <NoteList
            notes={visible}
            all={ctx.notes}
            now={now}
            canDrag={canDrag}
            onEdit={setEditing}
            onReorder={commitOrder}
          />
        </div>
      </div>

      {editing ? (
        <NoteDialog
          entity={editing}
          isNew={editing.id === 0}
          onClose={() => setEditing(null)}
          onSave={save}
          onDelete={remove}
        />
      ) : null}
    </>
  );
}

/**
 * 便签表头的列宽。**必须与行里的格子共用同一组常量** ——
 * 分开写死两份数值，改一边就会悄悄错位，而这种错位在截图里只表现为
 * 「表头文字没对齐」，很容易被当成字体问题查半天。
 */
const COL_GRIP = 26;
const COL_COLOR = 20;
const COL_TYPE = 96;
const COL_DEADLINE = 168;

/** 「分类」下拉里代表「全部」的取值。用字符串是为了和 `typeFilter === null` 区分开 */
const ALL_TYPES = "__all__";

/** 表头：标题 / 内容 ｜ 分类 ｜ 截止（与原版 `NoteTableHeader` 同列宽、同字号） */
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
      }}
    >
      <span style={{ width: COL_GRIP, flex: `0 0 ${COL_GRIP}px` }} />
      <span style={{ width: COL_COLOR, flex: `0 0 ${COL_COLOR}px` }} />
      <span style={{ flex: 1, minWidth: 0, ...cell }}>标题 / 内容</span>
      <span style={{ width: COL_TYPE, flex: `0 0 ${COL_TYPE}px`, ...cell }}>分类</span>
      <span style={{ width: COL_DEADLINE, flex: `0 0 ${COL_DEADLINE}px`, ...cell }}>截止</span>
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
  onEdit,
  onReorder,
}: {
  notes: NoteEntity[];
  all: NoteEntity[];
  now: number;
  canDrag: boolean;
  onEdit: (n: NoteEntity) => void;
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

  return (
    <div
      ref={scrollRef}
      className="card"
      style={{ flex: 1, overflow: "auto", position: "relative" }}
    >
      {notes.length === 0 ? (
        <div className="empty">
          {all.length === 0 ? "还没有便签，点右上角新建一条" : "没有匹配的便签"}
        </div>
      ) : null}

      {notes.map((note, index) => {
        const active = drag?.id === note.id;
        const target = drag ? resolveDropIndex(drag.tops, drag.startIndex, drag.offset) : -1;
        const shift = drag ? shiftFor(index, drag.startIndex, target, drag.heights[drag.startIndex] ?? 40) : 0;
        const translate = active ? drag?.offset ?? 0 : shift;
        const color = `var(--note-${((note.colorIndex % 8) + 8) % 8})`;
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
              background: active ? "var(--surface3)" : undefined,
              boxShadow: active ? "var(--shadow-flyout)" : undefined,
              borderRadius: active ? 4 : 0,
              opacity: active ? 0.96 : 1,
            }}
          >
            <div
              style={{
                display: "flex",
                alignItems: "stretch",
                gap: 0,
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
              <button
                onClick={() => onEdit(note)}
                style={{
                  flex: 1,
                  minWidth: 0,
                  textAlign: "left",
                  padding: "6px 8px 6px 0",
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                }}
              >
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span
                    style={{
                      display: "block",
                      fontSize: 13,
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {note.title}
                  </span>
                  {note.content ? (
                    <span
                      style={{
                        display: "block",
                        fontSize: 11,
                        color: "var(--on-surface-faint)",
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                        marginTop: 1,
                      }}
                    >
                      {note.content.replace(/\s+/g, " ").slice(0, 90)}
                    </span>
                  ) : null}
                </span>
              </button>
              <div
                style={{
                  width: COL_TYPE,
                  flex: `0 0 ${COL_TYPE}px`,
                  display: "flex",
                  alignItems: "center",
                }}
              >
                {/* 有截止时间的分类标签用 error 填充（原版 `FlChip(label, color = c.error, filled = true)`）：
                    红底是为了让「这条有期限」在扫视时先跳出来，与截止时间列的红字呼应。 */}
                {typeLabel(note) ? (
                  <span
                    className="chip"
                    style={
                      hasDeadline(note)
                        ? { background: "var(--error)", color: "var(--on-accent)", fontWeight: 600 }
                        : undefined
                    }
                  >
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
                  fontSize: 11,
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  color: hasDeadline(note) && note.deadlineAt < now
                    ? "var(--error)"
                    : "var(--on-surface-variant)",
                }}
              >
                {hasDeadline(note)
                  ? `${deadlineTimeLabel(note.deadlineAt, now)} · ${deadlineCountdown(note.deadlineAt, now)}`
                  : ""}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function NoteDialog({
  entity,
  isNew,
  onClose,
  onSave,
  onDelete,
}: {
  entity: NoteEntity;
  isNew: boolean;
  onClose: () => void;
  onSave: (n: NoteEntity) => void;
  onDelete: (id: number) => void;
}) {
  const [draft, setDraft] = useState<NoteEntity>(entity);
  const [error, setError] = useState("");
  const patch = (p: Partial<NoteEntity>) => setDraft((d) => ({ ...d, ...p }));

  const type = NOTE_TYPES[draft.typeIndex] ?? NOTE_TYPES[0];

  const submit = () => {
    if (!draft.title.trim()) {
      setError("标题不能为空");
      return;
    }
    onSave({ ...draft, title: draft.title.trim() });
  };

  return (
    <Dialog
      title={isNew ? "新建便签" : "编辑便签"}
      onClose={onClose}
      width={520}
      actions={
        <>
          {!isNew ? (
            <button className="btn danger" style={{ marginRight: "auto" }} onClick={() => onDelete(entity.id)}>
              <IconTrash size={13} /> 删除
            </button>
          ) : null}
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" onClick={submit}>
            保存
          </button>
        </>
      }
    >
      <Field label="标题">
        <input
          className="input"
          autoFocus
          value={draft.title}
          onChange={(e) => patch({ title: e.target.value })}
          placeholder="一句话说清这条便签是什么"
        />
      </Field>
      <Field label="正文">
        <textarea
          className="input"
          rows={6}
          value={draft.content}
          onChange={(e) => patch({ content: e.target.value })}
          placeholder="可以留空"
        />
      </Field>

      <Field label="颜色">
        <div style={{ display: "flex", gap: 8 }}>
          {Array.from({ length: 8 }).map((_, i) => (
            <button
              key={i}
              onClick={() => patch({ colorIndex: i })}
              title={`色号 ${i}`}
              style={{
                width: 22,
                height: 22,
                borderRadius: 11,
                background: `var(--note-${i})`,
                outline: draft.colorIndex === i ? "2px solid var(--on-surface)" : "none",
                outlineOffset: 2,
              }}
            />
          ))}
        </div>
      </Field>

      <div className="field-row">
        <div style={{ flex: 1 }}>
          <Field label="分类">
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
          </Field>
        </div>
        {type.editableLabel ? (
          <div style={{ flex: 1 }}>
            <Field label="分类名">
              <input
                className="input"
                value={draft.customLabel}
                onChange={(e) => patch({ customLabel: e.target.value })}
                placeholder={type.label}
              />
            </Field>
          </div>
        ) : null}
      </div>

      {type.kind === "deadline" ? (
        <Field label="截止时间" hint="留空也能存，只是不显示倒计时">
          <input
            className="input"
            type="datetime-local"
            value={toLocalInput(draft.deadlineAt)}
            onChange={(e) => {
              const v = e.target.value;
              patch({ deadlineAt: v ? new Date(v).getTime() : 0 });
            }}
          />
        </Field>
      ) : null}

      {draft.typeIndex === NOTE_TYPE_DEADLINE && !draft.deadlineAt ? (
        <div style={{ fontSize: 11, color: "var(--warning)" }}>选了 Deadline 但没填时间</div>
      ) : null}
      {error ? <div style={{ color: "var(--error)", fontSize: 12 }}>{error}</div> : null}
    </Dialog>
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
