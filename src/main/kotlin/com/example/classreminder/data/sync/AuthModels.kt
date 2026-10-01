package com.example.classreminder.data.sync

import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.array
import com.example.classreminder.data.backup.bool
import com.example.classreminder.data.backup.int
import com.example.classreminder.data.backup.str

/**
 * 服务端返回的错误。
 *
 * `code` 是稳定的机器可读码（接口文档 §5），`message` 是可以直接展示给用户的人话。
 * 用异常而不是 sealed Result：调用链里绝大多数地方只想「成功就往下走、失败就提示」，
 * 用 Result 会把每个中间层都塞满 `?: return`。
 */
class ApiException(
    val code: String,
    override val message: String,
    val httpStatus: Int = 0
) : Exception(message) {

    /** 连不上服务器（DNS / 超时 / 断网），不是服务端拒绝 */
    val isNetwork: Boolean get() = code == "NETWORK"

    /** 令牌失效或被吊销 → 调用方应该先静默 refresh 一次 */
    val isUnauthorized: Boolean get() = httpStatus == 401
}

data class AuthUser(
    val id: Int,
    val email: String,
    val nickname: String,
    /**
     * 这个账号**有没有**邮箱密码。
     *
     * 用 GitHub 注册出来的账号 `password_hash` 是 null —— 它只能靠第三方登录，
     * 界面上要据此引导用户补一套邮箱密码（见 `AccountSection` 的「邮箱密码」一行）。
     *
     * ⚠️ 服务端只回布尔，**永远不会**把 `password_hash` 本身发出来。
     */
    val hasPassword: Boolean = false
) {
    companion object {
        /**
         * @param hasPasswordFallback 服务端没给 `has_password` 时按什么算。
         *
         *        服务端**所有**返回 user 的接口都会带上这个字段，所以这个兜底只对
         *        「旧版本写下的本地凭证文件」生效。那种情况下按 `true` 算：
         *        宁可少提示一次，也不要凭空告诉一个已经设过密码的人「你还没设密码」。
         */
        fun fromJson(o: JsonValue.Obj, hasPasswordFallback: Boolean = false) = AuthUser(
            id = o.int("id"),
            email = o.str("email"),
            nickname = o.str("nickname"),
            hasPassword = o.bool("has_password", hasPasswordFallback)
        )
    }
}

data class AuthTokens(
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String
) {
    companion object {
        fun fromJson(o: JsonValue.Obj) = AuthTokens(
            accessToken = o.str("access_token"),
            accessExpiresAt = o.str("access_expires_at"),
            refreshToken = o.str("refresh_token"),
            refreshExpiresAt = o.str("refresh_expires_at")
        )
    }
}

data class AuthDevice(
    val id: Int,
    val name: String,
    val platform: String,
    /** 是不是当前这台设备 */
    val current: Boolean,
    val lastSeenAt: String
) {
    companion object {
        fun fromJson(o: JsonValue.Obj) = AuthDevice(
            id = o.int("id"),
            name = o.str("name"),
            platform = o.str("platform"),
            current = o.bool("current"),
            lastSeenAt = o.str("last_seen_at")
        )

        /** `GET /me/devices` 的响应形如 `{ "devices": [ … ] }` */
        fun listFromJson(root: JsonValue): List<AuthDevice> =
            (root as? JsonValue.Obj)?.array("devices")
                ?.mapNotNull { (it as? JsonValue.Obj)?.let(::fromJson) }
                ?: emptyList()
    }
}

data class OAuthBinding(
    val provider: String,
    val providerUid: String,
    val providerEmail: String
) {
    companion object {
        fun fromJson(o: JsonValue.Obj) = OAuthBinding(
            provider = o.str("provider"),
            providerUid = o.str("provider_uid"),
            providerEmail = o.str("provider_email")
        )
    }
}

/** 设备上报给服务端的信息（几乎所有登录类接口都要带） */
data class DeviceInfo(val name: String, val platform: String = "desktop")

/** 已登录账号的本地快照，持久化在 `credentials.bin` 里 */
data class AccountSnapshot(
    val user: AuthUser,
    val tokens: AuthTokens,
    val savedAt: Long
)

/** OAuth 等待阶段的中间态：state + 给用户看的授权地址 */
data class OAuthTicket(
    val provider: String,
    val state: String,
    val authorizeUrl: String,
    val expiresInSeconds: Int
)

/** 轮询结果 */
sealed interface OAuthPollResult {
    /** 用户还在浏览器里没弄完 */
    object Pending : OAuthPollResult

    /** 登录 / 注册成功，拿到了令牌 */
    data class SignedIn(val user: AuthUser, val tokens: AuthTokens) : OAuthPollResult

    /** 绑定成功（已登录状态下发起） */
    data class Bound(val binding: OAuthBinding) : OAuthPollResult

    /** 失败 */
    data class Failed(val code: String, val message: String) : OAuthPollResult
}
