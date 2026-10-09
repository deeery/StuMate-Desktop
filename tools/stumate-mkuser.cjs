/**
 * 在服务端库里建（或重置）一个测试账号。
 *
 * ## 为什么不能走 `/auth/register`
 *
 * 注册接口有两条硬校验，**登录接口没有**：
 *   - `isEmail(email)` —— 要求形如 `a@b.c`
 *   - `password.length >= 8`
 *
 * 所以「邮箱就叫 `test`」这种账号在注册那条路上直接 400。而登录接口只做
 * `findUserByEmail()`（trim + lowercase），**不校验格式** —— 也就是说
 * 只要库里有一行 `email='test'`，客户端就能用它登录。
 * 于是唯一的办法是直接写库。
 *
 * ⚠️ 客户端侧还有一道 `password.length >= 8`（桌面 `AccountSection.kt` /
 * 安卓 `AccountSyncSection.kt`，登录与注册**共用**这条校验）。所以密码必须
 * ≥ 8 位，否则 App 里的登录按钮是灰的、点不动。当前用的 `testtest` 正好 8 位。
 *
 * ## 哈希必须与 `lib/stumate/crypto.ts` 逐参数一致
 *
 * 差一个参数 → `verifyPassword()` 解析失败 → 恒返回 false →
 * 症状是「账号明明建好了，登录却永远说邮箱或密码不正确」。
 *   scrypt N=2^16, r=8, p=1, keylen=64, maxmem=128MiB
 *   明文先 `.normalize("NFKC")`
 *   存储格式 `scrypt$N$r$p$<salt b64>$<dk b64>`
 *
 * ## 用法（在服务器上跑）
 *
 *   scp tools/stumate-mkuser.cjs root@<host>:/tmp/
 *   NODE_PATH=/var/www/deeer/node_modules node /tmp/stumate-mkuser.cjs [邮箱] [密码] [昵称]
 *
 * 默认 `test` / `testtest` / `test`。账号已存在时**重置密码**，不重复建号。
 * 写库前请先按 `reference-server.md` 做一次 SQLite **在线备份**（不能 `cp`）。
 */
const Module = require("module");
process.env.NODE_PATH = process.env.NODE_PATH || "/var/www/deeer/node_modules";
Module.Module._initPaths();

const Database = require("/var/www/deeer/node_modules/better-sqlite3");
const { randomBytes, scryptSync } = require("node:crypto");

const EMAIL = (process.argv[2] || "test").trim().toLowerCase();
const PASSWORD = process.argv[3] || "testtest";
const NICKNAME = process.argv[4] || "test";

const DB_PATH = process.env.STUMATE_DB_PATH || "/var/lib/deeer/stumate.db";

// ── 与 crypto.ts 的 SCRYPT_PARAMS 完全一致 ─────────────────────────
const N = 2 ** 16;
const R = 8;
const P = 1;
const MAXMEM = 128 * 1024 * 1024;

function hashPassword(password) {
  const salt = randomBytes(16);
  const dk = scryptSync(password.normalize("NFKC"), salt, 64, {
    N,
    r: R,
    p: P,
    maxmem: MAXMEM,
  });
  return ["scrypt", N, R, P, salt.toString("base64"), dk.toString("base64")].join("$");
}

const db = new Database(DB_PATH);
const now = new Date().toISOString();
const hash = hashPassword(PASSWORD);

const row = db.prepare("SELECT id, email FROM users WHERE email = ?").get(EMAIL);

if (row) {
  db.prepare("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?").run(
    hash,
    now,
    row.id,
  );
  console.log(`已存在 → 重置密码  id=${row.id}  email=${row.email}`);
} else {
  const info = db
    .prepare(
      `INSERT INTO users (email, password_hash, nickname, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
    .run(EMAIL, hash, NICKNAME, now, now);
  console.log(`已创建  id=${info.lastInsertRowid}  email=${EMAIL}  nickname=${NICKNAME}`);
}

const after = db
  .prepare(
    "SELECT id, email, nickname, (password_hash IS NOT NULL) AS has_pw FROM users WHERE email = ?",
  )
  .get(EMAIL);
console.log("落库结果:", JSON.stringify(after));
db.close();
