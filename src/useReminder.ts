/**
 * 提醒轮询。对应原版 `platform/ReminderEngine.kt` 的 `start()` 循环。
 *
 * 原版是「常驻协程 + 30 秒轮询」，桌面端进程不会被系统杀掉，所以不需要保活、
 * 不需要权限、不需要通知渠道。这里用 `setInterval` 复刻同一套节奏，
 * **判定本身仍然全部交给 `decideReminder` 这个纯函数**，这个 hook 只管：
 *   · 定时器
 *   · 去重（同一节课只提醒一次）
 *   · 副作用（托盘状态、弹卡片、系统通知）
 *
 * 三个来自原版的细节，都有实际理由：
 *
 * 1. **首次检查延迟 5 秒**，不是立刻。启动瞬间数据库/课表可能还在装载，
 *    立刻判定容易得出"今日无课"这种错误结论并缓存下来。
 * 2. **休眠唤醒补检**：`setInterval` 在系统休眠期间不累积，醒来后第一拍可能
 *    比预期晚很多。原版用「距上次检查超过 90 秒」判定刚醒，并清掉去重标记，
 *    否则醒来后那节课永远不会再提醒。
 * 3. **去重按 classId**，同一节课的"即将上课"和"正在上课"只提醒一次 ——
 *    原版如此，用户被提醒两次同一节课会很烦。
 */

import { useCallback, useEffect, useRef } from "react";
import { api } from "./api";
import { decideReminder } from "./model/reminder";
import type { ClassEntity, Prefs } from "./model/types";
import { currentWeek } from "./model/weekSchedule";

/** 轮询周期，与安卓端一致 */
const POLL_MS = 30_000;
/** 首次检查延迟，与安卓端一致 */
const INITIAL_DELAY_MS = 5_000;
/** 超过这个间隔没检查过，认为刚从休眠中醒来，立即补检一次 */
const SLEEP_GAP_MS = 90_000;

export function useReminder(
  classes: ClassEntity[],
  prefs: Prefs | null,
  ready: boolean,
  toast: (text: string, tone?: "info" | "error") => void,
) {
  const lastShownId = useRef<number | null>(null);
  const lastCheckAt = useRef(0);
  // 回调里要读最新的课表与偏好，但又不想让它们成为定时器的依赖
  // （否则每次改设置都会重启定时器，节奏被打乱）
  const latest = useRef({ classes, prefs });
  latest.current = { classes, prefs };

  const check = useCallback(
    async (opts: { force?: boolean } = {}) => {
      const now = Date.now();
      const wokeUp =
        lastCheckAt.current !== 0 && now - lastCheckAt.current > SLEEP_GAP_MS;
      lastCheckAt.current = now;
      if (wokeUp) lastShownId.current = null;

      const { classes: list, prefs: p } = latest.current;
      if (!p) return;
      const decision = decideReminder({
        classes: list,
        currentWeek: currentWeek(p.week1Monday, now),
        now,
        advanceMinutes: p.advanceMinutes,
      });

      // ① 托盘状态：菜单两行 + tooltip
      try {
        await api.trayStatus(decision.status.title, decision.status.detail);
      } catch {
        // 托盘不可用（极少数环境）不该影响提醒本身
      }

      const alert = decision.alert;
      if (!alert) {
        // 没有需要关注的课 → 清掉去重标记，让这节课以后还能再提醒
        lastShownId.current = null;
        return;
      }
      if (!opts.force && lastShownId.current === alert.classId) return;
      lastShownId.current = alert.classId;

      // ② 置顶卡片（对应原版 showPopup）
      if (p.showPopup) {
        try {
          await api.overlayShow(alert);
        } catch (err) {
          toast(`提醒窗口打不开：${String(err)}`, "error");
        }
      }

      // ③ 系统通知（对应原版 notifyEnabled → 托盘气泡）
      //
      // 先问一句"现在方便打扰吗"：卡片那边 Rust 已经挡了全屏，但通知是这里发的，
      // 不挡的话游戏里虽然没弹卡片，却会从右下角冒出一条 —— 一样打断。
      const blocked = p.notifyEnabled ? await api.notifyBlocked().catch(() => null) : null;
      if (p.notifyEnabled && blocked) {
        toast(`已跳过通知：${blocked}`, "info");
      }
      if (p.notifyEnabled && !blocked) {
        // 桌面端没有权限模型，这里只按用户意愿走；失败不打断提醒
        void notifySystem(
          `${alert.ongoing ? "正在上课" : "即将上课"}：${alert.title}`,
          alert.room ? `${alert.room}` : "StuMate Lite",
        );
      }
    },
    [toast],
  );

  // 主循环
  useEffect(() => {
    if (!ready) return;
    let alive = true;
    const first = window.setTimeout(() => {
      if (alive) void check();
    }, INITIAL_DELAY_MS);
    const timer = window.setInterval(() => {
      if (alive) void check();
    }, POLL_MS);
    return () => {
      alive = false;
      window.clearTimeout(first);
      window.clearInterval(timer);
    };
  }, [ready, check]);

  return { checkNow: useCallback(() => void check({ force: true }), [check]) };
}

/**
 * 系统通知。
 *
 * 目前用 Web Notification API —— WebView2 里它映射到 Windows 通知，
 * 不需要额外插件。不可用时静默跳过：提醒的主要触达是置顶卡片，
 * 通知只是"锦上添花"，失败不该弹错误打断用户。
 */
async function notifySystem(title: string, body: string): Promise<void> {
  try {
    if (typeof Notification === "undefined") return;
    let permission = Notification.permission;
    if (permission === "default") permission = await Notification.requestPermission();
    if (permission !== "granted") return;
    new Notification(title, { body, tag: "stumate-reminder" });
  } catch {
    /* 静默降级 */
  }
}
