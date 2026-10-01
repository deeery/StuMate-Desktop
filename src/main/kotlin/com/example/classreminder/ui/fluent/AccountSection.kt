package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.data.sync.AccountSession
import com.example.classreminder.data.sync.ApiException
import com.example.classreminder.data.sync.AuthDevice
import com.example.classreminder.data.sync.OAuthPollResult
import com.example.classreminder.data.sync.OAuthTicket
import com.example.classreminder.platform.Browser
import com.example.classreminder.platform.ToastBus
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 设置页「账号」分组。
 *
 * 设计文档 §9 定的基调是「**可选登录，本地优先**」：
 *  - 启动不拦截，未登录也能用全部本地功能
 *  - 登录入口只在设置页里，不弹窗骚扰
 *  - 任何网络 / 登录问题都**不能**导致本地数据不可用
 *
 * 所以这一屏的默认形态是「一个安静的状态卡片」，而不是登录墙。
 */
@Composable
fun AccountSection() {
    val user by AccountSession.user.collectAsState()
    val bindings by AccountSession.bindings.collectAsState()

    var showAuth by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showNickname by remember { mutableStateOf(false) }
    var showForgot by remember { mutableStateOf(false) }
    var showSetPassword by remember { mutableStateOf(false) }
    var showModifyPassword by remember { mutableStateOf(false) }

    // 已登录时进这一屏就顺手核一次，把昵称 / 绑定关系刷新到最新；
    // 失败（离线）静默忽略 —— 本地快照照样能显示
    LaunchedEffect(user != null) {
        if (user != null) runCatching { AccountSession.refreshMe() }
    }

    SectionBlock(
        title = "账号",
        description = "登录是可选的。不登录也能正常使用全部本地功能；" +
            "登录后可以在多台设备之间同步课表与便签。"
    ) {
        val current = user
        if (current == null) {
            SignedOutCard(onSignIn = { showAuth = true })
        } else {
            SignedInHeader(
                title = current.nickname.ifBlank { current.email },
                subtitle = current.email
            )
            Spacer(Modifier.height(12.dp))
            FlCard(Modifier.fillMaxWidth()) {
                Column {
                    FlSettingRow(
                        label = "昵称",
                        detail = if (current.nickname.isBlank()) "还没设置，默认显示邮箱" else current.nickname
                    ) {
                        FlButton("修改", onClick = { showNickname = true }, variant = FlButtonVariant.GHOST, compact = true)
                    }
                    FlDivider()
                    FlSettingRow(
                        label = "邮箱密码",
                        detail = if (current.hasPassword) {
                            "已设置。可以用邮箱和密码登录这个账号"
                        } else {
                            "还没设置。设置后可以多一种登录方式，GitHub 登录不受影响"
                        }
                    ) {
                        // 按钮文案跟着 has_password 走：没密码时是「加一种登录方式」，
                        // 有了密码之后服务端 409 会把「设置」这条路永久关掉，只能走「修改」。
                        // 两个对话框分开也是同一个理由 —— 见文件末尾的注释。
                        FlButton(
                            if (current.hasPassword) "修改" else "设置",
                            onClick = {
                                if (current.hasPassword) showModifyPassword = true else showSetPassword = true
                            },
                            variant = FlButtonVariant.GHOST,
                            compact = true
                        )
                    }
                    FlDivider()
                    FlSettingRow(
                        label = "第三方账号",
                        detail = if (bindings.isEmpty()) {
                            "还没绑定。绑定后可以直接用 GitHub 一键登录"
                        } else {
                            bindings.joinToString("、") { bindingLabel(it.provider) + " " + it.providerEmail }
                        }
                    ) {
                        // 只放 GitHub：服务端还没配 Google 的 OAuth 凭据，
                        // 点了只会拿到 PROVIDER_NOT_CONFIGURED，摆一个必然失败的按钮是骗人。
                        // 等凭据配好，这里加回一个 ProviderBindButton("google", "Google", …) 即可
                        // —— 接口层与 startOAuth 的分支都还在，不用改别处。
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderBindButton("github", "GitHub", bindings.any { it.provider == "github" })
                        }
                    }
                    FlDivider()
                    FlSettingRow(
                        label = "设备管理",
                        detail = "同一账号最多 5 台设备，可以随时把别的设备踢下线"
                    ) {
                        FlButton("管理", onClick = { showDevices = true }, variant = FlButtonVariant.GHOST, compact = true)
                    }
                    FlDivider()
                    FlSettingRow(
                        label = "退出登录",
                        detail = "只清除本机的登录凭证，本机数据不会被删除"
                    ) {
                        SignOutButton()
                    }
                }
            }

        }
    }

    // 同步卡放在 `SectionBlock("账号")` 的 content lambda **之外**。
    //
    // 踩过的坑：原来它接在 lambda 尾部、位于 `if (current == null) … else { … }`
    // 之后，编译能过、探针确认 lambda 前半段在跑，但它**永远不执行** ——
    // 界面就是没有这张卡，且没有任何报错。把它挪到 lambda 外面立刻正常。
    // 至于编译器具体把它编进了哪个 group，Kotlin 没有输出可查，
    // 只记住结论：**lambda 尾部（尤其在 if/else 之后）不要追加 Composable**。
    Spacer(Modifier.height(22.dp))
    SyncCard(
        engine = LocalSyncEngine.current,
        onSignedOut = { showAuth = true }
    )

    if (showAuth) AccountAuthDialog(
        onDismiss = { showAuth = false },
        onForgotPassword = {
            showAuth = false
            showForgot = true
        }
    )
    if (showDevices) DeviceManagerDialog(onDismiss = { showDevices = false })
    if (showNickname) NicknameDialog(
        initial = user?.nickname.orEmpty(),
        onDismiss = { showNickname = false }
    )
    if (showForgot) ForgotPasswordDialog(onDismiss = { showForgot = false })
    if (showSetPassword) SetPasswordDialog(
        initialEmail = user?.email.orEmpty(),
        onDismiss = { showSetPassword = false },
        onForgotPassword = {
            showSetPassword = false
            showForgot = true
        }
    )
    if (showModifyPassword) ModifyPasswordDialog(onDismiss = { showModifyPassword = false })
}

