package com.example.classreminder.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.classreminder.data.MainViewModel
import com.example.classreminder.data.sync.AccountSession
import com.example.classreminder.data.sync.OAuthTicket
import com.example.classreminder.data.sync.SyncPhase
import com.example.classreminder.data.sync.SyncState
import com.example.classreminder.FirstRunDialog
import com.example.classreminder.platform.ReminderEngine
import com.example.classreminder.platform.ToastHost
import com.example.classreminder.ui.fluent.AccountAuthDialog
import com.example.classreminder.ui.fluent.AppPage
import com.example.classreminder.ui.fluent.AppShell
import com.example.classreminder.ui.fluent.DeviceManagerDialog
import com.example.classreminder.ui.fluent.FluentTheme
import com.example.classreminder.ui.fluent.ForgotPasswordDialog
import com.example.classreminder.ui.fluent.LocalSyncPreviewState
import com.example.classreminder.ui.fluent.LocalWindowChrome
import com.example.classreminder.ui.fluent.MODE_REGISTER
import com.example.classreminder.ui.fluent.ModifyPasswordDialog
import com.example.classreminder.ui.fluent.OverlayWindow
import com.example.classreminder.ui.fluent.SetPasswordDialog
import com.example.classreminder.ui.fluent.ThemeMode
import com.example.classreminder.ui.fluent.WindowChrome
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 账号相关 UI 的**独立预览窗口**，专供截图验收。
 *
 * ## 为什么要单独开一个入口
 *
 * 正常的验收方式是「跑起来 → 点进设置 → 点账号 → 点登录」，但验收环境里
 * 鼠标注入常常不可用（无交互桌面 / 断开的远程会话），
 * `SetCursorPos` / `mouse_event` / `PostMessage` 全部会被系统静默丢弃。
 * 与其在输入上耗时间，不如给 UI 开一个「直接落到目标状态」的入口。
 *
 * ## 它渲染的是**真的** AppShell
 *
 * 这里不手搭骨架：`AppShell(initialPage = SETTINGS)` 就是生产用的那个外壳
 * （左侧主导航、自绘标题栏、设置子导航、Toast 宿主），唯一的差别是起始页面
 * 被指定成设置页。所以截图里的界面和用户双击图标后点进设置看到的是同一套。
 *
 * ## 场景（系统属性 `stumate.preview`，或环境变量 `STUMATE_PREVIEW`）
 *
 * | 值 | 内容 |
 * |---|---|
 * | `account`（默认） | 设置页「账号」分组，未登录 |
 * | `auth` | 同上 + 登录对话框 |
 * | `authfail` | 用不存在的账号**真实登录一次**，展示服务端返回的失败提示 |
 * | `register` | 同上 + 注册对话框（含邀请码） |
 * | `registerfail` | 注册 + 假邀请码**真实提交一次**，展示最高状态下的报错 |
 * | `forgot` | 同上 + 重置密码对话框 |
 * | `devices` | 同上 + 登录设备管理对话框 |
 * | `oauth` | 真调 `/oauth/github/start` 拿 ticket，再看登录对话框的等待授权态 |
 * | `signedin` | 需要 `STUMATE_PREVIEW_EMAIL` / `STUMATE_PREVIEW_PASSWORD`，**真的登录一次** |
* | `setpwd` | 同上 + 设置邮箱密码对话框（账号**还没有**密码时） |
 * | `modpwd` | 同上 + 修改密码对话框（账号**已有**密码时） |
 * | `week` | 课表页（表格 / 列表取决于 `Prefs.isWeekGrid()`） |
 * | `notes` | 便签页 |
 * | `sync` | 设置页 + 同步卡，用 `-Psync=<状态>` 选状态（见下） |
 *
 * `sync` 场景的状态（`-Psync=`）：
 * | 值 | 状态 |
 * |---|---|
 * | `offline`（默认） | 未登录：只有入口行，没有状态正文 |
 * | `idle` | 空闲，从未同步 |
 * | `done` | 同步成功，有上次同步时间 |
 * | `busy` | 同步中，按钮禁用 |
 * | `override` | 有 3 条记录被服务端版本覆盖 |
 * | `failed` | 同步失败 |
 * | `skipped` | 未登录已跳过（用已登录态 + 灰色点） |
 * | `preinit` | 首端切换，已自动备份并展示路径 |
 *
 * `stumate.theme=light` 可以切浅色主题（默认深色）。
 *
 * 跑法：`./gradlew uiPreview -Ppreview=auth`，或由 `tools/shoot_scenarios.py` 批量跑。
 */
