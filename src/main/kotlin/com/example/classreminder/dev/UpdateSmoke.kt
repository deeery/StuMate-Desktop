package com.example.classreminder.dev

import com.example.classreminder.data.update.UpdateCenter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * 桌面端「自动更新」端到端冒烟 —— **连真实 GitHub Release**。
 *
 * ## 为什么不能只靠截图验收
 *
 * 「检查到有新版本」可以截图（`-Ppreview=update`），但**「下载 → 校验 → 就地替换」
 * 截图截不出来**：那几步没有 UI 状态，只有磁盘上多了一个 jar、cfg 里少了一行旧名字。
 * 而验收环境鼠标注入不可用（`SetCursorPos` / `mouse_event` 全被系统忽略），
 * 也没法点「立即更新」。
 *
 * 所以换成**可断言**的形式：把真实安装目录复制一份，对着副本跑一次真正的替换，
 * 然后逐条检查磁盘状态。这也顺带满足项目里那条「优先做成可断言的形式」。
 *
 * ## 怎么跑
 *
 * ```
 * ./gradlew updateSmoke                 # 自动找已构建的目录版产物
 * ./gradlew updateSmoke -Pdist=<路径>   # 指定目录版根目录（含 StuMate.exe 的那层）
 * ./gradlew updateSmoke -Pkeep          # 跑完不删临时副本，方便自己去看
 * ```
 *
 * ## 它断言什么
 *
 * 1. 真实 Release 的 tag 能被解析、资产能被找到、sha256 对得上（下载本身过了校验）；
 * 2. `app/` 下多出一个**文件名带新版本号**的主 jar；
 * 3. `app/StuMate.cfg` 的 `app.classpath=` 指向那个新 jar；
 * 4. cfg 的**行数不变**，其余 37 行依赖原样保留（只换主 jar 那一行）；
 * 5. 旧 cfg 备份成 `StuMate.cfg.bak`；
 * 6. `StuMate.cfg.new` 这种中间文件**没留下**（说明走的是原子改名）。
 *
 * 跑法：`./gradlew updateSmoke`
 */
