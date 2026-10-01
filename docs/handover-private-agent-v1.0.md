# StuMate 服务端实施 · 私有 Agent 交接书 v1.0

> **交接日期**：2026-09-30
> **交接方**：StuMate-Desktop 设计会话（已完成服务器实地勘察 + 方案设计）
> **接手方**：私有 Agent（需能访问用户的浏览器凭据，用于创建 OAuth App）
> **权威设计文档**：`docs/account-and-sync-design-v1.3.md` —— **动手前必须完整读一遍**。
> 本文只讲「怎么落地」，不重复设计推导；两者冲突时**以 v1.3 为准**。
> **配套**：`docs/handover-prompts-v1.0.md`（可直接粘贴的提示词，共 8 条）

---

## 0. 一页纸摘要

### 0.1 要做什么

给 **StuMate**（一个「课表 + 便签」的桌面 / 安卓双端应用）加**账号体系 + 云同步**。
服务端**不新起机器**，直接挂在用户已有的个人站 `deeer.online` 上（Next.js 16.3.0 + SQLite）。

### 0.2 已定的事（不要再问用户）

| 项 | 结论 |
|---|---|
| 主账号 | **邮箱 + 密码** |
| 第三方 | **只做 GitHub + Google**（微信/QQ 需企业资质，本期排除） |
| 注册方式 | **邀请码**（≤10 人小规模测试，不做开放注册） |
| 登录策略 | **可选登录、本地优先** —— 不登录照样单机用 |
| 同步范围 | 只同步**课程 + 便签**；设置类（主题/周数校准/视图偏好）留本地 |
| 冲突解决 | **LWW**（版本号 + last-write-wins） |
| 历史数据 | **以第一端为准**（首个同步的设备为权威源） |
| 邮件 | **完全不需要**（不做邮箱验证；找回密码走管理员 CLI 一次性码） |
| 部署 | **GitHub 私有仓库 → 服务器 `git pull`**（服务器持只读部署密钥） |
| 数据库 | 独立库文件 `/var/lib/deeer/stumate.db` |
| 注销账号 | **本期不做**，需要时管理员 CLI 手工删 |
| 昵称/头像 | 只要昵称，**不做头像** |
| UI 语言 | **只做中文** |

### 0.3 最大风险（一句话）

> 服务器 `/var/www/deeer` 的代码**比本地 git 仓库新**，含从未提交的 **TS3 模块**。
> **第一次 push 之前必须先以线上现状建立基线提交**，否则会把 TS3 删掉。

### 0.4 你的产出

1. GitHub 私有仓库 + 服务器 git 化 + 可用的 `git pull` 部署流程 → **T1**
2. 两个 OAuth App（GitHub / Google）的 Client ID + Secret，写进服务器 pm2 env → **T2**
3. 服务端账号与同步 API（`/api/stumate/v1/**`）+ 两个运维 CLI → **T3**
4. 两端客户端接入改动（桌面端优先） → **T4**
5. 一份回执（格式见 §11）

---

## 1. 角色边界：什么归你，什么不归你

### 1.1 归你

| 范围 | 说明 |
|---|---|
| 服务端一切改动 | `deeer-website` 仓库内的**新增**代码 |
| OAuth App 创建 | GitHub + Google 两个应用，含凭据落地到 pm2 env |
| 客户端 P2 / P3 / P5 | 桌面端网络层 / 数据层改造 / 移动端接入 |
| 部署流程落地 | GitHub 仓库、服务器 git 化、只读部署密钥 |

### 1.2 不归你

| 范围 | 原因 |
|---|---|
| **站点现有功能** | 博客 / 评论 / 留言板 / 聊天室 / 点赞 / 访问统计 / TeamSpeak 控制 / 管理后台 —— **只准新增，不准改** |
| 用户的 GitHub / Google **账号密码** | 只能引导用户本人在浏览器里操作，**不要索取、不要代填** |
| 客户端 UI 视觉风格 | 已冻结为 **Windows 11 Fluent 皮肤 B**（4dp 圆角 / 1px 描边 / 扁平卡片 / 左侧强调条导航） |
| 构建安装包 | 本机**没有 WiX**，`packageMsi` 会失败。默认只跑编译与单元测试 |

### 1.3 绝对不要碰

- `/var/lib/deeer/site.db` —— 站点**真实**数据库。本方案用独立的 `stumate.db`，**不要写进 site.db**
- `/var/www/deeer/data/otp-secret` —— 管理员 OTP 密钥
- `/etc/nginx/**` —— **不需要改**（见 §4.5）
- pm2 里现存的 `ADMIN_OTP_SECRET` / `DATABASE_PATH` 两个变量
- `~/.workbuddy-ai/` 之类的工具目录

---

## 2. 资产与凭据清单

### 2.1 服务器

| 项 | 值 |
|---|---|
| 公网 IP | `47.96.173.58` |
| 内网 IP | `172.24.55.32`（同一台机 eth0，**公网访问不到，别用**） |
| SSH 端口 | `22`，用户 `root` |
| SSH 私钥 | `F:\DownloadQQ\workbuddy_ed25519.pem`（Windows 路径） |
| 私钥指纹 | `SHA256:7y5qk1pUsu9w9US/5S7jkh9z6TBs7sz9QC9vvTEnHl8`（ED25519） |
| 域名 | `deeer.online` / `www.deeer.online` / `ts3.deeer.online` |
| 站点真实数据库 | `/var/lib/deeer/site.db` |
| **StuMate 新库（待建）** | `/var/lib/deeer/stumate.db` |

