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
version = "1.5.0"

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

            // ── 品牌图标 + 快捷方式 ──
            //
            // 这些**全部**配在 `windows { }` 里，不在 `nativeDistributions` 直接层。
            //
            // ## 图标（实测踩过）
            // 不配时jpackage 会拿插件自带的 `default-icon-windows.ico` 顶上，
            // 于是资源管理器 / 桌面快捷方式 /「程序和功能」列表里全是别人家的默认图标
            //（1.5.0 就是这样）。配法是 `windows.iconFile`：
            //   - 写在 `nativeDistributions` 直接层 → 脚本编译期报
            //     `Unresolved reference: icon`（那一层没有这个成员）
            //   - 写成 `icon = ...` 属性赋值 → 同样 `Unresolved reference: icon`
            //     （`AbstractPlatformSettings` 只有 `getIconFile()`，是 Gradle 属性不是 Kotlin 属性）
            //   - 正确写法就是下面这种 `iconFile = <RegularFile>`
            //
            // `app-icon.ico` 由 `tools/render_icon.py` 从 `StuMateMark.kt` 的
            // 几何常量渲染，含 16/24/32/48/64/128/256 七档。
            // 🔴 改图标要**两边一起改**：Kotlin 那边 + render_icon.py。
            //    重新生成：`./gradlew renderAppIcon`
            //
            // ## 快捷方式（实测踩过，结论与直觉相反）
            // 我一度断定「CMP 没暴露 jpackage 的 `--win-shortcut`」——**是错的**，
            // 反编译 `compose-gradle-plugin-1.5.10.jar` 后确认链路完整存在：
            //   WindowsPlatformSettings.shortcut / menu / menuGroup
            //     → ConfigureJvmApplicationKt
            //       → AbstractJPackageTask.winShortcut / winMenu / winMenuGroup
            //         → cliArg("--win-shortcut" / "--win-menu" / "--win-menu-group")
            // 而 `WindowsPlatformSettings` 构造时**只**把 `dirChooser` 默认成 true，
            // `shortcut` 和 `menu` 的默认值都是 **false** —— 所以不显式打开，
            // 打出来的 MSI 里 `Shortcut` 表是**空的**，装完一个快捷方式都没有。
            //
            // `shortcut = true` 只保证**开始菜单**有；桌面快捷方式 jpackage 不管
            //（没有 `--win-desktop-shortcut`），要桌面快捷方式走
            // `tools/postprocess_msi.py` 里补的 `scDesktop`。
            //
            // ## 安装完成后启动
            // jpackage **没有**对应的 `--win-...` 开关，所以只能自己补 deferred
            // CustomAction，见 `tools/postprocess_msi.py`。
            windows {
                iconFile = project.layout.projectDirectory.file("app-icon.ico")
                shortcut = true
                menu = true
                menuGroup = "StuMate"
                // jpackage 不支持 `--win-desktop-shortcut`，桌面那条走后处理脚本。
            }


            // ── runtime 必须显式带上 java.sql，否则安装后一定起不来 ──
            //
            // ## 症状
            // 安装后双击，窗口**闪一下就没了**，随后弹「Failed to launch JVM」。
            // 开发时用 `./gradlew run` 或 `java -cp ... MainKt` 却完全正常。
            //
            // ## 真实原因（费了很大周折才定位，过程别再走一遍）
            // jpackage 打出来的 runtime 是 jlink **裁剪**过的，只含默认那几个模块，
            // **里面没有 `java.sql`**。于是：
            //   1. AWT frame 先创建出来 —— 所以你看得见「窗口闪过」
            //   2. 界面第一帧要落库，sqlite-jdbc 去 `Class.forName("java.sql.Driver")`
            //   3. `NoClassDefFoundError: java/sql/Driver` 从协程里抛出
            //   4. `main` 抛异常退出 → launcher 拿到非零返回码 → 弹「Failed to launch JVM」
            // 所以那句报错是**结果不是原因**，别顺着它去查 jvm.dll / jli.dll / JAVA_HOME
            // （那些全都正常，`jvm.dll` 与系统 JDK 逐字节同体积）。
            //
            // ## 为什么本地跑不出来
            // `./gradlew run` 和 `java -cp` 用的都是**系统 JDK 的完整模块集**，
            // java.sql 自然在。只有打包产物走裁剪过的 runtime 才会炸——
            // 于是「本地好好的、打包就坏」，看起来像打包 bug，其实是缺模块。
            //
            // ## 怎么确认的
            // 用 jpackage 额外打一个 `--win-console` 的 app-image（GUI 子系统的 exe
            // 拿不到 stderr），stderr 一落盘就看到上面那行NoClassDefFoundError。
            // 之后手工 jlink 一个带 java.sql 的 runtime 换进去，窗口就正常了。
            //
            // ## 下面这些模块分别给谁用
            //  - java.sql            sqlite-jdbc（课表/便签落库）—— **缺它就起不来**
            //  - java.logging        slf4j
            //  - java.naming         云同步请求头（部分 HTTP 栈要用）
            //  - java.prefs          java.util.prefs，JNA 存托盘状态
            //  - java.management     桌面端没直接用，留着给 jmx/诊断
            //  - java.xml            桌面平台配置
            //  - java.net.http       云同步用的 JDK HttpClient
            //  - jdk.unsupported     Skiko/JNA 要用的 sun.misc.Unsafe 等内部类
            modules(
                "java.sql",
                "java.logging",
                "java.naming",
                "java.prefs",
                "java.management",
                "java.xml",
                "java.net.http",
                "jdk.unsupported",
            )
        }
    }
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// ── 品牌图标渲染 ──────────────────────────────────────────────────
//
// 为什么用 Python 而不是 Kotlin 渲染：Compose 的 ImageVector 渲染链要起 Skiko
// 表面，在无头环境里不稳定（沙箱会给 GUI 进程注入 sbx.dll 导致失真）。
// 纯几何 + PIL 无 GUI 依赖、输出确定、能进 CI。
//
// 🔴 图标的**唯一真相**在 `ui/fluent/StuMateMark.kt` 的几何常量里。
//    改那个文件必须回来重跑本任务，否则 .ico 与界面里的标识会不一致。
//    本任务同时是 `packageMsi` / `createDistributable` 的前置依赖，
//    所以正常打包时不会用到过期的 .ico。
tasks.register<Exec>("renderAppIcon") {
    group = "build"
    description = "从 StuMateMark.kt 的几何常量渲染 app-icon.ico（多尺寸）"
    workingDir = projectDir

    // 🔴 解释器路径**硬编码，不要探测**。
    //
    // 这个 WindowsApps/python3.exe 是 App Execution Alias（0 字节 reparse point），
    // 于是两种存在性判断**都**返回 false：
    //     Gradle 的 file(it).exists()  → false
    //     Kotlin 的 File(it).isFile()  → false
    // 于是「探测 + 回退到裸 python3」的写法会静默选中 daemon PATH 里那个
    // **没有 PIL** 的 python3，报`ModuleNotFoundError: No module named 'PIL'`，
    // 看起来像 PIL 没装，其实是选错了解释器。
    //
    // 所以：直接用它。真不存在时命令会报「系统找不到文件」，
    // 那比静默换一个错的解释器好得多。已验证它带 PIL 12.2.0。
    // （`.workbuddy-ai/binaries/python/` 下的解释器都**没有** PIL。）
    val py = "C:/Users/Administrator/AppData/Local/Microsoft/WindowsApps/python3.exe"
    logger.lifecycle("renderAppIcon: 用解释器 $py")

    commandLine(py, "tools/render_icon.py", "app-icon.ico")
    inputs.file("src/main/kotlin/com/example/classreminder/ui/fluent/StuMateMark.kt")
    inputs.file("tools/render_icon.py")
    outputs.file("app-icon.ico")
}

