//! StuMate Lite —— Tauri 后端入口。
//!
//! 与原版的关系：**同一个数据、同一套规则、不同的运行时**。
//! 原版是 Compose Desktop（JVM + Skia），Lite 是 Tauri（Rust + WebView2）。
//! 业务规则（周次解析、时间轴布局、导图派生、便签重排）落在前端的 `src/model/` 里，
//! 用 vitest 钉住；这一层只负责「把数据安全地读出来、写回去」。

mod autostart;
mod backup;
mod db;
mod models;
mod fullscreen;
mod overlay;
mod splash;
mod store;
mod tray;
mod vault;

use models::{AppInfo, ClassEntity, KnowledgeEntity, LinkRef, MindNode, NoteEntity};
use serde_json::Value;
use std::sync::Mutex;
use store::Paths;
use tauri::{Manager, State};

pub struct AppState {
    db: db::Db,
    paths: Paths,
    /// 偏好缓存：读写都过它，避免每次 `pref_set` 都重新读盘
    prefs: Mutex<Value>,
    /// 最近一帧提醒内容。提醒卡窗口是**新建**的，那一刻页面还在加载，
    /// 直接 emit 会丢事件 —— 所以留一份让页面挂载后主动来取（`overlay_state`）。
    alert: Mutex<Option<Value>>,
    /// 启动窗「两票制」状态。watchdog 线程也要看，所以是 Arc<Mutex<_>>，
    /// 不是上面两个那种只被 IPC 命令碰的裸 Mutex。
    splash: splash::SharedGate,
}

impl AppState {
    fn prefs_snapshot(&self) -> Value {
        self.prefs.lock().map(|g| g.clone()).unwrap_or_else(|_| Value::Null)
    }
}

// ── 应用信息 ──────────────────────────────────────────────────────

/// 诊断日志：只有设了 `STUMATE_LITE_TRACE` 才写，落到数据目录的 `lite-trace.log`。
///
/// release 版是 windows_subsystem="windows"，没有控制台，`println!` 全部丢失。
/// 窗口画面又抓不到的时候（见 VERSION-INFO「已修缺陷」第 3 条），文件是唯一出口。
pub(crate) fn trace(dir: &std::path::Path, msg: &str) {
    if std::env::var("STUMATE_LITE_TRACE").is_err() {
        return;
    }
    use std::io::Write;
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join("lite-trace.log"))
    {
        let _ = writeln!(f, "{} {}", chrono::Local::now().format("%H:%M:%S"), msg);
    }
}

/// WebView2 的启动参数。
///
/// 默认走软件渲染（SwiftShader），不是随手关硬件加速 —— 这是被实际故障逼出来的：
/// 在这台机器上，默认的 GPU/ANGLE 合成路径会让窗口一片空白，DOM 里明明有内容
/// （页面背景设成纯绿，窗口依然是白的），WebView2 的浏览器进程还会抛
/// SubCode=0x80000003 的崩溃转储。换成 `--use-angle=swiftshader` 之后画面立刻正常。
///
/// 这个界面是静态二维布局，软渲染的代价可以忽略，画错的代价不可接受。
/// 需要硬件加速时：设 STUMATE_LITE_HARDWARE_ACCEL=1，或用 STUMATE_LITE_BROWSER_ARGS
/// 直接接管全部参数。
///
/// ⚠️ **每一个 webview 都必须用同一份参数**。WebView2 的「环境」是按
/// `user data folder + 浏览器参数` 缓存的；参数不同就会去建第二个环境，
/// 而同一个数据目录只能有一个环境 —— 结果是建第二个窗口时直接失败：
/// `HRESULT(0x8007139F) 组或资源的状态不是执行请求操作的正确状态`。
/// 提醒浮窗踩过这个坑，所以这里抽成函数，两个窗口共用。
pub(crate) fn browser_args() -> String {
    std::env::var("STUMATE_LITE_BROWSER_ARGS")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| {
            if std::env::var("STUMATE_LITE_HARDWARE_ACCEL").is_ok() {
                String::new()
            } else {
                "--use-angle=swiftshader --disable-gpu-sandbox".to_string()
            }
        })
}

#[tauri::command]
fn app_info(state: State<'_, AppState>) -> AppInfo {
    // 这条记录是「前端确实挂载成功、IPC 确实打通」的探针：前端启动后第一件事就是
    // 调 app_info。
    trace(&state.paths.data_dir, "app_info called — 前端已挂载，IPC 可用");
    let original_present = state.paths.settings_original.is_file()
        || state.paths.data_dir.join("class_reminder.db").is_file();
    AppInfo {
        version: env!("CARGO_PKG_VERSION").to_string(),
        flavor: "lite".into(),
        data_dir: state.paths.data_dir.display().to_string(),
        db_path: state.db.path().display().to_string(),
        schema_version: state.db.schema_version(),
        shared_with_original: true,
        original_present,
        settings_path: state.paths.settings.display().to_string(),
        start_page: std::env::var("STUMATE_LITE_PAGE").ok().filter(|s| !s.is_empty()),
        start_view: std::env::var("STUMATE_LITE_VIEW").ok().filter(|s| !s.is_empty()),
        start_theme: std::env::var("STUMATE_LITE_THEME").ok().filter(|s| !s.is_empty()),
    }
}

