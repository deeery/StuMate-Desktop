// Windows 下 release 构建不要弹出多余的控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    stumate_lite_lib::run()
}
