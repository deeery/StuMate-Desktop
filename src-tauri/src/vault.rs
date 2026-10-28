//! Obsidian vault 导出：把前端铺好的 `{path, content}` 落盘。
//!
//! 命名、重名递增、frontmatter 全在 `src/model/vault.ts`（纯函数、有 vitest 钉住）。
//! 这里只做两件必须靠近文件系统的事：
//!   1) **路径不许穿越** —— `files` 由前端给，但渲染进程的输入一律当作不可信；
//!   2) 建目录 + 写入，并逐条统计失败原因（一条写不进去不该让整次导出失败）。
//!
//! 覆盖写、不删除 —— 具体理由见前端 `vault.ts` 顶部的注释。

use serde::{Deserialize, Serialize};
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Deserialize)]
pub struct VaultFile {
    pub path: String,
    pub content: String,
}

#[derive(Debug, Serialize)]
pub struct VaultReport {
    pub written: usize,
    pub failed: usize,
    pub dir: String,
    /// 前若干条失败原因，给用户看「为什么少了几篇」
    pub errors: Vec<String>,
}

/// 把相对路径安全地接到 root 上。
///
/// 拒绝：绝对路径、`..`、以及任何非普通名的前缀（盘符、UNC）。
/// `./` 这种无意义的当前目录分量直接放过，没必要因此报错。
pub fn safe_join(root: &Path, rel: &str) -> Result<PathBuf, String> {
    if rel.trim().is_empty() {
        return Err("空的相对路径".into());
    }
    let normalized = rel.replace('\\', "/");
    let path = Path::new(&normalized);
    if path.is_absolute() {
        return Err(format!("不允许绝对路径：{rel}"));
    }
    for c in path.components() {
        match c {
            Component::Normal(_) | Component::CurDir => {}
            _ => return Err(format!("非法路径：{rel}")),
        }
    }
    Ok(root.join(path))
}

pub fn write_files(root: &Path, files: &[VaultFile]) -> Result<VaultReport, String> {
    if !root.exists() {
        return Err(format!("目录不存在：{}", root.display()));
    }
    if !root.is_dir() {
        return Err(format!("这不是一个目录：{}", root.display()));
    }

    let mut written = 0usize;
    let mut errors: Vec<String> = Vec::new();

    for f in files {
        let target = match safe_join(root, &f.path) {
            Ok(t) => t,
            Err(e) => {
                errors.push(e);
                continue;
            }
        };
        if let Some(dir) = target.parent() {
            if let Err(e) = std::fs::create_dir_all(dir) {
                errors.push(format!("{}：建目录失败 {e}", f.path));
                continue;
            }
        }
        match std::fs::write(&target, f.content.as_bytes()) {
            Ok(()) => written += 1,
            Err(e) => errors.push(format!("{}：写入失败 {e}", f.path)),
        }
    }

    Ok(VaultReport {
        written,
        failed: errors.len(),
        dir: root.display().to_string(),
        // 全量失败时错误会很多，只留前 5 条：够说明问题，又不至于把对话框撑爆
        errors: errors.into_iter().take(5).collect(),
    })
}

// ───────────────────────────────────────────────────────────────────────────
// 知识库本体
//
// 与上面的「导出」是两件事：导出是**一次性铺一批文件**（从数据库往外倒），
// 这里是**库的日常读写**（扫描、读、带冲突检测的写、建目录、改名、删除）。
//
// 一条边界贯穿全部命令：`base` 是用户选的**库根**，真正的读写范围是
// `base/Stumate/` 这棵子树。这条边界写在 Rust 里而不是前端 ——
// 前端只需要传库根，没有任何办法（包括 bug）把写入挪到子树外面去。
// ───────────────────────────────────────────────────────────────────────────

/// 库根下的子目录名。与 `vault.ts` 的 `VAULT_ROOT` 必须一致。
pub const SUB_DIR: &str = "Stumate";