/// 启动窗的一票。四个取值：
///   `main`  主窗口数据装载完成（或失败）
///   `anim`  启动窗动画播完 / 用户点了跳过
///   `close` 淡出跑完后的收尾
///   `abort` 主窗口挂载失败，立刻撤窗露出错误页
///
/// 必须是 async：同步命令跑在主线程，而里面要 `close()` / `show()` 窗口，
/// 这些操作要等主线程事件循环回话 —— 自己等自己就死锁了（v1.7.9 的老坑）。
#[tauri::command(async)]
async fn splash_signal(app: tauri::AppHandle, kind: String) {
    let gate = {
        let state = app.state::<AppState>();
        state.splash.clone()
    };
    splash::signal(&app, &gate, &kind);
}

// ── 课程表 ────────────────────────────────────────────────────────

#[tauri::command]
fn classes_list(state: State<'_, AppState>) -> Result<Vec<ClassEntity>, String> {
    state.db.classes()
}

#[tauri::command]
fn class_save(state: State<'_, AppState>, entity: ClassEntity) -> Result<ClassEntity, String> {
    state.db.save_class(entity)
}

#[tauri::command]
fn class_delete(state: State<'_, AppState>, id: i32) -> Result<(), String> {
    state.db.delete_class(id)
}

#[tauri::command]
fn classes_clear(state: State<'_, AppState>) -> Result<(), String> {
    state.db.clear_classes()
}

// ── 便签 ──────────────────────────────────────────────────────────

#[tauri::command]
fn notes_list(state: State<'_, AppState>) -> Result<Vec<NoteEntity>, String> {
    state.db.notes()
}

#[tauri::command]
fn note_save(state: State<'_, AppState>, entity: NoteEntity) -> Result<NoteEntity, String> {
    state.db.save_note(entity)
}

#[tauri::command]
fn note_delete(state: State<'_, AppState>, id: i32) -> Result<(), String> {
    state.db.delete_note(id)
}

#[tauri::command]
fn notes_reorder(state: State<'_, AppState>, ids: Vec<i32>) -> Result<(), String> {
    state.db.reorder_notes(ids)
}

#[tauri::command]
fn notes_clear(state: State<'_, AppState>) -> Result<(), String> {
    state.db.clear_notes()
}

// ── 知识库 ────────────────────────────────────────────────────────

#[tauri::command]
fn knowledge_list(state: State<'_, AppState>) -> Result<Vec<KnowledgeEntity>, String> {
    state.db.knowledge()
}

#[tauri::command]
fn knowledge_save(
    state: State<'_, AppState>,
    entity: KnowledgeEntity,
) -> Result<KnowledgeEntity, String> {
    state.db.save_knowledge(entity)
}

#[tauri::command]
fn knowledge_delete(state: State<'_, AppState>, id: i32) -> Result<(), String> {
    state.db.delete_knowledge(id)
}

#[tauri::command]
fn knowledge_clear(state: State<'_, AppState>) -> Result<(), String> {
    state.db.clear_knowledge()
}

#[tauri::command]
fn knowledge_counts(state: State<'_, AppState>) -> Result<Vec<(String, i32)>, String> {
    state.db.knowledge_counts()
}

// ── 偏好 ──────────────────────────────────────────────────────────

#[tauri::command]
fn prefs_get(state: State<'_, AppState>) -> Value {
    state.prefs_snapshot()
}

/// 单键写入。前端每次只改一个开关，整份回写会让并发写互相覆盖。
#[tauri::command]
fn pref_set(state: State<'_, AppState>, key: String, value: Value) -> Result<Value, String> {
    write_pref(&state, &key, value)
}

/// `pref_set` 的实现。抽出来是因为「关闭时记住选择」也要写偏好，
/// 但那条路径是从命令里调用的，不该再绕一圈 IPC。
fn write_pref(state: &AppState, key: &str, value: Value) -> Result<Value, String> {
    let mut guard = state.prefs.lock().map_err(|_| "偏好锁损坏".to_string())?;
    let mut next = guard.clone();
    if let Some(map) = next.as_object_mut() {
        // v1.7.28：`showPopup` 已被 `reminderMode`（0 全局 / 1 窗口内 / 2 不弹）
        // 取代，但旧版本还在读这个键。写新模式时顺手把它镜像成「非 0 即关」，
        // 这样降级回旧版本也不会出现「设置里关了、旧版却还在弹」。
        if key == "reminderMode" {
            let show = value.as_i64().map(|m| m == 0).unwrap_or(true);
            map.insert("showPopup".into(), Value::from(show));
        }
        map.insert(key.to_string(), value);
    }
    store::save_prefs(&state.paths, &next)?;
    *guard = next.clone();
    Ok(next)
}

