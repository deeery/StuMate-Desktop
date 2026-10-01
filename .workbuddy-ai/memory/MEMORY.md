# StuMate-Desktop · 项目长期记忆

## 项目定位

安卓课表提醒 App「StuMate」（源项目 `C:\Users\Administrator\IdeaProjects\ClassReminderNewest`）
的 **Windows 桌面客户端**。Kotlin + Compose Multiplatform Desktop。

## 硬性约束（改代码前先看这里）

| 项 | 值 | 说明 |
|---|---|---|
| Kotlin | **1.9.20** | 与安卓源项目一致 |
| Compose Multiplatform | **1.5.10** | 勿升 1.5.11/1.5.12（要求 Kotlin 1.9.21/1.9.22） |
| Gradle | **8.4** | wrapper 不可用（见下），用项目内 `gradlew` 转发脚本 |
| JDK | 17（`E:\DevTools\Java\jdk-17.0.12+7`） | 写进 `gradle.properties` 的 `org.gradle.java.home` |
| 包名 | `com.example.classreminder` | **刻意与安卓端一致**，让 `MainScreen.kt` 零 import 改动 |
| 数据目录 | `%APPDATA%\StuMate\` | `settings.json` + `class_reminder.db` |
| JNA | **5.6.0** | 只为调 `dwmapi` 做 Win11 原生圆角 / 描边（见坑 12），本地 Gradle 缓存里已有 |

## 环境已知坑

1. **Gradle wrapper 不能用**：`services.gradle.org` 重定向到 GitHub release assets 返回 502。
   项目根目录的 `gradlew` / `gradlew.bat` 是**转发脚本**，指向本机缓存的 Gradle 8.4 发行版。
   不要试图用 `gradle wrapper` 重新生成。
2. **`compose.material` 对 `material-icons-core` 是 runtime 作用域**，编译期不可见，
   必须显式依赖 `org.jetbrains.compose.material:material-icons-core:1.5.10`。
3. **CMP 1.5.10 无 `Icons.AutoMirrored`**（1.6.0 才有）→ 用 `Icons.Default.List`。
4. **`androidx.compose.ui.util.lerp`** 需显式依赖 `org.jetbrains.compose.ui:ui-util:1.5.10`。
5. **`Dispatchers.Swing`** 需 `import kotlinx.coroutines.swing.Swing`。
6. **`MenuScope.Item` / `Separator`** 是 `MenuScope` 成员函数，不要 import。
7. **截图**：PowerShell 的 `Add-Type` 与反射加载程序集被沙箱拦截。
   用 `tools/capture_window.py`（Python + Pillow，venv 在
   `~/.workbuddy-ai/binaries/python/envs/default`）。交互模拟用 `tools/win_input.py`。
8. **窗口置顶**：`SetForegroundWindow` 在后台进程里常被 Windows 拒掉，直接截图会截到别的窗口。
   必须 `ShowWindow(SW_MINIMIZE)` → `SW_RESTORE` → `BringWindowToTop` → `SetForegroundWindow`，
   再用 `GetForegroundWindow() == hwnd` 校验（两个脚本都已实现 `raise_window`）。
9. **模拟点击定位**：不要目测截图坐标。用 PIL 扫描目标区域像素找控件行范围
   （本机 100% 缩放下：侧栏导航项 38px 高、设置子导航项 34px 高）。
10. **无边框窗口不能用 `WindowPlacement.Maximized`**：会连任务栏一起盖住。
    要自己算工作区（`Toolkit.getScreenInsets`）再设 `WindowState.size/position`，
    并自己记住还原尺寸。见 `ui/fluent/WindowChrome.kt` 的 `workAreaOf()`。
11. **`SetForegroundWindow` 会被 Windows 前台锁拒掉**（返回成功但没上来）。
    要配合 `SetWindowPos(HWND_TOPMOST)` → `HWND_NOTOPMOST` → `BringWindowToTop`。
    且「最小化→还原」之后第一次点击会被系统吃掉，脚本需先点一下空白处热身。
12. **窗口拖动绝对不能用 `detectDragGestures` 的 `dragAmount` 累加**。
    它给的是**指针在节点本地坐标系的位移**，而节点跟着窗口一起走，于是
    `dragAmount = Δ光标 − Δ窗口`，累加进窗口位置后化简为 `Δ窗口 = Δ光标 / 2`：
    窗口只以一半速度跟手，且自身位移被下一帧当成反向增量扣回来 → **持续抽搐**。
    （实测：光标移 120px 窗口只走 60px，中途方向反转 6 次；光标一停立刻稳定。）
    正确做法：用 `MouseInfo.getPointerInfo().location` 读**屏幕绝对光标坐标**，
    按下时记 `grab = 光标 − 窗口左上角`，拖动中 `窗口 = 光标 − grab`。
    这是纯函数、天然幂等，不会自己和自己打架。见 `WindowChrome.moveTo()`。
13. **拖动时必须同时写 `windowState.position`**，否则它一直停在
    `rememberWindowState` 的初始值（`WindowPosition(Alignment.Center)`），
    「最大化→还原 / 最小化→还原」会把窗口甩回屏幕正中。
    实测修复前会跳到 (620,290)，修复后精确回到拖动位置。
14. **Win11 原生圆角必须走 DWM，不要用「透明窗口 + 自己画圆角」**。
    无边框窗口 Win11 默认给直角，要 `DwmSetWindowAttribute(hwnd, 33, DWMWCP_ROUND)`；
    描边用属性 34（`DWMWA_BORDER_COLOR`，注意是 **COLORREF `0x00BBGGRR`**，字节序与 ARGB 相反）。
    透明窗口方案会让 Windows 对 alpha=0 的区域做逐像素穿透命中测试，
    而 `WindowResizeHandles` 的四个角热区恰好落在被切掉的圆角里 → 角上缩放整个失效。
    DWM 方案窗口仍是不透明的，实测角上缩放 +120/+60 精确命中。
15. **单击 + 双击共存时，别用 `detectTapGestures(onTap, onDoubleTap)` / `combinedClickable`**。
    这两个 API 为了区分单击与双击，必须等**双击超时**（`ViewConfiguration.doubleTapTimeoutMillis`，
    桌面端默认 **300ms**）才敢确认是单击 —— 结果是**每一次单击都原地僵 300ms**。
    这是 API 语义，不是性能问题，优化重组/布局都没用。
    正确做法：单击走 `clickable(interaction, indication = null)`（抬起即触发），
    双击自己用 `onPointerEvent(PointerEventType.Press)` 记时间戳，两次按下间隔 ≤420ms 视为双击。
    双击时第一次单击会先「选中」，而选中是幂等的，无副作用。
    实测：修复前 ≈300ms，修复后 23.4ms（对照组：普通按钮 19.5ms）。
16. **`hoverable(remember { MutableInteractionSource() })` 是空转的**。
    `hoverable` 只负责把指针进出写进交互源；**不读这个源就不会有任何视觉变化**。
    必须配 `val hovered by interaction.collectIsHoveredAsState()` 并把 `interaction` 传进去，
    否则就是「有事件、无反馈」—— 用户看到的就是「悬浮没反应」。
    正确范式见 `ui/fluent/NotesPage.kt` 的 `NoteTableRow`。
17. **整列 / 整行 hover 不要给每个格子挂 Enter/Exit 再共享状态**。
    鼠标从「表头」滑到「同一天的列」时，相邻节点的 `Exit` 与 `Enter` 派发顺序不保证，
    会把共享状态打回 -1 → 高亮闪断。
    改用「在整张表上挂一个 `Move`，拿指针 x 反算落在第几列」，
    `Exit` 只挂在整张表上（鼠标在表内移动时只走 `Move` 分支，不存在互相打断）。
    见 `ui/fluent/WeekPage.kt` 的 `WeekGrid`。
18. **多色 `ImageVector` 用 `Icon()` 渲染时，必须传 `tint = Color.Unspecified`**。
    Material3 的 `Icon(imageVector, …)` 默认 `tint = LocalContentColor.current`，
    内部走 `ColorFilter.tint(...)` —— 会把**整张图刷成单色**。
    品牌标识是「强调色底 + 墨色字 + 琥珀高亮格」三色，被染色后只剩一个剪影。
    `Color.Unspecified` 时 material3 不生成 `ColorFilter`，矢量自带的颜色才生效。
    见 `ui/fluent/StuMateMark.kt` / `AppShell.kt` 的品牌行。

## 账号与云同步（设计已定稿 · 服务端已上线 · P3 两端已完成）

设计方案：`docs/account-and-sync-design-v1.3.md`（**已定稿，可实施**）。
实施交接：`docs/handover-private-agent-v1.0.md`（交接书）+ `docs/handover-prompts-v1.0.md`（8 条可粘贴提示词）。
v1.0 / v1.1 / v1.2 保留供对比。

需求已定：邮箱+密码主账号、第三方 OAuth2 作绑定项、**可选登录本地优先**、
只同步课程与便签（设置留本地）、服务端已有 HTTPS。

### 进度（2026-09-30）

| 阶段 | 状态 |
|---|---|
| P0 服务端勘察 | ✅ |
| T1 git 化 + GitHub 私有仓库 | ✅（服务器代码含未提交的 TS3 模块，已以线上现状为基线） |
| T2 OAuth App | GitHub ✅ 已配；Google ⏳ 待补凭据 |
| T3a 服务端骨架 + `stumate.db` | ✅ |
| T3b 账号体系（邀请码 / 注册登录 / 令牌 / CLI） | ✅ |
| T3c 同步接口 | ❌ **未实现**（`records` / `sync_log` 表已建，三个路由还没有） |
| P3 两端数据层（uid / updatedAt / deletedAt） | ✅ 两端都完成，**均未提交** |

服务端接口契约：`F:\DownloadQQ\StuMate-服务端-OAuth接口文档-v1.0.md`。
基址 `https://deeer.online/api/stumate/v1`；OAuth 走「服务端中介 + 一次性 state + 客户端轮询」，
客户端**不需要**自定义 URL scheme、**不需要** PKCE、**永远拿不到** `client_secret`。
移动端 P3 验证报告：`docs/p3-mobile-migration-verification-v1.0.md`。

