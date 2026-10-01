# StuMate 账号与云同步系统 · 设计方案 v1.3

> 覆盖范围：`StuMate-Desktop`（Compose Multiplatform Desktop）+ `StuMate-Mobile`（`ClassReminderNewest`，Android 原生 Compose）+ 服务端
> 编写日期：2026-09-30 · 状态：**已定稿，可进入实施**（v1.2 遗留的 4 项待确认问题已全部关闭）
> 历史版本：`v1.0`（未连服务器时的假设稿）、`v1.1`（服务器实地勘察后重写）、`v1.2`（≤10 人 + 邀请码注册）
> **实施交接**：`handover-private-agent-v1.0.md`（交接书）、`handover-prompts-v1.0.md`（可直接粘贴的提示词）

---

## 变更摘要（v1.2 → v1.3）

| 项 | v1.2 | v1.3 |
|---|---|---|
| 待确认问题 | 4 项未决（§11） | **全部关闭**（§11） |
| 部署方式 | 手工改文件 + `deploy_step*.py` + tgz 备份 | **GitHub 私有仓库 + 服务器 `git pull`**（§10） |
| 服务端版本控制 | 服务器上**无 git** | GitHub 私有仓库；服务器持**只读部署密钥** |
| 数据库位置 | 建议独立库文件 | **已确认** `/var/lib/deeer/stumate.db` |
| 注销账号 | 待定 | **本期不做**；需要时管理员用 CLI 手工删除 |
| 昵称 / 头像 | 待定 | 只要**昵称**，**不做头像** |
| 登录 UI 多语言 | 待定 | **只做中文** |
| 历史分叉 | 未发现 | **已发现并定策**：服务器代码含从未提交的 TS3 模块，**以线上现状为基线**（§10） |

v1.0 / v1.1 / v1.2 保留供对比，但**以本文档为准**。

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
Web 服务器  nginx 80/443 → 127.0.0.1:3000
TLS 证书    Let's Encrypt（acme.sh 管理，2026-12-16 到期，自动续期）
```

**`172.24.55.32` 是内网地址，客户端从公网访问不到。两端必须使用 `https://deeer.online`。**

### 0.2 现有应用：`deeer-website`

`/var/www/deeer`，Next.js 16.3.0 + React 19.2.8 + TypeScript + Tailwind 4 + better-sqlite3。

个人站，含博客、评论、留言板、聊天室、点赞、访问统计、TeamSpeak 3 控制面板、管理后台。

**部署方式**：无 git 仓库。手工改文件 → `npm run build` → `pm2 restart deeer`。`/root` 下有大量 `deeer-site-backup-*.tgz` 手工备份。

**配置方式**：**没有 `.env` 文件**。所有配置（`ADMIN_OTP_SECRET`、`DATABASE_PATH`）写在 pm2 的 env 里（`/root/.pm2/dump.pm2`）。**新增配置项必须同步更新 pm2 配置，否则重启即丢。**

**数据库真实路径是 `/var/lib/deeer/site.db`**（由 `DATABASE_PATH` 指定），**不是**项目目录下的 `data/site.db` —— 后者是废弃副本，最后写入停在 9 月 15 日。

### 0.3 现有认证机制：**不是**多用户账号体系

`lib/auth.ts` + `lib/otp.ts` 实现的是**单管理员免密码登录**：服务器上跑 `npm run otp` 拿 5 位动态码 → 校验通过后签发随机 token 存 `sessions` 表 → httpOnly cookie。

**没有 `users` 表，`sessions` 表也没有 `user_id`。** 是后台门禁，不是账号系统。

**但它的 CLI 交付模式值得复用** —— 本项目 v1.2 的邀请码与密码重置正是沿用这个思路（§3.4）。

### 0.4 命名空间冲突（必须避开）

现有 `/api/auth/{login,logout,me}` 已被管理员登录占用。StuMate 接口必须另起命名空间：

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

### 0.8 规模结论：≤10 人意味着什么

这个规模让**大量工程复杂度直接消失**，明确记下来，避免过度设计：

| 项 | 结论 |
|---|---|
| 数据库 | SQLite 绰绰有余。10 人的课表+便签总量预计 **< 5 MB**。不需要 PostgreSQL |
| 缓存 | **不需要 Redis**。进程内内存足够 |
| 限流 | 进程内 Map 足够（pm2 fork 单实例，不存在多实例计数不一致） |
| scrypt 并发 | Node 的 `crypto.scrypt` 跑在 libuv 线程池（默认 4 线程），`N=2^16` 时峰值 `4 × 64 MB = 256 MB`。1.8 GB 内存下**安全**，参数不用降档 |
| 部署 | 单实例，`pm2 restart` 即可，不需要滚动更新/蓝绿 |
| sync_log 增长 | 10 人量级下几个月也就几万行。加一个 90 天清理任务即可，不需要分区 |
| 分库分表 | 完全不需要 |

**反过来说**：正因为规模小，**安全和正确性的投入更值得**（这几个人是你自己和你信任的人，数据丢了没法交代），而**性能优化基本不用做**。

---

## 1. 目标与非目标

### 目标

1. 邮箱 + 密码主账号，**邀请码注册**，支持找回密码（管理员协助）
2. 支持 **GitHub / Google** 第三方绑定，绑定后可用于快捷登录
3. 登录后**课程表与便签**在桌面端与移动端之间云同步
4. **不破坏现有体验**：不登录照常单机使用
5. 两端配置方式一致