// ── 导图 / 附件 ───────────────────────────────────────────────────

#[tauri::command]
fn mind_load(state: State<'_, AppState>) -> Value {
    store::load_mindmap(&state.paths)
}

#[tauri::command]
fn mind_save(state: State<'_, AppState>, nodes: Vec<MindNode>) -> Result<(), String> {
    let value = serde_json::to_value(nodes).map_err(|e| e.to_string())?;
    store::save_mindmap(&state.paths, value)
}

#[tauri::command]
fn links_load(state: State<'_, AppState>) -> Value {
    store::load_links(&state.paths)
}

#[tauri::command]
fn links_save(state: State<'_, AppState>, links: Vec<LinkRef>) -> Result<(), String> {
    let value = serde_json::to_value(links).map_err(|e| e.to_string())?;
    store::save_links(&state.paths, value)
}

// ── 循环提醒（`recurring.json`，Lite 专属旁路存档）──────────────────
//
// 规则的类型定义只在 `src/model/recurring.ts` 一处。Rust 把整条规则当
// `Value` 透传，不解析 kind / anchor / lastFiredAt —— 以后加规则类型不必
// 两端同时改结构。判定与相位推进也全在前端，理由同 `model/reminder.ts`：
// 同一套规则不该有两份各自演化的实现。

#[tauri::command]
fn recurring_load(state: State<'_, AppState>) -> Value {
    store::load_recurring(&state.paths)
}

#[tauri::command]
fn recurring_save(state: State<'_, AppState>, items: Vec<Value>) -> Result<(), String> {
    let value = serde_json::to_value(items).map_err(|e| e.to_string())?;
    store::save_recurring(&state.paths, value)
}

// ── 便签关联（`note_links.json`，Lite 专属旁路存档）────────────────
//
// 结构只在 `src/model/noteLinks.ts` 一处，Rust 整项透传。刻意不给 `notes` 表
// 加列：库与安卓共用，bump schema 会让对方的 fallbackToDestructiveMigration 清库。

#[tauri::command]
fn note_links_load(state: State<'_, AppState>) -> Value {
    store::load_note_links(&state.paths)
}

#[tauri::command]
fn note_links_save(state: State<'_, AppState>, links: Vec<Value>) -> Result<(), String> {
    let value = serde_json::to_value(links).map_err(|e| e.to_string())?;
    store::save_note_links(&state.paths, value)
}

// ── 备份 ──────────────────────────────────────────────────────────

#[tauri::command]
fn backup_export(
    state: State<'_, AppState>,
    modules: Vec<String>,
    path: String,
) -> Result<String, String> {
    let payload = backup::build(&state.db, &state.paths, &state.prefs_snapshot(), &modules)?;
    let target = std::path::PathBuf::from(&path);
    store::write_json_atomic(&target, &payload)?;
    Ok(target.display().to_string())
}

#[tauri::command]
fn backup_inspect(path: String) -> Result<Value, String> {
    let value = store::read_json(std::path::Path::new(&path))
        .ok_or("文件读不出来，或者不是合法的 JSON")?;
    Ok(backup::describe(&value))
}

#[tauri::command]
fn backup_import(
    state: State<'_, AppState>,
    path: String,
    modules: Vec<String>,
) -> Result<String, String> {
    let value = store::read_json(std::path::Path::new(&path))
        .ok_or("文件读不出来，或者不是合法的 JSON")?;
    let report = backup::apply(&state.db, &state.paths, &value, &modules)?;
    // 设置可能被覆盖，缓存要跟着刷新
    if report.settings {
        if let Ok(mut guard) = state.prefs.lock() {
            *guard = store::load_prefs(&state.paths);
        }
    }
    Ok(format!(
        "已导入：课程 {} 条、便签 {} 条、知识 {} 条、循环提醒 {} 条{}",
        report.courses,
        report.notes,
        report.knowledge,
        report.recurring,
        if report.settings { "、设置" } else { "" }
    ))
}

// ── 系统集成 ──────────────────────────────────────────────────────

#[tauri::command]
fn open_data_dir(state: State<'_, AppState>) -> Result<(), String> {
    open::that(&state.paths.data_dir).map_err(|e| e.to_string())
}

#[tauri::command]
fn open_external(target: String) -> Result<(), String> {
    open::that(&target).map_err(|e| e.to_string())
}

#[tauri::command]
fn path_exists(path: String) -> bool {
    std::path::Path::new(&path).exists()
}

/// 选一个「另存为」路径。取消返回 null。
#[tauri::command]
fn pick_save_path(default_name: String) -> Option<String> {
    rfd::FileDialog::new()
        .set_file_name(&default_name)
        .add_filter("JSON", &["json"])
        .save_file()
        .map(|p| p.display().to_string())
}

/// 选一个已有的备份文件。取消返回 null。
#[tauri::command]
fn pick_open_path() -> Option<String> {
    rfd::FileDialog::new()
        .add_filter("JSON", &["json"])
        .pick_file()
        .map(|p| p.display().to_string())
}

