//! 开机自启：写用户级注册表 Run 键。
//!
//! 位置与原版 `platform/AutoStart.kt` 一致：
//! `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`。用户级 Run 键不需要管理员权限，
//! 登录时由系统拉起。
//!
//! **与原版实现方式的差异（有意为之）**：原版用 `reg.exe`，这里直接调注册表 API。
//! 原版选 `reg.exe` 的理由是 JVM 里没有更省事的办法（`java.util.prefs` 会写到
//! `HKCU\Software\JavaSoft\Prefs\...` 这种带转义前缀的位置，系统不认）。Rust 没有这个限制，
//! 而 `reg.exe` 有三个实际代价：
//!   1. 它是控制台程序，从 GUI 进程（`windows_subsystem="windows"`）启动会**闪一下黑框**；
//!   2. 每次打开设置页都要 spawn 一个进程，只为读一个字符串；
//!   3. 可能被企业策略 / 安全软件拦掉（本机沙箱实测就禁了 `reg.exe`）。
//! 直接写注册表把这三件事一起消掉。
//!
//! **值名必须和原版不同**（原版 `StuMate`，这里 `StuMate Lite`）。两版是同时安装、
//! 同时运行的，共用一个值名会让「在 Lite 里关掉自启」把原版的自启一起删掉。

/// 启动项值名。与原版的 `StuMate` 区分开，两版可各自独立开关。
const VALUE_NAME: &str = "StuMate Lite";

const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";

/// 当前 exe 的启动命令。
///
/// 用 `current_exe()` 而不是猜安装路径：NSIS 装到哪、用户是不是免安装运行，
/// 都自动对得上。路径可能含空格（`C:\Program Files\...`），必须加引号，
/// 否则 Windows 会把前半截当程序、后半截当参数。
fn launch_command() -> Option<String> {
    let exe = std::env::current_exe().ok()?;
    Some(format!("\"{}\"", exe.display()))
}

/// 当前是否已写入自启项。
pub fn is_enabled() -> bool {
    winreg::RegKey::predef(winreg::enums::HKEY_CURRENT_USER)
        .open_subkey(RUN_KEY)
        .and_then(|key| key.get_value::<String, _>(VALUE_NAME))
        .is_ok()
}

/// 写入 / 删除自启项。返回是否成功 —— 失败通常是权限或环境异常，
/// 由调用方决定怎么提示用户（这里不弹窗，保持纯函数语义）。
pub fn set_enabled(enabled: bool) -> bool {
    let hkcu = winreg::RegKey::predef(winreg::enums::HKEY_CURRENT_USER);

    if enabled {
        let Some(target) = launch_command() else {
            return false;
        };
        // create_subkey 会按需创建 Run 键（正常情况下它一定已存在）
        hkcu.create_subkey(RUN_KEY)
            .and_then(|(key, _)| key.set_value(VALUE_NAME, &target))
            .is_ok()
    } else {
        let result = hkcu
            .open_subkey_with_flags(RUN_KEY, winreg::enums::KEY_WRITE)
            .and_then(|key| key.delete_value(VALUE_NAME));
        match result {
            Ok(()) => true,
            // 本来就没有这一项 = 已经是"关闭"状态，算成功。
            // 不这么写的话，用户连点两次关闭会看到一条莫名其妙的失败提示。
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => true,
            Err(_) => false,
        }
    }
}
