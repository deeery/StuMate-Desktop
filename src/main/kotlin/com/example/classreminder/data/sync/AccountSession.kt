package com.example.classreminder.data.sync

import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.backup.jsonObject
import com.example.classreminder.data.backup.long
import com.example.classreminder.data.backup.objOrNull
import com.example.classreminder.data.backup.str
import com.example.classreminder.data.backup.toJson
import com.example.classreminder.platform.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 账号会话：本机当前登录的是谁、令牌怎么保鲜、失效了怎么办。
 *
 * 这是**整个登录功能里唯一允许碰令牌的地方**。UI 只读 [user] / [bindings] 两个
 * 状态流，永远拿不到 access token —— 少一个泄露出入口。
 *
 * ## 三条不能破的规矩（都来自接口文档 §4）
 *
 * ### 1. refresh 是轮转的，必须每次覆盖本地
 *
 * 服务端每次 `POST /auth/refresh` 都返回**新的** refresh，旧的立刻作废。
 * 客户端要是没把新的存下来，下次拿旧的去刷 → 服务端判定为**重放攻击** →
 * **整台设备的令牌链被作废，用户被强制重新登录**。
 * 所以 [rotateRefresh] 里是「先落盘、再返回」，顺序不能调。
 *
 * ### 2. 并发刷新会自己把自己打死
 *
 * 轮转 + 重放检测意味着「两个协程同时刷新」= 后到的那个拿着已作废的 refresh。
 * 所以刷新全程压在 [refreshMutex] 上，且锁内**双重检查**：等锁期间别人可能已经刷好了。
 *
 * ### 3. 登录态失效 ≠ 数据没了
 *
 * 只有**服务端明确说 401 / UNAUTHORIZED** 才降级为未登录。
 * 断网、超时、DNS 挂了（[ApiException.isNetwork]）一律**保持登录态** ——
 * 设计文档 §9 的硬要求：网络问题只是「同步暂停」，绝不能变成「本地数据不可用」。
 */
object AccountSession {

    // ── 轮询参数（接口文档 §2.2：每 1.5~2 秒一次，总共不超过 10 分钟） ──

    private const val POLL_INTERVAL_MS = 2_000L
    private const val POLL_TIMEOUT_MS = 10 * 60_000L

    /** access token 剩余寿命低于这个值就提前换新的，别卡在边界上 */
    private const val REFRESH_SKEW_SECONDS = 60L

    // ── 对外状态 ────────────────────────────────────────────────────

    private val _user = MutableStateFlow<AuthUser?>(null)

    /** 当前登录用户；null = 未登录 */
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val _bindings = MutableStateFlow<List<OAuthBinding>>(emptyList())

    /** 当前账号绑定了哪些第三方平台 */
    val bindings: StateFlow<List<OAuthBinding>> = _bindings.asStateFlow()

    val signedIn: Boolean get() = _user.value != null

    /** 磁盘上的凭证是否加密（非 Windows 开发机会是 false，UI 可据此提示） */
    val credentialsEncrypted: Boolean get() = SecretStore.isEncryptedAtRest()

    // ── 内部状态 ────────────────────────────────────────────────────

    @Volatile
    private var tokens: AuthTokens? = null

    private val refreshMutex = Mutex()

    @Volatile
    private var restored = false

    // ── 启动恢复 ────────────────────────────────────────────────────

    /**
     * 从本机凭证恢复登录态。幂等，重复调用只有第一次有效。
     *
     * 分两步走，顺序是有意的：
     *  1. **先用本地快照点亮 UI** —— 没网的时候也要能显示「已登录 · 张三」，
     *     而不是转圈转到超时；
     *  2. 再联网核验一次，顺手把昵称 / 绑定关系刷新到最新。
     *     核验失败只在 401 时降级（见文件头的规矩 3）。
     */
    suspend fun restore() {
        if (restored) return

        val snapshot = refreshMutex.withLock {
            if (restored) return
            restored = true
            readSnapshot()
        } ?: return

        tokens = snapshot.tokens
        _user.value = snapshot.user

        // 核验放在锁外：它要走网络，不能挡着用户点「登录」
        try {
            refreshMe()
        } catch (e: ApiException) {
            if (e.isUnauthorized) signOutLocally()
            // 网络问题：什么都不做，保持「已登录（离线）」状态
        }
    }

    // ── 连通性 ──────────────────────────────────────────────────────

