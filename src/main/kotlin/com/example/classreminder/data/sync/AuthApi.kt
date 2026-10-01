package com.example.classreminder.data.sync

import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.array
import com.example.classreminder.data.backup.jsonObject
import com.example.classreminder.data.backup.objOrNull
import com.example.classreminder.data.backup.str
import com.example.classreminder.data.backup.toJson
import java.net.URLEncoder

/**
 * 服务端账号与 OAuth 接口的强类型封装。
 *
 * 路径与字段严格按 `StuMate-服务端-OAuth接口文档-v1.0.md` 来。
 * 这里只做「拼请求 + 解析响应」，**不做任何重试与令牌管理** ——
 * 那些属于 [AccountSession] 的职责（尤其是 refresh 轮转，见那里的说明）。
 */
internal object AuthApi {

    private const val P = "/auth"
    private const val M = "/me"

    private fun deviceJson(device: DeviceInfo) = jsonObject(
        "name" to device.name.toJson(),
        "platform" to device.platform.toJson()
    )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ── 健康检查 ────────────────────────────────────────────────────

    /** 用来在登录页显示「服务端可达」。失败会抛 [ApiException] */
    fun health(): JsonValue = HttpJson.get("/health")

    // ── 邀请码 ──────────────────────────────────────────────────────

    fun checkInvite(code: String): Boolean =
        (HttpJson.get("$P/invite/check?code=${enc(code)}") as? JsonValue.Obj)
            ?.let { (it.fields["valid"] as? JsonValue.Bool)?.value } ?: false

    // ── 邮箱 + 密码 ─────────────────────────────────────────────────

    fun register(
        email: String,
        password: String,
        inviteCode: String,
        device: DeviceInfo
    ): Pair<AuthUser, AuthTokens> {
        val res = HttpJson.post(
            "$P/register",
            jsonObject(
                "email" to email.toJson(),
                "password" to password.toJson(),
                "invite_code" to inviteCode.toJson(),
                "device" to deviceJson(device)
            )
        )
        return parseUserAndTokens(res)
    }

    fun login(email: String, password: String, device: DeviceInfo): Pair<AuthUser, AuthTokens> {
        val res = HttpJson.post(
            "$P/login",
            jsonObject(
                "email" to email.toJson(),
                "password" to password.toJson(),
                "device" to deviceJson(device)
            )
        )
        return parseUserAndTokens(res)
    }

    /**
     * 刷新令牌。**服务端是轮转的**：返回的 refresh 是新的，旧的那条立刻作废。
     * 调用方必须用返回值覆盖本地，否则下次刷新会被判定为重放 →
     * 整台设备的令牌链被作废，用户被强制重新登录。
     */
    fun refresh(refreshToken: String): AuthTokens {
        val res = HttpJson.post("$P/refresh", jsonObject("refresh_token" to refreshToken.toJson()))
        val tokens = (res as? JsonValue.Obj)?.objOrNull("tokens")
            ?: throw ApiException("BAD_RESPONSE", "刷新接口没有返回 tokens")
        return AuthTokens.fromJson(tokens)
    }

    /** 退出登录（吊销这条 refresh）。失败不抛，调用方照样清本地 */
    fun logout(refreshToken: String) {
        HttpJson.post("$P/logout", jsonObject("refresh_token" to refreshToken.toJson()))
    }

    fun changePassword(accessToken: String, oldPassword: String, newPassword: String): AuthTokens {
        val res = HttpJson.post(
            "$P/password",
            jsonObject(
                "old_password" to oldPassword.toJson(),
                "new_password" to newPassword.toJson()
            ),
            bearer = accessToken
        )
        val tokens = (res as? JsonValue.Obj)?.objOrNull("tokens")
            ?: throw ApiException("BAD_RESPONSE", "改密接口没有返回 tokens")
        return AuthTokens.fromJson(tokens)
    }

    /**
     * 用管理员发的一次性重置码改密。
     * 服务端**恒定返回 204**，不告诉你邮箱存不存在（防枚举），所以这里也没有返回值。
     */
    fun resetPasswordWithCode(email: String, code: String, newPassword: String) {
        HttpJson.post(
            "$P/password/reset-with-code",
            jsonObject(
                "email" to email.toJson(),
                "code" to code.toJson(),
                "new_password" to newPassword.toJson()
            )
        )
    }

    /**
     * **首次**给账号设置「邮箱 + 密码」。只对**还没有密码**的账号有效。
     *
     * 服务对象主要是用 GitHub 注册出来的账号：它们的 `password_hash` 是 null，
     * 而 [changePassword] 必须先验原密码、无从验起 —— 所以这类账号原先**没有任何途径**
     * 能设上密码，只能永远靠第三方登录。设完之后同一个账号两种方式都能进。
     *
     * 账号一旦有了密码，服务端就返回 409 `PASSWORD_ALREADY_SET`。这是硬边界不是偷懒：
     * 少了这道闸，任何拿到 access token 的人都能直接改密码，把「改密要验原密码」整个绕开。
     *
     * @param email 传空串表示不改邮箱、沿用账号现有的那个
     */
    fun setPassword(
        accessToken: String,
        newPassword: String,
        email: String
    ): Pair<AuthUser, AuthTokens> {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["new_password"] = newPassword.toJson()
        if (email.isNotBlank()) fields["email"] = email.toJson()
        val res = HttpJson.post("$P/password/set", JsonValue.Obj(fields), bearer = accessToken)
        return parseUserAndTokens(res)
    }

    // ── 账号信息 ────────────────────────────────────────────────────

    /** `{ user, bindings }` */
    fun me(accessToken: String): JsonValue.Obj =
        (HttpJson.get(M, bearer = accessToken) as? JsonValue.Obj)
            ?: throw ApiException("BAD_RESPONSE", "/me 返回的不是对象")

