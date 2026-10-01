package com.example.classreminder.data.sync

import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.backup.objOrNull
import com.example.classreminder.data.backup.str
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 极薄的 HTTP + JSON 客户端。
 *
 * ## 为什么零依赖
 *
 * 用 **JDK 17 自带的 `java.net.http.HttpClient`**，JSON 复用项目自研的 [MiniJson]。
 * 本项目构建必须 `--offline`（拉不到 Gson / Moshi / kotlinx-serialization，也拉不到 Ktor），
 * 所以「两端零网络依赖」这条现状**不用打破** —— `build.gradle.kts` 一行都不用加。
 *
 * ## 约定（接口文档 §1）
 *
 * - 一律 HTTPS，`Content-Type: application/json; charset=utf-8`
 * - 认证走 `Authorization: Bearer <access_token>`
 * - 成功体直接是数据对象（没有 envelope）；204 返回空体
 * - 错误体固定是 `{ "error": { "code": "...", "message": "..." } }`
 */
internal object HttpJson {

    /**
     * 服务端基址。
     *
     * **必须用域名，不能用 IP**（设计文档 §2.3）：内网 IP 从公网不可达，
     * 而且裸 IP 与 Let's Encrypt 证书的域名不匹配，TLS 握手就会失败。
     */
    const val BASE_URL = "https://deeer.online/api/stumate/v1"

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun get(path: String, bearer: String? = null): JsonValue = send("GET", path, null, bearer)

    fun post(path: String, body: JsonValue? = null, bearer: String? = null): JsonValue =
        send("POST", path, body, bearer)

    fun patch(path: String, body: JsonValue? = null, bearer: String? = null): JsonValue =
        send("PATCH", path, body, bearer)

    fun delete(path: String, bearer: String? = null): JsonValue =
        send("DELETE", path, null, bearer)

    private fun send(method: String, path: String, body: JsonValue?, bearer: String?): JsonValue {
        val builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")

        if (body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8")
            builder.method(
                method,
                HttpRequest.BodyPublishers.ofString(
                    MiniJson.write(body, pretty = false),
                    StandardCharsets.UTF_8
                )
            )
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")

        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: IOException) {
            // 断网 / DNS 失败 / 超时 / TLS 失败都落到这里 —— 一律归成 NETWORK，
            // 让 UI 能说「连不上服务器」而不是甩一串英文异常
            throw ApiException("NETWORK", "连不上服务器，请检查网络（${e.message ?: e::class.simpleName}）", 0)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiException("NETWORK", "请求被中断", 0)
        }

        val status = response.statusCode()
        val text = response.body().orEmpty()

        if (status in 200..299) {
            // 204 与空体都当空对象处理，调用方不用到处判 null
            if (text.isBlank()) return JsonValue.Obj(emptyMap())
            return runCatching { MiniJson.parse(text) }.getOrElse {
                throw ApiException("BAD_RESPONSE", "服务端返回的不是合法 JSON", status)
            }
        }

        val err = runCatching {
            (MiniJson.parse(text) as? JsonValue.Obj)?.objOrNull("error")
        }.getOrNull()

        throw ApiException(
            code = err?.str("code")?.takeIf { it.isNotBlank() } ?: "HTTP_$status",
            message = err?.str("message")?.takeIf { it.isNotBlank() } ?: defaultMessage(status),
            httpStatus = status
        )
    }

    private fun defaultMessage(status: Int): String = when (status) {
        400 -> "请求参数不对"
        401 -> "登录状态已失效"
        404 -> "接口不存在"
        429 -> "操作太频繁，请稍后再试"
        500, 502, 503 -> "服务端暂时不可用"
        else -> "服务端返回了 $status"
    }
}
