# 服务端补丁：`replace_local` 一次性化

> 2026-10-02 部署。修的是「非首端设备**每一轮**同步都被要求清库重拉」。

## 症状

桌面端（设备 4 `WIN-TLLCHC604HH`）每次启动、每次写后防抖同步都会：

```
push  →  服务端回 replace_local=true  →  客户端写一份 StuMate-preinit-backup-*.json
      →  清空本地 classes/notes  →  游标归零  →  全量重拉
```

实测证据：

| 观察 | 值 |
|---|---|
| 一次启动 | 产出 **2 个**备份文件（01:13:23 courses=10 / 01:13:30 courses=58） |
| 运行 6 分钟 | 累计 **3 个**备份 |
| 连续 6 次启动 | 每次都是成对出现（01:19:16+19、01:20:22+25、01:22:18+22、01:23:39+42、01:25:10+14） |

危害：备份文件按同步次数堆积；每轮全量重拉；`applyRemote` 每轮重分配本地 id；
**清库发生在 pull 之前，若 pull 失败则本地出现空窗**。

## 真因

`app/api/stumate/v1/sync/{pull,push}/route.ts` 都写的是：

```ts
const initial = isInitialDevice(principal.userId, principal.deviceId);
const replaceLocal = initial === false;
```

而 `lib/stumate/sync.ts` 的 `isInitialDevice()` 只比对
`users.initial_device_id === deviceId` —— 它回答的是「**我是不是首端**」，
**不是**「**我是不是第一次来**」。对非首端设备它**永远返回 `false`**。

设计 §5.8 的原文是「后续其他设备**首次** pull 时」，实现漏掉了「首次」这个限定。

## 改动（4 个文件）

### `lib/stumate/db.ts`

- `devices` 建表 DDL 增加 `first_synced_at TEXT`（给新库用）。
- `migrate()` 增加：`PRAGMA table_info(devices)` 查列 → 缺则 `ALTER TABLE devices ADD COLUMN first_synced_at TEXT`。
- 补列的同时把**已有设备**一次性回填成「已同步」
  （`UPDATE devices SET first_synced_at = COALESCE(last_seen_at, created_at)`）——
  它们此前已经在同步了，不回填的话每台老设备会各多清一次库。

### `lib/stumate/store.ts`

新增两个函数：

- `hasDeviceSynced(deviceId): boolean` —— `first_synced_at` 非空即已同步。
- `markDeviceSynced(deviceId): void` —— `WHERE id = ? AND first_synced_at IS NULL`，**只写一次**。

### `app/api/stumate/v1/sync/pull/route.ts`

```ts
const replaceLocal = initial === false && !hasDeviceSynced(principal.deviceId);
markDeviceSynced(principal.deviceId);   // 放在 return 之前
```

### `app/api/stumate/v1/sync/push/route.ts`

```ts
replace_local: initial === false && !hasDeviceSynced(principal.deviceId),
```

## 为什么标记在 pull 落、不在 push 落

客户端顺序是 **先 push 再 pull**，而 `replace_local` 的判断发生在 push 阶段
（客户端只读 `push?.replaceLocal`）。所以：

- push 阶段：标记还没写 → 本轮仍能正确下发 `true`；
- pull 阶段：客户端已经拿到响应、有机会完成「备份 → 清空 → 全量拉」→ 此时落标记，
  下一轮起就不再清。

在 push 阶段落标记会让本轮 pull 拿不到 `true`，首端切换就失效了。

## 部署过程

```bash
# 1. 备份
cp /var/lib/deeer/stumate.db /root/stumate.db.bak-<stamp>            # 196608 bytes
cd /var/www/deeer && tar czf /root/deeer-code-backup-<stamp>.tgz \
    --exclude=node_modules --exclude=.next app lib package.json      # 73563 bytes

# 2. 改文件（本地改好 scp 上去，避免 shell 引号转义问题）

# 3. 类型检查（只能在服务器跑）
./node_modules/.bin/tsc --noEmit        # exit 0

# 4. 构建 + 重启
npm run build
pm2 restart deeer
```

## 验证结果（2026-10-02 01:32 UTC+8）

| 项 | 结果 |
|---|---|
| `tsc --noEmit` | exit 0 |
| `npm run build` | 成功（`/api/stumate/v1/sync/{pull,push,status}` 都在路由表里） |
| `GET /api/stumate/v1/health` | `{"ok":true,"version":"stumate/v1","db":"ok"}` |
| `devices` 新列 | `6|first_synced_at|TEXT|0||0` |
| 存量设备回填 | 设备 4 → `2026-10-01T17:27:55.760Z`；设备 36 → `2026-10-01T17:26:36.106Z` |
| 站点未受影响 | `deeer.online` = 200，`ts3.deeer.online` = 302 |
| **端到端** | 重启桌面端后：备份文件数**停在 14 不变**；`devices.last_seen_at` 更新到 17:32:51（同步确实发生）；`first_synced_at` 保持 17:27:55（只写一次）；本地数据 `classes 58/53 live`、`notes 13` 完好 |

## 回滚

```bash
cp /root/stumate.db.bak-20261002-013047 /var/lib/deeer/stumate.db
cd /var/www/deeer && tar xzf /root/deeer-code-backup-20261002-013047.tgz
npm run build && pm2 restart deeer
```

## 遗留

设计层面还有一个**未修**的问题：客户端是「先 push 再 pull」，所以非首端设备
在得知自己该被替换**之前**就已经把本地数据推上去了 —— §5.8「首端权威」的目标
（避免两端历史数据 uid 不同导致重复）实际达不到。

本次实测里，桌面端的演示数据（8 门课 + 7 条便签）被推成服务端 revision 140–154，
与安卓端的数据**并存**了。对「两端确实是不同数据」的场景这没问题（等于合并），
对「同一批历史数据在两端各有一份」的场景仍会产生重复。

要不要改成「首次同步只 pull 不 push」，需另行决策。
