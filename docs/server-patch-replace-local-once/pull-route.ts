import type { NextRequest } from "next/server";
import { ERR, jsonOk } from "@/lib/stumate/http";
import { bearerFrom, principalFromAccessToken } from "@/lib/stumate/tokens";
import { isInitialDevice, pullChanges } from "@/lib/stumate/sync";
import { hasDeviceSynced, markDeviceSynced, touchDevice } from "@/lib/stumate/store";

export const dynamic = "force-dynamic";

/** 单次拉取上限。500 条按每条几百字节算约 200KB，对 10 人规模绰绰有余。 */
const MAX_LIMIT = 500;
const DEFAULT_LIMIT = 200;

/**
 * GET /api/stumate/v1/sync/pull?cursor=N&limit=500 （需 Bearer）
 *
 * 返回 `revision > cursor` 的全部变更。客户端拿 `nextCursor` 存下来当下次游标，
 * 下次接着从这里往后拉 —— 全程**增量**，不会重复下载已有数据。
 *
 * 三个响应字段的用法：
 *  - `cursor`        下次拉取时传回来的游标（**本次实际消费到的位置**）
 *  - `hasMore`       还有没有更多。true 时客户端应立刻接着拉，别等下一轮
 *  - `replaceLocal`  true 表示这台不是首端，本地数据要清空后全量重拉（§5.8）
 */
export async function GET(req: NextRequest) {
  const principal = principalFromAccessToken(bearerFrom(req) ?? "");
  if (!principal) return ERR.unauthorized();

  const url = new URL(req.url);

  // 游标必须是**非负整数**。这里不能用 parseInt 直接转：
  // `parseInt("12abc")` 会得到 12（静默吞掉尾巴），`parseInt("-5")` 得到 -5
  // （= 从未来往回拉，会把已清理的流水全翻出来）。宁可拒绝，也不要给用户一个错游标。
  const rawCursor = url.searchParams.get("cursor") ?? "0";
  if (!/^\d+$/.test(rawCursor.trim())) {
    return ERR.badRequest("cursor 必须是非负整数");
  }
  const cursor = Number(rawCursor);

  const rawLimit = url.searchParams.get("limit") ?? String(DEFAULT_LIMIT);
  if (!/^\d+$/.test(rawLimit.trim())) {
    return ERR.badRequest("limit 必须是非负整数");
  }
  const limit = Math.min(Math.max(Number(rawLimit), 1), MAX_LIMIT);

  touchDevice(principal.deviceId);

  const page = pullChanges(principal.userId, cursor, limit);

  // 首端判定放在 pull 侧：非首端设备**第一次**拉取时必须先清空本地，
  // 否则它自己生成的历史 uid 会和服务端的并存 → 用户看到双份课程。
  // null（还没有设备认领过首端）时**不要**下发 replaceLocal：
  // 用户刚注册、还没在任何设备推过数据，清空只会白丢数据。
  //
  // ⚠️ 三个条件缺一不可，尤其是 `!hasDeviceSynced()` 这一条。
  // 早先只写 `initial === false`，而 `isInitialDevice()` 回答的是「我是不是首端」，
  // **不是**「我是不是第一次来」—— 对非首端设备它永远返回 false。
  // 于是那台设备每一轮同步都收到 replace_local=true：客户端备份 → 清库 →
  // 从 cursor=0 全量重拉。实测一次启动落两个备份文件、运行 6 分钟三个。
  // 设计 §5.8 原文是「其他设备**首次** pull 时」，漏的正是这个「首次」。
  const initial = isInitialDevice(principal.userId, principal.deviceId);
  const replaceLocal = initial === false && !hasDeviceSynced(principal.deviceId);

  // 标记放在**返回之前**：客户端拿到本次响应后会走「备份 → 清空 → 全量拉」，
  // 从下一轮起就不该再清一次了。
  // 放在 push 侧会太早 —— 客户端是「先 push 再 pull」，push 阶段就得知道该不该清库。
  markDeviceSynced(principal.deviceId);

  return jsonOk({
    cursor: page.nextCursor,
    hasMore: page.hasMore,
    changes: page.changes,
    replace_local: replaceLocal,
  });
}