**SSH 调用建议**（避免 known_hosts 噪音，否则每次输出都带警告）：

```bash
ssh -i "F:/DownloadQQ/workbuddy_ed25519.pem" \
    -o UserKnownHostsFile=/dev/null \
    -o StrictHostKeyChecking=no \
    -o LogLevel=ERROR \
    root@47.96.173.58 '<命令>'
```

> 不要用 `ssh-keygen -R` 之类去写 known_hosts —— 这个环境里 `~/.ssh` 可能是只读的。

### 2.2 客户端仓库

| 端 | 路径 | 技术栈 |
|---|---|---|
| 桌面端 | `C:\Users\Administrator\IdeaProjects\StuMate-Desktop` | Kotlin 1.9.20 + Compose Multiplatform **1.5.10** + Gradle 8.4 + JDK 17 |
| 移动端 | `C:\Users\Administrator\IdeaProjects\ClassReminderNewest` | Android 原生 Compose + Room 2.6.1 |
| **服务端** | `C:\Users\Administrator\IdeaProjects\DeeerWebsite` | Next.js 16.3.0 + React 19.2.8 + TS + Tailwind 4 + better-sqlite3 13.0.3 |

**JDK 路径**：`E:\DevTools\Java\jdk-17.0.12+7`

> ⚠️ **Compose Multiplatform 锁死在 1.5.10**，不要升到 1.5.11 / 1.5.12（有已知问题）。

### 2.3 关键文件路径速查

| 用途 | 路径 |
|---|---|
| 权威设计文档 | `StuMate-Desktop/docs/account-and-sync-design-v1.3.md` |
| 本文档 | `StuMate-Desktop/docs/handover-private-agent-v1.0.md` |
| 提示词集 | `StuMate-Desktop/docs/handover-prompts-v1.0.md` |
| 桌面端项目记忆 | `StuMate-Desktop/.workbuddy-ai/memory/MEMORY.md` |
| 服务端项目根 | `/var/www/deeer`（服务器） |
| 服务端 nginx 主配置 | `/etc/nginx/nginx.conf`（`deeer.online` 的 server 块**内联在主配置里**，不在 `conf.d/`） |
| 服务端 TLS 证书 | `/etc/nginx/ssl/deeer.online.fullchain.cer` + `.key` |
| pm2 进程定义 | `/root/.pm2/dump.pm2`（**环境变量的唯一真相**） |
| 既有 OTP 脚本 | `/var/www/deeer/scripts/get-otp.mjs`（新 CLI 参考它的写法） |

---

## 3. 已冻结的决策（不可改）

| # | 决策 | 备注 |
|---|---|---|
| 1 | 邮箱 + 密码为主账号 | scrypt 哈希，`N=2^16, r=8, p=1, keylen=64` |
| 2 | 第三方只做 GitHub + Google | 做成可插拔 provider 注册表 |
| 3 | **邀请码注册**，无开放注册 | 第三方注册**同样需要**邀请码 |
| 4 | 不做邮箱验证 | 因此**绝不允许任何形式的账号自动合并** |
| 5 | 找回密码走管理员 CLI | 零邮件依赖 |
| 6 | 双不透明令牌 | access 15min / refresh 30d，**轮转 + 重放检测** |
| 7 | 令牌只存 SHA-256 | 邀请码**存明文**（生命周期短、管理员需再查） |
| 8 | 增量同步用服务端 **revision 游标** | **不用时间戳**（设备时钟不可信） |
| 9 | 软删除（`deletedAt`） | 否则「删了又活」 |
| 10 | 新增 `uid` (UUIDv4) 做同步主键 | **本地主键 `id: Int` 保持不动**，现有查询逻辑一行不改 |
| 11 | 同步载荷复用现有 `stumate-backup` JSON 格式 | `SCHEMA` 1→2，新字段全部可选，旧备份仍可读 |
| 12 | 设备上限 **5 台** | |
| 13 | 客户端一律用 `https://deeer.online` | **不能用 IP**（内网不可达；裸 IP 证书域名不匹配） |

**如需变更任何一条**：先写进回执的「建议变更」段落，**等用户确认后再动手**。不要在实施过程中自行调整。

---

## 4. 服务端现状实况（2026-09-30 实地勘察）

> 以下全部是**实测**结果，不是推测。数字可直接引用。

### 4.1 机器规格

```
系统    Alibaba Cloud Linux 3
CPU     2 vCPU
内存    1.8 GB（当前用量 34.9%）
磁盘    40 GB，已用 18%（剩余 31 GB）
```

**已装工具**：`git 2.43.7` · `node v24.9.0` · `npm 11.6.0` · `sqlite3` · `python3` · `pm2`

### 4.2 应用与进程

```
pm2 进程名  deeer（id 0）
模式        fork，**单实例**（不是 cluster）
已运行      12 天 · 重启过 64 次 · 常驻内存 44.2 MB
监听        127.0.0.1:3000
```

> **单实例这一点很重要**：§3.6 的登录限流用进程内内存 Map 就有效。**如果将来改 cluster，限流必须换成共享存储**（Redis 或 SQLite 表）。请把这条写进代码注释。

### 4.3 目录与数据库

