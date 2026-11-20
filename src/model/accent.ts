/**
 * 主题色（v1.7.28，可调）。
 *
 * 换一盏灯的做法是给 `<html>` 挂 `data-accent`，CSS 里只覆盖 `--accent` /
 * `--on-accent` 两个令牌；剩下的派生色（`--accent-tint` / `--accent-hover` /
 * `--hero-bg` / `--hero-border`）在 `tokens.css` 里已经写成
 * `color-mix(in srgb, var(--accent) …)`，所以淡底、悬停底、正在上课的主卡
 * 会一起跟着走，不用逐个登记。**新增一个色只改这一个文件 + tokens 里两行**。
 *
 * ## 为什么深浅各一套色
 *
 * 浅色主题下 accent 要「压得住白字」（按钮实心底 + `--on-accent` 文字），
 * 深色主题下 accent 又要「能在深底上做文字色」（状态行、强调标签）。
 * 同一个 hex 不可能两头都合格 —— 浅色的 #0067c0 放在深底上几乎看不见，
 * 深色的 #60cdff 又压不住白字。所以每个色给两个值。
 *
 * ## 对比度（都算过，见 README 变更记录）
 *
 * 浅色：白字压在 accent 上 5.44 ~ 6.63:1；
 * 深色：`on-accent` 压在 accent 上 5.96 ~ 7.86:1，accent 作为文字压在
 * `--surface #2b2b2b` 上 6.25 ~ 7.87:1。全部过 4.5:1。
 */

export interface AccentPreset {
  id: string;
  name: string;
  /** 浅色主题的主色：深一些，白字压得住 */
  light: string;
  /** 深色主题的主色：亮一些，能在深底上当文字色 */
  dark: string;
}

/** 顺序即设置页色块的排列顺序。第一个是默认值。 */
export const ACCENTS: AccentPreset[] = [
  { id: "blue", name: "蓝", light: "#0067c0", dark: "#60cdff" },
  { id: "teal", name: "青", light: "#026b6e", dark: "#4cc2c4" },
  { id: "green", name: "绿", light: "#0f7b0f", dark: "#6ccb5f" },
  { id: "purple", name: "紫", light: "#7a3cb8", dark: "#c39bf0" },
  { id: "pink", name: "洋红", light: "#b429a6", dark: "#f0a0e8" },
  { id: "orange", name: "橙", light: "#ab4402", dark: "#ffab70" },
];

export const DEFAULT_ACCENT = "blue";

/**
 * 把设置里存的任意值收敛成合法的一项。
 * 设置文件可能来自旧版、手改的备份，或未来的新键 —— 认不出来就回默认，
 * 而不是让整页 CSS 变量落空（那会让按钮变成无底无边的一行字）。
 */
export function accentOf(id: unknown): AccentPreset {
  return ACCENTS.find((a) => a.id === id) ?? ACCENTS[0];
}

/** 设置页色块要显示的颜色：跟当前生效的那一套走，深色下别拿浅色值充数 */
export function accentSwatch(preset: AccentPreset, theme: "light" | "dark"): string {
  return theme === "dark" ? preset.dark : preset.light;
}
