/**
 * 课表页：时间轴网格 + 列表两种视图。
 *
 * 位置计算全部来自 `model/timeAxis.ts`（比例，不是像素），这里只把比例乘上容器高度。
 * 这样窗口缩放、切换每格小时数都不会让课程块和网格线错位。
 *
 * 周次过滤口径：
 *  - 长期课程：`weeks` 文本包含当前周（写不清时一律显示，不因为备注写得怪就整门隐藏）
 *  - 临时提醒（`date` 非空）：只在那一天所在的那一周出现
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { PageTopBar, type AppContextValue } from "../App";
import { Dialog, Field, Select } from "../components/ui";
import {
  IconChevronLeft,
  IconChevronRight,
  IconPlus,
  IconTrash,
} from "../components/Icons";
import type { ClassEntity } from "../model/types";
import { EMPTY_CLASS } from "../model/types";
import {
  hoursPerRow,
  labelOf,
  layout,
  mappingOf,
  minutesOf,
  roundMarks,
  rowCount,
  spanOf,
  zebraBands,
  type Focus,
  type Span,
} from "../model/timeAxis";
import { currentWeek, mondayOf, weekContains, weekStart } from "../model/weekSchedule";
import { DAY_NAMES, dateOf } from "../model/today";
import { api, errorText } from "../api";

const DAY_CN = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

const DAY_OPTIONS = DAY_NAMES.map((d, i) => ({ value: d, label: DAY_CN[i] }));

/** 毫秒 → `<input type="date">` 要的 `YYYY-MM-DD`（本地时区，不走 UTC，否则会差一天）。 */
function toDateInput(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function WeekPage({ ctx }: { ctx: AppContextValue }) {
  const week1Monday = ctx.prefs.week1Monday;
  const nowWeek = currentWeek(week1Monday);
  const [week, setWeek] = useState<number>(() => nowWeek ?? 1);
  const [editing, setEditing] = useState<ClassEntity | null>(null);
  const [focus, setFocus] = useState<Focus | null>(null);
  const [gridRef, setGridRef] = useState<HTMLDivElement | null>(null);
  const [gridHeight, setGridHeight] = useState(640);
  // 「校准周数」对话框：原版把这个入口放在页面顶栏，不再塞一条常驻提示条
  const [calOpen, setCalOpen] = useState(false);
  const [calDate, setCalDate] = useState("");

  // 当前周的周一 00:00（未校准时以"本周"为第 1 周）
  const weekMonday = week1Monday ? weekStart(week1Monday, week) : mondayOf(Date.now());

  // 顶栏副标题与工具条右侧都用「周一 ~ 周日」的日期区间（原版口径，零填充 mm/dd）
  const weekRange = useMemo(() => {
    const fmt = (ms: number) => {
      const d = new Date(ms);
      return `${String(d.getMonth() + 1).padStart(2, "0")}/${String(d.getDate()).padStart(2, "0")}`;
    };
    return `${fmt(weekMonday)} ~ ${fmt(weekMonday + 6 * 24 * 60 * 60 * 1000)}`;
  }, [weekMonday]);

  const weekClasses = useMemo(() => {
    const from = weekMonday;
    const to = weekMonday + 7 * 24 * 60 * 60 * 1000;
    return ctx.classes.filter((c) => {
      if (c.date) {
        const t = new Date(`${c.date}T00:00:00`).getTime();
        return Number.isFinite(t) && t >= from && t < to;
      }
      return weekContains(c.weeks, week);
    });
  }, [ctx.classes, week, weekMonday]);

  const span = useMemo(() => spanOf(weekClasses) ?? { startMinute: 8 * 60, endMinute: 18 * 60 }, [weekClasses]);

  // 容器高度 → 每格几小时。留 1 行余量，免得刚好卡在边界上出现半格滚动条
  const rows = Math.max(Math.floor((gridHeight - 24) / 56), 4);
  const perRow = hoursPerRow(span, rows);
  const totalRows = rowCount(span, perRow);
  const mapping = useMemo(() => mappingOf(span, focus), [span, focus]);
  const marks = useMemo(() => roundMarks(span, perRow * 60), [span, perRow]);
  const bands = useMemo(() => zebraBands(span), [span]);

  useEffect(() => {
    if (!gridRef) return;
    const observer = new ResizeObserver((entries) => {
      const h = entries[0]?.contentRect.height ?? 0;
      if (h > 0) setGridHeight(h);
    });
    observer.observe(gridRef);
    return () => observer.disconnect();
  }, [gridRef]);

  const save = async (entity: ClassEntity) => {
    try {
      await api.saveClass(entity);
      await ctx.reloadClasses();
      setEditing(null);
      ctx.toast("已保存");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  const remove = async (entity: ClassEntity) => {
    try {
      await api.deleteClass(entity.id);
      await ctx.reloadClasses();
      setEditing(null);
      ctx.toast("已删除");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  return (
    <>
      <PageTopBar title="课表" subtitle={weekRange}>
        <button
          className="btn"
          onClick={() => {
            setCalDate(toDateInput(week1Monday || mondayOf(Date.now())));
            setCalOpen(true);
          }}
        >
          校准周数
        </button>
        <button className="btn primary" onClick={() => setEditing({ ...EMPTY_CLASS })}>
          <IconPlus size={14} /> 添加课程
        </button>
      </PageTopBar>

      <div className="page-content" style={{ display: "flex", flexDirection: "column" }}>
        {/* 工具条：周次步进 + 日期区间 + 视图切换，单独一行。
            原版就是这么分的 —— 顶栏只放「校准周数 / 添加课程」两个页面级动作，
            周次与视图切换留在内容区顶部。之前把步进器和视图切换塞进顶栏的副作用：
            右侧动作区被窗口按钮一挤，"新建课程"外面还挂着三个图标按钮，
            看起来像一排工具栏残渣，且和原版「表格/列表」分段控件对不上。 */}
        <div className="week-toolbar">
          <button
            className="step-btn"
            title="上一周"
            onClick={() => setWeek((w) => Math.max(1, w - 1))}
          >
            <IconChevronLeft size={15} />
          </button>
          <span className="week-label">第 {week} 周</span>
          <button className="step-btn" title="下一周" onClick={() => setWeek((w) => w + 1)}>
            <IconChevronRight size={15} />
          </button>
          <span className="week-range">{weekRange}</span>
          <div className="segmented" style={{ marginLeft: "auto" }}>
            <button
              className={ctx.prefs.weekGrid ? "on" : ""}
              onClick={() => void ctx.setPref("weekGrid", true)}
            >
              表格
            </button>
            <button
              className={!ctx.prefs.weekGrid ? "on" : ""}
              onClick={() => void ctx.setPref("weekGrid", false)}
            >
              列表
            </button>
          </div>
        </div>

        {!week1Monday ? (
          <div className="week-hint">
            还没有校准第 1 周的周一，课表暂时不按周次过滤。
            <button
              className="btn ghost"
              onClick={() => void ctx.setPref("week1Monday", mondayOf(Date.now()))}
            >
              以本周为第 1 周
            </button>
          </div>
        ) : null}

        {ctx.prefs.weekGrid ? (
          <div
            className="card"
            style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", overflow: "hidden" }}
          >
            {/* 列头：原版 44dp 高，星期 12sp（今日加粗）+「只显示日」的日期 10.5sp，
                今日列铺一层 accentTint 并在底部放一条 42% 宽的胶囊指示条。
                日期只取日不取月 —— 月份在上面那行区间标签里已经有了，
                「10/26」六七个字符挤进窄列会把星期名顶掉。 */}
            <div style={{ display: "flex", borderBottom: "1px solid var(--outline)", flex: "0 0 44px" }}>
              <div style={{ width: 56, flex: "0 0 56px" }} />
              {DAY_CN.map((label, i) => {
                const date = new Date(weekMonday + i * 24 * 60 * 60 * 1000);
                const isToday = dateOf(date.getTime()) === dateOf(Date.now());
                return (
                  <div
                    key={label}
                    style={{
                      flex: 1,
                      position: "relative",
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      gap: 5,
                      background: isToday ? "var(--accent-tint)" : "transparent",
                      borderLeft: "1px solid var(--outline)",
                    }}
                  >
                    <span
                      style={{
                        fontSize: 12,
                        fontWeight: isToday ? 700 : 600,
                        color: isToday ? "var(--accent)" : "var(--on-surface-variant)",
                      }}
                    >
                      {label}
                    </span>
                    <span
                      style={{
                        fontSize: 10.5,
                        fontWeight: isToday ? 600 : 400,
                        color: isToday ? "var(--accent)" : "var(--on-surface-faint)",
                        paddingBottom: 1,
                      }}
                    >
                      {String(date.getDate())}
                    </span>
                    {isToday ? (
                      <span
                        style={{
                          position: "absolute",
                          bottom: 4,
                          left: "29%",
                          width: "42%",
                          height: 2.5,
                          borderRadius: 2,
                          background: "var(--accent)",
                        }}
                      />
                    ) : null}
                  </div>
                );
              })}
            </div>

            <div
              ref={setGridRef}
              style={{ flex: 1, minHeight: 0, display: "flex", position: "relative", overflow: "hidden" }}
              onClick={(e) => {
                // 点空白处收起展开态
                if (e.target === e.currentTarget) setFocus(null);
              }}
            >
              {/* 纵轴刻度 */}
              <div style={{ width: 56, flex: "0 0 56px", position: "relative" }}>
                {marks.map((m) => (
                  <div
                    key={m}
                    style={{
                      position: "absolute",
                      top: mapping.fractionOf(m) * gridHeight,
                      right: 8,
                      transform: "translateY(-50%)",
                      fontSize: 10,
                      fontFamily: "var(--font-mono)",
                      color: "var(--on-surface-faint)",
                    }}
                  >
                    {labelOf(m)}
                  </div>
                ))}
              </div>

              {DAY_NAMES.map((day, dayIndex) => {
                const dayClasses = weekClasses.filter((c) => c.dayOfWeek === day);
                const placed = layout(dayClasses, mapping);
                const isToday =
                  dateOf(weekMonday + dayIndex * 24 * 60 * 60 * 1000) === dateOf(Date.now());
                return (
                  <div
                    key={day}
                    style={{
                      flex: 1,
                      position: "relative",
                      borderLeft: "1px solid var(--outline)",
                      background: isToday ? "var(--accent-hover)" : undefined,
                    }}
                  >
                    {/* 斑马纹：每小时交替的极淡色带，扫行时给眼睛一个参照物 */}
                    {bands.map((b) => (
                      <div
                        key={b.startMinute}
                        style={{
                          position: "absolute",
                          left: 0,
                          right: 0,
                          top: mapping.fractionOf(b.startMinute) * gridHeight,
                          height: (mapping.fractionOf(b.endMinute) - mapping.fractionOf(b.startMinute)) * gridHeight,
                          background: "var(--grid-zebra)",
                          pointerEvents: "none",
                        }}
                      />
                    ))}
                    {marks.map((m) => (
                      <div
                        key={m}
                        style={{
                          position: "absolute",
                          left: 0,
                          right: 0,
                          top: mapping.fractionOf(m) * gridHeight,
                          borderTop: "1px solid var(--grid-line)",
                          pointerEvents: "none",
                        }}
                      />
                    ))}

                    {placed.map((p) => {
                      const height = Math.max(p.heightFraction * gridHeight, 22);
                      const active = focus?.startMinute === minutesOf(p.cls.startTime);
                      // 按块高分级显示，与原版 CourseBlock 的四档阈值一致：
                      //   < 30 只课名 / ≥ 30 课名+地点 / ≥ 58 课名可两行 / ≥ 76 再加块底时间区间。
                      // 不这么分的话，矮块里硬塞三行会互相压住，长课又显得内容很少。
                      const tall = height >= 58;
                      const showRoom = height >= 30 && Boolean(p.cls.room);
                      const showTime = height >= 76 && Boolean(p.cls.startTime);
                      // 临时提醒（有具体日期）用警示色，常规课用强调色 —— 与原版 barColor 同一规则
                      const barColor = p.cls.date ? "var(--warning)" : "var(--accent)";
                      return (
                        <button
                          key={p.cls.id}
                          onClick={(e) => {
                            e.stopPropagation();
                            const start = minutesOf(p.cls.startTime);
                            setFocus(start === null ? null : { startMinute: start, endMinute: start + 1 });
                            setEditing(p.cls);
                          }}
                          onContextMenu={(e) => {
                            e.preventDefault();
                            setEditing(p.cls);
                          }}
                          style={{
                            position: "absolute",
                            top: p.topFraction * gridHeight + 1,
                            height: height - 2,
                            left: `calc(${p.leftFraction * 100}% + 3px)`,
                            width: `calc(${p.widthFraction * 100}% - 6px)`,
                            background: "var(--surface3)",
                            border: `1px solid ${active ? "var(--accent)" : "var(--border-rest)"}`,
                            borderRadius: 4,
                            padding: "3px 6px 3px 9px",
                            textAlign: "left",
                            overflow: "hidden",
                            display: "flex",
                            flexDirection: "column",
                            gap: 0,
                            transition:
                              "top 180ms cubic-bezier(0.33,0,0,1), height 180ms cubic-bezier(0.33,0,0,1)",
                          }}
                        >
                          {/* 左侧色条：跟随块高（上下各留 4px），不是固定长度的小短杠。
                              它是「这一节课的身份标识」，扫一列时靠它把同一节连起来 */}
                          <span
                            aria-hidden
                            style={{
                              position: "absolute",
                              left: 0,
                              top: 4,
                              bottom: 4,
                              width: 3,
                              borderRadius: 1.5,
                              background: barColor,
                            }}
                          />
                          <span
                            style={{
                              fontSize: tall ? 12 : 11.5,
                              fontWeight: 600,
                              lineHeight: tall ? "15px" : "13px",
                              overflow: "hidden",
                              textOverflow: "ellipsis",
                              display: "-webkit-box",
                              WebkitLineClamp: height >= 60 ? 2 : 1,
                              WebkitBoxOrient: "vertical",
                            }}
                          >
                            {p.cls.title}
                          </span>
                          {showRoom ? (
                            <span
                              style={{
                                marginTop: tall ? 2 : 1,
                                fontSize: 10,
                                lineHeight: "13px",
                                color: "var(--on-surface-variant)",
                                overflow: "hidden",
                                textOverflow: "ellipsis",
                                whiteSpace: "nowrap",
                              }}
                            >
                              {p.cls.room}
                            </span>
                          ) : null}
                          {showTime ? (
                            <span
                              style={{
                                marginTop: "auto",
                                fontSize: 9.5,
                                lineHeight: "12px",
                                color: "var(--on-surface-faint)",
                                overflow: "hidden",
                                textOverflow: "ellipsis",
                                whiteSpace: "nowrap",
                              }}
                            >
                              {p.cls.startTime}–{p.cls.endTime}
                            </span>
                          ) : null}
                        </button>
                      );
                    })}
                  </div>
                );
              })}
            </div>
          </div>
        ) : (
          <ListView weekClasses={weekClasses} onEdit={setEditing} />
        )}
      </div>

      {editing ? (
        <ClassDialog
          entity={editing}
          isNew={editing.id === 0}
          onClose={() => setEditing(null)}
          onSave={save}
          onDelete={remove}
        />
      ) : null}

      {calOpen ? (
        <Dialog
          title="校准周数"
          subtitle="指定第 1 周的周一，课表按它推算今天是第几周"
          onClose={() => setCalOpen(false)}
          actions={
            <>
              <button className="btn" onClick={() => setCalOpen(false)}>
                取消
              </button>
              <button
                className="btn primary"
                disabled={!calDate}
                onClick={() => {
                  const millis = new Date(`${calDate}T00:00:00`).getTime();
                  if (!Number.isFinite(millis)) return;
                  const monday = mondayOf(millis);
                  void ctx.setPref("week1Monday", monday);
                  // 校准时通常是想回到「本周」，所以顺手把视图跳到当前周 ——
                  // 否则校准完停在原来那一周，看不出校准生效了。
                  setWeek(currentWeek(monday) ?? 1);
                  setCalOpen(false);
                  ctx.toast("已校准周数");
                }}
              >
                保存
              </button>
            </>
          }
        >
          <Field label="第 1 周的周一" hint="不校准时课表不按周次过滤（所有周都显示）">
            <input
              className="input"
              type="date"
              value={calDate}
              onChange={(e) => setCalDate(e.target.value)}
            />
          </Field>
        </Dialog>
      ) : null}
    </>
  );
}

function ListView({
  weekClasses,
  onEdit,
}: {
  weekClasses: ClassEntity[];
  onEdit: (c: ClassEntity) => void;
}) {
  return (
    <div className="card" style={{ padding: "6px 16px 12px" }}>
      {DAY_NAMES.map((day, i) => {
        const list = weekClasses
          .filter((c) => c.dayOfWeek === day)
          .sort((a, b) => (minutesOf(a.startTime) ?? 0) - (minutesOf(b.startTime) ?? 0));
        if (list.length === 0) return null;
        return (
          <div key={day} style={{ marginTop: 12 }}>
            <div style={{ fontSize: 11, color: "var(--on-surface-faint)", marginBottom: 4 }}>
              {DAY_CN[i]}
            </div>
            {list.map((c) => (
              <button
                key={c.id}
                onClick={() => onEdit(c)}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 12,
                  width: "100%",
                  height: 38,
                  borderBottom: "1px solid var(--outline)",
                  textAlign: "left",
                }}
              >
                <span style={{ fontFamily: "var(--font-mono)", fontSize: 11, width: 84, flex: "0 0 84px", color: "var(--on-surface-variant)" }}>
                  {c.startTime}–{c.endTime}
                </span>
                <span style={{ flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {c.title}
                </span>
                <span style={{ fontSize: 11, color: "var(--on-surface-faint)" }}>
                  {[c.room, c.teacher].filter(Boolean).join(" · ")}
                </span>
              </button>
            ))}
          </div>
        );
      })}
      {weekClasses.length === 0 ? <div className="empty">这一周没有课</div> : null}
    </div>
  );
}

function ClassDialog({
  entity,
  isNew,
  onClose,
  onSave,
  onDelete,
}: {
  entity: ClassEntity;
  isNew: boolean;
  onClose: () => void;
  onSave: (e: ClassEntity) => void;
  onDelete: (e: ClassEntity) => void;
}) {
  const [draft, setDraft] = useState<ClassEntity>(entity);
  const [error, setError] = useState("");

  const patch = (p: Partial<ClassEntity>) => setDraft((d) => ({ ...d, ...p }));

  const submit = () => {
    if (!draft.title.trim()) {
      setError("课名不能为空");
      return;
    }
    if (minutesOf(draft.startTime) === null || minutesOf(draft.endTime) === null) {
      setError("起止时间要写成 HH:mm");
      return;
    }
    onSave({ ...draft, title: draft.title.trim() });
  };

  return (
    <Dialog
      title={isNew ? "新建课程" : "编辑课程"}
      subtitle={isNew ? "只填课名和时间就能存" : `#${entity.id}`}
      onClose={onClose}
      actions={
        <>
          {!isNew ? (
            <button
              className="btn danger"
              style={{ marginRight: "auto" }}
              onClick={() => onDelete(entity)}
            >
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
      <Field label="课名">
        <input
          className="input"
          autoFocus
          value={draft.title}
          onChange={(e) => patch({ title: e.target.value })}
          placeholder="例如：高等数学"
        />
      </Field>
      <div className="field-row">
        <div style={{ flex: 1 }}>
          <Field label="星期">
            <Select
              value={draft.dayOfWeek}
              options={DAY_OPTIONS}
              onChange={(v) => patch({ dayOfWeek: v })}
            />
          </Field>
        </div>
        <div style={{ width: 110 }}>
          <Field label="开始">
            <input
              className="input"
              value={draft.startTime}
              onChange={(e) => patch({ startTime: e.target.value })}
              placeholder="08:00"
            />
          </Field>
        </div>
        <div style={{ width: 110 }}>
          <Field label="结束">
            <input
              className="input"
              value={draft.endTime}
              onChange={(e) => patch({ endTime: e.target.value })}
              placeholder="09:40"
            />
          </Field>
        </div>
      </div>
      <div className="field-row">
        <div style={{ flex: 1 }}>
          <Field label="教室">
            <input
              className="input"
              value={draft.room}
              onChange={(e) => patch({ room: e.target.value })}
            />
          </Field>
        </div>
        <div style={{ flex: 1 }}>
          <Field label="教师">
            <input
              className="input"
              value={draft.teacher}
              onChange={(e) => patch({ teacher: e.target.value })}
            />
          </Field>
        </div>
      </div>
      <Field label="周次" hint="支持 1-16周、第6周、1-8周,10-12周、1-16周(单)；留空表示每周">
        <input
          className="input"
          value={draft.weeks}
          onChange={(e) => patch({ weeks: e.target.value })}
          placeholder="1-16周"
        />
      </Field>
      <Field label="临时提醒日期" hint="填了之后只在那一周出现，不再每周重复">
        <input
          className="input"
          type="date"
          value={draft.date}
          onChange={(e) => patch({ date: e.target.value })}
        />
      </Field>
      <Field label="备注">
        <textarea
          className="input"
          rows={2}
          value={draft.notes}
          onChange={(e) => patch({ notes: e.target.value })}
        />
      </Field>
      {error ? <div style={{ color: "var(--error)", fontSize: 12 }}>{error}</div> : null}
    </Dialog>
  );
}
