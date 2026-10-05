package com.example.classreminder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.example.classreminder.data.Db
import com.example.classreminder.data.MainViewModel
import com.example.classreminder.data.sync.AccountSession
import com.example.classreminder.data.sync.SyncEngine
import com.example.classreminder.platform.DesktopFileDialogs
import com.example.classreminder.platform.ReminderEngine
import com.example.classreminder.platform.AppIdentity
import com.example.classreminder.platform.ToastBus
import com.example.classreminder.platform.ToastHost
import com.example.classreminder.ui.fluent.AccountAuthDialog
import com.example.classreminder.ui.fluent.AppShell
import com.example.classreminder.ui.fluent.ApplyWindowCorners
import com.example.classreminder.ui.fluent.FlButton
import com.example.classreminder.ui.fluent.FlButtonVariant
import com.example.classreminder.ui.fluent.FlDialog
import com.example.classreminder.ui.fluent.FluentTheme
import com.example.classreminder.ui.fluent.ForgotPasswordDialog
import com.example.classreminder.ui.fluent.LocalSyncEngine
import com.example.classreminder.ui.fluent.LocalWindowChrome
import com.example.classreminder.ui.fluent.MODE_LOGIN
import com.example.classreminder.ui.fluent.MODE_REGISTER
import com.example.classreminder.ui.fluent.OverlayWindow
import com.example.classreminder.ui.fluent.StuMateBrandInk
import com.example.classreminder.ui.fluent.StuMateBrandTile
import com.example.classreminder.ui.fluent.WindowChrome
import com.example.classreminder.ui.fluent.WindowResizeHandles
import com.example.classreminder.ui.fluent.stuMateMark
import com.example.classreminder.ui.fluent.toThemeMode
import com.example.classreminder.ui.fluent.workAreaOf
import kotlinx.coroutines.launch
import java.awt.Dimension

