import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.10"
}

group = "com.example.classreminder"

// 版本的**唯一来源**。改版本只改这一行：
//  - `packageVersion`（下面 nativeDistributions 用它）跟着走
//  - 下面的 `generateBuildConfig` 任务据此生成 `BuildConfig.VERSION`
//  - 设置页「版本」那一行读 `BuildConfig.VERSION`，不会再和实际版本对不上
version = "1.4.0"

kotlin {
    jvmToolchain(17)
}

// ── 生成 BuildConfig ─────────────────────────────────────────────────
//
// 为什么手写而不是用插件：Kotlin JVM 插件**不生成** BuildConfig（那是 AGP 的功能），
// `kotlin("jvm")` 下没有 `buildConfig { }` 配置块，写了会直接
// `Unresolved reference: buildConfig`。所以这里用最朴素的办法：
// 一个普通的 Task 生成一个 Kotlin 源文件，扔进 generated 源码目录。
//
// 代价是设置页的「版本」不再是编译期常量（改版本要重新编译），
// 但它本来就只在运行期显示一次，这个代价可以忽略。
//
// ⚠️ 目录**不能**放在 `layout.buildDirectory` 下。我们为了与并行会话隔离构建，
// 把 buildDirectory 指到了 F 盘的 altbuild，而项目源码在 C 盘 ——
// Kotlin 增量编译的 `RelocatableFileToPathConverter.relativeTo` 遇到
// 「同根目录」假设被打破时会抛
// `IllegalArgumentException: this and base files have different roots`，
// 而且它只在**增量**编译时炸，`--rerun-tasks` 或首次编译反而正常，
// 症状是「刚才还好好的，突然冒出一堆编译错误」。
// 所以这里显式用项目内的固定路径，绕开 buildDirectory 重定向。
private val buildConfigDir = File(projectDir, "build/generated/sources/buildConfig/kotlin")

