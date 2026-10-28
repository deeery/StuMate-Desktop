//! 「现在适合弹提醒吗」—— 全屏游戏/演示/免打扰时不要弹。
//!
//! ## 为什么要做这个
//!
//! 提醒卡是 `always_on_top` 的。全屏游戏（尤其是无边框全屏）下，一个置顶窗口
//! 会把游戏画面挤掉、甚至把游戏从独占全屏踢成窗口化 —— 用户正在打副本时被
//! 一张"还有 5 分钟上课"的卡片打断，比不提醒更糟。
//!
//! Windows 自己也是这么处理的：系统 toast 在检测到全屏应用时会挂起不弹。
//! 判断依据是 `SHQueryUserNotificationState`，Shell 会综合「有全屏窗口 / 演示模式 /
//! 专注助手」给出结论，比自己遍历窗口去猜 `WS_EX_TOPMOST + 覆盖全屏` 可靠得多
//! （后者分不清"全屏游戏"和"最大化的浏览器"）。
//!
//! ## 取舍：宁可不弹，也不打断
//!
//! 被抑制时**不弹卡片、不发系统通知**，但托盘菜单的状态文案照常更新
//! （「即将上课：xxx」），所以信息没丢，只是不打扰。用户抬眼看托盘就能知道。
//!
//! 不排队补弹：全屏结束后再弹一张"20 分钟前该上课了"的卡片没有意义，
//! 下一轮轮询会按当时的真实时间重新判定。

/// 当前不适合弹窗的原因；`None` 表示可以弹。
///
/// 返回 `&'static str` 而不是枚举，是为了直接写进 trace，不用再翻译一遍。
#[cfg(windows)]
pub fn suppressed_reason() -> Option<&'static str> {
    use windows_sys::Win32::UI::Shell::{
        SHQueryUserNotificationState, QUNS_ACCEPTS_NOTIFICATIONS, QUNS_APP, QUNS_BUSY,
        QUNS_PRESENTATION_MODE, QUNS_QUIET_TIME, QUNS_RUNNING_D3D_FULL_SCREEN,
    };

    let mut state = 0i32;
    // SAFETY: 传入的是本栈上的 i32 指针，Shell 只写不读，生命周期覆盖调用期。
    let hr = unsafe { SHQueryUserNotificationState(&mut state) };
    if hr < 0 {
        // 查不到就当作"可以弹"：这是提醒功能，宁可偶尔多弹一次，
        // 也不要因为一个查询失败而永久静默（那才是真的失效）
        return None;
    }

    match state {
        QUNS_RUNNING_D3D_FULL_SCREEN => Some("全屏游戏运行中"),
        QUNS_PRESENTATION_MODE => Some("演示模式"),
        QUNS_QUIET_TIME => Some("系统免打扰时段"),
        // QUNS_BUSY = 全屏但不是 D3D（如全屏视频、部分 UWP）。这种情况弹卡片
        // 一样会盖住内容，按"不打扰"处理。
        QUNS_BUSY => Some("全屏应用运行中"),
        // QUNS_ACCEPTS_NOTIFICATIONS / QUNS_APP：正常，可以弹
        QUNS_ACCEPTS_NOTIFICATIONS | QUNS_APP => None,
        _ => None,
    }
}

#[cfg(not(windows))]
pub fn suppressed_reason() -> Option<&'static str> {
    None
}