| 路径 | 说明 |
|---|---|
| `/var/www/deeer` | 项目根。**不是 git 仓库** |
| `/var/www/deeer/node_modules` | **511 MB**，已被 `.gitignore` 排除 |
| `/var/lib/deeer/site.db` | **站点真实数据库**（pm2 的 `DATABASE_PATH` 指定） |
| `/var/lib/deeer/site.db-wal` | 1.79 MB，**仍在写入** → 证明这才是活库 |
| `/var/lib/deeer/ts3-query.json` | TeamSpeak 查询凭据（600 权限） |
| `/var/www/deeer/data/site.db` | ⚠️ **废弃副本**（最后写入停在 8/19）。**不要被它误导** |
| `/var/www/deeer/data/otp-secret` | 管理员 OTP 密钥（22 字节） |

`.gitignore` **已存在**且内容正确，关键排除项：`/node_modules`、`/data/`、`/.next/`、`.env*`、`*.pem`。

### 4.4 现有认证机制（**不是**多用户体系）

- 单管理员**免密码 5 位动态验证码**（`lib/auth.ts` + `lib/otp.ts`，HMAC-SHA1，60 秒轮换）
- 验证后写 `sessions` 表 → 下发 httpOnly cookie
- **没有 `users` 表**，不是账号体系
- **`/api/auth/*` 已被管理员登录占用** → StuMate 必须用 `/api/stumate/v1/**`

### 4.5 nginx 与 TLS

`deeer.online` 的 server 块**内联在 `/etc/nginx/nginx.conf`**（不在 `conf.d/`，`conf.d/` 里只有 `ts3.deeer.online.conf`）。

```
443  ssl_certificate     /etc/nginx/ssl/deeer.online.fullchain.cer   ← Let's Encrypt
     ssl_certificate_key /etc/nginx/ssl/deeer.online.key
     TLSv1.2 / TLSv1.3

     location ^~ /.well-known/acme-challenge/  → 静态目录（acme.sh 续期用）
     location ^~ /ts3        { return 404; }   ← TS3 面板只走子域名
     location ^~ /api/ts3/   { return 404; }
     location /              → proxy_pass http://127.0.0.1:3000
                               （带 Upgrade / Connection 头，支持 SSE 与 WebSocket）

80   → 301 跳转 https://$host$request_uri
```

**结论：`location /` 已兜住所有路径，新增 `/api/stumate/**` 会自动生效，nginx 不需要改。**

**证书是 Let's Encrypt 公信证书** → 两端客户端**零配置**，不需要信任锚、不需要 `networkSecurityConfig`。

### 4.6 邮件通道（已确认本期不需要）

- postfix 已安装但 **inactive / disabled**
- **阿里云默认封 25 端口出站**（实测 `smtp.qq.com:25` 超时）
- → 所以 v1 设计**完全绕开邮件**：邀请码注册 + 管理员 CLI 重置密码

### 4.7 可复用的既有代码

| 文件 | 复用方式 |
|---|---|
| `lib/db.ts` | **建表模式参考**（better-sqlite3 单例 + 启动时建表） |
| `lib/rate-limit.ts` | **模式参考**（进程内内存 Map）。但**要另建计数表** —— 现有的是「每 IP 每 30 秒 3 次」的通用写限流，对登录太宽松 |
| `lib/validate.ts` | 输入校验工具 |
| `scripts/get-otp.mjs` | **新 CLI 的写法参考**（`--watch` / `--file` 参数风格） |
| `lib/otp.ts` | 字符集 `ABCDEFGHJKMNPQRSTVWXYZ23456789`（去掉了易混的 I/L/O/0/1），**邀请码沿用这个字符集** |

### 4.8 服务器上需要处理的遗留文件

服务器项目根有几个**从未提交**的脚本，git 化时会被一并纳入：

```
check_server.py        运维检查脚本 —— 保留，可纳入版本控制
deploy_step1a.py       \
deploy_step1b.py        > 旧的"手工上传部署"脚本，git 化后由 git pull 取代
deploy_step2.py        /  —— 确认无引用后可删，或移入 legacy/ 目录
```

---

## 5. 任务 T1 · git 化 + GitHub 私有仓库 + 部署流程

> ⚠️ **本任务是全流程中唯一有数据丢失风险的一步。做完 §5.5 验证之前，不要执行任何其他任务。**

### 5.1 目标拓扑

```
本地 C:\Users\Administrator\IdeaProjects\DeeerWebsite
        │  git push（开发者手动）
        ▼
   GitHub 私有仓库  deeer-website
        │  git pull（服务器持只读 Deploy key）
        ▼
   服务器 /var/www/deeer  →  npm run build  →  pm2 restart deeer
```

服务器**只读**，不给 push 权限 —— 服务器上的临时改动必须先落回本地，再走正常流程。

### 5.2 前置：确认三处代码的差异（**必做，不可跳过**）

| 位置 | 状态 |
|---|---|
| 服务器 `/var/www/deeer` | **最新**。含 TS3 模块（`app/ts3/`、`app/api/ts3/{action,status}/route.ts`、`lib/ts3.ts`）+ `check_server.py` + `deploy_step*.py` |
| 本地 `DeeerWebsite` 仓库 | 10 个 commit，HEAD = `997f5cf`（聊天室在线列表），**没有 TS3**，**没有配任何 remote** |
| GitHub | **仓库还不存在** |

**已验证的连通性**：服务器 → `github.com:22` ✅ 通；`ssh.github.com:443` ✅ 通。**走 443 更稳**（云厂商对 22 出站偶有限制）。

