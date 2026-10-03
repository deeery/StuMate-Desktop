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
| `StuMate-1.5.0-final.msi` | **首次安装**（进「程序和功能」/ 开始菜单 / 桌面快捷方式 / 装完自动启动） | 71,539,578 字节（68.22 MB） |
| `StuMate-portable-1.5.0-icon.zip` | **免安装 + 后续更新**（解压即用） | 70,231,960 字节（66.97 MB） |

> ⚠️ 发 MSI 时认`-final.msi` 后缀那个：`packageMsi` 直接出的原包**没有**
> 「程序和功能」图标治理、没有完成后启动、没有 AppUserModelID，
> **而且重装会报 2819**（见下面 jpackage 上游缺陷那节）。

**SHA-256**（品牌图标 + 快捷方式 + 完成后启动 + 2819 修复后的包）

```
39a3a6ac5ad7b01f568d62350fce6286a856788c685271226c18810c5529f877  StuMate-1.5.0-final.msi
cb291ce1b0a34ff20360dcfdc3d9130ef727c57e4027515d9007ac42634327d1  StuMate-portable-1.5.0-icon.zip
c8359df0902fcdc2b772a756c510bc2e5b6b69b083527a9d5a3563a6981f74fd  app-icon.ico
```

绿色版 zip = 198 个文件、原始 136,354,746 字节、压缩后 70,231,960 字节。
（比上一版大 20 KB，正是换上的品牌图标。）

## 🔴 jpackage 上游缺陷：重装时报 2819（后处理已修）

**症状**：安装过程弹 `Error 2819`，日志：

```
Control [3] on dialog [2] needs a property linked to it.
```

**根因**（挖 `$JAVA_HOME/jmods/jdk.jpackage.jmod` 里的
`classes/jdk/jpackage/internal/resources/InstallDirNotEmptyDlg.wxs` 才确认）：

```xml
<Control Id="Yes" Type="PushButton" X="100" Y="55" … Text="!(loc.WixUIYes)">
  <Publish Event="NewDialog" Value="$(var.JpAfterInstallDirDlg)">1</Publish>
</Control>
<Control Id="No" Type="PushButton" X="150" Y="55" …>
  <Publish Event="NewDialog" Value="InstallDirDlg">1</Publish>
</Control>
```

两个 PushButton **都没有 `Property` 属性** —— Oracle 源码本身如此，属**上游缺陷**，
不是我们后处理引入的（原包与后处理包这段逐字一致）。
同一个 WXS 里还引用 `INSTALLDIR_VALID="0"/"1"` 做条件，
而 `INSTALLDIR_VALID` **在 Property 表里根本没定义**。

**为什么长期没人发现**：这个 Dialog 只在 `INSTALLDIR_VALID="0"`
（目标目录**已存在**）时才弹。干净机器首次安装根本不经过它，
于是「我这儿装得好好的」，**用户在重装/覆盖装时才炸**。

**修法**（`tools/postprocess_msi.py` 的 `fix_jpackage_dir_dialog`）：
给两个按钮补 `Property`（值只为过校验，不参与逻辑）+ 补声明
`INSTALLDIR_VALID=1`。幂等，重复跑不会重复加。

**校验**：`tools/inspect_msi.py` 的第 4.5 项专查这个
（Button 型控件缺 Property、或用了 `JpCheckInstallDir` 却没声明
`INSTALLDIR_VALID` → 退出码 1）。

## 打包命令

```bash
export JAVA_HOME="E:/DevTools/Java/jdk-17.0.12+7"
export WIX_PATH="C:\Program Files (x86)\WiX Toolset v3.14"

# MSI（首次安装用）+ 后处理 —— WIX_PATH 是环境变量，必须和 gradlew 在同一条命令里
./gradlew --offline --no-build-cache postprocessMsi -PwithMsi=true

# 目录版（绿色版，更新用）
./gradlew --offline --no-build-cache createDistributable
```

⚠️ **`WIX_PATH` 漏了会报一个看起来毫不相关的错**：
```
Could not evaluate onlyIf predicate for task ':downloadWix'.
```
这是 Gradle daemon 拿不到环境变量的典型表现 —— daemon 是长驻进程，
只在**启动时**读一次环境变量。同理 `JAVA_HOME` 也必须每次带上。

产物：
- 原包 → `build/compose/binaries/main/msi/StuMate-<版本>.msi`
- **后处理包（要发的是这个）** → `build/msi/StuMate-<版本>-final.msi`

