//! 备份 / 恢复。
//!
//! 文件格式与原版 `data/backup/Backup.kt` **完全一致**，所以两版导出的备份可以互相导入：
//!
//! ```json
//! { "format": "stumate-backup", "schema": 3, "app": "...", "exportedAt": 0,
//!   "modules": { "courses": { "count": n, "items": [...] }, ... } }
//! ```
//!
//! 两个细节是刻意保留的兼容口，**不要"顺手清理"**：
//!
//! 1. 便签同时写 `title` 和 `text`（`text` 是 `title` 的镜像）。旧版解码器只认 `text`，
//!    而且有「`text` 为空就跳过这条」的守卫，镜像缺了它们会把便签整批丢掉。
//! 2. 设置模块只搬两版语义相同的键。`experimentalGrid` 写出去（旧版要读），
//!    但 Lite 自己不消费它。

use crate::db::Db;
use crate::models::{ClassEntity, KnowledgeEntity, NoteEntity};
use crate::store::Paths;
use serde_json::{json, Map, Value};

pub const FORMAT: &str = "stumate-backup";
pub const SCHEMA: i32 = 3;
pub const APP: &str = "StuMate-Lite";

pub const MODULE_KEYS: [&str; 4] = ["courses", "notes", "knowledge", "settings"];

pub fn build(db: &Db, prefs: &Value, modules: &[String]) -> Result<Value, String> {
    let mut out = Map::new();
    for key in MODULE_KEYS {
        if !modules.iter().any(|m| m == key) {
            continue;
        }
        let value = match key {
            "courses" => {
                let items = db.classes()?;
                json!({ "count": items.len(), "items": items })
            }
            "notes" => {
                let items = db.notes()?;
                let mapped: Vec<Value> = items
                    .iter()
                    .map(|n| {
                        let mut o = serde_json::to_value(n).unwrap_or(json!({}));
                        if let Some(obj) = o.as_object_mut() {
                            obj.insert("text".into(), Value::String(n.title.clone()));
                        }
                        o
                    })
                    .collect();
                json!({ "count": mapped.len(), "items": mapped })
            }
            "knowledge" => {
                let items = db.knowledge()?;
                json!({ "count": items.len(), "items": items })
            }
            "settings" => json!({
                "advanceMinutes": prefs.get("advanceMinutes").cloned().unwrap_or(json!(30)),
                "autoStart": prefs.get("autoStart").cloned().unwrap_or(json!(false)),
                "showPopup": prefs.get("showPopup").cloned().unwrap_or(json!(true)),
                "themeMode": prefs.get("themeMode").cloned().unwrap_or(json!(0)),
                "week1Monday": prefs.get("week1Monday").cloned().unwrap_or(json!(0)),
                "weekGrid": prefs.get("weekGrid").cloned().unwrap_or(json!(true)),
                "experimentalGrid": true
            }),
            _ => continue,
        };
        out.insert(key.to_string(), value);
    }

    Ok(json!({
        "format": FORMAT,
        "schema": SCHEMA,
        "app": APP,
        "exportedAt": chrono::Utc::now().timestamp_millis(),
        "modules": Value::Object(out),
    }))
}

/// 导入结果，给界面显示「导入了什么、跳过了什么」。
pub struct ImportReport {
    pub courses: usize,
    pub notes: usize,
    pub knowledge: usize,
    pub settings: bool,
}

pub fn apply(
    db: &Db,
    paths: &Paths,
    root: &Value,
    modules: &[String],
) -> Result<ImportReport, String> {
    let format = root.get("format").and_then(|v| v.as_str()).unwrap_or("");
    if format != FORMAT {
        return Err("这不是 StuMate 的备份文件（format 字段不匹配）".into());
    }
    let all = root
        .get("modules")
        .and_then(|v| v.as_object())
        .ok_or("备份文件里没有 modules 字段")?;

    let wanted = |key: &str| modules.iter().any(|m| m == key);

    let mut report = ImportReport {
        courses: 0,
        notes: 0,
        knowledge: 0,
        settings: false,
    };

    if wanted("courses") {
        if let Some(items) = all.get("courses").and_then(|v| v.get("items")).and_then(|v| v.as_array()) {
            let parsed: Vec<ClassEntity> = items
                .iter()
                .filter_map(|v| serde_json::from_value::<ClassEntity>(v.clone()).ok())
                .filter(|c| !c.title.trim().is_empty())
                .collect();
            report.courses = parsed.len();
            db.clear_classes()?;
            for mut c in parsed {
                c.id = 0; // 重新分配主键：备份里的 id 可能和本机现有行撞车
                db.save_class(c)?;
            }
        }
    }

    if wanted("notes") {
        if let Some(items) = all.get("notes").and_then(|v| v.get("items")).and_then(|v| v.as_array()) {
            let parsed: Vec<NoteEntity> = items
                .iter()
                .filter_map(|v| {
                    let mut n: NoteEntity = serde_json::from_value(v.clone()).ok()?;
                    // v2 及更早的备份没有 title，回退到 text
                    if n.title.trim().is_empty() {
                        n.title = v.get("text").and_then(|t| t.as_str()).unwrap_or("").to_string();
                    }
                    if n.title.trim().is_empty() {
                        None
                    } else {
                        Some(n)
                    }
                })
                .collect();
            report.notes = parsed.len();
            db.clear_notes()?;
            for (index, mut n) in parsed.into_iter().enumerate() {
                n.id = 0;
                // 备份里的 position 是相对的，但整表替换后直接按文件顺序重排更稳
                n.position = index as i32;
                db.save_note(n)?;
            }
        }
    }

    if wanted("knowledge") {
        if let Some(items) = all
            .get("knowledge")
            .and_then(|v| v.get("items"))
            .and_then(|v| v.as_array())
        {
            let parsed: Vec<KnowledgeEntity> = items
                .iter()
                .filter_map(|v| serde_json::from_value::<KnowledgeEntity>(v.clone()).ok())
                .filter(|k| !k.title.trim().is_empty())
                .collect();
            report.knowledge = parsed.len();
            db.clear_knowledge()?;
            for mut k in parsed {
                k.id = 0;
                db.save_knowledge(k)?;
            }
        }
    }

    if wanted("settings") {
        if let Some(obj) = all.get("settings").and_then(|v| v.as_object()) {
            let mut current = crate::store::load_prefs(paths);
            if let Some(map) = current.as_object_mut() {
                for (k, v) in obj {
                    // experimentalGrid 是原版专属开关，不往 Lite 的偏好里塞
                    if k == "experimentalGrid" {
                        continue;
                    }
                    map.insert(k.clone(), v.clone());
                }
            }
            crate::store::save_prefs(paths, &current)?;
            report.settings = true;
        }
    }

    Ok(report)
}

/// 给界面做「导入前预览」：文件里每个模块有多少条。
pub fn describe(root: &Value) -> Value {
    let mut out = Map::new();
    if let Some(modules) = root.get("modules").and_then(|v| v.as_object()) {
        for (key, value) in modules {
            if key == "settings" {
                out.insert(key.clone(), json!({ "present": true }));
            } else {
                let count = value.get("count").and_then(|v| v.as_i64()).unwrap_or(0);
                out.insert(key.clone(), json!({ "count": count }));
            }
        }
    }
    json!({
        "format": root.get("format").cloned().unwrap_or(json!("")),
        "schema": root.get("schema").cloned().unwrap_or(json!(0)),
        "app": root.get("app").cloned().unwrap_or(json!("")),
        "exportedAt": root.get("exportedAt").cloned().unwrap_or(json!(0)),
        "modules": Value::Object(out),
    })
}