/// 递归深度上限。软链接成环时 `read_dir` 会一直往下走，用它兜底。
const MAX_DEPTH: usize = 32;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VaultEntry {
    /// 相对 `Stumate/` 的路径，`/` 分隔
    pub path: String,
    pub is_dir: bool,
    /// 毫秒时间戳；目录取自身 mtime
    pub mtime: i64,
    pub size: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NoteContent {
    pub content: String,
    pub mtime: i64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WriteAck {
    /// true = 文件在我们读它之后被外部改过，**没有写入**
    pub conflict: bool,
    pub mtime: i64,
    /// 实际落盘的相对路径（可能是前端传的那个）
    pub path: String,
}

fn mtime_ms(meta: &std::fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 把库根解析成 `Stumate/` 子树，不存在就建出来。
pub fn sub_root(base: &Path) -> Result<PathBuf, String> {
    if !base.is_dir() {
        return Err(format!("库目录不存在：{}", base.display()));
    }
    let root = base.join(SUB_DIR);
    if !root.is_dir() {
        std::fs::create_dir_all(&root).map_err(|e| format!("建库目录失败：{e}"))?;
    }
    Ok(root)
}

/// 扫描整棵子树。跳过一切点开头的名字（`.trash` / `.obsidian` / `.git`），
/// 非 `.md` 文件一律不报 —— 用户可能在库里放图片，那是 Obsidian 的事。
pub fn scan(root: &Path) -> Vec<VaultEntry> {
    let mut out = Vec::new();
    walk(root, "", &mut out, 0);
    // read_dir 的顺序由文件系统决定，不排一下每次扫描都不一样，
    // 会让「外部有没有变化」的判断永远为真。
    out.sort_by(|a, b| a.path.cmp(&b.path));
    out
}

fn walk(dir: &Path, rel: &str, out: &mut Vec<VaultEntry>, depth: usize) {
    if depth > MAX_DEPTH {
        return;
    }
    let entries = match std::fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return, // 没权限的目录跳过，不因为一个子目录让整库打不开
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue;
        }
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let child = if rel.is_empty() {
            name.clone()
        } else {
            format!("{rel}/{name}")
        };
        if meta.is_dir() {
            out.push(VaultEntry {
                path: child.clone(),
                is_dir: true,
                mtime: mtime_ms(&meta),
                size: 0,
            });
            walk(&entry.path(), &child, out, depth + 1);
        } else if name.to_lowercase().ends_with(".md") {
            out.push(VaultEntry {
                path: child,
                is_dir: false,
                mtime: mtime_ms(&meta),
                size: meta.len(),
            });
        }
    }
}

/// 整棵树的「版本号」：路径 + mtime 的哈希。
///
/// 前端每两秒问一次这个，只在变了之后才重新扫描。为什么不直接监听文件系统：
/// 库通常放在 OneDrive / 坚果云 / Syncthing 目录里，这些同步盘**不保证**
/// 发出目录变更事件（写入是后台进程延迟落盘的），`ReadDirectoryChangesW` 会漏。
/// 轮询 mtime 慢一点，但它对「谁写的」没有要求，反而更可靠。
pub fn rev(root: &Path) -> String {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    if !root.is_dir() {
        return String::new();
    }
    let mut hasher = DefaultHasher::new();
    for e in scan(root) {
        e.path.hash(&mut hasher);
        e.mtime.hash(&mut hasher);
        e.is_dir.hash(&mut hasher);
    }
    format!("{:016x}", hasher.finish())
}

pub fn read_note(root: &Path, rel: &str) -> Result<NoteContent, String> {
    let target = safe_join(root, rel)?;
    let meta = std::fs::metadata(&target).map_err(|e| format!("读不到：{e}"))?;
    if meta.is_dir() {
        return Err("这是一个目录".into());
    }
    let bytes = std::fs::read(&target).map_err(|e| format!("读不到：{e}"))?;
    // 非 UTF-8 一律按有损解码：宁可显示几个替换字符，也不能因为一个坏字节打不开文件
    let content = String::from_utf8_lossy(&bytes).to_string();
    Ok(NoteContent {
        content,
        mtime: mtime_ms(&meta),
    })
}

/// 写一篇笔记，并做「外部改过没有」的检查。
///
/// [expected] 是打开这篇笔记时读到的 mtime：
///  - `Some(t)` 且磁盘上的 mtime 已经不是 `t` → 返回 `conflict`，**一个字都不写**；
///  - `None` → 不检查（新建，或者调用方明确要覆盖）。
///
/// 这一条是整套「文件即真相」的兜底：没有它，网盘把别人的修改同步下来之后，
/// 你按下保存就把它盖掉了，而且没有任何机会知道。
pub fn write_note(
    root: &Path,
    rel: &str,
    content: &str,
    expected: Option<i64>,
) -> Result<WriteAck, String> {
    let target = safe_join(root, rel)?;

    if let (Some(exp), Ok(meta)) = (expected, std::fs::metadata(&target)) {
        let now = mtime_ms(&meta);
        if now != exp {
            return Ok(WriteAck {
                conflict: true,
                mtime: now,
                path: rel.to_string(),
            });
        }
    }

    if let Some(dir) = target.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("建目录失败：{e}"))?;
    }

    // 先写临时文件再改名：直接覆盖时若在写到一半崩溃，笔记会变成半截。
    // 临时名以点开头，扫描器会跳过，不会在树里闪一下。
    let tmp = target.with_file_name(format!(".{}.tmp", std::process::id()));
    std::fs::write(&tmp, content.as_bytes()).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        format!("写入失败：{e}")
    })?;
    if let Err(e) = std::fs::rename(&tmp, &target) {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("保存失败：{e}"));
    }

    let mtime = std::fs::metadata(&target)
        .map(|m| mtime_ms(&m))
        .unwrap_or(0);
    Ok(WriteAck {
        conflict: false,
        mtime,
        path: rel.to_string(),
    })
}