// ⚠️ 这里**不能**写 `tasks.named("packageMsi") { … }`。
// CMP 的打包任务是在**脚本执行完之后**才注册的（targetFormats 在
// `compose.desktop.application { }` 里被读到之后才建任务），
// 配置期 `tasks.named("packageMsi")` 会直接抛
//    Task with name 'packageMsi' not found in root project
// 而且它是在**脚本编译/配置阶段**抛的，连 `tasks --all` 都跑不起来。
// → 用字符串 dependsOn（任务图解析时才去找）或 configureEach 代替。

// 让打包前一定重渲图标，避免拿过期 .ico 出包。
// 字符串依赖 = 懒解析；CMP 还没注册的任务不会在配置期炸。
tasks.configureEach {
    if (name == "packageMsi" || name == "createDistributable"
        || name == "createReleaseBundle") {
        dependsOn("renderAppIcon")
    }
}

// ── MSI 后处理（桌面快捷方式 + 完成后启动）────────────────────────────
//
// Compose 的 `packageMsi` 直接调 jpackage，而 jpackage **没有**这两个开关：
//  - 桌面快捷方式：没有 `--win-desktop-shortcut`
//    （`makeArgs` 里只有 `--win-shortcut` / `--win-menu` / `--win-menu-group`）
//  - 安装完成后启动：完全没有对应选项
// 所以走 WiX 后处理：`tools/postprocess_msi.py`
//   dark 解包 → 改 WXS → candle 编译 → light 链接
//
// 开始菜单快捷方式**不再**由脚本插：`windows { shortcut = true }` 已经让
// jpackage 自己生成，脚本里的 `add_shortcuts()` 是幂等的，只补缺的那条。
//
// 产物写到 `<buildDirectory>/msi/StuMate-<version>-final.msi`（原包保留，方便对照）。
val msiOutDir = layout.buildDirectory.dir("msi")

tasks.register<Exec>("postprocessMsi") {
    group = "build"
    description = "后处理 MSI：补桌面快捷方式与安装完成后启动"

    // 字符串形式 → 任务图解析时才去找，不会因为注册时机把脚本搞崩
    dependsOn("packageMsi")

    val srcMsi = layout.buildDirectory
        .file("compose/binaries/main/msi/StuMate-${project.version}.msi")
    val dstMsi = msiOutDir.map { it.file("StuMate-${project.version}-final.msi") }

    // 同 renderAppIcon：硬编码已验证的带 PIL 的解释器，理由见那里的注释。
    val py = "C:/Users/Administrator/AppData/Local/Microsoft/WindowsApps/python3.exe"

    inputs.file(srcMsi)
    inputs.file("tools/postprocess_msi.py")
    inputs.file("app-icon.ico")
    outputs.file(dstMsi)

    doFirst {
        val src = srcMsi.get().asFile
        if (!src.exists()) {
            throw GradleException("找不到 jpackage 产物: $src\n" +
                "先跑 packageMsi（要 -PwithMsi=true 且已设 WIX_PATH）。")
        }
        dstMsi.get().asFile.parentFile.mkdirs()
        // ⚠️ 命令行放 doFirst 里：输出路径要在 dependsOn 的 packageMsi 跑完后才有效。
        //    跑完才知道对不对（在配置期解析会是过期的路径）。
        commandLine(py, "tools/postprocess_msi.py", src.absolutePath,
            dstMsi.get().asFile.absolutePath)
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