**服务器 git 没有全局身份** → 首次提交前必须先配置，否则 commit 会失败。

### 5.3 步骤 A：服务器侧（建立基线并推上去）

```bash
# ---- 0. 先做一次全量备份（改坏了好回滚）----
cd /var/www/deeer
tar czf /root/deeer-site-backup-$(date +%Y%m%d-%H%M%S).tgz \
    --exclude=node_modules --exclude=.next .
cp /var/lib/deeer/site.db /root/site-db-$(date +%Y%m%d).db
ls -lh /root/deeer-site-backup-*.tgz | tail -1     # 确认备份存在且大小合理

# ---- 1. git 身份 ----
git config --global user.name  "Deeer Deploy"
git config --global user.email "deploy@deeer.online"

# ---- 2. 初始化 + 提交线上现状 ----
git init -b master
git status --short | head -30          # 先看一眼要提交什么
git add -A
git commit -m "chore: 线上现状基线（含 TS3 模块，此前无版本控制）"

# ---- 3. 安全检查：确认没有密钥进仓库 ----
git status --ignored --short | grep -E "data/|\.env|\.pem|node_modules" | head
git ls-files | grep -E "otp-secret|\.env|\.pem|site\.db" || echo "✅ 无敏感文件被跟踪"
du -sh .git                            # 应该在几 MB 量级；若上百 MB 说明 node_modules 进来了

# ---- 4. 生成部署密钥（只读）----
ssh-keygen -t ed25519 -f /root/.ssh/github_deploy -N "" -C "deeer-deploy@47.96.173.58"
cat /root/.ssh/github_deploy.pub
# ↑ 复制这行公钥，稍后贴到 GitHub 仓库的 Deploy keys

# ---- 5. 让 git 走 443 ----
mkdir -p /root/.ssh && chmod 700 /root/.ssh
cat >> /root/.ssh/config <<'EOF'
Host github.com
  HostName ssh.github.com
  Port 443
  User git
  IdentityFile /root/.ssh/github_deploy
  IdentitiesOnly yes
EOF
chmod 600 /root/.ssh/config

# ---- 6. 关联并推送 ----
git remote add origin git@github.com:<GITHUB_USER>/deeer-website.git
git push -u origin master
```

**人工介入点**：先在 GitHub 上创建**私有**仓库（**不要**勾选 "Add a README"、不要加 .gitignore、不要加 license —— 保持空仓库），然后把上一步的公钥贴到 **Settings → Deploy keys → Add deploy key**，**不要勾选 "Allow write access"**。

### 5.4 步骤 B：本地侧（归档旧历史，切到线上基线）

```bash
cd "C:/Users/Administrator/IdeaProjects/DeeerWebsite"

# 先确认本地是干净的
git status --short          # 应该没有输出

git remote add origin git@github.com:<GITHUB_USER>/deeer-website.git
git fetch origin

# 旧历史归档（10 个 commit，内容是线上基线的子集，不丢东西）
git branch -m master legacy/pre-server-baseline

# 新 master = 线上基线，之后所有开发从这里继续
git switch -c master origin/master
git log --oneline -3
```

> 本地 `.env.local`（含 `ADMIN_OTP_SECRET`）被 `.gitignore` 的 `.env*` 排除，**不会**被推上去。
> 推之前用 `git check-ignore -v .env.local` 确认一次。

### 5.5 验证（**必须全部通过才继续**）

```bash
# ① 差异应当只有 TS3 相关 + deploy 脚本
cd "C:/Users/Administrator/IdeaProjects/DeeerWebsite"
git diff --stat legacy/pre-server-baseline master

# 预期看到的（大致）：
#   app/ts3/page.tsx
#   app/api/ts3/action/route.ts
#   app/api/ts3/status/route.ts
#   lib/ts3.ts
#   check_server.py
#   deploy_step1a.py / deploy_step1b.py / deploy_step2.py
#
# ❌ 如果出现上面之外的差异（尤其是 app/api/chat、lib/chat-store.ts、components/chat-room.tsx），
#    说明本地有线上没有的改动，**停下来人工确认**，不要盲目 push。

# ② 服务器上确认工作区与仓库一致
cd /var/www/deeer && git status --short     # 应该干净
git log --oneline -1                        # 应该看到基线提交

# ③ 端到端演练一次（不改代码，只验证流程）
#    本地：git commit --allow-empty -m "chore: 验证部署链路" && git push
#    服务器：cd /var/www/deeer && git pull && npm run build && pm2 restart deeer
#    然后 curl -s https://deeer.online | head -20  确认站点正常
```

### 5.6 回滚

**服务器代码改坏了**：

```bash
cd /var/www/deeer
git reset --hard HEAD~1        # 回退一个提交
npm run build && pm2 restart deeer
```

**整个 git 化搞砸了，想回到"没有 git"的状态**：

```bash
cd /var/www/deeer
rm -rf .git
# 代码本身没被动过（git init 不会改文件），站点继续正常运行
```

**从备份恢复**：

```bash
cd /var/www
tar xzf /root/deeer-site-backup-<时间戳>.tgz -C /var/www/deeer
cd /var/www/deeer && npm run build && pm2 restart deeer
```

### 5.7 已知坑

