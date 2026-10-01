# StuMate 账号与云同步系统 · 设计方案 v1.1

> 覆盖范围：`StuMate-Desktop`（Compose Multiplatform Desktop）+ `StuMate-Mobile`（`ClassReminderNewest`，Android 原生 Compose）+ 服务端
> 编写日期：2026-09-30 · 状态：**待评审**
> 前置版本：`account-and-sync-design-v1.0.md`

---

## 变更摘要（v1.0 → v1.1）

v1.0 是在**没连上服务器**的情况下写的，对后端做了假设。v1.1 基于**实际登录服务器勘察**的结果重写。

| 项 | v1.0（假设） | v1.1（实测） |
|---|---|---|
| 后端形态 | 未知，"已有后端" | 现有 **Next.js 16.3.0 个人站**，StuMate API 作为新增路由组接入 |
| 数据库 | PostgreSQL / MySQL | **SQLite（better-sqlite3）**，且建议**独立库文件** |
| 令牌 | JWT access + 不透明 refresh | **改为双不透明令牌**（见 §4.1，有理由） |
| 密码哈希 | Argon2id | **Node 内置 scrypt**（避免新增原生模块，见 §3.1） |
| 传输安全 | 需确认证书类型 | **Let's Encrypt 公信证书** → 两端**零配置**（重大简化） |
| 邮件通道 | 未提及 | **发现不通**：postfix 未启动 + 阿里云封 25 端口 → **新阻塞项** |
| 第三方平台 | 待定 | **已定：GitHub + Google** |
| 冲突策略 | 待确认 | **已定：LWW** |
| 历史数据回填 | 三选一 | **已定：以第一端为准** |

---

## 0. 前置：现状勘察结论

### 0.1 服务端真实身份

```
公网 IP     47.96.173.58
内网 IP     172.24.55.32/20   （eth0，阿里云 VPC 内网）
域名        deeer.online / www.deeer.online
系统        Alibaba Cloud Linux 3 (Anolis)
规格        2 vCPU / 1.8 GB RAM / 40 GB 盘（已用 18%）
运行时      Node v24.9.0 / npm 11.6.0
进程管理    pm2（进程名 deeer，fork 模式，单实例）
Web 服务器  nginx 1.20+，80/443 → 127.0.0.1:3000
TLS 证书    Let's Encrypt（acme.sh 管理，2026-12-16 到期，含自动续期）
```

**需求里给的 `172.24.55.32` 是这台机器的内网地址，客户端从公网访问不到。** 两端必须使用 `https://deeer.online`。这一点很重要 —— 内网 IP 也拿不到公信证书，而域名已经有现成的有效证书。

### 0.2 现有应用：`deeer-website`

`/var/www/deeer`，Next.js 16.3.0 + React 19.2.8 + TypeScript + Tailwind 4 + better-sqlite3。

一个个人站，包含：博客（markdown 内容）、评论、留言板、聊天室、点赞、访问统计、TeamSpeak 3 控制面板，以及一个**管理后台**。

```
app/
├── api/
│   ├── auth/{login,logout,me}      管理员认证（OTP）
│   ├── admin/{posts,settings,visits}
│   ├── chat/{messages,stream}
│   ├── comments/  guestbook/  likes/  visit/  ts3/
├── admin/  posts/  chat/  guestbook/  ts3/
lib/
├── db.ts           SQLite 连接 + 建表 + 全部数据访问函数
├── auth.ts         管理员会话（token 表 + httpOnly cookie）
├── otp.ts          TOTP 风格动态码（HMAC-SHA1，60s 窗口）
├── rate-limit.ts   进程内内存限流（getClientIp / isRateLimited）
├── validate.ts     输入校验
├── chat-store.ts   posts.ts  admin-posts.ts  ts3.ts
data/                （注意：真实数据库不在这里，见下）
```

**部署方式**：没有 git 仓库。手工改文件 → `npm run build` → `pm2 restart deeer`。`/root` 下有大量 `deeer-site-backup-*.tgz` 手工备份。

**配置方式**：没有 `.env` 文件。所有配置（`ADMIN_OTP_SECRET`、`DATABASE_PATH`）都写在 **pm2 的 env 里**（`/root/.pm2/dump.pm2`）。新增配置项必须同步更新 pm2 配置，否则重启即丢。

**数据库真实路径是 `/var/lib/deeer/site.db`**（由 `DATABASE_PATH` 环境变量指定），**不是**项目目录下的 `data/site.db` —— 后者是废弃副本，最后一次写入停在 9 月 15 日。勘察时不要被它误导。

现有表：`comments`、`guestbook_messages`、`likes`、`sessions`、`settings`、`chat_messages`、`visits`。

### 0.3 现有认证机制：**不是**多用户账号体系

`lib/auth.ts` + `lib/otp.ts` 实现的是**单管理员免密码登录**：

