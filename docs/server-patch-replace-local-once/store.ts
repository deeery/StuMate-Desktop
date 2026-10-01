import { sdb, nowIso, isoIn } from "./db";
import { sha256 } from "./crypto";

/**
 * StuMate 的表操作集合。
 *
 * 分层：本文件只做「SQL 进 SQL 出」，业务规则（限流、事务编排、响应形态）
 * 放在路由或 tokens.ts / oauth.ts 里。
 */

export interface UserRow {
  id: number;
  email: string;
  password_hash: string | null;
  nickname: string;
  initial_device_id: number | null;
  created_at: string;
  updated_at: string;
}

export interface DeviceRow {
  id: number;
  user_id: number;
  name: string;
  platform: string;
  last_seen_at: string | null;
  created_at: string;
}

export interface BindingRow {
  id: number;
  user_id: number;
  provider: string;
  provider_uid: string;
  provider_email: string | null;
  created_at: string;
}

// ── 用户 ────────────────────────────────────────────────────────

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

export function findUserByEmail(email: string): UserRow | undefined {
  return sdb
    .prepare("SELECT * FROM users WHERE email = ?")
    .get(normalizeEmail(email)) as UserRow | undefined;
}

export function findUserById(id: number): UserRow | undefined {
  return sdb.prepare("SELECT * FROM users WHERE id = ?").get(id) as
    | UserRow
    | undefined;
}

export function listUsers(): UserRow[] {
  return sdb
    .prepare("SELECT * FROM users ORDER BY id ASC")
    .all() as UserRow[];
}

