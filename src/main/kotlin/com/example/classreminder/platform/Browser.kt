package com.example.classreminder.platform

import java.awt.Desktop
import java.net.URI

/**
 * 打开系统默认浏览器。
 *
 * OAuth 必须走**系统浏览器**，不能在应用里内嵌 WebView：
 * 用户在地址栏里看到 `github.com` 才会放心输入账号密码，内嵌窗口是在培养钓鱼习惯。
 * 而且服务端中介方案下，回调由服务端接，客户端只要「把授权页推给用户」就行。
 *
 * 三级降级：AWT Desktop → Windows `rundll32` → 失败。前两级都失败才算真失败。
 */
object Browser {

    /** @return 是否成功把浏览器叫起来（不代表用户在浏览器里做了什么） */
    fun open(url: String): Boolean {
        if (url.isBlank()) return false
        return openWithDesktop(url) || openWithRundll32(url)
    }

    private fun openWithDesktop(url: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
        desktop.browse(URI(url))
        true
    }.getOrDefault(false)

    /**
     * 某些精简过的 JRE / 无桌面会话环境下 `Desktop.Action.BROWSE` 会报不支持，
     * 但 `rundll32` 其实还能用 —— 多一条兜底路径，成本几乎为零。
     */
    private fun openWithRundll32(url: String): Boolean = runCatching {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
        true
    }.getOrDefault(false)
}
