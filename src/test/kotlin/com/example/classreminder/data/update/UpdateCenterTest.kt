package com.example.classreminder.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查里的纯逻辑。
 *
 * 这些函数决定了「要不要提示更新」和「能不能走补丁包」——
 * 判断错了要么天天误报、要么把依赖变了的新版本按补丁装下去（应用起不来）。
 * 所以它们必须能在没有网络、没有安装目录的环境里单独验。
 */
class UpdateCenterTest {

    // ── 版本比较 ────────────────────────────────────────────────────

    @Test
    fun `去掉 tag 前缀与预发布后缀`() {
        assertEquals("1.6.0", UpdateCenter.normalizeVersion("v1.6.0"))
        assertEquals("1.6.0", UpdateCenter.normalizeVersion("V1.6.0"))
        assertEquals("1.6.0", UpdateCenter.normalizeVersion("  1.6.0  "))
        assertEquals("1.6.0", UpdateCenter.normalizeVersion("v1.6.0-beta.1"))
        assertEquals("1.6.0", UpdateCenter.normalizeVersion("v1.6.0+build.7"))
    }

    @Test
    fun `按段比较而不是按字符串比较`() {
        // 字符串比较会认为 "1.10.0" < "1.9.0"，这正是这类 bug 的经典来源
        assertTrue(UpdateCenter.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateCenter.compareVersions("1.9.0", "1.10.0") < 0)
        assertTrue(UpdateCenter.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateCenter.compareVersions("1.6.1", "1.6.0") > 0)
        assertTrue(UpdateCenter.compareVersions("1.6.0", "1.6.1") < 0)
        assertEquals(0, UpdateCenter.compareVersions("1.6.0", "1.6.0"))
    }

    @Test
    fun `缺失的段按 0 算`() {
        assertEquals(0, UpdateCenter.compareVersions("1.6", "1.6.0"))
        assertEquals(0, UpdateCenter.compareVersions("1.6.0", "1.6"))
        assertEquals(0, UpdateCenter.compareVersions("v1.6", "1.6.0.0"))
        assertTrue(UpdateCenter.compareVersions("1.6.1", "1.6") > 0)
    }

    @Test
    fun `两边都带前缀也能比`() {
        assertTrue(UpdateCenter.compareVersions("v1.7.0", "1.6.0") > 0)
        assertEquals(0, UpdateCenter.compareVersions("v1.6.0", "1.6.0"))
    }

    @Test
    fun `认不出的版本号退化成 0 而不是抛异常`() {
        // 用户可能手改过 settings / 或者上游 tag 起得随意，
        // 这时候「当成 0」比崩掉好 —— 0 一定小于当前版本，不会误报更新。
        assertFalse(UpdateCenter.compareVersions("nightly", "1.6.0") > 0)
        assertEquals(0, UpdateCenter.compareVersions("", ""))
    }

    // ── 文件名反解版本 ──────────────────────────────────────────────

    @Test
    fun `从主 jar 名里抠出版本号`() {
        assertEquals(
            "1.6.0",
            UpdateCenter.versionOfJar("StuMate-Desktop-1.6.0-9b2fff7049d831e92a1f8a2b4b128.jar")
        )
        assertEquals("1.5.0", UpdateCenter.versionOfJar("StuMate-Desktop-1.5.0-abc.jar"))
    }

    @Test
    fun `从补丁包名里抠出版本号`() {
        assertEquals("1.6.0", UpdateCenter.patchVersionOf("StuMate-patch-1.6.0.zip"))
    }

    @Test
    fun `版本号里带连字符也不会被截断`() {
        // 带 hash 的主 jar 名就是「版本 + '-' + hash」，所以不能简单地 split('-').first()
        assertEquals("1.6.0", UpdateCenter.versionOfJar("StuMate-Desktop-1.6.0-abc123.jar"))
        // 而依赖 jar 不该被误认成主 jar —— versionOfJar 只对主 jar 有意义，
        // 这里断言的是「解析规则本身不依赖文件名里恰好只有一个连字符」
        assertEquals("1.6.0", UpdateCenter.patchVersionOf("StuMate-patch-1.6.0.zip"))
    }

