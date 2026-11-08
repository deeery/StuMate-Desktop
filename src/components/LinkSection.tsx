/**
 * 知识笔记的「相关文件」。
 *
 * v1.7.7 换成文件库之后，`knowledge_links.json` 的读写、命令注册、全局装载
 * 全都还在，唯独界面入口没了 —— 这个组件就是把它接回来的那一截。
 *
 * 附件按 `entryUid` 挂，绑的是笔记 frontmatter 里的 `stumate_uid`，不是路径：
 * 改文件名、移动目录都不该让附件掉线。文件库下 uid 在写盘的那一刻就有，
 * 所以原版「新建态禁用附件」那条限制在这里不适用。
 */

import { useMemo } from "react";
import { api } from "../api";
import { IconClose, IconExternal, IconLink, IconPlus } from "./Icons";
import type { LinkRef } from "../model/types";

function newUid(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 没写 label 时从路径或网址里截一段能看的出来 */
export function linkLabelOf(link: LinkRef): string {
  if (link.label.trim()) return link.label;
  const normalized = link.target.replace(/\\/g, "/");
  return normalized.slice(normalized.lastIndexOf("/") + 1) || normalized;
}

export function isWebLink(target: string): boolean {
  return /^https?:\/\//i.test(target);
}

export function LinkSection({
  uid,
  links,
  onSave,
  onError,
  hint,
}: {
  uid: string;
  links: LinkRef[];
  onSave: (next: LinkRef[]) => Promise<void>;
  onError: (message: string) => void;
  /** 例如「这篇笔记没有 frontmatter uid，附件暂按路径绑定」 */
  hint?: string;
}) {
  const mine = useMemo(
    () => links.filter((l) => l.entryUid === uid),
    [links, uid],
  );

  const add = async (target: string | null | undefined) => {
    if (!target || !target.trim()) return;
    const next: LinkRef = {
      uid: newUid(),
      entryUid: uid,
      target: target.trim(),
      label: "",
      addedAt: Date.now(),
    };
    try {
      await onSave([...links, next]);
    } catch (e) {
      onError(String(e));
    }
  };

  const remove = async (linkUid: string) => {
    try {
      await onSave(links.filter((l) => l.uid !== linkUid));
    } catch (e) {
      onError(String(e));
    }
  };

  const open = async (target: string) => {
    try {
      await api.openExternal(target);
    } catch (e) {
      onError(String(e));
    }
  };

  return (
    <div className="kb-links">
      <div className="kb-links-head">
        <IconLink size={13} />
        <span>相关文件</span>
        <span className="kb-links-count">{mine.length}</span>
        <span className="kb-grow" />
        <button
          className="btn ghost sm"
          title="选一个本地文件挂到这篇笔记上"
          onClick={async () => {
            try {
              await add(await api.pickAttachment());
            } catch (e) {
              onError(String(e));
            }
          }}
        >
          <IconPlus size={12} /> 本地文件
        </button>
        <button
          className="btn ghost sm"
          title="挂一个网址"
          onClick={() => void add(window.prompt("输入网址（http:// 或 https://）", "https://"))}
        >
          <IconPlus size={12} /> 网址
        </button>
      </div>

      {hint ? <div className="kb-links-hint">{hint}</div> : null}

      {mine.length === 0 ? (
        <div className="kb-links-empty">还没有附件。本地文件与网址都可以挂在这篇笔记上。</div>
      ) : (
        <div className="kb-links-list">
          {mine.map((link) => (
            <div className="kb-link-row" key={link.uid}>
              <span className={`kb-link-kind${isWebLink(link.target) ? " web" : ""}`}>
                {isWebLink(link.target) ? "网址" : "文件"}
              </span>
              <button
                className="kb-link-name"
                title={link.target}
                onClick={() => void open(link.target)}
              >
                {linkLabelOf(link)}
                <IconExternal size={11} />
              </button>
              <span className="kb-grow" />
              <button
                className="kb-tool"
                title="移除这个附件（不会删除文件本身）"
                onClick={() => void remove(link.uid)}
              >
                <IconClose size={12} />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