fun main() = runBlocking {
    val srcRoot = File(
        System.getProperty("stumate.dist")
            ?: listOf(
                "F:/DownloadQQ/stumate-altbuild/compose/binaries/main/app/StuMate",
                File("build/compose/binaries/main/app/StuMate").absolutePath
            ).firstOrNull { File(it, "StuMate.exe").isFile }
            ?: error("找不到目录版产物，用 -Pdist=<路径> 指定")
    )

    println("═".repeat(72))
    println(" StuMate 桌面端自动更新冒烟（真实 GitHub Release）")
    println(" 源安装目录：$srcRoot")
    println(" 当前版本  ：${UpdateCenter.currentVersion}")
    println("═".repeat(72))

    val work = File(System.getProperty("java.io.tmpdir"), "StuMate-updateSmoke").apply {
        deleteRecursively()
        mkdirs()
    }
    val root = File(work, "StuMate")
    println("\n① 复制安装目录到临时区（绝不碰用户正在用的那份）")
    srcRoot.copyRecursively(root, overwrite = true)
    val appDir = File(root, "app")
    val cfg = File(appDir, "StuMate.cfg")

    // 把副本伪装成「用户当前装的是旧版」。
    //
    // 不伪装的话，副本里已经是**最新版**的 jar，而补丁包里也是同一个 jar ——
    // 「替换」这一步会变成把同名文件写回去，磁盘上看不出任何变化，
    // 断言「新旧 jar 不是同一个文件」必然失败（第一次跑就是这么翻车的）。
    // 改个旧版本号的文件名 + 同步改 cfg，就得到一个和真实老用户**结构完全一致**的
    // 安装目录：`app/` 里躺着一个旧版本名的主 jar，cfg 指着它。
    val realJar = mainJars(appDir).singleOrNull()
        ?: error("$appDir 下没有主 jar，不是 jpackage 目录版产物")
    // 只把**版本号那一段**换掉，保留 hash 与 .jar 后缀。
    // 名字形状是 `StuMate-Desktop-<版本>-<hash>.jar`（见 tools/release.py 的 main_jar()）。
    // 两种错法都踩过：`replaceAfter(...)` 会把后缀一起吃掉（改名成没有 .jar 的名字，
    // 于是 mainJars() 一个都匹配不到）；`replace("StuMate-Desktop-", "…-1.5.0-")`
    // 只是插入，得到 `…-1.5.0-1.6.0-<hash>.jar`（版本号还在，断言照样没意义）。
    val hash = realJar.removeSuffix(".jar").substringAfterLast('-')
    val fakeOldJar = "StuMate-Desktop-$OLD_VERSION-$hash.jar"
    println("   把主 jar 改名为旧版本：$realJar → $fakeOldJar")
    File(appDir, realJar).renameTo(File(appDir, fakeOldJar))
    cfg.writeText(
        cfg.readText(Charsets.UTF_8).replace(realJar, fakeOldJar),
        Charsets.UTF_8
    )

    val beforeJars = mainJars(appDir)
    val beforeCfg = cfg.readText(Charsets.UTF_8)
    val beforeLines = beforeCfg.lines()
    println("   jar  : ${beforeJars.joinToString()}")
    println("   cfg  : ${beforeLines.size} 行，classpath 行 = " +
            beforeLines.first { it.startsWith("app.classpath=") }.take(90) + "…")

    println("\n② 走真实代码：查 Release → 下载补丁 → 校验 → 就地替换")
    val t0 = System.currentTimeMillis()
    val newJar = UpdateCenter.smokeApplyPatchTo(root)
    println("   完成，用时 ${System.currentTimeMillis() - t0} ms")

    println("\n③ 断言磁盘状态")
    val afterJars = mainJars(appDir)
    val afterCfg = cfg.readText(Charsets.UTF_8)
    val afterLines = afterCfg.lines()

    val checks = mutableListOf<Pair<String, Boolean>>()

    checks += "app/ 下出现新的主 jar：$newJar" to (File(appDir, newJar).isFile)
    checks += "新旧 jar 不是同一个文件" to (newJar !in beforeJars)
    checks += "cfg 的 classpath 指向新 jar" to
            afterLines.any { it.startsWith("app.classpath=") && it.endsWith(newJar) }
    checks += "cfg 行数不变（${beforeLines.size} → ${afterLines.size}）" to
            (beforeLines.size == afterLines.size)
    checks += "其余依赖行原样保留" to
            (beforeLines.filterNot { it.startsWith("app.classpath=") } ==
                    afterLines.filterNot { it.startsWith("app.classpath=") })
    checks += "旧 cfg 备份成 StuMate.cfg.bak" to File(appDir, "StuMate.cfg.bak").isFile
    checks += "没留下 StuMate.cfg.new 中间文件" to !File(appDir, "StuMate.cfg.new").exists()
    checks += "新 jar 大小合理（> 1 MB）" to (File(appDir, newJar).length() > 1_000_000)
    checks += "新 jar 里能读到主类" to runCatching {
        java.util.zip.ZipFile(File(appDir, newJar)).use { z ->
            z.getEntry("com/example/classreminder/MainKt.class") != null
        }
    }.getOrDefault(false)

    var pass = 0
    checks.forEachIndexed { i, (name, ok) ->
        println("   ${if (ok) "✅" else "❌"} ${i + 1}. $name")
        if (ok) pass++
    }

    println("\n④ 产物指纹")
    println("   替换前 jar：${beforeJars.joinToString()}")
    println("   替换后 jar：${afterJars.joinToString()}")
    println("   新 jar sha256：${sha256(File(appDir, newJar))}")

    if (!System.getProperty("stumate.keep").isNullOrBlank()) {
        println("\n（-Pkeep 生效，临时副本保留在 $work）")
    } else {
        work.deleteRecursively()
    }

    println("\n" + "═".repeat(72))
    println(" $pass/${checks.size} 断言通过")
    println("═".repeat(72))
    if (pass != checks.size) kotlin.system.exitProcess(1)
}

private fun mainJars(appDir: File): List<String> =
    appDir.listFiles { f -> f.isFile && f.name.startsWith("StuMate-Desktop-") && f.name.endsWith(".jar") }
        ?.map { it.name }?.sorted() ?: emptyList()

/** 伪装成老用户时装的那个版本号。随便挑一个比线上低的即可 */
private const val OLD_VERSION = "1.5.0"

private fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
