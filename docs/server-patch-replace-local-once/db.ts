import Database from "better-sqlite3";
import fs from "node:fs";
import path from "node:path";

/**
 * StuMate 的独立数据库。
 *
 * 刻意**不复用**站点的 site.db（设计 v1.3 §6.1）：站点会被反复改版/重置，
 * 而用户的课表是真数据。用一个独立文件把爆炸半径隔开，代价只是这个模块。
 *
 * ⚠️ 绝对不要往 site.db 里写 StuMate 的表。
 */

const DB_PATH =
  process.env.STUMATE_DB_PATH ?? "/var/lib/deeer/stumate.db";

const globalForStumate = globalThis as unknown as {
  __stumateDb?: Database.Database;
};

const DDL = `
CREATE TABLE IF NOT EXISTS users (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  email             TEXT NOT NULL,              -- 存小写
  password_hash     TEXT,                       -- 第三方注册时可能为空
  nickname          TEXT NOT NULL DEFAULT '',
  initial_device_id INTEGER,                    -- 首个完成同步的设备（§5.8）
  created_at        TEXT NOT NULL,
  updated_at        TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_stumate_users_email ON users(email);

CREATE TABLE IF NOT EXISTS devices (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      INTEGER NOT NULL,
  name         TEXT NOT NULL,
  platform     TEXT NOT NULL,                   -- 'desktop' | 'android'
  last_seen_at TEXT,
  first_synced_at TEXT,                         -- 首次完成同步的时刻（§5.8 一次性 replace_local）
  created_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stumate_devices_user ON devices(user_id);

CREATE TABLE IF NOT EXISTS tokens (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  device_id  INTEGER NOT NULL,
  kind       TEXT NOT NULL,                     -- 'access' | 'refresh'
  token_hash TEXT NOT NULL UNIQUE,              -- 只存 SHA-256
  parent_id  INTEGER,                           -- 轮转链，用于重放检测
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_stumate_tokens_hash ON tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_stumate_tokens_user ON tokens(user_id, kind);

CREATE TABLE IF NOT EXISTS oauth_binding (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id        INTEGER NOT NULL,
  provider       TEXT NOT NULL,
  provider_uid   TEXT NOT NULL,
  provider_email TEXT,
  access_token   TEXT,
  refresh_token  TEXT,
  expires_at     TEXT,
  created_at     TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_stumate_oauth_provider_uid
  ON oauth_binding(provider, provider_uid);

CREATE TABLE IF NOT EXISTS oauth_state (
  state         TEXT PRIMARY KEY,               -- 一次性 state，用后即焚
  provider      TEXT NOT NULL,
  mode          TEXT NOT NULL,                  -- 'login' | 'bind'
  user_id       INTEGER,                        -- bind 模式下的目标用户
  code_verifier TEXT,
  status        TEXT NOT NULL,                  -- 'pending' | 'ok' | 'error'
  payload       TEXT,                           -- 完成后挂上令牌（JSON）
  error         TEXT,
  created_at    TEXT NOT NULL,
  expires_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS invite_codes (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  code        TEXT NOT NULL UNIQUE,             -- 存明文：生命周期短、管理员需要再查
  note        TEXT NOT NULL DEFAULT '',
  max_uses    INTEGER NOT NULL DEFAULT 1,
  used_count  INTEGER NOT NULL DEFAULT 0,
  expires_at  TEXT,
  revoked_at  TEXT,
  created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS invite_uses (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  code_id   INTEGER NOT NULL,
  user_id   INTEGER NOT NULL,
  used_at   TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_stumate_invite_uses_code
  ON invite_uses(code_id, user_id);

CREATE TABLE IF NOT EXISTS one_time_codes (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  purpose    TEXT NOT NULL,                     -- 本期只有 'reset'
  code_hash  TEXT NOT NULL,                     -- 存 SHA-256（与邀请码刻意相反）
  expires_at TEXT NOT NULL,
  used_at    TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stumate_otc_user ON one_time_codes(user_id, purpose);

-- 同步用（本期先建表，接口在下一阶段实现，见设计 §5）
CREATE TABLE IF NOT EXISTS records (
  user_id    INTEGER NOT NULL,
  entity     TEXT NOT NULL,                     -- 'class' | 'note'
  uid        TEXT NOT NULL,
  data       TEXT NOT NULL,                     -- JSON
  version    INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL,
  deleted_at TEXT,
  PRIMARY KEY (user_id, entity, uid)
);
CREATE INDEX IF NOT EXISTS idx_stumate_records_user_entity ON records(user_id, entity);

CREATE TABLE IF NOT EXISTS sync_log (
  revision   INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  entity     TEXT NOT NULL,
  uid        TEXT NOT NULL,
  op         TEXT NOT NULL,
  payload    TEXT,
  updated_at TEXT NOT NULL,
  deleted_at TEXT                           -- 后补列，见 migrate()
);
CREATE INDEX IF NOT EXISTS idx_stumate_sync_log_user_rev ON sync_log(user_id, revision);

CREATE TABLE IF NOT EXISTS stumate_meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`;