| 坑 | 说明 |
|---|---|
| `node_modules` 511 MB | `.gitignore` 已排除，但**提交前务必 `du -sh .git` 复核** |
| 服务器 git 无身份 | 不配 `user.name/email` 直接 commit 会失败 |
| 22 端口 | 用 `ssh.github.com:443`，别用 `github.com:22` |
| `.pem` 文件 | `.gitignore` 里有 `*.pem`，SSH 私钥不会被误提交 |
| Deploy key 权限 | **只读**。服务器永远不需要 push |
| 首次 push 前 | 一定要先 `git init` + 提交线上现状。**顺序反了就是删 TS3** |

---

## 6. 任务 T2 · OAuth App 创建（GitHub + Google）

> **这是用户明确要求交给私有 Agent 的一步。** 需要能操作用户的浏览器会话。
> **不要向用户索取账号密码** —— 引导用户本人在浏览器里完成登录，你负责导航和填表。

### 6.1 共通前提

| 项 | 值 |
|---|---|
| 站点域名 | `https://deeer.online` |
| 回调地址（GitHub） | `https://deeer.online/api/stumate/v1/oauth/github/callback` |
| 回调地址（Google） | `https://deeer.online/api/stumate/v1/oauth/google/callback` |
| 应用显示名 | `StuMate` |
| 凭据存放 | **服务器 pm2 env**（项目里没有 `.env` 文件） |

**架构说明（决定了为什么不需要 PKCE / 自定义 scheme）**：

```
客户端（桌面/安卓）
   │ ① 请求登录，拿到一个 poll 票据 + 授权 URL
   ├─→ ② 打开系统浏览器 → IdP 授权页
   │                            │ ③ IdP 回调到【服务端】
   │                            ▼
   │                    https://deeer.online/api/stumate/v1/oauth/{provider}/callback
   │                            │ ④ 服务端拿 code + client_secret 换 token（服务端对服务端）
   │                            │ ⑤ 服务端读 userinfo → 完成登录 → 把结果挂到 poll 票据上
   ├─→ ⑥ 客户端轮询 poll 票据 → 取走自己的 StuMate 令牌
   ▼
完成
```

**关键推论**：
- `client_secret` **只在服务端出现**，绝不下发到客户端
- 客户端不需要注册自定义 URL scheme，也不需要 PKCE
- **但建议顺手实现 PKCE**（`code_challenge` / `code_verifier`）—— 成本低，且为将来「移动端深链回调」留路
- 两端**共用同一个回调地址**（只有一台后端），这是结构性优势

**`state` 参数（必须做）**：一次性随机值，服务端存储或 HMAC 签名，绑定发起会话，回调时校验，**用后即焚**。

### 6.2 GitHub OAuth App

**入口**：`https://github.com/settings/developers` → **OAuth Apps** → **New OAuth App**

> 注意是 **OAuth Apps**，不是 GitHub Apps。我们要的是「用 GitHub 登录」，OAuth App 才对。

**表单填写**：

| 字段 | 值 |
|---|---|
| Application name | `StuMate` |
| Homepage URL | `https://deeer.online` |
| Application description | `StuMate 课表应用的账号登录与云同步`（可留空） |
| Authorization callback URL | `https://deeer.online/api/stumate/v1/oauth/github/callback` |

**创建后**：
- **Client ID** 直接可见（形如 `Iv1.xxxxxxxxxxxxxxxx`）
- 点 **Generate a new client secret** → Secret **只显示一次**，**立刻抄走**
- 如果丢了，重新生成即可（旧的立即失效）

**授权 scope**：`read:user user:email`

| scope | 为什么需要 |
|---|---|
| `read:user` | 读 `login` / `id` / `avatar_url` / `name` |
| `user:email` | **必须**。GitHub 用户的邮箱可能是私有的，此时 `/user` 的 `email` 字段返回 `null`，只有带这个 scope 才能读 `/user/emails` |

**取邮箱**：调 `/user/emails`，取 `primary === true && verified === true` 的那条。

**端点**：

| 用途 | URL |
|---|---|
| 授权 | `https://github.com/login/oauth/authorize` |
| 换票 | `https://github.com/login/oauth/access_token` |
| 用户信息 | `https://api.github.com/user` |
| 邮箱列表 | `https://api.github.com/user/emails` |

**坑**：
- 换票请求**必须带 `Accept: application/json`**，否则返回 `application/x-www-form-urlencoded`，解析会炸
- GitHub OAuth App **只有一个 callback URL**。本地调试要么再建一个 App，要么用 `state` 编码环境标识
- 换票时 `redirect_uri` 如果传了，必须与注册的**完全一致**

### 6.3 Google OAuth 客户端

**第一步：配置 OAuth 同意屏幕**（`https://console.cloud.google.com` → 选项目 → **APIs & Services** → **OAuth consent screen**）

| 字段 | 值 |
|---|---|
| User Type | **External** |
| App name | `StuMate` |
| User support email | 用户自己的邮箱 |
| Developer contact email | 用户自己的邮箱 |
| **Authorized domains** | `deeer.online` ← **必须加，否则保存报错** |
| Scopes | `openid`、`email`、`profile` |

> 这三个 scope **都是非敏感 scope** → **不需要 Google 审核**，不会卡在 verification。

**第二步：创建凭据**（**Credentials** → **Create Credentials** → **OAuth client ID**）

| 字段 | 值 |
|---|---|
| Application type | **Web application** |
| Name | `StuMate Server` |
| **Authorized redirect URIs** | `https://deeer.online/api/stumate/v1/oauth/google/callback` |
| Authorized JavaScript origins | 留空（我们不做前端 JS SDK 流程） |

