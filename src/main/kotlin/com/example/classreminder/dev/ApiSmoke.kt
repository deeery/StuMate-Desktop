package com.example.classreminder.dev

import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.str
import com.example.classreminder.data.sync.ApiException
import com.example.classreminder.data.sync.AuthApi
import com.example.classreminder.data.sync.DeviceInfo
import com.example.classreminder.data.sync.HttpJson
import com.example.classreminder.data.sync.OAuthPollResult
import kotlinx.coroutines.runBlocking

/**
 * 账号接口冒烟测试 —— **连真实服务端**跑一遍，用来验证客户端的网络层真的通。
 *
 * 这不是单元测试（单测不联网），而是「装上就能用吗」的那一步验证：
 * HTTPS 握手、JSON 编解码、错误码映射、OAuth 授权地址生成，全都在这里过一遍。
 *
 * 设计上**只做无副作用或有自愈副作用的调用**：
 *  - `/health`、`/auth/invite/check`、`/auth/login`（故意用错密码）都是只读
 *  - `/oauth/{provider}/start` 会写一条 10 分钟后自动失效的 state 记录，不会建账号
 *  - **不会**调用 `/auth/register` —— 建账号要花邀请码，这种事不该由脚本悄悄做
 *
 * 跑法：`./gradlew apiSmoke`
 */
fun main() = runBlocking {
    println("═".repeat(64))
    println(" StuMate 账号接口冒烟测试（真实服务端）")
    println(" 基址：${HttpJson.BASE_URL}")
    println("═".repeat(64))

    var passed = 0
    var failed = 0

    fun check(name: String, block: () -> String) {
        val result = runCatching(block)
        val line = result.getOrElse { e ->
            if (e is ApiException) "抛异常 ${e.code}（HTTP ${e.httpStatus}）：${e.message}"
            else "抛异常 ${e::class.simpleName}：${e.message}"
        }
        // 约定：以「√」开头的描述算通过，「×」开头算失败
        // （不用 √/× —— 这两个码位不在 GBK 里，中文控制台会打成问号）
        if (line.startsWith("√")) {
            passed++
            println("  [PASS] $name")
        } else {
            failed++
            println("  [FAIL] $name")
        }
        println("         $line")
    }

    // ① 服务端可达性
    check("GET /health 服务端可达") {
        val body = AuthApi.health() as? JsonValue.Obj
            ?: return@check "× /health 返回的不是 JSON 对象"
        "√ 服务端在线，version=${body.str("version").ifBlank { "(未返回 version)" }}"
    }

    // ② 邀请码校验（用一个必然不存在的码）
    check("GET /auth/invite/check 不存在的码应判为无效") {
        val valid = AuthApi.checkInvite("BOGUS-0000-0000")
        if (valid) "× 假邀请码居然被判为有效" else "√ 正确判为无效（valid=false）"
    }

    // ③ 登录失败路径：错误密码应当返回 BAD_CREDENTIALS
    check("POST /auth/login 错误凭据应返回 BAD_CREDENTIALS") {
        try {
            AuthApi.login("nobody@example.invalid", "not-a-real-password", DeviceInfo("smoke-test", "desktop"))
            "× 用不存在的账号居然登录成功了"
        } catch (e: ApiException) {
            if (e.code == "BAD_CREDENTIALS") "√ 返回 BAD_CREDENTIALS（HTTP ${e.httpStatus}），错误码映射正确"
            else "× 期望 BAD_CREDENTIALS，实际 ${e.code}（HTTP ${e.httpStatus}）"
        }
    }

    // ④ 防枚举：账号不存在时，无论密码多短都只能得到 BAD_CREDENTIALS
    //
    // 接口文档 §4 说的「密码最少 8 位」只对**注册与改密**生效；登录接口刻意不校验长度，
    // 否则攻击者就能用「返回码是 BAD_REQUEST 还是 BAD_CREDENTIALS」来推断账号是否存在。
    check("POST /auth/login 账号不存在时不泄露账号存在性") {
        val short = try {
            AuthApi.login("nobody@example.invalid", "123", DeviceInfo("smoke-test", "desktop")); null
        } catch (e: ApiException) { e.code }
        val long = try {
            AuthApi.login("nobody@example.invalid", "a".repeat(24), DeviceInfo("smoke-test", "desktop")); null
        } catch (e: ApiException) { e.code }
        when {
            short != long -> "× 短密码($short) 与 长密码($long) 返回码不同，可以枚举账号"
            short == "BAD_CREDENTIALS" -> "√ 两种密码都返回 BAD_CREDENTIALS，不泄露账号存在性"
            else -> "· 两种密码都返回 $short"
        }
    }

    // ⑤ GitHub OAuth：应当拿到真实授权地址
    check("POST /oauth/github/start 应返回真实 authorize_url") {
        val ticket = AuthApi.oauthStart("github", "login", null, DeviceInfo("smoke-test", "desktop"))
        val host = runCatching { java.net.URI(ticket.authorizeUrl).host }.getOrNull()
        when {
            ticket.state.isBlank() -> "× 没有返回 state"
            !ticket.authorizeUrl.startsWith("https://github.com/") ->
                "× 授权地址不是 github.com：${ticket.authorizeUrl.take(80)}"
            !ticket.authorizeUrl.contains("client_id=") ->
                "× 授权地址里没有 client_id，说明服务端没配凭据"
            else -> "√ host=$host state=${ticket.state.take(12)}… 有效期 ${ticket.expiresInSeconds}s"
        }
    }

    // ⑥ Google OAuth：文档说凭据还没配，应当明确报 PROVIDER_NOT_CONFIGURED
    check("POST /oauth/google/start 凭据未配置时应明确报错") {
        try {
            val ticket = AuthApi.oauthStart("google", "login", null, DeviceInfo("smoke-test", "desktop"))
            "· 返回了授权地址（说明 Google 凭据已配好）：${ticket.authorizeUrl.take(70)}…"
        } catch (e: ApiException) {
            if (e.code == "PROVIDER_NOT_CONFIGURED")
                "√ 返回 PROVIDER_NOT_CONFIGURED（HTTP ${e.httpStatus}），与接口文档 §6 一致"
            else "× 期望 PROVIDER_NOT_CONFIGURED，实际 ${e.code}（HTTP ${e.httpStatus}）"
        }
    }

    // ⑦ 轮询一个不存在的 state
    check("GET /oauth/github/poll 无效 state 应报错") {
        val r = AuthApi.oauthPoll("github", "0".repeat(32))
        when (r) {
            is OAuthPollResult.Failed -> "√ 返回错误：${r.code} —— ${r.message}"
            else -> "× 期望失败，实际 $r"
        }
    }

    // ⑧ 未带令牌访问受保护接口
    check("GET /me 未认证应被拒绝") {
        try {
            AuthApi.me("0".repeat(64))
            "× 伪造令牌居然访问成功了"
        } catch (e: ApiException) {
            if (e.httpStatus == 401) "√ 返回 401（${e.code}），受保护接口有效"
            else "× 期望 401，实际 HTTP ${e.httpStatus} / ${e.code}"
        }
    }

    println("─".repeat(64))
    println(" 通过 $passed 项，失败 $failed 项")
    println("═".repeat(64))
    if (failed > 0) kotlin.system.exitProcess(1)
}
