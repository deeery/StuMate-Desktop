# StuMate 账号与云同步系统 · 设计方案 v1.0

> 覆盖范围：`StuMate-Desktop`（Compose Multiplatform Desktop）+ `StuMate-Mobile`（`ClassReminderNewest`，Android 原生 Compose）+ 服务端
> 编写日期：2026-09-30 · 状态：**待评审**

---

## 0. 前置：现状勘察结论

在动手设计前先扫了两个仓库的全部源码，有五条结论直接决定了方案走向。

### 0.1 两端都是「零网络层」

| 项目 | 技术栈 | 网络依赖 | 数据层 |
|---|---|---|---|
| StuMate-Desktop | Kotlin + Compose Multiplatform 1.5.10 | **无**（无 OkHttp / Ktor / Retrofit） | sqlite-jdbc + 手写 DAO |
| StuMate-Mobile | Kotlin + Android Compose + Room 2.6.1 | **无** | Room v7 |

所以「加登录」不是加一个页面，而是**两端各新增一整套网络层**：

- HTTP 客户端 + JSON 序列化
- 凭证安全存储
- 同步引擎（增量拉取 / 推送 / 冲突处理）

移动端还额外缺一条权限 —— `AndroidManifest.xml` 里**没有任何 `INTERNET` 权限**（只有 FOREGROUND_SERVICE / POST_NOTIFICATIONS / BOOT_COMPLETED 等）。不加它，所有请求直接抛 `SecurityException`。

### 0.2 已有资产：模块化备份格式，可直接复用为同步载荷

两端都已经有一套完整的备份系统（`data/backup/`）：

```kotlin
enum class BackupModule(val key: String, val title: String) {
    COURSES("courses", "课程表"),
    NOTES("notes", "便签"),
    SETTINGS("settings", "设置");
}
object BackupFormat {
    const val FORMAT = "stumate-backup"   // 挡掉「随手选了个别的 JSON」
    const val SCHEMA = 1
}
data class BackupDocument(
    val schema: Int, val app: String, val exportedAt: Long,
    val modules: Map<BackupModule, JsonValue>
)
```

这份编解码是**纯 Kotlin、不依赖平台**，两端字段一致。**同步载荷直接复用它**，而不是另发明一套 JSON —— 少一套格式就少一处会不一致的地方。详见 §5.6。

### 0.3 两端实体字段刻意对齐

`ClassEntity`（10 列）与 `NoteEntity`（8 列）在两端逐列一致，建表 SQL 与 Room v7 导出的 schema 相同，`.db` 文件可以互相打开。**同步层可以共用一套数据模型。**

### 0.4 服务端地址与可达性

- 需求里给的 `172.24.55.32` 是**内网地址**，本机 `ping` 两次 100% 丢包。
- 你另外提供的 SSH 信息指向公网 `47.96.173.58:22`（阿里云 IP 段），推测 `172.24.55.32` 是同一台机器在 VPC 内的私有网卡地址，或需要 VPN 才能访问。
- **阻塞**：SSH 私钥路径 `C:\Users\21399.openclaw\workspace.secrets\workbuddy_ed25519` 在**本机不存在**（本机只有 `C:\Users\Administrator`，全盘也搜不到 `workspace.secrets` 或 `workbuddy_ed25519`）。该路径属于另一个环境，我无法连上服务器核实后端现状。

### 0.5 同步的核心障碍：主键是本机自增的 `Int`

这是整个方案里**唯一一个不改数据模型就绕不过去的问题**。

```kotlin
// 两端完全一样
@Entity(tableName = "classes")
data class ClassEntity(@PrimaryKey val id: Int, ...)   // 注意：没有 autoGenerate
```

`id` 是应用自己算出来的（非数据库自增）。于是：

```
桌面端新建一门课  → id = 5（高等数学）
移动端新建一条课  → id = 5（数据结构）
两端同步       → 撞号，谁覆盖谁？
```

`notes` 表同理。**任何同步方案都必须先解决这件事**，否则数据必然互相踩踏。

---

## 1. 目标与非目标

### 目标

