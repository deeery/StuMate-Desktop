/**
 * 循环提醒页的规则面板：规则列表 + 新建/编辑对话框。
 *
 * 三条约束，改这里之前先读：
 *
 * 1. **规则模型只有一份**。本组件只收表单、调 `ctx.saveRecurring`；相位计算、
 *    合法性收敛全在 `model/recurring.ts`，不在 UI 里重写一遍。
 * 2. **「立即试一次」绝不改相位**。构造一帧 alert 直接弹，帧里带的是
 *    `RECURRING_PREVIEW_ID` —— 浮窗照样显示「跳过本次」、IPC 通路照样验得到，
 *    但主窗口找不到这条规则，`lastFiredAt` 一个字节都不会写。
 * 3. **v1.7.16 起这里是侧栏独立页的主体**（v1.7.15 时是设置页的一个 Section）。
 *    页面标题交给 `PageTopBar`，本组件只画卡片内的规则列表；设置页只留一个
 *    跳转入口，不再把整张列表渲染两遍。
 */

import { useEffect, useRef, useState } from "react";
import type { AppContextValue } from "../App";
import { api, errorText } from "../api";
import { Dialog, Field, Select, Toggle } from "./ui";
import { usePresence } from "./Presence";
import {
  RECURRING_PREVIEW_ID,
  dateInputToMillis,
  defaultRule,
  describeNext,
  describeRule,
  millisToDateInput,
  normalizeRule,
  recurringAlert,
  ruleModeOf,
  startOfDay,
  upcomingFireAt,
  type RecurringItem,
  type RecurringRule,
  type RuleMode,
} from "../model/recurring";

const WEEKDAYS: { value: number; label: string }[] = [
  { value: 1, label: "一" },
  { value: 2, label: "二" },
  { value: 3, label: "三" },
  { value: 4, label: "四" },
  { value: 5, label: "五" },
  { value: 6, label: "六" },
  { value: 0, label: "日" },
];

interface Draft {
  id: string;
  title: string;
  body: string;
  enabled: boolean;
  mode: RuleMode;
  at: string;
  weekdays: number[];
  days: number;
  minutes: number;
  endDate: string;
  anchor: number;
  anchorDay: number;
  createdAt: number;
  lastFiredAt: number;
  /** 打开对话框时的规律类型；改类型要重置相位，否则旧 lastFiredAt 落在新规则上会错位 */
  originalMode: RuleMode | null;
}

function newDraft(now: number): Draft {
  return {
    id: "",
    title: "",
    body: "",
    enabled: true,
    mode: "daily",
    at: "08:00",
    weekdays: [new Date(now).getDay()],
    days: 2,
    minutes: 45,
    endDate: "",
    anchor: now,
    anchorDay: startOfDay(now),
    createdAt: now,
    lastFiredAt: 0,
    originalMode: null,
  };
}

function draftOf(item: RecurringItem): Draft {
  const rule = item.rule;
  return {
    id: item.id,
    title: item.title,
    body: item.body,
    enabled: item.enabled,
    mode: ruleModeOf(rule),
    at: rule.kind === "interval" ? "08:00" : rule.at,
    weekdays: rule.kind === "weekly" ? rule.weekdays : [],
    days: rule.kind === "everyDays" ? rule.days : 2,
    minutes: rule.kind === "interval" ? rule.minutes : 45,
    endDate: item.endAt ? millisToDateInput(item.endAt) : "",
    anchor: rule.kind === "interval" ? rule.anchor : Date.now(),
    anchorDay: rule.kind === "everyDays" ? rule.anchorDay : startOfDay(Date.now()),
    createdAt: item.createdAt,
    lastFiredAt: item.lastFiredAt,
    originalMode: ruleModeOf(rule),
  };
}