/// 读任意文件的原始字节。课表 PDF 解析在前端做（`src/model/timetablePdf.ts`），
/// 这里只负责把字节递过去 —— 解析逻辑要能被 vitest 钉住，不能藏在 Rust 里。
#[tauri::command]
fn read_file_bytes(path: String) -> Result<Vec<u8>, String> {
    std::fs::read(&path).map_err(|e| format!("读文件失败：{e}"))
}

/// 选一个 PDF 文件。
#[tauri::command]
fn pick_pdf_path() -> Option<String> {
    rfd::FileDialog::new()
        .add_filter("PDF", &["pdf"])
        .pick_file()
        .map(|p| p.display().to_string())
}

/// 选一个本地文件做「相关文件」附件。
#[tauri::command]
fn pick_attachment() -> Option<String> {
    rfd::FileDialog::new()
        .pick_file()
        .map(|p| p.display().to_string())
}

// ── Obsidian vault 导出 ───────────────────────────────────────────

/// 选一个 Obsidian 库目录（vault 根）。取消返回 null。
#[tauri::command]
fn pick_vault_dir() -> Option<String> {
    rfd::FileDialog::new()
        .set_title("选择 Obsidian 库目录（vault 根）")
        .pick_folder()
        .map(|p| p.display().to_string())
}

/// 把前端铺好的文件写进 vault。
///
/// 路径与重名规则在前端（`src/model/vault.ts`，有单测）；这里只做路径校验与落盘。
/// 返回 `written / failed / errors`，让界面能如实说「写了几篇、几篇失败」，
/// 而不是笼统报一个「导出成功」——半成功是最容易被忽略的那种错。
#[tauri::command]
fn vault_export(root: String, files: Vec<vault::VaultFile>) -> Result<vault::VaultReport, String> {
    vault::write_files(std::path::Path::new(&root), &files)
}

// ── 知识库（库根下的 Stumate/ 子树）─────────────────────────────────
//
// 这几个命令的 `base` 都是**用户选的库根**，不是 `Stumate/` 本身：
// 子目录半径由 Rust 决定（`vault::sub_root`），前端连传错的机会都没有。

/// 解析出真正的库目录（会顺手建出来），给界面显示与「在资源管理器中打开」用。
#[tauri::command]
fn vault_root(base: String) -> Result<String, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    Ok(root.display().to_string())
}

#[tauri::command]
fn vault_scan(base: String) -> Result<Vec<vault::VaultEntry>, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    Ok(vault::scan(&root))
}

/// 树的版本号。前端轮询它来决定要不要重扫，见 `vault::rev` 的注释。
#[tauri::command]
fn vault_rev(base: String) -> String {
    match vault::sub_root(std::path::Path::new(&base)) {
        Ok(root) => vault::rev(&root),
        Err(_) => String::new(),
    }
}

#[tauri::command]
fn vault_read(base: String, rel: String) -> Result<vault::NoteContent, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::read_note(&root, &rel)
}

/// `expected` 为读到的 mtime；与磁盘不一致时返回 `conflict: true` 且不写入。
#[tauri::command]
fn vault_write(
    base: String,
    rel: String,
    content: String,
    expected: Option<i64>,
) -> Result<vault::WriteAck, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::write_note(&root, &rel, &content, expected)
}

#[tauri::command]
fn vault_mkdir(base: String, rel: String) -> Result<(), String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::mkdir(&root, &rel)
}

#[tauri::command]
fn vault_rename(base: String, from: String, to: String) -> Result<(), String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::rename(&root, &from, &to)
}

#[tauri::command]
fn vault_trash(base: String, rel: String) -> Result<String, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::trash(&root, &rel)
}

// 回收站（v1.7.14）：删除只挪进 .trash，这四个命令让界面能真的找回 / 清掉。
// `name` 是 `.trash` 里的纯文件名，路径合法性由 vault.rs 逐个校验。

#[tauri::command]
fn vault_trash_list(base: String) -> Result<Vec<vault::TrashItem>, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    Ok(vault::trash_list(&root))
}

#[tauri::command]
fn vault_trash_restore(base: String, name: String) -> Result<String, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::trash_restore(&root, &name)
}

#[tauri::command]
fn vault_trash_purge(base: String, name: String) -> Result<(), String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::trash_purge(&root, &name)
}

#[tauri::command]
fn vault_trash_empty(base: String) -> Result<usize, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    vault::trash_empty(&root)
}

/// 全文搜索。不做索引，理由见 `vault::grep`。
#[tauri::command]
fn vault_grep(base: String, needle: String) -> Result<Vec<vault::GrepHit>, String> {
    let root = vault::sub_root(std::path::Path::new(&base))?;
    Ok(vault::grep(&root, &needle))
}