**动手前必须知道的四件事**：

1. **主键会撞号**。`classes` / `notes` 的主键是 `@PrimaryKey val id: Int`，
   **没有 `autoGenerate`**，id 由应用分配。两端各自新建记录必然撞号。
   同步方案：加 `uid TEXT`（UUIDv4）列，**同步层只认 uid，本地 DAO/UI 继续用 id** ——
   这样现有查询逻辑一行都不用改。
2. **必须软删除**。DAO 现在是 `DELETE FROM`，同步场景下会导致
   「A 删除 → B 不知情 → 下次同步推回来 → 数据复活」。
   加 `deletedAt`，查询加 `WHERE deletedAt = 0`。
3. **同步载荷复用现有备份格式**。`data/backup/` 的 `BackupDocument`
   （courses/notes/settings 三模块）两端字段一致、纯 Kotlin。
   把 `BackupFormat.SCHEMA` 1→2 加同步元数据字段（全部可选），
   备份/恢复/同步共用一套编解码，且旧备份文件仍可读。
4. **安卓端 Room 的 DAO 不能用 Kotlin 接口默认方法做包装**。
   需要一层「落库前补 uid / updatedAt」的方法，而 Room 的实现是 kapt 生成的 **Java** 类，
   `-Xjvm-default=disable`（Kotlin 1.9 默认）下接口默认方法编译成 `DefaultImpls` + 抽象方法，
   Java 实现类不带桥接 → 运行时 `AbstractMethodError`。
   **改用 `abstract class` DAO**（Room 官方 `@Transaction` 包装方法就是这个形态）。
   ⚠️ 另外：`UUID.randomUUID()` **不要**写成实体构造参数的默认值 ——
   每次 `copy()` 都会换新 uid（如拖动便签排序的 `note.copy(position = i)`），记录身份会断。

