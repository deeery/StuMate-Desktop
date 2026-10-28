//! 旁路 JSON 存档：偏好设置、思维导图、知识库附件。
//!
//! 原版把这三样都放在 `%APPDATA%\StuMate\` 下的 JSON 文件里（不落库，理由是
//! 落库要涨 `user_version`，安卓端 Room 就打不开这个库了 —— 见原版 `MindMap.kt` 顶部）。
//! Lite 沿用同一份文件格式，**导图与附件完全共用**，两版改完互相看得见。
//!
//! 唯一的例外是偏好设置：Lite 写 `settings.lite.json`，不写原版的 `settings.json`。
//! 原因是两版有各自专属的项（原版有 `renderApi` 渲染后端，Lite 没有；Lite 有侧栏宽度等），
//! 共写一个文件会互相覆盖。**首次启动时从原版 `settings.json` 播种一次**，
//! 于是主题、周次校准这些共享语义不会丢。
//!
//! 所有写入都走「临时文件 + 原子替换」，与 `Prefs.writeAtomically` 同一套路：
//! 断电最多留下一个 `.tmp`，不会把用户攒的数据写成半个 JSON。

use serde_json::{Map, Value};
use std::fs;
use std::path::{Path, PathBuf};

pub struct Paths {
    pub data_dir: PathBuf,
    pub db: PathBuf,
    pub settings: PathBuf,
    pub settings_original: PathBuf,
    pub mindmap: PathBuf,
    pub links: PathBuf,
}

impl Paths {
    pub fn resolve() -> Self {
        let base = std::env::var("APPDATA")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .map(PathBuf::from)
            .or_else(|| std::env::var("USERPROFILE").ok().map(|p| PathBuf::from(p).join(".stumate")))
            .unwrap_or_else(|| PathBuf::from("."));
        let data_dir = base.join("StuMate");
        Self {
            db: data_dir.join("class_reminder.db"),
            settings: data_dir.join("settings.lite.json"),
            settings_original: data_dir.join("settings.json"),
            mindmap: data_dir.join("mindmap.json"),
            links: data_dir.join("knowledge_links.json"),
            data_dir,
        }
    }
}

pub fn read_json(path: &Path) -> Option<Value> {
    let text = fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

/// 原子写：先写 `.tmp` 再 rename。rename 在同一分区上是原子的。
pub fn write_json_atomic(path: &Path, value: &Value) -> Result<(), String> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir).map_err(|e| format!("创建目录失败: {e}"))?;
    }
    let text = serde_json::to_string_pretty(value).map_err(|e| e.to_string())?;
    let tmp = path.with_extension(format!(
        "{}.tmp",
        path.extension().and_then(|s| s.to_str()).unwrap_or("json")
    ));
    fs::write(&tmp, text).map_err(|e| format!("写临时文件失败: {e}"))?;
    fs::rename(&tmp, path).map_err(|e| format!("替换文件失败: {e}"))
}

/// 偏好设置。**默认值与原版 `Prefs.kt` 逐条一致**，这样同一份用户习惯在两版之间不用重设。
pub fn default_prefs() -> Map<String, Value> {
    let mut m = Map::new();
    m.insert("advanceMinutes".into(), Value::from(30));
    m.insert("autoStart".into(), Value::from(false));
    m.insert("showPopup".into(), Value::from(true));
    m.insert("firstRun".into(), Value::from(true));
    m.insert("themeMode".into(), Value::from(0));
    m.insert("week1Monday".into(), Value::from(0));
    m.insert("weekGrid".into(), Value::from(true));
    m.insert("sidebarCollapsed".into(), Value::from(false));
    m.insert("closeAction".into(), Value::from(0));
    m.insert("notifyEnabled".into(), Value::from(true));
    m.insert("autoCheckUpdate".into(), Value::from(true));
    m.insert("notifyUpdate".into(), Value::from(true));
    // 知识库：库根（真正的库在 `<库根>/Stumate/`）+ 是否已把旧数据库条目铺成文件
    m.insert("vaultRoot".into(), Value::from(""));
    m.insert("vaultMigrated".into(), Value::from(false));
    m
}

/// 读设置：Lite 自己的文件优先；不存在时从原版播种一次（只读原版，绝不回写）。
pub fn load_prefs(paths: &Paths) -> Value {
    let mut base = default_prefs();

    // 已有 Lite 设置：在默认值之上覆盖，缺的键用默认值补齐。
    //
    // 不能整份信任磁盘上的文件。早期版本只写过 themeMode / weekGrid / week1Monday
    // 三个键，文件一旦存在就再也不会补全 —— 结果设置页上「提前提醒」一直是空输入框、
    // 「允许通知」一直是关的，看上去像功能没做，其实是键根本没读进来。
    if let Some(Value::Object(saved)) = read_json(&paths.settings) {
        for (k, v) in saved {
            base.insert(k, v);
        }
        return Value::Object(base);
    }

    if let Some(Value::Object(orig)) = read_json(&paths.settings_original) {
        // 首次运行：只搬两版语义相同的键。原版独有的 renderApi / experimentalGrid
        // 不搬 —— Lite 没有渲染后端这个概念，搬过来只会变成一堆读不懂的死键。
        for key in [
            "advanceMinutes",
            "autoStart",
            "showPopup",
            "themeMode",
            "week1Monday",
            "weekGrid",
            "sidebarCollapsed",
            "closeAction",
            "notifyEnabled",
            "autoCheckUpdate",
            "notifyUpdate",
        ] {
            if let Some(v) = orig.get(key) {
                base.insert(key.to_string(), v.clone());
            }
        }
    }
    Value::Object(base)
}

pub fn save_prefs(paths: &Paths, value: &Value) -> Result<(), String> {
    write_json_atomic(&paths.settings, value)
}

/// 导图存档。坏档当空档、**保留原文件**（与原版 `MindMapStore.load` 同一策略：
/// 宁可这次从知识库重新派生，也不要因为一次读失败就把手工结构覆盖掉）。
pub fn load_mindmap(paths: &Paths) -> Value {
    match read_json(&paths.mindmap) {
        Some(Value::Object(o)) if o.contains_key("nodes") => Value::Object(o),
        _ => serde_json::json!({ "version": 1, "nodes": [] }),
    }
}

pub fn save_mindmap(paths: &Paths, nodes: Value) -> Result<(), String> {
    let payload = serde_json::json!({ "version": 1, "nodes": nodes });
    write_json_atomic(&paths.mindmap, &payload)
}

pub fn load_links(paths: &Paths) -> Value {
    match read_json(&paths.links) {
        Some(Value::Object(o)) if o.contains_key("links") => Value::Object(o),
        _ => serde_json::json!({ "version": 1, "links": [] }),
    }
}

pub fn save_links(paths: &Paths, links: Value) -> Result<(), String> {
    let payload = serde_json::json!({ "version": 1, "links": links });
    write_json_atomic(&paths.links, &payload)
}