fun main() = application {
    val scenario = setting("STUMATE_PREVIEW", "stumate.preview").ifBlank { "account" }
    val themeMode = if (setting("STUMATE_PREVIEW_THEME", "stumate.theme") == "light") {
        ThemeMode.LIGHT
    } else {
        ThemeMode.DARK
    }
    val viewModel = remember { MainViewModel() }
    // overlay 场景：主窗口故意做成**小窗并偏到左上**，而不是铺满屏幕。
    // 这样屏幕被分成两块 —— 一块底下是这个绿色替身窗口、一块底下是桌面，
    // 一次截图就能同时验出「透出下面的窗口」和「透出桌面」两件事，
    // 而且能反证「不是整块糊成白色/黑色」。
    val isOverlayScenario = scenario == "overlay"
    val windowState = rememberWindowState(
        size = if (isOverlayScenario) DpSize(1000.dp, 700.dp) else DpSize(1200.dp, 800.dp),
        position = if (isOverlayScenario) WindowPosition(200.dp, 200.dp) else WindowPosition.PlatformDefault,
        placement = WindowPlacement.Floating
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = WINDOW_TITLE,
        undecorated = true,
        // ⚠️ 必须置顶。验收环境里本进程**无法提升别的窗口的 z 序**
        // （SetForegroundWindow / SetWindowPos / BringWindowToTop 全被系统忽略，
        // 连 ShowWindow(SW_MINIMIZE) 都无效），外部工具没法把预览窗口提到最前。
        // 不置顶的话窗口会被别的窗口盖住，截图里看起来就像「窗口是透明的」。
        // overlay 场景下要让位：置顶提醒窗口自己也置顶，两个都置顶时
        // 谁在上面就变成看运气了，截图会随机拍到「提醒被主窗口盖住」。
        alwaysOnTop = scenario != "overlay",
        state = windowState
    ) {
        // 自绘标题栏要用到 window 与 windowState，和 Main.kt 里是同一套接线，
        // 这样截图里才会有「打开数据目录 / 最小化 / 最大化 / 关闭」那几个按钮
        val chrome = remember(window) {
            WindowChrome(
                window = window,
                windowState = windowState,
                isMaximized = { false },
                onMinimize = {},
                onToggleMaximize = {},
                onClose = {}
            )
        }

        FluentTheme(themeMode = themeMode) {
            CompositionLocalProvider(
                LocalWindowChrome provides chrome,
                // 同步卡的预览态。**只在这一个场景注入**，其他场景恒为 null，
                // 走的是「引擎为 null → 只显示入口行」那条路。
                LocalSyncPreviewState provides syncPreviewState(scenario)
            ) {
                // 登录是异步的，对话框必须等 user 真的落到会话里才组合。
                // 直接读 StateFlow.value 不订阅，登录完成时不会触发重组，对话框永远出不来。
                val previewUser by AccountSession.user.collectAsState()

                // 「目标状态已就绪」的信号：截图脚本轮询窗口标题，而不是死等固定秒数。
                // 死等会有假阴性 —— 浅色首帧慢 / 网络慢时登录还没回来就截图，
                // 截出来一张「未登录」，看起来像功能没做，实际只是没等够。
                val needsUser = scenario == "signedin" || scenario == "setpwd" || scenario == "modpwd"
                // firstrun 是同步渲染的（没有异步登录），但仍然打 ready 信号：
                // 截图脚本一律等信号、不死等秒数 —— 死等会截到「还没画完」的废图。
                val readyWithoutUser = scenario == "firstrun"
                LaunchedEffect(previewUser, needsUser, readyWithoutUser) {
                    if (readyWithoutUser || (needsUser && previewUser != null)) {
                        window.title = "$WINDOW_TITLE ready"
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (scenario == "overlay") {
                        // 替身「用户正在看的画面」。
                        //
                        // 为什么不用 AppShell 当底：深色主题下 AppShell 本身就是一片深灰，
                        // 截出来和「透明失败渲染成黑色」长得一模一样 —— 这种图没法验收。
                        // 换成高饱和纯色 + 大字，卡片以外的区域到底透没透，一眼就能判。
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF1B7F4B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "这是提醒卡片「下面」的窗口\n卡片以外的区域应当能看到这一层",
                                color = Color.White,
                                fontSize = 30.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 44.sp
                            )
                        }
                    } else {
                        AppShell(
                            viewModel = viewModel,
                            themeModeOrdinal = if (themeMode == ThemeMode.LIGHT) 1 else 2,
                            onThemeModeChanged = {},
                            onTestNotification = {},
                            onOpenDataFolder = {},
                            onImportTimetable = {},
                            // 生产入口现在固定开「今日」（见 AppShell 注释），所以预览必须能
                            // 指定起始页，否则课表页的截图根本走不到。
                            initialPage = when (scenario) {
                                "week" -> AppPage.WEEK
                                "notes" -> AppPage.NOTES
                                else -> AppPage.SETTINGS
                            }
                        )
                    }
                    ToastHost(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }

                // 真的登一次 —— 这样「已登录」截图是端到端跑出来的，不是摆出来的。
                // setpwd 走的是令牌注入而不是登录：无密码账号压根登不进去，
                // 而「已登录 + 没有密码」正是这个场景要验的状态。
                if (scenario == "signedin" || scenario == "modpwd") {
                    // 给了 token 就从令牌进（无密码账号只能这样），没给才走真登录
                    val token = setting("STUMATE_PREVIEW_TOKEN", "stumate.token")
                    if (token.isNotBlank()) {
                        AccountSession.previewSignInWithToken(
                            accessToken = token,
                            email = setting("STUMATE_PREVIEW_EMAIL", "stumate.email"),
                            nickname = setting("STUMATE_PREVIEW_NICKNAME", "stumate.nickname")
                                .ifBlank { "预览账号" },
                            // modpwd 要的就是「已经有密码」那一态
                            hasPassword = scenario == "modpwd"
                        )
                    } else {
                        LaunchedEffect(Unit) {
                            val email = setting("STUMATE_PREVIEW_EMAIL", "stumate.email")
                            val password = setting("STUMATE_PREVIEW_PASSWORD", "stumate.password")
                            if (email.isNotBlank() && password.isNotBlank()) {
                                runCatching { AccountSession.login(email, password) }
                                    .onSuccess { println("预览：已登录 ${it.email}") }
                                    .onFailure { println("预览：登录失败 ${it.message}") }
                            } else {
                                println("预览：signedin 场景需要 -Pemail / -Ppassword")
                            }
                        }
                    }
                }
                if (scenario == "setpwd") {
                    // 令牌来自 stumate.token —— 服务端 scripts/stumate-sim-oauth.mjs
                    // 造出来的无密码账号。没有这一步就只能摆一个假表单，
                    // 那样截出来的「设置密码」和真实界面不是一回事。
                    val token = setting("STUMATE_PREVIEW_TOKEN", "stumate.token")
                    if (token.isBlank()) {
                        println("预览：setpwd 场景需要 -Ptoken")
                    } else {
                        AccountSession.previewSignInWithToken(
                            accessToken = token,
                            email = setting("STUMATE_PREVIEW_EMAIL", "stumate.email")
                                .ifBlank { "preview-nopass@deeer.online" },
                            nickname = setting("STUMATE_PREVIEW_NICKNAME", "stumate.nickname")
                                .ifBlank { "预览账号" },
                            hasPassword = false
                        )
                    }
                }

                when (scenario) {
                    // 首启引导：推荐登录 / 注册，游客是无边框灰字。
                    // 用 `internal` 的生产 Composable 而不是复制一份 —— 摆出来的必须就是用户看到的那个。
                    "firstrun" -> FirstRunDialog(onGuest = {}, onAuth = {})
                    "auth" -> AccountAuthDialog(onDismiss = {}, onForgotPassword = {})
                    "authfail" -> AccountAuthDialog(
                        onDismiss = {},
                        onForgotPassword = {},
                        initialEmail = setting("STUMATE_PREVIEW_EMAIL", "stumate.email")
                            .ifBlank { "verify-probe@example.com" },
                        initialPassword = setting("STUMATE_PREVIEW_PASSWORD", "stumate.password")
                            .ifBlank { "definitely-not-the-password" },
                        autoSubmit = true
                    )
                    "register" -> AccountAuthDialog(
                        onDismiss = {},
                        onForgotPassword = {},
                        initialMode = MODE_REGISTER
                    )
                    // 最高的一种状态：注册 + 报错。用假邀请码真打一次服务端，
                    // 拿回 INVALID_INVITE，用来验证错误条不会被挤出可视区
                    "registerfail" -> AccountAuthDialog(
                        onDismiss = {},
                        onForgotPassword = {},
                        initialMode = MODE_REGISTER,
                        initialEmail = setting("STUMATE_PREVIEW_EMAIL", "stumate.email")
                            .ifBlank { "verify-probe@example.com" },
                        initialPassword = setting("STUMATE_PREVIEW_PASSWORD", "stumate.password")
                            .ifBlank { "definitely-not-the-password" },
                        initialInvite = "AAAA-BBBB-CCCC",
                        autoSubmit = true
                    )
                    "forgot" -> ForgotPasswordDialog(onDismiss = {})
                    "devices" -> DeviceManagerDialog(onDismiss = {})
                    "oauth" -> OAuthTicketPreview()
                    // 预览的登录是异步的，等 user 真的落到会话里再组合对话框，
                    // 否则 initialEmail 会拿到空串，截出来的是个空表单。
                    "setpwd" -> previewUser?.let {
                        SetPasswordDialog(
                            initialEmail = it.email,
                            onDismiss = {},
                            onForgotPassword = {}
                        )
                    }
                    "modpwd" -> previewUser?.let {
                        ModifyPasswordDialog(onDismiss = {})
                    }
                }
            }
        }
    }

    // ── 置顶提醒卡片（overlay 场景）────────────────────────────────
    //
    // 直接调**生产**的 `OverlayWindow`，而不是自己再搭一个 `Window(...)`：
    // 窗口参数、色键透明、抢焦点全走真实那条路，验的才是用户会遇到的东西。
    // 自己搭一个只能验出「卡片长什么样」，验不出「卡片以外到底透没透」——
    // 而那正是这次要修的问题。
    if (scenario == "overlay") {
        OverlayWindow(
            alert = ReminderEngine.Alert(
                classId = 1,
                title = "高等数学（A）",
                room = "教三 402",
                // 固定时刻，不取 now()：截图要可复现
                startMillis = LocalDateTime.of(2026, 10, 2, 10, 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                endMillis = LocalDateTime.of(2026, 10, 2, 11, 40)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                ongoing = true
            ),
            themeMode = themeMode,
            onDismiss = {}
        )
    }
}

private const val WINDOW_TITLE = "StuMatePreview"

/**
 * 把 `-Psync=<状态>` 翻译成一个 [SyncState]。
 *
 * 只有 `scenario == "sync"` 时才返回非 null —— 其他场景不能被污染，
 * 否则「未登录」截图里会莫名其妙多出同步状态。
 *
 * 时间戳用「今天 21:47」这种固定时刻而不是 `now()`：截图要可复现，
 * 每次跑都显示当前时间的话，两批图对不上就分不清是界面变了还是时间变了。
 */
private fun syncPreviewState(scenario: String): SyncState? {
    if (scenario != "sync") return null
    val base = LocalDateTime.of(2026, 10, 1, 21, 47)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    val picked = when (setting("STUMATE_PREVIEW_SYNC", "stumate.sync").ifBlank { "offline" }) {
        // null = 未登录，卡片只渲染入口行（连状态分隔线都没有）
        "offline" -> null
        "idle" -> SyncState(message = "还没同步过")
        "done" -> SyncState(
            phase = SyncPhase.IDLE,
            lastSyncedAt = base,
            message = "已同步，课程和便签都是最新的"
        )
        "busy" -> SyncState(phase = SyncPhase.SYNCING, message = "正在同步…")
        "override" -> SyncState(
            phase = SyncPhase.IDLE,
            lastSyncedAt = base,
            message = "已同步，课程和便签都是最新的",
            overriddenCount = 3
        )
        "failed" -> SyncState(
            phase = SyncPhase.FAILED,
            lastSyncedAt = base - 32 * 60_000L,
            message = "同步失败：连不上服务器"
        )
        "skipped" -> SyncState(phase = SyncPhase.SKIPPED, message = "未登录，已跳过同步")
        "preinit" -> SyncState(
            phase = SyncPhase.IDLE,
            lastSyncedAt = base + 5 * 60_000L,
            message = "已同步，课程和便签都是最新的",
            backupPath = System.getProperty("user.home") +
                "\\AppData\\Roaming\\StuMate\\StuMate-preinit-backup-20261001-215204.json"
        )
        else -> {
            println("预览：未知的 -Psync= 值，用 offline（不注入状态）")
            null
        }
    }
    return picked
}

/**
 * 读一个设置项：优先系统属性（`-P` 转发过来的），退回环境变量。
 *
 * 之所以不能只用环境变量：Gradle 守护进程是长驻的，`System.getenv()` 拿到的是
 * 守护进程启动时的快照，改 shell 里的变量对已启动的守护进程毫无影响。
 */
private fun setting(envName: String, propName: String): String =
    System.getProperty(propName).orEmpty().ifBlank { System.getenv(envName).orEmpty() }

/**
 * 「等待授权」场景：先真调一次 `POST /oauth/github/start` 把 ticket 拿回来，
 * 再把它交给**真的**登录对话框 —— 于是对话框里的轮询、倒计时、复制链接
 * 全是真的在跑，而不是一个摆出来的静态面板。
 *
 * 这里刻意不走对话框自己的「使用 GitHub 登录」按钮，因为那条路径会顺手
 * 把系统浏览器打开；截图时不想在用户机器上多弹一个标签页。
 */
@Composable
private fun OAuthTicketPreview() {
    var ticket by remember { mutableStateOf<OAuthTicket?>(null) }
    LaunchedEffect(Unit) {
        runCatching { AccountSession.startOAuth("github", "login", null) }
            .onSuccess { ticket = it }
            .onFailure { println("预览：拿授权地址失败 ${it.message}") }
    }
    // ticket 就绪后才组合对话框，好让 remember 拿到正确的初值
    ticket?.let {
        AccountAuthDialog(onDismiss = {}, onForgotPassword = {}, initialTicket = it)
    }
}