**Room schema 校验的一个反直觉结论**（2026-09-30 从字节码确认）：
迁移里 `ALTER TABLE ... ADD COLUMN uid TEXT NOT NULL DEFAULT ''`，而实体**不**声明
`@ColumnInfo(defaultValue = ...)`，**不会**触发 `Migration didn't properly handle`。
`room-runtime-2.6.1` 的 `TableInfo$Column.equals` 在「实体侧 vs 数据库侧」比较时，
**只要实体侧 `defaultValue == null` 就直接跳过默认值比较**。
所以不必为了过校验而加 `@ColumnInfo`（项目既有迁移 5→6、6→7 也是这么写的）。

**两端共同的缺口**：目前**零网络依赖**（无 OkHttp/Ktor/Retrofit）。
移动端 `AndroidManifest.xml` **连 `INTERNET` 权限都没有**，加网络层时必须补。

### 服务端实况（2026-09-30 已实地勘察）

```
公网 47.96.173.58  /  内网 172.24.55.32（同一台机器的 eth0，公网访问不到）
域名 deeer.online  /  www.deeer.online
系统 Alibaba Cloud Linux 3 · 2vCPU / 1.8G 内存 / 40G 盘
Node v24.9.0 · npm 11.6.0 · pm2（进程名 deeer，fork 单实例）
nginx 80/443 → 127.0.0.1:3000
TLS  Let's Encrypt（acme.sh 自动续期，2026-12-16 到期）
```

- **SSH 私钥**：`F:\DownloadQQ\workbuddy_ed25519.pem`
  指纹 `SHA256:7y5qk1pUsu9w9US/5S7jkh9z6TBs7sz9QC9vvTEnHl8`
  登录 `ssh -i <key> root@47.96.173.58`
- **后端不是专门的服务**，是个人站 `deeer-website`（`/var/www/deeer`）：
  Next.js 16.3.0 + React 19.2.8 + TS + Tailwind 4 + better-sqlite3。
  含博客/评论/留言板/聊天室/点赞/访问统计/TeamSpeak 控制 + 管理后台。
- **真实数据库是 `/var/lib/deeer/site.db`**（pm2 env 里的 `DATABASE_PATH` 指定），
  项目目录下的 `data/site.db` 是**废弃副本**（最后写入停在 9/15），别被它误导。
- **没有 `.env` 文件**，配置全在 pm2 env 里（`/root/.pm2/dump.pm2`）。
  新增环境变量必须同步更新 pm2 配置，否则重启即丢。
