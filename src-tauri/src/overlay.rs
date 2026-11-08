//! 上课提醒浮窗（置顶卡片）。
//!
//! 对应原版 `ui/fluent/OverlayScreen.kt` 的 `OverlayWindow`：一个**无边框、置顶、
//! 不进任务栏**的小窗口，在屏幕正上方居中弹出。
//!
//! 位置口径与原版一致：**工作区（已扣掉任务栏）水平中线、距上沿 24px**。
//! 不用"右下角"是因为那一片经常被输入法候选框和系统通知占用；
//! 原版试过全屏透明窗口，在 Compose 上走不通，最后落在"小窗口 + 正上方居中"。
//!
//! 数据传递用「状态 + 事件」两条腿：
//!   · 窗口**新建**时页面还在加载，此刻 emit 事件会丢 —— 所以把最后一帧 alert
//!     存进 `AppState.alert`，页面挂载后主动 `overlay_state()` 取一次。
//!   · 窗口**已存在**时直接 emit，避免重建窗口导致闪烁。
//!
//! ## 建窗为什么要用 `async` 命令
//!
//! `WebviewWindowBuilder::build()` 会把自己**派发到主线程**执行，然后等结果。
//! 而 `#[tauri::command]` 的**同步**函数默认就在主线程上跑 —— 于是主线程在等
//! 一个「排队等主线程」的任务，经典自死锁。
//!
//! 症状极具迷惑性：窗口**建出来了、位置也对**（build 内部的前半段已经执行完），
//! 但 `build()` 永不返回，后面所有代码（发事件、显示、日志）都不执行。
//! 排查时探针日志停在 build 之前那一行，看着像"建窗卡住"，其实是"主线程卡住"。
//!
//! 改成 `async fn` 后命令跑在异步运行时线程上，`build()` 的等待才有人来满足。
//!
//! 另外两条实测结论也记在这里，免得以后重走：
//! · 在 `setup` 里预建第二个 WebView 会失败：`WebView2 HRESULT(0x8007139F)
//!   组或资源的状态不是执行请求操作的正确状态` —— 主 WebView 还没初始化完。
//! · 所以窗口是「第一次要提醒时才建」，之后复用（`hide` 而不是 `close`）。

use crate::AppState;
use serde_json::Value;
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};

pub const LABEL: &str = "overlay";

/// 提醒内容更新时发的事件（窗口已存在的情况）。
pub const EVENT_UPDATE: &str = "stumate://overlay-update";

/// 浮窗上点了「跳过本次」。载荷 `{ ruleId }`，由主窗口收下后推进相位。
pub const EVENT_SKIP: &str = "stumate://recurring-skip";

/// 卡片尺寸（逻辑像素），取自原版 `CARD_WIDTH/CARD_HEIGHT = 460x176 dp`。
const CARD_W: f64 = 460.0;
const CARD_H: f64 = 176.0;

/// 距工作区上沿的留白。24 比系统通知的 16 略大 —— 顶部横条压得太贴边会显得是系统级 UI。
const TOP_MARGIN: f64 = 24.0;

/// 两个矩形的重叠面积（物理像素）。无重叠返回 0。
fn overlap(a: (i32, i32, i32, i32), b: (i32, i32, i32, i32)) -> i64 {
    let left = a.0.max(b.0);
    let top = a.1.max(b.1);
    let right = a.2.min(b.2);
    let bottom = a.3.min(b.3);
    if right <= left || bottom <= top {
        0
    } else {
        (right - left) as i64 * (bottom - top) as i64
    }
}

/// 找出主窗口实际"压"在哪块屏上：取与窗口矩形重叠面积最大的那块。
///
/// 为什么不直接用 `window.current_monitor()`：混合 DPI 下它的返回值不一定跟着窗口走
/// （实测踩过 —— 主窗口明明被挪到副屏，`current_monitor()` 仍报主屏，卡片就弹回主屏去了）。
/// 用几何求交不依赖任何窗口→显示器映射，多屏/竖屏/负坐标（副屏摆在主屏左边）都成立。
/// 窗口整个在屏幕外时面积全为 0，此时退回 `current_monitor()`，再退回主屏。
fn monitor_for_window(app: &AppHandle) -> Option<tauri::Monitor> {
    let win_rect = app.get_webview_window("main").and_then(|w| {
        let pos = w.outer_position().ok()?;
        let size = w.outer_size().ok()?;
        Some((
            pos.x,
            pos.y,
            pos.x + size.width as i32,
            pos.y + size.height as i32,
        ))
    });

    if let Some(rect) = win_rect {
        let monitors = app.available_monitors().unwrap_or_default();
        let mut best: Option<(i64, tauri::Monitor)> = None;
        for m in monitors {
            let p = m.position();
            let s = m.size();
            let area = overlap(
                rect,
                (p.x, p.y, p.x + s.width as i32, p.y + s.height as i32),
            );
            let better = match best.as_ref() {
                Some((best_area, _)) => area > *best_area,
                None => true,
            };
            if area > 0 && better {
                best = Some((area, m));
            }
        }
        if let Some((_, m)) = best {
            return Some(m);
        }
    }

    app.get_webview_window("main")
        .and_then(|w| w.current_monitor().ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten())
}

