/**
 * 提醒轮询。对应原版 `platform/ReminderEngine.kt` 的 `start()` 循环。
 *
 * v1.7.15 起这一轮同时判定**上课提醒**与**循环提醒**：
 *   · 上课提醒：`decideReminder` 纯函数，按 classId 去重，逻辑与原版一致；
 *   · 循环提醒：`planRecurring` 纯函数，`lastFiredAt` 落盘做幂等补检。
 *
 * 三个来自原版的细节仍然保留：
 * 1. 首次检查延迟 5 秒，启动时课表/设置可能还在装载。
 * 2. 休眠唤醒补检：距上次检查超过 90 秒就清掉上课提醒的去重标记。
 *    循环提醒不用清 —— `planRecurring` 靠 `prevCheckAt` 自己判定「刚醒」，
 *    错过的相位点一律快进、一个都不补弹。
 * 3. 去重按 classId，同一节课的「即将上课」和「正在上课」只提醒一次。
 *
 * **冲突优先级**：浮窗是单例（`overlay.rs` 会覆盖内容）、系统通知 tag 固定，
 * 物理上不可能同时展示两条。所以上课提醒优先，被压下的循环提醒按
 * 「跳过本次」快进一个周期，不排队、不补弹 —— 上课提醒窗口过去就永久没了，
 * 循环提醒下一周期还会来。
 *
 * 定时器留在 WebView：`Rust setup` 里没有后台线程，`Cargo.toml` 也没有 tokio。
 * 隐藏到托盘时 Chromium 会节流 timer，代价是**最多约 1 分钟的延迟**；
 * 正确性由「lastFiredAt 落盘 + 每次重算相位」保证，节流/休眠/重启三者
 * 都只影响延迟。补检点：每次 tick、`visibilitychange`、`focus`、启动后首拍。
 */

