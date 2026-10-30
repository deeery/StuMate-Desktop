/**
 * 课程块的身份色。
 *
 * 原版（Compose）与 lite 都把「常规课」统一画成强调蓝竖条：一周八门课，块长得
 * 一模一样，想找「这学期周三下午都上什么」只能逐块读课名。这里按课程 id 派生
 * 一个稳定的色相档位，同一节课在任何一周、任何主题下都是同一种颜色。
 *
 * 为什么不加字段存库：数据库与原版共用（`%APPDATA%\StuMate\class_reminder.db`），
 * 加列会让两个版本互相看不懂；而「颜色」本来就不需要用户输入 —— 派生即可。
 *
 * 为什么用 FNV-1a 而不是 `id % 8`：自增 id 是连续的，取模会把相邻新增的课
 * 排到相邻色档，正好在同一周出现的两门课反而容易撞色。哈希打散后分布更均匀，
 * 且结果只取决于 id 本身（不是数组下标），课程增删、排序变化都不会让颜色乱跳。
 */

/** 可用的身份色档数，与 tokens.css 的 `--class-0..7` 一一对应 */
export const CLASS_COLOR_COUNT = 8;

/** 稳定的色档下标：同样的 id 永远得到同样的 0..7 */
export function classColorIndex(id: number | string): number {
  const text = String(id);
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash % CLASS_COLOR_COUNT;
}

/** 直接给 CSS 用的颜色值：`var(--class-3)` */
export function classColorOf(id: number | string): string {
  return `var(--class-${classColorIndex(id)})`;
}
