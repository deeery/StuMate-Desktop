/**
 * 后端命令封装。
 *
 * 所有跨进程调用都从这里出去，页面里不出现裸 `invoke("xxx")` —— 命令名一改，
 * 只有这一个文件需要跟着改，而且类型能一路传到页面。
 */

import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type {
  ClassEntity,
  KnowledgeEntity,
  LinkRef,
  MindNode,
  NoteEntity,
  Prefs,
} from "./model/types";
import { mindNodeFromDisk, mindNodeToDisk } from "./model/mindMap";
import type { MindNodeWire } from "./model/mindMap";
import type { ReminderAlert } from "./model/reminder";
import type { RecurringItem } from "./model/recurring";

/** 待落盘的一篇笔记：相对 vault 根的路径 + 全文 */
export interface VaultFilePayload {
  path: string;
  content: string;
}

/** Rust `vault::VaultReport` 的镜像。errors 只带前几条，够说明问题 */
export interface VaultReport {
  written: number;
  failed: number;
  dir: string;
  errors: string[];
}

/** 扫描出来的一项：路径相对 `<库根>/Stumate/` */
export interface VaultEntryWire {
  path: string;
  isDir: boolean;
  mtime: number;
  size: number;
}

export interface NoteContentWire {
  content: string;
  mtime: number;
}

/** `conflict: true` 表示文件在被读之后被外部改过，这次**没有写入** */
export interface WriteAckWire {
  conflict: boolean;
  mtime: number;
  path: string;
}

/** 全文搜索的一条命中 */
export interface GrepHitWire {
  path: string;
  count: number;
  preview: string;
}

/** `.trash` 里的一条。`orphan` = 老版本删的、没记原路径，只能放回根目录 */
export interface TrashItemWire {
  name: string;
  rel: string;
  deletedAt: number;
  isDir: boolean;
  orphan: boolean;
}

export interface AppInfo {
  version: string;
  flavor: string;
  dataDir: string;
  dbPath: string;
  schemaVersion: number;
  sharedWithOriginal: boolean;
  originalPresent: boolean;
  settingsPath: string;
  startPage: string | null;
  startView: string | null;
  startTheme: string | null;
}

