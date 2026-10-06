package com.example.classreminder.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 守着「补丁包能不能把 `runtime/` 也换掉」这条链路。
 *
 * ## 为什么需要
 *
 * 1.6.1 的教训是双重的：裁剪模块集漏了 `jdk.crypto.mscapi`（由 [JlinkModulesTest] 守），
 * **而且补丁包根本不带 `runtime/`** —— 于是修好代码也送不到用户手上。
 * 这里守的是后半截。
 *
 * 涉及三个容易写错、且错了很难从现象反推的点：
 *
 * 1. **模块集解析**：`MODULES=""`（空）必须解析成 null 而不是空列表 ——
 *    空列表会被当成「一个模块都没有」，跟本地一比就误判成「模块集变了」，
 *    每次更新都白白多下 48 MB。
 * 2. **换不换的判定**：`patchModules == null`（老补丁包）必须**不换**，
 *    否则老包会被当成「模块集从 0 个变成 N 个」。
 * 3. **替换顺序**：`runtime/release` 必须**最后**落盘。它是自检唯一的依据，
 *    先落盘而 `modules` 换失败 → 安装会「谎报健康」。
 */
class RuntimePatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 模块集解析 ──────────────────────────────────────────────────

    @Test
    fun `解析 runtime release 里的 MODULES 行`() {
        val text = """
            |JAVA_VERSION="17.0.12"
            |MODULES="java.base java.xml java.sql jdk.crypto.mscapi"
            |OS_NAME="Windows"
        """.trimMargin()
        assertEquals(
            listOf("java.base", "java.xml", "java.sql", "jdk.crypto.mscapi"),
            UpdateCenter.runtimeModules(text)
        )
    }

    @Test
    fun `MODULES 是空串时返回 null 而不是空列表`() {
        // 这条是**行为契约**，不是吹毛求疵：返回空列表会让 needRuntimeSwap 判定为
        // 「模块集变了」，于是每次更新都要多下 48 MB —— 而且只在某些异常产物上复现。
        assertNull(UpdateCenter.runtimeModules("MODULES=\"\""))
        assertNull(UpdateCenter.runtimeModules("MODULES=\"   \""))
    }

    @Test
    fun `没有 MODULES 行时返回 null`() {
        assertNull(UpdateCenter.runtimeModules("JAVA_VERSION=\"17.0.12\"\nOS_NAME=\"Windows\"\n"))
        assertNull(UpdateCenter.runtimeModules(""))
    }

    // ── 换不换的判定 ────────────────────────────────────────────────

    @Test
    fun `模块集不同时必须换`() {
        assertTrue(
            UpdateCenter.needRuntimeSwap(
                listOf("java.base", "jdk.crypto.mscapi"),
                listOf("java.base")
            )
        )
    }

    @Test
    fun `模块集相同时不换（省下 48 MB）`() {
        val same = listOf("java.base", "java.xml", "java.sql")
        assertFalse(UpdateCenter.needRuntimeSwap(same, same))
    }

    @Test
    fun `补丁包没带 runtime 信息时不换`() {
        // 1.6.1 及之前的补丁包只有 app/ 下两个文件。拿这种包更新时不能
        // 误判成「模块集从 0 个变成 N 个」。
        assertFalse(UpdateCenter.needRuntimeSwap(null, listOf("java.base")))
        assertFalse(UpdateCenter.needRuntimeSwap(null, null))
    }

    @Test
    fun `本地读不出 release 时要换（安装不完整）`() {
        assertTrue(UpdateCenter.needRuntimeSwap(listOf("java.base"), null))
    }

    // ── 本地 release 读取 ───────────────────────────────────────────

    @Test
    fun `读不到 runtime release 时返回 null`() {
        val root = tmp.newFolder("no-runtime")
        assertNull(UpdateCenter.localRuntimeModules(root))
    }

    @Test
    fun `能从安装目录读出模块集`() {
        val root = tmp.newFolder("install")
        val runtime = File(root, "runtime").apply { mkdirs() }
        File(runtime, "release").writeText("MODULES=\"java.base java.sql\"", Charsets.UTF_8)
        assertEquals(listOf("java.base", "java.sql"), UpdateCenter.localRuntimeModules(root))
    }

    // ── 替换 ────────────────────────────────────────────────────────

    @Test
    fun `替换 runtime 文件并留下 old 备份`() {
        val root = tmp.newFolder("install")
        val old = File(root, "runtime/lib/modules").apply {
            parentFile!!.mkdirs()
            writeText("OLD-MODULES", Charsets.UTF_8)
        }

        val src = tmp.newFolder("src")
        val freshModules = File(src, "modules").apply { writeText("NEW-MODULES", Charsets.UTF_8) }
        val freshRelease = File(src, "release").apply { writeText("MODULES=\"java.sql\"", Charsets.UTF_8) }

        val done = UpdateCenter.replaceRuntimeFiles(
            root,
            mapOf(
                "runtime/lib/modules" to freshModules,
                "runtime/release" to freshRelease,
            )
        )

        assertEquals("NEW-MODULES", old.readText(Charsets.UTF_8))
        assertEquals(
            "MODULES=\"java.sql\"",
            File(root, "runtime/release").readText(Charsets.UTF_8)
        )
        assertEquals(
            "旧文件必须留一份 .old —— 出问题时要能手工回滚",
            "OLD-MODULES",
            File(root, "runtime/lib/modules.old").readText(Charsets.UTF_8)
        )
        assertEquals(setOf("runtime/lib/modules", "runtime/release"), done.toSet())
    }

    @Test
    fun `release 必须最后落盘`() {
        // 它是「这次替换整体成功」的提交标记 —— runtimeGapOfSelf 只看它。
        // 先落盘而 modules 换失败 → 安装会谎报健康，比明显坏掉更难查。
        val root = tmp.newFolder("install")
        File(root, "runtime").mkdirs()

        val src = tmp.newFolder("src")
        val freshModules = File(src, "modules").apply { writeText("NEW", Charsets.UTF_8) }
        val freshRelease = File(src, "release").apply { writeText("REL", Charsets.UTF_8) }

        // 传入顺序刻意把 release 放前面，验证实现会自己排到最后
        val done = UpdateCenter.replaceRuntimeFiles(
            root,
            mapOf(
                "runtime/release" to freshRelease,
                "runtime/lib/modules" to freshModules,
            )
        )

        assertEquals(
            "release 不是最后一个落的盘，实际顺序：$done",
            "runtime/release",
            done.last()
        )
    }

    @Test
    fun `目标目录不存在时会自动建出来`() {
        val root = tmp.newFolder("fresh")
        val src = tmp.newFolder("src2")
        val f = File(src, "modules").apply { writeText("X", Charsets.UTF_8) }

        UpdateCenter.replaceRuntimeFiles(root, mapOf("runtime/lib/modules" to f))

        assertTrue(File(root, "runtime/lib/modules").isFile)
        assertEquals("X", File(root, "runtime/lib/modules").readText(Charsets.UTF_8))
    }
}