1. 管理员在服务器上跑 `npm run otp` 拿到 5 位动态码（每分钟轮换）
2. `POST /api/auth/login` 校验动态码
3. 通过后生成 32 字节随机 token 存入 `sessions` 表
4. 浏览器保存 httpOnly cookie `deeer_admin_session`，7 天有效

**没有 `users` 表，`sessions` 表也没有 `user_id`。** 这是一个"后台门禁"，不是账号系统。

结论：StuMate 需要的用户体系、设备管理、令牌轮转、同步协议，**全部要新建**。但可以复用现有的工程模式（SQLite 访问方式、限流、校验、路由组织）。

### 0.4 命名空间冲突（必须避开）

现有 `/api/auth/{login,logout,me}` 已被管理员登录占用。StuMate 的接口**必须另起命名空间**：

```
/api/stumate/v1/**        ← StuMate 账号与同步
/api/auth/**              ← 现有管理员（不动）
```

### 0.5 两端客户端现状

| 项目 | 技术栈 | 网络依赖 | 数据层 |
|---|---|---|---|
| StuMate-Desktop | Kotlin + Compose Multiplatform 1.5.10 | **无** | sqlite-jdbc + 手写 DAO，schema v7 |
| StuMate-Mobile | Kotlin + Android Compose + Room 2.6.1 | **无** | Room v7 |

移动端 `AndroidManifest.xml` **没有任何 `INTERNET` 权限**，必须补。

### 0.6 已有资产：模块化备份格式可直接复用

两端都有 `data/backup/`，`BackupDocument`（`courses` / `notes` / `settings` 三模块），纯 Kotlin、两端字段一致。**同步载荷复用它**，见 §5.6。

### 0.7 同步的核心障碍：主键是本机自增的 `Int`

```kotlin
@Entity(tableName = "classes")
data class ClassEntity(@PrimaryKey val id: Int, ...)   // 注意：没有 autoGenerate
```

`id` 由应用分配。桌面端新建一门课得 `id=5`，移动端新建一条也得 `id=5` → **跨设备必然撞号**。解法见 §5.2。

---

## 1. 目标与非目标

### 目标

1. 邮箱 + 密码主账号，支持注册、登录、找回密码
2. 支持 **GitHub / Google** 第三方绑定，绑定后可用于快捷登录
3. 登录后**课程表与便签**在桌面端与移动端之间云同步
4. **不破坏现有体验**：不登录照常单机使用
5. 两端配置方式一致

### 非目标（本期不做）

- 多人协作 / 共享课表
- 设置类数据（主题、周数校准、视图偏好）的云同步
- 端到端加密（服务端可见明文）
- 微信 / QQ 绑定（需企业主体 + 开放平台认证，见 §3.3）
- Web 端

---

## 2. 总体架构

```
┌──────────────────────┐          ┌──────────────────────┐
│  StuMate-Desktop     │          │  StuMate-Mobile      │
│  Compose Desktop     │          │  Android + Room      │
│                      │          │                      │
│   ui/fluent          │          │   ui/                │
│      ↓               │          │      ↓               │
│   MainViewModel      │          │   MainViewModel      │
│      ↓               │          │      ↓               │
│   本地 DAO (v8)      │          │   Room DAO (v8)      │
│      ↓               │          │      ↓               │
│   SyncEngine ────────┼──────────┼── SyncEngine         │
│   TokenStore         │          │   TokenStore         │
└──────────┬───────────┘          └──────────┬───────────┘
           │  HTTPS · Bearer                 │
           └────────────┬────────────────────┘
                        ▼
        ┌────────────────────────────────────────────┐
        │  nginx  443  (Let's Encrypt)               │
        │    deeer.online → 127.0.0.1:3000           │
        └───────────────────┬────────────────────────┘
                            ▼
        ┌────────────────────────────────────────────┐
        │  Next.js 16.3.0 (pm2: deeer)               │
        │                                            │
        │  /api/auth/**          现有管理员（不动）    │
        │  /api/stumate/v1/**    ← 新增：账号与同步    │
        │                                            │
        │  lib/stumate/*         ← 新增：独立模块      │
        └───────────────────┬────────────────────────┘
                            ▼
        ┌────────────────────────────────────────────┐
        │  SQLite  /var/lib/deeer/stumate.db         │
        │  （与站点库 site.db 分开，见 §6.1）          │
        └────────────────────────────────────────────┘
```

**OAuth 由服务端中介，客户端不直接对接 IdP。** 理由：桌面端是原生应用，GitHub / Google 虽然允许 `http://127.0.0.1:port` 回环回调，但走服务端中介能让两端共用同一条路径，且后续加微信（只接受备案域名回调）不用改客户端。

```
客户端  POST /api/stumate/v1/oauth/{provider}/start   →  { state, authorize_url }
客户端  打开系统浏览器
用户在浏览器完成授权 → IdP 回调服务端
服务端  记下 state 对应的用户，签发一次性 ticket
客户端  GET  /api/stumate/v1/oauth/{provider}/poll?state=...  →  { status, tokens? }
```

---

## 3. 账号体系

### 3.1 主账号：邮箱 + 密码

