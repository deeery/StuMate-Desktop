/**
 * 循环提醒页（v1.7.16）：侧栏的第六个一级入口。
 *
 * 规则本体仍是 `components/RecurringSection.tsx` 那一份实现 —— 相位计算、
 * 「立即试一次」的假 id、编辑对话框都只在那里。本页只负责页头与页面容器，
 * 让「循环提醒」与今天 / 课表 / 便签 / 知识库 / 设置同级，不再埋在设置页里。
 */

import { PageTopBar, type AppContextValue } from "../App";
import { RecurringSection } from "../components/RecurringSection";
import { RecurringTimeline } from "../components/RecurringFireMarker";

export function RecurringPage({ ctx }: { ctx: AppContextValue }) {
  const enabled = ctx.recurring.filter((r) => r.enabled).length;
  const total = ctx.recurring.length;
  return (
    <>
      <PageTopBar
        title="循环提醒"
        subtitle={total === 0 ? "还没有规则" : `${enabled} 条启用 · 共 ${total} 条`}
      />
      <div className="page-content">
        {/* v1.7.19：左规则列表 + 右「今日时间线」。右列只读，复用
            todayRecurringFires()，不写 recurring.json、不复制一份规律判定。 */}
        <div className="rec-page-cols">
          <div className="rec-page-main">
            <RecurringSection ctx={ctx} />
          </div>
          <RecurringTimeline items={ctx.recurring} />
        </div>
      </div>
    </>
  );
}