1. 邮箱 + 密码为主账号，支持注册、登录、找回密码、邮箱验证
2. 支持绑定第三方账号（OAuth2），绑定后可用于快捷登录
3. 登录后，**课程表与便签**在桌面端与移动端之间云同步
4. **不破坏现有体验**：不登录照常单机使用，登录才开启同步
5. 两端配置方式一致（同一套协议、同一套数据模型）

### 非目标（本期不做）

- 多人协作 / 共享课表
- 设置类数据（主题、周数校准、表格-列表偏好）的云同步 —— 按你的选择留在本地
- 端到端加密（服务端可见明文数据）
- Web 端

---

## 2. 总体架构

```
┌──────────────────────┐          ┌──────────────────────┐
│  StuMate-Desktop     │          │  StuMate-Mobile      │
│  (Compose Desktop)   │          │  (Android + Room)    │
│                      │          │                      │
│  ┌────────────────┐  │          │  ┌────────────────┐  │
│  │  ui/fluent     │  │          │  │  ui/           │  │
│  └───────┬────────┘  │          │  └───────┬────────┘  │
│  ┌───────▼────────┐  │          │  ┌───────▼────────┐  │
│  │ MainViewModel  │  │          │  │ MainViewModel  │  │
│  └───────┬────────┘  │          │  └───────┬────────┘  │
│  ┌───────▼────────┐  │          │  ┌───────▼────────┐  │
│  │ 本地 DAO       │  │          │  │  Room DAO      │  │
│  │ (sqlite-jdbc)  │  │          │  │                │  │
│  └───────┬────────┘  │          │  └───────┬────────┘  │
│  ┌───────▼────────┐  │          │  ┌───────▼────────┐  │
│  │ SyncEngine  ◄──┼──┼──────────┼──┼──► SyncEngine   │  │
│  │ TokenStore     │  │          │  │    TokenStore  │  │
│  └───────┬────────┘  │          │  └───────┬────────┘  │
└──────────┼───────────┘          └──────────┼───────────┘
           │  HTTPS (JWT Bearer)             │
           └────────────┬────────────────────┘
                        ▼
        ┌───────────────────────────────────────┐
        │  StuMate Server  (47.96.173.58)       │
        │                                       │
        │  /auth/*   账号与令牌                  │
        │  /oauth/*  第三方绑定（服务端中介）      │
        │  /me/*     用户与设备                  │
        │  /sync/*   增量同步                    │
        │                                       │
        │  PostgreSQL / MySQL                   │
        └───────────────┬───────────────────────┘
                        │ OAuth2 授权码
        ┌───────────────▼───────────────────────┐
        │  第三方 IdP：GitHub / Google / 微信 / QQ │
        └───────────────────────────────────────┘
```

**关键设计取舍：OAuth 由服务端中介，客户端不直接对接 IdP。**

理由：桌面端是原生应用，微信开放平台的「网站应用」只接受**已备案域名**作为回调地址，`http://127.0.0.1:port/callback` 不被接受。所以走服务端中介 + 轮询，两端共用同一条路径：

```
客户端  POST /oauth/{provider}/start      →  { state, authorize_url }
客户端  打开系统浏览器 / 显示二维码
用户在浏览器完成授权 → IdP 回调服务端
服务端  记下 state 对应的用户，签发一次性 ticket
客户端  GET  /oauth/{provider}/poll?state →  { status, access_token, refresh_token }
```

对 GitHub / Google 这类允许回环地址的 IdP，服务端可以额外支持 `127.0.0.1` 直连回调以省掉轮询；但**默认走轮询**，两端实现只有一份。

---

## 3. 账号体系

### 3.1 主账号：邮箱 + 密码

| 项 | 方案 | 理由 |
|---|---|---|
| 邮箱唯一性 | 唯一索引，**存储前统一 `trim()` + 小写归一化** | 否则 `A@x.com` 和 `a@x.com` 会注册出两个账号 |
| 密码哈希 | **Argon2id**（`m=64MiB, t=3, p=1`） | 2026 年的推荐默认。若服务端语言生态不便，退而求其次用 bcrypt（cost ≥ 12） |
| 密码强度 | 最少 8 位，禁止与邮箱相同，**不做复杂度强制** | 长度比字符种类更有效，且不会逼用户写便利贴 |
| 邮箱验证 | 注册后发验证邮件，**未验证可登录但同步受限** | 兼顾转化率与防滥用 |
| 找回密码 | 邮件一次性 token，**有效期 30 分钟、用后即焚** | 不存明文 token，只存其哈希 |
| 登录失败 | 按「邮箱 + IP」双维度限流，5 次后指数退避 | 防撞库 |

