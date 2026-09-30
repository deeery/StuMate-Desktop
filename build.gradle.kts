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
