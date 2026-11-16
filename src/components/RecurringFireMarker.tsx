/**
 * 循环提醒在两处界面的可视化标记（v1.7.19）。
 *
 * 一份数据、两套坐标系：
 *   · 课表页：`GridRecurringFire` / `ListRecurringFire`，按触发时刻落在今天那一列/分组
 *   · 循环提醒页：`RecurringTimeline`，右列 24 小时竖轴
 * 两者共用 `todayRecurringFires()`，**纯只读**，一行都不写 recurring.json。
 *
 * 形态统一为「一条横短线 + 左端一个朝上的三角形（下沿与横线严格对齐）」。悬浮在三角形
 * 附近（命中区由 `::before` 向外圈出一块）时三角以底边为原点放大，并弹**简略**浮层；
 * 单击 / Enter 则打开课表右侧详情栏（`onOpen`，只有课表页传）。
 * 浮层用 `createPortal` 挂到 body：课表网格是 `overflow:hidden`，任何挂在
 * 网格内部的 tooltip 都会被裁掉；portal 同时让定位用视口坐标，能自动避开右/下边缘。
 *
 * 每个标记自己持有 tip 的 state —— hover 时只重渲染这一个标记，
 * 不会把整张课表（几十个课程块）重画一遍。
 */

import { useEffect, useMemo, useState } from "react";
import type { CSSProperties } from "react";
import { createPortal } from "react-dom";
import type { DayRecurringFire, RecurringItem, TodayRecurringFire } from "../model/recurring";
import { describeNext, describeRule, todayRecurringFires } from "../model/recurring";
import { DAY_MINUTES, clockOfDay, layoutTimelineFires } from "../model/recurringTimeline";
import type { WeeklyFireRun } from "../model/recurringTimeline";

const TIP_WIDTH = 236;
const TIMELINE_HEIGHT = 480;
const TICKS = [0, 3, 6, 9, 12, 15, 18, 21, 24];

/**
 * 标记只关心这几件事：哪条规则、几点、是不是已经过去、副信息写什么。
 * 课表（`DayRecurringFire`）与时间线（`TodayRecurringFire`）各自算的次数口径不同
 * （「当天 N 次」vs「今天还剩 N 次」），所以在调用处折成 `countText`，
 * 组件不必知道数据来自哪一套坐标系。
 */
export interface FireView {
  item: RecurringItem;
  fireAt: number;
  /** 已过去的相位点：课表整周视图里会画，淡一档 */
  past?: boolean;
  /** 浮层副信息里的次数短句 */
  countText?: string;
}

interface TipPos {
  left: number;
  top: number;
}

function useFireTip() {
  const [pos, setPos] = useState<TipPos | null>(null);

  const show = (el: HTMLElement) => {
    const r = el.getBoundingClientRect();
    // 右边放不下就翻到左边；下边放不下就上移，绝不盖住任务栏。
    const left =
      window.innerWidth - r.right >= TIP_WIDTH + 16
        ? r.right + 10
        : Math.max(8, r.left - TIP_WIDTH - 10);
    const top = Math.min(
      Math.max(8, r.top - 8),
      Math.max(8, window.innerHeight - 190),
    );
    setPos({ left, top });
  };

  return { pos, show, hide: () => setPos(null) };
}

function FireTip({
  pos,
  fire,
  now,
  brief,
}: {
  pos: TipPos;
  fire: FireView;
  now: number;
  /** 简略模式：只报时刻 / 标题 / 规律（完整内容在点击后的右侧详情栏里） */
  brief?: boolean;
}) {
  return createPortal(
    <div
      className={`rec-fire-tip${brief ? " rec-fire-tip--brief" : ""}`}
      style={{ left: pos.left, top: pos.top }}
      role="tooltip"
    >
      <div className="rec-fire-tip-head">
        <span className="rec-fire-tip-time">{clockOfDay(fire.fireAt)}</span>
        <span className="rec-fire-tip-title">{fire.item.title}</span>
      </div>
      <div className="rec-fire-tip-meta">
        {describeRule(fire.item.rule)}
        {!brief && fire.countText ? ` · ${fire.countText}` : ""}
      </div>
      {brief ? (
        fire.past ? (
          <div className="rec-fire-tip-due is-past">这一次已经过去</div>
        ) : null
      ) : (
        <>
          <div className={`rec-fire-tip-due${fire.past ? " is-past" : ""}`}>
            {fire.past ? "这一次已经过去" : describeNext(fire.fireAt, now)}
          </div>
          {fire.item.body ? <div className="rec-fire-tip-body">{fire.item.body}</div> : null}
        </>
      )}
    </div>,
    document.body,
  );
}

/**
 * 横短线 + 上三角。整块都是 hover 热区，三角形太小，单独挂事件会很难命中。
 * 传了 `onOpen` 的场合（课表页）单击 / Enter 打开右侧详情栏，悬停只弹简略浮层。
 */