## 图标 / 快捷方式 / 完成后启动

### 一行配置解决的三件事

```kotlin
windows {
    iconFile = project.layout.projectDirectory.file("app-icon.ico")
    shortcut = true
    menu = true
    menuGroup = "StuMate"
}
```

`app-icon.ico` 由 `./gradlew renderAppIcon` 从 `src/main/kotlin/…/ui/fluent/StuMateMark.kt`
的几何常量渲染，7 档尺寸（16/24/32/48/64/128/256），361,102 字节。

**必须配在 `windows { }` 里**，配在 `nativeDistributions` 直接层会报
`Unresolved reference: icon` —— `AbstractPlatformSettings` 只有 `getIconFile()`，
而 `AbstractDistributions` 根本没有这个成员。

### ⚠️ `shortcut` / `menu` 默认是 false（这就是 1.5.0 没快捷方式的原因）

反编译 `compose-gradle-plugin-1.5.10.jar` 确认链路完整：
```
WindowsPlatformSettings.shortcut / menu / menuGroup
  → ConfigureJvmApplicationKt
    → AbstractJPackageTask.winShortcut / winMenu / winMenuGroup
      → cliArg("--win-shortcut" / "--win-menu" / "--win-menu-group")
```
而 `WindowsPlatformSettings` 构造时**只**把 `dirChooser` 默认成 true，
`shortcut` 与 `menu` 默认都是 `false` —— 不显式打开，打出来的 MSI 里
`Shortcut` 表是**空的**。

配好之后 jpackage **桌面和开始菜单两条都做**（不需要额外开关），
且快捷方式图标指向我们给的 ico（`Icon\icon_<hash>` 与 `app-icon.ico` SHA-256 一致）。

### 「保存到任务栏」技术上做不到

Windows Installer **没有**「pin to taskbar」这个动作，任务栏固定是 Explorer 的
用户态行为，任何安装包都做不到自动固定。

能做到的最好程度是：给快捷方式注册一个显式 AppUserModelID
（`HKLM\Software\Classes\AppUserModelId\StuMate.Desktop.1`），
这样用户右键任务栏图标时菜单里会出现「固定到任务栏」，点一下即可。
顺带「跳转到」列表里也会按这个 ID 分组。

### 后处理脚本补的两件事

`tools/postprocess_msi.py` 走 `dark 解包 → 改 WXS → candle → light`：

| 补什么 | 为什么 jpackage 没有 |
|---|---|
| 安装完成后启动 | jpackage 没有任何「装完启动」开关 |
| AppUserModelID 注册表项 | 同上，MSI 层面要手写 |
| `JpARPPRODUCTICON` → 品牌 ico | 「程序和功能」列表图标与快捷方式图标是两条 |

`Shortcut` 表与目录结构由 jpackage 原生产出，脚本里的 `add_shortcuts()`
已改成**幂等兜底**（判据看**目录节点**是否存在，不看 `Id="scStartMenu"` ——
jpackage 生成的 Id 是 `shortcut<hash>` 这种哈希名，按名字判会重复插
`ProgramMenuFolder`，light 直接报 `LGHT0091 Duplicate symbol`）。

验证：`python tools/inspect_msi.py <msi>`（dark + XML 解析，
顺带把图标流解出来算 SHA 比对；任何一项不过退出码为 1）。

### 🔴 Gradle 脚本里两个坑

1. **不能写 `tasks.named("packageMsi") { … }`**。CMP 的打包任务在脚本执行完之后
   才注册，配置期这么写直接抛 `Task with name 'packageMsi' not found in root project`，
   而且是**编译/配置阶段**抛的，连 `./gradlew tasks --all` 都跑不起来。
   用字符串 `dependsOn("packageMsi")`（任务图解析时才找）或 `tasks.configureEach`。
2. **找带 PIL 的解释器不能用 `file().exists()` 或 `File().isFile()` 探测**。
   `C:/Users/…/WindowsApps/python3.exe` 是 App Execution Alias（0 字节 reparse point），
   两种判断**都**返回 false，于是静默回退到裸 `python3`，而 daemon PATH 里那个
   **没有 PIL** → `ModuleNotFoundError: No module named 'PIL'`。
   看起来像「PIL 没装」，实际是选错了解释器。**直接硬编码那个路径。**

## 🔴 校验 MSI 内容：`msiexec /a` 在本机用不了