pub fn mkdir(root: &Path, rel: &str) -> Result<(), String> {
    let target = safe_join(root, rel)?;
    std::fs::create_dir_all(&target).map_err(|e| format!("建目录失败：{e}"))
}

pub fn rename(root: &Path, from: &str, to: &str) -> Result<(), String> {
    let src = safe_join(root, from)?;
    let dst = safe_join(root, to)?;
    if !src.exists() {
        return Err(format!("不存在：{from}"));
    }
    if let Some(dir) = dst.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("建目录失败：{e}"))?;
    }
    std::fs::rename(&src, &dst).map_err(|e| format!("改名失败：{e}"))
}

/// 删除 = 挪进 `<root>/.trash/`，**不是**物理删除。
///
/// 树里点一下右键就永久删掉一个笔记，是这个模块最容易造成不可挽回损失的地方。
/// 挪进点开头的目录有两个好处：扫描器天然看不见它，用户想找回的时候
/// 资源管理器里翻得到；不用引第三方回收站库，也没有「回收站满了」这种状态。
pub fn trash(root: &Path, rel: &str) -> Result<String, String> {
    let src = safe_join(root, rel)?;
    if !src.exists() {
        return Err(format!("不存在：{rel}"));
    }
    let dir = root.join(".trash");
    std::fs::create_dir_all(&dir).map_err(|e| format!("建回收目录失败：{e}"))?;
    let name = src
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "未命名".into());
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let dst = dir.join(format!("{stamp}_{name}"));
    std::fs::rename(&src, &dst).map_err(|e| format!("删除失败：{e}"))?;
    Ok(dst.display().to_string())
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GrepHit {
    pub path: String,
    pub count: usize,
    /// 第一处命中所在一行，已裁到 80 字符以内
    pub preview: String,
}

/// 全文搜索（大小写不敏感的子串）。
///
/// 为什么不做索引：几百个 `.md` 全读一遍是一两毫秒的事，而索引一旦存在就必须
/// 回答「外部改了文件索引怎么更新」——那正是这套设计一开始就想躲开的问题。
/// 等库大到扫不动再说，那时该换的是存储形态，不是加一个索引。
pub fn grep(root: &Path, needle: &str) -> Vec<GrepHit> {
    let needle = needle.trim().to_lowercase();
    if needle.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    for entry in scan(root) {
        if entry.is_dir {
            continue;
        }
        let bytes = match std::fs::read(root.join(&entry.path)) {
            Ok(b) => b,
            Err(_) => continue,
        };
        let text = String::from_utf8_lossy(&bytes);
        let lower = text.to_lowercase();
        let count = lower.matches(&needle).count();
        if count == 0 {
            continue;
        }
        out.push(GrepHit {
            path: entry.path,
            count,
            preview: first_line_with(&text, &needle),
        });
    }
    out.sort_by(|a, b| b.count.cmp(&a.count).then(a.path.cmp(&b.path)));
    out
}