### 非目标（本期不做）

- 开放注册、邮箱验证、邮件发送（**本期完全不需要邮件通道**）
- 多人协作 / 共享课表
- 设置类数据（主题、周数校准、视图偏好）的云同步
- 端到端加密（服务端可见明文）
- 微信 / QQ 绑定（需企业主体 + 开放平台认证）
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
        │  lib/stumate/*         ← 新增：独立模块      │
        │  scripts/stumate-*.mjs ← 新增：运维 CLI      │
        └───────────────────┬────────────────────────┘
                            ▼
        ┌────────────────────────────────────────────┐
        │  SQLite  /var/lib/deeer/stumate.db         │
        │  （与站点库 site.db 分开，见 §6.1）          │
        └────────────────────────────────────────────┘
```

**OAuth 由服务端中介，客户端不直接对接 IdP**，两端共用同一条路径：

```
客户端  POST /api/stumate/v1/oauth/{provider}/start   →  { state, authorize_url }
客户端  打开系统浏览器
用户在浏览器完成授权 → IdP 回调服务端
服务端  记下 state 对应的用户，签发一次性 ticket
客户端  GET  /api/stumate/v1/oauth/{provider}/poll?state=…  →  { status, tokens? }
```

---

## 3. 账号体系

### 3.1 主账号：邮箱 + 密码

| 项 | 方案 |
|---|---|
| 邮箱唯一性 | 唯一索引；存储前 `trim()` + 转小写 |
| 邮箱验证 | **本期不做**（邀请码已经起到了"准入控制"的作用，见 §3.2） |
| 密码哈希 | **Node 内置 `crypto.scrypt`**，`N=2^16, r=8, p=1, keylen=64`，16 字节随机盐 |
| 密码强度 | 最少 8 位；不做复杂度强制 |

**为什么用 scrypt 而不是 Argon2id**：`better-sqlite3` 已经是原生模块，再加 `argon2` 会引入第二个原生依赖（需要 node-gyp + 编译工具链），而**部署是手工拷贝文件、没有 git 仓库**，多一个原生依赖就多一处会在下次部署时炸掉的地方。`node:crypto` 的 scrypt 内置、零依赖、OWASP 认可。

**一个会直接抛错的坑**：`crypto.scrypt` 的 `maxmem` 默认 **32 MiB**，而 `N=2^16, r=8` 需要 `128 × N × r = 64 MiB`，**不显式传 `maxmem` 会直接报错**。

```ts
import { randomBytes, scrypt as _scrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";
const scrypt = promisify(_scrypt) as
  (p: string|Buffer, s: string|Buffer, k: number, o: object) => Promise<Buffer>;

const PARAMS = { N: 2 ** 16, r: 8, p: 1, maxmem: 128 * 1024 * 1024 };  // maxmem 必须显式给

export async function hashPassword(pw: string): Promise<string> {
  const salt = randomBytes(16);
  const dk = await scrypt(pw.normalize("NFKC"), salt, 64, PARAMS);
  return `scrypt$${PARAMS.N}$${PARAMS.r}$${PARAMS.p}$${salt.toString("base64")}$${dk.toString("base64")}`;
}

export async function verifyPassword(pw: string, stored: string): Promise<boolean> {
  const [alg, N, r, p, saltB64, dkB64] = stored.split("$");
  if (alg !== "scrypt") return false;
  const salt = Buffer.from(saltB64, "base64");
  const expect = Buffer.from(dkB64, "base64");
  const dk = await scrypt(pw.normalize("NFKC"), salt, expect.length,
    { N: +N, r: +r, p: +p, maxmem: 128 * 1024 * 1024 });
  return dk.length === expect.length && timingSafeEqual(dk, expect);
}
```

参数编进哈希串，将来调参不影响老密码校验。校验用 `timingSafeEqual` 防时序侧信道。

**容量核算（≤10 人）**：Node 的 scrypt 跑在 libuv 线程池（默认 4 线程），最坏情况 4 个并发哈希 × 64 MB = **256 MB**，1.8 GB 内存下安全。**参数不需要降档。**

### 3.2 邀请码注册（**v1.2 新增**）

不开放注册，凭邀请码建号。

```sql
invite_codes (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  code        TEXT NOT NULL UNIQUE,
  note        TEXT NOT NULL DEFAULT '',      -- 备注：这个码发给谁
  max_uses    INTEGER NOT NULL DEFAULT 1,
  used_count  INTEGER NOT NULL DEFAULT 0,
  expires_at  TEXT,                          -- NULL = 不过期
  revoked_at  TEXT,
  created_at  TEXT NOT NULL
);

invite_uses (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  code_id   INTEGER NOT NULL,
  user_id   INTEGER NOT NULL,
  used_at   TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_invite_uses_code ON invite_uses(code_id, user_id);
```

**码格式**：12 位，字符集沿用现有 `lib/otp.ts` 的无歧义字母表
`ABCDEFGHJKMNPQRSTVWXYZ23456789`（32 字符，去掉了 `0/O`、`1/I`），
按 `XXXX-XXXX-XXXX` 分组方便口头传达。熵 = `32^12 ≈ 2^60`，暴力枚举不可行。

**存储用明文，不存哈希 —— 与令牌的处理刻意不同**，理由：

| | 邀请码 | refresh_token |
|---|---|---|
| 管理员是否需要再次看到 | **需要**（10 人小规模，会反复转发/补发） | 不需要 |
| 生命周期 | 短（用完即废） | 长（30 天） |
| 泄露后果 | 多一个人注册（还要过邮箱唯一性检查） | 账号被完全接管 |
| 结论 | **明文** | **只存 SHA-256** |

存哈希会让管理员每次都得重新生成，对 10 人测试场景是纯粹的负担，而收益极小。

**注册流程**：

```
POST /api/stumate/v1/auth/register
  { email, password, invite_code, device }

  1. 校验 email 格式、密码长度
  2. 校验 email 未被注册
  3. 原子占用邀请码（见下）
  4. 建用户
  5. 写 invite_uses
  6. 签发令牌
  → { user, tokens }
```

**第 3 步必须原子**，否则两个人在同一秒用同一个「还剩 1 次」的码会都注册成功。用条件 UPDATE + 检查 `changes`，不要先 SELECT 再 UPDATE：

```ts
const claim = db.prepare(`
  UPDATE invite_codes
     SET used_count = used_count + 1
   WHERE code = ?
     AND revoked_at IS NULL
     AND (expires_at IS NULL OR expires_at > ?)
     AND used_count < max_uses
`).run(code, nowIso());

if (claim.changes !== 1) {
  // 统一返回"邀请码无效或已用完"，不区分具体原因（避免探测哪些码存在）
  return err(400, "INVALID_INVITE", "邀请码无效或已用完");
}
```

整个注册过程包在 `db.transaction()` 里（better-sqlite3 的事务是同步的，正好）。

**已知的、可接受的限制**：邮箱未验证，所以有人可以用别人的邮箱注册。在 ≤10 人的邀请制下这是可接受的——能拿到邀请码的人就是你邀请的。若将来开放注册，必须补邮箱验证。

### 3.3 找回密码：管理员协助（**v1.2 新增，零邮件依赖**）

没有邮件通道，所以密码重置走**管理员 CLI 生成一次性码 → 线下告知用户**。这正是现有 `npm run otp` 已经在用的模式。

```
# 管理员在服务器上执行
npm run stumate:reset -- --email alice@example.com
→ 输出：一次性重置码  K7M2-9PQR-4XTV   （30 分钟内有效，用后即焚）
→ 管理员把这个码通过微信/QQ 线下告诉 alice

# alice 在客户端「忘记密码」页输入
POST /api/stumate/v1/auth/password/reset-with-code
  { email, code, new_password }
```

```sql
one_time_codes (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  purpose    TEXT NOT NULL,          -- 本期只有 'reset'
  code_hash  TEXT NOT NULL,          -- 存 SHA-256
  expires_at TEXT NOT NULL,
  used_at    TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_otc_user ON one_time_codes(user_id, purpose);
```

**这里存哈希，与邀请码相反** —— 因为重置码由 CLI **在生成时打印一次**，之后管理员不需要再查，没有明文存储的必要。不同生命周期用不同策略，不是自相矛盾。

**安全约束**：
- 30 分钟有效期，用后即焚（`used_at` 非空即失效）
- 同一用户同时只保留最近一个未使用的重置码（生成新的时作废旧的重置码）
- 重置成功后**吊销该用户所有 refresh token**（强制所有设备重新登录）—— 这是防"账号被盗后改密码，但攻击者的令牌还在用"
- 接口恒定返回，不泄露邮箱是否注册

### 3.4 运维 CLI（**v1.2 新增**）

沿用现有 `scripts/get-otp.mjs` 的模式，新增两个脚本：

```
scripts/stumate-invite.mjs
  --count N          生成 N 个邀请码（默认 1）
  --note "给谁"       备注
  --max-uses N       每个码可用几次（默认 1）
  --expires 7d       有效期（默认 30d；0 = 不过期）
  --list             列出全部邀请码及使用情况
  --revoke <code>    撤销某个码

scripts/stumate-reset.mjs
  --email <邮箱>      生成一次性重置码
  --list-users       列出全部用户（邮箱、注册时间、最后同步时间、设备数）
```

`package.json` 追加：

```json
"stumate:invite": "node scripts/stumate-invite.mjs",
"stumate:reset":  "node scripts/stumate-reset.mjs",
"stumate:users":  "node scripts/stumate-reset.mjs --list-users"
```

**为什么用 CLI 而不是管理后台页面**：现有站点已有 `npm run otp` 的先例，管理员本来就习惯登录服务器操作；10 人规模下建后台页面的收益不足以抵消成本。将来用户变多，再把同样的逻辑包一层 `/api/stumate/v1/admin/**` 即可。

### 3.5 第三方绑定（GitHub + Google）

**已确认本期只做这两个。** 都是标准 OAuth2，无资质门槛。

```
oauth_binding (
  id, user_id, provider, provider_uid, provider_email,
  access_token, refresh_token, expires_at, created_at
  UNIQUE(provider, provider_uid)
);
```

`provider` 取值 `github` | `google`。做成可插拔的 provider 注册表（`lib/stumate/oauth.ts` 里一张配置表），后续加平台只加配置。

**绑定规则**（安全关键，写死）：

1. 已登录状态下发起绑定 → 直接绑到当前账号
2. 未登录状态下用第三方登录：
   - `provider_uid` 已有绑定 → 直接登录
   - 无绑定但 IdP 返回的邮箱与已存在账号一致 → **不自动合并**，要求先用邮箱密码登录后再绑定
     - 理由：自动合并意味着任何人只要能控制一个同邮箱的 IdP 账号，就能接管目标账号
     - **注意**：因为我们的邮箱是**未验证**的，这条规则比通常更重要，**绝不允许任何形式的自动合并**
   - 其余情况 → 走"补充注册"。**仍需邀请码**（第三方登录不能绕过准入控制）
3. 解绑前校验：**必须已设置密码或已绑其他平台**，否则用户会把自己锁在门外

### 3.6 登录限流

复用 `lib/rate-limit.ts` 的**模式**（进程内内存 Map），但**另建计数表** —— 现有 `isRateLimited` 是「每 IP 每 30 秒 3 次」的通用写限流，对登录太宽松。

| 维度 | 阈值 |
|---|---|
| 同 IP | 每 15 分钟 20 次 |
| 同邮箱 | 每 15 分钟 10 次，超过后指数退避 |
| 注册（含邀请码校验） | 同 IP 每 24 小时 10 次 |

**限流不只是防撞库，也是防 DoS** —— scrypt 每次占 64 MB 内存，不限流的话攻击者可以用登录接口把内存打满。

pm2 是 fork 单实例，进程内计数有效。**若将来改 cluster 模式，这里必须换成共享存储**（Redis 或 SQLite 表）。这一点写进代码注释。

---

## 4. 认证与会话

### 4.1 令牌方案：双不透明令牌（**相对 v1.0 的修改**）

| 令牌 | 形式 | 有效期 | 客户端存储 |
|---|---|---|---|
| access_token | 32 字节随机串 | **15 分钟** | 内存 |
| refresh_token | 32 字节随机串 | **30 天**，滑动续期 | 加密落盘 |

**v1.0 假设了 JWT access，这里改掉，理由**：

1. 本项目是**单实例、单库**，不存在"多服务需要无状态校验 JWT"的场景 —— JWT 的主要收益用不上
2. 每次请求多一次 SQLite 读，是**微秒级**本地操作，代价可忽略
3. 不透明令牌**可以立刻吊销**（改密码、踢设备、发现异常），JWT 签出去就收不回
4. 少一个 `jose` 依赖，少一处部署时会炸的地方

若将来需要横向扩展，再把 access 换成 JWT 即可，接口形状不变。

**刷新采用轮转（rotation）**：每次刷新签发新 refresh 并作废旧的；若检测到**已作废的 refresh 被再次使用**，判定令牌泄露，**吊销该设备整条令牌链**并要求重新登录。

### 4.2 令牌表结构

```sql
tokens (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  device_id  INTEGER NOT NULL,
  kind       TEXT NOT NULL,          -- 'access' | 'refresh'
  token_hash TEXT NOT NULL UNIQUE,   -- 只存 SHA-256
  parent_id  INTEGER,                -- 轮转链，用于检测重放
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked_at TEXT
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

每次登录登记一条设备记录（设备名、平台、最后活跃时间）。设置页可查看已登录设备并「退出该设备」；改密码时吊销除当前设备外的所有令牌；上限 **5 台**（10 人测试规模下够用，且能及早发现异常）。

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

delete 改成 `UPDATE ... SET deletedAt = ?, updatedAt = ?`，所有查询加 `WHERE deletedAt = 0`。

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
  updated_at TEXT NOT NULL,
  deleted_at TEXT                                -- 后补列，见下
);
CREATE INDEX idx_sync_log_user_rev ON sync_log(user_id, revision);
```

> **实施补记（2026-10-01）**：`deleted_at` 是实现时补上的，建表时漏了。
> 有它之后 pull 的消费者不必解析 `payload` 就能判断这条是不是删除。
> 因为 `CREATE TABLE IF NOT EXISTS` **不会给已有表补列**，所以同步走了一条
> 极轻量的迁移（`lib/stumate/db.ts` 的 `migrate()`：先 `PRAGMA table_info` 查列，
> 没有再 `ALTER TABLE ADD COLUMN`）。SQLite 没有 `ADD COLUMN IF NOT EXISTS`，只能这么做。

客户端持有 `lastCursor`（上次拿到的最大 revision）。

- **拉取** `GET /sync/pull?cursor=N` → 返回 `revision > N` 的全部变更 + 新游标
- **推送** `POST /sync/push` → 提交本地变更，服务端逐条合并，返回合并结果 + 新游标

**游标不用时间戳**：设备时钟不可信（用户改系统时间、时区漂移），用时间戳做游标会漏数据。单调序号没有这个问题。

**清理**：`sync_log` 保留 90 天。因为 10 人规模下增长极慢，用一个每天跑一次的清理任务即可（可以挂在 CLI 里，或用一个 `/api/stumate/v1/cron/cleanup` 由系统 crontab 调用）。

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

`BackupFormat.SCHEMA` 从 1 升到 2，**新增字段全部可选**，导入 v1 旧备份时自动补 `uid`（现场生成）、`updatedAt = 0`、`deletedAt = 0` → **旧备份文件继续可读**。备份、恢复、同步共用一套编解码。

### 5.7 API 契约

全部挂在 `/api/stumate/v1/` 下（见 §0.4）。

```
── 账号 ────────────────────────────────────────────────
POST   /api/stumate/v1/auth/register              { email, password, invite_code, device } → { user, tokens }
POST   /api/stumate/v1/auth/login                 { email, password, device }              → { user, tokens }
POST   /api/stumate/v1/auth/refresh               { refresh_token }                        → { tokens }
POST   /api/stumate/v1/auth/logout                { refresh_token }                        → 204
POST   /api/stumate/v1/auth/password/reset-with-code { email, code, new_password }         → 204
POST   /api/stumate/v1/auth/password/change       { old_password, new_password }           → { tokens }
GET    /api/stumate/v1/auth/invite/check?code=…                                            → { valid }

── 第三方绑定（GitHub / Google）──────────────────────────
POST   /api/stumate/v1/oauth/{provider}/start                                              → { state, authorize_url }
GET    /api/stumate/v1/oauth/{provider}/poll?state=…                                       → { status, tokens? }
POST   /api/stumate/v1/oauth/{provider}/bind      (需 Bearer)                               → { binding }
DELETE /api/stumate/v1/oauth/{provider}/bind      (需 Bearer)                               → 204

── 用户与设备 ──────────────────────────────────────────
GET    /api/stumate/v1/me                                                                  → { user, bindings }
PATCH  /api/stumate/v1/me                         { nickname? }                             → { user }
GET    /api/stumate/v1/me/devices                                                          → [ device ]
DELETE /api/stumate/v1/me/devices/{id}                                                     → 204

── 同步 ────────────────────────────────────────────────
GET    /api/stumate/v1/sync/pull?cursor=N&limit=500                                        → { cursor, changes[], hasMore }
POST   /api/stumate/v1/sync/push                  { cursor, changes[] }                     → { cursor, applied[], conflicts[], replace_local? }
GET    /api/stumate/v1/sync/status                                                         → { cursor, counts }
```

统一约定：
- 认证 `Authorization: Bearer <access_token>`
- 错误体 `{ "error": { "code": "INVALID_INVITE", "message": "…" } }`
- `POST /sync/push` 支持 `Idempotency-Key` 头，网络重试不写重

### 5.8 首次同步与 uid 回填：**以第一端为准**（已确认）

两端各自为历史数据生成 `uid`，会导致同一条历史课程在两端得到**不同 uid**，首次同步变成两条重复记录。

**确定的策略**：**首个完成同步的设备作为权威数据源**。

```
用户首次 push 时：
  if users.initial_device_id IS NULL:
      users.initial_device_id = 当前 device_id

后续其他设备首次 pull 时：
  若 device_id != users.initial_device_id:
      响应里带 "replace_local": true
      客户端收到后：清空本地 classes / notes（先自动备份到本地文件）
                   然后全量拉取服务端数据
```

**客户端必须实现"清空前自动本地备份"** —— 这一步不能省。用户可能在移动端有一批只在手机上存在的课，直接清空会丢数据。清空前写一个 `StuMate-preinit-backup-<日期>.json` 到数据目录，并在 UI 上明确告知路径。

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

CREATE TABLE IF NOT EXISTS invite_codes ( … 见 §3.2 … );
CREATE TABLE IF NOT EXISTS invite_uses  ( … 见 §3.2 … );
CREATE TABLE IF NOT EXISTS one_time_codes ( … 见 §3.3 … );
```

**时间统一用 TEXT + ISO8601 UTC**，与站点现有表的 `datetime('now','localtime')` 风格一致。注意 SQLite 没有原生时间类型，跨端比较时**必须统一时区** —— 全部存 UTC，只在展示层转本地。

### 6.3 建表与迁移机制

现有 `lib/db.ts` 的模式是**每次连接执行一遍 `CREATE TABLE IF NOT EXISTS`**。新表天然适配，直接照搬。

但**将来给已有表加列时这个模式会失效**（`CREATE TABLE IF NOT EXISTS` 不会补列）。建议同时引入一个极轻量的迁移机制：

```ts
// lib/stumate/db.ts
function migrate(db: Database.Database) {
  const cur = Number(getMeta(db, "schema_version", "0"));
  const steps: Array<[number, (d: Database.Database) => void]> = [
    [1, (d) => { /* v1 建表 */ }],
    // [2, (d) => { d.exec("ALTER TABLE records ADD COLUMN note TEXT"); }],
  ];
  for (const [v, fn] of steps) {
    if (cur < v) { fn(db); setMeta(db, "schema_version", String(v)); }
  }
}
```

用一个 `stumate_meta` key-value 表记录版本即可，不需要引入迁移框架。

### 6.4 两端本地库迁移

| 平台 | 版本 | 迁移动作 |
|---|---|---|
| Desktop | `Db.SCHEMA_VERSION` 7 → **8** | 新库直接建到 v8；老库 `ALTER TABLE classes/notes ADD COLUMN uid/updatedAt/deletedAt`。注意桌面端 `migrate()` 目前**只处理"全新库"**，必须补一条增量迁移分支 |
| Mobile | Room v7 → **v8** | 新增 `Migration(7, 8)`，同样三列 |

`uid` 回填按 §5.8 处理，客户端侧只需保证**本地已有数据的 uid 稳定**（生成后写入数据库，不要每次启动重新生成）。

---

## 7. 客户端改造清单

### 7.1 桌面端

| 项 | 内容 |
|---|---|
| 依赖 | Ktor Client (CIO) 或 OkHttp + `kotlinx-serialization-json` |
| 新增包 | `net/`（ApiClient、TokenStore、SyncEngine、Dto） |
| 数据层 | `Db.kt` 加 v8 迁移；DAO 的 delete 改软删除，insert/update 写 `updatedAt` |
| 凭证 | `net/DpapiStore.kt`，JNA 调 `crypt32.CryptProtectData` |
| UI | 登录/注册覆盖层（复用 `OverlayScreen.kt` 的对话框基座）；设置页新增「账号」分组 |
| 触发 | 启动后、写操作后 30 秒防抖、手动同步 |

**注册页需要「邀请码」输入框**（12 位，自动格式化 `XXXX-XXXX-XXXX`）。

### 7.2 移动端

| 项 | 内容 |
|---|---|
| 权限 | **`AndroidManifest.xml` 加 `INTERNET` + `ACCESS_NETWORK_STATE`** |
| 依赖 | OkHttp + `kotlinx-serialization-json`；`androidx.security:security-crypto` |
| 新增包 | `net/`（与桌面端同构） |
| 数据层 | Room `Migration(7, 8)`；DAO delete 改软删除 |
| 凭证 | EncryptedSharedPreferences |
| UI | 登录/注册页；设置页新增「账号」分组 |

**好消息**：因为是 Let's Encrypt 证书，**不需要 `network_security_config.xml`，不需要信任锚**，Android 直接就能连。

### 7.3 可共享的代码

两端 `data/` 下的 `TimeAxis` / `TodaySchedule` / `WeekSchedule` / `TodayNotePicker` / `TimetablePdfParser` / `backup/` 都是纯 Kotlin、零平台依赖，目前是**手工复制**关系。

**本期建议**：只把 `sync/` 的 DTO 与序列化抽成纯 JVM 库（Android 可直接依赖纯 JVM 库），其余保持现状。**不要在本期做全量 KMP 重构** —— 移动端是 Android 原生 Compose、桌面端是 Compose Multiplatform，改构建结构的风险远大于收益。

---

## 8. 传输安全

实测确认 `deeer.online` 使用 **Let's Encrypt** 签发的证书：

```
issuer  = C=US, O=Let's Encrypt, CN=YE2
subject = CN=deeer.online
SAN     = deeer.online, www.deeer.online
有效期  = 2026-09-17 → 2026-12-16（acme.sh 自动续期）
```

**结论：两端零配置。** Android 与 JVM 都默认信任 Let's Encrypt 根证书，不需要 `networkSecurityConfig`、不需要导入 CA、不需要自定义 `SSLContext`。

**必须做的两件事**：

1. **客户端必须用 `https://deeer.online`，不能用 IP。** `172.24.55.32` 是 VPC 内网地址公网不可达；`47.96.173.58` 裸 IP 访问会因为 SNI/证书域名不匹配而握手失败。
2. **禁止「信任所有证书」的 `TrustManager`**。那等于把 HTTPS 降级成 HTTP，凭证完全裸奔，而且给人"已经加密了"的错觉。