    /**
     * **仅供 UI 预览**：用现成的 access token 直接点亮「已登录」态，不落盘。
     *
     * 存在的理由：「已登录 + 还没有密码」这个状态**没法用登录接口构造** ——
     * 密码是登录的前提，而这类账号（GitHub 注册来的）恰恰没有密码。
     * 截图工具没有鼠标注入，点不出「用 GitHub 登录」那条 OAuth 流程，
     * 于是只能从令牌这一层进去。
     *
     * ⚠️ 只写内存、不写 [SecretStore]：预览进程截完图就退出，
     * 万一 token 落盘会污染真实客户端的登录态。
     */
    fun previewSignInWithToken(accessToken: String, email: String, nickname: String, hasPassword: Boolean) {
        restored = true
        tokens = AuthTokens(
            accessToken = accessToken,
            accessExpiresAt = "2999-01-01T00:00:00Z",
            refreshToken = "",
            refreshExpiresAt = "2999-01-01T00:00:00Z"
        )
        _user.value = AuthUser(id = 0, email = email, nickname = nickname, hasPassword = hasPassword)
        _bindings.value = emptyList()
    }

    /**
     * 探一下服务端是否可达，返回服务端版本号。
     *
     * 放在登录页上很有用：登录失败时用户第一反应是「密码错了」，
     * 但其实很可能是断网 / DNS 挂了 / 服务器没起来 —— 先把这层排掉。
     *
     * @throws ApiException NETWORK / BAD_RESPONSE
     */
    suspend fun health(): String = withContext(Dispatchers.IO) {
        val body = AuthApi.health() as? JsonValue.Obj
            ?: throw ApiException("BAD_RESPONSE", "健康检查返回的不是对象")
        body.str("version")
    }

    // ── 邮箱 + 密码 ─────────────────────────────────────────────────

    /** @throws ApiException BAD_CREDENTIALS / RATE_LIMITED / NETWORK … */
    suspend fun login(email: String, password: String): AuthUser {
        val (user, next) = withContext(Dispatchers.IO) {
            AuthApi.login(email.trim(), password, device())
        }
        persist(user, next)
        return user
    }

    /** @throws ApiException INVALID_INVITE / EMAIL_TAKEN / BAD_REQUEST … */
    suspend fun register(email: String, password: String, inviteCode: String): AuthUser {
        val (user, next) = withContext(Dispatchers.IO) {
            AuthApi.register(email.trim(), password, inviteCode.trim(), device())
        }
        persist(user, next)
        return user
    }

    /** 注册前先本地校验邀请码，省得用户填完一整张表单才被拒 */
    suspend fun checkInvite(code: String): Boolean =
        withContext(Dispatchers.IO) { AuthApi.checkInvite(code.trim()) }

    /**
     * 退出登录。**先通知服务端吊销这条 refresh，再清本地**；
     * 服务端调不通也照样清本地 —— 用户点了退出就必须退出。
     */
    suspend fun logout() {
        val refresh = tokens?.refreshToken
        if (refresh != null) {
            runCatching { withContext(Dispatchers.IO) { AuthApi.logout(refresh) } }
        }
        signOutLocally()
    }

    /** 改密码。服务端会顺手轮转令牌，返回的新令牌同样要落盘 */
    suspend fun changePassword(oldPassword: String, newPassword: String) {
        val access = freshAccessToken()
        val next = withContext(Dispatchers.IO) {
            AuthApi.changePassword(access, oldPassword, newPassword)
        }
        persistTokens(next)
    }

    /**
     * **首次**设置「邮箱 + 密码」。只对还没有密码的账号有效 ——
     * 主要给用 GitHub 注册出来的账号用，让它们之后也能用邮箱密码登录。
     *
     * 服务端一旦发现账号已有密码就返回 `PASSWORD_ALREADY_SET`，这里原样抛给调用方。
     *
     * ⚠️ 走 [persist] 而不是 [persistTokens]：服务端会顺手轮转令牌，
     * **user 和 tokens 都得落盘**。只落 tokens 的话，界面上的
     * 「邮箱密码：还没设置」会一直挂着不刷新；只落 user 不落 tokens 更糟 ——
     * 下次刷新拿着已作废的 refresh 去撞重放检测，整台设备被踢下线。
     */
    suspend fun setPassword(newPassword: String, email: String): AuthUser {
        val access = freshAccessToken()
        val (user, next) = withContext(Dispatchers.IO) {
            AuthApi.setPassword(access, newPassword, email.trim())
        }
        persist(user, next)
        return user
    }

