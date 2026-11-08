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

// ── 回收站 ────────────────────────────────────────────────────────
//
// v1.7.14 之前只「挪进去」：文件名是 `{毫秒}_{basename}`，原目录没记，
// 所以在界面上想还原都不知道该放回哪。现在每次删除往 `.trash/index.json`
// 追加一条元数据（原相对路径 / 删除时刻 / 是不是目录）。
//
// 老版本删的条目没有元数据，照样列得出来，只是 `orphan: true` —— 只能放回
// 根目录。宁可少还原一层目录，也不要凭空猜一个路径。
// index 本身也放在 `.trash` 里并被扫描器跳过，不会混进笔记树。

const TRASH_DIR: &str = ".trash";
const TRASH_INDEX: &str = "index.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrashItem {
    /// `.trash` 里的条目名（纯文件名，前端拿它当 id）
    pub name: String,
    /// 删除前相对 `Stumate/` 的路径
    pub rel: String,
    pub deleted_at: i64,
    pub is_dir: bool,
    /// 缺元数据（老版本删的）：只能还原到根目录。
    /// 注意不能写 `#[serde(skip)]` —— 这个字段是给 `vault_trash_list` 的
    /// 响应用的，跳过序列化后前端只会拿到 undefined，老条目会被误当成可精确还原。
    /// 写进 index.json 的那份恒为 false，多存一个键没有副作用。
    #[serde(default)]
    pub orphan: bool,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct TrashIndex {
    #[serde(default)]
    version: i32,
    #[serde(default)]
    items: Vec<TrashItem>,
}

fn trash_dir(root: &Path) -> PathBuf {
    root.join(TRASH_DIR)
}

fn read_index(dir: &Path) -> TrashIndex {
    std::fs::read_to_string(dir.join(TRASH_INDEX))
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn write_index(dir: &Path, index: &TrashIndex) -> Result<(), String> {
    let text = serde_json::to_string_pretty(index).map_err(|e| format!("回收站索引序列化失败：{e}"))?;
    // 与 write_note 同款：临时文件以点开头，扫描器看不见
    let tmp = dir.join(".index.tmp");
    std::fs::write(&tmp, text.as_bytes()).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        format!("写回收站索引失败：{e}")
    })?;
    std::fs::rename(&tmp, dir.join(TRASH_INDEX))
        .map_err(|e| format!("替换回收站索引失败：{e}"))
}

/// 只收纯文件名。渲染进程传来的名字一律当不可信：`../` 能直接把库外的
/// 文件挪进回收站，清空时还会把它删掉。
fn check_trash_name(name: &str) -> Result<(), String> {
    if name.trim().is_empty()
        || name.starts_with('.')
        || name.contains('/')
        || name.contains('\\')
        || name.contains("..")
    {
        return Err(format!("非法的回收站条目名：{name}"));
    }
    Ok(())
}

/// 从 `{毫秒}_{原名}` 里拆出老条目信息；认不出来就整串当原名、时间记 0。
fn parse_trash_name(name: &str) -> (i64, String) {
    if let Some((head, tail)) = name.split_once('_') {
        if !head.is_empty() && head.chars().all(|c| c.is_ascii_digit()) && !tail.is_empty() {
            return (head.parse::<i64>().unwrap_or(0), tail.to_string());
        }
    }
    (0, name.to_string())
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 目标被占了就顺延成 ` 2` / ` 3`，与前端 `vaultTree.uniqueTitle` 同口径。
/// 只在「还原」时用：正常改名撞名由前端先问过用户，不该走到这里。
fn free_rel(root: &Path, rel: &str, is_dir: bool) -> Result<String, String> {
    safe_join(root, rel)?;
    if !root.join(rel).exists() {
        return Ok(rel.to_string());
    }
    let (parent, filename) = match rel.rsplit_once('/') {
        Some((p, f)) => (p.to_string(), f.to_string()),
        None => (String::new(), rel.to_string()),
    };
    let (stem, ext) = if is_dir {
        (filename.clone(), String::new())
    } else {
        match filename.rsplit_once('.') {
            Some((s, e)) => (s.to_string(), format!(".{e}")),
            None => (filename.clone(), String::new()),
        }
    };
    for n in 2..10000 {
        let candidate = format!("{stem} {n}{ext}");
        let combined = if parent.is_empty() {
            candidate
        } else {
            format!("{parent}/{candidate}")
        };
        if !root.join(&combined).exists() {
            return Ok(combined);
        }
    }
    Err("找不到可用的还原文件名".into())
}

/// 删除 = 挪进 `<root>/.trash/`，**不是**物理删除。
///
/// 先写索引再挪文件：索引写失败时磁盘上什么都没发生，不会出现「文件没了、
/// 却不知道它原来在哪」。挪失败则把刚写的条目撤回。
pub fn trash(root: &Path, rel: &str) -> Result<String, String> {
    let src = safe_join(root, rel)?;
    if !src.exists() {
        return Err(format!("不存在：{rel}"));
    }
    let dir = trash_dir(root);
    std::fs::create_dir_all(&dir).map_err(|e| format!("建回收目录失败：{e}"))?;
    let original = src
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "未命名".into());
    let stamp = now_ms();
    let is_dir = src.is_dir();

    // 同一毫秒删两个同名文件（不同目录）会撞名，顺延一下
    let mut name = format!("{stamp}_{original}");
    for n in 2..10000 {
        if !dir.join(&name).exists() {
            break;
        }
        name = format!("{stamp}_{n}_{original}");
    }

    let mut index = read_index(&dir);
    index.version = 1;
    index.items.push(TrashItem {
        name: name.clone(),
        rel: rel.to_string(),
        deleted_at: stamp,
        is_dir,
        orphan: false,
    });
    write_index(&dir, &index)?;

    let dst = dir.join(&name);
    if let Err(e) = std::fs::rename(&src, &dst) {
        index.items.retain(|i| i.name != name);
        let _ = write_index(&dir, &index);
        return Err(format!("删除失败：{e}"));
    }
    Ok(dst.display().to_string())
}