---

## 9. UI 形态

按「可选登录，本地优先」，登录入口**不能**做成启动拦截。

| 场景 | 形态 |
|---|---|
| 首次启动 | **不弹登录**，直接进主界面。设置页「账号」分组显示「未登录 · 点此登录」 |
| 主动登录 | 从设置页进入，打开登录覆盖层（沿用现有对话框基座，不新开窗口） |
| 注册 | 登录覆盖层里切到「注册」，需填**邀请码** |
| 未登录 | 所有功能照常，账号区显示同步不可用 |
| 已登录 | 显示邮箱、上次同步时间、「立即同步」、同步状态 |
| 忘记密码 | 提示「请联系管理员获取重置码」，输入邮箱 + 重置码 + 新密码 |
| 同步冲突 | 不弹窗打断。账号区显示「上次同步覆盖了 N 条记录，点此查看」 |
| 令牌失效 | 静默尝试 refresh；失败则降级为未登录并**保留本地数据**，提示重新登录 |
| 首次同步（非首端设备） | **必须弹确认框**，明确告知「将以云端数据替换本地课表与便签，本地已自动备份到 &lt;路径&gt;」 |

**关键**：任何情况下都不能因为网络/登录问题导致本地数据不可用或丢失。登录态失效只是「同步暂停」，不是「数据没了」。