function FireBadge({
  fire,
  now,
  variant,
  label,
  style,
  onOpen,
}: {
  fire: FireView;
  now: number;
  variant: "grid" | "list" | "timeline";
  label?: string;
  style?: CSSProperties;
  onOpen?: () => void;
}) {
  const { pos, show, hide } = useFireTip();
  return (
    <>
      <span
        className={`rec-fire-mark rec-fire-mark--${variant}${fire.past ? " is-past" : ""}${
          onOpen ? " is-clickable" : ""
        }`}
        style={style}
        tabIndex={0}
        role={onOpen ? "button" : undefined}
        aria-label={`循环提醒 ${clockOfDay(fire.fireAt)}：${fire.item.title}`}
        onMouseEnter={(e) => show(e.target as HTMLElement)}
        onMouseLeave={hide}
        onFocus={(e) => show(e.currentTarget)}
        onBlur={hide}
        onClick={onOpen}
        onKeyDown={(e) => {
          if (!onOpen) return;
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onOpen();
          }
        }}
      >
        {label ? <span className="rec-fire-label">{label}</span> : null}
        <span className="rec-fire-tri" aria-hidden />
        <span className="rec-fire-line" aria-hidden />
      </span>
      {pos ? <FireTip pos={pos} fire={fire} now={now} brief={Boolean(onOpen)} /> : null}
    </>
  );
}

/**
 * 课表 · 表格视图：一段连续日期共用的标记（v1.7.28）。
 *
 * 定位交给外层：`left` / `right` 是相对**整周七列**的 CSS 长度（百分比 calc），
 * 所以线会穿过列边界，而不是在每列里各画一截。
 */
export function GridRecurringFire({
  run,
  now,
  top,
  left,
  right,
  past,
  onOpen,
}: {
  run: WeeklyFireRun;
  now: number;
  top: number;
  left: string;
  right: string;
  past?: boolean;
  /** 单击 / Enter → 打开课表右侧详情栏 */
  onOpen?: () => void;
}) {
  const days = run.endDay - run.startDay + 1;
  return (
    <FireBadge
      fire={{
        item: run.item,
        fireAt: run.fireAt,
        past,
        // 一天的提醒写「当天 N 次」，跨天写「本周 N 天」
        countText:
          days > 1
            ? `本周 ${days} 天`
            : run.dayCount > 1
              ? `当天 ${run.dayCount} 次`
              : undefined,
      }}
      now={now}
      variant="grid"
      style={{ top, left, right }}
      onOpen={onOpen}
    />
  );
}

/** 课表 · 列表视图：当天分组里的一行，与课程行同样的 38px 节奏 */
export function ListRecurringFire({
  fire,
  now,
  onOpen,
}: {
  fire: DayRecurringFire;
  now: number;
  onOpen?: () => void;
}) {
  return (
    <div className={`rec-fire-list-row${fire.past ? " is-past" : ""}`}>
      <span className="rec-fire-list-time">{clockOfDay(fire.fireAt)}</span>
      <FireBadge
        fire={{
          item: fire.item,
          fireAt: fire.fireAt,
          past: fire.past,
          countText: fire.dayCount > 1 ? `当天 ${fire.dayCount} 次` : undefined,
        }}
        now={now}
        variant="list"
        label={fire.item.title}
        onOpen={onOpen}
      />
    </div>
  );
}

/** 循环提醒页右列：24 小时竖直时间线，只读 */
export function RecurringTimeline({ items }: { items: RecurringItem[] }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(id);
  }, []);

  const fires = useMemo(() => todayRecurringFires(items, now), [items, now]);
  const placed = useMemo(() => layoutTimelineFires(fires, TIMELINE_HEIGHT), [fires]);

  return (
    <section className="card rec-timeline-card">
      <div className="rec-timeline-head">
        <span className="rec-timeline-title">今日时间线</span>
        {fires.length > 0 ? <span className="rec-timeline-count">{fires.length}</span> : null}
      </div>

      {fires.length === 0 ? (
        <div className="empty rec-timeline-empty">
          今天没有循环提醒
          <span className="rec-timeline-empty-hint">启用中的规则如果今天还有触发点，会按时刻标在这里。</span>
        </div>
      ) : (
        <div className="rec-timeline-body" style={{ height: TIMELINE_HEIGHT }}>
          <div className="rec-timeline-axis" aria-hidden />
          {TICKS.map((h) => (
            <div
              key={h}
              className="rec-timeline-tick"
              style={{ top: ((h * 60) / DAY_MINUTES) * TIMELINE_HEIGHT }}
            >
              <span className="rec-timeline-tick-line" aria-hidden />
              <span className="rec-timeline-tick-text">
                {String(h).padStart(2, "0")}:00
              </span>
            </div>
          ))}
          {placed.map(({ fire, top, rawTop, shifted }) => (
            <div key={fire.item.id} className="rec-timeline-slot" style={{ top }}>
              {shifted ? (
                <span className="rec-fire-guide" style={{ height: Math.max(top - rawTop, 1) }} aria-hidden />
              ) : null}
              <FireBadge
                fire={{
                  item: fire.item,
                  fireAt: fire.fireAt,
                  countText: `今天 ${fire.remainingToday} 次`,
                }}
                now={now}
                variant="timeline"
                label={`${clockOfDay(fire.fireAt)} · ${fire.item.title}`}
              />
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