| 项 | 方案 |
|---|---|
| 邮箱唯一性 | 唯一索引；存储前 `trim()` + 转小写 |
| 密码哈希 | **Node 内置 `crypto.scrypt`**，参数 `N=2^16, r=8, p=1, keylen=64`，16 字节随机盐 |
| 密码强度 | 最少 8 位；不做复杂度强制 |
| 找回密码 | 邮件一次性 token，30 分钟有效、用后即焚，库里只存哈希 |

**为什么用 scrypt 而不是 Argon2id**：这台机器上 `better-sqlite3` 已经依赖原生模块编译，再加 `argon2` 会引入第二个原生依赖（需要 node-gyp + 编译工具链），而**部署是手工拷贝文件、没有 git 仓库**，多一个原生依赖就多一处会在下次部署时炸掉的地方。`node:crypto` 的 scrypt 是内置的、零依赖、且是 OWASP 认可的密码 KDF。如果后续部署流程规范化了，再换 Argon2id 也不影响接口。

**一个必须注意的坑**：`crypto.scrypt` 的 `maxmem` 默认是 32 MiB，而 `N=2^16, r=8` 需要 `128 × N × r = 64 MiB`，**不显式传 `maxmem` 会直接抛错**。

```ts
import { randomBytes, scrypt as _scrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";
const scrypt = promisify(_scrypt) as (p: string|Buffer, s: string|Buffer, k: number, o: object) => Promise<Buffer>;

const PARAMS = { N: 2 ** 16, r: 8, p: 1, maxmem: 128 * 1024 * 1024 };  // maxmem 必须显式给

export async function hashPassword(pw: string): Promise<string> {
  const salt = randomBytes(16);
  const dk = await scrypt(pw.normalize("NFKC"), salt, 64, PARAMS);
  return `scrypt$${PARAMS.N}$${PARAMS.r}$${PARAMS.p}$${salt.toString("base64")}$${dk.toString("base64")}`;
}
```

参数编进哈希串，将来调参不影响老密码校验。

**性能提示**：64 MiB/次的哈希在 1.8 GB 内存的机器上，配合登录限流（§3.4）是安全的；但绝不能让登录接口无限制并发，否则内存会被打满。

### 3.2 第三方绑定（GitHub + Google）

**已确认本期只做这两个。** 两者都是标准 OAuth2，无资质门槛。

```
oauth_binding (
  id, user_id, provider, provider_uid, provider_email,
  access_token, refresh_token, expires_at, created_at
  UNIQUE(provider, provider_uid)
)
```

`provider` 取值 `github` | `google`。做成可插拔的 provider 注册表（`lib/stumate/oauth.ts` 里一张配置表），后续加平台只加配置。

**绑定规则**（安全关键，写死）：

1. 已登录状态下发起绑定 → 直接绑到当前账号
2. 未登录状态下用第三方登录：
   - `provider_uid` 已有绑定 → 直接登录
   - 无绑定但 IdP 返回的邮箱**已验证**且与已存在账号一致 → **不自动合并**，要求先用邮箱密码登录后再绑定
     - 理由：自动合并意味着任何人只要能控制一个同邮箱的 IdP 账号，就能接管目标账号
   - 其余情况 → 走"补充注册"，要求设置密码后建号
3. 解绑前校验：**必须已设置密码或已绑其他平台**，否则用户会把自己锁在门外

### 3.3 微信 / QQ（本期不做）

需要**企业主体 + 微信开放平台认证（300 元/年）**，且回调域名需备案。本期排除。若将来要做，走服务端中介 + 二维码轮询，客户端代码不用改。

### 3.4 登录限流

复用 `lib/rate-limit.ts` 的**模式**（进程内内存 Map），但**另建一份计数表**，因为现有 `isRateLimited` 是「每 IP 每 30 秒 3 次」的通用写限流，对登录来说太宽松。

建议登录单独限流：

| 维度 | 阈值 |
|---|---|
| 同 IP | 每 15 分钟 20 次 |
| 同邮箱 | 每 15 分钟 10 次，超过后指数退避 |
| 注册 | 同 IP 每 24 小时 5 次 |

pm2 是 fork 单实例，进程内计数有效。**若将来改 cluster 模式，这里必须换成共享存储**（Redis 或 SQLite 表），否则限流失效。这一点写进注释。

---

## 4. 认证与会话

### 4.1 令牌方案：双不透明令牌（**相对 v1.0 的修改**）

| 令牌 | 形式 | 有效期 | 客户端存储 |
|---|---|---|---|
| access_token | 32 字节随机串 | **15 分钟** | 内存 |
| refresh_token | 32 字节随机串 | **30 天**，滑动续期 | 加密落盘 |

**v1.0 假设了 JWT access，这里改掉，理由**：