---

## 10. 分阶段交付计划

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **P0** | ✅ 服务端勘察 | 已完成 |
| **P1** | 服务端账号体系：建库 + 邀请码 CLI + 注册 / 登录 / refresh / 改密 | 无 |
| **P2** | 桌面端接入：网络层 + TokenStore + 登录注册 UI | P1 |
| **P3** | 两端数据层改造：uid / updatedAt / deletedAt + 迁移 | **无依赖，可立即开始** |
| **P4** | 服务端同步接口 + SyncEngine | P1 + P3 |
| **P5** | 移动端接入：权限 + 网络层 + 登录注册 UI | P2 |
| **P6** | GitHub / Google OAuth 绑定 | P1 |
| **P7** | 设备管理、冲突提示、同步状态 UI | P4 |

**建议从 P3 起步** —— 它不依赖服务端任何信息，是同步的地基，且能独立验证（145 个单测必须保持全绿）。

### 服务端部署流程（v1.3 修订：GitHub 私有仓库 + `git pull`）

**已确认拓扑**（三处代码，单向流动）：

```
本地 C:\Users\Administrator\IdeaProjects\DeeerWebsite
        │  git push（开发者手动）
        ▼
   GitHub 私有仓库  deeer-website
        │  git pull（服务器持只读 Deploy key）
        ▼
   服务器 /var/www/deeer  →  npm run build  →  pm2 restart deeer
```