- **没有 git 仓库**，靠手工改文件 + `deeer-site-backup-*.tgz` 备份 + `pm2 restart deeer`。
- **现有认证是单管理员免密码 OTP**（`lib/auth.ts` + `lib/otp.ts`，5 位动态码 HMAC-SHA1
  60s 轮换 → `sessions` 表 → httpOnly cookie）。**没有 users 表**，不是多账号体系。
- **`/api/auth/*` 已被管理员登录占用**，StuMate 接口必须另起命名空间
  （方案里定的是 `/api/stumate/v1/**`）。
- 可复用：`lib/rate-limit.ts`（进程内内存限流）、`lib/validate.ts`、`lib/db.ts` 的建表模式。
- **邮件通道不通**：postfix 已安装但 inactive/disabled，且**阿里云默认封 25 端口出站**
  （实测 `smtp.qq.com:25` 超时）。→ v1.2 起**本期完全不需要邮件**，此阻塞项已消除。
- **服务器上没有任何 git 仓库**（`/var/www/deeer`、`/root`、`/srv` 全无 `.git`），
  且 git **无全局身份**（`user.name`/`user.email` 为空）→ 首次提交前必须先配置。
- **服务器可直连 GitHub**：`github.com:22` 与 `ssh.github.com:443` 实测**均通**，走 443 更稳
  （云厂商对 22 出站偶有限制）。
- **pm2 是 fork 单实例**（不是 cluster）→ 登录限流用进程内 Map 有效；
  若将来改 cluster 必须换共享存储。这条要写进代码注释。
- **nginx `deeer.online` server 块内联在 `/etc/nginx/nginx.conf`**（不在 `conf.d/`，
  `conf.d/` 里只有 `ts3.deeer.online.conf`）。证书 `/etc/nginx/ssl/deeer.online.{fullchain.cer,key}`。
  `location ^~ /ts3` 与 `/api/ts3/` 在 `deeer.online` 上 **return 404**（面板只走子域名）。
  **`location /` 已兜住所有路径 → 新增 `/api/stumate/**` 自动生效，nginx 不用改。**
- **`node_modules` 占 511M**；`.gitignore` 已存在且正确（排除 `/node_modules`、`/data/`、
  `/.next/`、`.env*`、`*.pem`）。
- **本地 `C:\Users\Administrator\IdeaProjects\DeeerWebsite` 已有 git 仓库**（10 个 commit，
  HEAD `997f5cf`，分支 `master`），但**一个 remote 都没配**。

### 已确认的需求决策

- 主账号 **邮箱 + 密码**；第三方**只做 GitHub + Google**（微信/QQ 需企业资质，本期排除）
- 冲突策略 **LWW**；历史数据 uid 回填 **以第一端为准**（首个同步的设备为权威源，
  其他设备清空后全量拉取，**清空前必须自动本地备份**）
- **可选登录、本地优先**；只同步课程与便签，设置类留本地
- 客户端一律用 `https://deeer.online`，**不能用 IP**（内网不可达；裸 IP 证书域名不匹配）
- **规模 ≤ 10 人，小规模测试**；**注册走邀请码**（v1.2 起）
- **v1 不做邮箱验证** → 原来的「邮件通道不通」阻塞项**消除**；
  找回密码改走**管理员 CLI 生成一次性重置码**（复用现有 `npm run otp` 的模式，零邮件依赖）
- 设备上限 **5 台**（原定 10 台，随规模收窄）
- 邮箱**未验证**，所以第三方账号**绝不允许自动合并**到已有主账号（防冒绑），
  第三方注册**仍需邀请码**

**规模带来的简化（明确「不做」清单）**：不需要 Redis（进程内限流够）、不需要分库分表、
不需要邮件服务、`sync_log` 加 90 天清理即可。
scrypt 容量核算：libuv 线程池 4 线程 × 64MB = 256MB，1.8GB 内存安全，**参数不用降档**。

**v1.3 追加决策（2026-09-30 四项待确认全部关闭）**：

- **部署方式**：**GitHub 私有仓库 + 服务器 `git pull`**。服务器持**只读 Deploy key**，
  永远不给 push 权限 —— 服务器上的临时改动必须先落回本地再走正常流程。
- **数据库位置**：确认独立库文件 **`/var/lib/deeer/stumate.db`**（与站点库隔离爆炸半径）。
- **注销账号**：**本期不做**，需要时管理员 CLI 手工删。
- **昵称要、头像不做**；**登录 UI 只做中文**；同步时机＝启动 + 写操作后 30 秒防抖 + 手动。
- OAuth App 创建**交给私有 Agent** 执行（需浏览器凭据），交接见下。

