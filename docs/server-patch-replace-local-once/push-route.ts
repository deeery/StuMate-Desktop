import type { NextRequest } from "next/server";
import { ERR, jsonOk, readJson } from "@/lib/stumate/http";
import { bearerFrom, principalFromAccessToken } from "@/lib/stumate/tokens";
import {
  claimInitialDevice,
  currentRevision,
  isEntity,
  isInitialDevice,
  isValidUid,
  MAX_RECORD_BYTES,
  putRecord,
  type Entity,
  type PushInput,
} from "@/lib/stumate/sync";
import { hasDeviceSynced, touchDevice } from "@/lib/stumate/store";

export const dynamic = "force-dynamic";

/** 单次推送上限。防止客户端逻辑出错时一次塞爆服务端。 */
const MAX_CHANGES = 500;

/**
 * POST /api/stumate/v1/sync/push （需 Bearer）
 * body: { changes: [ { entity, uid, data, updatedAt, deletedAt, baseVersion } ] }
 *
 * 响应：
 * ```
 * {
 *   cursor:     本次之后服务端的最大 revision，客户端存下来当新游标
 *   applied:    [ { uid, entity, version } ]              写入成功
 *   conflicts:  [ { entity, uid, data, version, … } ]      服务端更新，客户端要覆盖本地
 *   rejected:   [ { index, reason } ]                      单条不合法（不阻断整批）
 * }
 * ```
 *
 * ## 逐条裁决，不做「整批要么全成要么全败」
 *
 * 一批 50 条里若有 1 条格式不对就整批拒绝，等于用户改一节课就同步不上去。
 * 所以：**合法的照常写，非法的一条条列进 `rejected`**，
 * 客户端把合法的部分落库、对非法的打日志。
 *
 * ## 幂等
 *
 * 网络超时后客户端会重推同一批。第二次进来时 `baseVersion` 已经落后于
 * 服务端 `version` → 判成冲突 → 进 `conflicts` 而不是重复写入。
 * 所以 **LWW 本身就已经幂等**，不需要额外的 Idempotency-Key 表。
 */
export async function POST(req: NextRequest) {
  const principal = principalFromAccessToken(bearerFrom(req) ?? "");
  if (!principal) return ERR.unauthorized();

  const body = await readJson(req);
  const rawChanges = Array.isArray(body.changes) ? body.changes : null;
  if (!rawChanges) return ERR.badRequest("changes 必须是数组");
  if (rawChanges.length > MAX_CHANGES) {
    return ERR.badRequest(`一次最多推送 ${MAX_CHANGES} 条`);
  }

  touchDevice(principal.deviceId);

  // push 是「首次同步认领首端」的时机（设计 §5.8）：
  // 只有真正往服务端写了数据，这台才算「第一台」。
  const claim = claimInitialDevice(principal.userId, principal.deviceId);

  const applied: Array<{ entity: Entity; uid: string; version: number }> = [];
  const conflicts: unknown[] = [];
  const rejected: Array<{ index: number; reason: string }> = [];

  rawChanges.forEach((raw, index) => {
    const item = (raw ?? {}) as Record<string, unknown>;

    const entity = item.entity;
    const uid = item.uid;

    if (!isEntity(entity)) {
      rejected.push({ index, reason: "entity 只能是 class 或 note" });
      return;
    }
    if (!isValidUid(uid)) {
      rejected.push({ index, reason: "uid 不是合法 UUID" });
      return;
    }

    const data = item.data;
    if (!data || typeof data !== "object" || Array.isArray(data)) {
      rejected.push({ index, reason: "data 必须是 JSON 对象" });
      return;
    }

    // 体积上限：防住畸形载荷（比如把整个数据库塞进一个字段）
    const serialized = JSON.stringify(data);
    if (serialized.length > MAX_RECORD_BYTES) {
      rejected.push({ index, reason: "单条记录超过 1MB" });
      return;
    }

    const input: PushInput = {
      entity,
      uid,
      data: data as Record<string, unknown>,
      updatedAt: num(item.updatedAt),
      deletedAt: num(item.deletedAt),
      baseVersion: num(item.baseVersion),
    };

    const outcome = putRecord(principal.userId, input);
    if (outcome.kind === "applied") {
      applied.push({ entity, uid, version: outcome.version });
    } else {
      conflicts.push(outcome.server);
    }
  });

  // push 响应也带 replace_local：客户端先 push 再 pull 的顺序下，
  // 它需要在应用返回的变更之前就知道自己该不该清空本地。
  //
  // ⚠️ 与 pull 侧同样的三条件，同样不能省 `!hasDeviceSynced()`：
  // 只写 `initial === false` 会让非首端设备**每轮**都清库重拉（见 pull/route.ts 的注释）。
  // 这里**不**落标记 —— 标记由 pull 收尾时写，因为客户端要在 pull 之后才真正完成
  // 「备份 + 清空 + 全量拉」；在 push 阶段就落标记会让本轮 pull 拿不到 true。
  const initial = isInitialDevice(principal.userId, principal.deviceId);

  return jsonOk({
    cursor: currentRevision(principal.userId),
    applied,
    conflicts,
    rejected,
    replace_local: initial === false && !hasDeviceSynced(principal.deviceId),
    is_initial_device: claim.isInitial,
  });
}

/**
 * 取数字，缺省或非法一律 0。
 *
 * 用 `Number()` 而不是 `parseInt()`：后者会把 `"12abc"` 悄悄变成 12，
 * 而这里宁可当成 0（= 老数据）也不能接受半截数字。
 */
function num(value: unknown): number {
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : 0;
}