服务器**只读**，不给 push 权限 —— 服务器上的改动必须先落到本地再走正常流程。

> ⚠️ **一次性初始化属高危操作，完整步骤与回滚见 `handover-private-agent-v1.0.md` §5。**
> 核心风险：`/var/www/deeer` 的代码**比本地仓库新**（含从未提交的 TS3 模块
> `app/ts3`、`app/api/ts3/{action,status}`、`lib/ts3.ts`）。**必须先以线上现状建立基线提交**，
> 否则任何 push 都会把 TS3 删掉。

初始化要点（命令全文见交接书）：

```bash
# 服务器：以线上现状建基线（.gitignore 已存在，node_modules 511M / data/ 已排除）
cd /var/www/deeer
git config --global user.name "Deeer Deploy" && git config --global user.email "deploy@deeer.online"
git init -b master && git add -A
git commit -m "chore: 线上现状基线（含 TS3 模块，此前无版本控制）"
git remote add origin git@github.com:<GITHUB_USER>/deeer-website.git && git push -u origin master

# 本地：旧历史归档，master 改为线上基线
git remote add origin git@github.com:<GITHUB_USER>/deeer-website.git && git fetch origin
git branch -m master legacy/pre-server-baseline
git switch -c master origin/master
```

**GitHub 走 `ssh.github.com:443`**，不用 `github.com:22` —— 两者实测都通，但 443 更不容易被云厂商出站策略干扰。配置写进服务器 `/root/.ssh/config`。