**⚠️ 最大的实施风险：服务器代码存在未提交分叉。**
`/var/www/deeer` 比本地 git 仓库**新**，多了从未提交的 **TS3 模块**
（`app/ts3/page.tsx`、`app/api/ts3/{action,status}/route.ts`、`lib/ts3.ts`）
以及 `check_server.py` / `deploy_step{1a,1b,2}.py`。
**第一次 push 前必须先以线上现状建立基线提交**（服务器 `git init` + commit），
否则 push 会把 TS3 删掉。已定策：以线上现状为基线，本地旧 10 个 commit 归档到
`legacy/pre-server-baseline`。

设计方案：`docs/account-and-sync-design-v1.3.md`（最新，**已定稿可实施**）；
v1.0 / v1.1 / v1.2 保留供对比。
实施交接：`docs/handover-private-agent-v1.0.md`（交接书）、`docs/handover-prompts-v1.0.md`（8 条可粘贴提示词）。

## 架构分层

```
src/main/kotlin/com/example/classreminder/
├── Main.kt              application{} 入口：Window + Tray + 提醒引擎启动 + 关闭确认弹窗
├── Prefs.kt             %APPDATA%\StuMate\settings.json（复用 MiniJson，原子写）
├── AppPaths.kt          数据目录
├── platform/            平台接缝层（安卓 → Windows 的全部替换都在这）
│   ├── ReminderEngine   替代 ClassReminderService（常驻协程 30s 轮询）
│   ├── AutoStart        替代 BootReceiver（HKCU\...\Run 注册表）
│   ├── FileDialogs      替代 SAF（JFileChooser）
│   └── ToastHost        替代 android.widget.Toast（全局 ToastBus）
├── ui/                  Theme / TodayScreen / MainScreen / OverlayScreen / DateTimePickers
└── data/                Entity / Db(JDBC) / ClassDao / NoteDao / MainViewModel
                         + 纯逻辑：WeekSchedule / TodaySchedule / TimeAxis /
                           TodayNotePicker / TimetablePdfParser / backup/
```

**关键原则**：`data/` 里除 Entity 与 DAO 外全部是**纯 Kotlin 逻辑，与平台无关**，
145 个单测覆盖它们。改 UI 时不要碰 `data/`。

## 用户偏好（本项目）

- 用户**明确不要 1:1 复刻手机 UI**，要用**桌面软件的设计语言**（功能逻辑一致即可）。
- **已选定皮肤 B：Windows 11 Fluent**（4px 控件圆角、1px 描边扁平卡片、导航用左侧强调条、
  强调色 `#0067C0` / 深色 `#60CDFF`、正文 13sp、行高 34dp）。
  皮肤 A（Material 3 桌面化）的预览保留在 `design-preview/` 供对比，不再维护。
- 已确认的桌面方向：**左侧边栏 + 主内容区**、便签页 **表格 + 右侧编辑面板**、
  课表页 **全宽时间轴网格 + 右侧课程详情面板**、设置页 **子导航 + 面板**、
  窗口 **1200×800 可缩放（最小 900×600）**。
- 每次 UI 改动要产出 **HTML 验收页**（放 `design-preview/`）+ **实机截图**，让用户亲自验收。
- **不要主动构建安装包**（`packageMsi` 需要 WiX，本机没装）；默认只跑编译与单测。

## UI 分层

```
ui/
├── TodayText.kt        纯文案函数（greetingFor / todaySubtitle / emptyToday* / remainingText）
├── DateTimePickers.kt  M3 日期/时间选择器，但用 Fluent 色板包了一层 MaterialTheme
└── fluent/             桌面 UI 全部在这里（见下）
```

**关键原则**：`ui/fluent/` 里所有控件都是自绘的，不用 Material 3 的 Button/Switch/TextField
（M3 是「胶囊 + 阴影 + 大圆角」，和 Fluent 的「4dp 圆角 + 1px 描边 + 扁平」是两套语言）。
唯一的例外是日期/时间选择器 —— 自己画日历与表盘代价太大，所以复用了 M3 的，
但通过 `FluentMaterialScope` 把配色换成了 Fluent 色板。

## 窗口结构（自绘标题栏）

窗口是 **`undecorated = true`**（无系统标题栏）。因此下面三件事必须自己做，
全在 `ui/fluent/WindowChrome.kt`：

| 能力 | 实现 |
|---|---|
| 标题栏 | `PageTopBar` 兼任：`[标题+副标题(weight 1f，可拖动)] [页面操作] [─ □ ✕]` |
| 拖动 / 双击最大化 | `Modifier.windowDragArea(chrome)`，挂在标题区 |
| 缩放 | `WindowResizeHandles`，四边 + 四角共 8 个 5dp 热区 |
| 最大化 | 手工算 `workAreaOf()`，**不要用 `WindowPlacement.Maximized`** |
| 圆角 / 描边 | `WindowEffects.applyRoundedCorners()` 走 DWM（见坑 14），描边色取当前主题的 `outlineStrong`，由 `ApplyWindowCorners()` 在主题切换时重新下发 |
| 关闭按钮 ✕ | 加粗（2dp 描边）+ 红色：浅色 `#C42B1C`（Win11 原生）/ 深色 `#FF6B6B` |