export const api = {
  appInfo: () => invoke<AppInfo>("app_info"),

  // 课程表
  classes: () => invoke<ClassEntity[]>("classes_list"),
  saveClass: (entity: ClassEntity) => invoke<ClassEntity>("class_save", { entity }),
  deleteClass: (id: number) => invoke<void>("class_delete", { id }),
  clearClasses: () => invoke<void>("classes_clear"),

  // 便签
  notes: () => invoke<NoteEntity[]>("notes_list"),
  saveNote: (entity: NoteEntity) => invoke<NoteEntity>("note_save", { entity }),
  deleteNote: (id: number) => invoke<void>("note_delete", { id }),
  reorderNotes: (ids: number[]) => invoke<void>("notes_reorder", { ids }),
  clearNotes: () => invoke<void>("notes_clear"),

  // 知识库
  knowledge: () => invoke<KnowledgeEntity[]>("knowledge_list"),
  saveKnowledge: (entity: KnowledgeEntity) => invoke<KnowledgeEntity>("knowledge_save", { entity }),
  deleteKnowledge: (id: number) => invoke<void>("knowledge_delete", { id }),
  clearKnowledge: () => invoke<void>("knowledge_clear"),
  knowledgeCounts: () => invoke<[string, number][]>("knowledge_counts"),

  // 偏好
  prefs: () => invoke<Prefs>("prefs_get"),
  setPref: (key: string, value: unknown) => invoke<Prefs>("pref_set", { key, value }),

  // 导图 / 附件
  mindLoad: async () => {
    const raw = await invoke<{ version: number; nodes: MindNodeWire[] }>("mind_load");
    return { version: raw.version, nodes: raw.nodes.map(mindNodeFromDisk) };
  },
  mindSave: (nodes: MindNode[]) =>
    invoke<void>("mind_save", { nodes: nodes.map(mindNodeToDisk) }),
  linksLoad: () => invoke<{ version: number; links: LinkRef[] }>("links_load"),
  linksSave: (links: LinkRef[]) => invoke<void>("links_save", { links }),

  // 循环提醒（recurring.json，Lite 专属旁路存档）
  recurringLoad: () =>
    invoke<{ version: number; items: RecurringItem[] }>("recurring_load"),
  /** 整份写回。规则模型只有前端一份，Rust 不解析字段 */
  recurringSave: (items: RecurringItem[]) => invoke<void>("recurring_save", { items }),

  // 备份
  backupExport: (modules: string[], path: string) =>
    invoke<string>("backup_export", { modules, path }),
  backupInspect: (path: string) => invoke<unknown>("backup_inspect", { path }),
  backupImport: (path: string, modules: string[]) =>
    invoke<string>("backup_import", { path, modules }),

  // Obsidian 导出
  /** 选一个 vault 根目录。取消返回 null */
  pickVaultDir: () => invoke<string | null>("pick_vault_dir"),
  /** 把 `model/vault.ts` 铺好的文件落盘；路径校验与写入在 Rust 侧 */
  vaultExport: (root: string, files: VaultFilePayload[]) =>
    invoke<VaultReport>("vault_export", { root, files }),

  // ── 知识库（`<库根>/Stumate/` 子树）──────────────────────────────
  //
  // 全部命令的第一个参数都是**用户选的库根**，不是 `Stumate/` 本身：
  // 子目录半径由 Rust 决定，前端没有传入 `..` 之外的口子（那条也会被挡）。

  /** 解析出真正的库目录（不存在会建），用于显示与「打开目录」 */
  vaultRoot: (base: string) => invoke<string>("vault_root", { base }),
  vaultScan: (base: string) => invoke<VaultEntryWire[]>("vault_scan", { base }),
  /** 树的版本号；轮询它，变了才重扫 */
  vaultRev: (base: string) => invoke<string>("vault_rev", { base }),
  vaultRead: (base: string, rel: string) =>
    invoke<NoteContentWire>("vault_read", { base, rel }),
  /** `expected` = 打开时读到的 mtime；不一致时返回 conflict 且不写盘 */
  vaultWrite: (base: string, rel: string, content: string, expected: number | null) =>
    invoke<WriteAckWire>("vault_write", { base, rel, content, expected }),
  vaultMkdir: (base: string, rel: string) => invoke<void>("vault_mkdir", { base, rel }),
  vaultRename: (base: string, from: string, to: string) =>
    invoke<void>("vault_rename", { base, from, to }),
  /** 删除 = 挪进 `<库>/Stumate/.trash/`，返回落点 */
  vaultTrash: (base: string, rel: string) => invoke<string>("vault_trash", { base, rel }),
  // 回收站（v1.7.14）：删除只是挪走，这四个才让界面能真的找回 / 清掉
  vaultTrashList: (base: string) => invoke<TrashItemWire[]>("vault_trash_list", { base }),
  /** 返回实际还原到的相对路径（目标被占时会顺延成「 2」） */
  vaultTrashRestore: (base: string, name: string) =>
    invoke<string>("vault_trash_restore", { base, name }),
  vaultTrashPurge: (base: string, name: string) =>
    invoke<void>("vault_trash_purge", { base, name }),
  vaultTrashEmpty: (base: string) => invoke<number>("vault_trash_empty", { base }),
  /** 全文搜索（大小写不敏感）。不做索引：几百个文件直扫是一两毫秒的事 */
  vaultGrep: (base: string, needle: string) =>
    invoke<GrepHitWire[]>("vault_grep", { base, needle }),
  /** 同进程再开一个「只有知识库」的窗口（`?page=knowledge&solo=1`）。已开则聚焦 */
  openKnowledgeWindow: () => invoke<void>("open_knowledge_window"),
  /**
   * 另开一个 Markdowner 窗口编辑某篇笔记。
   *
   * 它不是「把内容传进去再传回来」，而是**直接读写库里的那个 .md 文件**
   * （见 `public/markdowner/stumate-bridge.js`）。所以这边不需要接收返回内容，
   * 写完由 `onVaultChanged` 通知刷新。
   */
  openMarkdownWindow: (base: string, rel: string) =>
    invoke<void>("open_markdown_window", { base, rel }),

  // 系统
  openDataDir: () => invoke<void>("open_data_dir"),  openExternal: (target: string) => invoke<void>("open_external", { target }),
  pathExists: (path: string) => invoke<boolean>("path_exists", { path }),
  pickSavePath: (defaultName: string) =>
    invoke<string | null>("pick_save_path", { defaultName }),
  pickOpenPath: () => invoke<string | null>("pick_open_path"),
  pickAttachment: () => invoke<string | null>("pick_attachment"),
  pickPdfPath: () => invoke<string | null>("pick_pdf_path"),
  /** 读原始字节；课表 PDF 的解析在前端做 */
  readFileBytes: (path: string) => invoke<number[]>("read_file_bytes", { path }),

  // 关闭行为 / 开机自启
  /** 关闭确认框里选了哪一档：0 询问 / 1 最小化到托盘 / 2 退出 */
  closeResolve: (action: number, remember: boolean) =>
    invoke<void>("close_resolve", { action, remember }),
  autostartGet: () => invoke<boolean>("autostart_get"),
  /** 返回**实际**写入结果，失败要提示用户 */
  autostartSet: (enabled: boolean) => invoke<boolean>("autostart_set", { enabled }),

  // 提醒卡（独立窗口）与托盘状态
  overlayShow: (alert: ReminderAlert) => invoke<void>("overlay_show", { alert }),
  /** 窗口新建时事件会丢，页面挂载后主动取一帧 */
  overlayState: () => invoke<ReminderAlert | null>("overlay_state"),
  overlayHide: () => invoke<void>("overlay_hide"),
  /** 浮窗「跳过本次」：把规则 id 广播回主窗口推进相位，随后收起卡片 */
  overlaySkip: (ruleId: string) => invoke<void>("overlay_skip", { ruleId }),
  /** 当前被「全屏游戏 / 演示模式 / 免打扰」拦住的原因；null = 可以提醒 */
  notifyBlocked: () => invoke<string | null>("notify_blocked"),
  trayStatus: (title: string, detail: string) => invoke<void>("tray_status", { title, detail }),
};