/// 取第一处命中所在行；命中不在正文而在 frontmatter 时也照样返回那一行。
///
/// 定位用的是小写串里的字节偏移，而切片要切原文 —— 少数字符（如 `İ`）小写化后
/// 字节长度会变，偏移就对不上了。所以长度不一致时退回原文定位，
/// 再不行就交出一行空预览：**预览难看可以忍，切在字符中间 panic 不行**。
fn first_line_with(text: &str, needle: &str) -> String {
    let lower = text.to_lowercase();
    let at = lower
        .find(needle)
        .filter(|_| lower.len() == text.len())
        .or_else(|| text.find(needle))
        .unwrap_or(0);

    let start = text[..at.min(text.len())].rfind('\n').map(|i| i + 1).unwrap_or(0);
    let end = text[at.min(text.len())..]
        .find('\n')
        .map(|i| at.min(text.len()) + i)
        .unwrap_or(text.len());
    let line = match text.get(start..end) {
        Some(s) => s.trim(),
        None => return String::new(),
    };
    let mut out: String = line.chars().take(80).collect();
    if line.chars().count() > 80 {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normal_relative_path() {
        let root = Path::new("C:/vault");
        assert_eq!(
            safe_join(root, "Stumate/大学物理/牛顿.md").unwrap(),
            root.join("Stumate/大学物理/牛顿.md")
        );
    }

    #[test]
    fn backslash_is_separator() {
        let root = Path::new("C:/vault");
        assert!(safe_join(root, "Stumate\\高数\\极限.md").is_ok());
    }

    #[test]
    fn reject_parent_traversal() {
        let root = Path::new("C:/vault");
        assert!(safe_join(root, "../外面.md").is_err());
        assert!(safe_join(root, "Stumate/../../外面.md").is_err());
    }

    #[test]
    fn reject_absolute() {
        let root = Path::new("C:/vault");
        assert!(safe_join(root, "C:/Windows/x.md").is_err());
        assert!(safe_join(root, "/etc/passwd").is_err());
    }

    #[test]
    fn reject_empty() {
        assert!(safe_join(Path::new("C:/vault"), "   ").is_err());
    }

    /// 真正落一次盘：建多级目录、写内容、坏路径不拖垮整批。
    /// 上层命令（选目录 → 导出）在验收环境点不动鼠标，所以写盘这一环只能靠这个测试钉住。
    #[test]
    fn write_files_creates_dirs_and_reports_partial_failure() {
        let root = std::env::temp_dir().join(format!("stumate-vault-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();

        let files = vec![
            VaultFile {
                path: "Stumate/大学物理/牛顿第一定律.md".into(),
                content: "---\nstumate: true\n---\n\n正文\n".into(),
            },
            VaultFile {
                path: "Stumate/未分类/随手记.md".into(),
                content: "只有正文".into(),
            },
            // 越界路径必须被挡下，且不影响前两条写入
            VaultFile {
                path: "../逃出去.md".into(),
                content: "x".into(),
            },
        ];

        let report = write_files(&root, &files).unwrap();
        assert_eq!(report.written, 2);
        assert_eq!(report.failed, 1);
        assert_eq!(report.errors.len(), 1);

        let md = root.join("Stumate/大学物理/牛顿第一定律.md");
        assert!(md.is_file(), "多级目录没建出来");
        assert_eq!(
            std::fs::read_to_string(&md).unwrap(),
            "---\nstumate: true\n---\n\n正文\n"
        );
        assert!(!root.parent().unwrap().join("逃出去.md").exists());

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn write_files_rejects_missing_root() {
        let missing = std::env::temp_dir().join("stumate-vault-does-not-exist");
        let _ = std::fs::remove_dir_all(&missing);
        assert!(write_files(&missing, &[]).is_err());
    }

    // ── 下面这些是知识库本体 ────────────────────────────────────────────

    fn temp_vault(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("stumate-kb-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn scan_lists_tree_and_skips_hidden_and_binary() {
        let base = temp_vault("scan");
        let root = sub_root(&base).unwrap();
        std::fs::create_dir_all(root.join("大学物理/例题")).unwrap();
        std::fs::write(root.join("大学物理/第一章.md"), "x").unwrap();
        std::fs::write(root.join("大学物理/例题/斜面.md"), "y").unwrap();
        std::fs::write(root.join("大学物理/图.png"), "z").unwrap();
        std::fs::create_dir_all(root.join(".trash")).unwrap();
        std::fs::write(root.join(".trash/已删.md"), "q").unwrap();

        let paths: Vec<String> = scan(&root).into_iter().map(|e| e.path).collect();
        assert_eq!(
            paths,
            vec![
                "大学物理",
                "大学物理/例题",
                "大学物理/例题/斜面.md",
                "大学物理/第一章.md"
            ]
        );
        let _ = std::fs::remove_dir_all(&base);
    }

    /// 外部改了文件之后，带旧 mtime 的保存必须被拦下，且一个字都不落盘。
    #[test]
    fn write_refuses_after_external_change() {
        let base = temp_vault("conflict");
        let root = sub_root(&base).unwrap();

        let first = write_note(&root, "笔记.md", "我的第一版", None).unwrap();
        assert!(!first.conflict);

        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(root.join("笔记.md"), "外部同步下来的").unwrap();

        let second = write_note(&root, "笔记.md", "我的第二版", Some(first.mtime)).unwrap();
        assert!(second.conflict, "外部改动没被检测到");
        assert_eq!(read_note(&root, "笔记.md").unwrap().content, "外部同步下来的");

        // 认领新 mtime 之后就能正常写
        let third = write_note(&root, "笔记.md", "我的第二版", Some(second.mtime)).unwrap();
        assert!(!third.conflict);
        assert_eq!(read_note(&root, "笔记.md").unwrap().content, "我的第二版");

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn write_creates_missing_parents() {
        let base = temp_vault("mkdirs");
        let root = sub_root(&base).unwrap();
        write_note(&root, "新科目/子目录/新笔记.md", "正文", None).unwrap();
        assert!(root.join("新科目/子目录/新笔记.md").is_file());
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn trash_moves_into_hidden_dir_and_hides_from_scan() {
        let base = temp_vault("trash");
        let root = sub_root(&base).unwrap();
        write_note(&root, "待删.md", "x", None).unwrap();

        let where_to = trash(&root, "待删.md").unwrap();
        assert!(!root.join("待删.md").exists());
        assert!(std::path::Path::new(&where_to).is_file(), "没有进回收目录");
        assert!(scan(&root).iter().all(|e| !e.path.contains("待删")));
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn rename_moves_and_keeps_content() {
        let base = temp_vault("rename");
        let root = sub_root(&base).unwrap();
        write_note(&root, "旧.md", "正文", None).unwrap();
        rename(&root, "旧.md", "目录/新.md").unwrap();
        assert!(!root.join("旧.md").exists());
        assert_eq!(read_note(&root, "目录/新.md").unwrap().content, "正文");
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn rename_rejects_escape() {
        let base = temp_vault("rename-escape");
        let root = sub_root(&base).unwrap();
        write_note(&root, "a.md", "x", None).unwrap();
        assert!(rename(&root, "a.md", "../逃出去.md").is_err());
        assert!(root.join("a.md").exists());
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn rev_follows_edits() {
        let base = temp_vault("rev");
        let root = sub_root(&base).unwrap();
        let before = rev(&root);
        write_note(&root, "a.md", "一", None).unwrap();
        let after = rev(&root);
        assert_ne!(before, after);

        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(root.join("a.md"), "二").unwrap();
        assert_ne!(after, rev(&root), "外部改动没体现在版本号上");

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn sub_root_creates_and_requires_base() {
        let base = temp_vault("subroot");
        assert!(sub_root(&base).unwrap().is_dir());
        let missing = base.join("不存在的库");
        assert!(sub_root(&missing).is_err());
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn grep_matches_case_insensitively_and_previews_the_line() {
        let base = temp_vault("grep");
        let root = sub_root(&base).unwrap();
        write_note(&root, "物理/牛顿.md", "第一行\n牛顿第二定律 F=ma\n第三行", None).unwrap();
        write_note(&root, "物理/无关.md", "什么都没有", None).unwrap();

        let hits = grep(&root, "f=MA");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].path, "物理/牛顿.md");
        assert_eq!(hits[0].preview, "牛顿第二定律 F=ma");
        assert_eq!(hits[0].count, 1);

        assert!(grep(&root, "   ").is_empty(), "空白查询不该扫全库");
        assert!(grep(&root, "不存在的词").is_empty());

        let _ = std::fs::remove_dir_all(&base);
    }
}
