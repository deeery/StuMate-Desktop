/**
 * 循环提醒页（v1.7.16）：侧栏的第六个一级入口。
 *
 * 规则本体仍是 `components/RecurringSection.tsx` 那一份实现 —— 相位计算、
 * 「立即试一次」的假 id、编辑对话框都只在那里。本页只负责页头与页面容器，
 * 让「循环提醒」与今天 / 课表 / 便签 / 知识库 / 设置同级，不再埋在设置页里。
 */

import { PageTopBar, type AppContextValue } from "../App";
import { RecurringSection } from "../components/RecurringSection";

export function RecurringPage({ ctx }: { ctx: AppContextValue }) {
  const enabled = ctx.recurring.filter((r) => r.enabled).length;
  const total = ctx.recurring.length;
  return (
    <>
      <PageTopBar
        title="循环提醒"
        subtitle={total === 0 ? "还没有规则" : `${enabled} 条启用 · 共 ${total} 条`}
      />
      <div className="page-content" style={{ maxWidth: 760 }}>
        <RecurringSection ctx={ctx} />
      </div>
    </>
  );
}