fun main() = application {
    // ⚠️ 必须是第一件事：Shell 在**创建第一个窗口/托盘图标**时就把进程身份定下来了，
    // 之后再设 AUMID 对已登记的窗口无效。不设的话 Windows 11 会直接吞掉托盘气泡
    // （调用不报错，但屏幕上什么都不出现）—— 详见 [AppIdentity]。
    AppIdentity.install()

    val viewModel = remember { MainViewModel() }
    val scope = rememberCoroutineScope()

    // 同步引擎跟着窗口作用域活着：写操作调 [SyncEngine.scheduleSync] 排一次防抖，
    // 引擎内部自己开协程跑网络与落库，不占 Compose 的主线程。
    // onApplied 在远端数据落库后回调，让界面刷新 —— 少了这一步，
    // 用户会看到「已同步」但课表没变。
    val syncEngine = remember { SyncEngine(scope, onApplied = { viewModel.reloadFromDb() }) }

    // 写操作 → 排一次 30 秒防抖同步。
    // 挂在 remember 里而不是每次重组：ViewModel 的这个 setter 只该生效一次，
    // 每次重组都重新赋值会让正在跑的同步拿到新的回调引用。
    LaunchedEffect(viewModel, syncEngine) {
        viewModel.onDataChanged = { syncEngine.scheduleSync() }
    }

    DisposableEffect(Unit) {
        // 桌面端没有通知权限模型，进程一起来就能跑提醒（对应安卓 onResume 里的 tryAutoStartService）
        ReminderEngine.start()
        onDispose {
            ReminderEngine.shutdown()
            viewModel.close()
            Db.close()
        }
    }

    var themeModeOrdinal by remember { mutableStateOf(Prefs.getThemeMode()) }
    val themeMode = themeModeOrdinal.toThemeMode()

    val windowState = rememberWindowState(
        size = DpSize(1200.dp, 800.dp),
        position = WindowPosition(Alignment.Center)
    )
    var windowVisible by remember { mutableStateOf(true) }
    var showCloseDialog by remember { mutableStateOf(false) }
    var dontAskAgain by remember { mutableStateOf(false) }
    var showFirstRun by remember { mutableStateOf(Prefs.isFirstRun()) }
    // 首启引导里点「登录 / 注册」后要弹的账号对话框（null = 没弹）。
    // 存的是 MODE_LOGIN / MODE_REGISTER，决定对话框开在哪个标签页
    var firstRunAuthMode by remember { mutableStateOf<Int?>(null) }
    var firstRunForgot by remember { mutableStateOf(false) }
    // 最大化是手工实现的（无边框窗口不能用 WindowPlacement.Maximized，会盖住任务栏），
    // 所以要自己记住还原时的位置与尺寸
    var maximized by remember { mutableStateOf(false) }
    var restorePosition by remember { mutableStateOf<WindowPosition?>(null) }
    var restoreSize by remember { mutableStateOf<DpSize?>(null) }

    fun quit() = exitApplication()

    fun handleCloseRequest() {
        when (Prefs.getCloseAction()) {
            Prefs.CLOSE_TO_TRAY -> windowVisible = false
            Prefs.CLOSE_QUIT -> quit()
            else -> showCloseDialog = true
        }
    }

    // ── 系统托盘（替代安卓的前台常驻通知） ──
    val trayState = rememberTrayState()
    // 托盘与任务栏图标在主题作用域之外，固定用品牌配色（深色主题的强调色）
    val trayIcon = rememberVectorPainter(remember { stuMateMark(StuMateBrandTile, StuMateBrandInk) })
    val status by ReminderEngine.status.collectAsState()
    val running by ReminderEngine.running.collectAsState()
    val alert by ReminderEngine.alert.collectAsState()

    LaunchedEffect(Unit) {
        // 提醒引擎通过托盘气泡发通知；托盘不可用时静默降级为「只弹置顶卡片」
        ReminderEngine.onNotify = { title, body ->
            if (isTraySupported) {
                trayState.sendNotification(Notification(title, body, Notification.Type.Info))
            }
        }
    }

    LaunchedEffect(Unit) {
        // 有本机凭证就先恢复登录态（断网时也能显示「已登录」），再在后台核验一次。
        // 这是**唯一**一处主动拉起账号会话的地方 —— 启动流程不等待它，界面不会被登录卡住
        AccountSession.restore()
    }

    // 登录状态一变（登录成功 / 退出登录）就重新同步。
    // 用 `signedIn` 而不是 `user`：用户对象每次 refreshMe 都会被替换成新实例，
    // 用它当 key 会在「刷新了昵称」这种无关变更上白白重跑一轮同步。
    //
    // ⚠️ 这里**只能**在「真的登录了」时同步，且**只能**在「真的登出」时清游标。
    // `signedInFlow` 的初始值是 false（`_user` 构造时是 null），而 `restore()`
    // 是异步的 —— 所以本 effect 第一次执行时看到的 false 是「凭证还没读出来」，
    // **不是**「用户退出了」。
    // 早期版本写成 `if (!signedIn) resetForSignOut() else startOnLaunch()`，两个后果：
    //   1. 每次启动都先把游标清零 → 首轮同步必然从 cursor=0 全量重拉；
    //   2. 同一个启动里 `LaunchedEffect(Unit)` 也调了 startOnLaunch()
    //      → 一轮启动跑两遍同步（实测：一次启动产出两个 preinit 备份文件）。
    // 用上一次的值区分「初始 false」与「true → false」的真登出。
    //
    // 另外要分开**两种「已登录」**，它们的同步动作不一样：
    //   · 启动恢复登录态 → `startOnLaunch()`，接着用本机数据，不动它
    //   · 用户主动登录   → `syncAfterLogin()`，把本地强制对齐到首端配置
    //     （先备份被覆盖的那份，路径显示在同步卡上）
    // 区分靠 `loginEpoch` —— 只有 login/register/第三方登录才自增。
    val signedIn by AccountSession.signedInFlow.collectAsState()
    // 只有「用户主动登录」才会自增（见 AccountSession.loginEpoch）。
    // 启动时恢复登录态不动它 —— 否则每次开软件都会被当成刚登录而强制对齐一次。
    val loginEpoch by AccountSession.loginEpoch.collectAsState()
    val prevSignedIn = remember { mutableStateOf<Boolean?>(null) }
    val handledLoginEpoch = remember { mutableStateOf(0) }
    LaunchedEffect(signedIn, loginEpoch) {
        val prev = prevSignedIn.value
        prevSignedIn.value = signedIn
        if (!signedIn) {
            if (prev == true) syncEngine.resetForSignOut()
            return@LaunchedEffect // 启动时凭证还没恢复完，游标不动
        }
        // 这次「已登录」是不是用户主动登录带来的？是就强制对齐首端配置
        val freshLogin = loginEpoch != handledLoginEpoch.value
        handledLoginEpoch.value = loginEpoch
        if (freshLogin) syncEngine.syncAfterLogin() else syncEngine.startOnLaunch()
    }

    if (isTraySupported) {
        Tray(
            icon = trayIcon,
            state = trayState,
            tooltip = "StuMate · ${status.title}",
            menu = {
                Item("打开 StuMate") { windowVisible = true }
                Separator()
                Item(if (running) "提醒服务运行中" else "提醒服务未启动") { }
                Item(status.title) { }
                Separator()
                Item("立即检查一次") { ReminderEngine.checkNow() }
                Separator()
                Item("退出") { quit() }
            },
            onAction = { windowVisible = true }
        )
    }

    if (windowVisible) {
        Window(
            onCloseRequest = { handleCloseRequest() },
            title = "StuMate",
            state = windowState,
            // 任务栏 / Alt-Tab 图标。与托盘共用品牌标识，不用 AWT 默认的咖啡杯
            icon = trayIcon,
            // 隐藏系统标题栏：标题、拖动、最小化/最大化/关闭全部由应用自绘
            // （见 ui/fluent/WindowChrome.kt）
            undecorated = true,
        ) {
            // 桌面窗口有最小可用尺寸：侧栏在 900dp 以下会收起成图标栏，再窄整体体验就崩了
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(900, 600)
            }

            val chrome = remember(window) {
                WindowChrome(
                    window = window,
                    // 拖动时 chrome.moveTo 会同步它，否则最大化/还原会跳回屏幕居中
                    windowState = windowState,
                    isMaximized = { maximized },
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = {
                        if (maximized) {
                            restorePosition?.let { windowState.position = it }
                            restoreSize?.let { windowState.size = it }
                            maximized = false
                        } else {
                            restorePosition = windowState.position
                            restoreSize = windowState.size
                            // ⚠️ `workAreaOf` 返回的**已经是 dp**（AWT 用户空间 =
                            // 物理 / uiScale，Compose 的 density 就是那个 uiScale），
                            // 不要再除 `scale` —— 除一次「最大化」只铺满左上四分之一屏。
                            val area = workAreaOf(window)
                            windowState.position = WindowPosition(area.x.dp, area.y.dp)
                            windowState.size = DpSize(area.width.dp, area.height.dp)
                            maximized = true
                        }
                    },
                    onClose = { handleCloseRequest() }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalWindowChrome provides chrome,
                    // 同步引擎下发到整棵树，设置页的 SyncCard 才能拿到它。
                    // 与 LocalWindowChrome 同理：都是为了避免把参数一路传下去。
                    LocalSyncEngine provides syncEngine
                ) {
                    FluentTheme(themeMode = themeMode) {
                        // 无边框窗口的 Win11 原生圆角 + 跟随主题的 1px 描边
                        ApplyWindowCorners(window)
                        Box(modifier = Modifier.fillMaxSize()) {
                            AppShell(
                                viewModel = viewModel,
                                themeModeOrdinal = themeModeOrdinal,
                                onThemeModeChanged = {
                                    Prefs.setThemeMode(it)
                                    themeModeOrdinal = it
                                },
                                onTestNotification = {
                                    if (isTraySupported) {
                                        trayState.sendNotification(
                                            Notification(
                                                "StuMate 测试通知",
                                                "通知可用，到点会像这样提醒你。",
                                                Notification.Type.Info
                                            )
                                        )
                                    } else {
                                        ToastBus.show("当前系统不支持托盘通知")
                                    }
                                },
                                onOpenDataFolder = {
                                    if (!AppPaths.openInExplorer()) ToastBus.show("打开数据目录失败")
                                },
                                onImportTimetable = {
                                    scope.launch {
                                        val file = runCatching {
                                            DesktopFileDialogs.openFile("选择课表 PDF", listOf("pdf"))
                                        }.getOrNull()
                                        if (file == null) return@launch
                                        viewModel.importTimetable(file) { ToastBus.show(it) }
                                    }
                                }
                            )
                            ToastHost(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp)
                            )
                        }
                    }
                }
                // 无边框窗口失去了系统缩放边框，这 8 个热区把它补回来；
                // 叠在最上层但只在外侧 5dp 生效，不会挡住里面的按钮
                WindowResizeHandles(chrome, windowState)
            }

            if (showCloseDialog) {
                CloseConfirmDialog(
                    dontAskAgain = dontAskAgain,
                    onDontAskAgainChange = { dontAskAgain = it },
                    onMinimize = {
                        if (dontAskAgain) Prefs.setCloseAction(Prefs.CLOSE_TO_TRAY)
                        showCloseDialog = false
                        windowVisible = false
                    },
                    onQuit = {
                        if (dontAskAgain) Prefs.setCloseAction(Prefs.CLOSE_QUIT)
                        quit()
                    },
                    onCancel = { showCloseDialog = false }
                )
            }

            if (showFirstRun) {
                FirstRunDialog(
                    onGuest = {
                        Prefs.setFirstRunDone()
                        showFirstRun = false
                    },
                    onAuth = { mode -> firstRunAuthMode = mode }
                )
            }
            // 首启引导里点「登录 / 注册」拉起的账号对话框。
            // 与设置页那份共用同一个 Composable，只是出口不同：
            // 登录成功要顺手把首启引导收掉，用户点「取消」则留着 —— 他还能选游客。
            firstRunAuthMode?.let { mode ->
                AccountAuthDialog(
                    onDismiss = {
                        firstRunAuthMode = null
                        if (AccountSession.signedIn) {
                            Prefs.setFirstRunDone()
                            showFirstRun = false
                        }
                    },
                    onForgotPassword = {
                        firstRunAuthMode = null
                        firstRunForgot = true
                    },
                    initialMode = mode
                )
            }
            if (firstRunForgot) {
                ForgotPasswordDialog(onDismiss = { firstRunForgot = false })
            }
        }
    }

    // ── 置顶全屏提醒卡片（替代安卓的 LockOverlayActivity） ──
    alert?.let { current ->
        OverlayWindow(
            alert = current,
            themeMode = themeMode,
            onDismiss = { ReminderEngine.dismissAlert() }
        )
    }
}

