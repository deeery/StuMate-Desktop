/**
 * 窗口内提醒横幅（v1.7.28，偏好 `reminderMode === 1`）。
 *
 * 与全局浮窗（`OverlayApp`）是**同一件事的两种落点**：内容口径、倒计时算法、
 * 循环提醒的「跳过本次」都复用它那一套，差别只在「画在哪里」——
 * 这里画在主窗口自己的顶边，从窗口上沿滑下来，不另开窗口、不抢焦点。
 *
 * 三处刻意的取舍：
 *  1. **不跟随鼠标抢戏**：只有鼠标真的移到横幅上才暂停自动消失，理由见下。
 *  2. **自动消失 14 秒**，底下那条细线就是这个倒计时的可视化 ——
 *     横幅停在窗口顶部会挡住标题栏内容，必须自己退场；
 *     想让它多停一会儿，把鼠标放上去即可（悬停期间不消失）。
 *  3. **退场交给 `usePresence`**：React 摘节点是瞬时的，不留退场时间
 *     就看不见「收回去」，跟浮窗滑入的观感对不上。
 */

import { useEffect, useRef, useState } from "react";
import { formatClock, overlayView, type ReminderAlert } from "../model/reminder";
import { usePresence } from "./Presence";
import { StuMateMark } from "./StuMateMark";

/** 卡片内的时间每 15 秒刷新一次，与浮窗保持一致 */
const TICK_MS = 15_000;
/** 自动消失时长；与 CSS 里 `.reminder-banner-timer-fill` 的动画时长同源 */
const AUTO_DISMISS_MS = 14_000;
/**
 * 退场时挂多久才真正摘节点。要**比动画长一点**（动画 180ms）：`usePresence`
 * 是在状态变化后立刻开始计时的，而 CSS 动画要等 React 提交 + 下一帧才启动，
 * 两边一样长会在条幅还剩几十像素时就消失，看着像「啪」一下被抽走。
 */
const EXIT_MS = 240;

export function ReminderBanner({
  alert,
  onDismiss,
  onSkip,
}: {
  alert: ReminderAlert | null;
  onDismiss: () => void;
  /** 循环提醒专用：跳过本次（由 App 转发到 `overlay_skip`，与浮窗走同一条路） */
  onSkip?: (ruleId: string) => void;
}) {
  const presence = usePresence(alert !== null, EXIT_MS);
  const [now, setNow] = useState(() => Date.now());
  const [hovered, setHovered] = useState(false);

  // 退场期间 alert 已经被置空，但节点还要留一会儿播动画 —— 记住最后一份内容
  const kept = useRef<ReminderAlert | null>(null);
  if (alert) kept.current = alert;
  const view = kept.current;

  // 倒计时与进度现算，不读 alert.ongoing（同 OverlayApp）
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), TICK_MS);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    setNow(Date.now());
  }, [alert]);

  // 自动消失：换了一条提醒就重新计时；鼠标停在上面时暂停（用户正在读）
  useEffect(() => {
    if (!alert || hovered) return;
    const timer = window.setTimeout(onDismiss, AUTO_DISMISS_MS);
    return () => window.clearTimeout(timer);
  }, [alert, hovered, onDismiss]);

  if (!presence.mounted || !view) return null;

  const isRecurring = Boolean(view.recurringId);
  const overlay = overlayView(view, now);
  const phase = isRecurring ? "循环提醒" : overlay.phase;
  const timeRange = `${formatClock(view.startMillis)} – ${formatClock(view.endMillis)}`;
  const subtitle = isRecurring
    ? view.room || "按设定的规律重复提醒"
    : view.room
      ? `${timeRange}   ·   ${view.room}`
      : timeRange;
  const countdown = isRecurring ? "" : overlay.countdown;
  // 换一条提醒时给 key 换个值，倒计时进度条才会从满格重新走
  const barKey = `${view.recurringId ?? view.classId}-${view.startMillis}`;

  return (
    <div
      className={`reminder-banner${presence.leaving ? " leaving" : ""}`}
      role="status"
      aria-live="polite"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div className="reminder-banner-body">
        <div className="reminder-banner-mark">
          <StuMateMark size={30} />
        </div>
        <div className="reminder-banner-text">
          <div className="reminder-banner-head">
            <span className="reminder-banner-phase">{phase}</span>
            {countdown ? <span className="reminder-banner-count">{countdown}</span> : null}
          </div>
          <div className="reminder-banner-title" title={view.title}>
            {view.title}
          </div>
          <div className="reminder-banner-sub" title={subtitle}>
            {subtitle}
          </div>
        </div>
        <div className="reminder-banner-actions">
          <button type="button" className="btn primary" onClick={onDismiss}>
            知道了
          </button>
          {isRecurring ? (
            <button
              type="button"
              className="btn ghost"
              onClick={() => onSkip?.(view.recurringId!)}
            >
              跳过本次
            </button>
          ) : null}
        </div>
      </div>
      {/* 自动消失的可视化倒计时：悬停时由 CSS 暂停，与上面的 setTimeout 同拍 */}
      <div className="reminder-banner-timer" aria-hidden="true">
        <div
          key={barKey}
          className="reminder-banner-timer-fill"
          style={{
            animationDuration: `${AUTO_DISMISS_MS}ms`,
            animationPlayState: hovered ? "paused" : "running",
          }}
        />
      </div>
    </div>
  );
}