**注意一个服务端细节**：阿里云 ECS 默认封禁 25 端口，发信必须走 465/587，或者直接接第三方邮件服务（阿里云邮件推送 / SendGrid）。这一点会直接影响后端实现，需要跟后端确认。

### 3.2 第三方绑定

绑定表独立于用户表，一个用户可绑多个平台，一个平台账号只能绑一个 StuMate 账号。

```
oauth_binding
  id, user_id, provider, provider_uid, provider_email,
  access_token(加密), refresh_token(加密), expires_at, created_at
  UNIQUE(provider, provider_uid)
```

**绑定规则**（这是最容易出安全漏洞的地方，明确写死）：

1. 已登录状态下发起绑定 → 直接绑到当前账号
2. 未登录状态下用第三方登录：
   - `provider_uid` 已存在绑定 → 直接登录
   - 不存在绑定，但 IdP 返回的邮箱**已验证**且与某个已存在账号一致 → **不自动合并**，而是要求用户先用邮箱密码登录后再绑定
     - 理由：自动合并 = 任何人只要能在 IdP 侧控制一个同邮箱账号，就能接管目标账号
   - 其余情况 → 走「补充注册」，要求设置密码后建号
3. 解绑前校验：**必须已设置密码或已绑其他平台**，否则会把自己锁在门外

### 3.3 第三方平台选型

| 平台 | 两端可行性 | 前置条件 | 建议 |
|---|---|---|---|
| GitHub | 高 | 注册 OAuth App 即可 | **首选**，无资质门槛 |
| Google | 中 | 需能访问 | 可选 |
| 微信 | 低 | **需企业主体 + 开放平台认证（300 元/年）**，且回调域名需备案 | 需你确认是否有资质 |
| QQ | 低 | 同上，需企业主体 | 同上 |

**待确认**：微信 / QQ 是否已有开放平台账号？若没有，建议本期只做 GitHub（+ Google），把 provider 做成可插拔，后续加平台只加配置不改代码。

---

## 4. 认证与会话

### 4.1 令牌方案：JWT access + 不透明 refresh

| 令牌 | 形式 | 有效期 | 存储位置 |
|---|---|---|---|
| access_token | JWT（HS256 或 RS256） | **15 分钟** | 内存（不落盘） |
| refresh_token | 不透明随机串（非 JWT） | **30 天**，滑动续期 | 加密落盘 |

**为什么 refresh 不用 JWT**：refresh 必须可吊销（用户改密码、踢设备）。JWT 无状态、签出去就收不回；不透明串在数据库里一行 `revoked_at` 就能立刻失效。

**刷新采用轮转（rotation）**：每次刷新都签发新的 refresh 并作废旧的；若检测到**已作废的 refresh 被再次使用**，判定为令牌泄露，**吊销该设备整条令牌链**并要求重新登录。这是 OAuth 2.1 的推荐做法。

### 4.2 凭证安全存储

| 平台 | 方案 |
|---|---|
| Desktop (Windows) | **DPAPI**（`CryptProtectData`，按当前用户加密），落盘到 `%APPDATA%\StuMate\credentials.bin`。JNA 已在依赖里（`net.java.dev.jna:jna:5.6.0`），直接调 `crypt32` 即可，无需新依赖 |
| Mobile (Android) | **EncryptedSharedPreferences**（`androidx.security:security-crypto`）+ Android Keystore 托管主密钥 |

**明确禁止**：把 token 明文写进现有的 `settings.json`。那是纯文本、还会被备份功能导出。

### 4.3 设备管理

每次登录登记一条设备记录（设备名、平台、最后活跃时间、当前 refresh 令牌 id）。

- 设置页可查看已登录设备并「退出该设备」
- 改密码时**吊销除当前设备外的所有令牌**
- 上限 10 台，超出时淘汰最久未活跃的

---

## 5. 同步协议（核心）

### 5.1 难题：本地主键不可用

