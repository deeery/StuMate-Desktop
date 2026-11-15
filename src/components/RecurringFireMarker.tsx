/**
 * 循环提醒在两处界面的可视化标记（v1.7.19）。
 *
 * 一份数据、两套坐标系：
 *   · 课表页：`GridRecurringFire` / `ListRecurringFire`，按触发时刻落在今天那一列/分组
 *   · 循环提醒页：`RecurringTimeline`，右列 24 小时竖轴
 * 两者共用 `todayRecurringFires()`，**纯只读**，一行都不写 recurring.json。
 *
 * 形态统一为「一条横短线 + 左端一个朝左的三角形」。hover（或键盘聚焦）弹浮层，
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
}: {
  pos: TipPos;
  fire: FireView;
  now: number;
}) {
  return createPortal(
    <div className="rec-fire-tip" style={{ left: pos.left, top: pos.top }} role="tooltip">
      <div className="rec-fire-tip-head">
        <span className="rec-fire-tip-time">{clockOfDay(fire.fireAt)}</span>
        <span className="rec-fire-tip-title">{fire.item.title}</span>
      </div>
      <div className="rec-fire-tip-meta">
        {describeRule(fire.item.rule)}
        {fire.countText ? ` · ${fire.countText}` : ""}
      </div>
      <div className={`rec-fire-tip-due${fire.past ? " is-past" : ""}`}>
        {fire.past ? "这一次已经过去" : describeNext(fire.fireAt, now)}
      </div>
      {fire.item.body ? <div className="rec-fire-tip-body">{fire.item.body}</div> : null}
    </div>,
    document.body,
  );
}

/** 横短线 + 左三角。整块都是 hover 热区，三角形太小，单独挂事件会很难命中。 */
function FireBadge({
  fire,
  now,
  variant,
  label,
  style,
}: {
  fire: FireView;
  now: number;
  variant: "grid" | "list" | "timeline";
  label?: string;
  style?: CSSProperties;
}) {
  const { pos, show, hide } = useFireTip();
  return (
    <>
      <span
        className={`rec-fire-mark rec-fire-mark--${variant}${fire.past ? " is-past" : ""}`}
        style={style}
        tabIndex={0}
        aria-label={`循环提醒 ${clockOfDay(fire.fireAt)}：${fire.item.title}`}
        onMouseEnter={(e) => show(e.target as HTMLElement)}
        onMouseLeave={hide}
        onFocus={(e) => show(e.currentTarget)}
        onBlur={hide}
      >
        {label ? <span className="rec-fire-label">{label}</span> : null}
        <span className="rec-fire-tri" aria-hidden />
        <span className="rec-fire-line" aria-hidden />
      </span>
      {pos ? <FireTip pos={pos} fire={fire} now={now} /> : null}
    </>
  );
}

/** 课表 · 表格视图：某一列内按 `mapping.fractionOf(分钟) * gridHeight` 定位 */
export function GridRecurringFire({
  fire,
  now,
  top,
}: {
  fire: DayRecurringFire;
  now: number;
  top: number;
}) {
  return (
    <FireBadge
      fire={{
        item: fire.item,
        fireAt: fire.fireAt,
        past: fire.past,
        // 一天内有多于一次才提，写「当天 1 次」是废话
        countText: fire.dayCount > 1 ? `当天 ${fire.dayCount} 次` : undefined,
      }}
      now={now}
      variant="grid"
      style={{ top }}
    />
  );
}

/** 课表 · 列表视图：当天分组里的一行，与课程行同样的 38px 节奏 */
export function ListRecurringFire({
  fire,
  now,
}: {
  fire: DayRecurringFire;
  now: number;
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