`WindowChrome` 通过 `LocalWindowChrome` 下发，避免把 `ComposeWindow` 透传到 4 个页面。
`isMaximized` 用 lambda 而不是 Boolean，避免缩放时每帧重建对象。

**动作按钮的落点约定**：页面级动作放 `PageTopBar` 的 `actions` 槽（在窗口控制按钮左侧）；
卡片级动作放该卡片标题行的右端（`start 16 / end 12 / top-bottom 8` 的 Row，按钮 `compact`）。



## 工具脚本

| 脚本 | 用途 |
|---|---|
| `tools/capture_window.py <标题> <输出路径>` | 截取指定标题的窗口。**会走最小化→还原，不能用来截 hover 态** |
| `tools/capture_app.py <标题> <输出路径> [--pad N]` | 按**进程**合并所有窗口一起截（Compose 对话框是独立顶层窗口）。主窗口只取客户区 |
| `tools/shoot_scenarios.py <输出目录> [场景=文件名 ...]` | 批量跑 `dev/UiPreview` 的场景并截图。**直接 `java -cp` 起进程，不走 Gradle** |
| `tools/win_input.py info/click/click-win/move-win/dblclick-win/key` | 查窗口位置、模拟点击、**只移动不点击（验证 hover）**、双击、按键 |
| `tools/seed_demo.py` / `--reset` | 灌演示数据 / 清空数据 |
| `%TEMP%\stumate_probe\probe_hover.py` | 只移动光标不碰 z 序，用来截 hover 态 |
| `%TEMP%\stumate_probe\probe_click_latency.py` | BitBlt 高频采样，量「鼠标按下→画面响应」的毫秒数 |
| `%TEMP%\stumate_probe\diff_shots.py` | 两张截图像素级 diff，输出变化包围盒与色差 |
| `%TEMP%\stumate_probe\grid_geom.py` / `col_profile.py` / `find_glyph.py` | 从截图反解控件几何，**不要靠肉眼估坐标**（截图是缩放显示的，肉眼估误差很大） |
| `%TEMP%\stumate_probe\probe_drag.py` / `probe_resize.py` / `probe_max_restore.py` | 窗口拖动 / 缩放 / 最大化回归探针 |

**性能提示**：`gdi32!GetPixel` 在本机单次约 **3.9ms**，几百个采样点就是 1.8 秒，量延迟完全不够用。
要高频抓屏请用 `BitBlt` 一次性拷进内存位图 + `GetDIBits` 取字节（整块 250×715 约 5ms）。

## 本机截图环境（踩坑结论，反复用得上）

- **输入注入彻底不可用**：`SetCursorPos` / `mouse_event` / `SendInput` / `PostMessage(WM_LBUTTONDOWN)`
  全部被系统静默丢弃（光标根本不动）。→ 想让应用落到某个状态，**只能改代码或加预览入口**，别指望点。
- **提升别的进程窗口 z 序：单用 `SetForegroundWindow` 无效，必须配合 `AttachThreadInput`**：
  ```python
  t_fg = user32.GetWindowThreadProcessId(user32.GetForegroundWindow(), None)
  t_me = kernel32.GetCurrentThreadId()
  user32.AttachThreadInput(t_me, t_fg, True)
  user32.BringWindowToTop(hwnd); user32.SetForegroundWindow(hwnd)
  user32.AttachThreadInput(t_me, t_fg, False)
  ```
  实测成功（`GetForegroundWindow()` 变成目标窗口）。自己起的预览窗口仍建议 `alwaysOnTop = true`。
- **启动应用**：`Start-Process` 起的 java 进程会**静默退出**（stdout/stderr 全空，无窗口）。
  用 Bash 后台任务直接 `java -cp` 反而正常，3 秒出窗口。
  **但 `java -cp` 也会偶发静默退出**（2026-10-01 两次遇到：日志 0 字节、无堆栈、窗口消失）。
  症状完全一样，**别去查代码**：直接重拉一次即可，重拉后正常。
  判断依据是「日志 0 字节 + 进程消失」—— 有任何异常输出才值得查。
- **要截被遮挡的窗口，用 `PrintWindow(hwnd, hdc, PW_RENDERFULLCONTENT /* 2 */)`**，
  它取的是窗口自绘内容，不受遮挡影响。`PW_CLIENTONLY /* 1 */` 会得到**全黑**。
  取像素要 `GetDIBits` + 负高度 BITMAPINFOHEADER（top-down），否则图是上下翻转的。