    fun updateNickname(accessToken: String, nickname: String): AuthUser {
        val res = HttpJson.patch(M, jsonObject("nickname" to nickname.toJson()), bearer = accessToken)
        val user = (res as? JsonValue.Obj)?.objOrNull("user")
            ?: throw ApiException("BAD_RESPONSE", "/me 没有返回 user")
        return AuthUser.fromJson(user)
    }

    fun devices(accessToken: String): List<AuthDevice> =
        AuthDevice.listFromJson(HttpJson.get("$M/devices", bearer = accessToken))

    fun revokeDevice(accessToken: String, deviceId: Int) {
        HttpJson.delete("$M/devices/$deviceId", bearer = accessToken)
    }

    // ── OAuth ───────────────────────────────────────────────────────

    /**
     * 发起第三方登录 / 注册。
     *
     * `mode = "register"` 时必须带邀请码 —— 第三方登录**不能绕过准入控制**。
     * 返回的 `authorize_url` 交给系统浏览器打开，然后轮询 [oauthPoll]。
     */
    fun oauthStart(
        provider: String,
        mode: String,
        inviteCode: String?,
        device: DeviceInfo
    ): OAuthTicket {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["mode"] = mode.toJson()
        if (mode == "register" && !inviteCode.isNullOrBlank()) {
            fields["invite_code"] = inviteCode.toJson()
        }
        fields["device"] = deviceJson(device)

        val res = HttpJson.post("/oauth/$provider/start", JsonValue.Obj(fields))
        val o = res as? JsonValue.Obj ?: throw ApiException("BAD_RESPONSE", "OAuth start 返回的不是对象")
        return OAuthTicket(
            provider = provider,
            state = o.str("state"),
            authorizeUrl = o.str("authorize_url"),
            expiresInSeconds = (o.fields["expires_in"] as? JsonValue.Num)?.value?.toInt() ?: 600
        )
    }

    /** 已登录状态下发起绑定 */
    fun oauthBindStart(provider: String, accessToken: String): OAuthTicket {
        val res = HttpJson.post("/oauth/$provider/bind", bearer = accessToken)
        val o = res as? JsonValue.Obj ?: throw ApiException("BAD_RESPONSE", "OAuth bind 返回的不是对象")
        return OAuthTicket(
            provider = provider,
            state = o.str("state"),
            authorizeUrl = o.str("authorize_url"),
            expiresInSeconds = (o.fields["expires_in"] as? JsonValue.Num)?.value?.toInt() ?: 600
        )
    }

    fun oauthUnbind(provider: String, accessToken: String) {
        HttpJson.delete("/oauth/$provider/bind", bearer = accessToken)
    }

    /**
     * 轮询 OAuth 结果。
     *
     * ⚠️ **成功响应只能取一次** —— 取完服务端就删掉这条 state。
     * 所以调用方拿到 `SignedIn` / `Bound` 后必须**立刻落盘再用**，
     * 不能把解析或保存留到下一次请求。
     */
    fun oauthPoll(provider: String, state: String): OAuthPollResult {
        // state 过期 / 无效时服务端返回 404 NOT_FOUND（接口文档 §5）。
        // 这不是「异常」，而是轮询的正常结局之一 —— 必须变成 Failed 值返回，
        // 否则调用方得在两处分别处理「失败」和「失败」，迟早漏掉一边。
        val res = try {
            HttpJson.get("/oauth/$provider/poll?state=${enc(state)}")
        } catch (e: ApiException) {
            // 网络问题继续往上抛：轮询循环会忽略它继续等，别浪费掉 10 分钟的授权窗口
            if (e.isNetwork) throw e
            return OAuthPollResult.Failed(e.code, e.message)
        }
        val o = res as? JsonValue.Obj ?: throw ApiException("BAD_RESPONSE", "OAuth poll 返回的不是对象")
        return when (o.str("status")) {
            "pending" -> OAuthPollResult.Pending
            "ok" -> {
                val user = o.objOrNull("user")
                val tokens = o.objOrNull("tokens")
                if (user != null && tokens != null) {
                    OAuthPollResult.SignedIn(AuthUser.fromJson(user), AuthTokens.fromJson(tokens))
                } else {
                    val binding = o.objOrNull("binding")
                    if (binding != null) OAuthPollResult.Bound(OAuthBinding.fromJson(binding))
                    else OAuthPollResult.Failed("BAD_RESPONSE", "OAuth 返回了 ok 但既没有 user 也没有 binding")
                }
            }
            "error" -> {
                val err = o.objOrNull("error")
                OAuthPollResult.Failed(
                    code = err?.str("code") ?: "OAUTH_FAILED",
                    message = err?.str("message") ?: "第三方登录失败"
                )
            }
            else -> OAuthPollResult.Failed("BAD_RESPONSE", "OAuth 返回了未知状态")
        }
    }

    /** 已登录账号当前绑定了哪些平台 */
    fun bindingsFromMe(me: JsonValue.Obj): List<OAuthBinding> =
        me.array("bindings").mapNotNull { (it as? JsonValue.Obj)?.let(OAuthBinding::fromJson) }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun parseUserAndTokens(res: JsonValue): Pair<AuthUser, AuthTokens> {
        val o = res as? JsonValue.Obj ?: throw ApiException("BAD_RESPONSE", "服务端返回的不是对象")
        val user = o.objOrNull("user") ?: throw ApiException("BAD_RESPONSE", "返回里没有 user")
        val tokens = o.objOrNull("tokens") ?: throw ApiException("BAD_RESPONSE", "返回里没有 tokens")
        return AuthUser.fromJson(user) to AuthTokens.fromJson(tokens)
    }
}
