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
  IconRestore,
  IconSettings,
  IconSidebar,
  IconToday,
  IconWeek,
} from "./Icons";

export type PageKey = "today" | "week" | "notes" | "knowledge" | "settings";

interface IconProps {
  size?: number;
}

export const NAV: { key: PageKey; label: string; icon: ComponentType<IconProps> }[] = [
  { key: "today", label: "今天", icon: IconToday },
  { key: "week", label: "课表", icon: IconWeek },
  { key: "notes", label: "便签", icon: IconNote },
  { key: "knowledge", label: "知识库", icon: IconKnowledge },
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
    <div className="brand-row">
      <StuMateMark size={26} />
      {collapsed ? null : (
        <>
          <span className="brand">StuMate</span>
          <span className="brand-tag">LITE</span>
          <span className="brand-version">v{version}</span>
        </>
      )}
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
  version,
  noteCount,
}: {
  page: PageKey;
  onNavigate: (page: PageKey) => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
  status: string;
  version: string;
  noteCount: number;
}) {
  return (
    <nav className={`sidebar${collapsed ? " rail" : ""}`}>
      <BrandRow version={version} collapsed={collapsed} />
      {NAV.map((item) => {
        const Icon = item.icon;
        // 便签条数徽标：原版只在便签项上出现，收起态不显示（图标栏宽度放不下）
        const badge = item.key === "notes" && noteCount > 0 ? String(noteCount) : null;
        return (
          <button
            key={item.key}
            className={`nav-item${page === item.key ? " active" : ""}`}
            onClick={() => onNavigate(item.key)}
            title={collapsed ? item.label : undefined}
          >
            <Icon size={17} />
            {collapsed ? null : (
              <>
                <span className="nav-label">{item.label}</span>
                {badge ? <span className="nav-badge">{badge}</span> : null}
              </>
            )}
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
        {collapsed ? null : <span className="nav-label">收起侧栏</span>}
      </button>
      <div className="sidebar-foot" title={status}>
        <span className="status-dot" />
        {collapsed ? null : <span>{status}</span>}
      </div>
    </nav>
  );
}