/// 在同一个进程里再开一个「只有知识库」的窗口。
///
/// 同一进程而不是第二个可执行文件：两个窗口共用同一份 `AppState`（库路径、偏好），
/// 也就不会出现「两个实例同时读写同一份设置」的问题。知识库本身不碰 SQLite，
/// 所以这里连数据库竞争都不存在。
#[tauri::command(async)]
fn open_knowledge_window(app: tauri::AppHandle) -> Result<(), String> {
    use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

    if let Some(existing) = app.get_webview_window("knowledge") {
        let _ = existing.set_focus();
        return Ok(());
    }
    let mut builder = WebviewWindowBuilder::new(
        &app,
        "knowledge",
        WebviewUrl::App("index.html?page=knowledge&solo=1".into()),
    )
    .title("StuMate 知识库")
    .inner_size(1100.0, 760.0)
    .min_inner_size(760.0, 520.0)
    .decorations(false);

    // 必须和主窗口用同一份浏览器参数。WebView2 的「环境」是按
    // `用户数据目录 + 浏览器参数` 缓存的，参数不同会去建第二个环境，
    // 而一个数据目录只能有一个 —— 第二个窗口会**建出来但一片空白**。
    let args = browser_args();
    if !args.is_empty() {
        builder = builder.additional_browser_args(&args);
    }

    builder
        .build()
        .map(|_| ())
        .map_err(|e| format!("打开知识库窗口失败：{e}"))
}