// ── 未登录 ──────────────────────────────────────────────────────

@Composable
private fun SignedOutCard(onSignIn: () -> Unit) {
    val c = FluentTheme.colors
    FlCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(initial = "?", accent = false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("未登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "所有数据只存在本机。登录后才会在多台设备之间同步。",
                        fontSize = 12.5.sp,
                        color = c.onSurfaceVariant
                    )
                }
                FlChip("离线可用", color = c.success)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlButton("登录 / 注册", onClick = onSignIn, compact = true)
                Spacer(Modifier.width(10.dp))
                Text("登录后自动同步课程与便签", fontSize = 11.5.sp, color = c.onSurfaceFaint)
            }
        }
    }
}

@Composable
private fun SignedInHeader(title: String, subtitle: String) {
    val c = FluentTheme.colors
    FlCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(initial = title.take(1).uppercase(Locale.getDefault()), accent = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 12.5.sp, color = c.onSurfaceVariant)
                }
                FlChip("已登录", color = c.accent, filled = true)
            }
        }
    }
}

@Composable
private fun Avatar(initial: String, accent: Boolean) {
    val c = FluentTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (accent) c.accentTint else c.surface3)
            .border(1.dp, if (accent) c.accent.copy(alpha = 0.35f) else c.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) c.accent else c.onSurfaceFaint
        )
    }
}

@Composable
private fun SignOutButton() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    FlButton(
        "退出登录",
        onClick = {
            scope.launch {
                busy = true
                AccountSession.logout()
                busy = false
                ToastBus.show("已退出登录，本地数据未受影响")
            }
        },
        variant = FlButtonVariant.DANGER,
        enabled = !busy,
        compact = true
    )
}

// ── 第三方绑定 ──────────────────────────────────────────────────

@Composable
private fun ProviderBindButton(provider: String, label: String, bound: Boolean) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ticket by remember { mutableStateOf<OAuthTicket?>(null) }

    // 绑定也要走「开浏览器 → 轮询」，逻辑和登录完全一样，只是结果是 binding
    LaunchedEffect(ticket) {
        val t = ticket ?: return@LaunchedEffect
        when (val result = AccountSession.awaitOAuth(t)) {
            is OAuthPollResult.Bound -> {
                ToastBus.show("已绑定 $label：${result.binding.providerEmail}")
                ticket = null
            }
            is OAuthPollResult.SignedIn -> ticket = null
            is OAuthPollResult.Failed -> {
                ToastBus.show(friendlyOAuthError(result.code, result.message))
                ticket = null
            }
            OAuthPollResult.Pending -> Unit
        }
    }

    if (bound) {
        FlButton(
            "解绑",
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        AccountSession.unbind(provider)
                        ToastBus.show("已解绑 $label")
                    } catch (e: ApiException) {
                        ToastBus.show(e.message)
                    } finally {
                        busy = false
                    }
                }
            },
            variant = FlButtonVariant.GHOST,
            enabled = !busy,
            compact = true
        )
    } else {
        FlButton(
            if (ticket != null) "等待授权…" else "绑定 $label",
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        val t = AccountSession.startBind(provider)
                        if (!Browser.open(t.authorizeUrl)) {
                            ToastBus.show("打不开浏览器，请在浏览器里手动访问 deeer.online")
                        }
                        ticket = t
                    } catch (e: ApiException) {
                        ToastBus.show(friendlyOAuthError(e.code, e.message))
                    } finally {
                        busy = false
                    }
                }
            },
            variant = FlButtonVariant.GHOST,
            enabled = !busy,
            compact = true
        )
    }
}

