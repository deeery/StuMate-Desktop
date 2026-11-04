/**
 * 「退场也看得见」的最小实现。
 *
 * React 的条件渲染是**瞬时**的：`{open ? <Panel/> : null}` 在 open 变 false 的那一帧
 * 就把节点从 DOM 里摘掉，CSS 的 exit 动画根本没有时间跑。于是所有「关掉一个东西」
 * 的动作都变成了硬切 —— 面板突然消失、对话框直接不见、提示条凭空蒸发。
 *
 * 这个 hook 只解决一件事：**把卸载推迟到退场动画结束**。
 *
 * ```tsx
 * const p = usePresence(open);
 * {p.mounted ? <Panel className={p.leaving ? "leaving" : ""} /> : null}
 * ```
 *
 * ## 为什么不监听 `transitionend` / `animationend`
 *
 * 那两个事件有三个坑：元素没有可见动画时不发（`prefers-reduced-motion` 会把它压到
 * 0.01ms，某些浏览器干脆不发）；同一元素上有多个动画时事件会重复触发；
 * 被 `display:none` 或父级 `overflow` 裁掉时也不发。**用固定时长兜底最稳**，
 * 时长与 CSS 里的 `--dur-exit` 必须同源（这里默认 120，改一处要改两处）。
 *
 * ## `reduced-motion`
 *
 * 用户的系统设置里开了「减少动态效果」时，CSS 的全局兜底已经把所有动画压到 0.01ms，
 * 这里再多等 120ms 只会让界面显得迟钝。所以直接问一次媒体查询，命中就 0 延迟卸载。
 */

import { useEffect, useState } from "react";

export interface Presence {
  /** 节点是否应该留在 DOM 里（含退场期间） */
  mounted: boolean;
  /** 是否正在退场 —— 调用点用它加 `.leaving` 类 */
  leaving: boolean;
}

const REDUCED =
  typeof window !== "undefined" &&
  typeof window.matchMedia === "function" &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

export function usePresence(open: boolean, exitMs = 120): Presence {
  const [mounted, setMounted] = useState(open);
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    if (open) {
      setMounted(true);
      setLeaving(false);
      return;
    }
    // 从来没打开过：没有节点可退场，也不该无谓地等一个定时器
    if (!mounted) return;

    setLeaving(true);
    const wait = REDUCED ? 0 : exitMs;
    const timer = window.setTimeout(() => {
      setMounted(false);
      setLeaving(false);
    }, wait);
    return () => window.clearTimeout(timer);
  }, [open, exitMs, mounted]);

  return { mounted, leaving };
}
