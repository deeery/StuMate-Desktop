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
//!
//! v1.7.14 起 `knowledge` 模块**以文件库为准**：桌面端 v1.7.7 把知识库改成
//! `<库根>/Stumate/**/*.md` 之后，DB 里的 `knowledge` 表就冻结了，而手机端
//! 的备份解码器只认这张表的行 —— 于是「桌面新写的知识，导出备份里一条都没有」。
//! 现在导出时把 .md 折成 v10 实体写进包里，**不加列、不改 schema、不回写 DB**，
//! 两端的数据契约就这么重新对齐了。

use crate::db::Db;
use crate::models::{ClassEntity, KnowledgeEntity, NoteEntity};
use crate::store::Paths;
use crate::vault;
use serde_json::{json, Map, Value};

pub const FORMAT: &str = "stumate-backup";
pub const SCHEMA: i32 = 3;
pub const APP: &str = "StuMate-Lite";

/// v1.7.15 起多一个 `recurring`。**SCHEMA 保持 3**：安卓 / Compose 的
/// `Backup.kt` 用 `BackupModule.byKey(key)?.let { ... }`，未知键静默忽略，
/// 且只校验 `format`、不校验 `schema` —— 所以旧客户端导入新备份不会报错、不丢数据。
pub const MODULE_KEYS: [&str; 5] = ["courses", "notes", "knowledge", "settings", "recurring"];

/// 剥掉 frontmatter，取出 `stumate_uid` 和正文。
///
/// 只认 `parseNote`（前端 `model/vaultNote.ts`）写出来的那几行，其余键原样
/// 丢弃 —— 备份里要的是「手机端能显示的知识正文」，不是 YAML 元数据。
/// 没有 frontmatter 的 .md（用户自己丢进库里的）整篇当正文，uid 由调用方兜底。
fn split_note(raw: &str) -> (Option<String>, String) {
    let text = raw.strip_prefix('\u{feff}').unwrap_or(raw);
    let lines: Vec<&str> = text.split('\n').collect();
    if lines.first().map(|l| l.trim_end_matches('\r').trim()) != Some("---") {
        return (None, raw.to_string());
    }
    let close = lines
        .iter()
        .enumerate()
        .skip(1)
        .find(|(_, l)| {
            let t = l.trim_end_matches('\r').trim();
            t == "---" || t == "..."
        })
        .map(|(i, _)| i);
    let Some(end) = close else {
        return (None, raw.to_string());
    };

    let mut uid = None;
    for line in &lines[1..end] {
        let line = line.trim_end_matches('\r');
        let Some((key, value)) = line.split_once(':') else {
            continue;
        };
        let key = key.trim();
        if key != "stumate_uid" && key != "uid" {
            continue;
        }
        let value = value.trim().trim_matches('"').trim_matches('\'').trim();
        if !value.is_empty() {
            uid = Some(value.to_string());
        }
    }

    // 分隔线后恰好一个空行是 composeNote 的格式，吃掉它；正文其余空白不动
    let mut rest = lines[end + 1..].join("\n");
    if rest.starts_with("\r\n") {
        rest = rest[2..].to_string();
    } else if rest.starts_with('\n') {
        rest = rest[1..].to_string();
    }
    (uid, rest)
}

/// 把文件库当前内容折成 v10 `knowledge` 行。
///
/// 返回 `None` = 「没启用文件库 / 还没迁移 / 库目录不在了」，调用方应退回
/// 冻结的 DB 表；返回 `Some(vec![])` 是合法结果 —— 用户确实把笔记删空了。
/// 单篇读失败只跳过那一篇，不因为一个坏文件让整次导出悄悄换数据源。
pub fn vault_knowledge(prefs: &Value) -> Option<Vec<KnowledgeEntity>> {
    let base = prefs.get("vaultRoot")?.as_str()?;
    if base.trim().is_empty() || prefs.get("vaultMigrated")?.as_bool() != Some(true) {
        return None;
    }
    let root = std::path::Path::new(base).join(vault::SUB_DIR);
    if !root.is_dir() {
        return None;
    }

    let mut out = Vec::new();
    for entry in vault::scan(&root).into_iter().filter(|e| !e.is_dir) {
        let Ok(note) = vault::read_note(&root, &entry.path) else {
            continue;
        };
        let (uid, body) = split_note(&note.content);
        let (subject, filename) = match entry.path.rsplit_once('/') {
            Some((dir, file)) => (
                dir.split('/').next().unwrap_or("").to_string(),
                file.to_string(),
            ),
            None => (String::new(), entry.path.clone()),
        };
        let title = filename
            .strip_suffix(".md")
            .or_else(|| filename.strip_suffix(".MD"))
            .unwrap_or(&filename)
            .to_string();
        let index = out.len();
        out.push(KnowledgeEntity {
            id: (index + 1) as i32,
            subject,
            title,
            content: body,
            position: index as i32,
            created_at: note.mtime,
            // 外部丢进来的 .md 没有 uid：用路径兜底，保证每行都有稳定非空串
            uid: uid.unwrap_or_else(|| format!("vault:{}", entry.path)),
            updated_at: note.mtime,
            deleted_at: 0,
        });
    }
    Some(out)
}