1. 本项目是**单实例、单库**，不存在"多个服务需要无状态校验 JWT"的场景 —— JWT 的主要收益在这里用不上
2. 每次请求多一次 SQLite 读，是**微秒级**的本地操作，代价可忽略
3. 不透明令牌**可以立刻吊销**（改密码、踢设备、发现异常），JWT 签出去就收不回
4. 少一个 `jose` 依赖，少一处部署时会炸的地方

若将来需要横向扩展或多服务共享鉴权，再把 access 换成 JWT 即可，接口形状不变。

**刷新采用轮转（rotation）**：每次刷新签发新 refresh 并作废旧的；若检测到**已作废的 refresh 被再次使用**，判定令牌泄露，**吊销该设备整条令牌链**并要求重新登录。

### 4.2 令牌表结构

```sql
tokens (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER NOT NULL,
  device_id     INTEGER NOT NULL,
  kind          TEXT NOT NULL,          -- 'access' | 'refresh'
  token_hash    TEXT NOT NULL UNIQUE,   -- 只存 SHA-256，不存原文
  parent_id     INTEGER,                -- 轮转链，用于检测重放
  created_at    TEXT NOT NULL,
  expires_at    TEXT NOT NULL,
  revoked_at    TEXT
);
CREATE INDEX idx_tokens_hash ON tokens(token_hash);
CREATE INDEX idx_tokens_user ON tokens(user_id, kind);
```

**只存哈希**：即使数据库泄露，攻击者也拿不到可用令牌。校验时对来访令牌做 SHA-256 再查表。

### 4.3 凭证安全存储（客户端）

| 平台 | 方案 |
|---|---|
| Desktop (Windows) | **DPAPI**（`CryptProtectData`，按当前用户加密）→ `%APPDATA%\StuMate\credentials.bin`。JNA 已在依赖里（`net.java.dev.jna:jna:5.6.0`），直接调 `crypt32`，无需新依赖 |
| Mobile (Android) | **EncryptedSharedPreferences**（`androidx.security:security-crypto`）+ Keystore 托管主密钥 |

**明确禁止**把 token 明文写进现有的 `settings.json` —— 那是纯文本，而且会被备份功能导出。

### 4.4 设备管理

每次登录登记一条设备记录（设备名、平台、最后活跃时间）。设置页可查看已登录设备并「退出该设备」；改密码时吊销除当前设备外的所有令牌；上限 10 台。

---

## 5. 同步协议（核心）

### 5.1 难题：本地主键不可用

见 §0.7。服务端分配 id 也不行 —— 那要求创建记录时必须联网，直接摧毁离线优先。

### 5.2 解法：新增全局 `uid`，本地主键保持不动

给 `classes` 和 `notes` 各加一列 `uid TEXT NOT NULL`（UUIDv4），**同步层只认 `uid`，本地 DAO 继续用 `id`**。

关键好处：**现有 UI、DAO、ViewModel 的查询逻辑一行都不用改**，只在同步边界做一层映射。

| 新增列 | 类型 | 含义 |
|---|---|---|
| `uid` | TEXT | 全局唯一标识，UUIDv4 |
| `updatedAt` | INTEGER | 本地最后修改时刻（epoch ms） |
| `deletedAt` | INTEGER | 软删除标记，`0` = 未删除 |

### 5.3 软删除：不做就会「删了又活」

现有 DAO 的 delete 是 `DELETE FROM`。同步场景下这是错的：设备 A 删除后本地行消失，设备 B 不知情，下次同步会把已删的推回来 → **数据复活**。

所以 delete 改成 `UPDATE ... SET deletedAt = ?, updatedAt = ?`，所有查询加 `WHERE deletedAt = 0`。

代价：数据不会真正消失。缓解：服务端保留 90 天，客户端每次同步成功后清理 `deletedAt` 超过 30 天的行。

### 5.4 增量同步：服务端 revision 游标

```sql
sync_log (
  revision   INTEGER PRIMARY KEY AUTOINCREMENT,  -- SQLite 单调递增
  user_id    INTEGER NOT NULL,
  entity     TEXT NOT NULL,                      -- 'class' | 'note'
  uid        TEXT NOT NULL,
  op         TEXT NOT NULL,                      -- 'upsert' | 'delete'
  payload    TEXT,                               -- JSON
  updated_at TEXT NOT NULL
);
CREATE INDEX idx_sync_log_user_rev ON sync_log(user_id, revision);
```

客户端持有 `lastCursor`（上次拿到的最大 revision）。

- **拉取** `GET /sync/pull?cursor=N` → 返回 `revision > N` 的全部变更 + 新游标
- **推送** `POST /sync/push` → 提交本地变更，服务端逐条合并，返回合并结果 + 新游标

**游标不用时间戳**：设备时钟不可信（用户改系统时间、时区漂移），用时间戳做游标会漏数据。单调序号没有这个问题。

### 5.5 冲突解决：版本号 + LWW（**已确认**）

每条记录在服务端维护 `version`（每次写入 +1）。客户端推送时带 `baseVersion`：

```
baseVersion == 服务端 version   → 无冲突，写入，version += 1
baseVersion <  服务端 version   → 冲突 → LWW：比较 updatedAt，保留较新的一方
```