import { useCallback, useEffect, useRef } from "react";
import { api, onRecurringSkip } from "./api";
import { decideReminder, reminderModeOf, type ReminderAlert } from "./model/reminder";
import {
  planRecurring,
  recurringAlert,
  skipOnce,
  type RecurringFireAtUpdate,
  type RecurringItem,
} from "./model/recurring";
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
  recurring: RecurringItem[],
  saveRecurring: (items: RecurringItem[]) => Promise<void>,
  /** 「窗口内提醒」模式下把这一条交给 App 的横幅（`reminderMode === 1`） */
  showBanner: (alert: ReminderAlert) => void,
) {
  const lastShownId = useRef<number | null>(null);
  const lastCheckAt = useRef(0);
  // 回调里要读最新的课表、偏好与规则，但不想让它们成为定时器的依赖
  // （否则每次改设置都会重启定时器，节奏被打乱）
  const latest = useRef({ classes, prefs, saveRecurring });
  latest.current = { classes, prefs, saveRecurring };

  /**
   * 规则列表的**进程内权威副本**。
   *
   * `saveRecurring` 是整份写回，两个 tick / 一次「跳过本次」几乎同时到达时，
   * 若各自基于 React state 拼快照，后写的旧快照会覆盖前一次的新 lastFiredAt。
   * 所以所有内部更新先改这个 ref，再串行写盘；外部（设置页编辑、导入备份）
   * 换了整个数组时才从 prop 同步进来。
   */
  const rulesRef = useRef<RecurringItem[]>(recurring);
  const seenProp = useRef(recurring);
  if (seenProp.current !== recurring) {
    seenProp.current = recurring;
    rulesRef.current = recurring;
  }

  const writeChain = useRef<Promise<void>>(Promise.resolve());

  /** 串行提交一次规则变更：先更新权威副本，再落盘。失败只提示，不回滚 */
  const commit = useCallback(
    (updater: (items: RecurringItem[]) => RecurringItem[]): Promise<void> => {
      writeChain.current = writeChain.current.then(async () => {
        const next = updater(rulesRef.current);
        if (next === rulesRef.current) return;
        rulesRef.current = next;
        try {
          await latest.current.saveRecurring(next);
        } catch (err) {
          toast(`循环提醒保存失败：${String(err)}`, "error");
        }
      });
      return writeChain.current;
    },
    [toast],
  );

  const applyUpdates = useCallback(
    (updates: RecurringFireAtUpdate[]) =>
      commit((items) => {
        if (updates.length === 0) return items;
        const byId = new Map(updates.map((u) => [u.id, u.lastFiredAt]));
        let changed = false;
        const next = items.map((it) => {
          const value = byId.get(it.id);
          if (value === undefined || value === it.lastFiredAt) return it;
          changed = true;
          return { ...it, lastFiredAt: value };
        });
        return changed ? next : items;
      }),
    [commit],
  );

  /** 三通道呈现：浮窗（看 `reminderMode` 决定全局 / 窗口内）+ 系统通知
   *（托盘状态由调用方单独更新） */
  const present = useCallback(
    async (alert: ReminderAlert, title: string, body: string, p: Prefs) => {
      // 0 全局浮窗 / 1 主窗口内横幅 / 2 不弹。三档互斥 —— 同一时刻只该有一种
      // 「看得见的提醒」，否则用户会看到两张卡片讲同一件事。
      const mode = reminderModeOf(p);
      if (mode === 0) {
        try {
          await api.overlayShow(alert);
        } catch (err) {
          toast(`提醒窗口打不开：${String(err)}`, "error");
        }
      } else if (mode === 1) {
        // 窗口内横幅由 App 渲染；这里只把「这一条」交上去
        showBanner(alert);
      }

      // 卡片那边 Rust 已经挡了全屏，但通知是这里发的：不挡的话游戏里
      // 虽然没弹卡片，却会从右下角冒出一条 —— 一样打断。
      const blocked = p.notifyEnabled ? await api.notifyBlocked().catch(() => null) : null;
      if (p.notifyEnabled && blocked) {
        toast(`已跳过通知：${blocked}`, "info");
      }
      if (p.notifyEnabled && !blocked) {
        void notifySystem(title, body);
      }
    },
    [toast, showBanner],
  );

  const check = useCallback(
    async (opts: { force?: boolean } = {}) => {
      const now = Date.now();
      const prevCheckAt = lastCheckAt.current;
      const wokeUp = prevCheckAt !== 0 && now - prevCheckAt > SLEEP_GAP_MS;
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

      // ① 托盘状态：菜单两行 + tooltip（上课状态优先，与浮窗口径一致）
      try {
        await api.trayStatus(decision.status.title, decision.status.detail);
      } catch {
        // 托盘不可用（极少数环境）不该影响提醒本身
      }

      // ② 循环提醒判定：无论这一拍有没有上课提醒都要跑，因为「让位」的
      //    那一条也必须快进一个周期，否则下一拍会补弹回来。
      const plan = planRecurring(rulesRef.current, now, prevCheckAt);
      if (plan.updates.length > 0) void applyUpdates(plan.updates);

      const classAlert = decision.alert;
      if (classAlert) {
        if (!opts.force && lastShownId.current === classAlert.classId) return;
        lastShownId.current = classAlert.classId;
        await present(
          classAlert,
          `${classAlert.ongoing ? "正在上课" : "即将上课"}：${classAlert.title}`,
          classAlert.room ? classAlert.room : "StuMate Lite",
          p,
        );
        return; // 上课提醒优先：循环提醒这一拍让位，updates 已快进
      }

      lastShownId.current = null; // 没有需要关注的课 → 让这节课以后还能再提醒

      if (!plan.fire) return;
      const { item } = plan.fire;
      await present(
        recurringAlert(item, now),
        `循环提醒：${item.title}`,
        item.body || "StuMate Lite",
        p,
      );
    },
    [applyUpdates, present],
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

  // 节流补检：窗口从托盘恢复 / 重新获得焦点时立刻算一次。
  // 被 Chromium 压到 1 分钟一拍的后台页面，靠这条把延迟收回来。
  useEffect(() => {
    if (!ready) return;
    const onVisible = () => {
      if (document.visibilityState === "visible") void check();
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onVisible);
    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onVisible);
    };
  }, [ready, check]);

  // 浮窗「跳过本次」：Rust 只广播 ruleId，相位推进在这里做（规则只有前端一份）
  useEffect(() => {
    let alive = true;
    let un: (() => void) | null = null;
    onRecurringSkip(({ ruleId }) => {
      const now = Date.now();
      void commit((items) => {
        const found = items.find((it) => it.id === ruleId);
        if (!found) return items;
        return items.map((it) =>
          it.id === ruleId ? { ...it, lastFiredAt: skipOnce(it, now) } : it,
        );
      });
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
  }, [commit]);

  return { checkNow: useCallback(() => void check({ force: true }), [check]) };
}

/**
 * 系统通知。
 *
 * 用 Web Notification API —— WebView2 里映射到 Windows 通知，不需要额外插件。
 * 不可用时静默跳过：主要触达是置顶卡片，通知只是锦上添花，失败不该打断用户。
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
