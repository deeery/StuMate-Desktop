/**
 * `Ctrl+P` 快速切换：输入名字直接跳，不用在树里一层层点。
 *
 * 与左侧搜索框的分工：搜索框查**正文**（会命中内容行），这里只查**名称与路径**。
 * 两者的使用场景不一样 —— 「我记得有这条」用这个，「我记得写过这句话」用搜索。
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { parentOf, matchScore, type TreeNode } from "../model/vaultTree";

const MAX_ROWS = 40;

export function QuickOpen({
  notes,
  leaving,
  onPick,
  onClose,
}: {
  notes: TreeNode[];
  leaving?: boolean;
  onPick: (path: string) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState("");
  const [index, setIndex] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  const results = useMemo(() => {
    const scored = notes
      .map((n) => {
        // 名称命中的权重高于路径命中：路径里往往有一长串相同的科目名，
        // 若同等对待，「找某一篇」会被同一科目下的所有笔记挤满。
        const byName = matchScore(query, n.name);
        const byPath = matchScore(query, n.path);
        const score = Math.max(byName, byPath < 0 ? -1 : byPath - 200);
        return { note: n, score };
      })
      .filter((r) => r.score >= 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, MAX_ROWS);
    return scored.map((r) => r.note);
  }, [notes, query]);

  // 查询一变，选中项回到第一条 —— 否则会出现「选中项停在第 9 行，结果只剩 2 行」
  useEffect(() => setIndex(0), [query]);

  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(`[data-idx="${index}"]`);
    el?.scrollIntoView({ block: "nearest" });
  }, [index]);

  return (
    <div className={`qo-mask${leaving ? " leaving" : ""}`} onMouseDown={onClose}>
      <div className="qo" onMouseDown={(e) => e.stopPropagation()}>
        <input
          className="qo-input"
          autoFocus
          placeholder="按名称跳转…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") {
              e.preventDefault();
              setIndex((i) => Math.min(i + 1, Math.max(0, results.length - 1)));
            } else if (e.key === "ArrowUp") {
              e.preventDefault();
              setIndex((i) => Math.max(0, i - 1));
            } else if (e.key === "Enter") {
              e.preventDefault();
              const hit = results[index];
              if (hit) onPick(hit.path);
            } else if (e.key === "Escape") {
              e.preventDefault();
              onClose();
            }
          }}
        />
        <div className="qo-list" ref={listRef}>
          {results.length === 0 ? (
            <div className="qo-none">{query ? "没有匹配的笔记" : "输入几个字开始找"}</div>
          ) : (
            results.map((n, i) => (
              <button
                key={n.path}
                data-idx={i}
                className={`qo-row${i === index ? " on" : ""}`}
                onMouseEnter={() => setIndex(i)}
                onClick={() => onPick(n.path)}
              >
                <span className="qo-name">{n.name}</span>
                <span className="qo-path">{parentOf(n.path) || "根目录"}</span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