**创建后**：弹窗给出 **Client ID** 和 **Client Secret**。Secret 之后可在凭据页面随时重新查看（不像 GitHub 只显示一次）。

**发布状态**：

- consent screen 默认是 **Testing**，上限 100 个测试用户 —— 我们只有 ≤10 人，**够用**
- **但**：Testing 模式下若应用持有 refresh token，**7 天后失效**
- 本设计**不保存 Google 的 token**（只取一次 userinfo 就丢弃），所以 7 天限制**不影响我们**
- 仍**建议直接点 "Publish app" 转为 Production** —— scope 非敏感，无需审核，能彻底避开测试模式的所有限制

**端点**：

| 用途 | URL |
|---|---|
| 授权 | `https://accounts.google.com/o/oauth2/v2/auth` |
| 换票 | `https://oauth2.googleapis.com/token` |
| 用户信息 | `https://openidconnect.googleapis.com/v1/userinfo` |

**坑**：
- Google 的 redirect URI **必须精确匹配**：scheme / host / path / **末尾斜杠**，一个字符都不能差
- **不要传 `access_type=offline`** —— 那是要 refresh token 的，会让用户看到「离线访问」这种吓人的授权项，**我们不需要**
- 授权时 `response_type=code`，`scope` 用空格分隔（`openid email profile`）

### 6.4 凭据落地到服务器

项目里**没有 `.env` 文件**，配置的唯一真相是 **pm2 env**。

```bash
# 在服务器上执行
export STUMATE_GITHUB_CLIENT_ID='Iv1.xxxxxxxxxxxxxxxx'
export STUMATE_GITHUB_CLIENT_SECRET='xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
export STUMATE_GOOGLE_CLIENT_ID='xxxxxxxxxxxx-xxxxxxxxxxxxxxxx.apps.googleusercontent.com'
export STUMATE_GOOGLE_CLIENT_SECRET='GOCSPX-xxxxxxxxxxxxxxxxxxxx'
export STUMATE_SECRET="$(openssl rand -hex 32)"     # state / 一次性码的 HMAC 密钥

# 让 pm2 重新读取当前 shell 的环境变量
pm2 restart deeer --update-env
pm2 save
```

> `--update-env` 的行为是「从**当前 shell 环境**重新读取」。所以必须**先 export 再 restart**，顺序反了不生效。

**验证**：

```bash
pm2 env 0 | grep -E "STUMATE_|ADMIN_OTP_SECRET|DATABASE_PATH"
# 应该看到 5 个 STUMATE_* + 原有的 2 个，原有值不要变

curl -s https://deeer.online | head -5      # 站点仍然正常
```

**持久化**：`pm2 save` 会把当前进程列表与 env 写进 `/root/.pm2/dump.pm2`。之后重启机器由 `pm2 resurrect` 恢复。
**注意**：如果之后有人用 `pm2 restart` 不带 `--update-env`，env 会从 dump 里恢复（值仍在），不会丢。

### 6.5 自检清单

- [ ] GitHub OAuth App 已创建，callback = `https://deeer.online/api/stumate/v1/oauth/github/callback`
- [ ] GitHub Client ID + Secret 已拿到（Secret 只显示一次，确认抄对了）
- [ ] Google 同意屏幕已配置，Authorized domains 含 `deeer.online`
- [ ] Google OAuth 客户端类型 = **Web application**，redirect URI 精确匹配
- [ ] Google 同意屏幕已 **Publish**（或确认接受 Testing 模式限制）
- [ ] 5 个环境变量已写进 pm2 env，`pm2 env 0` 可见
- [ ] `ADMIN_OTP_SECRET` / `DATABASE_PATH` 的值**没有被改动**
- [ ] 站点首页仍正常访问
- [ ] **所有 Secret 都没有出现在任何 git 提交、日志、或客户端代码里**

---

## 7. 任务 T3 · 服务端实现

> 详细设计见 `account-and-sync-design-v1.3.md` §3 / §4 / §5 / §6。这里只给落地顺序与目录约定。

### 7.1 目录结构（新增，不改动既有文件）

```
/var/www/deeer/
├── app/api/stumate/v1/
│   ├── health/route.ts                    健康检查（先做这个，用来验证路由生效）
│   ├── auth/
│   │   ├── register/route.ts              邀请码 + 邮箱密码注册
│   │   ├── login/route.ts
│   │   ├── refresh/route.ts
│   │   ├── logout/route.ts
│   │   ├── password/route.ts              改密
│   │   ├── password/reset-with-code/route.ts
│   │   └── invite/check/route.ts          校验邀请码是否可用
│   ├── oauth/[provider]/
│   │   ├── start/route.ts                 返回授权 URL + state
│   │   ├── callback/route.ts              IdP 回调（服务端换票）
│   │   └── poll/route.ts                  客户端轮询取结果
│   ├── devices/route.ts                   设备列表 / 踢下线
│   └── sync/
│       ├── push/route.ts
│       ├── pull/route.ts
│       └── status/route.ts
├── lib/stumate/
│   ├── db.ts                              独立库 stumate.db 的连接与建表
│   ├── schema.ts                          DDL（参考 v1.3 §6.2）
│   ├── password.ts                        scrypt 哈希与校验
│   ├── tokens.ts                          双不透明令牌的签发 / 轮转 / 重放检测
│   ├── invite.ts                          邀请码生成与原子占用
│   ├── oauth.ts                           provider 注册表（github / google）
│   ├── ratelimit.ts                       登录/注册限流（进程内 Map）
│   ├── revision.ts                        单调 revision 分配
│   └── merge.ts                           LWW 合并
├── scripts/
│   ├── stumate-invite.mjs                 生成 / 列出 / 吊销邀请码
│   └── stumate-reset.mjs                  生成密码重置码 / 列出用户
└── types/stumate.ts
```

