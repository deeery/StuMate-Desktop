# StuMate-Desktop · 项目长期记忆

## 项目定位

安卓课表提醒 App「StuMate」（源项目 `C:\Users\Administrator\IdeaProjects\ClassReminderNewest`）
的 **Windows 桌面客户端**。Kotlin + Compose Multiplatform Desktop。

## 硬性约束（改代码前先看这里）

| 项 | 值 | 说明 |
|---|---|---|
| Kotlin | **1.9.20** | 与安卓源项目一致 |
| Compose Multiplatform | **1.5.10** | 勿升 1.5.11/1.5.12（要求 Kotlin 1.9.21/1.9.22） |
| Gradle | **8.4** | wrapper 不可用（见下），用项目内 `gradlew` 转发脚本 |
| JDK | 17（`E:\DevTools\Java\jdk-17.0.12+7`） | 写进 `gradle.properties` 的 `org.gradle.java.home` |
| 包名 | `com.example.classreminder` | **刻意与安卓端一致**，让 `MainScreen.kt` 零 import 改动 |
| 数据目录 | `%APPDATA%\StuMate\` | `settings.json` + `class_reminder.db` |

## 环境已知坑

1. **Gradle wrapper 不能用**：`services.gradle.org` 重定向到 GitHub release assets 返回 502。
   项目根目录的 `gradlew` / `gradlew.bat` 是**转发脚本**，指向本机缓存的 Gradle 8.4 发行版。
   不要试图用 `gradle wrapper` 重新生成。
2. **`compose.material` 对 `material-icons-core` 是 runtime 作用域**，编译期不可见，
   必须显式依赖 `org.jetbrains.compose.material:material-icons-core:1.5.10`。
3. **CMP 1.5.10 无 `Icons.AutoMirrored`**（1.6.0 才有）→ 用 `Icons.Default.List`。
4. **`androidx.compose.ui.util.lerp`** 需显式依赖 `org.jetbrains.compose.ui:ui-util:1.5.10`。
5. **`Dispatchers.Swing`** 需 `import kotlinx.coroutines.swing.Swing`。
6. **`MenuScope.Item` / `Separator`** 是 `MenuScope` 成员函数，不要 import。
7. **截图**：PowerShell 的 `Add-Type` 与反射加载程序集被沙箱拦截。
   用 `tools/capture_window.py`（Python + Pillow，venv 在
   `~/.workbuddy-ai/binaries/python/envs/default`）。交互模拟用 `tools/win_input.py`。
8. **窗口置顶**：`SetForegroundWindow` 在后台进程里常被 Windows 拒掉，直接截图会截到别的窗口。
   必须 `ShowWindow(SW_MINIMIZE)` → `SW_RESTORE` → `BringWindowToTop` → `SetForegroundWindow`，
   再用 `GetForegroundWindow() == hwnd` 校验（两个脚本都已实现 `raise_window`）。
9. **模拟点击定位**：不要目测截图坐标。用 PIL 扫描目标区域像素找控件行范围
   （本机 100% 缩放下：侧栏导航项 38px 高、设置子导航项 34px 高）。
10. **无边框窗口不能用 `WindowPlacement.Maximized`**：会连任务栏一起盖住。
    要自己算工作区（`Toolkit.getScreenInsets`）再设 `WindowState.size/position`，
    并自己记住还原尺寸。见 `ui/fluent/WindowChrome.kt` 的 `workAreaOf()`。
11. **`SetForegroundWindow` 会被 Windows 前台锁拒掉**（返回成功但没上来）。
    要配合 `SetWindowPos(HWND_TOPMOST)` → `HWND_NOTOPMOST` → `BringWindowToTop`。
    且「最小化→还原」之后第一次点击会被系统吃掉，脚本需先点一下空白处热身。

## 架构分层

```
src/main/kotlin/com/example/classreminder/
├── Main.kt              application{} 入口：Window + Tray + 提醒引擎启动 + 关闭确认弹窗
├── Prefs.kt             %APPDATA%\StuMate\settings.json（复用 MiniJson，原子写）
├── AppPaths.kt          数据目录
├── platform/            平台接缝层（安卓 → Windows 的全部替换都在这）
│   ├── ReminderEngine   替代 ClassReminderService（常驻协程 30s 轮询）
│   ├── AutoStart        替代 BootReceiver（HKCU\...\Run 注册表）
│   ├── FileDialogs      替代 SAF（JFileChooser）
│   └── ToastHost        替代 android.widget.Toast（全局 ToastBus）
├── ui/                  Theme / TodayScreen / MainScreen / OverlayScreen / DateTimePickers
└── data/                Entity / Db(JDBC) / ClassDao / NoteDao / MainViewModel
                         + 纯逻辑：WeekSchedule / TodaySchedule / TimeAxis /
                           TodayNotePicker / TimetablePdfParser / backup/