    // ── cfg 解析：能不能走补丁包 ─────────────────────────────────────

    private val localCfg = """
        [Application]
        app.classpath=${'$'}APPDIR\StuMate-Desktop-1.5.0-9b2fff7049d831e92a1f8a2b4b128.jar
        app.mainclass=com.example.classreminder.MainKt
        app.classpath=${'$'}APPDIR\animation-core-desktop-1.5.10-c65799cd.jar
        app.classpath=${'$'}APPDIR\kotlin-stdlib-1.9.20-aaa.jar
    """.trimIndent()

    @Test
    fun `依赖行不含主 jar 自己`() {
        val deps = UpdateCenter.dependencyLines(localCfg)
        assertEquals(2, deps.size)
        assertFalse(deps.any { it.contains("StuMate-Desktop-") })
        assertTrue(deps.any { it.contains("animation-core-desktop") })
        assertTrue(deps.any { it.contains("kotlin-stdlib") })
    }

    @Test
    fun `只换版本号的 cfg 依赖行完全相同 —— 可以走补丁`() {
        val patchCfg = localCfg.replace("1.5.0-9b2fff7049d831e92a1f8a2b4b128", "1.6.0-ccc111")
        assertEquals(
            UpdateCenter.dependencyLines(localCfg),
            UpdateCenter.dependencyLines(patchCfg)
        )
    }

    @Test
    fun `依赖变了就判为不能走补丁`() {
        // 这一条是补丁包的守门人：Compose / Kotlin 版本一升，
        // 补丁只换主 jar 就不够了，必须整包重装。
        val patchCfg = localCfg.replace(
            "animation-core-desktop-1.5.10-c65799cd.jar",
            "animation-core-desktop-1.7.0-ddd222.jar"
        )
        assertTrue(
            UpdateCenter.dependencyLines(localCfg) != UpdateCenter.dependencyLines(patchCfg)
        )
    }

    @Test
    fun `依赖多一个少一个也算变`() {
        val added = localCfg + "\napp.classpath=${'$'}APPDIR\\new-dep-1.0.jar"
        assertTrue(
            UpdateCenter.dependencyLines(localCfg) != UpdateCenter.dependencyLines(added)
        )
    }

    // ── 改写 cfg ────────────────────────────────────────────────────

    @Test
    fun `按行改写只动主 jar 那一行`() {
        val newName = "StuMate-Desktop-1.6.0-ccc111.jar"
        val rewritten = localCfg.lines().joinToString("\n") { line ->
            if (line.startsWith("app.classpath=") && line.contains("StuMate-Desktop-")) {
                "app.classpath=${'$'}APPDIR\\$newName"
            } else line
        }
        assertTrue(rewritten.contains(newName))
        assertFalse(rewritten.contains("1.5.0-9b2fff"))
        // 其余行原样
        assertTrue(rewritten.contains("app.mainclass=com.example.classreminder.MainKt"))
        assertTrue(rewritten.contains("animation-core-desktop-1.5.10-c65799cd.jar"))
        // 行数不变 —— 少一行就等于丢了依赖
        assertEquals(localCfg.lines().size, rewritten.lines().size)
    }

    // ── 安装形态识别 ────────────────────────────────────────────────

    @Test
    fun `测试进程里识别不出安装目录`() {
        // 单测跑的是 build/classes 下的 class 文件，不是 jar，
        // 所以 detectInstall() 必须返回 null —— 这正是「开发模式不适用」那条路径。
        assertNull(UpdateCenter.detectInstall())
        assertTrue(UpdateCenter.selfInstallBlocker()!!.contains("开发模式"))
    }

    @Test
    fun `进度百分比在总长未知时不乱报`() {
        assertEquals(-1, UpdateState.Downloading(100, 0).percent)
        assertEquals(-1, UpdateState.Downloading(100, -1).percent)
        assertEquals(0, UpdateState.Downloading(0, 200).percent)
        assertEquals(50, UpdateState.Downloading(100, 200).percent)
        assertEquals(100, UpdateState.Downloading(200, 200).percent)
    }
}