- **对话框截图尺寸核对**：屏幕是 1:1 无缩放（`width = 420.dp` 的对话框实测 420px 宽），
  所以可以直接用像素坐标核对布局，不用换算。核对方法：逐行算亮度均值找「亮线」（边框/分隔线），
  再对比不同状态的行段是否一致 —— 比肉眼看截图可靠得多。
- **`undecorated = true` 窗口的客户区就是可视区域**，外面还有约 16px 的透明缩放边框。
  截图一律取 `GetClientRect` + `ClientToScreen`，**不要加 pad**，多抓 1px 就把后面的窗口拍进来。
- **Gradle 守护进程是长驻的**：`System.getenv()` 拿到的是守护进程启动时的快照，改 shell 变量无效。
  给预览传参必须走 `-P` → `systemProperty`。
- **从 Bash/Python 调 `cmd /c gradlew.bat` 会被安全策略拦掉**（Python `subprocess` 还会静默失败）。
  → 用 `./gradlew printRuntimeClasspath` 导出 classpath 到 `.preview-classpath`，之后直接 `java -cp` 起进程。
- 带 PIL 的解释器：`C:/Users/Administrator/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`
  （`binaries/python/versions/3.13.12/python.exe` **没有** PIL）。
- **Compose Desktop 表情符号可用**：Skiko 走 Segoe UI Emoji，`👁️` / `🙈` 渲染为**全彩**表情，不是豆腐块。
- **带 PIL 的解释器变了**（2026-10-01 实测）：`binaries/python/versions/3.13.12/python.exe`
  和 `binaries/python/envs/default/` 都**没有** PIL 了；现在可用的是
  **`C:/Users/Administrator/AppData/Local/Microsoft/WindowsApps/python3.exe`（系统 3.11.9，有 PIL）**。
- **用 Bash 后台任务起 GUI 程序时，不要再叠加 `&`**：`run_in_background=true` 本身已经后台化，
  命令里再写 `java … &` 会让外层 shell 立刻退出，进程组被连带杀掉，表现为「日志 0 字节 + 没有窗口」。
  正确写法是 `run_in_background=true` + 命令里**不**加 `&`、用 `exec java …` 挂住前台。
- **任务栏 / Alt-Tab 图标取不到彩色位图**（2026-10-01 实测）：
  Skia 交给 Windows 的是 **1bpp 单色旧式图标**。`WM_GETICON` 能拿到非零 HICON，
  但 `GetObjectW` 报 `bmBitsPixel=1`，随后 `GetDIBits` 返回 **0 行**、`DrawIconEx` 返回 **0**。
  连补齐 `argtypes`（64 位句柄按 int 传会被截断）也无效。
  → **改用 `ImageGrab.grab(bbox=(x, H-64, x+240, H))` 直接截屏取任务栏**，
  反而是更硬的证据：看到的就是系统真实绘制的结果。
- **Compose 1.5.10 的 `ImageVector.Builder.addGroup` 没有尾随 lambda**：它最后一个参数是
  `clipPathData: List<PathNode>`，并**返回**一个「作用域指向该 group」的新 builder。
  写成 `builder.addGroup(...) { addPath(...) }` 会报
  `Type mismatch: inferred type is () -> Unit but List<PathNode> was expected`。
  正确写法：
  ```kotlin
  val g = builder.addGroup(name = "…", scaleX = s, scaleY = s, translationX = tx, translationY = ty)
  g.addPath(pathData = …, stroke = …, strokeLineWidth = …, strokeLineCap = StrokeCap.Round, …)
  ```
  group 变换语义已反编译 `ui-desktop-1.5.10` 的 `GroupComponent.updateMatrix` 确认：
  `translate(tx+pivotX, ty+pivotY) · rotateZ · scale(sx,sy) · translate(-pivotX,-pivotY)`，
  pivot 取 0 即 `p → s·p + t`；且该矩阵被 concat 进画布变换，**group 内 `strokeLineWidth` 也按 s 缩放**。
  用途：把 24×24 的开源图标 path 原样搬进 512 画布，改尺寸只需动 `scaleX/scaleY/translation*` 四个数。

## 交付状态

- 业务层（data + platform）：**完成**，145/145 单测通过。
- UI 层：**已按皮肤 B（Windows 11 Fluent）重做为桌面外壳**，编译通过、145 单测全绿，
  实机截图见 `design-preview/f10`~`f18`（深浅两套主题 × 四个页面）。
- 设计预览：`design-preview/desktop-preview-B-fluent.html`（选定版）、
  `desktop-preview-A-material3.html`（备选，留档）。
- 交互修复（2026-09-30）：拖动抽搐、Win11 圆角描边、关闭按钮加粗变红、
  **课表页 hover 反馈 + 点击延迟**（见坑 15/16/17）。
  验收页 `design-preview/hover-click-preview-v1.0.html`。
