/**
 * 用一份 StuMate 备份文件**完全覆盖**某个账号在服务端的同步数据。
 *
 * 只动 `records` 与 `sync_log` 两张表里 `user_id = N` 的行，别的用户、别的表不碰。
 *
 * ## 为什么不能走 API / 客户端
 *
 * `push` 是 LWW：只比 `updatedAt`，且**不会删**服务端有、客户端没有的记录。
 * 所以「把服务端变成这份备份的样子」在 API 层面做不到 —— 必须直接写库。
 *
 * ## 写入形状必须和客户端推上来的完全一致
 *
 * - `records.data` = **裸记录 JSON**（客户端 `oneItem()` 拆掉了 `{count,items}` 包装）
 * - `records.updated_at` = `new Date(item.updatedAt).toISOString()`
 * - `records.deleted_at` = `item.deletedAt > 0 ? ISO : NULL`
 * - `sync_log` 每个 uid 一条，op 由 `deletedAt` 决定
 *
 * ## 为什么要删掉旧的 sync_log
 *
 * `sync_log` 是「原始变更流水」，客户端 `cursor = 0` 时会按顺序重放。
 * 留着旧流水的话，新设备会先重放出**旧状态**、再被新条目覆盖 ——
 * 虽然最终态正确，但中间会短暂出现已经被删掉的课。
 * 删掉重灌，任何游标的客户端拉到的都是这份备份的直接结果。
 *
 * 用法：
 *   NODE_PATH=/var/www/deeer/node_modules node stumate-overwrite.cjs <备份文件> [--apply]
 *   不带 --apply 是 dry-run，只打印差异、不写库。
 */
const Module = require("module");
process.env.NODE_PATH = process.env.NODE_PATH || "/var/www/deeer/node_modules";
Module._initPaths();

const fs = require("fs");
const Database = require("better-sqlite3");

const DB_PATH = process.env.STUMATE_DB_PATH || "/var/lib/deeer/stumate.db";
const USER_ID = Number(process.env.STUMATE_USER_ID || 3);

const argv = process.argv.slice(2);
const APPLY = argv.includes("--apply");
const backupPath = argv.find((a) => !a.startsWith("--"));

if (!backupPath) {
  console.error("用法: node stumate-overwrite.cjs <备份文件> [--apply]");
  process.exit(2);
}

const doc = JSON.parse(fs.readFileSync(backupPath, "utf8"));
if (doc.format !== "stumate-backup") {
  throw new Error(`不是 StuMate 备份文件（format=${doc.format}）`);
}

const MODULES = [
  ["courses", "class"],
  ["notes", "note"],
];
const items = [];
for (const [mod, entity] of MODULES) {
  const box = doc.modules?.[mod];
  if (!box) continue;
  for (const item of box.items) {
    if (!item.uid) {
      console.warn(`  ! 跳过没有 uid 的 ${entity}`);
      continue;
    }
    items.push({ entity, item });
  }
}

const db = new Database(DB_PATH);
db.pragma("journal_mode = WAL");

const iso = (ms) => new Date(ms).toISOString();
const key = (entity, uid) => `${entity}\u0000${uid}`;

const existing = db
  .prepare(
    "SELECT entity, uid, version, data, updated_at, deleted_at FROM records WHERE user_id = ?",
  )
  .all(USER_ID);

const incoming = new Map(items.map((x) => [key(x.entity, x.item.uid), x]));
const existingByKey = new Map(existing.map((r) => [key(r.entity, r.uid), r]));

const added = items.filter((x) => !existingByKey.has(key(x.entity, x.item.uid)));
const orphaned = existing.filter((r) => !incoming.has(key(r.entity, r.uid)));

console.log(`库       : ${DB_PATH}`);
console.log(`账号     : user_id = ${USER_ID}`);
console.log(`备份     : schema=${doc.schema} app=${doc.app} exportedAt=${iso(doc.exportedAt)}`);
console.log(`服务端   : records ${existing.length} 行（活 ${existing.filter((r) => !r.deleted_at).length}）`);
console.log(`备份     : items ${items.length} 条（活 ${items.filter((x) => !x.item.deletedAt).length}）`);
console.log(`将新增   : ${added.length}`);
console.log(`将墓碑   : ${orphaned.length}（服务端有、备份里没有）`);
for (const r of orphaned.slice(0, 10)) console.log(`   - ${r.entity} ${r.uid}`);

// 逐条列出「死活状态变了」的，这是真正会影响用户看到的改动
const flips = [];
for (const x of items) {
  const old = existingByKey.get(key(x.entity, x.item.uid));
  if (!old) continue;
  const wasAlive = !old.deleted_at;
  const nowAlive = !x.item.deletedAt;
  if (wasAlive !== nowAlive) {
    flips.push(`${wasAlive ? "活→删" : "删→活"}  ${x.entity} ${x.uid}  ${x.item.title || ""}`);
  }
}
console.log(`死活翻转 : ${flips.length}`);
for (const f of flips) console.log(`   ~ ${f}`);

if (!APPLY) {
  console.log("\nDRY-RUN：没有写库。加 --apply 才真正执行。");
  process.exit(0);
}

const insRec = db.prepare(
  `INSERT INTO records (user_id, entity, uid, data, version, updated_at, deleted_at)
   VALUES (?, ?, ?, ?, ?, ?, ?)`,
);
const insLog = db.prepare(
  `INSERT INTO sync_log (user_id, entity, uid, op, payload, updated_at, deleted_at)
   VALUES (?, ?, ?, ?, ?, ?, ?)`,
);

const run = db.transaction(() => {
  const delRec = db.prepare("DELETE FROM records WHERE user_id = ?").run(USER_ID);
  const delLog = db.prepare("DELETE FROM sync_log WHERE user_id = ?").run(USER_ID);

  const put = (entity, uid, payloadObj, updatedAtMs, deletedAtMs, version) => {
    const payload = JSON.stringify(payloadObj);
    const upd = iso(updatedAtMs);
    const del = deletedAtMs > 0 ? iso(deletedAtMs) : null;
    insRec.run(USER_ID, entity, uid, payload, version, upd, del);
    insLog.run(USER_ID, entity, uid, del ? "delete" : "upsert", payload, upd, del);
  };

  let n = 0;
  for (const { entity, item } of items) {
    const old = existingByKey.get(key(entity, item.uid));
    put(entity, item.uid, item, item.updatedAt, item.deletedAt || 0, old ? old.version + 1 : 1);
    n++;
  }
  // 服务端有、备份里没有 → 补一条墓碑，否则客户端永远看不到「这条被删了」
  for (const r of orphaned) {
    const old = JSON.parse(r.data);
    const tomb = { ...old, updatedAt: doc.exportedAt, deletedAt: doc.exportedAt };
    put(r.entity, r.uid, tomb, doc.exportedAt, doc.exportedAt, r.version + 1);
    n++;
  }
  return { delRec: delRec.changes, delLog: delLog.changes, n };
});

const res = run();
console.log(
  `\n已写入：records 删 ${res.delRec} 插 ${res.n} · sync_log 删 ${res.delLog} 插 ${res.n}`,
);
