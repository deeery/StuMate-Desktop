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

import { useCallback, useEffect, useRef, useState } from "react";
import { api, onOverlayUpdate } from "./api";
import { StuMateMark } from "./components/StuMateMark";
import { formatClock, overlayView, type ReminderAlert } from "./model/reminder";
import { accentOf } from "./model/accent";
import { tintOf } from "./model/theme";

/** 卡片内的时间每 15 秒刷新一次：够用来更新倒计时，又不至于每秒重渲染 */
const TICK_MS = 15_000;

export function OverlayApp() {
  const [alert, setAlert] = useState<ReminderAlert | null>(null);
  const [now, setNow] = useState(() => Date.now());
  /** 正在退场：窗口由 Rust 侧往上滑 200ms，这段里内容要留着，不能先清空 */
  const [leaving, setLeaving] = useState(false);
  const alertRef = useRef<ReminderAlert | null>(null);
  alertRef.current = alert;

  const dismiss = useCallback(() => {
    // 退场动效在 Rust 侧（窗口整块往上滑），这里只负责「别提前把内容抽掉」：
    // 先标记 leaving 让内容淡出、并请 Rust 开始滑，等它滑完再真正清空。
    // 留快照是为了这 240ms 里若来了新提醒（alert 已换），就别把新内容清掉。
    const snapshot = alertRef.current;
    setLeaving(true);
    void api.overlayHide();
    window.setTimeout(() => {
      if (alertRef.current === snapshot) {
        setAlert(null);
        setLeaving(false);
      }
    }, 240);
  }, []);

  // 主题：main.tsx 先按系统偏好给了个同步默认值，这里再按用户的真实设置纠正。
  // 浮窗没有 App.tsx 那一层主题 effect，只能自己读一次 prefs。
  useEffect(() => {
    let alive = true;
    api
      .prefs()
      .then((p) => {
        if (!alive) return;
        const dark =
          p.themeMode === 2 ||
          p.themeMode === 4 ||
          ((p.themeMode ?? 0) === 0 &&
            (window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false));
        document.documentElement.dataset.theme = dark ? "dark" : "light";
        // 配色方案：主题档位是「主题色（亮）/（暗）」时，浮窗也跟着掺主题色，
        // 否则主窗口是青底、浮窗却是纯灰，看着像两张不同的皮。
        document.documentElement.dataset.tint = tintOf(p.themeMode);
        // 主题色（v1.7.28）：浮窗也用同一盏灯，否则主窗口换了色、浮窗还是蓝的。
        // `accentOf` 认不出来就回默认，不会让卡片变成无底无边。
        document.documentElement.dataset.accent = accentOf(p.accent).id;
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
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
    onOverlayUpdate((next) => {
      // 新提醒接管：就算上一条正在退场，也要把 leaving 收回来
      setLeaving(false);
      setAlert(next);
    })
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
  // 循环提醒复用同一张卡片：区间退化让 overlayView 自动不显示倒计时/进度，
  // 这里只换一行标题、把副标题从「时间·教室」换成正文字，并多一个按钮。
  // 刻意不新建窗口、不改 460×176 尺寸、不给 alert 加 kind 字段。
  const isRecurring = Boolean(alert.recurringId);
  const phase = isRecurring ? "循环提醒" : view.phase;
  const subtitle = isRecurring
    ? alert.room || "按设定的规律重复提醒"
    : alert.room
      ? `${timeRange}   ·   ${alert.room}`
      : timeRange;

  return (
    <div
      // key 跟着「这一条提醒」走：换了一条才重播淡入，同一节课的倒计时刷新不该闪
      key={`${alert.recurringId ?? alert.classId}-${alert.startMillis}`}
      className={`overlay-card${leaving ? " leaving" : ""}`}
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
              {phase}
            </span>
            <span style={{ flex: 1 }} />
            {!isRecurring && view.countdown ? (
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
            {subtitle}
          </div>
        </div>
      </div>

      <div style={{ flex: 1 }} />

      {/* 进度条：只有"正在上课"时才画。灰槽本身没有信息量，
          循环提醒卡里它更像一条坏掉的进度条，所以干脆不渲染；
          高度靠上面那个 flex:1 占位吸收，按钮不会跟着跳。 */}
      {view.progress > 0 ? (
        <div
          style={{
            height: 4,
            borderRadius: 2,
            background: "var(--surface3)",
            overflow: "hidden",
          }}
        >
          <div
            style={{
              height: "100%",
              width: `${Math.min(Math.max(view.progress, 0), 1) * 100}%`,
              borderRadius: 2,
              background: "var(--accent)",
            }}
          />
        </div>
      ) : null}

      <div style={{ height: 14 }} />

      <div style={{ display: "flex", gap: 8 }}>
        <button className="btn primary" onClick={dismiss}>
          知道了
        </button>
        {isRecurring ? (
          <button
            className="btn ghost"
            onClick={() => {
              // Rust 侧会广播 ruleId 并顺手收窗；相位推进由主窗口做
              setAlert(null);
              void api.overlaySkip(alert.recurringId!);
            }}
          >
            跳过本次
          </button>
        ) : null}
      </div>
    </div>
  );
}
