# StuMate 服务端实施 · 交接提示词集 v1.0

> **用法**：按顺序把 P0 → P8 逐条粘贴给私有 Agent。每条都是**自包含**的，可单独使用。
> **前置**：私有 Agent 必须能访问本机文件系统（读取 `C:\Users\Administrator\IdeaProjects\StuMate-Desktop\docs\`）
> 以及服务器 SSH（私钥 `F:\DownloadQQ\workbuddy_ed25519.pem`）。
> **配套文档**：`handover-private-agent-v1.0.md`（交接书）、`account-and-sync-design-v1.3.md`（权威设计）

---

## P0 · 开场（**必须先发这一条**）

```
你是 StuMate 项目的服务端实施 Agent。上一阶段由另一个会话完成了服务器勘察与方案设计，
现在把实施工作交接给你。

【第一步：读文档，先不要动手】
依次读完这三份，读完后用不超过 300 字向我复述你的理解：
1. C:\Users\Administrator\IdeaProjects\StuMate-Desktop\docs\handover-private-agent-v1.0.md
   → 这是你的交接书，包含角色边界、资产清单、已冻结的决策、服务器实况、5 个任务
2. C:\Users\Administrator\IdeaProjects\StuMate-Desktop\docs\account-and-sync-design-v1.3.md
   → 这是权威设计文档，包含全部设计推导。与交接书冲突时以它为准
3. C:\Users\Administrator\IdeaProjects\StuMate-Desktop\.workbuddy-ai\memory\MEMORY.md
   → 项目长期记忆，含历史踩坑

【复述时必须覆盖这 4 点】
- 最大的数据丢失风险是什么？（提示：和 TS3 模块有关）
- 哪些目录/文件是绝对不能碰的？
- 服务端代码要 push 到哪里、服务器扮演什么角色？
- 一共有哪 5 个任务（T1~T4 加回执）？

【纪律】
- 读完之前不要执行任何命令
- 后续每个任务完成后，按交接书 §11 的格式写回执
- 遇到与设计文档不符的现实情况，先回报再动手，不要自行改设计
- 不要向我索取 GitHub / Google 的账号密码，涉及登录时引导我本人操作

复述完之后，告诉我你建议从哪个任务开始，以及理由。
```

---

## P1 · T1 · git 化 + GitHub 私有仓库 + 部署流程【高危，先做这个】

```
执行交接书的 T1（§5）。这是全流程唯一有数据丢失风险的一步，请严格按顺序来。

【背景】
- 服务器 /var/www/deeer 的代码比本地仓库新：多了 TS3 模块
  （app/ts3/page.tsx、app/api/ts3/action/route.ts、app/api/ts3/status/route.ts、lib/ts3.ts）
  以及 check_server.py、deploy_step1a.py、deploy_step1b.py、deploy_step2.py —— 这些从未被提交过
- 本地 C:\Users\Administrator\IdeaProjects\DeeerWebsite 有 10 个 commit（HEAD=997f5cf），但没有配任何 remote
- 已定策：以服务器线上现状为基线，本地旧历史归档到 legacy/pre-server-baseline
- 已实测：服务器到 github.com:22 和 ssh.github.com:443 都通，走 443 更稳

【执行步骤】
1. 服务器上先做全量备份（tar 打包 + 单独备份 site.db），确认备份文件存在且大小合理
2. 服务器上 git config --global 配置 user.name / user.email（服务器目前没有全局身份）
3. 服务器上 git init -b master，提交线上现状为基线
4. 【安全检查】提交后立刻验证：git status --ignored 确认 data/、.env、*.pem 都没进仓库；
   git ls-files 确认没有 otp-secret / site.db；du -sh .git 确认体积正常（几 MB，不是几百 MB）
5. 服务器上生成 ED25519 部署密钥，输出公钥
6. 配置 /root/.ssh/config 让 github.com 走 ssh.github.com:443
7. 关联 origin 并 push

【需要我人工介入的地方，请停下来告诉我】
- 在 GitHub 创建【私有】空仓库（不要勾 README / .gitignore / license），把仓库地址给我
- 把公钥贴到仓库 Settings → Deploy keys（不要勾 Allow write access）

【做完服务器侧后，本地侧由你执行】
- git remote add origin + git fetch
- git branch -m master legacy/pre-server-baseline
- git switch -c master origin/master

【必做的验证（§5.5）】
git diff --stat legacy/pre-server-baseline master
预期差异只有 TS3 相关文件 + check_server.py + deploy_step*.py。
如果出现其他差异（尤其是 app/api/chat、lib/chat-store.ts、components/chat-room.tsx），
立刻停下来告诉我，不要 push。