/// 卡片该落在哪：`(物理坐标, 逻辑坐标)`。
///
/// ## 为什么两个都要返回
///
/// `.position()` 收的是**逻辑**坐标，而 Tauri 换算时用的缩放比是「窗口创建时所在
/// 那块屏」的 —— 不是目标屏的。两块屏缩放不同时（本机实测：主屏 2560x1440@1.0、
/// 副屏 1600x2560@2.0），把副屏的逻辑坐标传给 `.position()`，会被按主屏的 1.0 换算，
/// 结果卡片**落回主屏**，偏移量正好是两块屏缩放比之差。
///
/// 所以这里以物理像素为唯一口径算目标（`work_area` 本来就是物理像素），
/// 建窗时先用「逻辑近似值」占位避免可见的跳变，建完立刻用 `set_position(PhysicalPosition)`
/// 校正 —— 物理坐标不经任何缩放解释，是唯一没有歧义的口径。
fn card_target(app: &AppHandle) -> Option<((i32, i32), (f64, f64), (u32, u32))> {
    let monitor = monitor_for_window(app)?;
    let scale = monitor.scale_factor();
    // work_area 是物理像素，且已经扣掉任务栏
    let area = monitor.work_area();

    // 全部按物理像素算：卡片在目标屏上的物理宽度 = 逻辑宽度 × 该屏缩放比
    let card_w_phys = (CARD_W * scale).round();
    let margin_phys = (TOP_MARGIN * scale).round();
    let px = area.position.x as f64 + (area.size.width as f64 - card_w_phys) / 2.0;
    let py = area.position.y as f64 + margin_phys;

    // 逻辑值要除以**新建窗口所在屏**的缩放比，而不是目标屏的 ——
    // 新窗口默认开在主屏，Tauri 也就按主屏的缩放比把逻辑值换算成物理值。
    // 用目标屏的缩放比会正好差一个比值，卡片落回主屏（实测：副屏 2.0 时落在 1450
    // 而不是 2900，差的就是这个 2 倍）。
    // 位置按「建窗屏」的缩放比折算（新窗口总是开在主屏，Tauri 也就用主屏的缩放比换算）。
    let create_scale = app
        .primary_monitor()
        .ok()
        .flatten()
        .map(|m| m.scale_factor())
        .unwrap_or(1.0);
    let logical = (px / create_scale, py / create_scale);
    let physical = (px.round() as i32, py.round() as i32);
    let size = (card_w_phys as u32, (CARD_H * scale).round() as u32);

    if let Some(state) = app.try_state::<AppState>() {
        let win_rect = app.get_webview_window("main").and_then(|w| {
            let pos = w.outer_position().ok()?;
            let size = w.outer_size().ok()?;
            Some(format!(
                "({},{}) {}x{}",
                pos.x, pos.y, size.width, size.height
            ))
        });
        crate::trace(
            &state.paths.data_dir,
            &format!(
                "overlay 定位：窗口矩形 {}，选中屏 pos=({},{}) size={}x{} scale={:.2} work_area=({},{}) {}x{} → 物理 ({},{})",
                win_rect.as_deref().unwrap_or("无"),
                monitor.position().x,
                monitor.position().y,
                monitor.size().width,
                monitor.size().height,
                scale,
                area.position.x,
                area.position.y,
                area.size.width,
                area.size.height,
                px,
                py
            ),
        );
    }

    Some((physical, logical, size))
}