`id: Int` 是本机分配的，跨设备必然撞号（§0.5）。**服务端分配 id 也不行** —— 那要求创建记录时必须联网，直接摧毁离线优先。

### 5.2 解法：新增全局 `uid`，本地主键保持不动

给 `classes` 和 `notes` 各加一列 `uid TEXT NOT NULL`（UUIDv4），**同步层只认 `uid`，本地 DAO 继续用 `id`**。

这样做的关键好处：**现有 UI、DAO、ViewModel 的查询逻辑一行都不用改**，只在同步边界做一层映射。

```
本地表 classes
  id      INTEGER  ← 本地主键，UI/DAO 继续用，不变
  uid     TEXT     ← 新增，全局唯一，同步用
  ...
```

写入时机：任何 insert 都先生成 `uid = UUID.randomUUID().toString()`。

同时新增两列同步元数据：

| 列 | 类型 | 含义 |
|---|---|---|
| `uid` | TEXT | 全局唯一标识，UUIDv4 |
| `updatedAt` | INTEGER | 本地最后修改时刻（epoch ms），每次写操作刷新 |
| `deletedAt` | INTEGER | 软删除标记，`0` = 未删除 |

### 5.3 软删除：不做就会「删了又活」

现有 DAO 的 delete 是 `DELETE FROM`。同步场景下这是错的：

```
设备A 删除课程X  →  本地行消失
设备B 不知道这件事  →  下次同步把课程X 推上去
结果：课程X 复活
```

所以 **delete 改成 `UPDATE ... SET deletedAt = ?, updatedAt = ?`**，所有查询加 `WHERE deletedAt = 0`。

代价：数据不会真正消失。缓解：服务端保留 90 天，客户端每次同步成功后清理 `deletedAt` 超过 30 天的行。

### 5.4 增量同步：服务端 revision 游标

服务端为每个用户的变更维护一条**单调递增的 revision 序号**：

```
sync_log
  revision    BIGSERIAL      -- 全局单调递增
  user_id     BIGINT
  entity      TEXT           -- 'class' | 'note'
  uid         TEXT
  op          TEXT           -- 'upsert' | 'delete'
  payload     JSONB
  updated_at  TIMESTAMPTZ
```

客户端持有 `lastCursor`（就是上次拿到的最大 revision）。

- **拉取**：`GET /sync/pull?cursor=N` → 返回 `revision > N` 的全部变更 + 新游标
- **推送**：`POST /sync/push` → 提交本地变更，服务端逐条合并，返回合并结果 + 新游标

**游标不用时间戳**：设备时钟不可信（用户改系统时间、时区漂移），用时间戳做游标会漏数据。单调序号没有这个问题。

### 5.5 冲突解决：版本号 + LWW

每条记录在服务端维护一个 `version`（每次写入 +1）。客户端推送时带上自己改动前的 `baseVersion`：

```
baseVersion == 服务端 version   → 无冲突，直接写入，version += 1
baseVersion <  服务端 version   → 冲突
```

**冲突时用 LWW（Last-Write-Wins）**，比较客户端 `updatedAt` 与服务端 `updated_at`，保留较新的一方，并把最终结果**回吐给客户端**（客户端据此覆盖本地）。

选 LWW 而非字段级合并的理由：StuMate 是**单人多设备**场景，不是多人协作。同一个人在两台设备上同时改同一条便签的概率极低，而字段级合并要处理「双方都改了同一个字段」「一方改了A字段一方改了B字段」等分支，复杂度远高于收益。

**已知限制（明确记录，不隐藏）**：如果两台设备在**离线状态下**修改同一条记录，后同步的一方会覆盖先同步的一方，且先同步方的改动会丢失。缓解措施：
- 推送响应里返回被覆盖的记录清单
- 客户端在设置页展示「最近一次同步覆盖了 N 条记录」
- 极端情况下用户可用已有的**备份功能**兜底

### 5.6 载荷格式：复用备份格式

同步用的 JSON **沿用 `stumate-backup` 的形状**，只是每条记录多带三个同步元数据字段：