/// 列出回收站。索引里没有的实体条目也列出来（老版本删的），标 `orphan`。
pub fn trash_list(root: &Path) -> Vec<TrashItem> {
    let dir = trash_dir(root);
    let index = read_index(&dir);
    let mut out = Vec::new();
    let entries = match std::fs::read_dir(&dir) {
        Ok(e) => e,
        Err(_) => return out,
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if name == TRASH_INDEX || name.starts_with('.') {
            continue;
        }
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let (rel, deleted_at, orphan) = match index.items.iter().find(|i| i.name == name) {
            Some(i) => (i.rel.clone(), i.deleted_at, false),
            None => {
                let (stamp, original) = parse_trash_name(&name);
                (original, stamp, true)
            }
        };
        out.push(TrashItem {
            name,
            rel,
            deleted_at,
            is_dir: meta.is_dir(),
            orphan,
        });
    }
    out.sort_by(|a, b| b.deleted_at.cmp(&a.deleted_at).then(a.name.cmp(&b.name)));
    out
}

/// 还回原路径。目标被占了就顺延（不覆盖、不丢东西），返回实际落点。
pub fn trash_restore(root: &Path, name: &str) -> Result<String, String> {
    check_trash_name(name)?;
    let dir = trash_dir(root);
    let src = dir.join(name);
    if !src.exists() {
        return Err(format!("回收站里没有：{name}"));
    }
    let mut index = read_index(&dir);
    let known = index.items.iter().find(|i| i.name == name).cloned();
    let (base_rel, is_dir) = match known {
        Some(i) => (i.rel.clone(), i.is_dir),
        None => (parse_trash_name(name).1, src.is_dir()),
    };
    let rel = free_rel(root, &base_rel, is_dir)?;
    let dst = safe_join(root, &rel)?;
    if let Some(parent) = dst.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("建目录失败：{e}"))?;
    }
    std::fs::rename(&src, &dst).map_err(|e| format!("还原失败：{e}"))?;
    index.items.retain(|i| i.name != name);
    let _ = write_index(&dir, &index);
    Ok(rel)
}

/// 单条彻底删除。**唯一会真的抹掉字节的地方**，界面上必须二次确认。
pub fn trash_purge(root: &Path, name: &str) -> Result<(), String> {
    check_trash_name(name)?;
    let dir = trash_dir(root);
    let target = dir.join(name);
    if !target.exists() {
        return Err(format!("回收站里没有：{name}"));
    }
    if target.is_dir() {
        std::fs::remove_dir_all(&target).map_err(|e| format!("删除目录失败：{e}"))?;
    } else {
        std::fs::remove_file(&target).map_err(|e| format!("删除文件失败：{e}"))?;
    }
    let mut index = read_index(&dir);
    index.items.retain(|i| i.name != name);
    let _ = write_index(&dir, &index);
    Ok(())
}