冲突结果**回吐给客户端**，客户端据此覆盖本地。

**已知限制（明确记录，不隐藏）**：两台设备在**离线状态下**修改同一条记录，后同步的一方会覆盖先同步的一方，先同步方的改动会丢失。缓解：
- 推送响应返回被覆盖的记录清单
- 客户端在设置页显示「上次同步覆盖了 N 条记录」
- 极端情况用已有的**备份功能**兜底

### 5.6 载荷格式：复用备份格式

沿用 `stumate-backup` 的形状，每条记录多带三个同步元数据字段：

```json
{
  "schema": 2,
  "app": "StuMate",
  "exportedAt": 1790000000000,
  "cursor": 1024,
  "modules": {
    "courses": [
      { "uid": "9f1c…", "id": 5, "title": "高等数学",
        "dayOfWeek": "Monday", "startTime": "08:00", "endTime": "09:35",
        "room": "教二 305", "notes": "", "teacher": "王海燕",
        "weeks": "1-16周", "date": "",
        "updatedAt": 1789999999000, "deletedAt": 0 }
    ],
    "notes": []
  }
}
```

`BackupFormat.SCHEMA` 从 1 升到 2，**新增字段全部可选**，导入 v1 旧备份时自动补 `uid`（现场生成）、`updatedAt = 0`、`deletedAt = 0` → **旧备份文件继续可读**。这样备份、恢复、同步共用一套编解码。

### 5.7 API 契约

全部挂在 `/api/stumate/v1/` 下（见 §0.4 命名空间冲突）。

```
── 账号 ────────────────────────────────────────────────
POST   /api/stumate/v1/auth/register        { email, password, device }   → { user, tokens }
POST   /api/stumate/v1/auth/login           { email, password, device }   → { user, tokens }
POST   /api/stumate/v1/auth/refresh         { refresh_token }             → { tokens }
POST   /api/stumate/v1/auth/logout          { refresh_token }             → 204
POST   /api/stumate/v1/auth/password/forgot { email }                     → 204（恒定返回，防枚举）
POST   /api/stumate/v1/auth/password/reset  { token, new_password }       → 204
POST   /api/stumate/v1/auth/password/change { old_password, new_password }→ { tokens }

── 第三方绑定（GitHub / Google）──────────────────────────
POST   /api/stumate/v1/oauth/{provider}/start                            → { state, authorize_url }
GET    /api/stumate/v1/oauth/{provider}/poll   ?state=…                  → { status, tokens? }
POST   /api/stumate/v1/oauth/{provider}/bind   (需 Bearer)                → { binding }
DELETE /api/stumate/v1/oauth/{provider}/bind   (需 Bearer)                → 204

── 用户与设备 ──────────────────────────────────────────
GET    /api/stumate/v1/me                                                → { user, bindings }
PATCH  /api/stumate/v1/me                   { nickname? }                 → { user }
GET    /api/stumate/v1/me/devices                                        → [ device ]
DELETE /api/stumate/v1/me/devices/{id}                                   → 204

── 同步 ────────────────────────────────────────────────
GET    /api/stumate/v1/sync/pull?cursor=N&limit=500                      → { cursor, changes[], hasMore }
POST   /api/stumate/v1/sync/push            { cursor, changes[] }        → { cursor, applied[], conflicts[] }
GET    /api/stumate/v1/sync/status                                       → { cursor, counts }
```

统一约定：
- 认证 `Authorization: Bearer <access_token>`
- 错误体 `{ "error": { "code": "EMAIL_TAKEN", "message": "…" } }`
- `POST /sync/push` 支持 `Idempotency-Key` 头，网络重试不写重

### 5.8 首次同步与 uid 回填：**以第一端为准**（已确认）

两端各自为历史数据生成 `uid`，会导致同一条历史课程在两端得到**不同 uid**，首次同步变成两条重复记录。

**确定的策略**：**首个完成同步的设备作为权威数据源**。

服务端在 `users` 表上记一个 `initial_device_id`：

```
用户首次 push 时：
  if users.initial_device_id IS NULL:
      users.initial_device_id = 当前 device_id
      标记该用户已"初始化"

后续其他设备首次 pull 时：
  若 device_id != users.initial_device_id:
      响应里带 "replace_local": true
      客户端收到后：清空本地 classes / notes（先自动备份一份到本地文件）
                   然后全量拉取服务端数据
```

**桌面端和移动端都要实现"清空前自动本地备份"** —— 这一步不能省。用户可能在移动端有一批只在手机上存在的课，直接清空会丢数据。清空前写一个 `StuMate-preinit-backup-<日期>.json` 到数据目录，并在 UI 上明确告知路径。

---

## 6. 数据模型

### 6.1 数据库选择：独立库文件 `stumate.db`

**建议新建 `/var/lib/deeer/stumate.db`，而不是并入现有的 `site.db`。**