【最后】做一次端到端演练：本地提交一个空 commit → push → 服务器 pull + build + restart →
curl 验证站点正常。然后按交接书 §11 格式写回执。

【红线】不要碰 /var/lib/deeer/site.db；不要改 /etc/nginx/**；
不要动 pm2 里的 ADMIN_OTP_SECRET 和 DATABASE_PATH。
```

---

## P2 · T2a · 创建 GitHub OAuth App

```
执行交接书的 §6.2：创建 GitHub OAuth App。

【前置确认】
先读交接书 §6.1，理解我们的 OAuth 架构：客户端 → 系统浏览器 → IdP → 回调到【服务端】→
服务端换票 → 客户端轮询取结果。所以 client_secret 只在服务端，客户端不需要 PKCE 或自定义 scheme。

【需要我本人操作的部分 —— 请引导我，不要索取我的账号密码】
1. 打开 https://github.com/settings/developers
2. 点 OAuth Apps → New OAuth App（注意是 OAuth Apps，不是 GitHub Apps）
3. 表单填：
   - Application name: StuMate
   - Homepage URL: https://deeer.online
   - Application description: StuMate 课表应用的账号登录与云同步
   - Authorization callback URL: https://deeer.online/api/stumate/v1/oauth/github/callback
4. 创建后把 Client ID 给我
5. 点 Generate a new client secret，把 Secret 给我
   （只显示一次，如果丢了就重新生成）

【请同时确认这几件事】
- 授权 scope 用 read:user + user:email（user:email 是必须的，因为 GitHub 用户邮箱可能设为私有，
  此时 /user 的 email 字段返回 null，只能从 /user/emails 读）
- 取邮箱时选 primary === true && verified === true 的那条
- 换票请求必须带 Accept: application/json，否则返回 form-urlencoded 会解析失败

【拿到凭据后】
不要写进任何文件或 git。按交接书 §6.4 的方式写进服务器 pm2 env：
先 export，再 pm2 restart deeer --update-env，再 pm2 save。
然后 pm2 env 0 | grep STUMATE_ 验证。

【注意】现在只做 GitHub。Google 等我确认后再做。
```

---

## P3 · T2b · 创建 Google OAuth 客户端

```
执行交接书的 §6.3：创建 Google OAuth 客户端。

【需要我本人操作的部分 —— 请引导我】
第一步：配置 OAuth 同意屏幕（console.cloud.google.com → APIs & Services → OAuth consent screen）
   - User Type: External
   - App name: StuMate
   - User support email / Developer contact email: 用我的邮箱
   - Authorized domains: deeer.online（必须加，否则保存报错）
   - Scopes: openid、email、profile（都是非敏感 scope，不需要 Google 审核）

第二步：创建凭据（Credentials → Create Credentials → OAuth client ID）
   - Application type: Web application
   - Name: StuMate Server
   - Authorized redirect URIs: https://deeer.online/api/stumate/v1/oauth/google/callback
   - Authorized JavaScript origins: 留空
   创建后把 Client ID 和 Client Secret 给我

【请提醒我注意这几点】
- redirect URI 必须精确匹配（scheme / host / path / 末尾斜杠，一个字符都不能差）
- 不要传 access_type=offline —— 那是要 refresh token 的，会让用户看到「离线访问」这种吓人的授权项，
  我们不需要
- 同意屏幕默认是 Testing 模式（上限 100 用户，但持有 refresh token 的话 7 天失效）。
  我们不保存 Google 的 token，所以 7 天限制不影响我们；但仍然建议直接点 Publish app 转 Production，
  因为 scope 非敏感、无需审核

【拿到凭据后】
按交接书 §6.4 写进服务器 pm2 env，并验证 ADMIN_OTP_SECRET / DATABASE_PATH 没有被改动。

【最后】走一遍交接书 §6.5 的自检清单，逐项打勾，然后按 §11 格式写回执。
```

---

## P4 · T3a · 服务端骨架 + 独立数据库 + 健康检查

```
执行交接书 §7.1 和 §7.2：搭服务端骨架，先打通最基础的一条链路。

【目标】新增的代码全部放在 /api/stumate/v1/** 和 lib/stumate/** 下，不改动任何既有文件。

【要做的事】
1. 建独立数据库 /var/lib/deeer/stumate.db（绝对不要写进 site.db）
   - 参考 lib/db.ts 的 better-sqlite3 单例模式
   - 开 WAL: db.pragma('journal_mode = WAL')
   - DDL 见设计文档 v1.3 §6.2，启动时 CREATE TABLE IF NOT EXISTS
2. 建 app/api/stumate/v1/health/route.ts，返回 { ok: true, version, time }
3. 建 lib/stumate/ 下的目录骨架（先放占位，后续任务填）

【验收】
- sqlite3 /var/lib/deeer/stumate.db ".tables" 能看到所有表
- curl -s https://deeer.online/api/stumate/v1/health 返回 200
- 站点原有功能全部正常（博客/评论/留言板/聊天室/点赞/统计/TS3）
- /var/lib/deeer/site.db 的 mtime 没有被改动

【部署方式】
本地改代码 → commit → push → 服务器 git pull && npm run build && pm2 restart deeer
（T1 完成后应该已经是这个流程了；如果不是，先回去确认 T1）

【红线】
- 不要改 /etc/nginx/**（location / 已经兜住所有路径，新路由自动生效）
- 不要改 lib/auth.ts、lib/otp.ts（那是站点管理员登录，和 StuMate 无关）
- 不要跑 packageMsi 之类构建安装包的命令

完成后按交接书 §11 格式写回执。
```

---

## P5 · T3b · 邀请码 CLI + 注册登录

```
执行设计文档 v1.3 §3.1~§3.4 和 §4：账号体系。

【关键设计点，实现前请确认你都理解了】
1. 密码用 Node 内置 crypto.scrypt，参数 N=2^16, r=8, p=1, keylen=64
   ⚠️ 必须显式传 maxmem（Node 默认 32MiB，而 N=2^16 需要 64MiB，不传会直接抛错）
2. 双不透明令牌（不是 JWT）：
   - access 15 分钟 / refresh 30 天
   - 两个都存 SHA-256（明文只在签发那一刻出现一次）
   - refresh 必须轮转，重放即吊销整条链
3. 邀请码（§3.2）：
   - 12 位，格式 XXXX-XXXX-XXXX
   - 字符集沿用 lib/otp.ts 的 ABCDEFGHJKMNPQRSTVWXYZ23456789（去掉了易混的 I/L/O/0/1）
   - 存【明文】不存哈希（生命周期短、管理员需要再查）
   - 占用必须【原子】：条件 UPDATE + 检查 changes === 1，防止并发超发
4. 找回密码（§3.3）：管理员 CLI 生成一次性码，存 SHA-256，30 分钟有效、用后即焚，
   重置成功后【吊销该用户全部 refresh token】
5. 限流（§3.6）：复用 lib/rate-limit.ts 的模式，但【另建计数表】——
   现有的 isRateLimited 是「每 IP 每 30 秒 3 次」，对登录太宽松。
   阈值：同 IP 每 15 分钟 20 次；同邮箱每 15 分钟 10 次；注册同 IP 每 24 小时 10 次
   ⚠️ 限流不只是防撞库，也是防 DoS —— scrypt 每次占 64MB 内存
   ⚠️ pm2 是 fork 单实例，所以进程内 Map 有效。请把这句写进代码注释

【CLI（§3.4）】
scripts/stumate-invite.mjs  --count / --note / --max-uses / --expires / --list / --revoke
scripts/stumate-reset.mjs   --email / --list-users
package.json 追加 stumate:invite / stumate:reset / stumate:users 三个 script
⚠️ 不要改现有的 otp script（那是站点管理员的日常工具）
写法参考 scripts/get-otp.mjs

【API】
POST /api/stumate/v1/auth/register
POST /api/stumate/v1/auth/login
POST /api/stumate/v1/auth/refresh
POST /api/stumate/v1/auth/logout
POST /api/stumate/v1/auth/password
POST /api/stumate/v1/auth/password/reset-with-code
GET  /api/stumate/v1/auth/invite/check

【验收】
- 邀请码：生成 → 注册成功 → 同一码超出 max_uses 再用被拒
- 登录：正确密码通过；错误密码计入限流；同 IP 第 21 次被拒
- refresh 轮转生效；旧 refresh 再用一次 → 整条链被吊销
- 重置码 30 分钟过期、用后即焚；重置后该用户所有 refresh token 失效
- 站点原有功能全部正常

完成后按交接书 §11 格式写回执。
```

---

## P6 · T3c · 同步接口（push / pull / status）

```
执行设计文档 v1.3 §5 和 §6：云同步。

【核心难点（务必先理解，否则会做出会丢数据的实现）】
1. 两端本地主键都是 @PrimaryKey val id: Int，【没有 autoGenerate】，id 由应用自己分配
   → 跨设备必然撞号。解法：新增 uid (UUIDv4) 列，同步层只认 uid
   → 本地 DAO / UI 继续用 id，【现有查询逻辑一行不用改】
2. 必须软删除（deletedAt）。否则「A 删除 → B 不知情 → 下次同步推回来 → 数据复活」
3. 增量同步用服务端单调 revision 游标，【不要用时间戳】（设备时钟不可信，会漏数据）
4. 冲突解决：版本号 + LWW。已知限制：两端离线同改一条会丢一方 —— 这个限制要写进注释和文档
5. 载荷复用现有 stumate-backup 的模块化 JSON 格式
   （BackupDocument + courses/notes/settings 三模块），
   把 BackupFormat.SCHEMA 从 1 升到 2，新增字段【全部可选】，保证旧备份文件仍可读

【API】
POST /api/stumate/v1/sync/push
GET  /api/stumate/v1/sync/pull?since=<revision>
GET  /api/stumate/v1/sync/status

【首次同步 / uid 回填（§5.8）】
以【第一端】为准：首个同步的设备是权威源，其他设备清空后全量拉取。
⚠️ 清空前【必须自动做一次本地备份】

【验收】
- push 后 pull 能拿到；since 游标能正确增量
- 带 deletedAt 的记录不会被复活
- 两个 uid 冲突时 LWW 生效（版本号大的赢）
- 站点原有功能全部正常

完成后按交接书 §11 格式写回执。
```

---

## P7 · T4 · 客户端数据层改造（P3，可独立于服务端先做）

```
执行交接书 §8.1 的 P3：两端数据层改造。这一步不依赖服务端任何信息，可以立刻做。

【仓库】
- 桌面端：C:\Users\Administrator\IdeaProjects\StuMate-Desktop
- 移动端：C:\Users\Administrator\IdeaProjects\ClassReminderNewest
- JDK：E:\DevTools\Java\jdk-17.0.12+7

【要做的改动】
1. ClassEntity / NoteEntity 各新增三列：uid (TEXT, UUIDv4)、updatedAt (INTEGER)、deletedAt (INTEGER, 默认 0)
2. schema v7 → v8 迁移，为已有数据回填 uid
3. DAO 查询加 WHERE deletedAt = 0；删除改成 UPDATE 设 deletedAt
4. 保留本地主键 id: Int 不变 —— 同步层只认 uid，现有查询逻辑一行不用改

【硬约束】
- ⚠️ Compose Multiplatform 锁死在 1.5.10，不要升到 1.5.11 / 1.5.12
- 两端 ClassEntity(10列) / NoteEntity(8列) 必须【逐列一致】，.db 文件要能互开
- 加列后 v8 的库 v7 客户端打不开 → 两端必须同步升级
- 145 个单元测试必须保持全绿
- 改数据层时不要碰 ui/ 目录

【验收】
- JAVA_HOME="E:/DevTools/Java/jdk-17.0.12+7" ./gradlew test → 145/145 通过
- ./gradlew compileKotlin → 0 告警
- 旧库能正确迁移（先复制一份旧库做测试）
- ⚠️ 不要跑 packageMsi（本机没有 WiX，会失败）

【用户偏好（来自项目记忆）】
- 每次 UI 改动要产出 HTML 验收页（放 design-preview/）+ 实机截图，让用户亲自验收
- 但这一步是数据层改造，没有 UI 变化，所以只需给出迁移前后的数据对比证据

完成后按交接书 §11 格式写回执。
```

---

## P8 · 回执模板（每个任务完成后用）

```
请按下面的格式，为刚才完成的 {T编号} 写回执。不要贴任何 Secret 明文。

## T{n} · {任务名}

**状态**：完成 / 部分完成 / 阻塞
**耗时**：{大致}

### 实际执行的关键命令
（只列关键几条，不要贴完整日志）

### 验证结果
- [x] {验收项} → 实测输出：`{贴关键输出原文}`
- [ ] {未通过项} → 原因：{...}

### 偏离设计的地方
{若有，说明改了什么、为什么、影响范围}
若无 → 写「无」

### 遗留问题 / 需要用户决策
{列表}

### 下一步建议
{列表}
```

---

## 附：任务依赖顺序

```
T1（git 化）────┬──→ T3a（骨架）──→ T3b（账号）──→ T3c（同步）──┐
                │                                                │
                └──→ T2a（GitHub OAuth）──→ T2b（Google OAuth）──┤
                                                                  │
                          T4/P3（客户端数据层，无依赖，可随时并行）─┘
```

**建议顺序**：`T1 → T2a → T2b → T3a → T3b → T3c`，同时**并行**推进 `T4/P3`。

> T4/P3 与 T3c 是天然并行的一对：P3 决定客户端怎么存，T3c 决定服务端怎么收。
> 两者的接口契约由设计文档 v1.3 §5.7 固定，可以各做各的。