pub fn trash_empty(root: &Path) -> Result<usize, String> {
    let dir = trash_dir(root);
    if !dir.is_dir() {
        return Ok(0);
    }
    let mut removed = 0usize;
    let entries = std::fs::read_dir(&dir).map_err(|e| format!("读回收站失败：{e}"))?;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if name == TRASH_INDEX || name.starts_with('.') {
            continue;
        }
        let path = entry.path();
        let ok = if path.is_dir() {
            std::fs::remove_dir_all(&path).is_ok()
        } else {
            std::fs::remove_file(&path).is_ok()
        };
        if ok {
            removed += 1;
        }
    }
    let _ = write_index(&dir, &TrashIndex {
        version: 1,
        items: Vec::new(),
    });
    Ok(removed)
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

    // ── v1.7.14 回收站：列出 / 还原 / 彻底删除 / 清空 ──────────────

    #[test]
    fn trash_index_remembers_original_path_and_restore_puts_it_back() {
        let base = temp_vault("trash-restore");
        let root = sub_root(&base).unwrap();
        write_note(&root, "物理/力学/牛顿.md", "F=ma", None).unwrap();

        trash(&root, "物理/力学/牛顿.md").unwrap();
        let list = trash_list(&root);
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].rel, "物理/力学/牛顿.md");
        assert!(!list[0].orphan);
        assert!(!list[0].is_dir);
        assert!(list[0].deleted_at > 0);

        let rel = trash_restore(&root, &list[0].name).unwrap();
        assert_eq!(rel, "物理/力学/牛顿.md", "没放回原目录");
        assert_eq!(read_note(&root, &rel).unwrap().content, "F=ma");
        assert!(trash_list(&root).is_empty(), "还原后索引里不该留着这一条");
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn restore_does_not_overwrite_and_falls_back_with_suffix() {
        let base = temp_vault("restore-clash");
        let root = sub_root(&base).unwrap();
        write_note(&root, "笔记.md", "旧的", None).unwrap();
        trash(&root, "笔记.md").unwrap();
        write_note(&root, "笔记.md", "重建的", None).unwrap();

        let name = trash_list(&root)[0].name.clone();
        let rel = trash_restore(&root, &name).unwrap();
        assert_eq!(rel, "笔记 2.md");
        assert_eq!(read_note(&root, "笔记.md").unwrap().content, "重建的");
        assert_eq!(read_note(&root, "笔记 2.md").unwrap().content, "旧的");
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn orphan_entry_is_listed_and_restored_to_root() {
        let base = temp_vault("trash-orphan");
        let root = sub_root(&base).unwrap();
        let dir = trash_dir(&root);
        std::fs::create_dir_all(&dir).unwrap();
        // 老版本留下的条目：没有 index，只有 `{stamp}_{原名}`
        std::fs::write(dir.join("1730000000000_老笔记.md"), "旧版本内容").unwrap();

        let list = trash_list(&root);
        assert_eq!(list.len(), 1);
        assert!(list[0].orphan);
        assert_eq!(list[0].deleted_at, 1730000000000);
        let rel = trash_restore(&root, &list[0].name).unwrap();
        assert_eq!(rel, "老笔记.md");
        assert_eq!(read_note(&root, &rel).unwrap().content, "旧版本内容");
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn trash_folder_keeps_its_tree_and_purge_and_empty_work() {
        let base = temp_vault("trash-folder");
        let root = sub_root(&base).unwrap();
        write_note(&root, "科目/一/甲.md", "甲", None).unwrap();
        write_note(&root, "科目/一/乙.md", "乙", None).unwrap();

        trash(&root, "科目/一").unwrap();
        assert!(trash_list(&root)[0].is_dir);
        let rel = trash_restore(&root, &trash_list(&root)[0].name).unwrap();
        assert_eq!(rel, "科目/一");
        assert_eq!(read_note(&root, "科目/一/甲.md").unwrap().content, "甲");

        trash(&root, "科目/一").unwrap();
        let name = trash_list(&root)[0].name.clone();
        trash_purge(&root, &name).unwrap();
        assert!(trash_list(&root).is_empty());
        assert!(!root.join(".trash").join(&name).exists());

        write_note(&root, "a.md", "a", None).unwrap();
        write_note(&root, "b.md", "b", None).unwrap();
        trash(&root, "a.md").unwrap();
        trash(&root, "b.md").unwrap();
        assert_eq!(trash_list(&root).len(), 2);
        assert_eq!(trash_empty(&root).unwrap(), 2);
        assert!(trash_list(&root).is_empty());
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn trash_commands_reject_path_traversal() {
        let base = temp_vault("trash-evil");
        let root = sub_root(&base).unwrap();
        std::fs::create_dir_all(trash_dir(&root)).unwrap();
        assert!(trash_restore(&root, "../库外.md").is_err());
        assert!(trash_purge(&root, "..\\库外.md").is_err());
        assert!(trash_restore(&root, ".trash").is_err());
        let _ = std::fs::remove_dir_all(&base);
    }
}
