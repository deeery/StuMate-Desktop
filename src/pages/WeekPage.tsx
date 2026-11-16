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
import type { CSSProperties } from "react";
import { PageTopBar, type AppContextValue } from "../App";
import { Dialog, Field, Select } from "../components/ui";
import { usePresence } from "../components/Presence";
import {
  IconChevronLeft,
  IconChevronRight,
  IconClose,
  IconEdit,
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
  type Span,
} from "../model/timeAxis";
import { currentWeek, mondayOf, weekContains, weekStart } from "../model/weekSchedule";
import { DAY_NAMES, dateOf } from "../model/today";
import { classColorOf } from "../model/classColor";
import { notesOfClass, notesOfRule } from "../model/noteLinks";
import type { NoteEntity } from "../model/types";
import { deadlineTimeLabel, hasDeadline } from "../model/notes";
import {
  describeNext,
  describeRule,
  firesOnDay,
  startOfDay,
  upcomingFireAt,
  type DayRecurringFire,
  type RecurringItem,
} from "../model/recurring";
import {
  clockOfDay,
  extendSpanForFires,
  mergeWeekFires,
  minuteOfDay,
  type WeeklyFireRun,
} from "../model/recurringTimeline";
import { GridRecurringFire, ListRecurringFire } from "../components/RecurringFireMarker";
import {
  RuleDialog,
  draftOf,
  itemOf,
  type Draft,
} from "../components/RecurringSection";
import { api, errorText } from "../api";

const DAY_CN = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

const DAY_OPTIONS = DAY_NAMES.map((d, i) => ({ value: d, label: DAY_CN[i] }));