### 7.2 建库与迁移

- 独立库文件 **`/var/lib/deeer/stumate.db`**（**不要**写进 `site.db`）
- 连接方式参考 `lib/db.ts` 的 better-sqlite3 单例模式
- **启动时建表**（`CREATE TABLE IF NOT EXISTS`），与站点库的做法保持一致
- 记得开 WAL：`db.pragma('journal_mode = WAL')`
- DDL 见 v1.3 §6.2

### 7.3 实现顺序（每步都可独立验证）

| 序 | 内容 | 验证方式 |
|---|---|---|
| 1 | `stumate.db` + 建表 | `sqlite3 /var/lib/deeer/stumate.db ".tables"` |
| 2 | `/health` 路由 | `curl -s https://deeer.online/api/stumate/v1/health` |
| 3 | 邀请码 CLI | `npm run stumate:invite -- --count 3 --note test` |
| 4 | 注册 + 登录 + refresh | curl 走一遍完整流程 |
| 5 | 改密 + 重置码 | `npm run stumate:reset -- --email x@y.z` 后用它改密 |
| 6 | 同步 push / pull | 用两个 uid 手动构造载荷对推 |
| 7 | OAuth（GitHub） | 浏览器走一遍真实授权 |
| 8 | OAuth（Google） | 同上 |
| 9 | 设备管理 | 登录 6 台 → 第 6 台应被拒 |

### 7.4 运维 CLI（`package.json` 追加）

```json
{
  "scripts": {
    "stumate:invite": "node scripts/stumate-invite.mjs",
    "stumate:reset":  "node scripts/stumate-reset.mjs",
    "stumate:users":  "node scripts/stumate-reset.mjs --list-users"
  }
}
```

> **不要改** `otp` 脚本（`npm run otp` 是站点管理员的日常工具）。

### 7.5 验收标准

- [ ] `curl https://deeer.online/api/stumate/v1/health` 返回 200
- [ ] 邀请码：生成 → 注册成功 → 同一码重复用（超出 max_uses）被拒
- [ ] 登录：正确密码通过；错误密码计入限流；第 21 次（同 IP / 15 分钟）被拒
- [ ] refresh：轮转生效；**旧 refresh 再用一次 → 整条链被吊销**
- [ ] 重置码：30 分钟过期；用后即焚；重置成功后**该用户所有 refresh token 失效**
- [ ] 同步：push 后 pull 能拿到；`deletedAt` 的记录不会被复活
- [ ] **站点原有功能全部正常**（博客/评论/留言板/聊天室/点赞/统计/TS3）
- [ ] `/var/lib/deeer/site.db` 的 mtime **没有被 StuMate 改动**

---

## 8. 任务 T4 · 客户端接入

### 8.1 桌面端（`StuMate-Desktop`，优先做）

| 阶段 | 内容 |
|---|---|
| **P3（可最先做，无服务端依赖）** | 数据层加 `uid` / `updatedAt` / `deletedAt`；schema v7→v8 迁移；DAO 查询加 `WHERE deletedAt = 0` |
| P2 | 网络层（新增 Ktor 或 OkHttp）+ `TokenStore`（DPAPI 加密）+ 登录注册 UI |

**P3 的关键约束**：
- `uid` = UUIDv4，**本地主键 `id: Int` 保持不动**，同步层只认 uid → 现有 DAO / UI 查询逻辑**一行都不用改**
- 迁移时为已有数据回填 uid
- **145 个单元测试必须保持全绿**
- 两端 `ClassEntity`(10 列) / `NoteEntity`(8 列) **逐列一致**，`.db` 可互开；加列后 v8 的库 v7 客户端打不开 → **两端必须同步升级**

### 8.2 移动端（`ClassReminderNewest`）

- ⚠️ **`AndroidManifest.xml` 连 `INTERNET` 权限都没有**，必须补
- TokenStore 用 `EncryptedSharedPreferences`
- 因为用 HTTPS + 公信证书，**不需要** `networkSecurityConfig`

### 8.3 可共享的代码

两端的 `data/backup/`（`BackupDocument` + courses/notes/settings 三模块）字段一致、纯 Kotlin，
**把 `BackupFormat.SCHEMA` 1→2 加同步元数据字段（全部可选）**，备份/恢复/同步共用一套编解码，且旧备份文件仍可读。

---

## 9. 安全红线（逐条核对，全部来自 v1.3 附录 B）

1. refresh_token 必须**可吊销**、必须**轮转**、**重放即吊销整条链**
2. 令牌**只存 SHA-256**，明文只在签发那一刻出现一次
3. 密码用 **scrypt `N=2^16, r=8, p=1, keylen=64`**，且**必须显式传 `maxmem`**（Node 默认 32 MiB，而 `N=2^16` 需要 64 MiB，不传会直接抛错）
4. 邀请码用**条件 UPDATE 原子占用**，检查 `changes === 1`
5. 邀请码**存明文**（生命周期短、管理员需再查）；一次性重置码**存哈希**
6. 邮箱**未验证** → **绝不允许任何形式的账号自动合并**
7. 第三方注册**仍需邀请码**，不能绕过准入控制
8. 解绑前校验：**必须已设置密码或已绑其他平台**，否则用户会把自己锁在门外
9. 密码重置成功后**吊销该用户全部 refresh token**
10. 登录限流不只是防撞库，**也是防 DoS**（scrypt 每次占 64 MB 内存）
11. `state` 一次性、绑定会话、用后即焚
12. `client_secret` **只在服务端**，绝不下发客户端
13. 所有 Secret 只进 **pm2 env**，**不进 git、不进日志、不进客户端**

