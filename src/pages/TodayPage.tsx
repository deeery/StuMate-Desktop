/**
 * 今天页：主卡（正在上 / 即将上 / 空闲 / 已上完）+ 今日时间线 + 今日截止
 *          + 今日循环（v1.7.17，右列第二张独立卡片）。
 *
 * 主卡的四种状态是这一页唯一的"规则"，判定全部走 `model/today.ts` 的纯函数，
 * 这里只负责排版 —— 和原版 `TodayPage` 的分工一致。
 */

import { useEffect, useMemo, useState } from "react";
import { PageTopBar, type AppContextValue } from "../App";
import { IconCheck, IconNote, IconToday } from "../components/Icons";
import { TodayRecurringCard } from "../components/TodayRecurringCard";
import type { PageKey } from "../components/Chrome";
import {
  greetingOf,
  humanDuration,
  isIdle,
  monthDayOf,
  ongoingAt,
  remaining,
  todayClasses,
  type TodayClass,
} from "../model/today";
import { currentWeek } from "../model/weekSchedule";
import { deadlineCountdown, hasDeadline, typeLabel } from "../model/notes";

const WEEKDAY_CN = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

export function TodayPage({
  ctx,
  onNavigate,
}: {
  ctx: AppContextValue;
  /** v1.7.17：今日循环卡片的「打开」入口，跳到侧栏第 6 页 */
  onNavigate?: (page: PageKey) => void;
}) {
  const [now, setNow] = useState(() => Date.now());

  // 每分钟重算一次：倒计时和"正在上"的判定都靠它。
  // 不逐秒刷新是因为这一页最细的刻度就是分钟，每秒重渲染只是白烧 CPU。
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const week = currentWeek(ctx.prefs.week1Monday, now);
  const today = useMemo(
    () => todayClasses(ctx.classes, week, now),
    [ctx.classes, week, now],
  );
  const upcoming = useMemo(() => remaining(today, now), [today, now]);
  const ongoing = today.find((c) => ongoingAt(c, now));
  const next = upcoming.find((c) => c.startMillis > now);
  const idle = isIdle(upcoming, now, ctx.prefs.advanceMinutes);

  const deadlines = useMemo(() => {
    const endOfDay = new Date(now);
    endOfDay.setHours(23, 59, 59, 999);
    return ctx.notes
      .filter((n) => hasDeadline(n) && n.deadlineAt <= endOfDay.getTime())
      .sort((a, b) => a.deadlineAt - b.deadlineAt);
  }, [ctx.notes, now]);

  const todayIndex = (new Date(now).getDay() + 6) % 7;

  return (
    <>
      {/* 原版这一页顶栏**没有**右侧动作：便签入口在右列面板里（Lite 暂未实现），
          课表入口在侧栏。这里曾经挂过一个「打开课表 ›」，纯属占位，去掉才对得上。 */}
      <PageTopBar
        title={greetingOf(now)}
        subtitle={`${monthDayOf(now)}${WEEKDAY_CN[todayIndex]}${week ? ` · 第 ${week} 周` : ""}`}
      />

      <div className="page-content">
        <HeroCard
          ongoing={ongoing}
          next={next}
          idle={idle}
          allDone={today.length > 0 && upcoming.length === 0}
          empty={today.length === 0}
          now={now}
          advanceMinutes={ctx.prefs.advanceMinutes}
        />

        <div
          style={{
            display: "grid",
            gridTemplateColumns: "minmax(0, 1fr) minmax(0, 300px)",
            gap: 16,
            marginTop: 16,
            alignItems: "start",
          }}
        >
          {/* 今日课程：原版是「表头 + 34dp 四列行」，没有卡片外框 ——
              表头铺 surface、行铺页面底色，靠分隔线分条。
              之前这里是「卡片 + 小节标题 + 三列自流排版」：多一层卡片框，
              且教室挤在课程名后面，扫视时不齐列。 */}
          <div style={{ minWidth: 0 }}>
            <div className="today-table-head">
              <span style={{ width: 126 }}>时间</span>
              <span style={{ flex: 1 }}>课程</span>
              <span style={{ width: 150 }}>教室</span>
              <span style={{ width: 90 }}>教师</span>
              <span style={{ width: 72 }} />
            </div>
            {today.length === 0 ? (
              // 空态不是「留白」，是这一页唯一能给下一步的地方：
              // 说明为什么空、空的时候去哪 —— 原来只有一句 12px 灰字浮在中间
              <div className="empty" style={{ padding: "24px 0" }}>
                <span className="empty-icon">
                  <IconToday size={22} />
                </span>
                <span className="empty-title">今天没有课</span>
                <span className="empty-hint">
                  在「课表」里添加课程后，这里会按时间顺序列出今天的安排；临时提醒也会出现在这里。
                </span>
              </div>
            ) : (
              today.map((c) => (
                <TimelineRow key={`${c.entity.id}-${c.startMillis}`} item={c} now={now} />
              ))
            )}
          </div>

          {/* 右列（v1.7.17）：今日截止 + 今日循环两张独立卡片，各自一个 card 外框。
              包一层 flex 列而不是把两节塞进同一张卡 —— 用户要的是「单独窗口」。 */}
          <div className="today-side">
          <section className="card" style={{ padding: "14px 16px 10px" }}>
            <SectionTitle>今日截止</SectionTitle>
            {deadlines.length === 0 ? (
              <div className="empty" style={{ padding: "24px 0" }}>
                <span className="empty-icon">
                  <IconCheck size={22} />
                </span>
                <span className="empty-title">今天没有要交的东西</span>
                <span className="empty-hint">便签里带截止时间的项目到期当天会列在这里。</span>
              </div>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                {deadlines.map((n) => (
                  <div
                    key={n.id}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 8,
                      height: 34,
                      borderBottom: "1px solid var(--outline)",
                    }}
                  >
                    <span style={{ color: `var(--note-${n.colorIndex % 8})`, display: "flex" }}>
                      <IconNote size={14} />
                    </span>
                    <span
                      style={{
                        flex: 1,
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {n.title}
                    </span>
                    <span
                      style={{
                        fontSize: 11,
                        color: n.deadlineAt < now ? "var(--error)" : "var(--on-surface-faint)",
                      }}
                    >
                      {deadlineCountdown(n.deadlineAt, now)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </section>

            <TodayRecurringCard
              items={ctx.recurring}
              now={now}
              onNavigate={onNavigate}
            />
          </div>
        </div>
      </div>
    </>
  );
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        fontSize: 12,
        fontWeight: 600,
        color: "var(--on-surface-variant)",
        marginBottom: 8,
        letterSpacing: 0.2,
      }}
    >
      {children}
    </div>
  );
}

function HeroCard({
  ongoing,
  next,
  idle,
  allDone,
  empty,
  now,
  advanceMinutes,
}: {
  ongoing?: TodayClass;
  next?: TodayClass;
  idle: boolean;
  allDone: boolean;
  empty: boolean;
  now: number;
  advanceMinutes: number;
}) {
  let kicker = "现在";
  let title = "今天没有课";
  let detail = "好好休息，或者去课表里排一门新课。";
  let tone = "var(--on-surface)";

  if (ongoing) {
    // 剩余时间放**第一行**（原版口径：「正在上课 · 还剩 2 小时 13 分钟」），
    // 不跟第三行的「时间·教室·教师」挤在一起 —— 那一行原版是固定三段式，
    // 多塞一段进去会把教师名挤出可视宽度。
    // 单位写「分钟」而不是 humanDuration 的「分」：原版 `TodayPage.kt` 里是
    // 逐字写死的 `${left / 60} 小时 ${left % 60} 分钟`，两版并排时「分钟/分」的
    // 字符宽度差会让首行长度明显不同。
    const leftMin = Math.max(0, Math.floor((ongoing.endMillis - now) / 60_000));
    const leftH = Math.floor(leftMin / 60);
    const leftM = leftMin % 60;
    kicker = `正在上课 · 还剩 ${leftH > 0 ? `${leftH} 小时 ` : ""}${leftM} 分钟`;
    title = ongoing.entity.title;
    // 分段符用 en dash（–）而不是连字符：原版是 `"${start} – ${end}"`。
    detail = [`${ongoing.entity.startTime} – ${ongoing.entity.endTime}`, ongoing.entity.room, ongoing.entity.teacher]
      .filter(Boolean)
      .join(" · ");
    tone = "var(--accent)";
  } else if (next && !idle) {
    kicker = "即将开始";
    title = next.entity.title;
    detail = `${next.entity.startTime} 开始 · ${humanDuration(next.startMillis - now)}后${
      next.entity.room ? ` · ${next.entity.room}` : ""
    }`;
    tone = "var(--warning)";
  } else if (next) {
    kicker = "空闲";
    title = `下一节 ${next.entity.title}`;
    detail = `${next.entity.startTime} 开始 · 还有 ${humanDuration(
      next.startMillis - now,
    )}（提前 ${advanceMinutes} 分钟提醒）`;
    tone = "var(--success)";
  } else if (allDone) {
    kicker = "今天的课上完了";
    title = "收工";
    detail = "今天没有还没结束的课了。";
    tone = "var(--success)";
  } else if (empty) {
    kicker = "今天";
    title = "今天没有课";
    detail = "课表里这一天是空的。";
  }

  // 「正在上课」时整张卡用强调色填充 —— 与原版同一处理：这一页最重要的是
  // 「现在有没有课、上到几点」，用整片色块而不是一个小色点来表达，扫一眼就够。
  //
  // v1.7.12：填充什么色交给令牌（--hero-*）。浅色主题仍是整片强调色，
  // 深色主题换成「强调色洗进 surface」的低饱和底 —— 深色下一条 1160px 宽的
  // 高亮青色亮带会把整页的眼压全吃走，而这页真正要读的是课程名和结束时间。
  const inClass = Boolean(ongoing);
  const heroFg = inClass ? "var(--hero-fg)" : "var(--on-surface)";
  const heroSub = inClass ? "var(--hero-sub)" : "var(--on-surface-variant)";
  // 状态点：所有「有状态」的形态都画，它是状态的可视锚点；纯空态不画
  const showDot = Boolean(ongoing || next || allDone);

  return (
    <section
      className="card"
      style={{
        // 内边距 / 行距 / 字号全部照抄原版 `HeroOngoing`：
        // 18×20 内边距、状态行→标题 8、标题→副行 4，标题 22sp、副行 13sp、时间 26sp。
        // 之前这里用的是全局 --fs-display（26sp），标题比原版大一号，
        // 并排时整张卡的文字重心明显偏下。
        padding: "18px 20px",
        display: "flex",
        alignItems: "flex-start",
        justifyContent: "space-between",
        gap: 18,
        background: inClass ? "var(--hero-bg)" : "var(--surface)",
        borderColor: inClass ? "var(--hero-border)" : undefined,
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        {/* 状态行：原版是 7dp 实心圆点 + 12sp 常重正文，不是全大写带字距的标签。
            点只在上课态出现 —— 它是「正在」的可视锚点，其余状态没有对应语义。 */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            fontSize: 12,
            color: inClass ? "var(--hero-kicker)" : tone,
            marginBottom: 8,
          }}
        >
          {showDot ? (
            <span
              style={{
                width: 7,
                height: 7,
                borderRadius: 4,
                background: "currentColor",
                flex: "0 0 auto",
              }}
            />
          ) : null}
          {kicker}
        </div>
        <div
          style={{
            fontSize: 22,
            fontWeight: 600,
            letterSpacing: -0.3,
            lineHeight: 1.25,
            color: heroFg,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {title}
        </div>
        <div style={{ marginTop: 4, color: heroSub, opacity: inClass ? 0.9 : 1, fontSize: 13 }}>
          {detail}
        </div>
      </div>
      {/* 右端放大号时间。原版在这里放「18:30 / 开始」——比一行小字更早被读到 */}
      {(ongoing ?? next) ? (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "flex-end",
            gap: 2,
            color: heroFg,
            flex: "0 0 auto",
          }}
        >
          <span style={{ fontSize: 26, fontWeight: 600, lineHeight: 1.1, letterSpacing: -0.4 }}>
            {(ongoing ?? next)!.entity.startTime}
          </span>
          {/* 原版这里恒为「课程开始时间」+ 标签「开始」——
              标签描述的是左边那个数字是什么，不是当前状态，所以上课中也不改字。 */}
          <span style={{ fontSize: 11, opacity: 0.85 }}>开始</span>
        </div>
      ) : null}
    </section>
  );
}

function TimelineRow({ item, now }: { item: TodayClass; now: number }) {
  const done = item.endMillis <= now;
  const active = ongoingAt(item, now);
  // 临时提醒（`date` 非空）在行尾标「临时」；正在上的课标「进行中」。
  // 两者互斥 —— 同一行同时出现两个标签只会让人猜哪个更重要。
  const temporary = Boolean(item.entity.date);
  const time = `${item.entity.startTime} – ${item.entity.endTime}`;
  return (
    <div className={`today-row${done ? " done" : ""}${active ? " active" : ""}`}>
      <span className="today-time">{time}</span>
      <span className="today-title" style={{ fontWeight: active ? 600 : 400 }}>
        {item.entity.title}
      </span>
      <span className="today-room">{item.entity.room || "—"}</span>
      <span className="today-teacher">{item.entity.teacher || "—"}</span>
      <span className="today-chip-cell">
        {active ? (
          <span className="chip accent">进行中</span>
        ) : temporary ? (
          <span className="chip warn">临时</span>
        ) : null}
      </span>
    </div>
  );
}