// ── 登录 / 注册 ─────────────────────────────────────────────────

internal const val MODE_LOGIN = 0
internal const val MODE_REGISTER = 1

/**
 * 登录 / 注册对话框**内容区**的固定高度。
 *
 * ## 为什么要固定
 *
 * 「注册」比「登录」多一整块「邀请码」。不固定高度的话，切标签页时对话框会猛地长高，
 * 密码框、分隔线、GitHub 按钮、底部的「登录 / 注册并登录」全部跟着往下跳 ——
 * 鼠标指针底下的那个按钮会自己跑掉，很容易误点。
 *
 * 固定之后：表单区**顶部对齐**、页脚贴底，两个标签页里
 * **共有元素的坐标完全一致**，多出来的高度由「邀请码」那一块吃掉。
 *
 * ## 取值
 *
 * 412dp 是按四种状态里**最高的那一种**（注册 + 错误条）算出来的：
 * 错误条放在滚动区之外、高度由表单区（`weight(1f)`）让出来，
 * 所以「注册 + 报错」时表单区刚好够用、不出现滚动条，对话框总高也纹丝不动。
 * 宁可「登录」态底部多一块留白，也不要任何一态被压出滚动条 ——
 * 前者只是松，后者是坏。
 */
private val AUTH_CONTENT_HEIGHT = 412.dp

/**
 * @param initialMode 初始标签页，见 [MODE_LOGIN] / [MODE_REGISTER]。
 *        生产调用点永远用默认值；只有 `dev/UiPreview` 截图工具会指定它。
 * @param initialEmail / [initialPassword] / [initialInvite] 预填的凭据，同样只给截图工具用。
 * @param autoSubmit 进来就自动提交一次。走的是本文件里**真实的** [submit]，
 *        所以拿到的报错文案是服务端真回来的，不是摆出来的假状态。
 * @param initialTicket 直接落进「等待授权」态。只给截图工具用 —— 它先真调一次
 *        `/oauth/{provider}/start` 拿到 ticket，再交给对话框，于是轮询也是真的。
 */