pub fn build(db: &Db, paths: &Paths, prefs: &Value, modules: &[String]) -> Result<Value, String> {
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
                // 文件库是桌面端当前的真相；只有没启用时才退回冻结的 DB 表。
                // 手机端导入的仍是同一种 v10 行，格式一字未改。
                let items = match vault_knowledge(prefs) {
                    Some(v) => v,
                    None => db.knowledge()?,
                };
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
            "recurring" => {
                // 独立模块、整表替换。不塞进 settings：那边是「逐键合并」语义，
                // 塞进去会让导入与本机规则混成一团，且一个键要装整个数组。
                let payload = crate::store::load_recurring(paths);
                let items = payload.get("items").cloned().unwrap_or(json!([]));
                let count = items.as_array().map(|a| a.len()).unwrap_or(0);
                json!({ "count": count, "items": items })
            }
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
    /// 导入的循环提醒条数；没选该模块时为 0
    pub recurring: usize,
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
        recurring: 0,
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

    if wanted("recurring") {
        if let Some(items) = all
            .get("recurring")
            .and_then(|v| v.get("items"))
            .and_then(|v| v.as_array())
        {
            // 整表替换：与 courses/notes/knowledge 一致，不合并、不保留本机独有项
            crate::store::save_recurring(paths, Value::Array(items.clone()))?;
            report.recurring = items.len();
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

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_base(tag: &str) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!(
            "stumate-backup-{tag}-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&path);
        std::fs::create_dir_all(path.join(vault::SUB_DIR)).unwrap();
        path
    }

    #[test]
    fn split_note_reads_uid_and_keeps_body() {
        let raw = "---\nstumate: true\nstumate_uid: abc-123\ncreated: 2026-01-01T10:00\n---\n\n正文第一行\n\n第二行\n";
        let (uid, body) = split_note(raw);
        assert_eq!(uid.as_deref(), Some("abc-123"));
        assert_eq!(body, "正文第一行\n\n第二行\n");
        assert!(!body.contains("stumate_uid"));

        let (none, plain) = split_note("没有元信息的正文\n");
        assert_eq!(none, None);
        assert_eq!(plain, "没有元信息的正文\n");
    }

    #[test]
    fn vault_knowledge_maps_files_to_v10_rows() {
        let base = temp_base("map");
        let root = base.join(vault::SUB_DIR);
        vault::write_note(
            &root,
            "物理/牛顿.md",
            "---\nstumate: true\nstumate_uid: uid-a\n---\n\nF=ma\n",
            None,
        )
        .unwrap();
        vault::write_note(&root, "随笔.md", "一段没有元信息的文字", None).unwrap();

        let prefs = json!({
            "vaultRoot": base.to_string_lossy(),
            "vaultMigrated": true
        });
        let rows = vault_knowledge(&prefs).expect("启用了文件库就该接管");
        assert_eq!(rows.len(), 2);

        let newton = rows.iter().find(|r| r.title == "牛顿").unwrap();
        assert_eq!(newton.subject, "物理");
        assert_eq!(newton.uid, "uid-a");
        assert_eq!(newton.content, "F=ma\n");
        assert_eq!(newton.deleted_at, 0);
        assert_eq!(newton.id, 1);

        let plain = rows.iter().find(|r| r.title == "随笔").unwrap();
        assert_eq!(plain.subject, "");
        assert_eq!(plain.uid, "vault:随笔.md");
        assert_eq!(plain.content, "一段没有元信息的文字");

        // 九列一个不多、一个不少：安卓 Room 的 schema 校验靠这个
        let value = serde_json::to_value(newton).unwrap();
        assert_eq!(value.as_object().unwrap().len(), 9);
        let _ = std::fs::remove_dir_all(&base);
    }

    fn temp_env(tag: &str) -> (std::path::PathBuf, Paths, crate::db::Db) {
        let dir = std::env::temp_dir().join(format!(
            "stumate-backup-rec-{tag}-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(vault::SUB_DIR)).unwrap();
        let paths = Paths {
            data_dir: dir.clone(),
            db: dir.join("class_reminder.db"),
            settings: dir.join("settings.lite.json"),
            settings_original: dir.join("settings.json"),
            mindmap: dir.join("mindmap.json"),
            links: dir.join("knowledge_links.json"),
            recurring: dir.join("recurring.json"),
            note_links: dir.join("note_links.json"),
        };
        let db = crate::db::Db::open(&paths.db).unwrap();
        (dir, paths, db)
    }

    #[test]
    fn recurring_module_is_the_fifth_and_keeps_schema_3() {
        assert_eq!(MODULE_KEYS.len(), 5);
        assert!(MODULE_KEYS.contains(&"recurring"));
        // 旧客户端只校验 format、对未知模块键静默忽略；schema 一旦改动
        // 就可能触发老解码器的分支，所以这一版必须继续是 3。
        assert_eq!(SCHEMA, 3);
        assert_eq!(FORMAT, "stumate-backup");
    }

    #[test]
    fn recurring_module_roundtrips_with_whole_table_replacement() {
        let (dir, paths, db) = temp_env("round");
        let items = json!([{
            "id": "r1", "title": "起身活动", "body": "别一直坐着",
            "enabled": true,
            "rule": { "kind": "interval", "minutes": 45, "anchor": 1762588800000i64 },
            "endAt": null, "lastFiredAt": 0, "createdAt": 1762588800000i64
        }]);
        crate::store::save_recurring(&paths, items.clone()).unwrap();

        let wanted = vec!["recurring".to_string()];
        let payload = build(&db, &paths, &json!({}), &wanted).unwrap();
        assert_eq!(payload["schema"], 3);
        assert_eq!(payload["modules"]["recurring"]["count"], 1);
        assert_eq!(payload["modules"]["recurring"]["items"], items);
        // describe() 不需要认识新模块，按 count 自动出预览
        assert_eq!(describe(&payload)["modules"]["recurring"]["count"], 1);

        // 本机另有一条规则 → 导入后应被整表替换掉，不是合并
        crate::store::save_recurring(&paths, json!([{ "id": "local-only" }])).unwrap();
        let report = apply(&db, &paths, &payload, &wanted).unwrap();
        assert_eq!(report.recurring, 1);
        let after = crate::store::load_recurring(&paths);
        assert_eq!(after["version"], 1);
        assert_eq!(after["items"], items);
        assert_eq!(after["items"].as_array().unwrap().len(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn not_selecting_recurring_leaves_local_rules_untouched() {
        let (dir, paths, db) = temp_env("untouched");
        let local = json!([{ "id": "local-only" }]);
        crate::store::save_recurring(&paths, local.clone()).unwrap();
        let payload = json!({
            "format": FORMAT,
            "schema": SCHEMA,
            "modules": { "recurring": { "count": 1, "items": [{ "id": "from-backup" }] } }
        });
        let report = apply(&db, &paths, &payload, &["settings".to_string()]).unwrap();
        assert_eq!(report.recurring, 0);
        assert_eq!(crate::store::load_recurring(&paths)["items"], local);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn vault_knowledge_defers_to_db_when_not_enabled() {
        let base = temp_base("off");
        assert!(vault_knowledge(&json!({})).is_none());
        assert!(vault_knowledge(&json!({ "vaultRoot": base.to_string_lossy() })).is_none());
        assert!(vault_knowledge(&json!({
            "vaultRoot": base.to_string_lossy(),
            "vaultMigrated": false
        }))
        .is_none());
        // 启用但库是空的：Some(空) 才是「真相」，不能退回 DB 的旧行
        let empty = vault_knowledge(&json!({
            "vaultRoot": base.to_string_lossy(),
            "vaultMigrated": true
        }))
        .unwrap();
        assert!(empty.is_empty());
        let _ = std::fs::remove_dir_all(&base);
    }
}