```json
{
  "schema": 2,
  "app": "StuMate",
  "exportedAt": 1790000000000,
  "cursor": 1024,
  "modules": {
    "courses": [
      {
        "uid": "9f1c...-uuid",
        "id": 5,
        "title": "高等数学",
        "dayOfWeek": "Monday",
        "startTime": "08:00",
        "endTime": "09:35",
        "room": "教二 305",
        "notes": "",
        "teacher": "王海燕",
        "weeks": "1-16周",
        "date": "",
        "updatedAt": 1789999999000,
        "deletedAt": 0
      }
    ],
    "notes": []
  }
}
```

- 备份格式 `SCHEMA` 从 1 升到 2，**新增字段全部可选**，导入 v1 旧备份时自动补 `uid`（现场生成）、`updatedAt = 0`、`deletedAt = 0` → **旧备份文件继续可读**，不会打破现有功能。
- 这样备份、恢复、同步**共用一套编解码**，只有一份需要维护。

### 5.7 API 契约

```
── 账号 ────────────────────────────────────────────────
POST   /api/v1/auth/register              { email, password }              → { user, tokens }
POST   /api/v1/auth/login                 { email, password, device }      → { user, tokens }
POST   /api/v1/auth/refresh               { refresh_token }                → { tokens }
POST   /api/v1/auth/logout                { refresh_token }                → 204
POST   /api/v1/auth/email/verify          { token }                        → 204
POST   /api/v1/auth/password/forgot       { email }                        → 204（恒定返回，防枚举）
POST   /api/v1/auth/password/reset        { token, new_password }          → 204
POST   /api/v1/auth/password/change       { old_password, new_password }   → { tokens }

── 第三方绑定 ──────────────────────────────────────────
POST   /api/v1/oauth/{provider}/start                                      → { state, authorize_url }
GET    /api/v1/oauth/{provider}/poll      ?state=...                       → { status, tokens? }
POST   /api/v1/oauth/{provider}/bind      (已登录)                          → { binding }
DELETE /api/v1/oauth/{provider}/bind      (已登录)                          → 204

── 用户与设备 ──────────────────────────────────────────
GET    /api/v1/me                                                          → { user, bindings }
PATCH  /api/v1/me                         { nickname? }                     → { user }
GET    /api/v1/me/devices                                                  → [ device ]
DELETE /api/v1/me/devices/{device_id}                                      → 204

── 同步 ────────────────────────────────────────────────
GET    /api/v1/sync/pull?cursor=N&limit=500                                → { cursor, changes[], hasMore }
POST   /api/v1/sync/push                  { cursor, changes[] }            → { cursor, applied[], conflicts[] }
GET    /api/v1/sync/status                                                 → { cursor, counts }
```

统一约定：
- 认证：`Authorization: Bearer <access_token>`
- 错误体：`{ "error": { "code": "EMAIL_TAKEN", "message": "..." } }`
- 幂等：`POST /sync/push` 支持 `Idempotency-Key` 头，网络重试不会写重

### 5.8 同步时序

```
客户端                          服务端
  │                               │
  │  POST /sync/push              │
  │  { cursor: 1024, changes:[…] }│
  │──────────────────────────────►│
  │                               │ 逐条合并（version + LWW）
  │                               │ 写 sync_log，revision 递增
  │  { cursor: 1050,              │
  │    applied: […],              │
  │    conflicts: […] }           │
  │◄──────────────────────────────│
  │                               │
  │  本地应用 conflicts（覆盖）      │
  │                               │
  │  GET /sync/pull?cursor=1050   │
  │──────────────────────────────►│
  │  { cursor: 1062, changes:[…] }│
  │◄──────────────────────────────│
  │  本地 upsert（按 uid）          │
  │                               │
  │  保存 cursor = 1062            │
```

**触发时机**：应用启动后、网络恢复时、本地写操作后 30 秒防抖、手动点「立即同步」、切到前台。

### 5.9 便签 `position` 的排序冲突

`position` 是本地排序索引，拖动排序时会整批重写。同步它有两个后果：

- 两端同时重排 → LWW 会丢掉一方的顺序
- 但若不同步 → 用户会看到两端顺序不一致，更困惑

**建议：`position` 作为普通字段参与 LWW 同步**，接受「同时重排会丢一方」这个限制。理由同上——单人多设备场景下概率低，且顺序是可轻易恢复的非破坏性信息。