@Composable
internal fun AccountAuthDialog(
    onDismiss: () -> Unit,
    onForgotPassword: () -> Unit,
    initialMode: Int = MODE_LOGIN,
    initialEmail: String = "",
    initialPassword: String = "",
    initialInvite: String = "",
    autoSubmit: Boolean = false,
    initialTicket: OAuthTicket? = null
) {
    val c = FluentTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var mode by remember { mutableStateOf(initialMode) }
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf(initialPassword) }
    var invite by remember { mutableStateOf(initialInvite) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ticket by remember { mutableStateOf(initialTicket) }
    var waitedSeconds by remember { mutableStateOf(0) }

    // 服务端可达性：进来就探一次，帮用户把「密码错」和「连不上」区分开
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var serverChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        serverVersion = runCatching { AccountSession.health() }.getOrNull()
        serverChecked = true
    }

    // OAuth 轮询：ticket 变化就开跑；对话框一关，这个协程随之取消
    LaunchedEffect(ticket) {
        val t = ticket ?: return@LaunchedEffect
        waitedSeconds = 0
        val result = AccountSession.awaitOAuth(t) { round -> waitedSeconds = round * 2 }
        when (result) {
            is OAuthPollResult.SignedIn -> {
                ToastBus.show("已登录：${result.user.email}")
                onDismiss()
            }
            is OAuthPollResult.Bound -> {
                ToastBus.show("绑定成功")
                onDismiss()
            }
            is OAuthPollResult.Failed -> {
                error = friendlyOAuthError(result.code, result.message)
                ticket = null
            }
            OAuthPollResult.Pending -> Unit
        }
    }

    val canSubmit = email.isNotBlank() && password.length >= 8 &&
        (mode == MODE_LOGIN || invite.isNotBlank())

    fun submit() {
        if (busy || !canSubmit) return
        error = null
        scope.launch {
            busy = true
            try {
                if (mode == MODE_LOGIN) {
                    val user = AccountSession.login(email, password)
                    ToastBus.show("已登录：${user.email}")
                } else {
                    val user = AccountSession.register(email, password, invite)
                    ToastBus.show("注册成功，已登录：${user.email}")
                }
                onDismiss()
            } catch (e: ApiException) {
                error = friendlyAuthError(e.code, e.message)
            } finally {
                busy = false
            }
        }
    }

    // 截图工具专用：带着预填凭据自动提交一次。
    // 注意这里**不是**伪造一个错误字符串 —— 它真的会打一次服务端，
    // 所以截图里那句「邮箱或密码不正确」是服务端返回 BAD_CREDENTIALS 之后映射出来的。
    if (autoSubmit) {
        LaunchedEffect(Unit) { submit() }
    }

    fun startOAuth(provider: String) {
        if (busy) return
        if (mode == MODE_REGISTER && invite.isBlank()) {
            error = "注册需要用邀请码，请先填上"
            return
        }
        error = null
        scope.launch {
            busy = true
            try {
                val t = AccountSession.startOAuth(
                    provider = provider,
                    mode = if (mode == MODE_REGISTER) "register" else "login",
                    inviteCode = invite.takeIf { mode == MODE_REGISTER }
                )
                if (!Browser.open(t.authorizeUrl)) {
                    error = "没能自动打开浏览器。可以复制下面的链接手动打开。"
                }
                ticket = t
            } catch (e: ApiException) {
                error = friendlyOAuthError(e.code, e.message)
            } finally {
                busy = false
            }
        }
    }

    FlDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = if (mode == MODE_LOGIN) "登录 StuMate" else "注册 StuMate",
        width = 420.dp,
        // 点对话框外面 / 按 Esc 都不关。这里躺着用户刚敲的邮箱、密码、邀请码，
        // 误触一下就全清空了 —— 要关只能点「取消」。
        dismissible = false,
        content = {
            // 固定高度 + 表单区顶部对齐 + 页脚贴底：
            // 切「登录 / 注册」时共有元素一个像素都不动，只有「邀请码」那块出现或消失。
            Column(Modifier.height(AUTH_CONTENT_HEIGHT)) {
                val waiting = ticket
                if (waiting != null) {
                    // 等待面板比表单矮不少，顶部对齐会在下方留一大块空。
                    // 它是独立场景（不是切标签页），居中比对齐好看，也不会影响别处
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        OAuthWaitingPanel(
                            ticket = waiting,
                            waitedSeconds = waitedSeconds,
                            onCopyLink = {
                                clipboard.setText(AnnotatedString(waiting.authorizeUrl))
                                ToastBus.show("授权链接已复制")
                            },
                            onReopen = {
                                if (!Browser.open(waiting.authorizeUrl)) {
                                    ToastBus.show("还是打不开，请手动复制链接")
                                }
                            },
                            onCancel = { ticket = null }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        FlSegmented(
                            options = listOf("登录", "注册"),
                            selectedIndex = mode,
                            onSelect = { mode = it; error = null }
                        )
                        Spacer(Modifier.height(16.dp))

                        FieldLabel("邮箱")
                        FlTextField(
                            value = email,
                            onValueChange = { email = it.trim(); error = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "you@example.com"
                        )
                        Spacer(Modifier.height(12.dp))

                        FieldLabel("密码")
                        FlTextField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = if (mode == MODE_LOGIN) "至少 8 位" else "至少 8 位，别太简单",
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            isError = password.isNotEmpty() && password.length < 8,
                            onSubmit = { submit() },
                            trailing = {
                                // 用表情符号代替「显示 / 隐藏」两个字：
                                // 文字按钮在 420dp 宽的对话框里太抢眼，眼睛图标是通用语义
                                Box(
                                    Modifier
                                        .padding(start = 8.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .clickable { showPassword = !showPassword }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (showPassword) "🙈" else "👁️",
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        )
                        if (password.isNotEmpty() && password.length < 8) {
                            Spacer(Modifier.height(4.dp))
                            Text("密码最少 8 位", fontSize = 11.5.sp, color = c.error)
                        }

                        if (mode == MODE_REGISTER) {
                            Spacer(Modifier.height(12.dp))
                            FieldLabel("邀请码")
                            FlTextField(
                                value = invite,
                                onValueChange = { invite = it.trim(); error = null },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "XXXX-XXXX-XXXX",
                                onSubmit = { submit() }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "StuMate 采用邀请制，请向管理员索取邀请码。",
                                fontSize = 11.5.sp,
                                color = c.onSurfaceFaint
                            )
                        } else {
                            // 「登录」态把这块高度留出来（见 AUTH_CONTENT_HEIGHT 的注释），
                            // 但不当成空白扔着 —— 顺手把「账号从哪来」说清楚
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "还没有账号？切到上方「注册」—— StuMate 采用邀请制，" +
                                    "需要向管理员索取邀请码。",
                                fontSize = 11.5.sp,
                                color = c.onSurfaceFaint
                            )
                        }
                    }

                    // 错误条放在**滚动区之外**。
                    // 放进滚动区的话，「注册」态本来就被「邀请码」占满了高度，
                    // 再加一条错误就超出可滚动区，报错会被裁在底下看不见 ——
                    // 而报错恰恰是用户此刻唯一需要看到的东西。
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        ErrorBanner(it)
                    }

                    // ── 页脚：贴底，两个标签页里位置完全一致 ──
                    FlDivider()
                    Spacer(Modifier.height(14.dp))

                    GitHubAuthButton(
                        text = "使用 GitHub ${if (mode == MODE_LOGIN) "登录" else "注册"}",
                        enabled = !busy,
                        onClick = { startOAuth("github") }
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        FlButton(
                            "忘记密码",
                            onClick = { if (!busy) onForgotPassword() },
                            variant = FlButtonVariant.TEXT,
                            enabled = !busy,
                            compact = true
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    ServerStatusLine(serverChecked = serverChecked, version = serverVersion)
                }
            }
        },
        actions = {
            val waiting = ticket != null
            FlButton("取消", onClick = { if (!busy) onDismiss() }, variant = FlButtonVariant.GHOST, compact = true)
            if (!waiting) {
                Spacer(Modifier.width(8.dp))
                FlButton(
                    if (busy) "请稍候…" else if (mode == MODE_LOGIN) "登录" else "注册并登录",
                    onClick = { submit() },
                    enabled = canSubmit && !busy,
                    compact = true
                )
            }
        }
    )
}

/**
 * 全宽的 GitHub 登录按钮：左图标 + 居中文字。
 *
 * 为什么不用 [FlButton]：`FlButton` 的 `icon` 槽只接受 `ImageVector` 且尺寸写死
 * （14/15dp），而且它是「内容撑开」的，宽度只有文案那么宽。这里要的是
 * 「占满整行 + 40dp 高 + 图标 18dp + 图标与文字一起居中」，属于登录页的一次性样式。
 *
 * 图标来自 [GitHubMark]（手写矢量，见该文件的注释）。
 */
@Composable
private fun GitHubAuthButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(d.radiusControl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(if (hovered && enabled) c.hover else c.surface)
            .border(1.dp, c.outlineStrong, shape)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = GitHubMark,
            contentDescription = null,
            tint = c.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = c.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    val c = FluentTheme.colors
    Text(text, fontSize = 12.sp, color = c.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ErrorBanner(text: String) {
    val c = FluentTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
            .background(c.error.copy(alpha = if (c.isDark) 0.18f else 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 12.sp, color = c.error)
    }
}

@Composable
private fun ServerStatusLine(serverChecked: Boolean, version: String?) {
    val c = FluentTheme.colors
    val (dot, label) = when {
        !serverChecked -> c.onSurfaceFaint to "正在检查服务端…"
        version != null -> c.success to "服务端可达（${version.ifBlank { "deeer.online" }}）"
        else -> c.error to "连不上服务端，登录 / 注册暂时不可用"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 11.5.sp, color = c.onSurfaceFaint)
    }
}

// ── OAuth 等待面板 ──────────────────────────────────────────────

@Composable
internal fun OAuthWaitingPanel(
    ticket: OAuthTicket,
    waitedSeconds: Int,
    onCopyLink: () -> Unit,
    onReopen: () -> Unit,
    onCancel: () -> Unit
) {
    val c = FluentTheme.colors
    Column {
        Text(
            "请在浏览器里完成 ${bindingLabel(ticket.provider)} 授权",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = c.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "授权完成后这个窗口会自动关闭，不需要手动回来点确认。" +
                "授权链接 10 分钟内有效。",
            fontSize = 12.5.sp,
            color = c.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
                .background(c.surface3)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                ticket.authorizeUrl,
                fontSize = 11.sp,
                color = c.onSurfaceVariant,
                maxLines = 3
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                Spacer(Modifier.width(7.dp))
                Text("等待授权中… 已等待 ${waitedSeconds} 秒", fontSize = 12.sp, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            FlButton("复制链接", onClick = onCopyLink, variant = FlButtonVariant.TEXT, compact = true)
            Spacer(Modifier.width(4.dp))
            FlButton("重新打开", onClick = onReopen, variant = FlButtonVariant.GHOST, compact = true)
            Spacer(Modifier.width(4.dp))
            FlButton("返回", onClick = onCancel, variant = FlButtonVariant.TEXT, compact = true)
        }
    }
}

// ── 设备管理 ────────────────────────────────────────────────────

@Composable
internal fun DeviceManagerDialog(onDismiss: () -> Unit) {
    val c = FluentTheme.colors
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<AuthDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<Int?>(null) }

    suspend fun reload() {
        try {
            devices = AccountSession.devices()
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }

    LaunchedEffect(Unit) { reload() }

    FlDialog(
        onDismiss = onDismiss,
        title = "登录设备",
        width = 460.dp,
        content = {
            Column {
                Text(
                    "同一个账号最多同时登录 5 台设备。把不认识的设备踢下线会立刻吊销它的令牌。",
                    fontSize = 12.5.sp,
                    color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                val list = devices
                when {
                    error != null -> ErrorBanner(error!!)
                    list == null -> Text("正在加载…", fontSize = 12.5.sp, color = c.onSurfaceFaint)
                    list.isEmpty() -> Text("没有设备记录", fontSize = 12.5.sp, color = c.onSurfaceFaint)
                    else -> Column {
                        list.forEach { device ->
                            DeviceRow(
                                device = device,
                                busy = busyId == device.id,
                                onRevoke = {
                                    scope.launch {
                                        busyId = device.id
                                        try {
                                            AccountSession.revokeDevice(device.id)
                                            ToastBus.show("已踢下线：${device.name}")
                                            reload()
                                        } catch (e: ApiException) {
                                            ToastBus.show(e.message)
                                        } finally {
                                            busyId = null
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        actions = {
            FlButton("关闭", onClick = onDismiss, compact = true)
        }
    )
}

@Composable
private fun DeviceRow(device: AuthDevice, busy: Boolean, onRevoke: () -> Unit) {
    val c = FluentTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, fontSize = 13.sp, color = c.onSurface)
                if (device.current) {
                    Spacer(Modifier.width(6.dp))
                    FlChip("本机", color = c.accent, filled = true)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${platformLabel(device.platform)} · 最近活跃 ${formatIso(device.lastSeenAt)}",
                fontSize = 11.5.sp,
                color = c.onSurfaceFaint
            )
        }
        if (device.current) {
            Text("当前设备", fontSize = 11.5.sp, color = c.onSurfaceFaint)
        } else {
            FlButton(
                if (busy) "处理中…" else "踢下线",
                onClick = onRevoke,
                variant = FlButtonVariant.DANGER,
                enabled = !busy,
                compact = true
            )
        }
    }
}

// ── 改昵称 ──────────────────────────────────────────────────────

@Composable
private fun NicknameDialog(initial: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                AccountSession.updateNickname(value)
                ToastBus.show("昵称已更新")
                onDismiss()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    FlDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = "修改昵称",
        width = 380.dp,
        content = {
            Column {
                FlTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "留空则显示邮箱",
                    onSubmit = { save() }
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    ErrorBanner(it)
                }
            }
        },
        actions = {
            FlButton("取消", onClick = onDismiss, variant = FlButtonVariant.GHOST, enabled = !busy, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton(if (busy) "保存中…" else "保存", onClick = { save() }, enabled = !busy, compact = true)
        }
    )
}

// ── 忘记密码 ────────────────────────────────────────────────────

@Composable
internal fun ForgotPasswordDialog(onDismiss: () -> Unit) {
    val c = FluentTheme.colors
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        error = null
        scope.launch {
            busy = true
            try {
                AccountSession.resetPasswordWithCode(email, code, newPassword)
                // 服务端恒定返回 204（不告诉你邮箱存不存在），所以这里只能照实说
                done = true
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    FlDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = "重置密码",
        width = 400.dp,
        content = {
            Column {
                if (done) {
                    Text(
                        "已提交。如果邮箱和重置码都对得上，现在就可以用新密码登录了。",
                        fontSize = 13.sp,
                        color = c.onSurfaceVariant
                    )
                } else {
                    Text(
                        "重置码需要联系管理员生成（30 分钟内有效，用后即焚）。",
                        fontSize = 12.5.sp,
                        color = c.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    FieldLabel("邮箱")
                    FlTextField(value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    FieldLabel("重置码")
                    FlTextField(value = code, onValueChange = { code = it.trim() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    FieldLabel("新密码")
                    FlTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "至少 8 位",
                        visualTransformation = PasswordVisualTransformation(),
                        onSubmit = { submit() }
                    )
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        ErrorBanner(it)
                    }
                }
            }
        },
        actions = {
            if (done) {
                FlButton("知道了", onClick = onDismiss, compact = true)
            } else {
                FlButton("取消", onClick = onDismiss, variant = FlButtonVariant.GHOST, enabled = !busy, compact = true)
                Spacer(Modifier.width(8.dp))
                FlButton(
                    if (busy) "提交中…" else "重置密码",
                    onClick = { submit() },
                    enabled = !busy && email.isNotBlank() && code.isNotBlank() && newPassword.length >= 8,
                    compact = true
                )
            }
        }
    )
}

// ── 设置 / 修改邮箱密码 ──────────────────────────────────────────
//
// 这两个对话框是「两种登录方式登同一个账号」的客户端落点：
// 服务端 `oauth_binding` 挂在 `user_id` 上、不依赖邮箱，
// 所以 GitHub 登录的用户在这里设完邮箱密码后，两种方式指向的是同一个账号，
// 互不影响、随时可换着用。
//
// 界面刻意分成两个对话框而不是一个带「当前密码」可选字段的：
//  - 没设过密码的用户根本没有「原密码」这回事，让他填一个空字段只会困惑
//  - 已经设过密码的用户走服务端 409（PASSWORD_ALREADY_SET）拿不到任何补救路径
// 分开之后两条路各自的必填项都是确定的，也不会出现「到底是新建还是修改」的歧义。

/** 设置邮箱密码：账号还没有密码时用，允许顺便把邮箱改成自己记得住的 */
@Composable
internal fun SetPasswordDialog(
    initialEmail: String,
    onDismiss: () -> Unit,
    onForgotPassword: () -> Unit
) {
    val c = FluentTheme.colors
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 两次输入不一致时当场拦下，不浪费一次 scrypt（服务端算这个很贵）也避免一次往返
    val mismatch = confirm.isNotEmpty() && confirm != password
    val submittable = !busy && email.isNotBlank() && password.length >= 8 &&
        confirm.isNotEmpty() && confirm == password

    fun submit() {
        if (!submittable) return
        error = null
        scope.launch {
            busy = true
            try {
                AccountSession.setPassword(password, email)
                ToastBus.show("邮箱密码已设置，现在两种方式都能登录了")
                onDismiss()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    FlDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = "设置邮箱密码",
        width = 400.dp,
        content = {
            Column {
                Text(
                    "设置后可以用邮箱和密码登录同一个账号，GitHub 登录依然照常可用。",
                    fontSize = 12.5.sp,
                    color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                FieldLabel("邮箱")
                FlTextField(
                    value = email,
                    onValueChange = { email = it.trim(); error = null },
                    modifier = Modifier.fillMaxWidth(),
                    onSubmit = { submit() }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // GitHub 有时只给 12345+user@users.noreply.github.com 这类私有转发地址，
                    // 用户自己记不住，所以这一栏默认可改，而不是只读展示。
                    "第三方登录返回的邮箱如果不是你常用的，改成常用的更方便。",
                    fontSize = 11.5.sp,
                    color = c.onSurfaceFaint
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("新密码")
                FlTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "至少 8 位",
                    visualTransformation = PasswordVisualTransformation(),
                    onSubmit = { submit() }
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("确认密码")
                FlTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "再输一遍",
                    visualTransformation = PasswordVisualTransformation(),
                    onSubmit = { submit() }
                )
                if (mismatch) {
                    Spacer(Modifier.height(12.dp))
                    ErrorBanner("两次输入的密码不一致。")
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    ErrorBanner(it)
                }
            }
        },
        actions = {
            FlButton(
                "忘记密码",
                onClick = onForgotPassword,
                variant = FlButtonVariant.GHOST,
                enabled = !busy,
                compact = true
            )
            Spacer(Modifier.width(8.dp))
            FlButton("取消", onClick = onDismiss, variant = FlButtonVariant.GHOST, enabled = !busy, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton(if (busy) "设置中…" else "设置", onClick = { submit() }, enabled = submittable, compact = true)
        }
    )
}

/** 修改密码：账号已经有密码时用，必须先验原密码 */
@Composable
internal fun ModifyPasswordDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val mismatch = confirm.isNotEmpty() && confirm != password
    val unchanged = old.isNotEmpty() && password.isNotEmpty() && old == password
    val submittable = !busy && old.isNotEmpty() && password.length >= 8 &&
        confirm.isNotEmpty() && confirm == password && !unchanged

    fun submit() {
        if (!submittable) return
        error = null
        scope.launch {
            busy = true
            try {
                // 走 /auth/password（验原密码），不是 reset-with-code：
                // 后者要管理员发重置码，是「忘了密码」的补救，不是「我想改密码」的正路。
                AccountSession.changePassword(old, password)
                ToastBus.show("密码已更新，其他设备已下线")
                onDismiss()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    FlDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = "修改密码",
        width = 380.dp,
        content = {
            Column {
                FieldLabel("原密码")
                FlTextField(
                    value = old,
                    onValueChange = { old = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    onSubmit = { submit() }
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("新密码")
                FlTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "至少 8 位",
                    visualTransformation = PasswordVisualTransformation(),
                    onSubmit = { submit() }
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("确认密码")
                FlTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "再输一遍",
                    visualTransformation = PasswordVisualTransformation(),
                    onSubmit = { submit() }
                )
                if (unchanged) {
                    Spacer(Modifier.height(12.dp))
                    ErrorBanner("新密码不能和原密码一样。")
                } else if (mismatch) {
                    Spacer(Modifier.height(12.dp))
                    ErrorBanner("两次输入的密码不一致。")
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    ErrorBanner(it)
                }
            }
        },
        actions = {
            FlButton("取消", onClick = onDismiss, variant = FlButtonVariant.GHOST, enabled = !busy, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton(if (busy) "提交中…" else "修改", onClick = { submit() }, enabled = submittable, compact = true)
        }
    )
}

// ── 文案映射 ────────────────────────────────────────────────────

/**
 * 把服务端的错误码翻译成「用户接下来该做什么」。
 *
 * 接口文档 §3 特意点了两个码需要引导（`NEED_INVITE` / `EMAIL_EXISTS_REQUIRE_BIND`）——
 * 它们不是「你错了」，而是「此路不通，请走那条路」，光甩 message 用户会卡住。
 */
private fun friendlyOAuthError(code: String, message: String): String = when (code) {
    "NEED_INVITE" -> "这个第三方账号还没有注册过 StuMate。请切到「注册」，填上邀请码再来一次。"
    "EMAIL_EXISTS_REQUIRE_BIND" -> "该邮箱已经注册过了。请先用邮箱密码登录，再到「第三方账号」里绑定。"
    "PROVIDER_NOT_CONFIGURED" -> "服务端还没有配置这个平台的凭据，请先用邮箱登录。"
    "NOT_FOUND" -> "授权已过期或已失效，请重新发起。"
    "TIMEOUT" -> message
    else -> message
}

private fun friendlyAuthError(code: String, message: String): String = when (code) {
    "BAD_CREDENTIALS" -> "邮箱或密码不对。"
    "EMAIL_TAKEN" -> "这个邮箱已经注册过了，直接登录即可。"
    "INVALID_INVITE" -> "邀请码无效、已过期或者已经被用完了。"
    "RATE_LIMITED" -> "尝试太频繁了，请过几分钟再试。"
    "PASSWORD_ALREADY_SET" -> "这个账号已经设过密码了。想换密码请走「修改密码」，那里需要输入原密码。"
    "BAD_REQUEST" -> message
    "NETWORK" -> "连不上服务器，请检查网络后重试。"
    else -> message
}

private fun bindingLabel(provider: String): String = when (provider) {
    "github" -> "GitHub"
    "google" -> "Google"
    else -> provider
}

private fun platformLabel(platform: String): String = when (platform) {
    "desktop" -> "桌面端"
    "android" -> "安卓端"
    else -> platform.ifBlank { "未知平台" }
}

private val ISO_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 服务端时间是 ISO8601 UTC；解析不了就原样显示，绝不因为格式化把界面搞崩 */
private fun formatIso(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    return ISO_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
}