- **账号 / 登录（P2）**：客户端**已实现**（`data/sync/` + `SecretStore` + `AccountSection`），
  接口冒烟 8/8 通过真实服务端。验收页 `design-preview/account-login-verification-v1.1.html`（v1.0 保留）。
  - 登录对话框：内容区固定 412dp（登录/注册切换高度不跳）、Google 入口已隐藏、
    GitHub 按钮全宽带官方标识（`ui/fluent/GitHubMark.kt`）、密码显隐用表情。
  - 「已登录」界面**尚无截图**（本机无测试账号，注册需管理员发邀请码）。
  - **⚠️ 待用户操作**：GitHub 授权报 `redirect_uri is not associated with this application`。
    需在 github.com/settings/developers 把回调地址设为
    `https://deeer.online/api/stumate/v1/oauth/github/callback`（client_id `Ov23liahl5KxfqpIYXyd`）。
    客户端与服务端代码都不用改，改完即生效。
  - 服务端 `/sync/*` 未实现；Google OAuth 凭据未配（客户端入口已隐藏）。
  - 设置页默认分组已改为「账号」（原「提醒」），**待用户确认**。

- **品牌图标（2026-10-01，v2.2 定稿）**：侧栏品牌方块从「纯文字 S」换成标识
  —— 圆角方块 + 课表九宫格 + 右上角琥珀高亮格（待提醒的课）+ **Lucide `bell` 铃铛（原版描边）**。**不用字母**。
  - 设计源文件：`design-preview/stumate-mark-v2.2.svg`（深色）/ `stumate-mark-light-v2.2.svg`（浅色）。
    **改图先改 SVG，再同步到 Kotlin**。v2.1 / v2.0 / v1.0 全部保留仅供对比。
  - 实现：`ui/fluent/StuMateMark.kt`，手写 `ImageVector`（512 viewport），
    提供 `stuMateMark()`（跟主题）与 `stuMateMark(tile, ink)`（指定配色，给托盘/任务栏用）。
    参照既有的 `GitHubMark.kt` 模式，不加依赖、不建资源目录。
  - 用到三处：侧栏品牌行（`AppShell.kt`）、系统托盘图标、`Window(icon = …)` 任务栏图标。
  - 几何：外轮廓圆角 `118/512 ≈ 23%`（与旧版 `RoundedCornerShape(6.dp)` / 26dp 同比例）；
    九宫格 9 个 `96×96` / 间距 24 / 墨色 18%；高亮格固定 `#FFB900`（不跟主题 `warning`）。
  - **铃铛 = Lucide `bell`，path 与描边参数逐字照搬**（v2.2 起）：
    主体 `M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326`、
    摆锤 `M10.268 21a2 2 0 0 0 3.464 0`、`fill=none` + `stroke-width=2` + round cap/join。
    **末尾故意不加 `Z`** —— 加了会在底沿多画一条封口线。
    缩放落位：`addGroup(scale 12.6, translate 104.79/104.8)`，24×24 → 512×512，
    视觉外接框 19.478×22 放大到 245 宽（比 v2.1 的 236 略大，补回描边损失的视觉分量）。
  - **渲染约束（v2.2 仍在）**：必须 `Icon(…, tint = Color.Unspecified)`，
    否则 Material3 会把多色矢量刷成单色（见下方「坑 18」）。
  - **铃铛比例的硬约束**：圆顶直径 ÷ 底沿宽 **≥65%**，铃身高宽比 **0.83–0.94**。
    v2.0 曾把圆顶做成底沿的 46%，侧腰斜张 → **读成锥形「圣诞树」**。
    基准来自实测 5 个开源图标集的 path 数据：Lucide 68.7%/0.86（ISC）、Bootstrap 71%/0.86（MIT）、
    Heroicons 76%/0.83（MIT）、Phosphor 100%/1.10（MIT）、Tabler ≈100%/≈1.06（MIT）。
    分「窄腰派」（68–76%）与「直筒派」（≈100%）两派；**直筒派实测读成拱门/墓碑**。
  - **为什么不用字母**：字母对「这是什么应用」零信息量，16–26dp 下识别成本还高于形状；
    铃铛缩到 26dp 仍一眼看出「提醒」。淘汰过的候选：铃铛内嵌课表横条（糊成一条）、
    琥珀圆点（像一颗球）、闹钟（**读成电源插座**）、铃声弧（读成字母 Y）、
    铃铛加顶钮（读成圣诞球）。
  - 验收页：`design-preview/icon-preview-v2.2.html`（含与参考图的并排比对、缩放系数比选、
    多尺寸、深浅实机截图、任务栏实拍、ISC 合规说明）。
    v2.1 / v2.0 / v1.0 的验收页与 SVG 全部保留。
  - **许可**：Lucide = ISC（`bell` 不在其 Feather/MIT 衍生清单内，只有一条义务）。
    ISC 要求声明出现在 all copies，故 **KDoc 内嵌 + 项目根 `THIRD-PARTY-NOTICES.md` 两处**都放了。


