//! 数据模型。
//!
//! 字段名与顺序**逐列对齐**原版 `data/ClassEntity.kt` / `NoteEntity.kt` / `KnowledgeEntity.kt`，
//! 因为 Lite 与原版共用同一个 SQLite 文件（`%APPDATA%\StuMate\class_reminder.db`）。
//! 任何一侧多一列、少一列、换个类型，另一侧打开就会报错 —— 这是本工程最硬的一条约束。
//!
//! 序列化统一 `camelCase`，前端直接用 `ClassEntity` 这样的形状，不需要中间转换层。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassEntity {
    #[serde(default)]
    pub id: i32,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub day_of_week: String,
    #[serde(default)]
    pub start_time: String,
    #[serde(default)]
    pub end_time: String,
    #[serde(default)]
    pub room: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub teacher: String,
    #[serde(default)]
    pub weeks: String,
    #[serde(default)]
    pub date: String,
    #[serde(default)]
    pub uid: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NoteEntity {
    #[serde(default)]
    pub id: i32,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub content: String,
    #[serde(default)]
    pub position: i32,
    #[serde(default)]
    pub created_at: i64,
    #[serde(default)]
    pub color_index: i32,
    #[serde(default)]
    pub type_index: i32,
    #[serde(default)]
    pub custom_label: String,
    #[serde(default)]
    pub deadline_at: i64,
    #[serde(default)]
    pub uid: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KnowledgeEntity {
    #[serde(default)]
    pub id: i32,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub content: String,
    #[serde(default)]
    pub position: i32,
    #[serde(default)]
    pub created_at: i64,
    #[serde(default)]
    pub uid: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: i64,
}

/// 导图节点。**不落 SQLite**，见 `store.rs` 顶部说明。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MindNode {
    pub uid: String,
    #[serde(default)]
    pub parent_uid: String,
    #[serde(default)]
    pub label: String,
    /// "subject" | "entry" | "manual"
    #[serde(default = "default_kind")]
    pub kind: String,
    #[serde(default)]
    pub knowledge_uid: String,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub position: i32,
    #[serde(default)]
    pub collapsed: bool,
    #[serde(default)]
    pub suppressed: bool,
}

fn default_kind() -> String {
    "manual".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkRef {
    #[serde(default)]
    pub uid: String,
    #[serde(default)]
    pub entry_uid: String,
    #[serde(default)]
    pub target: String,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub added_at: i64,
}

/// 应用与数据位置的说明，给设置页「关于」显示，也用来证明两版确实指向同一份数据。
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub version: String,
    pub flavor: String,
    pub data_dir: String,
    pub db_path: String,
    pub schema_version: i32,
    pub shared_with_original: bool,
    pub original_present: bool,
    pub settings_path: String,
    /// 预览用：`STUMATE_LITE_PAGE` 指定的初始页（验收截图时省掉"点一下导航"）
    pub start_page: Option<String>,
    /// 预览用：`STUMATE_LITE_VIEW` 指定的初始视图（知识库的 list / map）
    pub start_view: Option<String>,
    /// 预览用：`STUMATE_LITE_THEME` 强制主题（light / dark），不改用户偏好文件
    pub start_theme: Option<String>,
}