/** 提醒内容更新（窗口已存在时走这条路，不重建窗口） */
export function onOverlayUpdate(handler: (alert: ReminderAlert) => void): Promise<UnlistenFn> {
  return listen<ReminderAlert>("stumate://overlay-update", (e) => handler(e.payload));
}

/** 托盘菜单「立即检查一次」 */
export function onCheckNow(handler: () => void): Promise<UnlistenFn> {
  return listen("stumate://check-now", () => handler());
}

/** 浮窗上点了「跳过本次」，由主窗口收下并推进该规则的相位 */
export function onRecurringSkip(
  handler: (payload: { ruleId: string }) => void,
): Promise<UnlistenFn> {
  return listen<{ ruleId: string }>("stumate://recurring-skip", (e) => handler(e.payload));
}

/**
 * Markdowner 子窗口把某篇笔记写回磁盘了。
 *
 * 主窗口必须知道这件事：它的外部变更检测是「磁盘 mtime 比上次保存的更新就弹冲突」，
 * 不加这条，用户每在 Markdowner 里敲一次，主窗口就会误报一次「文件被别处改过」。
 */
export function onVaultChanged(
  handler: (payload: { rel: string; mtime: number }) => void,
): Promise<UnlistenFn> {
  return listen<{ rel: string; mtime: number }>("stumate://vault-changed", (e) =>
    handler(e.payload),
  );
}

/**
 * 关闭请求的订阅。
 *
 * Rust 端在 `closeAction == 0`（询问）时会拦下关闭并发这个事件 ——
 * 事件名里的 `stumate://` 前缀是自己加的命名空间，避免和 Tauri 内置事件撞名。
 */
export function onCloseRequested(handler: () => void): Promise<UnlistenFn> {
  return listen("stumate://close-requested", () => handler());
}

/** 把后端抛回来的错误转成人话。Tauri 的 reject 值是字符串或对象，这里统一。 */
export function errorText(err: unknown): string {
  if (typeof err === "string") return err;
  if (err instanceof Error) return err.message;
  try {
    return JSON.stringify(err);
  } catch {
    return String(err);
  }
}
