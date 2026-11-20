/**
 * 主题与「配色方案」的取值（v1.7.28）。
 *
 * 主题从三档扩到五档：跟随系统 / 浅色 / 深色 / **主题色（亮）** / **主题色（暗）**。
 * 后两档不是新的一套色板，而是「把上面那套中性面按主题色掺一遍」——
 *
 *   · 明暗仍由主题决定（亮/暗各一套 base，见 tokens.css 的 `--n-*`）；
 *   · 面（底色、卡片、分隔线、网格线）由 `--accent` 按比例混进去，形成同色系的
 *     低饱和底；
 *   · **文字色不跟着掺**：正文/次要文字保持中性，对比度才守得住（`--on-surface-faint`
 *     是按纯中性面校准到 4.5:1 的，掺色后那点余量不够再挪）。
 *
 * 所以这里只回答两个问题：用哪套明暗、要不要掺主题色。真正的混色在 CSS 里。
 */

import type { ThemeMode } from "./types";

export type ThemeName = "light" | "dark";
/** neutral = 原来的中性灰；accent = 按主题色掺过的配色方案 */
export type TintMode = "neutral" | "accent";

/** 设置页「主题」下拉的选项。顺序即展示顺序，与本文件的判定一一对应。 */
export const THEME_OPTIONS: { value: ThemeMode; label: string }[] = [
  { value: 0, label: "跟随系统" },
  { value: 1, label: "浅色" },
  { value: 2, label: "深色" },
  { value: 3, label: "主题色（亮）" },
  { value: 4, label: "主题色（暗）" },
];

/**
 * 主题档位 → 明暗。认不出来的值（旧设置、手改的备份）当「跟随系统」，
 * 宁可跟系统也不锁死一套 —— 锁死的话用户会以为换主题没生效。
 */
export function themeNameOf(mode: number | null | undefined, systemDark: boolean): ThemeName {
  if (mode === 1) return "light";
  if (mode === 2) return "dark";
  if (mode === 3) return "light";
  if (mode === 4) return "dark";
  return systemDark ? "dark" : "light";
}

/** 只有 3 / 4 两档掺主题色，其余都是中性面。 */
export function tintOf(mode: number | null | undefined): TintMode {
  return mode === 3 || mode === 4 ? "accent" : "neutral";
}