    /**
     * 用管理员发的一次性重置码改密。
     * 服务端**恒定返回 204**，不告诉你邮箱存不存在（防枚举），所以这里也没有返回值。
     */
    suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String) {
        withContext(Dispatchers.IO) {
            AuthApi.resetPasswordWithCode(email.trim(), code.trim(), newPassword)
        }
    }

    // ── 账号信息 ────────────────────────────────────────────────────

    /** 拉一次 `/me`，刷新昵称与绑定关系 */
    suspend fun refreshMe(): AuthUser = authed { access ->
        val me = AuthApi.me(access)
        val user = AuthUser.fromJson(
            me.objOrNull("user") ?: throw ApiException("BAD_RESPONSE", "服务端没有返回用户信息")
        )
        _bindings.value = AuthApi.bindingsFromMe(me)
        _user.value = user
        tokens?.let { writeSnapshot(AccountSnapshot(user, it, System.currentTimeMillis())) }
        user
    }

    suspend fun updateNickname(nickname: String): AuthUser = authed { access ->
        val user = AuthApi.updateNickname(access, nickname.trim())
        _user.value = user
        tokens?.let { writeSnapshot(AccountSnapshot(user, it, System.currentTimeMillis())) }
        user
    }

    suspend fun devices(): List<AuthDevice> = authed { access -> AuthApi.devices(access) }

    suspend fun revokeDevice(deviceId: Int) {
        authed { access -> AuthApi.revokeDevice(access, deviceId) }
    }

    // ── OAuth ───────────────────────────────────────────────────────

    /**
     * 发起第三方登录 / 注册。
     *
     * @param mode `"login"` 或 `"register"`；register 必须带邀请码，
     *             第三方注册同样要过准入，不能绕过。
     */
    suspend fun startOAuth(provider: String, mode: String, inviteCode: String? = null): OAuthTicket =
        withContext(Dispatchers.IO) { AuthApi.oauthStart(provider, mode, inviteCode, device()) }

    /** 已登录状态下发起绑定（需要 Bearer） */
    suspend fun startBind(provider: String): OAuthTicket =
        authed { access -> AuthApi.oauthBindStart(provider, access) }

    /** 解绑。若这是最后一个可登录手段，服务端会 400 拒绝（防止用户把自己锁在门外） */
    suspend fun unbind(provider: String) {
        authed { access -> AuthApi.oauthUnbind(provider, access) }
        runCatching { refreshMe() }
    }

    /**
     * 轮询 OAuth 结果，直到出结果、超时、或者**协程被取消**（用户关掉对话框）。
     *
     * ⚠️ 成功响应**只能取一次**（服务端取走即删），所以 [AuthApi.oauthPoll] 一返回
     * `SignedIn` 就立刻 [persist] 落盘，中间不插任何 suspend 点。
     *
     * 网络抖动**不中断轮询** —— 用户可能正在手机上点授权，一次超时就把 10 分钟的机会
     * 丢掉太亏了；只有服务端明确报错（state 过期 / 业务错误）才收摊。
     */
    suspend fun awaitOAuth(ticket: OAuthTicket, onTick: (Int) -> Unit = {}): OAuthPollResult {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var round = 0

        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            round++

            val result = try {
                withContext(Dispatchers.IO) { AuthApi.oauthPoll(ticket.provider, ticket.state) }
            } catch (e: ApiException) {
                if (e.isNetwork) {
                    onTick(round)
                    continue
                }
                return OAuthPollResult.Failed(e.code, e.message)
            }

            if (result is OAuthPollResult.Pending) {
                onTick(round)
                continue
            }
            if (result is OAuthPollResult.SignedIn) persist(result.user, result.tokens)
            return result
        }
        return OAuthPollResult.Failed("TIMEOUT", "授权等待超时，请重新发起")
    }

    // ── 令牌保鲜 ────────────────────────────────────────────────────

    /**
     * 带认证地调一个接口，401 时自动轮转一次再试。
     *
     * 为什么不在每个调用点各写一遍 try/catch：**重放检测的代价是整台设备掉线**，
     * 这种逻辑必须只有一处实现，否则迟早有人漏掉一个分支。
     */
    private suspend fun <T> authed(block: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        val access = freshAccessToken()
        try {
            block(access)
        } catch (e: ApiException) {
            if (!e.isUnauthorized) throw e
            // access 可能被别处吊销了，强刷一次再试；再失败就让它抛出去
            block(rotateRefresh())
        }
    }

    private suspend fun freshAccessToken(): String {
        val current = tokens ?: throw ApiException("UNAUTHORIZED", "尚未登录", 401)
        if (!needsRefresh(current.accessExpiresAt)) return current.accessToken
        return rotateRefresh()
    }

    /**
     * 轮转 refresh。**整个应用里唯一一处调 `/auth/refresh` 的地方。**
     *
     * 锁内双重检查是必需的：`authed` 在多个协程里并发跑时，第一个协程刷完，
     * 后面的协程拿到的必须是**新**令牌，而不是各自拿旧 refresh 去撞重放检测。
     */
    private suspend fun rotateRefresh(): String = refreshMutex.withLock {
        val current = tokens ?: throw ApiException("UNAUTHORIZED", "尚未登录", 401)
        // 等锁期间可能已经被别的协程刷过了，直接用新的
        if (!needsRefresh(current.accessExpiresAt)) return@withLock current.accessToken

        try {
            val next = withContext(Dispatchers.IO) { AuthApi.refresh(current.refreshToken) }
            // ⚠️ 先落盘再返回。顺序反了 = 下次重放 = 整台设备掉线
            persistTokens(next)
            next.accessToken
        } catch (e: ApiException) {
            if (e.isUnauthorized) signOutLocally()
            throw e
        }
    }

    private fun needsRefresh(expiresAt: String): Boolean {
        val instant = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return true
        return instant.isBefore(Instant.now().plusSeconds(REFRESH_SKEW_SECONDS))
    }

    // ── 落盘 ────────────────────────────────────────────────────────

    private fun persist(user: AuthUser, next: AuthTokens) {
        tokens = next
        _user.value = user
        writeSnapshot(AccountSnapshot(user, next, System.currentTimeMillis()))
    }

    /** 只换令牌、不动用户（刷新 / 改密走这条） */
    private fun persistTokens(next: AuthTokens) {
        tokens = next
        val user = _user.value ?: return
        writeSnapshot(AccountSnapshot(user, next, System.currentTimeMillis()))
    }

    private fun signOutLocally() {
        tokens = null
        _user.value = null
        _bindings.value = emptyList()
        SecretStore.erase()
    }

    private fun writeSnapshot(snapshot: AccountSnapshot) {
        val text = MiniJson.write(encodeSnapshot(snapshot), pretty = false)
        SecretStore.write(text.toByteArray(Charsets.UTF_8))
    }

    private fun readSnapshot(): AccountSnapshot? {
        // 凭证文件可能被手改、被旧版本写过、或者 DPAPI 换了密钥（换机器/换用户）——
        // 任何一种都只意味着「没有可用凭证」，不该把启动流程炸掉
        val raw = runCatching { SecretStore.read() }.getOrNull() ?: return null
        val root = runCatching {
            MiniJson.parse(String(raw, Charsets.UTF_8)) as? JsonValue.Obj
        }.getOrNull() ?: return null

        val user = root.objOrNull("user") ?: return null
        val tokenObj = root.objOrNull("tokens") ?: return null
        return AccountSnapshot(
            // 旧版本写下的凭证文件里没有 has_password。这里按 true 兜底：
            // 宁可少提示一次，也不要凭空告诉一个已经设过密码的人「你还没设密码」。
            // 真实值由紧随其后的 refreshMe() 从服务端取回。
            user = AuthUser.fromJson(user, hasPasswordFallback = true),
            tokens = AuthTokens.fromJson(tokenObj),
            savedAt = root.long("savedAt")
        )
    }

    private fun encodeSnapshot(snapshot: AccountSnapshot) = jsonObject(
        "user" to jsonObject(
            "id" to snapshot.user.id.toJson(),
            "email" to snapshot.user.email.toJson(),
            "nickname" to snapshot.user.nickname.toJson(),
            "has_password" to snapshot.user.hasPassword.toJson()
        ),
        "tokens" to jsonObject(
            "access_token" to snapshot.tokens.accessToken.toJson(),
            "access_expires_at" to snapshot.tokens.accessExpiresAt.toJson(),
            "refresh_token" to snapshot.tokens.refreshToken.toJson(),
            "refresh_expires_at" to snapshot.tokens.refreshExpiresAt.toJson()
        ),
        "savedAt" to snapshot.savedAt.toJson()
    )

    // ── 设备标识 ────────────────────────────────────────────────────

    /**
     * 本机在服务端「设备列表」里显示的名字。
     * 优先用 Windows 的 `COMPUTERNAME`，它通常就是用户在系统设置里看到的机器名。
     */
    private fun device(): DeviceInfo = DeviceInfo(name = deviceName())

    private fun deviceName(): String {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { java.net.InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "我的电脑"
    }
}