val generateBuildConfig by tasks.registering {
    val outputDir = buildConfigDir
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.resolve("com/example/classreminder")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildConfig.kt").writeText(
            """
            |// 由 Gradle 的 `generateBuildConfig` 任务生成，**不要手改，也不要提交**。
            |// 每次构建都会按 build.gradle.kts 里的 `version` 重新写一遍。
            |package com.example.classreminder
            |
            |object BuildConfig {
            |    const val VERSION: String = "$versionValue"
            |}
            |
            """.trimMargin(),
            Charsets.UTF_8,
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(buildConfigDir)
tasks.named("compileKotlin") { dependsOn(generateBuildConfig) }

dependencies {
    // Compose Desktop 运行时（含 skiko 原生库）
    implementation(compose.desktop.currentOs)
    // Material 3
    implementation(compose.material3)
    // 图标：material-icons-core 在 compose.material 里是 runtime 作用域，
    // 编译期不可见，必须显式声明（注意 CMP 1.5.10 无 Icons.AutoMirrored）
    implementation("org.jetbrains.compose.material:material-icons-core:1.5.10")
    // androidx.compose.ui.util.lerp —— 源码里的高亮插值用到了，默认不在 classpath 上
    implementation("org.jetbrains.compose.ui:ui-util:1.5.10")
    // 桌面 Dispatchers.Main
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    // 本地 SQLite
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    // 静音 sqlite-jdbc 的 slf4j 提示
    implementation("org.slf4j:slf4j-nop:2.0.12")
    // 调 dwmapi.dll 给无边框窗口加 Windows 11 原生圆角与描边
    // （无边框窗口 Win11 默认是直角，必须用 DWMWA_WINDOW_CORNER_PREFERENCE 显式开启）
    implementation("net.java.dev.jna:jna:5.6.0")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.example.classreminder.MainKt"

        nativeDistributions {
            // Dmg 是 macOS 的，本项目只面向 Windows。
            //
            // 两种产物的性质完全不同，别混为一谈：
            //  - Exe：jpackage 的**安装引导程序**（双击弹安装向导），不是绿色版。
            //    想要免安装直接跑，得用 `createDistributable` 出的目录版。
            //  - Msi：真正的安装包，还能进「程序和功能」列表。
            //
            // ## MSI 为什么默认关着
            // MSI 依赖 WiX 工具链，而 JDK **不自带**。缺WiX 时插件会走
            // `:downloadWix` 去 GitHub 拉 `wix311-binaries.zip`，
            // 在走 HTTPS 代理的网络下会直接挂在 PKIX 证书校验上：
            //   SSLHandshakeException: unable to find valid certification path
            //（注意这不是「网络不通」—— curl 走同一个代理是通的，
            //   是 Java 信任库不认代理的证书。）
            //
            // 两条出路，优先用第一条：
            //  1. 装 WiX 后设 **WIX_PATH** 环境变量指向它，插件就完全跳过下载：
            //       WIX_PATH="C:\Program Files (x86)\WiX Toolset v3.14" ./gradlew packageMsi -PwithMsi=true
            //     已验证 v3.14 可用（插件自己只想用 3.11.2，但 candle/light 向后兼容）。
            //     ⚠️ WIX_PATH 是**环境变量**，Gradle daemon 是长驻进程，
            //        改完必须先`./gradlew --stop`，否则 daemon 拿不到新值。
            //  2. 自己下好 wix311-binaries.zip，放到 ~/.gradle/compose-jb/wix311.zip
            //     （文件名固定，插件 `:unzipWix` 按名字找）。
            //
            // 默认只出 exe：它零外部依赖，任何机器上都能出；
            // MSI 是可选产物，不该把 exe 的构建一起拖死。
            val withMsi = providers.gradleProperty("withMsi").orNull?.toBoolean() ?: false
            if (withMsi) targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            else targetFormats(TargetFormat.Exe)
            packageName = "StuMate"
            packageVersion = project.version.toString()
            description = "StuMate 桌面课表提醒"
            vendor = "StuMate"
        }
    }
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// ── 开发辅助任务 ────────────────────────────────────────────────────
//
// 这两个任务不属于产品构建产物，只给开发/验收用。
// 单测不联网，所以「客户端到底能不能连上服务端」必须靠 apiSmoke 单独验证。

/** 连**真实服务端**跑一遍账号接口（只读为主，不建账号）。见 dev/ApiSmoke.kt */
tasks.register<JavaExec>("apiSmoke") {
    group = "verification"
    description = "连真实服务端冒烟测试账号接口"
    mainClass.set("com.example.classreminder.dev.ApiSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    // 输出里有 ✓ / ✗ / — 这些非 GBK 字符，不强制 UTF-8 会在中文 Windows 上变成乱码
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

/**
 * 把运行期 classpath 打印出来。
 *
 * 给 `tools/shoot_scenarios.py` 用：它拿这个 classpath 直接 `java -cp ... UiPreviewKt`，
 * 省掉每次十几秒的 Gradle 启动开销（批量截图要跑十几次）。
 */
tasks.register("printRuntimeClasspath") {
    group = "verification"
    description = "打印主源码集的运行期 classpath"
    doLast { println("RUNTIME_CLASSPATH=" + sourceSets["main"].runtimeClasspath.asPath) }
}

/**
 * 云同步端到端冒烟（连真实服务端）。见 dev/SyncSmoke.kt
 *
 * 需要一个邀请码（每轮要建 2 个新账号，所以码得够用）：
 * ```
 * ./gradlew syncSmoke -Pinvite=XXXX-XXXX-XXXX
 * ```
 *
 * ## 怎么跑
 *
 * 推荐：给两个**固定账号**走登录（登录不受注册限流，可以随便重跑）：
 * ```
 * ./gradlew syncSmoke -PemailA=a@x.com -PemailB=b@x.com -Ppassword=…
 * ```
 * 固定账号只需用注册接口各建一次。
 *
 * 一次性跑（每次消耗 2 次**注册**额度）：
 * ```
 * ./gradlew syncSmoke -Pinvite=XXXX-XXXX-XXXX
 * ```
 *
 * ⚠️ 注册接口按 IP 限流 **10 次 / 24 小时**（`lib/stumate/ratelimit.ts` 的
 * `registerIpLimited`），一轮冒烟烧 2 次，跑 5 轮就撞墙；撞墙后服务端一律回
 * `429 RATE_LIMITED`，令牌拿不到 → 全部用例静默跳过还报 BUILD SUCCESSFUL。
 *
 * ## 两个必须知道的通道问题
 *
 * 1. **必须走 `-P`，不能走环境变量**：Gradle 守护进程是长驻进程，
 *    `System.getenv()` 拿到的是它**启动时**的环境，`VAR=x ./gradlew` 改的
 *    只是 gradlew 客户端进程的环境，永远传不进 daemon。
 * 2. **必须用 `jvmArgs` 而不是 `args`**：`args` 是应用参数（拼在 main 后面），
 *    `-D` 走那条路 JVM 不认，程序里 `System.getProperty` 读到 null。
 *
 * 两条都踩过 —— 表现同样是「冒烟静默全跳过还报 BUILD SUCCESSFUL」。
 */
tasks.register<JavaExec>("syncSmoke") {
    group = "verification"
    description = "连真实服务端验证云同步端到端"
    mainClass.set("com.example.classreminder.dev.SyncSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

    val invite = providers.gradleProperty("invite").orNull?.trim()
    val emailA = providers.gradleProperty("emailA").orNull?.trim()
    val emailB = providers.gradleProperty("emailB").orNull?.trim()
    val password = providers.gradleProperty("password").orNull?.trim()
    val hasFixed = !emailA.isNullOrBlank() && !emailB.isNullOrBlank()

    logger.lifecycle(
        "syncSmoke: " + when {
            hasFixed -> "用固定账号 $emailA / $emailB 走登录"
            !invite.isNullOrBlank() -> "没有固定账号，将用 -Pinvite 注册 2 个新账号"
            else -> "既无固定账号也无 -Pinvite"
        }
    )
    doFirst {
        // 静默跳过会报 BUILD SUCCESSFUL，看着像「测过了」—— 这里必须硬失败
        if (!hasFixed && invite.isNullOrBlank()) {
            throw GradleException(
                "syncSmoke 需要 -PemailA/-PemailB（走登录，推荐）或 -Pinvite=XXXX-XXXX-XXXX（走注册）"
            )
        }
    }
    listOf(
        "stumate.invite" to invite,
        "stumate.emailA" to emailA,
        "stumate.emailB" to emailB,
        "stumate.password" to password
    ).forEach { (key, value) ->
        if (!value.isNullOrBlank()) jvmArgs("-D$key=$value")
    }
}

/**
 * 只渲染账号相关 UI 的预览窗口，用于截图验收。见 dev/UiPreview.kt
 *
 * ⚠️ 场景通过 `-P` 传，**不能**靠环境变量：Gradle 守护进程是长驻进程，
 * `System.getenv()` 拿到的是守护进程启动时的环境，改 shell 变量根本不生效。
 *
 * ```
 * ./gradlew uiPreview -Ppreview=auth        # 登录对话框
 * ./gradlew uiPreview -Ppreview=signedin -Pemail=a@b.c -Ppassword=...
 * ./gradlew uiPreview -Ppreview=sync -Psync=override   # 同步卡：被覆盖态
 * ```
 */
tasks.register<JavaExec>("uiPreview") {
    group = "verification"
    description = "启动 UI 预览窗口（-Ppreview=<场景> 选择场景）"
    mainClass.set("com.example.classreminder.dev.UiPreviewKt")
    classpath = sourceSets["main"].runtimeClasspath
    listOf(
        "preview" to "account",
        "theme" to "dark",
        "email" to "",
        "password" to "",
        // 同步卡的预览态：offline / idle / done / busy / override / failed / skipped / preinit
        "sync" to "offline"
    ).forEach { (key, default) ->
        systemProperty("stumate.$key", project.findProperty(key) ?: default)
    }
}
