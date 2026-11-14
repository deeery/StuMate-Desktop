/**
 * 窗口骨架：侧栏（含品牌行）+ 窗口按钮。
 *
 * ## 为什么品牌行在侧栏里、窗口按钮在页头里
 *
 * 原版 `AppShell` 的结构是「侧栏通高（品牌标识在它顶部） + 右侧一列（52dp 页头 + 内容）」，
 * 页头那一行同时充当自绘标题栏，窗口按钮贴在页头最右端。这里照搬同一套分工，
 * 而不是继续用「整宽标题栏 + 下面再来一行页头」——后者会让内容整体下移 40px，
 * 两个版本并排看时每张卡片的高度都对不上。
 *
 * 推断依据是原版截图的横向扫描：x∈[0,212] 通高都是 surface2（侧栏），
 * 品牌标识落在 (12..36, y≈18..44) 这段侧栏内部；页头的背景色（bg）从 x=212 才开始。
 *
 * ## 拖拽区
 *
 * `-webkit-app-region: drag` 只铺在页头那一条上（与原版 `windowDragArea` 的范围一致）。
 * 品牌行**刻意不可拖**：原版也没让侧栏顶部当把手。曾经把 `.page-topbar` 和标题栏
 * 都设为 drag，结果页面顶部的按钮和输入框一起变成「拖窗口」热区，点不动。
 */

import { useEffect, useState, type ComponentType } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { StuMateMark } from "./StuMateMark";
import {
  IconClose,
  IconKnowledge,
  IconMaximize,
  IconMinus,
  IconNote,
  IconRecurring,
  IconRestore,
  IconSettings,
  IconSidebar,
  IconToday,
  IconWeek,
} from "./Icons";

export type PageKey = "today" | "week" | "notes" | "knowledge" | "recurring" | "settings";

interface IconProps {
  size?: number;
}

/**
 * v1.7.16 起「循环提醒」是第六个一级页：放在知识库之后、设置之前，
 * 设置依旧是侧栏最后一项。**Ctrl+1..5 的老映射一个都不动**，新页用 Ctrl+6 ——
 * 数字与显示顺序在这里不再一一对应，但用户已有的肌肉记忆不会突然改指向。
 */
export const NAV: { key: PageKey; label: string; icon: ComponentType<IconProps> }[] = [
  { key: "today", label: "今天", icon: IconToday },
  { key: "week", label: "课表", icon: IconWeek },
  { key: "notes", label: "便签", icon: IconNote },
  { key: "knowledge", label: "知识库", icon: IconKnowledge },
  { key: "recurring", label: "循环提醒", icon: IconRecurring },
  { key: "settings", label: "设置", icon: IconSettings },
];

/**
 * 品牌行。只在展开态显示文字，收起成图标栏时只留标识（与原版一致）。
 *
 * 尺寸对齐原版 `AppSidebar` 的品牌行：标识 26dp、与文字间距 10dp、
 * 「StuMate」15sp SemiBold、版本号 11sp 淡色贴右。
 *
 * `LITE` 是两版唯一的身份差别，按验收要求压成**与版本号同规格的灰字**——
 * 原先那枚带色描边的胶囊，视觉权重比页面标题还高，与「极简品牌区」不是一套语言。
 * 但它必须留着：两版共用同一个数据库、可以同时开着，没有这行字就分不清点的是哪个窗口。
 */
export function BrandRow({ version, collapsed }: { version: string; collapsed: boolean }) {
  return (
    <div className="brand-row" title={collapsed ? "StuMate LITE" : undefined}>
      <StuMateMark size={26} />
      {/* 收起态**不卸载**这三个 span：条件渲染会让文字在栏宽开始收之前就消失
          （「先删字、再收栏」），两段动作接不上。这里交给 CSS 的
          max-width + opacity 一起收拢，见 app.css 过渡层。 */}
      <span className="brand">StuMate</span>
      <span className="brand-tag">LITE</span>
      <span className="brand-version">v{version}</span>
    </div>
  );
}

/** 最小化 / 最大化 / 关闭。尺寸对齐原版：46×32，垂直居中在 52 高的页头里 */
export function WindowControls() {
  const [maximized, setMaximized] = useState(false);
  const win = getCurrentWindow();

  useEffect(() => {
    let alive = true;
    win.isMaximized().then((v) => {
      if (alive) setMaximized(v);
    });
    const unlisten = win.onResized(() => {
      win.isMaximized().then((v) => {
        if (alive) setMaximized(v);
      });
    });
    return () => {
      alive = false;
      unlisten.then((f) => f());
    };
  }, [win]);

  return (
    <div className="win-controls">
      <button title="最小化" onClick={() => win.minimize()}>
        <IconMinus size={14} />
      </button>
      <button title={maximized ? "还原" : "最大化"} onClick={() => win.toggleMaximize()}>
        {maximized ? <IconRestore size={14} /> : <IconMaximize size={13} />}
      </button>
      <button className="close" title="关闭" onClick={() => win.close()}>
        <IconClose size={14} />
      </button>
    </div>
  );
}

export function Sidebar({
  page,
  onNavigate,
  collapsed,
  onToggleCollapse,
  status,
  statusOn,
  statusHint,
  version,
  noteCount,
  recurringCount,
}: {
  page: PageKey;
  onNavigate: (page: PageKey) => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
  status: string;
  /** 状态点是否「亮」—— 提醒服务在跑用 success，没跑用 error（与原版一致） */
  statusOn?: boolean;
  /** 悬停说明。原版只有一行文字，这里把数据源 / schema 版本收进 tooltip */
  statusHint?: string;
  version: string;
  noteCount: number;
  recurringCount: number;
}) {
  return (
    <nav className={`sidebar${collapsed ? " rail" : ""}`}>
      <BrandRow version={version} collapsed={collapsed} />
      {NAV.map((item) => {
        const Icon = item.icon;
        // 条数徽标：便签是总数，循环提醒是「启用中的规则数」（停用的不算待触发）。
        // 收起态由 .sidebar.rail .nav-badge 压成 0 宽，这里不必再判 collapsed。
        const badge =
          item.key === "notes" && noteCount > 0
            ? String(noteCount)
            : item.key === "recurring" && recurringCount > 0
              ? String(recurringCount)
              : null;
        return (
          <button
            key={item.key}
            className={`nav-item${page === item.key ? " active" : ""}`}
            onClick={() => onNavigate(item.key)}
            title={collapsed ? item.label : undefined}
          >
            <Icon size={17} />
            <span className="nav-label">{item.label}</span>
            {badge ? <span className="nav-badge">{badge}</span> : null}
          </button>
        );
      })}
      <div className="grow" />
      <button
        className="nav-item nav-toggle"
        onClick={onToggleCollapse}
        title={collapsed ? "展开侧栏" : "收起侧栏"}
      >
        <IconSidebar size={16} />
        <span className="nav-label">收起侧栏</span>
      </button>
      <div className="sidebar-foot" title={statusHint ?? status}>
        <span className={`status-dot${statusOn === false ? " is-off" : ""}`} />
        <span>{status}</span>
      </div>
    </nav>
  );
}