@Composable
private fun CloseConfirmDialog(
    dontAskAgain: Boolean,
    onDontAskAgainChange: (Boolean) -> Unit,
    onMinimize: () -> Unit,
    onQuit: () -> Unit,
    onCancel: () -> Unit
) {
    val c = FluentTheme.colors
    FlDialog(
        onDismiss = onCancel,
        title = "关闭窗口",
        width = 440.dp,
        content = {
            Column {
                Text(
                    "最小化到托盘后应用继续在后台运行，到点仍会提醒；直接退出则停止提醒。",
                    fontSize = 13.sp,
                    color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlCheckbox(dontAskAgain) { onDontAskAgainChange(!dontAskAgain) }
                    Spacer(Modifier.width(10.dp))
                    Text("下次不再提问", fontSize = 13.sp, color = c.onSurface)
                }
            }
        },
        actions = {
            FlButton("退出", onClick = onQuit, variant = FlButtonVariant.DANGER, compact = true)
            Spacer(Modifier.weight(1f))
            FlButton("取消", onClick = onCancel, variant = FlButtonVariant.GHOST, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton("最小化到托盘", onClick = onMinimize, compact = true)
        }
    )
}

@Composable
private fun FlCheckbox(checked: Boolean, onToggle: () -> Unit) {
    val c = FluentTheme.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(shape)
            .background(if (checked) c.accent else c.surface)
            .border(1.dp, if (checked) c.accent else c.outlineStrong, shape)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(Icons.Default.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(11.dp))
        }
    }
}

