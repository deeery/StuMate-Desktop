/**
 * 图标：全部是内联 SVG，不引图标库。
 *
 * 原版用的是 `GlyphIcons`（自绘矢量），理由是 emoji/字符图标在不同字体下
 * 粗细和基线都不一样，一排图标放在侧栏里会高低不齐。这里沿用同一思路：
 * 统一 16×16 视窗、1.5px 描边、`currentColor` 取色。
 */

import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Svg({ size = 16, children, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {children}
    </svg>
  );
}

export const IconToday = (p: IconProps) => (
  <Svg {...p}>
    <rect x="2" y="3.5" width="12" height="10.5" rx="1.5" />
    <path d="M2 6.5h12M5.5 2v3M10.5 2v3" />
    <path d="M5.5 10l1.8 1.8L11 8.2" />
  </Svg>
);

export const IconWeek = (p: IconProps) => (
  <Svg {...p}>
    <rect x="2" y="2.5" width="12" height="11" rx="1.5" />
    <path d="M2 5.5h12M6 5.5v8M10 5.5v8" />
  </Svg>
);

export const IconNote = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 2.5h10v11H3z" />
    <path d="M5.5 6h5M5.5 9h5M5.5 11.5h3" />
  </Svg>
);

export const IconKnowledge = (p: IconProps) => (
  <Svg {...p}>
    <path d="M2.5 3.2c1.8-.7 3.6-.7 5.5.4 1.9-1.1 3.7-1.1 5.5-.4v9.2c-1.8-.7-3.6-.7-5.5.4-1.9-1.1-3.7-1.1-5.5-.4z" />
    <path d="M8 3.6v9.2" />
  </Svg>
);

export const IconSettings = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="8" cy="8" r="2.2" />
    <path d="M8 1.8v1.6M8 12.6v1.6M2.4 8H1M15 8h-1.4M4 4l-1.1-1.1M13.1 13.1L12 12M12 4l1.1-1.1M2.9 13.1L4 12" />
  </Svg>
);

/** 循环提醒：沿用 IconRefresh 的环箭头，中心补一根钟针，和「设置」区分开 */
export const IconRecurring = (p: IconProps) => (
  <Svg {...p}>
    <path d="M13.2 8a5.2 5.2 0 11-1.6-3.7" />
    <path d="M13.4 2.6v2.9h-2.9" />
    <path d="M8 5.1V8l2 1.3" />
  </Svg>
);

export const IconPlus = (p: IconProps) => (
  <Svg {...p}>
    <path d="M8 3.5v9M3.5 8h9" />
  </Svg>
);

export const IconMinus = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3.5 8h9" />
  </Svg>
);

export const IconClose = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3.5 3.5l9 9M12.5 3.5l-9 9" />
  </Svg>
);

export const IconMaximize = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3.5" y="3.5" width="9" height="9" rx="1" />
  </Svg>
);

export const IconRestore = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3" y="5" width="8" height="8" rx="1" />
    <path d="M5.5 5V3.5h7v7H11" />
  </Svg>
);

export const IconChevronLeft = (p: IconProps) => (
  <Svg {...p}>
    <path d="M9.5 3.5L5 8l4.5 4.5" />
  </Svg>
);

export const IconChevronRight = (p: IconProps) => (
  <Svg {...p}>
    <path d="M6.5 3.5L11 8l-4.5 4.5" />
  </Svg>
);

export const IconChevronDown = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3.5 6l4.5 4.5L12.5 6" />
  </Svg>
);

export const IconSearch = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="7" cy="7" r="4" />
    <path d="M10 10l3.5 3.5" />
  </Svg>
);

export const IconTrash = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 4.5h10M6 4.5V3h4v1.5M4.5 4.5l.6 8.2c0 .5.4.8.9.8h4c.5 0 .9-.3.9-.8l.6-8.2" />
  </Svg>
);

export const IconEdit = (p: IconProps) => (
  <Svg {...p}>
    <path d="M10.6 2.9l2.5 2.5-7.2 7.2-3 .5.5-3z" />
  </Svg>
);

export const IconMore = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="3.5" cy="8" r=".9" fill="currentColor" />
    <circle cx="8" cy="8" r=".9" fill="currentColor" />
    <circle cx="12.5" cy="8" r=".9" fill="currentColor" />
  </Svg>
);

export const IconLink = (p: IconProps) => (
  <Svg {...p}>
    <path d="M6.5 9.5a3 3 0 004.2 0l2-2a3 3 0 00-4.2-4.2l-1 1" />
    <path d="M9.5 6.5a3 3 0 00-4.2 0l-2 2a3 3 0 004.2 4.2l1-1" />
  </Svg>
);

export const IconFile = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 1.8h5l3 3v9.4H4z" />
    <path d="M9 1.8v3h3" />
  </Svg>
);

export const IconExternal = (p: IconProps) => (
  <Svg {...p}>
    <path d="M9 2.5h4.5V7" />
    <path d="M13.5 2.5L7 9" />
    <path d="M12 9.5v3.5a.5.5 0 01-.5.5h-8a.5.5 0 01-.5-.5v-8a.5.5 0 01.5-.5H7" />
  </Svg>
);

export const IconCheck = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 8.5l3.2 3.2L13 5" />
  </Svg>
);

export const IconFolder = (p: IconProps) => (
  <Svg {...p}>
    <path d="M1.8 4.2c0-.5.4-.9.9-.9h3l1.3 1.5h6.3c.5 0 .9.4.9.9v6.4c0 .5-.4.9-.9.9H2.7a.9.9 0 01-.9-.9z" />
  </Svg>
);

export const IconRefresh = (p: IconProps) => (
  <Svg {...p}>
    <path d="M13.2 8a5.2 5.2 0 11-1.6-3.7" />
    <path d="M13.4 2.6v2.9h-2.9" />
  </Svg>
);

export const IconSidebar = (p: IconProps) => (
  <Svg {...p}>
    <rect x="2" y="3" width="12" height="10" rx="1.5" />
    <path d="M6.2 3v10" />
  </Svg>
);

export const IconMap = (p: IconProps) => (
  <Svg {...p}>
    <rect x="2" y="6" width="4" height="4" rx="1" />
    <rect x="10" y="2.5" width="4" height="4" rx="1" />
    <rect x="10" y="9.5" width="4" height="4" rx="1" />
    <path d="M6 8h2v-3.5h2M8 8v3.5h2" />
  </Svg>
);

export const IconList = (p: IconProps) => (
  <Svg {...p}>
    <path d="M5 4h8M5 8h8M5 12h8" />
    <circle cx="2.6" cy="4" r=".7" fill="currentColor" />
    <circle cx="2.6" cy="8" r=".7" fill="currentColor" />
    <circle cx="2.6" cy="12" r=".7" fill="currentColor" />
  </Svg>
);

/** 排序：上下两个箭头 + 三条递减的横线（资源管理器的「排序」按钮） */
export const IconSort = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4.5 3v9M4.5 3 2.6 5M4.5 3 6.4 5" />
    <path d="M9 4h4.5M9 8h3M9 12h1.5" />
  </Svg>
);

/** 全部折叠：两个朝上的尖角叠在一起（各资源管理器通用的「收起」图形）。
 *
 * 一开始画的是「两个箭头指向中线」，14px 下两个箭头挤成一团糊影 ——
 * 那个尺寸只剩 3px 的间距，抗锯齿一摊就看不出是箭头。叠两个 8px 宽的尖角
 * 反而在同样尺寸下清清楚楚。 */
export const IconCollapse = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 8.2 8 4.2l4 4" />
    <path d="M4 14 8 10l4 4" />
  </Svg>
);
