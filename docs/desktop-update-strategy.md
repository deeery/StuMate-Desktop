# StuMate 桌面端更新方案

> 结论先行：**桌面端从 1.5.0 起，更新走「替换文件」，不走 MSI 安装流程。**
> MSI 只作为「首次安装」的手段保留。

## 为什么

Compose Desktop 的 `jpackage` 产物结构决定了这件事：

```
StuMate/
├── StuMate.exe            553 KB   ← jpackage 启动器，各版本完全一致
├── app/
│   ├── StuMate.cfg                ← 只列 classpath
│   ├── StuMate-Desktop-<版本>-<hash>.jar    ← **只有这个是变化的**
│   └── 37 个依赖 jar                ← 版本固定，跨版本逐字节相同
└── runtime/                65 MB   ← JRE，各版本完全一致
```

`app/` 共 38 个文件，其中**只有主 jar 的文件名带版本号和 hash**，其余 37 个依赖 jar
的名字和内容都跟着依赖版本走（Compose 1.5.10 / Kotlin 1.9.20 / sqlite-jdbc 3.45.3.0 …）。
升级时这些文件一个都不会变。

于是「升级」这件事实际只需要动两处：

1. 覆盖 `app/StuMate-Desktop-<新版本>-<新hash>.jar`
2. 把 `app/StuMate.cfg` 第一行 `app.classpath` 指向新jar 名

`runtime/` 65 MB 和 `StuMate.exe` 553 KB 都可以不动 —— 这是「替换文件」能成立的根本原因。

## 已验证

在 1.5.0 目录版上做了完整实验：

| 步骤 | 结果 |
|---|---|
| 复制 1.5.0 目录版到临时位置 | 176 个文件，`app/` 38 个 |
| 把主 jar 里的 `BuildConfig.VERSION` 从 `1.5.0` 改写成 `9.9.9` | class 常量池里 `1.5.0` 计数 0、`9.9.9` 计数 1 |
| jar 改名为 `StuMate-Desktop-1.6.0-<hash>.jar` | — |
| 改 `StuMate.cfg` 的 `app.classpath` 指到新名 | cfg 首行已变成 1.6.0 |
| 启动 `StuMate.exe`，用 `EnumWindows` 查**可见主窗口** | 窗口 `StuMate` 可见并持续存活 |

也就是说：只要换掉 jar 和 cfg 两处，一个 1.5.0 的安装目录就能原地升到新版本，
不需要卸载、不需要管理员权限、不进「程序和功能」列表。

> ⚠️ **验收必须查窗口，不能只看进程存活。**
> 这一节早期版本写的是「运行 14 秒不退出」，那是**错的** —— 只看 `poll()` 会得出
> 完全相反的结论。当时那份产物其实启动即崩（见下面「runtime 缺 java.sql」），
> 但进程在崩溃后仍短暂存活，掩盖了问题。正确判据是
> `EnumWindows` + `GetWindowThreadProcessId` + `IsWindowVisible` 确认
> 主窗口 `StuMate` 可见且持续存活。

## 两种分发形态

| 产物 | 用途 | 体积 |
|---|---|---|
| `dist/StuMate-1.5.0.msi` | **首次安装**（需要进「程序和功能」/ 开始菜单时用它） | 66.94 MB |
| `dist/StuMate-portable-1.5.0.zip` | **免安装 + 后续更新**（解压即用） | 65.89 MB |

**SHA-256**
```
9a6ede163c0c5f9125c0c626e5bec68461f2ed1db6c74f5888b90a9c470e0c4d  StuMate-1.5.0.msi
95e133477caf81ab2f4716dd4166045788455809a397bdccbdfc3f669cc185eb  StuMate-portable-1.5.0.zip
```

## 打包命令

```bash
# MSI（首次安装用）—— WIX_PATH 是环境变量，必须和 gradlew 在同一条命令里
export JAVA_HOME="E:/DevTools/Java/jdk-17.0.12+7"
export WIX_PATH="C:\Program Files (x86)\WiX Toolset v3.14"
./gradlew --offline --no-build-cache packageMsi -PwithMsi=true

# 目录版（绿色版，更新用）
./gradlew --offline --no-build-cache createDistributable
```

⚠️ **`WIX_PATH` 漏了会报一个看起来毫不相关的错**：
```
Could not evaluate onlyIf predicate for task ':downloadWix'.
```
这是 Gradle daemon 拿不到环境变量的典型表现 —— daemon 是长驻进程，
只在**启动时**读一次环境变量。同理 `JAVA_HOME` 也必须每次带上。

产物落在 `build/compose/binaries/main/{msi,app}/`，要手动拷到 `dist/`。

## 🔴 runtime 必须显式声明 modules（否则装完一定起不来）

`nativeDistributions` 里必须写：

```kotlin
modules(
    "java.sql",        // sqlite-jdbc —— 缺它 100% 起不来
    "java.logging",    // slf4j
    "java.naming", "java.prefs", "java.management", "java.xml",
    "java.net.http",   // 云同步的 JDK HttpClient
    "jdk.unsupported", // Skiko/JNA 要用 sun.misc.Unsafe
)
```

### 症状