```

**关键原则**：`data/` 里除 Entity 与 DAO 外全部是**纯 Kotlin 逻辑，与平台无关**，
145 个单测覆盖它们。改 UI 时不要碰 `data/`。

## 用户偏好（本项目）

- 用户**明确不要 1:1 复刻手机 UI**，要用**桌面软件的设计语言**（功能逻辑一致即可）。
- **已选定皮肤 B：Windows 11 Fluent**（4px 控件圆角、1px 描边扁平卡片、导航用左侧强调条、
  强调色 `#0067C0` / 深色 `#60CDFF`、正文 13sp、行高 34dp）。
  皮肤 A（Material 3 桌面化）的预览保留在 `design-preview/` 供对比，不再维护。
- 已确认的桌面方向：**左侧边栏 + 主内容区**、便签页 **表格 + 右侧编辑面板**、
  课表页 **全宽时间轴网格 + 右侧课程详情面板**、设置页 **子导航 + 面板**、
  窗口 **1200×800 可缩放（最小 900×600）**。
- 每次 UI 改动要产出 **HTML 验收页**（放 `design-preview/`）+ **实机截图**，让用户亲自验收。
- **不要主动构建安装包**（`packageMsi` 需要 WiX，本机没装）；默认只跑编译与单测。

## UI 分层

```
ui/
├── TodayText.kt        纯文案函数（greetingFor / todaySubtitle / emptyToday* / remainingText）
├── DateTimePickers.kt  M3 日期/时间选择器，但用 Fluent 色板包了一层 MaterialTheme
└── fluent/             桌面 UI 全部在这里（见下）
```

**关键原则**：`ui/fluent/` 里所有控件都是自绘的，不用 Material 3 的 Button/Switch/TextField
（M3 是「胶囊 + 阴影 + 大圆角」，和 Fluent 的「4dp 圆角 + 1px 描边 + 扁平」是两套语言）。
唯一的例外是日期/时间选择器 —— 自己画日历与表盘代价太大，所以复用了 M3 的，
但通过 `FluentMaterialScope` 把配色换成了 Fluent 色板。

## 窗口结构（自绘标题栏）

窗口是 **`undecorated = true`**（无系统标题栏）。因此下面三件事必须自己做，
全在 `ui/fluent/WindowChrome.kt`：

| 能力 | 实现 |
|---|---|
| 标题栏 | `PageTopBar` 兼任：`[标题+副标题(weight 1f，可拖动)] [页面操作] [─ □ ✕]` |
| 拖动 / 双击最大化 | `Modifier.windowDragArea(chrome)`，挂在标题区 |
| 缩放 | `WindowResizeHandles`，四边 + 四角共 8 个 5dp 热区 |
| 最大化 | 手工算 `workAreaOf()`，**不要用 `WindowPlacement.Maximized`** |

`WindowChrome` 通过 `LocalWindowChrome` 下发，避免把 `ComposeWindow` 透传到 4 个页面。
`isMaximized` 用 lambda 而不是 Boolean，避免缩放时每帧重建对象。

**动作按钮的落点约定**：页面级动作放 `PageTopBar` 的 `actions` 槽（在窗口控制按钮左侧）；
卡片级动作放该卡片标题行的右端（`start 16 / end 12 / top-bottom 8` 的 Row，按钮 `compact`）。



## 工具脚本

| 脚本 | 用途 |
|---|---|
| `tools/capture_window.py <标题> <输出路径>` | 截取指定标题的窗口 |
| `tools/win_input.py info/click/click-win/key` | 查询窗口位置、模拟点击与按键 |
| `tools/seed_demo.py` / `--reset` | 灌演示数据 / 清空数据 |

## 交付状态

- 业务层（data + platform）：**完成**，145/145 单测通过。
- UI 层：**已按皮肤 B（Windows 11 Fluent）重做为桌面外壳**，编译通过、145 单测全绿，
  实机截图见 `design-preview/f10`~`f18`（深浅两套主题 × 四个页面）。
- 设计预览：`design-preview/desktop-preview-B-fluent.html`（选定版）、
  `desktop-preview-A-material3.html`（备选，留档）。
