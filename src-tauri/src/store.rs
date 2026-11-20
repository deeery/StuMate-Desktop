//! 旁路 JSON 存档：偏好设置、思维导图、知识库附件、循环提醒。
//!
//! 原版把前三样都放在 `%APPDATA%\StuMate\` 下的 JSON 文件里（不落库，理由是
//! 落库要涨 `user_version`，安卓端 Room 就打不开这个库了 —— 见原版 `MindMap.kt` 顶部）。
//! Lite 沿用同一份文件格式，**导图与附件完全共用**，两版改完互相看得见。
//!
//! `recurring.json` 是 Lite 专属的第四份：安卓端 `AppDatabase.kt` 用的是
//! `fallbackToDestructiveMigration()`，Lite 单方面 bump schema 会让安卓打开库时清库，
//! 所以循环规则绝不进 DB。它也不与原版共用 —— 原版没有这套规则模型。
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
    pub recurring: PathBuf,
    pub note_links: PathBuf,
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
            recurring: data_dir.join("recurring.json"),
            note_links: data_dir.join("note_links.json"),
            data_dir,
        }
    }
}

pub fn read_json(path: &Path) -> Option<Value> {
    let text = fs::read_to_string(path).ok()?;
    // Windows PowerShell 的 `Set-Content -Encoding utf8`、以及不少编辑器会
    // 给 UTF-8 加 BOM，而 serde_json 不认 BOM：整份解析失败后调用方只能
    // 回落默认值 —— 用户看到的是「设置莫名其妙全丢了」。这里统一剥掉。
    let text = text.trim_start_matches('\u{feff}');
    serde_json::from_str(text).ok()
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
    // v1.7.28：提醒呈现方式 0 全局浮窗 / 1 窗口内横幅 / 2 不弹。
    // `showPopup` 保留只为向下兼容（旧版本读它），两者由 `pref_set` 保持同步。
    m.insert("reminderMode".into(), Value::from(0));
    m.insert("firstRun".into(), Value::from(true));
    m.insert("themeMode".into(), Value::from(0));
    // v1.7.28：主题色。存 id（见前端 model/accent.ts），认不出来的值前端回落 blue。
    m.insert("accent".into(), Value::from("blue"));
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
    // 知识库是实验性功能：默认关闭，入口藏在「设置 → 实验性功能」里。
    // 原版没有这个概念，所以下面播种旧设置时也不搬这个键。
    m.insert("experimentalKnowledge".into(), Value::from(false));
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
        // 知识库在 v1.7.28 变成实验性功能（默认关闭）。已经在用它的人不该一升级
        // 就少掉侧栏入口 —— 文件里没有这个键、但库根非空，就当作已启用；
        // 从没选过库根的新装用户照默认（关闭）走。
        let had_flag = saved.contains_key("experimentalKnowledge");
        let had_vault = matches!(
            saved.get("vaultRoot"),
            Some(Value::String(s)) if !s.is_empty()
        );
        // v1.7.28：`reminderMode` 也是新键。旧文件里没有它时，按老的
        // `showPopup`（开 = 全局浮窗，关 = 不弹）迁移一次 —— 升级前后观感一致。
        // 只在读取时推导，不在这里落盘：用户没动设置就不该改写他的文件。
        let had_mode = saved.contains_key("reminderMode");
        for (k, v) in saved {
            base.insert(k, v);
        }
        if !had_mode {
            let show = base
                .get("showPopup")
                .and_then(|v| v.as_bool())
                .unwrap_or(true);
            base.insert("reminderMode".into(), Value::from(if show { 0 } else { 2 }));
        }
        if !had_flag && had_vault {
            base.insert("experimentalKnowledge".into(), Value::from(true));
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
        // 原版没有 `reminderMode`：按刚搬过来的 `showPopup` 推导一次
        let show = base
            .get("showPopup")
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        base.insert("reminderMode".into(), Value::from(if show { 0 } else { 2 }));
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

/// 循环提醒规则。
///
/// 内部项整体当作 `Value` 透传：**规则模型只在前端 `model/recurring.ts` 有一份实现**，
/// Rust 不解析 `kind` / `anchor` / `lastFiredAt`，这样以后加规则类型不用两端同步改结构。
/// 坏档当空档但不删原文件（与 `load_mindmap` 同一策略）。
pub fn load_recurring(paths: &Paths) -> Value {
    match read_json(&paths.recurring) {
        Some(Value::Object(o)) if o.get("items").map(|v| v.is_array()).unwrap_or(false) => {
            Value::Object(o)
        }
        _ => serde_json::json!({ "version": 1, "items": [] }),
    }
}

pub fn save_recurring(paths: &Paths, items: Value) -> Result<(), String> {
    let payload = serde_json::json!({ "version": 1, "items": items });
    write_json_atomic(&paths.recurring, &payload)
}

/// 便签 ↔ 课程 / 循环提醒的关联。
///
/// 同样整项 `Value` 透传：模型只在 `src/model/noteLinks.ts` 一处。
/// **刻意不进数据库**：`notes` 表与原版 / 安卓共用，加列要 bump schema，
/// 而安卓端是 `fallbackToDestructiveMigration()` —— 清库的代价远大于这层关联。
/// 坏档当空档但不删原文件（与 recurring / mindmap 同一策略）。
pub fn load_note_links(paths: &Paths) -> Value {
    match read_json(&paths.note_links) {
        Some(Value::Object(o)) if o.get("links").map(|v| v.is_array()).unwrap_or(false) => {
            Value::Object(o)
        }
        _ => serde_json::json!({ "version": 1, "links": [] }),
    }
}

pub fn save_note_links(paths: &Paths, links: Value) -> Result<(), String> {
    let payload = serde_json::json!({ "version": 1, "links": links });
    write_json_atomic(&paths.note_links, &payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_paths(tag: &str) -> Paths {
        let dir = std::env::temp_dir().join(format!("stumate-store-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        Paths {
            data_dir: dir.clone(),
            db: dir.join("class_reminder.db"),
            settings: dir.join("settings.lite.json"),
            settings_original: dir.join("settings.json"),
            mindmap: dir.join("mindmap.json"),
            links: dir.join("knowledge_links.json"),
            recurring: dir.join("recurring.json"),
            note_links: dir.join("note_links.json"),
        }
    }

    #[test]
    fn note_links_roundtrip_leaves_no_tmp_file() {
        let p = temp_paths("notelinks");
        // 没有文件时给空列表，而不是让前端拿到 null
        assert_eq!(load_note_links(&p)["links"].as_array().unwrap().len(), 0);
        save_note_links(
            &p,
            serde_json::json!([{ "noteId": 7, "classId": 3, "ruleId": "r1" }]),
        )
        .unwrap();
        let loaded = load_note_links(&p);
        assert_eq!(loaded["links"][0]["noteId"], 7);
        assert_eq!(loaded["links"][0]["ruleId"], "r1");
        assert!(!p.data_dir.join("note_links.json.tmp").exists());
    }

    #[test]
    fn recurring_roundtrip_keeps_opaque_rule_shape() {
        let p = temp_paths("roundtrip");
        assert_eq!(load_recurring(&p)["items"].as_array().unwrap().len(), 0);

        // 规则里的嵌套字段 Rust 完全不认识，必须原样往返
        let items = serde_json::json!([{
            "id": "r1", "title": "起身活动", "enabled": true,
            "rule": { "kind": "interval", "minutes": 45, "anchor": 1762588800000i64 },
            "lastFiredAt": 1762588800000i64, "endAt": null
        }]);
        save_recurring(&p, items.clone()).unwrap();
        let loaded = load_recurring(&p);
        assert_eq!(loaded["version"], 1);
        assert_eq!(loaded["items"], items);
        assert_eq!(loaded["items"][0]["rule"]["kind"], "interval");
        let _ = std::fs::remove_dir_all(&p.data_dir);
    }

    #[test]
    fn recurring_corrupt_file_is_treated_as_empty_but_preserved() {
        let p = temp_paths("corrupt");
        std::fs::write(&p.recurring, "{ 这不是 JSON").unwrap();
        let loaded = load_recurring(&p);
        assert_eq!(loaded["items"].as_array().unwrap().len(), 0);
        assert!(p.recurring.is_file(), "坏档不能被静默覆盖掉");
        let _ = std::fs::remove_dir_all(&p.data_dir);
    }

    #[test]
    fn recurring_write_leaves_no_tmp_file() {
        let p = temp_paths("atomic");
        save_recurring(&p, serde_json::json!([])).unwrap();
        assert!(p.recurring.is_file());
        assert!(!p.data_dir.join("recurring.json.tmp").exists());
        let _ = std::fs::remove_dir_all(&p.data_dir);
    }
}
