import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.10"
}

group = "com.example.classreminder"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

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
            targetFormats(TargetFormat.Msi)
            packageName = "StuMate"
            packageVersion = "1.0.0"
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
 * 只渲染账号相关 UI 的预览窗口，用于截图验收。见 dev/UiPreview.kt
 *
 * ⚠️ 场景通过 `-P` 传，**不能**靠环境变量：Gradle 守护进程是长驻进程，
 * `System.getenv()` 拿到的是守护进程启动时的环境，改 shell 变量根本不生效。
 *
 * ```
 * ./gradlew uiPreview -Ppreview=auth        # 登录对话框
 * ./gradlew uiPreview -Ppreview=signedin -Pemail=a@b.c -Ppassword=...
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
        "password" to ""
    ).forEach { (key, default) ->
        systemProperty("stumate.$key", project.findProperty(key) ?: default)
    }
}