---

## 6. 数据模型

### 6.1 服务端表

```sql
users (
  id BIGSERIAL PK,
  email CITEXT UNIQUE NOT NULL,
  password_hash TEXT,                  -- 第三方注册时可能为空，首次设密码后填
  email_verified_at TIMESTAMPTZ,
  nickname TEXT,
  created_at, updated_at TIMESTAMPTZ
)

refresh_tokens (
  id BIGSERIAL PK,
  user_id BIGINT REFERENCES users,
  device_id BIGINT REFERENCES devices,
  token_hash TEXT UNIQUE,              -- 只存哈希
  issued_at, expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,              -- 轮转/吊销
  replaced_by BIGINT                   -- 轮转链，用于检测重放
)

devices (
  id BIGSERIAL PK,
  user_id BIGINT REFERENCES users,
  name TEXT, platform TEXT,            -- 'desktop' | 'android'
  last_seen_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ
)

oauth_binding (
  id BIGSERIAL PK, user_id BIGINT REFERENCES users,
  provider TEXT, provider_uid TEXT, provider_email TEXT,
  access_token TEXT, refresh_token TEXT,   -- 应用层加密
  expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ,
  UNIQUE(provider, provider_uid)
)

records (                              -- 课程与便签统一存这张表
  user_id BIGINT REFERENCES users,
  entity TEXT,                         -- 'class' | 'note'
  uid TEXT,
  data JSONB,                          -- 业务字段
  version BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ,
  PRIMARY KEY (user_id, entity, uid)
)

sync_log (                             -- 见 §5.4
  revision BIGSERIAL PK, user_id BIGINT, entity TEXT,
  uid TEXT, op TEXT, payload JSONB, updated_at TIMESTAMPTZ
)
```

### 6.2 两端本地库迁移

| 平台 | 版本 | 迁移动作 |
|---|---|---|
| Desktop | `Db.SCHEMA_VERSION` 7 → **8** | 新库直接建到 v8；老库 `ALTER TABLE classes/notes ADD COLUMN uid/updatedAt/deletedAt`。桌面端 `migrate()` 目前只处理「全新库」，需要补一条增量迁移分支 |
| Mobile | Room v7 → **v8** | 新增 `Migration(7, 8)`，同样三列；`uid` 回填需用 `UPDATE ... SET uid = ...` 逐行生成（Room 的 `Migration` 里可用 `SupportSQLiteDatabase` 查询后循环） |

**回填注意**：给已有数据生成 `uid` 时，**两端各自生成会导致同一条历史数据在两端得到不同 uid**，首次同步时会变成两条记录。这是一个必须提前决定的产品问题 —— 见 §11 待确认问题 6。

---

## 7. 客户端改造清单

### 7.1 桌面端（StuMate-Desktop）

| 项 | 内容 |
|---|---|
| 依赖 | Ktor Client（CIO 引擎）或 OkHttp + `kotlinx-serialization-json` |
| 新增包 | `net/`（ApiClient、TokenStore、SyncEngine、Dto） |
| 数据层 | `Db.kt` 加 v8 迁移；`ClassDao` / `NoteDao` 的 delete 改软删除，insert/update 写 `updatedAt` |
| 凭证 | `net/DpapiStore.kt`，JNA 调 `crypt32.CryptProtectData` |
| UI | 登录覆盖层（复用现有 `OverlayScreen.kt` 的对话框基座）；设置页新增「账号」分组（登录状态 / 同步开关 / 立即同步 / 已登录设备 / 退出登录） |
| 触发 | 应用启动、写操作后防抖、手动同步 |

### 7.2 移动端（ClassReminderNewest）

| 项 | 内容 |
|---|---|
| 权限 | **`AndroidManifest.xml` 加 `INTERNET` + `ACCESS_NETWORK_STATE`** |
| 依赖 | OkHttp + `kotlinx-serialization-json`；`androidx.security:security-crypto:1.1.0-alpha06` |
| 新增包 | `net/`（与桌面端同构） |
| 数据层 | Room `Migration(7, 8)`；DAO delete 改软删除 |
| 凭证 | EncryptedSharedPreferences |
| UI | 登录页（Compose）；设置页新增「账号」分组 |
| 网络配置 | 若服务端用自签证书，需加 `network_security_config.xml`（见 §8） |