`msiexec /a <msi> /qn TARGETDIR=…`（管理安装，免管理员）本来是最直接的验证手段，
但本机返 `rc=1619`，`/l*v` 日志里是：
```
Note: 1: 2203 C:\WINDOWS\Installer\inprogressinstallinfo.ipi -2147287038
```
即 Windows Installer **服务**被上一次没收尾的安装占着。那是服务级状态，
杀 `msiexec` 进程解不开，要重启 `msiserver` 服务或机器。

替代方案：`tools/unpack_msi.py <msi> <目标目录>` ——
用 `dark -x` 解出全部 File 流，再按 WXS 的 Directory 树还原成安装后的目录结构，
内容本来就是同一批流，与安装后逐文件一致。跑还原出来的 `StuMate.exe` 即可验收。

> 🔴 还原时 **Component 在 WXS 里没有 `@Directory` 属性**，它是 Directory 的子孙节点：
> `<Directory …><Directory …><Component …><File …/></Component></Directory></Directory>`。
> 按属性查 → 一个都查不到 → 197 个文件全被平铺到根目录，
> `runtime/lib/modules` 根本不存在，一眼看着像「MSI 里没 runtime」。必须递归下降真实树。

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

> 🔴 **补丁包有一个前置条件：`runtime/` 里必须已经有 `java.sql`。**
> 补丁包不含 runtime，所以给「修复前的 1.5.0 目录版」打补丁是**修不好**的 ——
> 那样只是把一个启动即崩的程序换成另一个启动即崩的程序。
> 判断某份安装目录能不能直接收补丁，看这个：
> `runtime/lib/modules` 大小 **< 46,000,000 字节 = runtime 是裁剪过的，必须重装整包**；
> ≥ 46,000,000 = runtime 是全的，只换 jar 即可。
> （46 MB 这个阈值来自本轮：44,964,997 无 `java.sql` / 48,577,830 有。）

### 一致性检查（别跳过）

`StuMate.cfg` 里的 jar 名必须和实际文件名**逐字一致**，差一个字符就启动失败。
`BuildConfig.VERSION` 是生成进 class 常量池的，改了版本号必须重新 `createDistributable`，
否则设置页显示的版本还是旧的 —— 这和安卓端「交付了不含新代码的 APK」是同一类事故。

## 已知限制

- **不支持跨安装形态升级**：MSI 装的程序在 `%ProgramFiles%\StuMate\app\`，
  绿色版在自己的目录里，两者不能原地互换。
  建议：**新用户直接用绿色版 zip**，从一开始就避开 MSI，后续更新永远走替换文件。
- **MSI 的真·安装态仍未验成**：`msiexec /i /qn` 在当前 shell 返 **1625**
  （`IsUserAnAdmin()==0`，UAC 未提升）；`msiexec /a` 又因 Windows Installer
  服务被占返 **1619**（日志 Note 1: `2203 inprogressinstallinfo.ipi`）。
  两个都是环境限制，不是包的问题。已用「dark 解包还原文件树 → 跑还原出的 exe」
  等价验证了包内容。**有提权环境时补一次真安装即可确认快捷方式与注册表落地。**

## 品牌图标 + 快捷方式 + 完成后启动的验证记录

| 检查项 | 方法 | 结果 |
|---|---|---|
| MSI 表结构 | `tools/inspect_msi.py`（dark + XML，顺带解图标流算 SHA） | `Shortcut` 2 条（`DesktopFolder` / `ProgramMenuFolder/StuMate`）、`LaunchStuMate` @6501 + `NOT REMOVE`、`ARPPRODUCTICON` 有效、`AppUserModelId` 已注册 → **全部通过，退出码 0** |
| 图标一致性 | MSI 内 `StuMate.ico` 与 `app-icon.ico` 比 SHA-256 | 361,102 字节，`c8359df0902fcdc2…` **完全一致** |
| 包内文件树 | `tools/unpack_msi.py` 还原 | 197 个文件全部还原、0 跳过；`StuMate/{exe,ico,app,runtime}` 结构正确 |
| runtime 完整性 | 看 `runtime/lib/modules` 大小 | **48,577,830 字节**，与目录版逐字节一致（含 `java.sql`） |
| 启动 | 跑还原出的 `StuMate.exe` + `EnumWindows` 查可见主窗口 | 主窗口 `StuMate` 可见，稳定 15s+ |
| 绿色版 zip | 重新打包 | 198 文件 / 136,354,746 → 70,231,960 字节，含品牌 `StuMate.ico` |

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