/// 用 Markdowner 编辑某篇笔记：另开一个 WebView 窗口，指向打包进来的 Markdowner 静态资源。
///
/// 为什么不是「在知识库页里自己实现高亮和预览」：Markdowner 已经是功能完整的编辑器
/// （语法高亮、实时预览、KaTeX 公式、查找替换、大纲、命令面板），重写一遍只会得到
/// 一个更弱的版本。这里只负责把它接到文件上 —— 读写走 `vault_read` / `vault_write`，
/// 由 `public/markdowner/stumate-bridge.js` 接管它的持久化路径。
///
/// 窗口用系统装饰（`decorations` 默认 true）：Markdowner 的标题栏是应用内工具条，
/// 没有最小化/关闭键，去掉系统装饰就会变成一个关不掉的窗口。
#[tauri::command(async)]
fn open_markdown_window(app: tauri::AppHandle, base: String, rel: String) -> Result<(), String> {
    use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

    // 窗口标题带上完整相对路径，不只是文件名：它同时充当「当前开的是哪一篇」的凭据
    // （下面用它判断要不要重建窗口），不同目录下的同名文件靠这个区分开。
    let wanted_title = format!("Markdowner — {rel}");

    // 已经开着就先看是不是同一篇：是就只聚焦，不是就换掉 ——
    // WebView 建好之后 URL 改不了，只能重建窗口。
    if let Some(existing) = app.get_webview_window("markdowner") {
        let same = existing
            .title()
            .map(|t| t == wanted_title)
            .unwrap_or(false);
        if same {
            let _ = existing.set_focus();
            return Ok(());
        }
        // close 是异步的：它只是往事件循环投一条消息。紧接着用同一个 label 建窗口
        // 会撞上 "a webview with label `markdowner` already exists"，所以这里等它真的消失。
        let _ = existing.close();
        for _ in 0..80 {
            if app.get_webview_window("markdowner").is_none() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
    }

    // release 版是 windows_subsystem="windows"，`eprintln!` 没有控制台可写，
    // 所以诊断走 `trace`（`STUMATE_LITE_TRACE=1` 时才落盘）。
    let trace_dir = std::env::var("APPDATA")
        .map(std::path::PathBuf::from)
        .map(|p| p.join("StuMate"))
        .unwrap_or_else(|_| std::path::PathBuf::from("."));
    trace(&trace_dir, &format!("open_markdown_window 进入 base={base} rel={rel}"));

    // 参数不走 URL query，走 initialization_script。
    //
    // 原因：`WebviewUrl::App("...index.html?base=...&rel=...")` 在这里会导航失败
    // （窗口建出来停在 about:blank）—— asset 协议那边把整个字符串当成了文件路径。
    // 初始化脚本在页面任何脚本之前执行，比 query 更可靠，也不用操心百分号编码。
    let payload = format!(
        "window.__STUMATE_ARGS = {{ base: {}, rel: {} }};",
        serde_json::to_string(&base).map_err(|e| e.to_string())?,
        serde_json::to_string(&rel).map_err(|e| e.to_string())?,
    );

    let mut builder = WebviewWindowBuilder::new(
        &app,
        "markdowner",
        WebviewUrl::App("markdowner/index.html".into()),
    )
    .title(&wanted_title)
    .inner_size(1180.0, 800.0)
    .min_inner_size(720.0, 480.0)
    .initialization_script(&payload)
    .on_page_load(|_wv, p| {
        let dir = std::env::var("APPDATA")
            .map(std::path::PathBuf::from)
            .map(|d| d.join("StuMate"))
            .unwrap_or_else(|_| std::path::PathBuf::from("."));
        trace(&dir, &format!("page_load {:?} {}", p.event(), p.url()));
    });

    // 同 `open_knowledge_window`：参数必须与主窗口一致，否则 WebView2 会去建
    // 第二个环境（同一数据目录只允许一个），窗口能建出来但内容一片空白。
    let args = browser_args();
    trace(&trace_dir, &format!("browser_args={args:?}"));
    if !args.is_empty() {
        builder = builder.additional_browser_args(&args);
    }

    let win = match builder.build() {
        Ok(w) => {
            trace(&trace_dir, "build 成功");
            w
        }
        Err(e) => {
            trace(&trace_dir, &format!("build 失败：{e}"));
            return Err(format!("打开 Markdowner 窗口失败：{e}"));
        }
    };

    // 建完之后再看一次 URL：Tauri 的导航投递到事件循环，build 返回时可能还是
    // about:blank。留一小段让它落定，仅用于诊断。
    std::thread::sleep(std::time::Duration::from_millis(150));
    match win.url() {
        Ok(u) => trace(&trace_dir, &format!("静置后 url={u}")),
        Err(e) => trace(&trace_dir, &format!("读 url 失败：{e}")),
    }
    trace(
        &trace_dir,
        &format!("窗口可见={:?}", win.is_visible().unwrap_or(false)),
    );
    Ok(())
}

// ── 关闭行为 / 开机自启 ───────────────────────────────────────────

/// 关闭确认框里用户点了哪个按钮。
///
/// `remember` 对应「下次不再提问」：勾了就只写**动作**那一档
/// （1 或 2），不会把「询问」写进去 —— 那等于把用户的勾选丢掉。
#[tauri::command]
fn close_resolve(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    action: i64,
    remember: bool,
) -> Result<(), String> {
    let action = action.clamp(tray::CLOSE_ASK, tray::CLOSE_QUIT);
    if remember && action != tray::CLOSE_ASK {
        write_pref(&state, "closeAction", serde_json::json!(action))?;
    }
    match action {
        tray::CLOSE_TO_TRAY => {
            if let Some(win) = app.get_webview_window("main") {
                let _ = win.hide();
            }
        }
        tray::CLOSE_QUIT => app.exit(0),
        _ => {}
    }
    Ok(())
}

/// 开机自启的当前状态。
///
/// 读注册表而不是读偏好：注册表才是事实源。用户在别处（任务管理器 / 系统设置）
/// 关掉过启动项时，读偏好会显示"开着"却根本不生效 —— 那种开关点起来最迷惑。
#[tauri::command]
fn autostart_get() -> bool {
    autostart::is_enabled()
}

/// 写入 / 删除自启项。返回**实际操作结果**，失败时由前端提示。
#[tauri::command]
fn autostart_set(state: State<'_, AppState>, enabled: bool) -> Result<bool, String> {
    let ok = autostart::set_enabled(enabled);
    // 只在真的写成功时才同步偏好，避免设置页与实际注册表状态打架
    if ok {
        write_pref(&state, "autoStart", serde_json::json!(enabled))?;
    }
    Ok(ok)
}

/// 更新托盘状态文字（菜单两行 + tooltip）。
///
/// 判定在前端（复用 `src/model/today.ts`），这里只负责显示 ——
/// 这样"托盘说正在上课"和"今天页说正在上课"永远出自同一份计算。
#[tauri::command]
fn tray_status(app: tauri::AppHandle, title: String, detail: String) {
    tray::set_status(&app, &title, &detail);
}

pub fn run() {
    let paths = Paths::resolve();

    // 自动化验收（CDP 截图主界面）用 NO_SPLASH 直接跳过启动窗，
    // 免得每次都要等动画。正常启动一律启用。
    let splash_enabled = std::env::var("STUMATE_LITE_NO_SPLASH").is_err();

    // 数据库打不开时**不要 panic**：那会变成一个没有窗口、没有提示的僵尸进程。
    // 用弹窗把原因（通常是「老 schema，请先开一次原版」）原样给用户，然后正常退出。
    let db = match db::Db::open(&paths.db) {
        Ok(db) => db,
        Err(err) => {
            rfd::MessageDialog::new()
                .set_title("StuMate Lite 无法打开数据库")
                .set_description(&err)
                .set_level(rfd::MessageLevel::Error)
                .show();
            std::process::exit(1);
        }
    };

    let prefs = store::load_prefs(&paths);

    // 无头自检：`STUMATE_LITE_SELFTEST=1` 时只跑一遍数据层，打印结果后退出。
    // 用途是验证「Lite 确实能读原版那份库」——窗口截图证明不了这件事，
    // 而这正是两版共用数据里最容易出问题的一环（schema 版本、列名、WAL 并发）。
    if std::env::var("STUMATE_LITE_SELFTEST").is_ok() {
        let classes = db.classes();
        let notes = db.notes();
        let knowledge = db.knowledge();
        let report = serde_json::json!({
            "db": paths.db.display().to_string(),
            "schemaVersion": db.schema_version(),
            "classes": classes.as_ref().map(|v| v.len()).unwrap_or(0),
            "notes": notes.as_ref().map(|v| v.len()).unwrap_or(0),
            "knowledge": knowledge.as_ref().map(|v| v.len()).unwrap_or(0),
            "errors": [
                classes.err().map(|e| format!("classes: {e}")),
                notes.err().map(|e| format!("notes: {e}")),
                knowledge.err().map(|e| format!("knowledge: {e}")),
            ],
            "mindmap": store::load_mindmap(&paths).get("nodes").and_then(|v| v.as_array()).map(|v| v.len()).unwrap_or(0),
            "links": store::load_links(&paths).get("links").and_then(|v| v.as_array()).map(|v| v.len()).unwrap_or(0),
            "prefsThemeMode": prefs.get("themeMode").cloned().unwrap_or(serde_json::Value::Null),
        });
        println!("{}", serde_json::to_string_pretty(&report).unwrap_or_default());
        return;
    }

    tauri::Builder::default()
        .manage(AppState {
            db,
            paths,
            prefs: Mutex::new(prefs),
            alert: Mutex::new(None),
            splash: splash::new_gate(splash_enabled),
        })
        // 窗口在代码里建，而不是写在 tauri.conf.json：
        //  - 需要按 `STUMATE_LITE_SCREEN` 决定放到哪块屏（验收截图时不要压住用户的主屏）
        //  - 需要按 `STUMATE_LITE_BROWSER_ARGS` 追加 WebView2 启动参数（开调试端口截图）
        // 这两件事都只在设了环境变量时才生效，正常启动就是一个普通的 1200×800 无边框窗口。
        .setup(|app| {
            use tauri::{Manager as _, WebviewUrl, WebviewWindowBuilder};

            let data_dir = app.state::<AppState>().paths.data_dir.clone();

            // 启动窗和主窗口必须用**同一份** WebView2 启动参数（理由见 browser_args
            // 的注释），所以在这里先算一次，两处都取自它。
            let args = browser_args();

            // 启动窗先建、先画：第一个 webview 会初始化 WebView2 环境，
            // 后建的主窗口复用它。主窗口这期间保持隐藏，等 splash.rs 两票齐了再亮出来。
            let splash_enabled = app
                .state::<AppState>()
                .splash
                .lock()
                .map(|g| g.enabled)
                .unwrap_or(false);
            if splash_enabled {
                let prefs_value = app.state::<AppState>().prefs_snapshot();
                let mut theme_mode = prefs_value
                    .get("themeMode")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0);
                // 验收时的强制主题优先于偏好（与 App.tsx 的 startTheme 同口径）。
                match std::env::var("STUMATE_LITE_THEME").as_deref() {
                    Ok("light") => theme_mode = 1,
                    Ok("dark") => theme_mode = 2,
                    _ => {}
                }
                let cfg = serde_json::json!({
                    "themeMode": theme_mode,
                    "version": env!("CARGO_PKG_VERSION"),
                    // 截图验收时让启动窗多停一会儿，不必改代码。
                    "holdMs": if std::env::var("STUMATE_LITE_SPLASH_HOLD").is_ok() { 20000 } else { 0 },
                });
                splash::build(app.handle(), &args, &cfg)?;
                splash::spawn_watchdog(
                    app.handle().clone(),
                    app.state::<AppState>().splash.clone(),
                );
            }

            // 目标屏（1 起算）。尺寸必须在**创建窗口时**就定好，不能建完再 set_size ——
            // 实测 set_size 返回 Ok 却没生效（outer 仍是原来的 2432×1618）。
            // 另外副屏可能比窗口还小（本机副屏逻辑尺寸只有 800×1280），
            // 所以最小尺寸也要跟着放宽，否则窗口管理器会把宽度夹回 900。
            let target = std::env::var("STUMATE_LITE_SCREEN")
                .ok()
                .and_then(|raw| raw.parse::<usize>().ok())
                .and_then(|index| {
                    let monitors = app.available_monitors().ok()?;
                    trace(
                        &data_dir,
                        &format!(
                            "screen 请求 {}，可用显示器 {} 个：{:?}",
                            index,
                            monitors.len(),
                            monitors
                                .iter()
                                .map(|m| (
                                    m.position().x,
                                    m.position().y,
                                    m.size().width,
                                    m.size().height,
                                    m.scale_factor()
                                ))
                                .collect::<Vec<_>>()
                        ),
                    );
                    monitors.get(index.saturating_sub(1)).cloned()
                });

            let builder = WebviewWindowBuilder::new(app, "main", {
                // `--kb`：直接开成「知识库专用窗口」——
                // 前端读到 URL 里的 `solo=1` 会把侧栏藏掉，剩下就是一棵树 + 一个编辑器。
                // 用 URL 而不是环境变量：同一个进程里再开第二个知识库窗口时，
                // 两个窗口必须能各自决定长什么样，环境变量做不到这件事。
                if std::env::args().any(|a| a == "--kb") {
                    WebviewUrl::App("index.html?page=knowledge&solo=1".into())
                } else {
                    WebviewUrl::default()
                }
            })
            .title("StuMate Lite")
            .decorations(false)
            .resizable(true)
            .visible(!splash_enabled);

            let mut builder = match &target {
                Some(monitor) => {
                    const MARGIN: f64 = 24.0;
                    let scale = monitor.scale_factor();
                    let logical = monitor.size().to_logical::<f64>(scale);
                    let origin = monitor.position().to_logical::<f64>(scale);

                    let w = (logical.width - MARGIN * 2.0).clamp(560.0, 1200.0);
                    let h = (logical.height - MARGIN * 2.0).clamp(480.0, 800.0);

                    trace(
                        &data_dir,
                        &format!(
                            "screen 目标逻辑尺寸 {:.0}x{:.0}（scale {:.2}）→ 窗口 {:.0}x{:.0} 位置 ({:.0},{:.0})",
                            logical.width,
                            logical.height,
                            scale,
                            w,
                            h,
                            origin.x + (logical.width - w) / 2.0,
                            origin.y + (logical.height - h) / 2.0
                        ),
                    );

                    builder
                        .inner_size(w, h)
                        .min_inner_size(560.0, 480.0)
                        .position(origin.x + (logical.width - w) / 2.0,
                                  origin.y + (logical.height - h) / 2.0)
                }
                None => builder
                    .inner_size(1200.0, 800.0)
                    .min_inner_size(900.0, 620.0)
                    .center(),
            };

            // WebView2 的启动参数。
            //
            // 默认走软件渲染（SwiftShader），不是随手关硬件加速 —— 这是被实际故障逼出来的：
            // 在这台机器上，默认的 GPU/ANGLE 合成路径会让窗口一片空白，DOM 里明明有内容
            // （页面背景设成纯绿，窗口依然是白的），WebView2 的浏览器进程还会抛
            // SubCode=0x80000003 的崩溃转储。换成 --use-angle=swiftshader 之后画面立刻正常。
            //
            // 这个界面是静态二维布局，软渲染的代价可以忽略，画错的代价不可接受。
            // 需要硬件加速时：设 STUMATE_LITE_HARDWARE_ACCEL=1，或用 STUMATE_LITE_BROWSER_ARGS
            // 直接接管全部参数。`args` 在 setup 开头已算好，启动窗用的是同一份。
            if !args.is_empty() {
                builder = builder.additional_browser_args(&args);
            }

            // 截图用的置顶开关。外部进程没法把这个窗口抬到前台（SetWindowPos 会被
            // 系统的前台锁拒掉），只有窗口自己声明置顶才可靠。
            if std::env::var("STUMATE_LITE_TOPMOST").is_ok() {
                builder = builder.always_on_top(true).focused(true);
            }

            let _win = builder.build()?;

            // 托盘在窗口之后建：菜单里的「打开」要能找到 main 窗口，
            // 窗口先存在一步，读代码时顺序才顺。
            tray::build(app)?;
            trace(&data_dir, "托盘已创建");

            Ok(())
        })
        // 关闭按钮的分流放在这里，而不是交给前端自己判断：
        // 用户可能用任务栏右键「关闭」、Alt+F4，这些路径都不经过页面里的按钮。
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                // 这个回调是**应用级**的，所有窗口共用 —— 包括 Markdowner 子窗口。
                // 而 `handle_close_request` 是给主窗口写的策略（收进托盘 / 退出应用），
                // 套在子窗口上就变成「点一下子窗口的 ×，整个应用没了」。
                //
                // 子窗口的关闭语义很简单：关它自己。什么都不做就是默认行为，
                // 主窗口和托盘都不受影响。
                if window.label() != "main" {
                    return;
                }
                tray::handle_close_request(window, api);
            }
        })
        .invoke_handler(tauri::generate_handler![
            app_info,
            splash_signal,
            classes_list,
            class_save,
            class_delete,
            classes_clear,
            notes_list,
            note_save,
            note_delete,
            notes_reorder,
            notes_clear,
            knowledge_list,
            knowledge_save,
            knowledge_delete,
            knowledge_clear,
            knowledge_counts,
            prefs_get,
            pref_set,
            mind_load,
            mind_save,
            links_load,
            links_save,
            recurring_load,
            recurring_save,
            note_links_load,
            note_links_save,
            backup_export,
            backup_inspect,
            backup_import,
            open_data_dir,
            open_external,
            path_exists,
            pick_save_path,
            pick_open_path,
            pick_attachment,
            pick_vault_dir,
            vault_export,
            vault_root,
            vault_scan,
            vault_rev,
            vault_read,
            vault_write,
            vault_mkdir,
            vault_rename,
            vault_trash,
            vault_trash_list,
            vault_trash_restore,
            vault_trash_purge,
            vault_trash_empty,
            vault_grep,
            open_knowledge_window,
            open_markdown_window,
            read_file_bytes,
            pick_pdf_path,
            close_resolve,
            autostart_get,
            autostart_set,
            tray_status,
            overlay::overlay_show,
            overlay::overlay_state,
            overlay::overlay_hide,
            overlay::overlay_skip,
            overlay::notify_blocked,
        ])
        .run(tauri::generate_context!())
        .expect("Tauri 启动失败");
}