| | 并入 site.db | 独立 stumate.db（建议） |
|---|---|---|
| 代码量 | 少（复用 `lib/db.ts` 的 `db` 单例） | 多一个连接模块 |
| 故障隔离 | 站点库出问题会连累 StuMate | **互不影响** |
| 备份粒度 | 只能整体备份 | 可单独备份/恢复/迁移 |
| 未来迁出 | 需要拆表 | 拷一个文件即可 |

理由：StuMate 存的是**用户的真实课表数据**，价值高于站点的评论留言。而站点是会被反复改版、重部署、甚至重置的。用独立文件把爆炸半径隔开，代价只是一个 20 行的连接模块。

**部署注意**：现有的 `deeer-site-backup-*.tgz` 手工备份习惯必须扩展到包含 `stumate.db`，否则恢复时会把用户数据漏掉。

### 6.2 服务端表（SQLite DDL）

```sql
CREATE TABLE IF NOT EXISTS users (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  email             TEXT NOT NULL,              -- 存小写
  password_hash     TEXT,                       -- 第三方注册时可能为空
  email_verified_at TEXT,
  nickname          TEXT NOT NULL DEFAULT '',
  initial_device_id INTEGER,                    -- 见 §5.8
  created_at        TEXT NOT NULL,
  updated_at        TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email);

CREATE TABLE IF NOT EXISTS devices (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      INTEGER NOT NULL,
  name         TEXT NOT NULL,
  platform     TEXT NOT NULL,                   -- 'desktop' | 'android'
  last_seen_at TEXT,
  created_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

CREATE TABLE IF NOT EXISTS tokens ( … 见 §4.2 … );

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
CREATE UNIQUE INDEX IF NOT EXISTS idx_oauth_provider_uid ON oauth_binding(provider, provider_uid);

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
CREATE INDEX IF NOT EXISTS idx_records_user_entity ON records(user_id, entity);

CREATE TABLE IF NOT EXISTS sync_log ( … 见 §5.4 … );

CREATE TABLE IF NOT EXISTS email_tokens (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  purpose    TEXT NOT NULL,                     -- 'verify' | 'reset'
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  used_at    TEXT,
  created_at TEXT NOT NULL
);
```

**时间统一用 TEXT + ISO8601**，与站点现有表的 `datetime('now','localtime')` 风格一致。注意 SQLite 没有原生时间类型，跨端比较时**必须统一时区** —— 建议全部存 UTC，只在展示层转本地。

### 6.3 建表与迁移机制

现有 `lib/db.ts` 的模式是**每次连接执行一遍 `CREATE TABLE IF NOT EXISTS`**。新表天然适配这个模式，直接照搬。

但**将来给已有表加列时这个模式会失效**（`CREATE TABLE IF NOT EXISTS` 不会补列）。建议同时引入一个极轻量的迁移机制：

```ts
// lib/stumate/db.ts
function migrate(db: Database.Database) {
  const cur = Number(getSetting(db, "stumate_schema", "0"));
  const steps: Array<[number, (d: Database.Database) => void]> = [
    [1, (d) => { /* v1 建表 */ }],
    // [2, (d) => { d.exec("ALTER TABLE records ADD COLUMN note TEXT"); }],
  ];
  for (const [v, fn] of steps) {
    if (cur < v) { fn(db); setSetting(db, "stumate_schema", String(v)); }
  }
}
```

复用现有 `settings` 表的 key-value 模式即可，不需要引入迁移框架。

### 6.4 两端本地库迁移

| 平台 | 版本 | 迁移动作 |
|---|---|---|
| Desktop | `Db.SCHEMA_VERSION` 7 → **8** | 新库直接建到 v8；老库 `ALTER TABLE classes/notes ADD COLUMN uid/updatedAt/deletedAt`。注意桌面端 `migrate()` 目前**只处理"全新库"**，必须补一条增量迁移分支 |
| Mobile | Room v7 → **v8** | 新增 `Migration(7, 8)`，同样三列 |

`uid` 回填按 §5.8 的"以第一端为准"策略处理，客户端侧只需保证**本地已有数据的 uid 稳定**（生成后写入数据库，不要每次启动重新生成）。

---

## 7. 客户端改造清单

### 7.1 桌面端

| 项 | 内容 |
|---|---|
| 依赖 | Ktor Client (CIO) 或 OkHttp + `kotlinx-serialization-json` |
| 新增包 | `net/`（ApiClient、TokenStore、SyncEngine、Dto） |
| 数据层 | `Db.kt` 加 v8 迁移；DAO 的 delete 改软删除，insert/update 写 `updatedAt` |
| 凭证 | `net/DpapiStore.kt`，JNA 调 `crypt32.CryptProtectData` |
| UI | 登录覆盖层（复用 `OverlayScreen.kt` 的对话框基座）；设置页新增「账号」分组 |
| 触发 | 启动后、写操作后 30 秒防抖、手动同步 |

### 7.2 移动端