**基线验证（必做）**：`git diff --stat legacy/pre-server-baseline master` 的差异应当**只有**
TS3 相关文件 + `check_server.py` / `deploy_step*.py`。若出现其他差异，说明本地有线上没有的改动，**必须先人工确认再继续**。

#### 日常发布流程

```bash
# ---- 本地 ----
git add -A && git commit -m "feat: ..." && git push

# ---- 服务器 ----
cd /var/www/deeer

# 1. 备份（现有习惯，必须包含 stumate.db）
tar czf /root/deeer-site-backup-$(date +%Y%m%d-%H%M%S).tgz \
    --exclude=node_modules --exclude=.next .
[ -f /var/lib/deeer/stumate.db ] && \
    cp /var/lib/deeer/stumate.db /root/stumate-db-$(date +%Y%m%d).db

# 2. 拉取 + 构建 + 重启
git pull
npm ci            # 仅当 package-lock.json 有变动
npm run build
pm2 restart deeer

# 3. 验证
curl -s https://deeer.online/api/stumate/v1/health
```

**新增环境变量必须写进 pm2 配置**（`pm2 set` 或重新 `pm2 start` 带 `--update-env`），因为项目里**没有 `.env` 文件**，配置全部存在 pm2 的 env 里。重启后不生效的话检查 `/root/.pm2/dump.pm2`。

