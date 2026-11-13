//! 启动窗（splash）的窗口生命周期。
//!
//! 它不是「延时关掉的一张装饰图」，而是冷启动空窗期的真实遮挡：Rust 起来之后要开库、
//! 建托盘、起主窗口，主窗口还要等 React 挂载 + 七张表的 IPC，这一两秒里用户原本只能
//! 看到一块白板。启动窗是独立 webview，只有一张图 + 几十行 CSS，拿到字节就能画。
//!
//! 切换时机用**两票制**：
//!   · 主窗口前端数据装载完成（或失败）投 `main`
//!   · 启动窗动画播完（或用户点了跳过）投 `anim`
//! 两票齐了才淡出。只等动画 → 数据没好就露出半截界面；只等数据 → 动画被腰斩。
//! 谁都可能先到，状态放在 `Gate` 里，后到的一票负责触发。
//!
//! 三条兜底：
//!   · 主窗口挂载失败（`index.html` 的 boot guard）投 `abort`，立刻撤窗露出错误页
//!   · watchdog 12s 后强制切换，任何一票丢了也不会把用户卡在启动窗
//!   · `STUMATE_LITE_NO_SPLASH=1` 完全跳过，主窗口照常直接可见（自动化验收用）

use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};

/// 动画 3.9s + 主窗口正常几百毫秒，12s 足够宽；
/// 又不会让 IPC 挂掉时用户对着启动窗等太久。
const WATCHDOG_MS: u64 = 12_000;

#[derive(Default)]
pub struct Gate {
    pub enabled: bool,
    pub main_ready: bool,
    pub anim_done: bool,
    pub finished: bool,
}

/// watchdog 线程也要看这份状态，所以比 AppState 里其它几个 Mutex 多包一层 Arc。
pub type SharedGate = Arc<Mutex<Gate>>;

pub fn new_gate(enabled: bool) -> SharedGate {
    Arc::new(Mutex::new(Gate {
        enabled,
        ..Gate::default()
    }))
}

fn unlock<'a>(gate: &'a SharedGate) -> std::sync::MutexGuard<'a, Gate> {
    gate.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// 建启动窗。**必须在主窗口之前**：第一个 webview 会初始化 WebView2 环境，
/// 后建的窗口复用它；反过来的话，用户先看到的是主窗口那一下空白。
pub fn build(app: &AppHandle, args: &str, cfg: &serde_json::Value) -> tauri::Result<()> {
    // 主题 / 版本 / 停留时长走 initialization_script，不塞 URL query：
    // asset 协议会把整个字符串当文件路径，带 query 会停在 about:blank
    // （Markdowner 子窗口踩过，见 lib.rs open_markdown_window）。
    let payload = format!("window.__STUMATE_SPLASH__ = {};", cfg);

    let mut builder = WebviewWindowBuilder::new(app, "splash", WebviewUrl::App("splash.html".into()))
        .title("StuMate Lite · 启动")
        .decorations(false)
        .resizable(false)
        .inner_size(640.0, 320.0)
        .skip_taskbar(true)
        .center()
        .visible(true)
        .initialization_script(&payload);

    // 与主窗口共用**同一份** WebView2 启动参数。参数不同就会去建第二个环境，
    // 而同一个数据目录只允许一个环境 —— 症状是第二个窗口直接建不出来。
    if !args.is_empty() {
        builder = builder.additional_browser_args(args);
    }

    builder.build()?;
    Ok(())
}

/// 亮出主窗口、收掉启动窗。
///
/// 顺序是 show → close → focus：先让主窗口存在，再撤启动窗，中间没有「两个都不可见」
/// 的一帧。反过来（先 close）会闪一下桌面。
fn reveal_main(app: &AppHandle) {
    if let Some(main) = app.get_webview_window("main") {
        let _ = main.show();
    }
    if let Some(splash) = app.get_webview_window("splash") {
        let _ = splash.close();
    }
    if let Some(main) = app.get_webview_window("main") {
        let _ = main.set_focus();
    }
}

/// 一票进来。返回后可能发生的三件事：什么都不做 / 让启动窗开始淡出 / 直接切换。
pub fn signal(app: &AppHandle, gate: &SharedGate, kind: &str) {
    let (start_fade, switch_now) = {
        let mut g = unlock(gate);
        if !g.enabled {
            return;
        }
        // finished 之后只有 close / abort 还需要放行（前者是淡出后的收尾）。
        let is_switch = matches!(kind, "close" | "abort");
        if g.finished && !is_switch {
            return;
        }

        match kind {
            "main" => g.main_ready = true,
            "anim" => g.anim_done = true,
            "close" | "abort" => g.finished = true,
            _ => return,
        }

        let fade = !g.finished && g.main_ready && g.anim_done;
        if fade {
            g.finished = true;
        }
        (fade, is_switch)
    };

    if switch_now {
        reveal_main(app);
    } else if start_fade {
        // 淡出交给页面自己跑（280ms），跑完它会再投一票 `close`。
        // 用 eval 而不是 Tauri 事件：独立页不必引 @tauri-apps/api，
        // 也不必在两边各写一份事件名常量。
        let triggered = app
            .get_webview_window("splash")
            .and_then(|w| w.eval("window.__stumateSplashFinish && window.__stumateSplashFinish()").ok());
        // 启动窗已经不在了（watchdog / 用户手动关掉）就直接切，别等一张不来的票。
        if triggered.is_none() {
            reveal_main(app);
        }
    }
}

/// 兜底：主线程之外的独立线程，到点发现还没切就强制切。
pub fn spawn_watchdog(app: AppHandle, gate: SharedGate) {
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(WATCHDOG_MS));
        let mut g = unlock(&gate);
        if g.enabled && !g.finished {
            g.finished = true;
            drop(g);
            reveal_main(&app);
        }
    });
}