/**
 * 首次运行引导。
 *
 * ## 取舍：**推荐登录，但不强制**
 *
 * 主按钮给「登录 / 注册」—— 这份应用的价值有一半在多设备同步上，
 * 首启是唯一一次「用户愿意听你说完」的时机，值得提一句。
 *
 * 但「先以游客身份使用」必须**真实存在且能被一眼看到**：
 * 全部功能本来就离线可用，把游客选项藏进小字注释、或者干脆不给，
 * 等于用界面骗人。所以它做成无边框灰字按钮 —— 在、但不抢眼。
 *
 * 对话框右上角没有 ✕，点外面 / 按 Esc 等价于「游客」，
 * 不会出现「关不掉」的死路。
 *
 * ⚠️ `internal` 而不是 `private`：`dev/UiPreview` 要能直接组合它来做视觉验收，
 * 而 Kotlin 的 `private` 顶层函数是**文件私有**，跨文件调用不了。
 */
@Composable
internal fun FirstRunDialog(onGuest: () -> Unit, onAuth: (Int) -> Unit) {
    val c = FluentTheme.colors
    FlDialog(
        onDismiss = onGuest,
        title = "欢迎使用 StuMate",
        width = 480.dp,
        content = {
            Column {
                Text(
                    "到点会弹出置顶提醒卡片，并在系统托盘发一条通知；" +
                        "关闭窗口时可以选择最小化到托盘，让提醒继续工作。",
                    fontSize = 13.sp,
                    color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "登录后，课表与便签会同步到云端，多台设备自动保持一致。\n" +
                        "不登录也能用 —— 数据只存在本机，随时可以在「设置 → 账号」里登录。",
                    fontSize = 13.sp,
                    color = c.onSurfaceVariant
                )
            }
        },
        actions = {
            FlButton(
                "先以游客身份使用",
                onClick = onGuest,
                variant = FlButtonVariant.TEXT_MUTED,
                compact = true
            )
            Spacer(Modifier.weight(1f))
            FlButton(
                "注册",
                onClick = { onAuth(MODE_REGISTER) },
                variant = FlButtonVariant.GHOST,
                compact = true
            )
            Spacer(Modifier.width(8.dp))
            FlButton("登录", onClick = { onAuth(MODE_LOGIN) }, compact = true)
        }
    )
}
