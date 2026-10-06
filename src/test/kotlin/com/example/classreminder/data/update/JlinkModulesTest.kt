package com.example.classreminder.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore

/**
 * 守着「打包用的 jlink 模块集」这个**只在打包产物里才会暴露**的坑。
 *
 * ## 为什么值得单开一个测试
 *
 * 缺模块的症状是**静默的**，而且**本地跑不出来**：
 *
 * - `./gradlew run` / IDE 用的是系统 JDK 的完整模块集，`KeyStore.getInstance("Windows-ROOT")`
 *   正常；只有 jpackage 那个裁剪过的 runtime 会抛 `KeyStoreException`。
 * - 而 `UpdateCenter.sslContext()` 里 `runCatching` 把异常吞了 → 返回 null →
 *   退回默认 SSLContext。用户看到的只有一句 `PKIX path building failed`，
 *   **完全指不出根因**（实测就是这么查了半天，以为是证书问题）。
 *
 * 所以这里用「跨文件断言」把 build.gradle.kts 的 `modules(...)` 和运行时的真实需要绑起来 ——
 * 与 `AppIdentityTest` 断言 `tools/postprocess_msi.py` 是同一个套路。
 */
class JlinkModulesTest {

    @Test
    fun `jlink 模块集必须带上 jdk_crypto_mscapi`() {
        val modules = jlinkModules()
        assertNotNull("没找到 build.gradle.kts 里的 modules(...) 块 —— 解析逻辑要跟着改", modules)

        assertTrue(
            "缺 jdk.crypto.mscapi → SunMSCAPI provider 不注册 → " +
                "KeyStore.getInstance(\"Windows-ROOT\") 抛异常被静默吞掉 → " +
                "打包产物的「检查更新」只剩一句 PKIX path building failed。" +
                "实际模块集：$modules",
            modules!!.contains("jdk.crypto.mscapi")
        )
    }

    @Test
    fun `解析出的模块集不是空壳（反向守卫）`() {
        // 如果上面的正则哪天只匹配到空白，`contains` 恒为 false 会「因为错误的原因」失败，
        // 或者反过来匹配到一整份文件会「因为错误的原因」通过。两条都要挡住。
        val modules = jlinkModules()
        assertNotNull(modules)
        assertTrue("模块数太少，八成是解析错了：$modules", modules!!.size >= 5)
        assertTrue(
            "java.sql 是已知必需项（缺它连窗口都起不来），解析结果里必须有：$modules",
            modules.contains("java.sql")
        )
    }

    @Test
    fun `本机能真的读到系统根证书库`() {
        assumeTrue("非 Windows 没有 Windows-ROOT 证书库", System.getProperty("os.name").startsWith("Windows"))

        val ctx = UpdateCenter.sslContext()

        assertNotNull(
            "sslContext() 返回 null —— 说明 JDK cacerts 或系统根证书库没读成，" +
                "打包产物上就会退化成 PKIX 失败。诊断：${UpdateCenter.sslDiagnostics}",
            ctx
        )
        // 形状必须是 `jdk=<n> os=<m>`，且两边都非 0。
        // 只看「不为 null」不够 —— os=0 的路径会被上面那个分支提前拦掉，
        // 但「os 读到了 1 条」和「读到了 73 条」对排障是天壤之别，所以把数字断言出来。
        val diag = UpdateCenter.sslDiagnostics
        val m = Regex("""^jdk=(\d+) os=(\d+)$""").find(diag)
        assertNotNull("诊断串形状不对，实际：$diag", m)
        assertTrue("JDK cacerts 一条都没有？实际：$diag", m!!.groupValues[1].toInt() > 0)
        assertTrue("系统根证书库一条都没有？实际：$diag", m.groupValues[2].toInt() > 0)
    }

    @Test
    fun `Windows-ROOT 这个 KeyStore 类型在运行时确实可用`() {
        assumeTrue("非 Windows", System.getProperty("os.name").startsWith("Windows"))
        // 这条跑的是**本进程**的模块集（测试进程 = 全量 JDK），所以它一定通过 ——
        // 它的价值是把「这个 API 的名字/语义」钉住：真正会缺模块的是打包产物，
        // 那由上面的 build.gradle.kts 断言守。
        val ks = KeyStore.getInstance("Windows-ROOT")
        ks.load(null, null)
        assertTrue("Windows-ROOT 里居然没有任何证书", ks.size() > 0)
    }

    /** 从 build.gradle.kts 的 `modules(...)` 里抠出模块名列表；找不到返回 null */
    private fun jlinkModules(): List<String>? {
        val gradle = findFile("build.gradle.kts") ?: return null
        val text = gradle.readText(Charsets.UTF_8)
        val block = Regex("""modules\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return null
        return Regex("""["']([^"']+)["']""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun findFile(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth++ < 6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
