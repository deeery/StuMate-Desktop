/**
 * 知识库左侧的资源管理器（树）。
 *
 * 纯展示 + 交互回调：折叠状态、重命名草稿、右键菜单**都不在这里持有**——
 * 它们和「当前打开的笔记」「未保存的正文」是同一份状态，分散在两个组件里
 * 必然出现「树已经改名了、编辑器还指着旧路径」这种对不上的情况。
 * 这个文件只负责把树画出来，以及把点击翻译成回调。
 */

import { IconChevronDown, IconChevronRight, IconFile, IconFolder } from "./Icons";
import type { TreeNode } from "../model/vaultTree";

const INDENT = 12;
const BASE_PAD = 8;

export function VaultTree({
  tree,
  current,
  collapsed,
  renaming,
  onToggle,
  onOpen,
  onMenu,
  onRenameChange,
  onRenameCommit,
  onRenameCancel,
}: {
  tree: TreeNode[];
  current: string | null;
  collapsed: Set<string>;
  renaming: { path: string; value: string } | null;
  onToggle: (path: string) => void;
  onOpen: (path: string) => void;
  onMenu: (e: React.MouseEvent, node: TreeNode) => void;
  onRenameChange: (value: string) => void;
  onRenameCommit: () => void;
  onRenameCancel: () => void;
}) {
  if (tree.length === 0) {
    return (
      <div className="kb-empty">
        <div>这个库里还没有笔记</div>
        <div style={{ fontSize: 11.5, color: "var(--on-surface-faint)", marginTop: 4 }}>
          用上面的 ＋ 建一条，或者在库里放几个 .md 文件
        </div>
      </div>
    );
  }

  const rows: React.ReactNode[] = [];

  const walk = (nodes: TreeNode[], depth: number) => {
    for (const node of nodes) {
      const isFolder = node.kind === "folder";
      const isCollapsed = isFolder && collapsed.has(node.path);
      const isRenaming = renaming?.path === node.path;

      rows.push(
        <div
          key={node.path}
          className={`kb-row${current === node.path ? " on" : ""}`}
          style={{ paddingLeft: BASE_PAD + depth * INDENT }}
          onClick={() => (isFolder ? onToggle(node.path) : onOpen(node.path))}
          onContextMenu={(e) => onMenu(e, node)}
          title={node.path}
        >
          <span className="kb-caret">
            {isFolder ? (
              isCollapsed ? (
                <IconChevronRight size={12} />
              ) : (
                <IconChevronDown size={12} />
              )
            ) : null}
          </span>
          <span className="kb-glyph">{isFolder ? <IconFolder size={14} /> : <IconFile size={14} />}</span>
          {isRenaming ? (
            <input
              className="kb-rename"
              autoFocus
              value={renaming!.value}
              onChange={(e) => onRenameChange(e.target.value)}
              onClick={(e) => e.stopPropagation()}
              onBlur={onRenameCommit}
              onFocus={(e) => e.currentTarget.select()}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  onRenameCommit();
                } else if (e.key === "Escape") {
                  e.preventDefault();
                  onRenameCancel();
                }
              }}
            />
          ) : (
            <span className="kb-name">{node.name}</span>
          )}
        </div>,
      );

      if (isFolder && !isCollapsed) walk(node.children, depth + 1);
    }
  };

  walk(tree, 0);
  return <>{rows}</>;
}