export function createUser(
  email: string,
  passwordHash: string | null,
  nickname = "",
): number {
  const info = sdb
    .prepare(
      `INSERT INTO users (email, password_hash, nickname, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
    .run(normalizeEmail(email), passwordHash, nickname, nowIso(), nowIso());
  return Number(info.lastInsertRowid);
}

export function updateUserPassword(userId: number, hash: string): void {
  sdb.prepare("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?").run(
    hash,
    nowIso(),
    userId,
  );
}

/**
 * 改账号邮箱。
 *
 * 调用方**必须自己先查重**（`users.email` 上有唯一索引，撞了会抛 SQLITE_CONSTRAINT）——
 * 这里不吞异常，因为「邮箱被占用」是要如实告诉用户的业务结果，
 * 不该被降级成一句「保存失败」。
 */
export function updateUserEmail(userId: number, email: string): void {
  sdb.prepare("UPDATE users SET email = ?, updated_at = ? WHERE id = ?").run(
    normalizeEmail(email),
    nowIso(),
    userId,
  );
}

export function updateUserNickname(userId: number, nickname: string): void {
  sdb.prepare("UPDATE users SET nickname = ?, updated_at = ? WHERE id = ?").run(
    nickname,
    nowIso(),
    userId,
  );
}

export function setInitialDevice(userId: number, deviceId: number): void {
  sdb.prepare(
    "UPDATE users SET initial_device_id = ?, updated_at = ? WHERE id = ? AND initial_device_id IS NULL",
  ).run(deviceId, nowIso(), userId);
}

// ── 设备 ────────────────────────────────────────────────────────

/**
 * 登录时登记设备，**同一台机器重复登录要复用同一条记录**。
 *
 * 之前是无条件 INSERT，于是「退出登录 → 再登录」每来一次就多一条设备：
 * 一台电脑点满 5 次就把 DEVICE_LIMIT 顶满，第 6 次登录被自己的上限拒掉，
 * 而用户从头到尾只有一台机器 —— 这个上限本意是限制「同时有几台设备」，
 * 不是限制「这台机器登录过几次」。
 *
 * 复用的判据是 `user_id + name + platform`：桌面端 device.name 传的是
 * `COMPUTERNAME`，同机重名，跨机不会撞。匹配上就只更新时间戳，
 * 原来的 device_id 保持不变，于是它名下已签发的 refresh 令牌也不受影响。
 */
export function findOrCreateDevice(
  userId: number,
  name: string,
  platform: string,
): number {
  const existing = sdb
    .prepare(
      `SELECT id FROM devices
       WHERE user_id = ? AND name = ? AND platform = ?
       ORDER BY id LIMIT 1`,
    )
    .get(userId, name, platform) as { id: number } | undefined;

  if (existing) {
    touchDevice(existing.id);
    return existing.id;
  }
  return createDevice(userId, name, platform);
}

export function createDevice(
  userId: number,
  name: string,
  platform: string,
): number {
  const info = sdb
    .prepare(
      `INSERT INTO devices (user_id, name, platform, last_seen_at, created_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
    .run(userId, name, platform, nowIso(), nowIso());
  return Number(info.lastInsertRowid);
}

export function listDevices(userId: number): DeviceRow[] {
  return sdb
    .prepare("SELECT * FROM devices WHERE user_id = ? ORDER BY id DESC")
    .all(userId) as DeviceRow[];
}

export function countDevices(userId: number): number {
  const row = sdb
    .prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ?")
    .get(userId) as { n: number };
  return row.n;
}

/**
 * 这台机器（`user_id + name + platform`）是不是已经在设备列表里了。
 *
 * 给设备上限判断用：上限只该拦**新增**设备，不该拦同一台机器重复登录。
 */
export function hasDevice(userId: number, name: string, platform: string): boolean {
  const row = sdb
    .prepare(
      "SELECT 1 AS x FROM devices WHERE user_id = ? AND name = ? AND platform = ? LIMIT 1",
    )
    .get(userId, name, platform);
  return row !== undefined;
}

export function touchDevice(deviceId: number): void {
  sdb.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(
    nowIso(),
    deviceId,
  );
}

/**
 * 这台设备是否**已经完成过至少一次同步**。
 *
 * 用于 §5.8 的「一次性 replace_local」：设计原文是「其他设备**首次** pull 时清空本地」，
 * 而 `isInitialDevice()` 只能回答「我是不是首端」，回答不了「我是不是第一次来」——
 * 对非首端设备它永远返回 false。缺了「首次」这个限定，那台设备每一轮同步都会被
 * 要求清库重拉（实测一次启动落两个备份文件，运行 6 分钟三个）。
 */
export function hasDeviceSynced(deviceId: number): boolean {
  const row = sdb
    .prepare("SELECT first_synced_at FROM devices WHERE id = ?")
    .get(deviceId) as { first_synced_at: string | null } | undefined;
  return !!row?.first_synced_at;
}

/**
 * 标记这台设备已经同步过一次。**只写一次**（`first_synced_at IS NULL` 才更新）。
 *
 * 调用时机必须是「客户端已经拿到本次响应、有机会完成备份+清库」之后，
 * 也就是 **pull 收尾**。放在 push 里会太早：客户端是「先 push 再 pull」，
 * push 阶段就得知道该不该清库，此时还不能把标记落下去。
 */
export function markDeviceSynced(deviceId: number): void {
  sdb.prepare(
    "UPDATE devices SET first_synced_at = ? WHERE id = ? AND first_synced_at IS NULL",
  ).run(nowIso(), deviceId);
}

export function deleteDevice(userId: number, deviceId: number): boolean {
  const info = sdb
    .prepare("DELETE FROM devices WHERE id = ? AND user_id = ?")
    .run(deviceId, userId);
  return info.changes === 1;
}

// ── 第三方绑定 ──────────────────────────────────────────────────

export function findBinding(
  provider: string,
  providerUid: string,
): BindingRow | undefined {
  return sdb
    .prepare(
      "SELECT * FROM oauth_binding WHERE provider = ? AND provider_uid = ?",
    )
    .get(provider, providerUid) as BindingRow | undefined;
}

export function listBindings(userId: number): BindingRow[] {
  return sdb
    .prepare("SELECT * FROM oauth_binding WHERE user_id = ? ORDER BY id ASC")
    .all(userId) as BindingRow[];
}

export function createBinding(
  userId: number,
  provider: string,
  providerUid: string,
  providerEmail: string | null,
): void {
  sdb.prepare(
    `INSERT INTO oauth_binding (user_id, provider, provider_uid, provider_email, created_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(provider, provider_uid) DO UPDATE SET user_id = excluded.user_id`,
  ).run(userId, provider, providerUid, providerEmail, nowIso());
}

export function deleteBinding(userId: number, provider: string): boolean {
  const info = sdb
    .prepare("DELETE FROM oauth_binding WHERE user_id = ? AND provider = ?")
    .run(userId, provider);
  return info.changes === 1;
}

// ── 邀请码 ──────────────────────────────────────────────────────

/**
 * 原子占用一次邀请码。返回 code_id，失败返回 null。
 *
 * 必须用「条件 UPDATE + 检查 changes」，不能先 SELECT 再 UPDATE ——
 * 否则两个人同一秒用同一个「还剩 1 次」的码会双双注册成功。
 */
export function claimInvite(code: string): number | null {
  const info = sdb
    .prepare(
      `UPDATE invite_codes
          SET used_count = used_count + 1
        WHERE code = ?
          AND revoked_at IS NULL
          AND (expires_at IS NULL OR expires_at > ?)
          AND used_count < max_uses`,
    )
    .run(code, nowIso());
  if (info.changes !== 1) return null;
  const row = sdb
    .prepare("SELECT id FROM invite_codes WHERE code = ?")
    .get(code) as { id: number } | undefined;
  return row?.id ?? null;
}

/** 注册中途失败时把刚占用的次数退回，避免白白消耗一个码 */
export function releaseInvite(codeId: number): void {
  sdb.prepare(
    "UPDATE invite_codes SET used_count = MAX(used_count - 1, 0) WHERE id = ?",
  ).run(codeId);
}

export function recordInviteUse(codeId: number, userId: number): void {
  sdb.prepare(
    "INSERT OR IGNORE INTO invite_uses (code_id, user_id, used_at) VALUES (?, ?, ?)",
  ).run(codeId, userId, nowIso());
}

export function createInviteCode(
  code: string,
  note: string,
  maxUses: number,
  expiresAt: string | null,
): void {
  sdb.prepare(
    `INSERT INTO invite_codes (code, note, max_uses, used_count, expires_at, created_at)
     VALUES (?, ?, ?, 0, ?, ?)`,
  ).run(code, note, maxUses, expiresAt, nowIso());
}

export function listInviteCodes() {
  return sdb
    .prepare("SELECT * FROM invite_codes ORDER BY id DESC")
    .all() as Array<{
    id: number;
    code: string;
    note: string;
    max_uses: number;
    used_count: number;
    expires_at: string | null;
    revoked_at: string | null;
    created_at: string;
  }>;
}

export function revokeInviteCode(code: string): boolean {
  const info = sdb
    .prepare("UPDATE invite_codes SET revoked_at = ? WHERE code = ? AND revoked_at IS NULL")
    .run(nowIso(), code);
  return info.changes === 1;
}

// ── 一次性码（密码重置） ────────────────────────────────────────

/** 存 SHA-256，明文只在生成那一刻由 CLI 打印一次 */
export function createOneTimeCode(
  userId: number,
  purpose: string,
  code: string,
  ttlSeconds: number,
): void {
  // 同一用户同一用途只留最近一个：生成新的就把旧的作废
  sdb.prepare(
    "UPDATE one_time_codes SET used_at = ? WHERE user_id = ? AND purpose = ? AND used_at IS NULL",
  ).run(nowIso(), userId, purpose);
  sdb.prepare(
    `INSERT INTO one_time_codes (user_id, purpose, code_hash, expires_at, created_at)
     VALUES (?, ?, ?, ?, ?)`,
  ).run(userId, purpose, sha256(code), isoIn(ttlSeconds), nowIso());
}

/** 用后即焚：条件 UPDATE，命中即消费 */
export function consumeOneTimeCode(
  userId: number,
  purpose: string,
  code: string,
): boolean {
  const info = sdb
    .prepare(
      `UPDATE one_time_codes SET used_at = ?
        WHERE user_id = ? AND purpose = ? AND code_hash = ?
          AND used_at IS NULL AND expires_at > ?`,
    )
    .run(nowIso(), userId, purpose, sha256(code), nowIso());
  return info.changes === 1;
}

// ── OAuth 一次性 state ──────────────────────────────────────────

export interface OAuthStateRow {
  state: string;
  provider: string;
  mode: string;
  user_id: number | null;
  code_verifier: string | null;
  status: string;
  payload: string | null;
  error: string | null;
  created_at: string;
  expires_at: string;
}

export function createOAuthState(
  state: string,
  provider: string,
  mode: string,
  userId: number | null,
  payload: string,
  ttlSeconds = 600,
): void {
  sdb.prepare(
    `INSERT INTO oauth_state (state, provider, mode, user_id, status, payload, created_at, expires_at)
     VALUES (?, ?, ?, ?, 'pending', ?, ?, ?)`,
  ).run(state, provider, mode, userId, payload, nowIso(), isoIn(ttlSeconds));
}

export function getOAuthState(state: string): OAuthStateRow | undefined {
  return sdb
    .prepare("SELECT * FROM oauth_state WHERE state = ?")
    .get(state) as OAuthStateRow | undefined;
}

export function finishOAuthState(
  state: string,
  status: "ok" | "error",
  payload: string | null,
  error: string | null,
): void {
  sdb.prepare(
    "UPDATE oauth_state SET status = ?, payload = ?, error = ? WHERE state = ?",
  ).run(status, payload, error, state);
}

export function deleteOAuthState(state: string): void {
  sdb.prepare("DELETE FROM oauth_state WHERE state = ?").run(state);
}

/** 顺手清过期 state（每次 start 调用一次，成本可忽略） */
export function purgeExpiredOAuthState(): void {
  sdb.prepare("DELETE FROM oauth_state WHERE expires_at <= ?").run(nowIso());
}