本方案涉及的环境变量（**以服务端实际实现为准**，2026-09-30 对齐）：

| 变量 | 用途 | 状态 |
|---|---|---|
| `STUMATE_GITHUB_CLIENT_ID` / `STUMATE_GITHUB_CLIENT_SECRET` | GitHub OAuth（§3.5） | ✅ 已写入 |
| `STUMATE_GOOGLE_CLIENT_ID` / `STUMATE_GOOGLE_CLIENT_SECRET` | Google OAuth（§3.5） | ⏳ 待补 |
| `STUMATE_DB_PATH` | 可选，默认 `/var/lib/deeer/stumate.db` | 未设置（用默认） |
| `STUMATE_PUBLIC_BASE` | 可选，默认 `https://deeer.online` | 未设置（用默认） |

> **更正**：本文档早先写的 `STUMATE_SECRET`（一次性码 / state 的 HMAC 签名密钥）**已作废**。
> 服务端实际采用「`state` 存库 + 回调校验 + 取走一次即删」，而不是 HMAC 签名 ——
> 存库方案能**立刻吊销**，比签名更安全，所以不需要这个密钥。
>
> 服务器上现存的 `ADMIN_OTP_SECRET`、`DATABASE_PATH` **不要动**。
> ⚠️ `pm2 restart deeer --update-env` 读取的是**当前 shell 的环境**，
> 改 env 时必须先把这两个现存变量一起 `export` 再重启，否则会被冲掉。

### 日常运维命令

```bash
npm run stumate:invite -- --count 3 --note "给同学"   # 生成邀请码
npm run stumate:invite -- --list                      # 查看邀请码使用情况
npm run stumate:reset  -- --email a@b.com             # 生成密码重置码
npm run stumate:users                                 # 列出用户与同步状态
```

---

