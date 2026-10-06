package com.example.classreminder.dev

import com.example.classreminder.data.update.UpdateCenter
import kotlinx.coroutines.runBlocking

/**
 * 「检查更新」这条链路的**运行时探针** —— 只回答一个问题：
 * 当前这个 JVM（尤其是**打包用的 jlink 裁剪运行时**）能不能真的连上 GitHub。
 *
 * ## 为什么必须单独探
 *
 * `UpdateCenter.sslContext()` 要把**系统根证书库**并进信任链，才能对付
 * SteamTools 这类「加速 GitHub」的中间人工具。而读系统库要
 * `KeyStore.getInstance("Windows-ROOT")` —— 它由 `jdk.crypto.mscapi`
 * 模块提供。**打包时漏了这个模块，异常会被 `runCatching` 吞掉**，
 * 静默退回默认 SSLContext，用户只看到一句 `PKIX path building failed`。
 *
 * 关键：`./gradlew run` 用的是全量 JDK，**永远看不出这个问题** ——
 * 只有换到打包产物的 runtime 才暴露。所以这个探针要经
 * `StuMate.exe`（jpackage 启动器）跑才有意义：
 *
 * ```bash
 * # 复制一份 createDistributable 的输出，把 cfg 的 mainclass 换成这个类
 * # 再往 [JavaOptions] 追加 -Dstdout.encoding=UTF-8
 * ./StuMate.exe
 * ```
 *
 * 输出里的 `sslDiagnostics` 是关键：
 *  - `jdk=<n> os=<m>`            → 合并成功，两边各多少条根证书
 *  - `构建失败：KeyStoreException: Windows-ROOT not found`
 *                                → **就是缺 `jdk.crypto.mscapi`**
 */
fun main() = runBlocking {
    println("─".repeat(64))
    println("更新检查探针")
    println("  java.home        = ${System.getProperty("java.home")}")
    println("  java.version     = ${System.getProperty("java.version")}")
    println("  file.encoding    = ${System.getProperty("file.encoding")}")
    // 构建 http 客户端之前先看一眼：此时 sslContext() 还没被调用
    println("  sslDiagnostics   = ${UpdateCenter.sslDiagnostics}   ← 尚未构建")
    println("─".repeat(64))

    UpdateCenter.check(manual = true)

    println("  sslDiagnostics   = ${UpdateCenter.sslDiagnostics}")
    println("  state            = ${UpdateCenter.state.value}")
    println("─".repeat(64))

    val ok = UpdateCenter.sslDiagnostics.startsWith("jdk=")
    println(if (ok) "√ 系统根证书库已并进信任链" else "× 系统根证书库没读进来 —— 打包模块集缺 jdk.crypto.mscapi")
}