| 项 | 内容 |
|---|---|
| 权限 | **`AndroidManifest.xml` 加 `INTERNET` + `ACCESS_NETWORK_STATE`** |
| 依赖 | OkHttp + `kotlinx-serialization-json`；`androidx.security:security-crypto` |
| 新增包 | `net/`（与桌面端同构） |
| 数据层 | Room `Migration(7, 8)`；DAO delete 改软删除 |
| 凭证 | EncryptedSharedPreferences |
| UI | 登录页；设置页新增「账号」分组 |

**好消息**：因为是 Let's Encrypt 证书，**不需要 `network_security_config.xml`，不需要信任锚**，Android 直接就能连。

### 7.3 可共享的代码

两端 `data/` 下的 `TimeAxis` / `TodaySchedule` / `WeekSchedule` / `TodayNotePicker` / `TimetablePdfParser` / `backup/` 都是纯 Kotlin、零平台依赖，目前是**手工复制**关系。

**本期建议**：只把 `sync/` 的 DTO 与序列化抽成纯 JVM 库（Android 可直接依赖纯 JVM 库），其余保持现状。**不要在本期做全量 KMP 重构** —— 移动端是 Android 原生 Compose、桌面端是 Compose Multiplatform，改构建结构的风险远大于收益。

---

## 8. 传输安全（**相对 v1.0 大幅简化**）

实测确认 `deeer.online` 使用 **Let's Encrypt** 签发的证书：

```
issuer  = C=US, O=Let's Encrypt, CN=YE2
subject = CN=deeer.online
SAN     = deeer.online, www.deeer.online
有效期  = 2026-09-17 → 2026-12-16（acme.sh 自动续期）
```

**结论：两端零配置。** Android 与 JVM 都默认信任 Let's Encrypt 根证书，不需要 `networkSecurityConfig`、不需要导入 CA、不需要自定义 `SSLContext`。

**必须做的两件事**：

1. **客户端必须用 `https://deeer.online`，不能用 IP。** `172.24.55.32` 是 VPC 内网地址，公网不可达；`47.96.173.58` 裸 IP 访问会因为 SNI/证书域名不匹配而握手失败。
2. **禁止「信任所有证书」的 `TrustManager`**。那等于把 HTTPS 降级成 HTTP，凭证完全裸奔，而且给人"已经加密了"的错觉。

---

## 9. UI 形态

按「可选登录，本地优先」，登录入口**不能**做成启动拦截。

| 场景 | 形态 |
|---|---|
| 首次启动 | **不弹登录**，直接进主界面。设置页「账号」分组显示「未登录 · 点此登录」 |
| 主动登录 | 从设置页进入，打开登录覆盖层（沿用现有对话框基座，不新开窗口） |
| 未登录 | 所有功能照常，账号区显示同步不可用 |
| 已登录 | 显示邮箱、上次同步时间、「立即同步」、同步状态 |
| 同步冲突 | 不弹窗打断。账号区显示「上次同步覆盖了 N 条记录，点此查看」 |
| 令牌失效 | 静默尝试 refresh；失败则降级为未登录并**保留本地数据**，提示重新登录 |
| 首次同步（非首端设备） | **必须弹确认框**，明确告知「将以云端数据替换本地课表与便签，本地已自动备份到 &lt;路径&gt;」 |

**关键**：任何情况下都不能因为网络/登录问题导致本地数据不可用或丢失。登录态失效只是「同步暂停」，不是「数据没了」。

---

## 10. 分阶段交付计划

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **P0** | ✅ 服务端勘察 | 已完成 |
| **P1** | 服务端账号体系：建库 + 注册 / 登录 / refresh / 改密 | 邮件通道决策（§11.1） |
| **P2** | 桌面端接入：网络层 + TokenStore + 登录 UI | P1 |
| **P3** | 两端数据层改造：uid / updatedAt / deletedAt + 迁移 | **无依赖，可立即开始** |
| **P4** | 服务端同步接口 + SyncEngine | P1 + P3 |
| **P5** | 移动端接入：权限 + 网络层 + 登录 UI | P2 |
| **P6** | GitHub / Google OAuth 绑定 | P1 |
| **P7** | 设备管理、冲突提示、同步状态 UI | P4 |

**建议从 P3 起步** —— 它不依赖服务端任何信息，是同步的地基，且能独立验证（145 个单测必须保持全绿）。

### 服务端部署流程（现无 git，手工部署）

```bash
cd /var/www/deeer
# 1. 备份（现有习惯，必须包含 stumate.db）
tar czf /root/deeer-site-backup-$(date +%Y%m%d-%H%M%S).tgz \
    --exclude=node_modules --exclude=.next .
cp /var/lib/deeer/stumate.db /root/stumate-db-$(date +%Y%m%d).db

# 2. 改代码（新增 app/api/stumate/**、lib/stumate/**）

# 3. 构建 + 重启
npm run build && pm2 restart deeer

# 4. 验证
curl -s https://deeer.online/api/stumate/v1/sync/status
```

