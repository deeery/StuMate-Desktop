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
| 启动 `StuMate.exe` | **正常运行 14 秒不退出**（即跑的是新代码，不是缓存的旧 class） |

也就是说：只要换掉 jar 和 cfg 两处，一个 1.5.0 的安装目录就能原地升到新版本，
不需要卸载、不需要管理员权限、不进「程序和功能」列表。

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
- MSI 的静默安装（`msiexec /i /qn`）在沙箱环境里起不来，需要本机提权验证 ——
  本轮没做（`msiexec` 被沙箱拦），但也**不再需要**：既然走替换文件，MSI 只是首次安装手段。