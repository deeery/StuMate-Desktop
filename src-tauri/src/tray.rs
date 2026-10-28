//! 系统托盘 + 关闭按钮行为。
//!
//! 对应原版 `Main.kt` 里的 `Tray { ... }` 与 `handleCloseRequest()`。
//! 语义必须一致，因为两版的设置项是同一套（`settings.lite.json` 里的 `closeAction`）：
//!
//! | closeAction | 原版 | Lite |
//! |---|---|---|
//! | 0 询问 | 弹 `CloseConfirmDialog` | 发事件给前端，弹同一个对话框 |
//! | 1 最小化到托盘 | `windowVisible = false` | `window.hide()` |
//! | 2 直接退出 | `exitApplication()` | `app.exit(0)` |
//!
//! **「询问」这一档为什么不在 Rust 里用系统对话框**：原版的对话框带「下次不再提问」
//! 勾选框和三个按钮（退出 / 取消 / 最小化到托盘），`rfd` 只能给 Ok/Cancel 两档，
//! 拼不出来。所以走「Rust 拦下关闭 → 通知前端 → 前端用 Fluent 对话框渲染」，
//! 视觉和交互都与原版一致。

use crate::AppState;
use serde_json::Value;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager};

/// 关闭时询问（默认）
pub const CLOSE_ASK: i64 = 0;
/// 关闭时最小化到托盘，应用继续后台运行
pub const CLOSE_TO_TRAY: i64 = 1;
/// 关闭时直接退出
pub const CLOSE_QUIT: i64 = 2;

const TRAY_ID: &str = "stumate-lite-tray";

/// 前端监听这个事件来弹关闭确认框。
pub const EVENT_CLOSE_REQUESTED: &str = "stumate://close-requested";

/// 托盘菜单里点「立即检查一次」时发给前端的事件。
pub const EVENT_CHECK_NOW: &str = "stumate://check-now";

/// 托盘菜单里那两行**可变**状态文字。
///
/// 原版 `Tray { menu = { Item(status.title) ... } }` 每次重组都会重建菜单，
/// 所以状态自然是活的。Tauri 的菜单是原生对象，建好之后只能改文字，
/// 因此要把这两个 `MenuItem` 存下来（交给 Tauri 的 state 管）。
pub struct TrayStatus {
    pub title: MenuItem<tauri::Wry>,
    pub detail: MenuItem<tauri::Wry>,
}

/// 更新托盘状态：菜单里两行文字 + 图标 tooltip 一起改。
///
/// 对应原版「托盘 tooltip = `StuMate · ${status.title}`」与菜单里的状态行。
pub fn set_status(app: &AppHandle, title: &str, detail: &str) {
    if let Some(state) = app.try_state::<TrayStatus>() {
        let _ = state.title.set_text(title);
        let _ = state.detail.set_text(detail);
    }
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        let _ = tray.set_tooltip(Some(format!("StuMate Lite · {title}")));
    }
}

/// 读 `closeAction` 偏好。越界值一律按「询问」处理 ——
/// 宁可多问一次，也不要因为一个坏值把窗口关掉。
pub fn close_action(app: &AppHandle) -> i64 {
    app.state::<AppState>()
        .prefs_snapshot()
        .get("closeAction")
        .and_then(Value::as_i64)
        .unwrap_or(CLOSE_ASK)
        .clamp(CLOSE_ASK, CLOSE_QUIT)
}

/// 把主窗口叫回来。托盘点击和菜单「打开」都走这里。
///
/// 三步缺一不可：窗口可能是被 `hide()` 藏起来的（要 `show`），
/// 也可能是最小化到任务栏的（要 `unminimize`），最后才谈得上聚焦。
pub fn show_main(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.show();
        let _ = win.unminimize();
        let _ = win.set_focus();
    }
}

/// 建托盘图标与菜单。
///
/// 托盘对象本身不用保存：`TrayIconBuilder::build` 内部会把图标注册进
/// Tauri 的资源表（`resources_table().add(...)`）并记进 `manager.tray.icons`，
/// 所以返回值丢掉也不会消失。这里显式 `let _ =` 只是为了表明"故意的"。
pub fn build(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "打开 StuMate Lite", true, None::<&str>)?;
    // 两行状态：不可点击，只用来显示（对应原版的动态前台通知）
    let status_title = MenuItem::with_id(app, "status_title", "运行中", false, None::<&str>)?;
    let status_detail = MenuItem::with_id(app, "status_detail", "今日无课", false, None::<&str>)?;
    let check_now = MenuItem::with_id(app, "check_now", "立即检查一次", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;

    let sep = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(
        app,
        &[&open, &sep, &status_title, &status_detail, &sep, &check_now, &sep, &quit],
    )?;

    app.manage(TrayStatus {
        title: status_title,
        detail: status_detail,
    });

    let mut builder = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("StuMate Lite")
        .menu(&menu)
        // 左键单击 = 唤回窗口，右键才出菜单。默认是左键也弹菜单，
        // 那会让「点一下打开」变成「点一下先看到菜单」，多一次点击。
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "check_now" => {
                // 判定逻辑在前端（复用 today.ts），这里只负责转达
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.emit(EVENT_CHECK_NOW, ());
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        });

    // 托盘图标：优先用打包时嵌入的窗口图标（和任务栏一致），
    // 拿不到就退回编译期写死的那张 PNG。
    //
    // 这个兜底不是多余的：`TrayIconBuilder` 在 Windows 上**没有图标就 build 失败**，
    // 而失败点在 `setup` 里 —— 那会让整个应用起不来，症状是"双击没反应"，
    // 排查方向会被引到完全不相干的地方。宁可多带 4 KB，也不要这种失败模式。
    let icon = app
        .default_window_icon()
        .cloned()
        .or_else(|| tauri::image::Image::from_bytes(include_bytes!("../icons/32x32.png")).ok());

    match icon {
        Some(icon) => builder = builder.icon(icon),
        // 两个来源都拿不到就干脆不建托盘，至少窗口还能用
        None => return Ok(()),
    }

    let _ = builder.build(app)?;
    Ok(())
}

/// 处理关闭请求：按 `closeAction` 分流。
///
/// 三条分支都先 `prevent_close()`，因为：
/// - 询问档要等用户在前端点按钮，不能先关掉；
/// - 托盘档本来就不该关；
/// - 退出档用 `app.exit(0)` 走完整退出流程（比让窗口自然关闭更确定：
///   托盘存在时"最后一个窗口关闭"不一定会退出进程）。
pub fn handle_close_request(window: &tauri::Window, api: &tauri::CloseRequestApi) {
    let app = window.app_handle();
    api.prevent_close();

    match close_action(app) {
        CLOSE_TO_TRAY => {
            let _ = window.hide();
        }
        CLOSE_QUIT => app.exit(0),
        _ => {
            // 前端没挂起来时（页面崩了 / 还没加载完）事件没人接，
            // 那就会变成一个关不掉的窗口。这种情况直接退，别把用户困住。
            if window.emit(EVENT_CLOSE_REQUESTED, ()).is_err() {
                app.exit(0);
            }
        }
    }
}