### 7.3 可共享的代码

两端 `data/` 下的 `TimeAxis` / `TodaySchedule` / `WeekSchedule` / `TodayNotePicker` / `TimetablePdfParser` / `backup/` 都是**纯 Kotlin、零平台依赖**，目前是**手工复制**的关系。

- **建议**：同步协议相关的 DTO + 编解码抽成独立模块，两端共用。
- **但**：移动端是 Android 原生 Compose、桌面端是 Compose Multiplatform，做完整 KMP 共享需要改构建结构，风险不小。
- **折中**：本期只把 `sync/` 的 DTO 与序列化抽成纯 JVM 库（Android 可直接依赖纯 JVM 库），其余保持现状。**不要在本期做全量 KMP 重构。**

---

## 8. 传输安全

你确认服务端**已有 HTTPS 证书**。需要进一步确认它是**公信 CA 签发**还是**自签/内网 CA** —— 这决定了两端要不要额外配置。

### 情况 A：公信 CA 签发（如 Let's Encrypt）

两端**零配置**，直接用。这是最理想的情况。

### 情况 B：自签 / 内网 CA

- **Android**：默认信任系统 CA 列表，自签证书会导致 `SSLHandshakeException`。需要在 `network_security_config.xml` 里声明信任锚，并在 Manifest 引用：

  ```xml
  <network-security-config>
    <domain-config>
      <domain includeSubdomains="true">47.96.173.58</domain>
      <trust-anchors>
        <certificates src="@raw/stumate_ca" />
      </trust-anchors>
    </domain-config>
  </network-security-config>
  ```

- **Desktop**：把 CA 证书导入应用私有的 truststore（不要动系统 JVM 的 `cacerts`），用自定义 `SSLContext` 加载。

**明确禁止的做法**：写一个「信任所有证书」的 `X509TrustManager`。那等于把 HTTPS 降级成 HTTP，凭证在链路上完全裸奔 —— 比直接用 HTTP 更危险，因为它给人一种「已经加密了」的错觉。

**证书固定（certificate pinning）**：暂不建议。自签证书轮换时会直接导致全端不可用，而内网环境被中间人的风险本就低。

---

## 9. UI 形态

按你选的「可选登录，本地优先」，登录入口**不能**做成启动拦截。

| 场景 | 形态 |
|---|---|
| 首次启动 | **不弹登录**，直接进主界面。设置页「账号」分组显示「未登录 · 点此登录」 |
| 主动登录 | 从设置页进入，打开登录覆盖层（沿用现有对话框基座，不新开窗口） |
| 未登录 | 所有功能照常，设置页账号区显示同步不可用 |
| 已登录 | 账号区显示邮箱、上次同步时间、「立即同步」按钮、同步状态（空闲/同步中/失败） |
| 同步冲突 | 不弹窗打断。在账号区显示「上次同步覆盖了 N 条记录，点此查看」 |
| 令牌失效 | 静默尝试 refresh；失败则降级为未登录并**保留本地数据**，提示重新登录 |

**关键**：任何情况下都不能因为网络/登录问题导致本地数据不可用或丢失。登录态失效只是「同步暂停」，不是「数据没了」。

---

## 10. 分阶段交付计划

| 阶段 | 内容 | 产出 | 依赖 |
|---|---|---|---|
| **P0** | 服务端接口现状核实 | 接口清单 / OpenAPI 文档 | **需服务器访问权限** |
| **P1** | 服务端账号体系：注册 / 登录 / refresh / 找回密码 | 可用 API | 后端技术栈确定 |
| **P2** | 桌面端接入：网络层 + TokenStore + 登录 UI | 桌面端能注册登录 | P1 |
| **P3** | 两端数据层改造：uid / updatedAt / deletedAt + 迁移 | 本地库升级，功能不回退 | 无（可与 P1 并行） |
| **P4** | 服务端同步接口 + SyncEngine | 两端数据互通 | P1 + P3 |
| **P5** | 移动端接入：权限 + 网络层 + 登录 UI | 移动端能登录同步 | P2 的代码可复用 |
| **P6** | 第三方 OAuth 绑定 | 绑定 / 快捷登录 | 各平台资质 |
| **P7** | 设备管理、冲突提示、同步状态 UI | 完整体验 | P4 |