## 11. 待确认的问题 —— ✅ 已全部关闭（2026-09-30）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 部署方式 | **GitHub 私有仓库 + 服务器 `git pull`**，服务器持只读 Deploy key（§10） |
| 2 | 数据库位置 | **确认** `/var/lib/deeer/stumate.db`（独立库文件，与站点库隔离爆炸半径） |
| 3 | 注销账号 | **本期不做**；需要时管理员用 CLI 手工删除 |
| 4 | OAuth App 创建 | **交由私有 Agent 执行**（GitHub + Google），步骤见 `handover-private-agent-v1.0.md` §6 |
| 5 | 昵称 / 头像 | 只要**昵称**，**不做头像** |
| 6 | 同步触发时机 | **接受**：启动 + 写操作后 30 秒防抖 + 手动 |
| 7 | 登录 UI 多语言 | **只做中文** |

### 实施期新发现（记录备查）

- **服务器代码存在未提交分叉**：`/var/www/deeer` 含 TS3 模块（`app/ts3`、`app/api/ts3/{action,status}`、
  `lib/ts3.ts`）与 `check_server.py` / `deploy_step{1a,1b,2}.py`，**从未进入任何 git 提交**。
  已定策：**以线上现状为基线**，本地旧历史归档到 `legacy/pre-server-baseline`（§10）。
- **服务器可直连 GitHub**：`github.com:22` 与 `ssh.github.com:443` 实测**均通**，走 443 更稳。
- **服务器 git 无全局身份**，首次提交前必须先 `git config --global user.name/user.email`。
- **`/var/www/deeer/data/` 里有 `otp-secret`**（管理员 OTP 密钥），已被 `.gitignore` 的 `/data/` 排除。
  初始化后务必用 `git status --ignored` 复核一次，确认没有密钥进仓库。
- **`node_modules` 占 511M**，已被 `.gitignore` 排除；服务器上跑 `npm ci` 重建。
- **pm2 进程名 `deeer`**，fork 单实例（不是 cluster），故 §3.6 的进程内限流有效。

---

## 附录 A：与现有代码/环境的兼容性检查表

| 现有能力 | 是否受影响 | 说明 |
|---|---|---|
| 站点现有 API | **不受影响** | StuMate 全部挂在 `/api/stumate/v1/**`，与 `/api/auth/**` 等无交集 |
| 站点 SQLite 库 | **不受影响** | 用独立库文件 `stumate.db`（§6.1） |
| 站点限流器 | 不受影响 | StuMate 另建计数表，避免与评论/留言互相挤占配额 |
| `npm run otp` | 不受影响 | 新增的 CLI 是并列的，不改现有脚本 |
| nginx 配置 | **不需要改** | 现有 `location /` 已全部转发到 3000 端口，新路由自动生效 |
| pm2 进程 | 需重启一次 | `pm2 restart deeer`；新增环境变量要同步更新 pm2 配置 |
| TLS 证书 | 不需要改 | Let's Encrypt 已覆盖 `deeer.online` |
| 站点备份习惯 | **需扩展** | 备份必须包含 `/var/lib/deeer/stumate.db` |
| 145 个单元测试 | 不受影响 | 改的是数据层与 UI 层，纯逻辑（TimeAxis / WeekSchedule 等）不动 |
| 两端备份功能 | 向后兼容 | `SCHEMA` 1→2，新字段可选，旧备份文件仍可读（§5.6） |
| 两端 `.db` 互开 | 升级后需同版本 | 加列后 v8 的库，v7 客户端打不开。两端必须同步升级 |
| 现有 `settings.json` | 不受影响 | 凭证单独存 `credentials.bin`，不进 `settings.json` |
| 课表 PDF 导入 | 不受影响 | 导入走正常 insert，自动获得 uid |
| 提醒引擎 | 不受影响 | 只读本地库 |
| 服务器无 git 仓库 | **改为 git 管理** | GitHub 私有仓库 + 只读部署密钥（§10） |
| 服务器上的 `deploy_step{1a,1b,2}.py` | **退役** | git 化后由 `git pull` 取代；确认无引用后可删 |
| 服务器上的 `check_server.py` | 保留 | 与 git 无关的运维脚本，可一并纳入版本控制 |

## 附录 B：安全红线（实现时逐条核对）

1. refresh_token 必须可吊销、必须轮转、重放即吊销整条链
2. 密码只存 scrypt 哈希，永不落明文、永不进日志
3. 令牌**只存 SHA-256 哈希**，数据库泄露也不可直接使用
4. 邀请码**原子占用**（条件 UPDATE + 检查 changes），杜绝并发超用
5. 客户端凭证落盘必须经 DPAPI（桌面）/ Keystore（移动），不得明文
6. 禁止「信任所有证书」的 TrustManager
7. 找回密码接口恒定返回，不泄露邮箱是否注册
8. 第三方绑定**绝不允许任何形式的按邮箱自动合并**
9. 解绑前校验，防止把用户锁在门外
10. 重置密码成功后**吊销该用户所有 refresh token**
11. 登录/注册接口必须限流（scrypt 的 64 MB 内存占用可被用于 DoS）
12. 日志脱敏：邮箱部分掩码，token 与邀请码永不打印（CLI 输出除外）
13. **首次同步替换本地数据前必须自动备份**，且 UI 明确告知路径