function makeId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `r-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
  }
}

function itemOf(d: Draft, now: number): RecurringItem {
  let rule: RecurringRule;
  switch (d.mode) {
    case "daily":
      rule = { kind: "weekly", weekdays: [], at: d.at };
      break;
    case "weekly":
      rule = { kind: "weekly", weekdays: d.weekdays.length ? d.weekdays : [1], at: d.at };
      break;
    case "everyDays":
      rule = { kind: "everyDays", days: d.days, at: d.at, anchorDay: d.anchorDay };
      break;
    case "interval":
      rule = { kind: "interval", minutes: d.minutes, anchor: d.anchor };
      break;
  }
  const modeChanged = d.originalMode !== null && d.originalMode !== d.mode;
  return {
    id: d.id || makeId(),
    title: d.title.trim() || "未命名提醒",
    body: d.body.trim(),
    enabled: d.enabled,
    rule: normalizeRule(rule, now),
    endAt: dateInputToMillis(d.endDate),
    // 改了规律类型就当新规则重新对齐，别把旧相位套到新序列上
    lastFiredAt: modeChanged ? 0 : d.lastFiredAt,
    createdAt: d.createdAt || now,
  };
}

export function RecurringSection({ ctx }: { ctx: AppContextValue }) {
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  // 对话框的退场保留：关掉之后还要画 120ms，用 ref 记住最后一份可渲染表单
  const presence = usePresence(draft !== null);
  const retained = useRef<Draft | null>(null);
  if (draft) retained.current = draft;
  const shown = draft ?? retained.current;

  // 「下次触发」是相对时间，不刷就不会从「45 分钟后」变成「30 分钟后」
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(id);
  }, []);

  const patch = (p: Partial<Draft>) => setDraft((d) => (d ? { ...d, ...p } : d));

  const write = async (next: RecurringItem[]) => {
    try {
      await ctx.saveRecurring(next);
      setError(null);
    } catch (e) {
      const msg = errorText(e);
      setError(msg);
      ctx.toast(msg, "error");
      throw e;
    }
  };

  const saveDraft = async () => {
    if (!draft) return;
    const item = itemOf(draft, Date.now());
    const exists = ctx.recurring.some((r) => r.id === item.id);
    const next = exists
      ? ctx.recurring.map((r) => (r.id === item.id ? item : r))
      : [...ctx.recurring, item];
    try {
      await write(next);
      setDraft(null);
      ctx.toast(exists ? "规则已更新" : "规则已创建");
    } catch {
      /* write 已经提示过 */
    }
  };

  const remove = async (id: string) => {
    if (confirmDelete !== id) {
      setConfirmDelete(id);
      return;
    }
    setConfirmDelete(null);
    try {
      await write(ctx.recurring.filter((r) => r.id !== id));
      ctx.toast("规则已删除");
    } catch {
      /* write 已经提示过 */
    }
  };

  const toggle = async (id: string, enabled: boolean) => {
    try {
      await write(ctx.recurring.map((r) => (r.id === id ? { ...r, enabled } : r)));
    } catch {
      /* write 已经提示过 */
    }
  };

  const tryOnce = async (item: RecurringItem) => {
    try {
      // 假 id 让浮窗仍显示「跳过本次」，但主窗口找不到规则 → 不写 lastFiredAt
      await api.overlayShow(recurringAlert({ ...item, id: RECURRING_PREVIEW_ID }, Date.now()));
    } catch (e) {
      ctx.toast(`浮窗打不开：${errorText(e)}`, "error");
    }
  };

  return (
    <>
      <section className="card" style={{ padding: "14px 16px 10px", marginBottom: 12 }}>
        <div className="rec-head">
          <div>
            <div className="rec-head-title">规则列表</div>
            <div className="rec-head-hint">
              按设定规律重复提醒；错过的不补弹，下一次始终按原相位触发。规则只保存在本机 Lite，导出备份时会带上
            </div>
          </div>
          <button className="btn primary" onClick={() => setDraft(newDraft(Date.now()))}>
            新建规则
          </button>
        </div>

        {ctx.recurring.length === 0 ? (
          <div className="empty" style={{ padding: "18px 0" }}>
            还没有规则 · 比如「每天 22:00 复盘」或「每 45 分钟起身活动」
          </div>
        ) : (
          <div className="rec-list">
            {ctx.recurring.map((item) => {
              const next = upcomingFireAt(item, now);
              return (
                <div key={item.id} className={`rec-row${item.enabled ? "" : " rec-off"}`}>
                  <div className="rec-main">
                    <div className="rec-title">{item.title}</div>
                    <div className="rec-meta">
                      {describeRule(item.rule)} · 下次 {describeNext(next, now)}
                      {item.endAt ? ` · 到 ${millisToDateInput(item.endAt)} 结束` : ""}
                    </div>
                    {item.body ? <div className="rec-body">{item.body}</div> : null}
                  </div>
                  <div className="rec-actions">
                    <label className="rec-switch" title={item.enabled ? "已启用" : "已停用"}>
                      <input
                        type="checkbox"
                        checked={item.enabled}
                        onChange={(e) => void toggle(item.id, e.target.checked)}
                      />
                      <span>{item.enabled ? "开" : "关"}</span>
                    </label>
                    <button className="btn ghost sm" onClick={() => void tryOnce(item)}>
                      立即试一次
                    </button>
                    <button className="btn ghost sm" onClick={() => setDraft(draftOf(item))}>
                      编辑
                    </button>
                    <button
                      className={`btn ghost sm${confirmDelete === item.id ? " danger-text" : ""}`}
                      onClick={() => void remove(item.id)}
                    >
                      {confirmDelete === item.id ? "确定删除？" : "删除"}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {error ? <div className="rec-error">{error}</div> : null}
      </section>

      {presence.mounted && shown ? (
        <RuleDialog
          leaving={presence.leaving}
          draft={shown}
          patch={patch}
          onClose={() => setDraft(null)}
          onSave={() => void saveDraft()}
        />
      ) : null}
    </>
  );
}

function RuleDialog({
  draft,
  patch,
  leaving,
  onClose,
  onSave,
}: {
  draft: Draft;
  patch: (p: Partial<Draft>) => void;
  leaving?: boolean;
  onClose: () => void;
  onSave: () => void;
}) {
  const isNew = draft.id === "";
  const calendar = draft.mode !== "interval";

  return (
    <Dialog
      leaving={leaving}
      title={isNew ? "新建循环提醒" : "编辑循环提醒"}
      subtitle="错过的不补弹；下一次始终按原相位触发"
      width={480}
      onClose={onClose}
      actions={
        <>
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" onClick={onSave}>
            {isNew ? "创建" : "保存"}
          </button>
        </>
      }
    >
      <div className="rec-form">
        <Field label="标题">
          <input
            className="input"
            value={draft.title}
            placeholder="例如：起身活动 / 交作业"
            onChange={(e) => patch({ title: e.target.value })}
          />
        </Field>
        <Field label="提醒正文" hint="浮窗副标题，可留空">
          <input
            className="input"
            value={draft.body}
            placeholder="例如：别一直坐着"
            onChange={(e) => patch({ body: e.target.value })}
          />
        </Field>
        <Field label="规律">
          <Select<RuleMode>
            value={draft.mode}
            options={[
              { value: "daily", label: "每天固定时刻" },
              { value: "weekly", label: "每周指定几天" },
              { value: "everyDays", label: "每 N 天" },
              { value: "interval", label: "每隔 N 分钟 / 小时" },
            ]}
            onChange={(mode) => {
              const fallback = defaultRule(mode, Date.now());
              patch({
                mode,
                ...(fallback.kind === "everyDays" ? { days: fallback.days } : {}),
                ...(fallback.kind === "interval" ? { minutes: fallback.minutes } : {}),
              });
            }}
          />
        </Field>

        {draft.mode === "weekly" ? (
          <Field label="星期" hint="至少选一天">
            <div className="rec-weekdays">
              {WEEKDAYS.map((d) => (
                <label
                  key={d.value}
                  className={`rec-weekday${draft.weekdays.includes(d.value) ? " on" : ""}`}
                >
                  <input
                    type="checkbox"
                    checked={draft.weekdays.includes(d.value)}
                    onChange={(e) =>
                      patch({
                        weekdays: e.target.checked
                          ? [...draft.weekdays, d.value].sort()
                          : draft.weekdays.filter((w) => w !== d.value),
                      })
                    }
                  />
                  {d.label}
                </label>
              ))}
            </div>
          </Field>
        ) : null}

        {calendar ? (
          <div className="rec-inline">
            {draft.mode === "everyDays" ? (
              <Field label="每隔几天">
                <input
                  className="input"
                  type="number"
                  min={1}
                  max={365}
                  value={draft.days}
                  onChange={(e) => patch({ days: Number(e.target.value) || 1 })}
                />
              </Field>
            ) : null}
            <Field label="触发时刻">
              <input
                className="input"
                type="time"
                value={draft.at}
                onChange={(e) => patch({ at: e.target.value })}
              />
            </Field>
          </div>
        ) : (
          <Field label="间隔（分钟）" hint="60 分钟 = 每 1 小时；1440 分钟 = 每 1 天">
            <input
              className="input"
              type="number"
              min={1}
              value={draft.minutes}
              onChange={(e) => patch({ minutes: Number(e.target.value) || 1 })}
            />
          </Field>
        )}

        <Field label="结束日期" hint="留空表示一直循环；结束当天仍会触发">
          <input
            className="input"
            type="date"
            value={draft.endDate}
            onChange={(e) => patch({ endDate: e.target.value })}
          />
        </Field>
        <Toggle
          label="启用这条规则"
          checked={draft.enabled}
          onChange={(enabled) => patch({ enabled })}
        />
      </div>
    </Dialog>
  );
}