function createDb(): Database.Database {
  fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });
  const db = new Database(DB_PATH);
  db.pragma("journal_mode = WAL");
  db.pragma("synchronous = NORMAL");
  db.exec(DDL);
  migrate(db);
  return db;
}

/**
 * 补列迁移。
 *
 * ## 为什么需要
 *
 * 上面的 DDL 全是 `CREATE TABLE IF NOT EXISTS` —— 这条语句**只在表不存在时建表**，
 * 表已经存在时它什么都不做，**不会补上后来新增的列**。
 * 所以「给已有表加字段」这件事必须在这里显式做一遍。
 *
 * 这是唯一一处需要迁移的地方（设计 v1.3 §6.3 也提到过）：
 * SQLite 没有 `ADD COLUMN IF NOT EXISTS`，只能先查 `PRAGMA table_info` 再决定要不要 ALTER。
 */
function migrate(db: Database.Database): void {
  const columns = new Set(
    (db.prepare("PRAGMA table_info(sync_log)").all() as Array<{ name: string }>).map(
      (c) => c.name,
    ),
  );

  // sync_log.deleted_at —— 建表时漏了。没有它，pull 出来的删除记录看不出删除时间，
  // 客户端只能去解析 payload 找 deletedAt 字段。
  if (!columns.has("deleted_at")) {
    db.exec("ALTER TABLE sync_log ADD COLUMN deleted_at TEXT");
  }

  // devices.first_synced_at —— 见 §5.8 的「一次性 replace_local」。
  //
  // 加这列之前，`replace_local` 是 `isInitialDevice(userId, deviceId) === false`，
  // 而那个函数只比对 `users.initial_device_id` —— 对**非首端设备永远返回 false**。
  // 于是那台设备每一轮同步都收到 `replace_local = true`：客户端备份 → 清库 →
  // 从 cursor=0 全量重拉（实测一次启动落两个备份文件）。
  // 现在用「这台设备是否已经同步过一次」把它限定成**一次**。
  //
  // ⚠️ 已有行会得到 NULL = 「还没同步过」，于是每台老设备会在下次同步时**多清一次**库。
  // 这是可接受的（客户端会先备份），而且我们顺手把已在库里的设备都标记成已同步，
  // 避免老设备集体重清一遍。
  const deviceColumns = new Set(
    (db.prepare("PRAGMA table_info(devices)").all() as Array<{ name: string }>).map(
      (c) => c.name,
    ),
  );
  if (!deviceColumns.has("first_synced_at")) {
    db.exec("ALTER TABLE devices ADD COLUMN first_synced_at TEXT");
    // 补列当时**已经存在**的设备，说明它们此前已经同步过（否则不会出现在 devices 表里），
    // 一律按「已同步」处理，别让它们各清一次库。
    db.exec("UPDATE devices SET first_synced_at = COALESCE(last_seen_at, created_at)");
  }
}

export const sdb = globalForStumate.__stumateDb ?? createDb();
if (process.env.NODE_ENV !== "production") {
  globalForStumate.__stumateDb = sdb;
}

/** 统一时间格式：ISO8601 UTC 字符串（跨端比较时不要混时区） */
export function nowIso(): string {
  return new Date().toISOString();
}

/** 从现在起 offsetSeconds 秒后的 ISO 时间 */
export function isoIn(offsetSeconds: number): string {
  return new Date(Date.now() + offsetSeconds * 1000).toISOString();
}

export function getMeta(key: string, fallback = ""): string {
  const row = sdb
    .prepare("SELECT value FROM stumate_meta WHERE key = ?")
    .get(key) as { value: string } | undefined;
  return row?.value ?? fallback;
}

export function setMeta(key: string, value: string): void {
  sdb.prepare(
    `INSERT INTO stumate_meta (key, value) VALUES (?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
  ).run(key, value);
}