---

## 10. 回滚手册

| 场景 | 操作 |
|---|---|
| 服务端代码改坏 | `cd /var/www/deeer && git reset --hard HEAD~1 && npm run build && pm2 restart deeer` |
| 依赖装坏 | `rm -rf node_modules && npm ci` |
| 数据库改坏 | 用每日 `cp` 备份覆盖 `/var/lib/deeer/stumate.db`（**站点 `site.db` 不受影响**） |
| 环境变量配错 | `pm2 restart deeer --update-env`（先修正 shell 里的 export）；值仍在 `/root/.pm2/dump.pm2` |
| 站点整体挂掉 | `tar xzf /root/deeer-site-backup-<时间戳>.tgz -C /var/www/deeer && npm run build && pm2 restart deeer` |
| 想撤销 git 化 | `rm -rf /var/www/deeer/.git`（代码文件没被动过，站点照常运行） |
| 客户端迁移出问题 | 数据层改动前先自动备份（复用现有 `stumate-backup` 导出） |

---

## 11. 回执格式

完成每个任务后，按这个格式回报（**不要贴任何 Secret 明文**）：

```md
## T{n} · {任务名}

**状态**：完成 / 部分完成 / 阻塞
**耗时**：{大致}

### 实际执行的关键命令
（只列关键几条）

### 验证结果
- [x] {验收项} → 实测输出：`{贴关键输出}`
- [ ] {未通过项} → 原因：{...}

### 偏离设计的地方
{若有，说明改了什么、为什么、影响范围}
若无 → 写「无」

### 遗留问题 / 需要用户决策
{列表}

### 下一步建议
{列表}
```

**汇报时的纪律**：
- 只报**实际执行并验证过**的事。没验证的写成「未验证」，不要写成「应该可以」
- 命令输出**贴原文**，不要转述
- 遇到与设计文档不符的现实情况 → **先回报再动手**，不要自行决定改设计

---

## 附录 A · 命令速查

```bash
# ---------- SSH ----------
ssh -i "F:/DownloadQQ/workbuddy_ed25519.pem" \
    -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -o LogLevel=ERROR \
    root@47.96.173.58 '<命令>'

# ---------- 服务端日常 ----------
cd /var/www/deeer
pm2 list                                  # 看进程
pm2 logs deeer --lines 50                 # 看日志
pm2 restart deeer                         # 重启（env 从 dump 恢复）
pm2 env 0 | grep STUMATE_                 # 看环境变量
curl -s https://deeer.online | head -5    # 站点健康
sqlite3 /var/lib/deeer/stumate.db ".tables"

# ---------- 部署 ----------
# 本地： git add -A && git commit -m "..." && git push
# 服务器：
cd /var/www/deeer && git pull && npm run build && pm2 restart deeer

# ---------- 备份 ----------
tar czf /root/deeer-site-backup-$(date +%Y%m%d-%H%M%S).tgz --exclude=node_modules --exclude=.next .
cp /var/lib/deeer/site.db     /root/site-db-$(date +%Y%m%d).db
cp /var/lib/deeer/stumate.db  /root/stumate-db-$(date +%Y%m%d).db

# ---------- 客户端 ----------
cd "C:/Users/Administrator/IdeaProjects/StuMate-Desktop"
JAVA_HOME="E:/DevTools/Java/jdk-17.0.12+7" ./gradlew test        # 145 个单测
JAVA_HOME="E:/DevTools/Java/jdk-17.0.12+7" ./gradlew compileKotlin
# ⚠️ 不要跑 packageMsi（本机没有 WiX）
```

---

## 附录 B · 术语表

| 术语 | 含义 |
|---|---|
| **StuMate** | 本项目名。课表 + 便签应用，有桌面端与安卓端 |
| **Deerer / deeer-website** | 用户的个人站项目，StuMate 的服务端代码挂在它下面 |
| **第一端** | 首个完成同步的设备。它拥有的历史数据是权威源，其他设备清空后全量拉取 |
| **LWW** | Last-Write-Wins，冲突解决策略（配合版本号） |
| **uid** | 新增的 UUIDv4 列，同步层的唯一标识。**不是**本地主键 `id` |
| **软删除** | 用 `deletedAt` 标记删除而非 `DELETE FROM`，否则同步会把删掉的记录推回来 |
| **revision 游标** | 服务端单调递增的版本号，增量同步用。**不用时间戳**（设备时钟不可信） |
| **双不透明令牌** | access（15 分钟）+ refresh（30 天），都不含信息、都存哈希、都可立刻吊销 |
| **邀请码** | 12 位码，格式 `XXXX-XXXX-XXXX`，字符集沿用 `lib/otp.ts` 的无歧义字母表 |
| **TS3** | TeamSpeak 3，站点的一个子功能（`app/ts3`、`lib/ts3.ts`）。**这是分叉风险的核心** |
