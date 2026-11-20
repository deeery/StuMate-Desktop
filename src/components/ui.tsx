/**
 * 通用控件：对话框、下拉、开关、Toast。
 *
 * 只做原版 FluentControls 里真正被用到的那些，不追求通用组件库 ——
 * 这一层的目的是让页面代码短、视觉统一，而不是再造一套设计系统。
 */

import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { IconCheck } from "./Icons";

// ── 对话框 ──────────────────────────────────────────────────────

export function Dialog({
  title,
  subtitle,
  children,
  actions,
  onClose,
  width,
  leaving,
}: {
  title: string;
  subtitle?: string;
  children?: ReactNode;
  actions?: ReactNode;
  onClose: () => void;
  width?: number;
  /** 退场中：遮罩与面板各播一段收起动画，由调用方的 `usePresence` 驱动 */
  leaving?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // onClose 是父组件内联的箭头函数，每次渲染都是新身份。它**不能**进 effect 的
  // deps：否则每敲一个字都会重跑下面这段，把焦点从输入框抢回 `.dialog` ——
  // 表现是「对话框里只能输入一个字符」，而且容器吃到 :focus-visible，
  // 整扇窗口被描上一圈强调色外环（v1.7.28 修）。用 ref 转一手拿最新闭包，
  // effect 只在挂载时跑一次。
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeRef.current();
    };
    window.addEventListener("keydown", onKey);
    // 打开时把焦点放进对话框，否则 Esc 会被页面上的输入框吞掉
    ref.current?.focus();
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return createPortal(
    <div
      className={`flyout-scrim${leaving ? " leaving" : ""}`}
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <div
        className={`dialog${leaving ? " leaving" : ""}`}
        style={width ? { width } : undefined}
        ref={ref}
        tabIndex={-1}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h3>{title}</h3>
        {subtitle ? <div className="dialog-sub">{subtitle}</div> : null}
        {children}
        {actions ? <div className="dialog-actions">{actions}</div> : null}
      </div>
    </div>,
    document.body,
  );
}

// ── 表单 ────────────────────────────────────────────────────────

export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  return (
    <div className="field">
      <label>{label}</label>
      {children}
      {hint ? <div style={{ fontSize: 11, color: "var(--on-surface-faint)" }}>{hint}</div> : null}
    </div>
  );
}

export function Select<T extends string | number>({
  value,
  options,
  onChange,
  disabled,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
  disabled?: boolean;
}) {
  return (
    <select
      className="input"
      style={{ cursor: disabled ? "default" : "pointer" }}
      value={value}
      disabled={disabled}
      onChange={(e) => {
        const raw = e.target.value;
        const match = options.find((o) => String(o.value) === raw);
        if (match) onChange(match.value);
      }}
    >
      {options.map((o) => (
        <option key={String(o.value)} value={String(o.value)}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

export function Toggle({
  checked,
  onChange,
  label,
  hint,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label: string;
  hint?: string;
}) {
  return (
    <label
      style={{
        display: "flex",
        alignItems: "center",
        gap: 12,
        padding: "10px 0",
        cursor: "pointer",
      }}
    >
      <span style={{ flex: 1 }}>
        <span style={{ display: "block" }}>{label}</span>
        {hint ? (
          <span style={{ display: "block", fontSize: 11, color: "var(--on-surface-faint)", marginTop: 2 }}>
            {hint}
          </span>
        ) : null}
      </span>
      <span
        // 纯 div/span 拼的开关，没有原生 checkbox：补上 role/aria 之后
        // 读屏和自动化脚本（CDP 验收）才能判断它是开还是关；
        // aria-label 用可见的那行字，省得读屏只念出「开关」。
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={(e) => {
          e.preventDefault();
          onChange(!checked);
        }}
        style={{
          width: 40,
          height: 20,
          borderRadius: 10,
          background: checked ? "var(--accent)" : "var(--outline-strong)",
          position: "relative",
          transition: "background 120ms cubic-bezier(0.33,0,0,1)",
          flex: "0 0 40px",
        }}
      >
        <span
          style={{
            position: "absolute",
            top: 3,
            left: checked ? 23 : 3,
            width: 14,
            height: 14,
            borderRadius: 7,
            background: checked ? "var(--on-accent)" : "var(--surface)",
            transition: "left 160ms cubic-bezier(0.33,0,0,1)",
          }}
        />
      </span>
    </label>
  );
}

/**
 * 勾选框。原版 `CloseConfirmDialog` 里的 `FlCheckbox` 是 16px 圆角方块 +
 * 选中时填充强调色并画一个对勾，这里照搬同一套观感。
 */
export function Checkbox({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label?: string;
}) {
  return (
    <div
      onClick={() => onChange(!checked)}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        cursor: "pointer",
        fontSize: 13,
        userSelect: "none",
      }}
    >
      <span
        style={{
          width: 16,
          height: 16,
          borderRadius: 3,
          flex: "0 0 16px",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: checked ? "var(--accent)" : "var(--surface)",
          border: `1px solid ${checked ? "var(--accent)" : "var(--outline-strong)"}`,
          transition: "background 120ms cubic-bezier(0.33,0,0,1)",
        }}
      >
        {checked ? <IconCheck size={11} style={{ color: "var(--on-accent)" }} /> : null}
      </span>
      {label ? <span>{label}</span> : null}
    </div>
  );
}

// ── Toast ───────────────────────────────────────────────────────
export interface ToastMessage {
  id: number;
  text: string;
  tone?: "info" | "error";
  /** 正在退场：先播 140ms 收起动画，再由 App 的定时器真正移除 */
  leaving?: boolean;
}

export function ToastHost({ toasts }: { toasts: ToastMessage[] }) {
  if (toasts.length === 0) return null;
  return createPortal(
    <div
      style={{
        position: "fixed",
        left: "50%",
        bottom: 28,
        transform: "translateX(-50%)",
        display: "flex",
        flexDirection: "column",
        gap: 8,
        zIndex: 90,
        pointerEvents: "none",
      }}
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          className={`toast-msg${t.leaving ? " leaving" : ""}`}
          style={{
            padding: "9px 16px",
            borderRadius: 6,
            background: t.tone === "error" ? "var(--error)" : "var(--surface)",
            color: t.tone === "error" ? "#fff" : "var(--on-surface)",
            border: t.tone === "error" ? "none" : "1px solid var(--outline)",
            boxShadow: "var(--shadow-flyout)",
            fontSize: 12,
            maxWidth: 520,
          }}
        >
          {t.text}
        </div>
      ))}
    </div>,
    document.body,
  );
}
