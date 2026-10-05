package com.example.classreminder.dev

import com.example.classreminder.platform.AppIdentity
import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.nio.charset.Charset

/**
 * 托盘气泡（右下角 Windows 通知）的**最小复现探针**。
 *
 * 用途只有一个：把「中文在气泡里会不会变成乱码」这件事，从
 * 「读源码猜」变成「跑一次看」。它走的是**与应用完全同一条代码路径**：
 * Compose 的 `TrayState.sendNotification(Notification(...))` 底层就是
 * `TrayIcon.displayMessage(caption, text, type)`。
 *
 * ## 怎么用
 *
 * 必须用**打包同款运行时**跑才有意义 —— 打包用的是 jlink 裁剪过的 runtime
 * （模块集见 `runtime/release` 的 `MODULES`），它跟完整 JDK 的差别正是嫌疑所在。
 *
 * ```
 * CP=$(cat F:/DownloadQQ/stumate-cp.txt)
 * F:/DownloadQQ/jlinkprobe/bin/java.exe -cp "$CP" com.example.classreminder.dev.TrayProbeKt
 * ```
 *
 * 开关：
 *  - `-Dstumate.probe.noAumid=1`  不设 AUMID（对照组）
 *  - `-Dstumate.probe.hold=秒数`  气泡显示后停留多久（默认 25）
 */
fun main() {
    val noAumid = System.getProperty("stumate.probe.noAumid") == "1"
    val hold = System.getProperty("stumate.probe.hold")?.toLongOrNull() ?: 25L

    println("─".repeat(64))
    println("托盘气泡探针")
    println("  java.version      = ${System.getProperty("java.version")}")
    println("  file.encoding     = ${System.getProperty("file.encoding")}")
    println("  sun.jnu.encoding  = ${System.getProperty("sun.jnu.encoding")}")
    println("  defaultCharset    = ${Charset.defaultCharset()}")
    println("  Locale            = ${java.util.Locale.getDefault()}")
    println("  GBK 可用          = ${Charset.isSupported("GBK")}")
    println("  设 AUMID          = ${!noAumid}")
    println("─".repeat(64))

    if (!noAumid) {
        val ok = AppIdentity.install()
        println("AppIdentity.install() = $ok  (AUMID=${AppIdentity.AUMID})")
    }

    if (!SystemTray.isSupported()) {
        println("× 本机不支持 SystemTray")
        return
    }

    // 画一个纯色小图标：托盘里得先有图标，气泡才有「宿主」
    val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    // ⚠️ 不能写 `createGraphics().use { }`：`java.awt.Graphics` **没有**实现
    // `AutoCloseable`，Kotlin 的 `kotlin.io.use` 不适用（自己写扩展又会和它抢重载）。
    val g = img.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x00, 0x67, 0xC0)
        g.fillRoundRect(0, 0, 16, 16, 4, 4)
        g.color = Color.WHITE
        g.drawString("S", 5, 12)
    } finally {
        g.dispose()
    }

    val tray = SystemTray.getSystemTray()
    val icon = TrayIcon(img, "StuMate 测试通知")
    icon.isImageAutoSize = true
    try {
        tray.add(icon)
    } catch (t: Throwable) {
        println("× 加托盘图标失败：$t")
        return
    }
    println("√ 托盘图标已加上，等 3 秒再弹气泡…")
    Thread.sleep(3_000)

    // 与应用里「设置页 → 测试通知」完全相同的文案
    // ⚠️ `tag` 是**必须**的：Windows 对「内容完全相同」的 Toast 有抑制
    // （Toast 历史里出现过的会直接跳过），不换内容会看到「气泡不弹」的假象。
    val tag = System.getProperty("stumate.probe.tag")?.let { " [$it]" } ?: ""
    val caption = "StuMate 测试通知$tag"
    val text = "通知可用，到点会像这样提醒你。$tag"
    println("displayMessage(caption=\"$caption\", text=\"$text\")")
    icon.displayMessage(caption, text, TrayIcon.MessageType.INFO)

    // 再补一条真实提醒的文案，两相对照
    Thread.sleep(4_000)
    val caption2 = "即将上课：高等数学$tag"
    val text2 = "教二 305  08:00 - 09:35$tag"
    println("displayMessage(caption=\"$caption2\", text=\"$text2\")")
    icon.displayMessage(caption2, text2, TrayIcon.MessageType.INFO)

    println("气泡已发出，停留 ${hold}s 供截图…")
    Thread.sleep(hold * 1_000)
    tray.remove(icon)
    println("结束")
}
