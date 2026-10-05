package com.example.classreminder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [AppIdentity] 的约束测试。
 *
 * 这里**不测** Win32 调用本身（那要有窗口站才行，CI/无头环境跑不了），
 * 只测那些真正会悄悄错掉的东西：**AUMID / 显示名的值**，以及
 * **注册表项的值名**（必须是 `DisplayName`，写成应用名等于没注册）。
 *
 * ## 为什么值得单测一个字符串常量
 *
 * AUMID 是**跨文件契约**：Kotlin 侧设进程身份，`tools/postprocess_msi.py`
 * 往注册表和快捷方式里写同一个值。两边不一致时**不会有任何报错** ——
 * 只是气泡标题从应用名退化成原始 AUMID 字符串（或者干脆不显示）。
 * 这种「不报错的错」只能靠断言拦。
 */
class AppIdentityTest {

    @Test
    fun `AUMID 与打包脚本里注册的值逐字一致`() {
        val script = findPostprocessScript()
        assumeTrue("找不到 tools/postprocess_msi.py，跳过跨文件断言", script != null)

        val text = script!!.readText(Charsets.UTF_8)
        val match = Regex("""^AUMID\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(text)
        assertNotNull("postprocess_msi.py 里应当有 AUMID 常量", match)
        assertEquals(
            "AppIdentity.AUMID 必须与 tools/postprocess_msi.py 的 AUMID 逐字一致",
            match!!.groupValues[1],
            AppIdentity.AUMID
        )
    }

    @Test
    fun `AUMID 形状正确`() {
        // AUMID 只允许「厂商.产品.版本」这种点分标识；带空格或中文会让注册表键名非法
        assertTrue(
            "AUMID 不该含空格或非 ASCII: ${AppIdentity.AUMID}",
            AppIdentity.AUMID.all { it.code in 33..126 }
        )
        assertTrue(
            "AUMID 至少要有两段（形如 厂商.产品）: ${AppIdentity.AUMID}",
            AppIdentity.AUMID.count { it == '.' } >= 1
        )
    }

    @Test
    fun `显示名与打包脚本里的一致`() {
        val script = findPostprocessScript()
        assumeTrue("找不到 tools/postprocess_msi.py，跳过跨文件断言", script != null)

        val text = script!!.readText(Charsets.UTF_8)
        val match = Regex("""^DISPLAY_NAME\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(text)
        assertNotNull("postprocess_msi.py 里应当有 DISPLAY_NAME 常量", match)
        assertEquals(
            "AppIdentity.DISPLAY_NAME 必须与 tools/postprocess_msi.py 的 DISPLAY_NAME 逐字一致",
            match!!.groupValues[1],
            AppIdentity.DISPLAY_NAME
        )
    }

    /**
     * 注册表项的**值名必须是 `DisplayName`**。
     *
     * ## 为什么这条断言最值钱
     *
     * Shell 只认 `DisplayName` 这个名字。写成别的（比如把应用名当成值名 ——
     * 这正是修之前的写法 `Name="StuMate"`）**不会报任何错**，
     * 编译过、安装过、注册表里也确实多了一项，
     * 但 Windows 找不到 `DisplayName` 就退回显示**原始 AUMID**，
     * 于是右下角通知气泡的标题变成 `StuMate.Desktop.1` —— 用户看到的就是「乱码」。
     *
     * 这个错犯过一次，而且是从 MSI 装出来的用户才看得到（便携包根本没这一步），
     * 排查成本极高。所以把它钉在单测里。
     */
    @Test
    fun `注册表项的值名必须是 DisplayName`() {
        val script = findPostprocessScript()
        assumeTrue("找不到 tools/postprocess_msi.py，跳过跨文件断言", script != null)

        val text = script!!.readText(Charsets.UTF_8)
        // 抓「AUMID 那条注册表项」的原文片段：从 Key= 起，到该元素结束的 `/>` 为止。
        //
        // ⚠️ 不能用惰性组 `(.*?)` 收尾 —— 它会**匹配空串**（长度 0 也算满足），
        // 于是断言拿到空片段、永远失败。要么锚到确定的分隔符（这里用 `/>`），
        // 要么直接按长度截取。第一版就是这么写错的。
        val block = Regex(
            """Key="Software\\\\Classes\\\\AppUserModelId.*?/>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(text)?.value
        assertNotNull("postprocess_msi.py 里应当有 AppUserModelId 的注册表项", block)
        assertTrue(
            "AppUserModelId 的注册表值名必须是 DisplayName（写成应用名等于没注册）\n实际片段：$block",
            block!!.contains("""Name="DisplayName"""")
        )
    }

    /**
     * 从测试工作目录往上找 `tools/postprocess_msi.py`。
     *
     * 不写死绝对路径：换机器 / 换 clone 目录都要能跑。
     * Gradle 的测试工作目录通常是模块目录，但用「向上找」更稳。
     */
    private fun findPostprocessScript(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth++ < 6) {
            val candidate = File(dir, "tools/postprocess_msi.py")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
