package com.example.classreminder.dev

import com.example.classreminder.platform.AppIdentity
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.Charset
import javax.imageio.ImageIO

/**
 * 托盘气泡（右下角 Windows 通知）的**最小复现探针**。
 *
 * 用途只有一个：把「气泡到底弹没弹 / 中文与应用名对不对」这件事，
 * 从「读源码猜」变成「跑一次看」。它走的是**与应用完全同一条代码路径**：
 * Compose 的 `TrayState.sendNotification(Notification(...))` 底层就是
 * `TrayIcon.displayMessage(caption, text, type)`。
 *
 * ## 为什么探针要自己截图
 *
 * 气泡**不是本进程的窗口**（由 Explorer 托管），外部脚本按窗口标题抓不到它；
 * 靠外部脚本「等 N 秒再全屏抓」又是在赌时机 —— 抓早了气泡还没滑出来、
 * 抓晚了已经消失，得到的是**假阴性**（看起来像「功能没做」）。
 *
 * 所以探针自己在**同一个进程里**取两张图：`displayMessage` 之前一张（基线）、
 * 之后一张，然后只对**右下角**那块区域做逐像素差分，直接打印
 * `气泡可见 = true/false`。时序完全由进程自己掌握，不靠运气。
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
 *  - `-Dstumate.probe.noDisplayName=1` 只设 AUMID、**不**写 `DisplayName`
 *    （对照组 = 修复前用户的处境）
 *  - `-Dstumate.probe.awtFirst=1` 先建一个**真实原生窗口**再设 AUMID
 *    （对照组，用来检验「设 AUMID 必须早于任何窗口」这条说法）
 *  - `-Dstumate.probe.shot=目录`   把基线与两轮 after 图存下来，供肉眼复核
 *  - `-Dstumate.probe.corner=760x260` 差分区域（右下角，默认 760x260）
 *  - `-Dstumate.probe.hold=秒数`  气泡显示后停留多久（默认 25）
 *
 * ## 实测结论（2026-10-05，Win11 26200，打包同款 jlink 运行时）
 *
 * | 场景 | 气泡 | 标题那一行 |
 * |---|---|---|
 * | 不设 AUMID | **照弹** | `OpenJDK Platform binary`（宿主 exe 的文件描述） |
 * | 设 AUMID，但没注册显示名 | 照弹 | 原始 AUMID 字符串 |
 * | 设 AUMID + 注册 `DisplayName` | 照弹 | `StuMate` |
 * | 先建真实窗口**再**设 AUMID | **照弹**，标题 `StuMate` | 同上一行 |
 *
 * 🔴 后两行推翻了两个曾经写进代码注释的「根因」：
 * 「不设 AUMID → 气泡完全不弹」与「设 AUMID 必须早于任何窗口」**都不成立**。
 * 它们很可能是**假阴性**：气泡不是本进程窗口，早先靠外部脚本「等 N 秒再全屏抓」，
 * 抓早了/被 Toast 历史去重都会得到「什么都没弹」的假象。
 * 现在探针在进程内自己取基线 + 取 after 做差分，时序不再靠运气。
 */