/** 毫秒 → `<input type="date">` 要的 `YYYY-MM-DD`（本地时区，不走 UTC，否则会差一天）。 */
function toDateInput(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function WeekPage({
  ctx,
  onOpenNote,
}: {
  ctx: AppContextValue;
  /** 详情栏里点「关联便签」→ 切到便签页并选中那一条（由 App 注入） */
  onOpenNote?: (id: number) => void;
}) {
  const week1Monday = ctx.prefs.week1Monday;
  const nowWeek = currentWeek(week1Monday);
  const [week, setWeek] = useState<number>(() => nowWeek ?? 1);
  const [editing, setEditing] = useState<ClassEntity | null>(null);
  /**
   * 选中态**按课程 id 唯一**，不按开始时间。
   *
   * 这里原来是 `focus: Focus | null`，而块的高亮判定写成
   * `focus?.startMinute === minutesOf(p.cls.startTime)` —— 只要开始时间相同就一起亮。
   * 结果是点周一 10:00 那节课，周一/周二/周五所有 10:00 的块全部被画上 accent 边框，
   * 看上去像「一行被整行选中」。同一时段本来就会有多门课（不同天），所以这不是罕见情况。
   *
   * 顺带把 `focus` 这个只为放大纵轴服务的状态也拿掉了：它进来时传的区间是
   * `{start, start + 1}`，一分钟的区间在 600 分钟的时间轴上放大 2.2 倍，
   * 视觉上等于没有，却让 mapping 的计算多绕一层。选中与布局现在彻底解耦。
   */
  const [selectedId, setSelectedId] = useState<number | null>(null);
  /**
   * 循环提醒标记的选中态。和课程选中**互斥**：右侧只有一块详情栏，
   * 两种内容同时挂进去会打架（课程有编辑/删除，提醒是另一套字段）。
   * v1.7.28：选中的是「一段连续日期」（`WeeklyFireRun`），不再是单天。
   */
  const [selectedFire, setSelectedFire] = useState<{
    run: WeeklyFireRun;
    past: boolean;
  } | null>(null);
  /** 从详情栏点「编辑」时打开同一个规则对话框（复用 RecurringSection 的表单） */
  const [ruleDraft, setRuleDraft] = useState<Draft | null>(null);
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

  /*
   * 「现在」单独挂一个 60s 时钟：当前时刻线、今日循环提醒标记、纵轴跨度共用它，
   * 网格不会按分钟重渲染。它必须在 span 之前 —— 今天的相位点要参与纵轴范围计算。
   */
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  // 今天落在这周的第几列（不在本周时为 -1）
  const todayIndex = DAY_NAMES.findIndex(
    (_, i) => dateOf(weekMonday + i * 24 * 60 * 60 * 1000) === dateOf(nowMs),
  );

  /*
   * v1.7.27：整周七列都画循环提醒。
   * 原来只算「今天还有剩的相位点」，于是下午打开课表一片空白 —— 用户以为
   * 功能没生效。课表本来就是一周视图，每天 08:00 的提醒应该在七列里都看得见；
   * 已过去的那次由 `past` 标出来，界面上淡一档。
   * `weekFires[i]` 对应 DAY_NAMES[i]，长度恒为 7。
   */
  const weekFires = useMemo(
    () =>
      DAY_NAMES.map((_, i) =>
        firesOnDay(ctx.recurring, startOfDay(weekMonday + i * 24 * 60 * 60 * 1000), nowMs),
      ),
    [ctx.recurring, weekMonday, nowMs],
  );
  const allFires = useMemo(() => weekFires.flat(), [weekFires]);

  const baseSpan = useMemo(
    () => spanOf(weekClasses) ?? { startMinute: 8 * 60, endMinute: 18 * 60 },
    [weekClasses],
  );
  // 相位点也要落进可见纵轴：spanOf 只看课程，21:00 的提醒会被 overflow:hidden 裁掉。
  // 一整周都没有标记时不扩展 —— 白白拉长只会压扁课程块。
  const span = useMemo(
    () => extendSpanForFires(baseSpan, allFires, allFires.length > 0),
    [baseSpan, allFires],
  );

  // 容器高度 → 每格几小时。留 1 行余量，免得刚好卡在边界上出现半格滚动条
  const rows = Math.max(Math.floor((gridHeight - 24) / 56), 4);
  const perRow = hoursPerRow(span, rows);
  const totalRows = rowCount(span, perRow);
  const mapping = useMemo(() => mappingOf(span, null), [span]);
  const marks = useMemo(() => roundMarks(span, perRow * 60), [span, perRow]);
  const bands = useMemo(() => zebraBands(span), [span]);

  /**
   * 循环提醒标记的排布（v1.7.28）。
   *
   * 先把「按天」折成「按连续日期分段」：同一条规则、同一时刻、日期相连的几天
   * 共用一条线和一个三角，线穿过列边界。位置仍来自与课程块同一个 `mapping`，
   * 所以缩放窗口、换每格小时数都不会错位。
   *
   * 防重叠只发生在**横向范围相交**的两段之间（18px 最小间距）；七列里互不相干
   * 的标记不该互相挤。`past` 的口径不变：只有**今天**那一次已经过去才淡化。
   */
  const fireRuns = useMemo(() => mergeWeekFires(weekFires), [weekFires]);

  const fireSlots = useMemo(() => {
    const maxTop = Math.max(gridHeight - 4, 0);
    const pastOf = (run: WeeklyFireRun) => {
      if (todayIndex < 0 || run.endDay !== todayIndex) return false;
      return Boolean(weekFires[todayIndex]?.find((f) => f.item.id === run.item.id)?.past);
    };
    const raw = fireRuns
      .map((run) => ({
        run,
        rawTop: mapping.fractionOf(minuteOfDay(run.fireAt)) * gridHeight,
      }))
      .sort((a, b) => a.rawTop - b.rawTop);

    const out: { run: WeeklyFireRun; top: number; past: boolean }[] = [];
    for (const { run, rawTop } of raw) {
      let top = Math.min(rawTop, maxTop);
      for (const placed of out) {
        const overlaps =
          placed.run.startDay <= run.endDay && run.startDay <= placed.run.endDay;
        if (overlaps && top - placed.top < 18) top = Math.min(placed.top + 18, maxTop);
      }
      out.push({ run, top, past: pastOf(run) });
    }
    return out;
  }, [fireRuns, mapping, gridHeight, todayIndex, weekFires]);

  // 换周之后原来那节课可能不在这一周了，选中态要跟着清掉，
  // 否则会把「上一周选过的 id」带过来，看起来像选中了一块空白。
  useEffect(() => {
    setSelectedId(null);
    setSelectedFire(null);
  }, [week]);

  /*
   * 选中 id → 课程实体。这里用 `weekClasses` 而不是 `ctx.classes`：
   * 详情面板必须和「本周实际画出来的块」一致，否则切周到一门没课的周次，
   * 面板还挂着一个不在网格里的课程，点「编辑」改的是看不见的条目。
   */
  const selected = useMemo(
    () => (selectedId === null ? null : weekClasses.find((c) => c.id === selectedId) ?? null),
    [selectedId, weekClasses],
  );

  /**
   * 三处「一关就消失」的退场保留：详情面板、课程编辑对话框、校准周数对话框。
   *
   * 关闭动作会把状态**同一帧**置空，但节点还要再画 120ms 播收起动画。
   * 直接读当前值会让它先塌成空白再淡出（一帧白框），所以用 ref 记住最后一份
   * 可渲染的值。只在当前值为空时才回退 —— 否则切到另一节课会先闪一帧上一条。
   *
   * 与 Compose 端 `RightPanelTransition` 的 `retained` 同一个思路。
   */
  // v1.7.21：退场时长要跟 CSS 里 `.week-detail.leaving` 的 `--dur-select-out`
  // 同源（Presence 用固定时长兜底，改一处必须改两处）。v1.7.22 一起 160 → 220。
  // v1.7.25：再**多留 40ms 余量**（220 → 260）。面板现在还会做宽度收起动画，
  // 定时器与 CSS 动画不是同一刻起跑，等长会在宽度还剩 80px 时就把节点摘掉，
  // 露出一次可见的末段跳变 —— 多这 40ms 刚好让宽度归零后再卸载。
  const detailPresence = usePresence(selected !== null || selectedFire !== null, 260);
  /*
   * 面板已经展开时只是换内容（课程 ↔ 循环提醒、或换另一条规则）——
   * **不重播** 0→268 的宽度展开与淡入，否则每点一次整张表都会重新缩一圈。
   * `detailWasOpen` 记的是「上一次渲染时面板是否已挂载」，所以只能在 effect 里写：
   * 首次打开那一帧还要读到 false（否则连第一次都不给动画）。
   */
  const detailWasOpen = useRef(false);
  useEffect(() => {
    detailWasOpen.current = detailPresence.mounted;
  }, [detailPresence.mounted]);
  const detailEnter = !detailWasOpen.current;
  /**
   * 面板内容有两种来源（课程 / 循环提醒），退场时要保留「最后一份」，
   * 所以把两者折成一个带 kind 的目标，而不是两个平行的 retained ref ——
   * 两个 ref 并存时，切到另一种内容会先闪一帧旧的。
   */
  type DetailTarget =
    | { kind: "class"; cls: ClassEntity }
    | { kind: "fire"; run: WeeklyFireRun; past: boolean };
  const liveTarget: DetailTarget | null = selected
    ? { kind: "class", cls: selected }
    : selectedFire
      ? { kind: "fire", run: selectedFire.run, past: selectedFire.past }
      : null;
  const retainedTarget = useRef<DetailTarget | null>(null);
  if (liveTarget) retainedTarget.current = liveTarget;
  const shownTarget = liveTarget ?? retainedTarget.current;

  const editPresence = usePresence(editing !== null);
  const retainedEdit = useRef<ClassEntity | null>(null);
  if (editing) retainedEdit.current = editing;
  const shownEdit = editing ?? retainedEdit.current;

  // 规则对话框（从循环提醒详情栏进「编辑」）—— 与课程对话框同一套退场保留
  const rulePresence = usePresence(ruleDraft !== null);
  const retainedRule = useRef<Draft | null>(null);
  if (ruleDraft) retainedRule.current = ruleDraft;
  const shownRule = ruleDraft ?? retainedRule.current;

  const calPresence = usePresence(calOpen);

  /**
   * 本周统计。口径照抄原版 `computeWeekStats`：
   * 节数按块数、课时按「结束 - 开始」求和后一小时为单位（整点不带小数点），
   * 最忙的那天用「周X · N 节」，一天都没课时显示「—」。
   */
  const weekStats = useMemo(() => {
    const minutes = weekClasses.reduce((sum, c) => {
      const s = minutesOf(c.startTime);
      const e = minutesOf(c.endTime);
      if (s === null || e === null) return sum;
      return sum + Math.max(e - s, 0);
    }, 0);
    const perDay = DAY_NAMES.map((d) => weekClasses.filter((c) => c.dayOfWeek === d).length);
    const busiest = perDay.reduce((best, n, i) => (n > perDay[best] ? i : best), 0);
    const hours = minutes / 60;
    return {
      courses: weekClasses.length,
      hours: Number.isInteger(hours) ? String(hours) : hours.toFixed(1),
      busiest: perDay[busiest] === 0 ? "—" : `${DAY_CN[busiest]} · ${perDay[busiest]} 节`,
    };
  }, [weekClasses]);

  /* nowMs / todayIndex 已在上面（span 需要它们）算好，这里只推当前时刻的位置。 */
  const nowClock = new Date(nowMs);
  const nowMinute = nowClock.getHours() * 60 + nowClock.getMinutes();
  const nowTop =
    todayIndex >= 0 && nowMinute >= span.startMinute && nowMinute <= span.endMinute
      ? mapping.fractionOf(nowMinute) * gridHeight
      : null;

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
      // 删掉的是当前选中项时，面板会挂着一个已经不存在的课程 —— 一起清掉
      setSelectedId((id) => (id === entity.id ? null : id));
      ctx.toast("已删除");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  /**
   * 规则保存 / 删除：走和循环提醒页同一个 `ctx.saveRecurring`，
   * 表单也是同一个 `RuleDialog`（`draftOf` / `itemOf`）。这里不重写规则模型。
   */
  const saveRule = async () => {
    if (!ruleDraft) return;
    const item = itemOf(ruleDraft, Date.now());
    const exists = ctx.recurring.some((r) => r.id === item.id);
    const next = exists
      ? ctx.recurring.map((r) => (r.id === item.id ? item : r))
      : [...ctx.recurring, item];
    try {
      await ctx.saveRecurring(next);
      setRuleDraft(null);
      ctx.toast(exists ? "规则已更新" : "规则已创建");
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  const removeRule = async (item: RecurringItem) => {
    try {
      await ctx.saveRecurring(ctx.recurring.filter((r) => r.id !== item.id));
      setSelectedFire(null);
      ctx.toast("规则已删除");
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

        {/* 课表 + 右侧详情面板并排。
            v1.7.25：面板宽度不再是瞬时的 —— 原来 `flex: 0 0 268px` 一挂上，
            网格可用宽度当场少 280px，而课程块是按百分比定位的，于是「选中一下、
            整张表原地缩一圈」，正是用户说的「表格大小没有过渡」。现在面板宽度
            从 0 撑到 268px，网格跟着连续变窄，块宽自己就会平滑跟过去。
            `gap` 也交给 CSS 过渡（`.week-split.has-detail`），否则那 12px 仍是硬跳。 */}
        <div
          className={`week-split${
            detailPresence.mounted && !detailPresence.leaving ? " has-detail" : ""
          }`}
        >
          <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
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
                // 点空白处取消选中
                if (e.target === e.currentTarget) {
                  setSelectedId(null);
                  setSelectedFire(null);
                }
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

                    {/* 当前时间线：只画在今天这一列。课表最常被问的是「现在到哪了」，
                        原来只能靠列头强调色 + 自己数刻度。带节奏的 dashed 会干扰网格，
                        所以是一条 1px 实线 + 起点圆点。 */}
                    {isToday && nowTop !== null ? (
                      <div className="now-line" style={{ top: nowTop }} aria-hidden />
                    ) : null}

                    {/* v1.7.28：循环提醒标记不再画在列里，而是画在整周共用的
                        覆盖层上（见下方 .rec-fire-overlay）—— 连续的日子要共用
                        一条线，线的两端落在首日与末日的列边界上。 */}

                    {placed.map((p) => {
                      const height = Math.max(p.heightFraction * gridHeight, 22);
                      const active = selectedId === p.cls.id;
                      // 按块高分级显示，与原版 CourseBlock 的四档阈值一致：
                      //   < 30 只课名 / ≥ 30 课名+地点 / ≥ 58 课名可两行 / ≥ 76 再加块底时间区间。
                      // 不这么分的话，矮块里硬塞三行会互相压住，长课又显得内容很少。
                      const tall = height >= 58;
                      const showRoom = height >= 30 && Boolean(p.cls.room);
                      const showTime = height >= 76 && Boolean(p.cls.startTime);
                      // 同一门课永远同一档身份色（按 id 哈希派生，不存库）；
                      // 临时提醒（有具体日期）仍用警示色 —— 它和常规课不是一个语义
                      const barColor = p.cls.date ? "var(--warning)" : classColorOf(p.cls.id);
                      return (
                        <button
                          key={p.cls.id}
                          // v1.7.22：选中态不再只靠 1px 描边变色（几乎看不出来），
                          // 改成类 + CSS —— 原版是整块底色换 accentTint
                          // （`bg = if (selected) c.accentTint else c.surface3`）+ 描边加粗，
                          // 这里照做，只是把 1.5dp 换成「1px 描边 + 1px 外环」，
                          // 两者都能进 transition。
                          className={`week-block${active ? " is-selected" : ""}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            // 单击只选中，不弹编辑框 —— 和原版 1.6.x 一致：
                            // 「单击看详情，双击编辑」。原来点一下就 setEditing，
                            // 等于所有选中都被对话框盖住，选中态根本看不见。
                            setSelectedFire(null);
                            setSelectedId(p.cls.id);
                          }}
                          onDoubleClick={(e) => {
                            e.stopPropagation();
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
                            // 底色不在这里写死：.week-block / .week-block.is-selected
                            // 在 CSS 里用它把身份色洗淡铺上去（选中时再压一层强调色）
                            "--block-color": barColor,
                            borderRadius: 4,
                            padding: "3px 6px 3px 9px",
                            textAlign: "left",
                            overflow: "hidden",
                            display: "flex",
                            flexDirection: "column",
                            gap: 0,
                            // v1.7.21：top/height 的过渡搬到 app.css（那里还要并上
                            // 选中态的 border-color）；内联写死会把那一条盖掉，
                            // 选中时边框就只能硬切。
                          } as CSSProperties}
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

              {/* v1.7.28：整周共用的循环提醒层。放在七列之上、纵轴右侧，
                  每段按 startDay..endDay 折算成百分比，所以线能连续穿过列边界；
                  容器不吃鼠标事件，只有三角是热区（否则会挡住课程块点击）。 */}
              <div className="rec-fire-overlay">
                {fireSlots.map(({ run, top, past }) => (
                  <GridRecurringFire
                    key={`${run.item.id}-${run.startDay}`}
                    run={run}
                    now={nowMs}
                    top={top}
                    past={past}
                    left={`calc(${(run.startDay / 7) * 100}% + 3px)`}
                    right={`calc(${((6 - run.endDay) / 7) * 100}% + 3px)`}
                    onOpen={() => {
                      // 与课程选中互斥：右侧详情栏一次只讲一件事
                      setSelectedId(null);
                      setSelectedFire({ run, past });
                    }}
                  />
                ))}
              </div>
            </div>
          </div>
        ) : (
          <ListView
            weekClasses={weekClasses}
            selectedId={selectedId}
            firesByDay={weekFires}
            now={nowMs}
            onSelect={(c) => {
              setSelectedFire(null);
              setSelectedId(c.id);
            }}
            onEdit={setEditing}
            onOpenFire={(fire, dayIndex) => {
              setSelectedId(null);
              // 列表里一天一行，不画跨天连线，选中就是「这一天的这一次」
              setSelectedFire({
                run: {
                  item: fire.item,
                  startDay: dayIndex,
                  endDay: dayIndex,
                  fireAt: fire.fireAt,
                  dayCount: fire.dayCount,
                },
                past: fire.past,
              });
            }}
          />
        )}
          </div>
          {detailPresence.mounted && shownTarget ? (
            shownTarget.kind === "class" ? (
              <ClassDetailPanel
                leaving={detailPresence.leaving}
                enter={detailEnter}
                cls={shownTarget.cls}
                stats={weekStats}
                notes={notesOfClass(ctx.notes, ctx.noteLinks, shownTarget.cls.id)}
                now={nowMs}
                onOpenNote={onOpenNote}
                onEdit={() => setEditing(shownTarget.cls)}
                onDelete={() => void remove(shownTarget.cls)}
                onClose={() => setSelectedId(null)}
              />
            ) : (
              <RecurringDetailPanel
                key={shownTarget.run.item.id}
                leaving={detailPresence.leaving}
                enter={detailEnter}
                run={shownTarget.run}
                past={shownTarget.past}
                now={nowMs}
                notes={notesOfRule(ctx.notes, ctx.noteLinks, shownTarget.run.item.id)}
                onOpenNote={onOpenNote}
                onEdit={() => setRuleDraft(draftOf(shownTarget.run.item))}
                onDelete={() => void removeRule(shownTarget.run.item)}
                onClose={() => setSelectedFire(null)}
              />
            )
          ) : null}
        </div>
      </div>

      {editPresence.mounted && shownEdit ? (
        <ClassDialog
          leaving={editPresence.leaving}
          entity={shownEdit}
          isNew={shownEdit.id === 0}
          onClose={() => setEditing(null)}
          onSave={save}
          onDelete={remove}
        />
      ) : null}

      {rulePresence.mounted && shownRule ? (
        <RuleDialog
          leaving={rulePresence.leaving}
          draft={shownRule}
          patch={(p) => setRuleDraft((d) => (d ? { ...d, ...p } : d))}
          onClose={() => setRuleDraft(null)}
          onSave={() => void saveRule()}
        />
      ) : null}

      {calPresence.mounted ? (
        <Dialog
          leaving={calPresence.leaving}
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

/**
 * 右侧课程详情面板（原版 `ClassDetailPanel`）。
 *
 * 规格照抄 Compose 版 v1.7.5 的 268dp 裸列，不画整块背景 —— 面板由两张 `FlCard` 组成，
 * 分别是「课程信息」和「本周统计」，中间 12px 缝。为什么不做成一张长卡：
 * 统计是页面级信息，和某一节课无关，粘在同一张卡里会让「这条属于谁」说不清。
 *
 * 和 1.6.x 的分工一致：**单击课程块看这里，双击才进编辑**。
 */
function ClassDetailPanel({
  cls,
  stats,
  leaving,
  enter = true,
  notes,
  now,
  onOpenNote,
  onEdit,
  onDelete,
  onClose,
}: {
  cls: ClassEntity;
  stats: { courses: number; hours: string; busiest: string };
  leaving?: boolean;
  /** false = 面板本来就在，只是换了内容：直接落在终态，不重播展开动画 */
  enter?: boolean;
  /** 关联到这门课的便签（note_links.json 反查） */
  notes: NoteEntity[];
  now: number;
  onOpenNote?: (id: number) => void;
  onEdit: () => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const isTemp = Boolean(cls.date);
  const dayLabel = DAY_CN[DAY_NAMES.indexOf(cls.dayOfWeek)] ?? cls.dayOfWeek;
  const when = `${isTemp ? cls.date : dayLabel} ${cls.startTime} – ${cls.endTime}`;

  return (
    <aside
      className={`week-detail${leaving ? " leaving" : ""}${enter ? "" : " no-enter"}`}
      aria-label="课程详情"
    >
      {/* v1.7.25：外面这层只负责「宽度」，内容固定 268px 宽、被外层裁切。
          不这么拆的话，宽度动画期间两张卡片每帧都在重排，标题会一行行跳字。 */}
      <div className="week-detail-inner">
      <div className="card" style={{ padding: 16 }}>
        <div style={{ display: "flex", alignItems: "center" }}>
          {/* 身份色条：临时提醒沿用警示色，和网格里那块的颜色条是同一个语义 */}
          <span
            aria-hidden
            style={{
              width: 4,
              height: 30,
              borderRadius: 2,
              background: isTemp ? "var(--warning)" : "var(--accent)",
            }}
          />
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
          {cls.title}
        </div>
        <div style={{ fontSize: 12.5, marginTop: 4, color: "var(--on-surface-variant)" }}>{when}</div>

        <dl style={{ margin: "12px 0 0" }}>
          <DetailRow label="教室" value={cls.room || "—"} />
          <DetailRow label="教师" value={cls.teacher || "—"} />
          <DetailRow label="周次" value={cls.weeks || "不限"} />
          <DetailRow label="类型" value={isTemp ? "临时提醒" : "长期提醒"} />
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

      {/* 详细内容下方：挂着这门课的便签 */}
      <LinkedNotesCard notes={notes} now={now} onOpenNote={onOpenNote} />

      <div className="card" style={{ padding: 16 }}>
        <div className="detail-section-label">本周统计</div>
        <dl style={{ margin: "8px 0 0" }}>
          <DetailRow label="课程" value={`${stats.courses} 节`} />
          <DetailRow label="课时" value={`${stats.hours} 小时`} />
          <DetailRow label="最忙" value={stats.busiest} />
        </dl>
        <div className="detail-section-label" style={{ marginTop: 14 }}>
          操作提示
        </div>
        <div style={{ fontSize: 12, marginTop: 6, color: "var(--on-surface-variant)", lineHeight: 1.6 }}>
          单击课程块查看详情，双击直接编辑。
        </div>
      </div>
      </div>
    </aside>
  );
}

/**
 * 右侧循环提醒详情栏 —— 点课表上的三角打开（v1.7.28）。
 *
 * 与课程详情面板同一副骨架（268dp 裸列 + 两张卡、同样的宽度过渡）。
 * v1.7.28 起**可编辑**：编辑复用循环提醒页的同一个 `RuleDialog`，
 * 删除走同一个 `ctx.saveRecurring`，不在课表页另写一套规则逻辑。
 */
function RecurringDetailPanel({
  run,
  past,
  now,
  leaving,
  enter = true,
  notes,
  onOpenNote,
  onEdit,
  onDelete,
  onClose,
}: {
  run: WeeklyFireRun;
  past: boolean;
  now: number;
  leaving?: boolean;
  /** false = 面板本来就在，只是换了内容：直接落在终态，不重播展开动画 */
  enter?: boolean;
  /** 关联到这条规则的便签 */
  notes: NoteEntity[];
  onOpenNote?: (id: number) => void;
  onEdit: () => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const item = run.item;
  const days = run.endDay - run.startDay + 1;
  const clock = clockOfDay(run.fireAt);
  const when =
    days > 1
      ? `${DAY_CN[run.startDay]}–${DAY_CN[run.endDay]} ${clock}`
      : `${DAY_CN[run.startDay]} ${clock}`;
  // 删除沿用循环提醒页的二次确认：第一次点只改文案，第二次才真删
  const [confirm, setConfirm] = useState(false);

  return (
    <aside
      className={`week-detail${leaving ? " leaving" : ""}${enter ? "" : " no-enter"}`}
      aria-label="循环提醒详情"
    >
      <div className="week-detail-inner">
        <div className="card" style={{ padding: 16 }}>
          <div style={{ display: "flex", alignItems: "center" }}>
            {/* 珊瑚色条：与网格里那枚标记是同一个色号，指认同一条规则 */}
            <span
              aria-hidden
              style={{ width: 4, height: 30, borderRadius: 2, background: "var(--rec-fire)" }}
            />
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
            {item.title}
          </div>
          <div style={{ fontSize: 12.5, marginTop: 4, color: "var(--on-surface-variant)" }}>
            {when}
          </div>

          <dl style={{ margin: "12px 0 0" }}>
            <DetailRow label="规律" value={describeRule(item.rule)} />
            <DetailRow
              label="本周"
              value={days > 1 ? `${days} 天` : run.dayCount > 1 ? `当天 ${run.dayCount} 次` : "1 次"}
            />
            <DetailRow
              label="状态"
              value={past ? "这一次已经过去" : describeNext(upcomingFireAt(item, now), now)}
            />
            <DetailRow label="类型" value="循环提醒" />
          </dl>

          {item.body ? (
            <>
              <div className="detail-section-label" style={{ marginTop: 14 }}>
                正文
              </div>
              <div
                style={{
                  fontSize: 12,
                  marginTop: 6,
                  color: "var(--on-surface-variant)",
                  lineHeight: 1.6,
                  whiteSpace: "pre-wrap",
                  wordBreak: "break-word",
                }}
              >
                {item.body}
              </div>
            </>
          ) : null}

          <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
            <button className="btn primary" onClick={onEdit}>
              <IconEdit size={13} /> 编辑
            </button>
            <button
              className="btn danger"
              onClick={() => {
                if (!confirm) {
                  setConfirm(true);
                  return;
                }
                onDelete();
              }}
            >
              <IconTrash size={13} /> {confirm ? "确定删除？" : "删除"}
            </button>
          </div>
        </div>

        <LinkedNotesCard notes={notes} now={now} onOpenNote={onOpenNote} />

        <div className="card" style={{ padding: 16 }}>
          <div className="detail-section-label">说明</div>
          <div style={{ fontSize: 12, marginTop: 6, color: "var(--on-surface-variant)", lineHeight: 1.6 }}>
            循环提醒只保存在本机 Lite：错过的不补弹，下一次始终按原相位触发。
            启停与「立即试一次」在侧栏「循环提醒」页。
          </div>
        </div>
      </div>
    </aside>
  );
}

/**
 * 「关联便签」卡：课程详情与循环提醒详情共用。
 * 关联关系由便签侧决定（便签编辑面板里的两个下拉），这里只做反查与跳转。
 */
function LinkedNotesCard({
  notes,
  now,
  onOpenNote,
}: {
  notes: NoteEntity[];
  now: number;
  onOpenNote?: (id: number) => void;
}) {
  return (
    <div className="card" style={{ padding: 16 }}>
      <div className="detail-section-label">
        关联便签{notes.length > 0 ? ` · ${notes.length}` : ""}
      </div>
      {notes.length === 0 ? (
        <div
          style={{ fontSize: 12, marginTop: 6, color: "var(--on-surface-faint)", lineHeight: 1.6 }}
        >
          还没有便签挂在这里。在便签编辑面板里选「关联课程 / 关联循环提醒」即可。
        </div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 2, marginTop: 6 }}>
          {notes.map((n) => (
            <button
              key={n.id}
              className="linked-note"
              onClick={() => onOpenNote?.(n.id)}
              title="在便签页打开"
            >
              <span className="linked-note-title">{n.title}</span>
              {hasDeadline(n) ? (
                <span className="linked-note-meta">{deadlineTimeLabel(n.deadlineAt, now)}</span>
              ) : null}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** 详情里的一行「标签 + 值」。标签固定 48px，四行才会左对齐成一条线。 */
function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="detail-row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function ListView({
  weekClasses,
  selectedId,
  firesByDay,
  now,
  onSelect,
  onEdit,
  onOpenFire,
}: {
  weekClasses: ClassEntity[];
  selectedId: number | null;
  /** v1.7.27：整周七组各自的相位点，不再只给「今天」那一组 */
  firesByDay: DayRecurringFire[][];
  now: number;
  onSelect: (c: ClassEntity) => void;
  onEdit: (c: ClassEntity) => void;
  onOpenFire: (fire: DayRecurringFire, dayIndex: number) => void;
}) {
  return (
    <div className="card" style={{ padding: "6px 16px 12px" }}>
      {DAY_NAMES.map((day, i) => {
        const list = weekClasses
          .filter((c) => c.dayOfWeek === day)
          .sort((a, b) => (minutesOf(a.startTime) ?? 0) - (minutesOf(b.startTime) ?? 0));
        // 这一天没课但有循环提醒时，这一组也要出现
        const dayFires = firesByDay[i] ?? [];
        if (list.length === 0 && dayFires.length === 0) return null;
        return (
          <div key={day} style={{ marginTop: 12 }}>
            <div style={{ fontSize: 11, color: "var(--on-surface-faint)", marginBottom: 4 }}>
              {DAY_CN[i]}
            </div>
            {dayFires.map((f) => (
              <ListRecurringFire
                key={f.item.id}
                fire={f}
                now={now}
                onOpen={() => onOpenFire(f, i)}
              />
            ))}
            {list.map((c) => (
              <button
                key={c.id}
                // 与表格视图同一套手势：单击选中、双击编辑
                onClick={() => onSelect(c)}
                onDoubleClick={() => onEdit(c)}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 12,
                  width: "100%",
                  height: 38,
                  borderBottom: "1px solid var(--outline)",
                  textAlign: "left",
                  background: c.id === selectedId ? "var(--accent-tint)" : undefined,
                  borderLeft:
                    c.id === selectedId ? "2px solid var(--accent)" : "2px solid transparent",
                  paddingLeft: 10,
                  // v1.7.21：与表格视图同一个选中时长、同一条曲线，
                  // 列表里也是淡进去而不是硬跳
                  transition:
                    "background-color var(--dur-select) var(--ease-select), border-left-color var(--dur-select) var(--ease-select)",
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
      {weekClasses.length === 0 && firesByDay.every((f) => f.length === 0) ? (
        <div className="empty">这一周没有课</div>
      ) : null}
    </div>
  );
}

function ClassDialog({
  entity,
  isNew,
  leaving,
  onClose,
  onSave,
  onDelete,
}: {
  entity: ClassEntity;
  isNew: boolean;
  leaving?: boolean;
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
      leaving={leaving}
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