/// 建提醒窗口（隐藏）。见文件头「建窗时机」。
fn create_overlay(app: &AppHandle) -> tauri::Result<tauri::WebviewWindow> {
    if let Some(win) = app.get_webview_window(LABEL) {
        return Ok(win);
    }
    // 拿不到显示器信息时退回一个保守值，至少别把卡片放到 (0,0) 压住标题栏
    let (physical, logical, size) =
        card_target(app).unwrap_or(((100, 100), (100.0, 100.0), (CARD_W as u32, CARD_H as u32)));

    // 尺寸**必须**在建窗时定好：实测 `set_size(PhysicalSize)` 返回 Ok 却完全不生效
    // （VERSION-INFO 第六节「副屏」记过同一条）。所以把目标屏上的物理尺寸
    // 折成「建窗屏」的逻辑值传进去 —— 缩放 2.0 的副屏上要 920x352 物理，
    // 就传 inner_size(920, 352)（建窗屏 scale=1.0）。
    let create_scale = app
        .primary_monitor()
        .ok()
        .flatten()
        .map(|m| m.scale_factor())
        .unwrap_or(1.0);

    let mut builder = WebviewWindowBuilder::new(
        app,
        LABEL,
        // 复用同一个 SPA，用查询参数分流到提醒卡页面 ——
        // 比再配一个 Vite 入口简单，也不会多打一份 JS 包。
        WebviewUrl::App("index.html?overlay=1".into()),
    )
    .title("StuMate Lite 提醒")
    .inner_size(size.0 as f64 / create_scale, size.1 as f64 / create_scale)
    .resizable(false)
    .maximizable(false)
    .minimizable(false)
    .decorations(false)
    .always_on_top(true)
    // 不占任务栏：它是提醒，不是"另一个应用窗口"
    .skip_taskbar(true)
    // 不主动抢焦点：正在打字时被弹窗抢走焦点非常烦
    .focused(false)
    .position(logical.0, logical.1);

    // ⚠️ 必须和主窗口用同一份浏览器参数，否则 WebView2 会去建第二个「环境」
    // 并以 HRESULT(0x8007139F) 失败。见 crate::browser_args 的注释。
    let args = crate::browser_args();
    if !args.is_empty() {
        builder = builder.additional_browser_args(&args);
    }

    let win = builder.build()?;

    // 建完按**物理**坐标与尺寸校正。逻辑值在建窗时就被主屏的缩放比解释掉了，
    // 落到另一块不同缩放的屏上会又偏又小（实测副屏 2.0 时卡片只有一半大）。
    // 物理口径不经缩放解释，是唯一没有歧义的口径。
    apply_placement(&win, physical, size);

    if let Some(state) = app.try_state::<AppState>() {
        crate::trace(
            &state.paths.data_dir,
            &format!(
                "overlay 已建窗并隐藏：目标物理 ({},{})，实际 {}",
                physical.0,
                physical.1,
                fmt_pos(&win)
            ),
        );
    }
    Ok(win)
}

fn fmt_pos(win: &tauri::WebviewWindow) -> String {
    match win.outer_position() {
        Ok(p) => format!("({},{})", p.x, p.y),
        Err(e) => format!("未知（{e}）"),
    }
}

/// 显示但不激活。`show()` 走的是 `SW_SHOW`，会抢焦点；
/// 提醒卡在用户打字时弹出来抢焦点很烦，所以直接用 `SW_SHOWNOACTIVATE`。
#[cfg(windows)]
fn show_no_activate(win: &tauri::WebviewWindow) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{ShowWindow, SW_SHOWNOACTIVATE};
    if let Ok(hwnd) = win.hwnd() {
        unsafe {
            ShowWindow(hwnd.0 as *mut core::ffi::c_void, SW_SHOWNOACTIVATE);
        }
    }
}

#[cfg(not(windows))]
fn show_no_activate(win: &tauri::WebviewWindow) {
    let _ = win.show();
}