安装后双击：**窗口闪一下就没了**，随后弹「Failed to launch JVM」。
但开发时 `./gradlew run` 或 `java -cp ... MainKt` 完全正常。

### 真实原因

jpackage 打出来的 runtime 是 jlink **裁剪**过的，**不含 `java.sql`**。于是：

1. AWT frame 先创建出来 —— 这就是你看到的「窗口闪过」
2. 界面首帧要落库，sqlite-jdbc 去 `Class.forName("java.sql.Driver")`
3. `NoClassDefFoundError: java/sql/Driver` 从协程里抛出（`Db.kt:43`）
4. `main` 抛异常退出 → launcher 拿到非零返回码 → 弹「Failed to launch JVM」

**那句报错是结果不是原因。** 别顺着它去查 `jvm.dll` / `jli.dll` / `JAVA_HOME`
（那些全都正常，`jvm.dll` 与系统 JDK 逐字节同体积）。

### 为什么本地跑不出来

`./gradlew run` 和 `java -cp` 用的是**系统 JDK 的完整模块集**，`java.sql` 自然在。
只有打包产物走裁剪过的 runtime 才会炸 —— 于是「本地好好的、打包就坏」，
看起来像打包 bug，其实是缺模块。

### 怎么确认的

用 jpackage 额外打一个 `--win-console` 的 app-image（GUI 子系统的 exe 拿不到 stderr），
stderr 一落盘就看到那行 `NoClassDefFoundError`。

### 判据

`runtime/lib/modules` 的大小会随模块集变化，可以当快速自检：

| | 字节 |
|---|---|
| 未声明 modules（坏的） | 44,964,997 |
| 声明 modules（好的） | 48,577,830 |

### ⚠️ 换 buildDirectory 打包时可能踩到

产物目录里的 `StuMate.exe` 会被 jpackage 设成**只读**（`-r-xr-xr-x`）。
如果上一次构建中途失败，`createDistributable` 下次会卡在
`java.io.IOException: Unable to delete directory`，且因为是只读位，
Gradle 删不掉。先处理掉只读位：

```bash
attrib -R "<buildDir>/compose/binaries/main/app/StuMate/StuMate.exe"
rm -rf "<buildDir>/compose/binaries/main/app" "<buildDir>/compose/binaries/main/msi"
```

删掉 `msi/` 目录是必须的 —— 否则 `packageMsi` 会报 `UP-TO-DATE`，
磁盘上留着上一版的**坏包**。

## 后续版本该怎么做

1. 改 `build.gradle.kts` 的 `version = "1.6.0"`
2. `./gradlew createDistributable`
3. 打 zip：新的 `app/StuMate-Desktop-1.6.0-<hash>.jar` + `app/StuMate.cfg` + 其余不变
4. **发一个「补丁包」**：只含那两个文件（jar + cfg），实测 **1.70 MB**
   （主 jar 自己就 1.7 MB，cfg 只有 3 KB）
5. 用户把补丁包解压覆盖到自己的安装目录即可，进程要先退出

> 补丁包实测可行：把 1.5.0 目录版复制一份，解压补丁包覆盖，
> `app/` 仍是 38 个文件、cfg 的 33 条 classpath 完好，`StuMate.exe` 正常运行。

### 一致性检查（别跳过）

`StuMate.cfg` 里的 jar 名必须和实际文件名**逐字一致**，差一个字符就启动失败。
`BuildConfig.VERSION` 是生成进 class 常量池的，改了版本号必须重新 `createDistributable`，
否则设置页显示的版本还是旧的 —— 这和安卓端「交付了不含新代码的 APK」是同一类事故。

## 已知限制

- **不支持跨安装形态升级**：MSI 装的程序在 `%ProgramFiles%\StuMate\app\`，
  绿色版在自己的目录里，两者不能原地互换。
  建议：**新用户直接用绿色版 zip**，从一开始就避开 MSI，后续更新永远走替换文件。

## 1.5.1 启动修复的验证记录

本轮修掉「安装后窗口闪过 + Failed to launch JVM」后，验收做在两处：

| 验证对象 | 方法 | 结果 |
|---|---|---|
| `createDistributable` 目录版 | `ShellExecuteW` 启动 + `EnumWindows` 查可见窗口 | 主窗口 `StuMate` 可见，稳定 25s+，内存 ~300 MB，无弹框 |
| MSI 内的文件树 | `msiexec /a` 解包（管理安装，免管理员）→ 跑解出来的 exe | `modules` = 48,577,830 字节（含 `java.sql`），主窗口可见稳定 |

`runtime/lib/modules` 大小与目录版**逐字节一致**（48,577,830），
证明 MSI 嵌的就是修复后那份 runtime，不是旧包。

⚠️ **MSI 的真·安装态（`msiexec /i`）本轮没验成** —— 当前 shell 虽名为 Administrator
但 `IsUserAnAdmin()==0`（UAC 未提升），`msiexec /i /qn` 返回 **1625**
（系统策略拒绝）。这是环境限制，不是包的问题；
`/a` 管理安装已能证明 MSI 内容正确，剩下的只是注册表/快捷方式那一层。
有提权环境时补一次 `msiexec /i StuMate-1.5.0.msi` 即可。