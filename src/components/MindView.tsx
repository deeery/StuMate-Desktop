/**
 * 导图视图：把 396 行的 `MindCanvas` 重新接进知识库页。
 *
 * 组件本身从 v1.7.7 起就没被任何文件 import 过（不是写得残缺，是入口没了）。
 * 这里负责三件事：给画布灌入**文件库**派生的节点（经 `vaultMind.ts` 适配）、
 * 把画布的编辑回调翻译成 `model/mindMap.ts` 的纯函数、提供缩放与跳转工具栏。
 *
 * 一条必须讲清楚的边界：**在导图上删节点不会删磁盘上的 .md**。节点是导图
 * 的结构，笔记是文件；删掉一个 entry 只是把它从图上摘下来，下次刷新扫描
 * 还会按 `reconcileMind` 补回去。要删文件请回列表视图用右键菜单。
 */

import { useMemo, useRef, useState } from "react";
import { MindCanvas, type MindCanvasHandle } from "../pages/MindCanvas";
import {
  addManualChild,
  buildMindTree,
  canReparent,
  deleteMindNode,
  MIND_NO_SUBJECT_LABEL,
  renameMindNode,
  reparentMindNode,
  toggleMindCollapsed,
} from "../model/mindMap";
import { pathOfVaultUid } from "../model/vaultMind";
import type { MindNode } from "../model/types";
import { IconExternal, IconMap, IconMinus, IconPlus, IconRefresh } from "./Icons";

export function MindView({
  nodes,
  onNodesChange,
  onOpenNote,
  onNewNote,
  onRebuild,
}: {
  nodes: MindNode[];
  onNodesChange: (nodes: MindNode[]) => void;
  onOpenNote: (path: string) => void;
  onNewNote: (dir: string) => void;
  onRebuild: () => void;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const canvas = useRef<MindCanvasHandle | null>(null);
  const tree = useMemo(() => buildMindTree(nodes), [nodes]);
  const selectedNode = selected ? tree.byUid.get(selected) : null;
  const entryPath =
    selectedNode?.kind === "entry"
      ? pathOfVaultUid(selectedNode.knowledgeUid ?? "")
      : null;

  const edit = (next: MindNode[]) => onNodesChange(next);

  return (
    <div className="kb-map">
      <div className="kb-map-bar">
        <span className="kb-map-hint">
          <IconMap size={13} /> 拖拽改父子 · 双击折叠 · 右键更多
        </span>
        <span className="kb-grow" />
        <button className="btn ghost sm" onClick={() => canvas.current?.zoomBy(-0.15)}>
          <IconMinus size={12} />
        </button>
        <button className="btn ghost sm" onClick={() => canvas.current?.fit()}>
          适应
        </button>
        <button className="btn ghost sm" onClick={() => canvas.current?.zoomBy(0.15)}>
          <IconPlus size={12} />
        </button>
        <span className="kb-map-sep" />
        <button className="btn ghost sm" onClick={onRebuild} title="按当前文件库重新派生一次">
          <IconRefresh size={12} /> 重建
        </button>
        <button
          className="btn ghost sm"
          disabled={!entryPath}
          title={entryPath ? `回到列表打开 ${entryPath}` : "先选中一个笔记节点"}
          onClick={() => {
            if (entryPath) onOpenNote(entryPath);
          }}
        >
          <IconExternal size={12} /> 打开笔记
        </button>
        <button
          className="btn ghost sm"
          title="在选中科目下新建一篇笔记（会切回列表）"
          onClick={() => {
            const raw = selectedNode?.kind === "subject" ? selectedNode.subject : "";
            onNewNote(raw === MIND_NO_SUBJECT_LABEL ? "" : raw);
          }}
        >
          <IconPlus size={12} /> 新建笔记
        </button>
      </div>

      <MindCanvas
        nodes={nodes}
        selectedUid={selected}
        onSelect={setSelected}
        onReparent={(uid, parentUid) => {
          if (canReparent(tree, uid, parentUid)) {
            edit(reparentMindNode(nodes, uid, parentUid));
          }
        }}
        onToggleCollapse={(uid) => edit(toggleMindCollapsed(nodes, uid))}
        onRename={(uid, label) => edit(renameMindNode(nodes, uid, label))}
        onDelete={(uid) => {
          edit(deleteMindNode(nodes, uid));
          setSelected(null);
        }}
        onAddChild={(parentUid) => {
          const label = window.prompt("新节点名称", "新节点");
          if (!label || !label.trim()) return;
          const result = addManualChild(nodes, parentUid, label.trim());
          edit(result.nodes);
          setSelected(result.uid);
        }}
        registerApi={(handle) => {
          canvas.current = handle;
        }}
      />

      <div className="kb-map-foot">
        导图结构存在 mindmap.json；在这里删节点只改图，不会删除库里的 .md 文件。
      </div>
    </div>
  );
}
