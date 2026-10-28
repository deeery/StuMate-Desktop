/**
 * 上课提醒卡片（独立窗口，`index.html?overlay=1`）。
 *
 * 与原版 `ui/fluent/OverlayScreen.kt` 对齐：**460×176、正上方居中、无边框、置顶、不进任务栏**。
 * 窗口由 Rust 侧 `overlay.rs` 创建与定位，这里只负责画内容 ——
 * 位置算错是"看不见"，内容算错是"看不懂"，把两件事分开才好查。
 *
 * 三处容易搞错的地方：
 *  1. **倒计时与进度从时间区间现算**，不读 `alert.ongoing`。卡片可能停留一分钟，
 *     期间"还有 1 分钟"要自己变成"已上课 0 分钟"。
 *  2. **窗口新建时事件会丢** —— 页面还在加载。所以挂载后先主动 `overlayState()` 取一次。
 *  3. **不抢焦点**。窗口用 `focused(false)` 创建，用户正在打字时不该被打断；
 *     点了卡片之后 Esc 才有效（跟原版一致，原版也没写"按 Esc 关闭"的提示）。
 */

import { useCallback, useEffect, useState } from "react";
import { api, onOverlayUpdate } from "./api";
import { StuMateMark } from "./components/StuMateMark";
import { formatClock, overlayView, type ReminderAlert } from "./model/reminder";

/** 卡片内的时间每 15 秒刷新一次：够用来更新倒计时，又不至于每秒重渲染 */
const TICK_MS = 15_000;

export function OverlayApp() {
  const [alert, setAlert] = useState<ReminderAlert | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const dismiss = useCallback(() => {
    void api.overlayHide();
  }, []);

  // 挂载后主动取一帧：窗口是新建的，创建时 emit 的事件早于监听注册，会丢
  useEffect(() => {
    let alive = true;
    api
      .overlayState()
      .then((value) => {
        if (!alive) return;
        if (value) setAlert(value);
        // 取不到说明提醒已经被撤销（比如用户在主窗口关掉了），直接收窗
        else void api.overlayHide();
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  // 后续更新走事件（窗口已存在时 Rust 不会再建窗）
  useEffect(() => {
    let alive = true;
    let un: (() => void) | null = null;
    onOverlayUpdate((next) => setAlert(next))
      .then((fn) => {
        if (alive) un = fn;
        else fn();
      })
      .catch(() => {});
    return () => {
      alive = false;
      un?.();
    };
  }, []);

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), TICK_MS);
    return () => window.clearInterval(id);
  }, []);

  // Esc 关闭。窗口不抢焦点时收不到按键，这是预期行为（点过卡片之后才有效）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") dismiss();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [dismiss]);

  if (!alert) return null;

  const view = overlayView(alert, now);
  const timeRange = `${formatClock(alert.startMillis)} – ${formatClock(alert.endMillis)}`;

  return (
    <div
      style={{
        height: "100vh",
        boxSizing: "border-box",
        display: "flex",
        flexDirection: "column",
        padding: "20px 22px",
        // 先铺不透明底色再叠渐变：只写渐变的话，半透明像素会和窗口底混合，
        // 顶部会变成一片亮银而不是"深色卡片顶部微亮"（原版踩过，见 OverlayScreen.kt）
        background: "var(--surface)",
        backgroundImage: "linear-gradient(180deg, var(--accent-tint), transparent 45%)",
        color: "var(--on-surface)",
        fontFamily: "var(--font-ui)",
        overflow: "hidden",
      }}
    >
      <div style={{ display: "flex", gap: 16, alignItems: "flex-start" }}>
        <div style={{ flex: "0 0 46px", marginTop: 2 }}>
          <StuMateMark size={46} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span
              style={{
                fontSize: 12,
                fontWeight: 600,
                letterSpacing: 0.6,
                color: "var(--accent)",
              }}
            >
              {view.phase}
            </span>
            <span style={{ flex: 1 }} />
            {view.countdown ? (
              <span style={{ fontSize: 12, color: "var(--on-surface-variant)" }}>
                {view.countdown}
              </span>
            ) : null}
          </div>
          <div
            style={{
              marginTop: 5,
              fontSize: 21,
              fontWeight: 600,
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
            title={alert.title}
          >
            {alert.title}
          </div>
          <div
            style={{
              marginTop: 5,
              fontSize: 13,
              color: "var(--on-surface-variant)",
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
          >
            {alert.room ? `${timeRange}   ·   ${alert.room}` : timeRange}
          </div>
        </div>
      </div>

      <div style={{ flex: 1 }} />

      {/* 进度条：只有"正在上课"时才有内容，其余情况留一条空槽，布局不会跳 */}
      <div
        style={{
          height: 4,
          borderRadius: 2,
          background: "var(--surface3)",
          overflow: "hidden",
        }}
      >
        {view.progress > 0 ? (
          <div
            style={{
              height: "100%",
              width: `${Math.min(Math.max(view.progress, 0), 1) * 100}%`,
              borderRadius: 2,
              background: "var(--accent)",
            }}
          />
        ) : null}
      </div>

      <div style={{ height: 14 }} />

      <div>
        <button className="btn primary" onClick={dismiss}>
          知道了
        </button>
      </div>
    </div>
  );
}