**新增环境变量必须写进 pm2 配置**（`pm2 set` 或重新 `pm2 start` 带 `--update-env`），因为项目里**没有 `.env` 文件**，配置全部存在 pm2 的 env 里。重启后不生效的话检查 `/root/.pm2/dump.pm2`。

---

## 11. 待确认的问题

### 阻塞级

**1. 邮件通道（新发现，必须解决）**

实测结果：
- `postfix` 已安装但**处于 inactive / disabled 状态**
- **阿里云默认封禁 25 端口出站**（已实测确认，连 `smtp.qq.com:25` 超时）

所以目前**没有任何可用的发信通道**。而邮箱主账号至少需要它来发找回密码邮件。三个选项：

- **a) 接阿里云邮件推送（DirectMail）**：同厂商、内网调用、有免费额度，需要开通并拿到 AccessKey。**推荐**
- **b) 接第三方（Resend / SendGrid）**：走 465/587，注册即用，需要 API Key
- **c) v1 先不做邮箱验证，注册走邀请制**：由你在服务器上手动放行。适合"只有我自己用"的场景

请选一个。**若选 c，注册接口需要额外的邀请码机制。**

**2. 部署方式**

现在没有 git 仓库，靠手工改文件 + 备份 tgz。StuMate 的服务端代码是要长期演进的，建议：

- a) 在服务器上初始化 git，本地开发后 push 部署
- b) 保持手工部署（我会把每次改动写成可复制的脚本）
- c) 用 `rsync` 从本机同步

**3. 数据库位置确认**

真实数据库在 `/var/lib/deeer/site.db`（由 pm2 的 `DATABASE_PATH` 指定），项目目录下的 `data/site.db` 是废弃副本。StuMate 用独立文件 `/var/lib/deeer/stumate.db` 可以吗？

### 决策级

4. **StuMate 用户规模**：只有你自己用，还是打算给别人用？这决定要不要做注册限流、邀请码、邮箱验证的强度。
5. **是否需要「注销账号」**：涉及《个人信息保护法》的数据删除义务，做的话要定义冷静期。
6. **服务器内存只有 1.8 GB**：scrypt 每次哈希占 64 MB。若预计会有较多并发注册/登录，需要把参数降到 `N=2^15`（32 MB）。请告知预期规模。

### 细节级

7. 昵称 / 头像本期要不要？
8. 同步触发时机（启动 + 写操作后 30 秒防抖 + 手动）是否接受？
9. 登录 UI 只做中文，还是要预留多语言？

---

## 附录 A：与现有代码/环境的兼容性检查表

| 现有能力 | 是否受影响 | 说明 |
|---|---|---|
| 站点现有 API | **不受影响** | StuMate 全部挂在 `/api/stumate/v1/**`，与 `/api/auth/**` 等无交集 |
| 站点 SQLite 库 | **不受影响** | 用独立库文件 `stumate.db`（§6.1） |
| 站点限流器 | 不受影响 | StuMate 另建计数表，避免与评论/留言互相挤占配额 |
| nginx 配置 | **不需要改** | 现有 `location /` 已经全部转发到 3000 端口，新路由自动生效 |
| pm2 进程 | 需重启一次 | `pm2 restart deeer`；新增环境变量要同步更新 pm2 配置 |
| TLS 证书 | 不需要改 | Let's Encrypt 已覆盖 `deeer.online` |
| 站点备份习惯 | **需扩展** | 备份必须包含 `/var/lib/deeer/stumate.db` |
| 145 个单元测试 | 不受影响 | 改的是数据层与 UI 层，纯逻辑（TimeAxis / WeekSchedule 等）不动 |
| 两端备份功能 | 向后兼容 | `SCHEMA` 1→2，新字段可选，旧备份文件仍可读（§5.6） |
| 两端 `.db` 互开 | 升级后需同版本 | 加列后 v8 的库，v7 客户端打不开。两端必须同步升级 |
| 现有 `settings.json` | 不受影响 | 凭证单独存 `credentials.bin`，不进 `settings.json` |
| 课表 PDF 导入 | 不受影响 | 导入走正常 insert，自动获得 uid |
| 提醒引擎 | 不受影响 | 只读本地库 |

## 附录 B：安全红线（实现时逐条核对）

1. refresh_token 必须可吊销、必须轮转、重放即吊销整条链
2. 密码只存 scrypt 哈希，永不落明文、永不进日志
3. 令牌**只存 SHA-256 哈希**，数据库泄露也不可直接使用
4. 客户端凭证落盘必须经 DPAPI（桌面）/ Keystore（移动），不得明文
5. 禁止「信任所有证书」的 TrustManager
6. 找回密码接口恒定返回，不泄露邮箱是否注册
7. 第三方绑定**不允许按邮箱自动合并账号**
8. 解绑前校验，防止把用户锁在门外
9. 登录/注册接口必须限流（scrypt 的 64 MB 内存占用可被用于 DoS）
10. 日志脱敏：邮箱部分掩码，token 永不打印
11. **首次同步替换本地数据前必须自动备份**，且 UI 明确告知路径
