/**
 * 品牌标识：圆角方块 + 课表九宫格 + 高亮待提醒课格 + 铃铛。
 *
 * 原版是 Compose 里的 `ImageVector`（`ui/fluent/StuMateMark.kt`），这里用同构的 SVG 重画：
 * 外轮廓圆角 23%（与 26dp 方块上的 6dp 圆角同比例）、3×3 网格、右上格固定琥珀色
 * （**不跟主题的 warning** —— 浅色主题的 warning 是深褐 #9D5D00，压在蓝底上会糊成一团）。
 *
 * 铃铛形状取 Lucide `bell` 的描边风格（ISC 许可，声明见 THIRD-PARTY-NOTICES.md）。
 */

export function StuMateMark({ size = 20 }: { size?: number }) {
  const bg = "var(--accent)";
  const ink = "var(--on-accent)";
  const slot = "#FFB900";
  return (
    <svg width={size} height={size} viewBox="0 0 512 512" aria-label="StuMate">
      <rect width="512" height="512" rx="118" fill={bg} />
      {/* 九宫格：课表的时间格。线宽按 512 画布给，缩小到 18px 也不会糊 */}
      <g stroke={ink} strokeWidth="26" strokeLinecap="round" opacity="0.9">
        <path d="M132 176h248M132 256h248M132 336h248" />
        <path d="M176 132v248M256 132v248M336 132v248" />
      </g>
      {/* 右上角实心琥珀格 = 即将开始、等着被提醒的那节课 */}
      <rect x="272" y="152" width="80" height="80" rx="14" fill={slot} />
      {/* 铃铛：逐字取 Lucide bell 的两条 path，按 24→512 的比例（×9.6）放大并落到右下 */}
      <g
        transform="translate(300 300) scale(8.4)"
        fill="none"
        stroke={ink}
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
        <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
      </g>
    </svg>
  );
}
