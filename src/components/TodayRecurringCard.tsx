/**
 * 今日页右列的「今日循环」独立卡片（v1.7.17）。
 *
 * 定位与约束（动手前先读）：
 *
 * 1. **是页内卡片，不是新窗口**。它挂在「今日截止」下方，同宽 300px、随页面
 *    一起滚动；不开 WebviewWindow、不建新进程、不占导航位。
 * 2. **只读展示**。规则本体、相位计算、编辑 / 启停 /「立即试一次」都只有
 *    `RecurringSection.tsx` + `model/recurring.ts` 那一份实现。这里绝不写
 *    `recurring.json`，也不复制一遍规律判定 —— 需要管理时跳到侧栏第 6 页。
 * 3. **只列今天之内还有相位点的启用规则**，按触发时刻升序；停用的、结束日期
 *    已过的、今天已经响完（下一次排到明天）的一律不出现。这一句是纯函数
 *    `todayRecurringFires` 的职责，本组件只负责把它画出来。
 */

import { useMemo } from "react";
import type { PageKey } from "./Chrome";
import { IconRecurring } from "./Icons";
import {
  describeNext,
  describeRule,
  todayRecurringFires,
  type RecurringItem,
} from "../model/recurring";

function clockOf(millis: number): string {
  const d = new Date(millis);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function TodayRecurringCard({
  items,
  now,
  onNavigate,
}: {
  items: RecurringItem[];
  now: number;
  onNavigate?: (page: PageKey) => void;
}) {
  const fires = useMemo(() => todayRecurringFires(items, now), [items, now]);

  return (
    <section className="card today-rec-card">
      {/* v1.7.20：标题行 / 计数 / 「打开」三个部件改成共享类，与上方
          「今日截止」用同一套（card-head / card-head-count / card-open-btn）。 */}
      <div className="card-head">
        <div className="card-heading">
          <span>今日循环</span>
          {fires.length > 0 ? (
            <span className="card-head-count">{fires.length}</span>
          ) : null}
        </div>
        <button
          type="button"
          className="btn sm card-open-btn"
          onClick={() => onNavigate?.("recurring")}
          title="打开循环提醒页"
        >
          打开
        </button>
      </div>

      {fires.length === 0 ? (
        <div className="empty today-rec-empty">
          <span className="empty-icon">
            <IconRecurring size={22} />
          </span>
          <span className="empty-title">今天没有循环提醒</span>
          <span className="empty-hint">
            启用中的规则如果今天还有触发点，会按时间列在这里。
          </span>
        </div>
      ) : (
        <div className="today-rec-list">
          {fires.map(({ item, fireAt, remainingToday }) => (
            <div className="today-rec-row" key={item.id}>
              <span className="today-rec-time">{clockOf(fireAt)}</span>
              <span className="today-rec-main">
                <span className="today-rec-title" title={item.title}>
                  {item.title}
                </span>
                <span className="today-rec-meta">
                  {describeRule(item.rule)} · 今天 {remainingToday} 次
                </span>
              </span>
              <span className="today-rec-due">{describeNext(fireAt, now)}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