**建议从 P3 起步**：它不依赖任何服务端信息，且是同步的地基。即使后端迟迟定不下来，这部分改造也必须要做，且能独立验证（145 个单测必须保持全绿）。

---

## 11. 待你确认的问题

按阻塞程度排序。

### 阻塞级（不解决无法推进）

1. **服务器访问方式**
   私钥路径 `C:\Users\21399.openclaw\workspace.secrets\workbuddy_ed25519` 在本机不存在。请三选一：
   - 把私钥放到本机某个路径并告诉我（例如 `C:\Users\Administrator\.ssh\workbuddy_ed25519`）
   - 直接告诉我后端的技术栈与已有接口清单
   - 确认「已有后端」的具体含义（是已有完整服务，还是只有服务器环境）

2. **后端技术栈**
   Node / Go / Java(Ktor/Spring) / Python(Django/FastAPI)？决定接口是「对接现有」还是「我定义契约、后端实现」。

3. **HTTPS 证书类型**
   公信 CA 还是自签？决定两端要不要配信任锚（§8）。

### 决策级（影响方案走向）

4. **第三方平台选型**
   微信 / QQ 是否已有企业主体与开放平台认证？若没有，建议本期只做 GitHub（+ Google）。

5. **冲突策略是否接受 LWW**
   即「两端离线同时改同一条，后同步的覆盖先同步的」。若不接受，需要升级为字段级合并（工作量约 +2 倍）。

6. **历史数据的 uid 回填策略** ← 这个很容易被忽略但必须先定
   两端各自为已有数据生成 `uid`，会导致同一条历史课程在两端得到不同 uid，首次同步时变成**两条重复记录**。三个选项：
   - **a) 首次登录时以一端为准**：上传方（比如桌面端）把本地数据作为初始数据，另一端**清空后全量拉取**（推荐，最简单且结果确定）
   - b) 内容指纹匹配：用「标题+星期+时间」算哈希做去重（脆弱，同名课程会误合并）
   - c) 不做处理，允许出现重复，让用户手动删

7. **是否需要「注销账号」**
   涉及数据删除的合规义务（《个人信息保护法》要求提供删除途径），做的话要定义冷静期。

### 细节级

8. 移动端确认：就是 `C:\Users\Administrator\IdeaProjects\ClassReminderNewest` 吗？
9. 昵称/头像：本期要不要？
10. 同步频率：是否接受「启动 + 写操作后 30 秒防抖 + 手动」这套触发时机？
11. 登录 UI 语言：只做中文，还是要预留多语言？

---

## 附录 A：与现有代码的兼容性检查表

| 现有能力 | 是否受影响 | 说明 |
|---|---|---|
| 145 个单元测试 | **不受影响** | 改的都是数据层与 UI 层，纯逻辑（TimeAxis / WeekSchedule 等）不动 |
| 模块化备份 / 恢复 | 向后兼容 | `SCHEMA` 1→2，新字段可选，旧备份文件仍可读（§5.6） |
| 两端 `.db` 互开 | 升级后需同版本 | 加列后 v8 的库，v7 的客户端打不开（SQLite 列数不符）。两端必须同步升级 |
| 现有 `settings.json` | 不受影响 | 凭证单独存 `credentials.bin`，不进 `settings.json`（§4.2） |
| 课表 PDF 导入 | 不受影响 | 导入后走正常 insert，自动获得 uid |
| 提醒引擎 | 不受影响 | 只读本地库 |

## 附录 B：安全红线（实现时逐条核对）

1. refresh_token 必须可吊销、必须轮转、重放即吊销整条链
2. 密码只存 Argon2id/bcrypt 哈希，永不落明文、永不进日志
3. 凭证落盘必须经 DPAPI（桌面）/ Keystore（移动），不得明文
4. 禁止「信任所有证书」的 TrustManager
5. 找回密码接口恒定返回，不泄露邮箱是否注册
6. 第三方绑定**不允许按邮箱自动合并账号**
7. 解绑前校验，防止把用户锁在门外
8. 日志脱敏：邮箱部分掩码，token 永不打印
