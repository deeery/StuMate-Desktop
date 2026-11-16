/**
 * 数据模型：与原版 Kotlin 实体**逐字段对齐**。
 *
 * 这些类型不是「前端自己的视图模型」，而是数据库行的直接映射 —— Lite 与原版
 * 共用同一个 `class_reminder.db`，字段名对不上就是写坏别人的数据。
 * 所以这里刻意保留 `dayOfWeek` / `updatedAt` 这类后端命名，不改成前端习惯的写法。
 */

export interface ClassEntity {
  id: number;
  title: string;
  /** "Monday" … "Sunday"，与库里存的英文名一致 */
  dayOfWeek: string;
  /** "HH:mm" */
  startTime: string;
  /** "HH:mm" */
  endTime: string;
  room: string;
  notes: string;
  teacher: string;
  /** 上课周次，如 "1-16周"、"第6周"、"1-8周,10-12周"；空 = 不限 */
  weeks: string;
  /** 临时提醒的具体日期 yyyy-MM-dd；空 = 每周重复的长期课程 */
  date: string;
  uid: string;
  updatedAt: number;
  deletedAt: number;
}

export interface NoteEntity {
  id: number;
  title: string;
  content: string;
  /** 越小越靠上；新建取「当前最小值 - 1」 */
  position: number;
  createdAt: number;
  colorIndex: number;
  typeIndex: number;
  customLabel: string;
  /** 截止时刻（epoch ms）。0 = 没设 */
  deadlineAt: number;
  uid: string;
  updatedAt: number;
  deletedAt: number;
}

/**
 * 便签 ↔ 课程 / 循环提醒的关联（Lite 专属，存 `note_links.json`）。
 *
 * 为什么不做成 `notes` 表的新列：库与原版 / 安卓共用，`bump schema` 会让安卓端
 * 的 `fallbackToDestructiveMigration()` 直接清库。关联是 Lite 独有的呈现层信息，
 * 放旁路文件既不动 schema，也不影响两侧读写同一批便签。
 */
export interface NoteLink {
  noteId: number;
  /** 关联课程 id；0 = 未关联 */
  classId: number;
  /** 关联循环提醒规则 id；空串 = 未关联 */
  ruleId: string;
}

export interface KnowledgeEntity {
  id: number;
  /** 科目名；空串 = 未分类 */
  subject: string;
  title: string;
  content: string;
  position: number;
  createdAt: number;
  uid: string;
  updatedAt: number;
  deletedAt: number;
}

export type MindNodeKind = "subject" | "entry" | "manual";

export interface MindNode {
  uid: string;
  /** 空串 = 直接挂在根上 */
  parentUid: string;
  label: string;
  kind: MindNodeKind;
  /** 仅 entry：挂接的知识条目键 */
  knowledgeUid: string;
  /** 仅 subject：科目名 */
  subject: string;
  position: number;
  collapsed: boolean;
  /** 派生节点被删后的墓碑，防止下次派生又冒出来 */
  suppressed: boolean;
}

export interface LinkRef {
  uid: string;
  /** 所属知识条目的 uid */
  entryUid: string;
  /** 本地绝对路径，或 http(s) 网址 */
  target: string;
  label: string;
  addedAt: number;
}

export type ThemeMode = 0 | 1 | 2; // 0 跟随系统 / 1 浅色 / 2 深色

export interface Prefs {
  advanceMinutes: number;
  autoStart: boolean;
  showPopup: boolean;
  firstRun: boolean;
  themeMode: ThemeMode;
  week1Monday: number;
  weekGrid: boolean;
  sidebarCollapsed: boolean;
  closeAction: number;
  notifyEnabled: boolean;
  autoCheckUpdate: boolean;
  notifyUpdate: boolean;
  /**
   * 知识库的**库根**（用户选的目录）。真正的库在它下面的 `Stumate/` 子目录里。
   * 空串 = 还没选，知识库页显示引导。
   */
  vaultRoot: string;
  /** 旧数据库条目是否已经铺成文件。只在第一次启用知识库时跑一次 */
  vaultMigrated: boolean;
  [key: string]: unknown;
}

export const EMPTY_CLASS: ClassEntity = {
  id: 0,
  title: "",
  dayOfWeek: "Monday",
  startTime: "08:00",
  endTime: "09:40",
  room: "",
  notes: "",
  teacher: "",
  weeks: "",
  date: "",
  uid: "",
  updatedAt: 0,
  deletedAt: 0,
};

export const EMPTY_NOTE: NoteEntity = {
  id: 0,
  title: "",
  content: "",
  position: 0,
  createdAt: 0,
  colorIndex: 0,
  typeIndex: 0,
  customLabel: "",
  deadlineAt: 0,
  uid: "",
  updatedAt: 0,
  deletedAt: 0,
};

export const EMPTY_KNOWLEDGE: KnowledgeEntity = {
  id: 0,
  subject: "",
  title: "",
  content: "",
  position: 0,
  createdAt: 0,
  uid: "",
  updatedAt: 0,
  deletedAt: 0,
};