fun main() {
    val noAumid = System.getProperty("stumate.probe.noAumid") == "1"
    val noDisplayName = System.getProperty("stumate.probe.noDisplayName") == "1"
    val awtFirst = System.getProperty("stumate.probe.awtFirst") == "1"
    val hold = System.getProperty("stumate.probe.hold")?.toLongOrNull() ?: 25L
    val shotDir = System.getProperty("stumate.probe.shot")?.takeIf { it.isNotBlank() }?.let { File(it) }
    val corner = System.getProperty("stumate.probe.corner")?.let { spec ->
        val (w, h) = spec.split("x").mapNotNull { it.trim().toIntOrNull() }.let {
            if (it.size == 2) it[0] to it[1] else 760 to 260
        }
        w to h
    } ?: (760 to 260)

    println("─".repeat(64))
    println("托盘气泡探针")
    println("  java.version      = ${System.getProperty("java.version")}")
    println("  file.encoding     = ${System.getProperty("file.encoding")}")
    println("  sun.jnu.encoding  = ${System.getProperty("sun.jnu.encoding")}")
    println("  defaultCharset    = ${Charset.defaultCharset()}")
    println("  Locale            = ${java.util.Locale.getDefault()}")
    println("  GBK 可用          = ${Charset.isSupported("GBK")}")
    println("  设 AUMID          = ${!noAumid}")
    println("  先建窗口再设 AUMID = $awtFirst")
    println("─".repeat(64))

    if (awtFirst) {
        // 模拟 Compose 的 `application { }`：它进 lambda 之前就已经把 AWT 起起来了
        // （要事件循环、要 Toolkit），并且很快会建出真实窗口。
        //
        // 🔴 `JFrame.setSize` **不会**创建原生窗口 —— peer 是在 `addNotify()` 里建的，
        // 只有 `setVisible(true)` / `pack()` 才会触发。第一版对照组只写了
        // `setSize(1,1)`，结果 AWT 初始化了但一个 HWND 都没有，
        // 气泡照弹 —— 那是个**假的对照组**（看起来像「假设被推翻」，其实什么都没测）。
        // 这里显式 `isVisible = true` 再收起来，确保真的建过一个 HWND。
        javax.swing.JFrame("probe-hidden").apply {
            setSize(200, 120)
            isVisible = true
            isVisible = false
            dispose()
        }
        println("已建过一个真实原生窗口（AWT 已初始化，且已有 HWND）")
    }

    if (!noAumid) {
        // `noDisplayName`：只设 AUMID、**不**写 `DisplayName` —— 这正是修复前
        // 用户的处境（`postprocess_msi.py` 把值名写成了 `StuMate`，等于没注册），
        // 用来确认气泡标题会退化成什么。
        val ok = if (noDisplayName) AppIdentity.installAumidOnly() else AppIdentity.install()
        println("AppIdentity.install() = $ok  (AUMID=${AppIdentity.AUMID}, 注册显示名=${!noDisplayName})")
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

    // ⚠️ `tag` 是**必须**的：Windows 对「内容完全相同」的 Toast 有抑制
    // （Toast 历史里出现过的会直接跳过），不换内容会看到「气泡不弹」的假象。
    val tag = System.getProperty("stumate.probe.tag")?.let { " [$it]" } ?: ""

    val shots = ShotTool(shotDir, corner)
    println("── 第 1 条（设置页「测试通知」的原文案） ──")
    val caption = "StuMate 测试通知$tag"
    val text = "通知可用，到点会像这样提醒你。$tag"
    println("displayMessage(caption=\"$caption\", text=\"$text\")")
    val vis1 = shots.probe("after-1") {
        icon.displayMessage(caption, text, TrayIcon.MessageType.INFO)
    }
    println("气泡可见 = $vis1")

    Thread.sleep(4_000)
    println("── 第 2 条（真实提醒的文案） ──")
    val caption2 = "即将上课：高等数学$tag"
    val text2 = "教二 305  08:00 - 09:35$tag"
    println("displayMessage(caption=\"$caption2\", text=\"$text2\")")
    val vis2 = shots.probe("after-2") {
        icon.displayMessage(caption2, text2, TrayIcon.MessageType.INFO)
    }
    println("气泡可见 = $vis2")

    println("─".repeat(64))
    println("结论：气泡可见 = ${vis1 || vis2}  (第1条=$vis1, 第2条=$vis2)")
    println("─".repeat(64))

    println("停留 ${hold}s 供外部复核截图…")
    Thread.sleep(hold * 1_000)
    tray.remove(icon)
    println("结束")
}

/**
 * 自己截图 + 自己判定气泡有没有出现。
 *
 * 判据是**右下角区域的像素差分比例**：基线与「发出气泡 1.6 秒后」两张图，
 * 逐像素比 RGB，任一通道差 > 12 就算「变了」。气泡是一块不透明卡片，
 * 出现时会盖掉相当大一片像素 —— 阈值取 0.5%，远高于桌面时钟/鼠标指针的噪声。
 */
private class ShotTool(private val dir: File?, private val corner: Pair<Int, Int>) {
    private val bounds: Rectangle =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
    private var last: BufferedImage? = null

    init {
        dir?.mkdirs()
    }

    /** 先取基线，再执行 [action]，再取「之后」图，返回气泡是否可见。 */
    fun probe(name: String, action: () -> Unit): Boolean {
        val before = grab()
        save(before, "$name-before")
        action()
        // 1.6s：足够滑入动画走完，又远早于 Windows 默认的 ~5s 自动收起
        Thread.sleep(1_600)
        val after = grab()
        save(after, "$name-after")
        val ratio = diffRatio(before, after)
        println("  差分区域=${corner.first}x${corner.second} 变化像素占比=${"%.3f".format(ratio * 100)}%")
        return ratio >= 0.005
    }

    private fun grab(): BufferedImage = Robot().createScreenCapture(bounds)

    private fun save(image: BufferedImage, name: String) {
        val d = dir ?: return
        runCatching { ImageIO.write(image, "png", File(d, "$name.png")) }
    }

    private fun diffRatio(a: BufferedImage, b: BufferedImage): Double {
        val (cw, ch) = corner
        val w = minOf(cw, a.width, b.width)
        val h = minOf(ch, a.height, b.height)
        val x0 = a.width - w
        val y0 = a.height - h
        var changed = 0L
        var total = 0L
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val p = a.getRGB(x, y)
                val q = b.getRGB(x, y)
                total++
                if (channelDiff(p, q) > 12) changed++
            }
        }
        return if (total == 0L) 0.0 else changed.toDouble() / total.toDouble()
    }

    private fun channelDiff(p: Int, q: Int): Int {
        val dr = kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
        val dg = kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
        val db = kotlin.math.abs((p and 0xFF) - (q and 0xFF))
        return maxOf(dr, dg, db)
    }
}
