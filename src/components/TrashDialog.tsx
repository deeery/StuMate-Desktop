/**
 * 回收站：列出 / 还原 / 彻底删除 / 清空。
 *
 * v1.7.14 之前删除只是把文件挪进 `.trash/`，界面上没有任何找回的口子，
 * 用户得自己钻进隐藏目录翻。Rust 侧现在每次删除都写 `index.json` 记住原路径，
 * 这里才敢提供「还原」—— 老版本删的条目没有元数据，标成「只能放回根目录」，
 * 不假装知道它原来在哪。
 *
 * 清空与彻底删除都会真的抹掉字节，所以两个动作都先在对话框内二次确认，
 * 不额外弹一层（Dialog 用的是 portal，套两层会互相抢焦点和 Esc）。
 */

import { useCallback, useEffect, useState } from "react";
import { api, type TrashItemWire } from "../api";
import { Dialog } from "./ui";
import { IconRefresh, IconRestore, IconTrash } from "./Icons";

type Confirm = { kind: "empty" } | { kind: "purge"; item: TrashItemWire } | null;

function timeText(ms: number): string {
  if (!ms) return "时间不详";
  return new Date(ms).toLocaleString("zh-CN", { hour12: false });
}

export function TrashDialog({
  base,
  onClose,
  onChanged,
  toast,
  leaving,
}: {
  base: string;
  onClose: () => void;
  /** 还原成功后让页面重扫一次树 */
  onChanged: () => Promise<void>;
  toast: (text: string, tone?: "info" | "error") => void;
  leaving?: boolean;
}) {
  const [items, setItems] = useState<TrashItemWire[]>([]);
  const [loading, setLoading] = useState(true);
  const [confirm, setConfirm] = useState<Confirm>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setItems(await api.vaultTrashList(base));
      setError(null);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }, [base]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const run = async (work: () => Promise<void>, done?: string) => {
    try {
      await work();
      if (done) toast(done);
      await reload();
      await onChanged();
      setConfirm(null);
    } catch (e) {
      setError(String(e));
    }
  };

  return (
    <Dialog
      title="回收站"
      subtitle="删除的笔记先放在这里，还原会放回原来的目录"
      onClose={onClose}
      width={560}
      leaving={leaving}
      actions={
        <>
          <button className="btn ghost" onClick={() => void reload()} title="重新读取">
            <IconRefresh size={13} /> 刷新
          </button>
          <span className="kb-grow" />
          <button
            className="btn danger"
            disabled={items.length === 0}
            onClick={() => setConfirm({ kind: "empty" })}
          >
            清空回收站
          </button>
          <button className="btn" onClick={onClose}>
            关闭
          </button>
        </>
      }
    >
      {confirm ? (
        <div className="kb-trash-confirm">
          <span>
            {confirm.kind === "empty"
              ? `真的清空回收站？${items.length} 项会被永久删除，无法找回。`
              : `真的彻底删除「${confirm.item.rel || confirm.item.name}」？这一步无法撤销。`}
          </span>
          <button className="btn" onClick={() => setConfirm(null)}>
            取消
          </button>
          <button
            className="btn danger"
            onClick={() => {
              if (confirm.kind === "empty") {
                void run(
                  async () => {
                    await api.vaultTrashEmpty(base);
                  },
                  "回收站已清空",
                );
              } else {
                const name = confirm.item.name;
                void run(
                  async () => {
                    await api.vaultTrashPurge(base, name);
                  },
                  "已彻底删除",
                );
              }
            }}
          >
            确认删除
          </button>
        </div>
      ) : null}

      {error ? <div className="kb-trash-error">{error}</div> : null}

      {loading ? (
        <div className="kb-trash-empty">正在读取…</div>
      ) : items.length === 0 ? (
        <div className="kb-trash-empty">
          <IconTrash size={22} />
          <span>回收站是空的</span>
        </div>
      ) : (
        <div className="kb-trash-list">
          {items.map((item) => (
            <div className="kb-trash-row" key={item.name}>
              <div className="kb-trash-meta">
                <div className="kb-trash-name">
                  {item.rel || item.name}
                  {item.isDir ? <span className="kb-trash-tag">文件夹</span> : null}
                  {item.orphan ? (
                    <span className="kb-trash-tag warn">老版本条目 · 只能还原到根目录</span>
                  ) : null}
                </div>
                <div className="kb-trash-time">删除于 {timeText(item.deletedAt)}</div>
              </div>
              <span className="kb-grow" />
              <button
                className="btn ghost sm"
                title="放回原目录（若已被占用会自动顺延）"
                onClick={() =>
                  void run(
                    async () => {
                      const rel = await api.vaultTrashRestore(base, item.name);
                      toast(`已还原到 ${rel}`);
                    },
                  )
                }
              >
                <IconRestore size={13} /> 还原
              </button>
              <button
                className="btn ghost sm danger-text"
                onClick={() => setConfirm({ kind: "purge", item })}
              >
                彻底删除
              </button>
            </div>
          ))}
        </div>
      )}
    </Dialog>
  );
}