/// 弹出（或更新）提醒卡片。
///
/// 弹出（或更新）提醒卡片。
///
/// 三条路径，理由见文件头：
///   · 窗口已存在 → 直接定位 + 推内容 + 不抢焦点显示；
///   · 首次 → 把「建窗 + 显示」整体派发到主线程（WebView2 要求 STA + 消息泵）；
///   · 命令必须是 `async`，否则派发出去的任务没人接（主线程在等自己）。
#[tauri::command]
pub async fn overlay_show(app: AppHandle, state: State<'_, AppState>, alert: Value) -> Result<(), String> {
    if let Ok(mut guard) = state.alert.lock() {
        *guard = Some(alert.clone());
    }
    let dir = state.paths.data_dir.clone();

    // 全屏游戏/演示/免打扰时不弹。见 fullscreen.rs：置顶卡片会把全屏应用挤掉，
    // 用户正在打游戏时被打断比不提醒更糟。托盘状态仍然照常更新（调用方负责），
    // 所以信息没丢，只是不打扰。
    if let Some(reason) = crate::fullscreen::suppressed_reason() {
        crate::trace(&dir, &format!("overlay 抑制：{reason}，不弹出"));
        return Ok(());
    }

    if let Some(win) = app.get_webview_window(LABEL) {
        // 每次弹出都重新定位：用户可能刚把主窗口拖到另一块屏
        reposition(&app, &win);
        win.emit(EVENT_UPDATE, alert).map_err(|e| e.to_string())?;
        show_no_activate(&win);
        crate::trace(&dir, &format!("overlay 已显示：{}", fmt_pos(&win)));
        return Ok(());
    }

    // 首次：整段丢回主线程。这里**不能**在当前线程建 —— 实测 WebView2 会报
    // HRESULT(0x8007139F)「组或资源的状态不是执行请求操作的正确状态」。
    let app2 = app.clone();
    app.run_on_main_thread(move || {
        let dir2 = app2.state::<AppState>().paths.data_dir.clone();
        match create_overlay(&app2) {
            Ok(win) => {
                // 内容从 AppState 取：命令刚写进去，闭包里没法借用外面那个 alert
                let payload = app2.state::<AppState>().alert.lock().ok().and_then(|g| g.clone());
                if let Some(payload) = payload {
                    let _ = win.emit(EVENT_UPDATE, payload);
                }
                show_no_activate(&win);
                crate::trace(&dir2, &format!("overlay 首次创建并显示：{}", fmt_pos(&win)));
            }
            Err(err) => crate::trace(&dir2, &format!("overlay 建窗失败：{err}")),
        }
    })
    .map_err(|e| e.to_string())?;

    Ok(())
}

/// 挪到「主窗口所在那块屏」的正上方居中。
fn reposition(app: &AppHandle, win: &tauri::WebviewWindow) {
    if let Some((physical, _, size)) = card_target(app) {
        apply_placement(win, physical, size);
    }
}

/// 按物理坐标摆放。
///
/// 只设位置、不设尺寸：`set_size` 建完之后调是不生效的（见 VERSION-INFO「副屏」一节），
/// 尺寸只能在建窗时定。所以提醒卡如果中途被拖到「缩放比不同的另一块屏」，
/// 位置会跟着走、尺寸要等窗口重建才正确 —— 这是已知取舍，换来的是不闪、不重建。
fn apply_placement(win: &tauri::WebviewWindow, pos: (i32, i32), _size: (u32, u32)) {
    let _ = win.set_position(tauri::PhysicalPosition::new(pos.0, pos.1));
}

/// 页面挂载后取当前应显示的提醒。窗口新建时事件会丢，靠这条补上。
#[tauri::command]
pub fn overlay_state(state: State<'_, AppState>) -> Option<Value> {
    state.alert.lock().ok().and_then(|g| g.clone())
}

/// 关掉卡片（用户点「知道了」或按 Esc）。
#[tauri::command]
pub fn overlay_hide(app: AppHandle, state: State<'_, AppState>) {
    if let Ok(mut guard) = state.alert.lock() {
        *guard = None;
    }
    // 隐藏而不是销毁：窗口要留着复用，销毁了下次还得重建（而重建这条路会卡死）
    if let Some(win) = app.get_webview_window(LABEL) {
        let _ = win.hide();
    }
}

/// 浮窗上点了「跳过本次」。
///
/// **这里不碰 `recurring.json`**：相位推进（`skipOnce`）只有前端一份实现，
/// Rust 再把规则解析一遍就会出现第二份各自演化的逻辑。所以只把 ruleId
/// 广播回主窗口，由 `useReminder` 收下后改 `lastFiredAt` 并落盘。
/// 事件是全局 emit，主窗口与子窗口都能收到，谁拿着规则谁处理。
#[tauri::command]
pub fn overlay_skip(
    app: AppHandle,
    state: State<'_, AppState>,
    rule_id: String,
) -> Result<(), String> {
    app.emit(EVENT_SKIP, serde_json::json!({ "ruleId": rule_id }))
        .map_err(|e| format!("跳过本次事件发不出去：{e}"))?;
    overlay_hide(app, state);
    Ok(())
}

/// 当前是否被「全屏 / 演示 / 免打扰」拦住。
///
/// 给前端用：卡片本身在 [`overlay_show`] 里已经挡过了，但系统通知是前端发的，
/// 得让前端也知道该不该发 —— 否则游戏里虽然没弹卡片，却从右下角冒出一条通知。
#[tauri::command]
pub fn notify_blocked() -> Option<String> {
    crate::fullscreen::suppressed_reason().map(|s| s.to_string())
}
