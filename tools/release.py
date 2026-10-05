"""桌面端发布工具：打补丁包 / 绿色版，建 GitHub Release 并上传资产。

为什么要有这个脚本
------------------
发布流程原先全靠手点：找 `build/compose/binaries/main/app/StuMate/`、挑出主 jar 和
`StuMate.cfg`、压 zip、去网页建 Release、逐个拖文件。**每次都要重新判断哪两个文件
是变的**，很容易压错包 —— 而压错的后果是用户覆盖后应用起不来。

这里把「哪两个文件」变成代码：**主 jar 是 `app/` 里唯一文件名带版本号的那个**，
其余 37 个依赖 jar 与 `runtime/` 跨版本逐字节不变（见 docs/desktop-update-strategy.md）。

用法
----
    # 打包（从已构建的目录版产出 dist/ 下的两个 zip + SHA-256）
    python tools/release.py pack --version 1.6.0

    # 从 git log 生成 release notes
    python tools/release.py notes --version 1.6.0 --since v1.5.0

    # 建 tag + Release + 传资产
    python tools/release.py publish --version 1.6.0 --notes-file /tmp/notes.md

    # 只看要做什么，不真调 API
    python tools/release.py publish --version 1.6.0 --dry-run

⚠️ token 从 `~/.git-credentials` 读（git 的 credential store）。
   **绝不要把 token 打进客户端** —— 客户端匿名调 api.github.com 就够了，
   这个仓库是 public，匿名限流 60 次/小时/IP，启动时查一次完全够用。
"""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile

REPO = "deeery/StuMate-Desktop"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")
API = "https://api.github.com"

# 默认找独立构建目录（打包时正在跑的实例会锁住 build/，见 memory 里的说明）。
# 按顺序找第一个存在的。
DIST_CANDIDATES = [
    "F:/DownloadQQ/stumate-altbuild/compose/binaries/main/app/StuMate",
    os.path.join(ROOT, "build", "compose", "binaries", "main", "app", "StuMate"),
]


# ── 基础工具 ────────────────────────────────────────────────────

def die(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def human(n):
    return f"{n:,} 字节（{n / 1048576:.2f} MB）"


def find_distributable(explicit=None):
    cands = [explicit] if explicit else DIST_CANDIDATES
    for c in cands:
        if c and os.path.isdir(c) and os.path.isfile(os.path.join(c, "StuMate.exe")):
            return os.path.abspath(c)
    die("找不到目录版产物，试过：\n  " + "\n  ".join(str(c) for c in cands)
        + "\n先跑：./gradlew --offline createDistributable")


def main_jar(dist_dir, version):
    """`app/` 里文件名带版本号的那个 jar —— 就是唯一会变的那份。

    刻意用「版本号出现在文件名里」当判据，而不是「取最大的 jar」：
    后者在依赖升级时会挑错。
    """
    app = os.path.join(dist_dir, "app")
    hits = [f for f in os.listdir(app)
            if f.startswith("StuMate-Desktop-") and f.endswith(".jar") and version in f]
    if len(hits) != 1:
        die(f"{app} 下按版本 {version} 找到 {len(hits)} 个主 jar：{hits}")
    return os.path.join(app, hits[0])


# ── 打包 ────────────────────────────────────────────────────────

def pack(args):
    version = args.version
    dist_dir = find_distributable(args.dist)
    jar = main_jar(dist_dir, version)
    cfg = os.path.join(dist_dir, "app", "StuMate.cfg")
    if not os.path.isfile(cfg):
        die(f"缺少 {cfg}")

    os.makedirs(DIST, exist_ok=True)
    patch = os.path.join(DIST, f"StuMate-patch-{version}.zip")
    portable = os.path.join(DIST, f"StuMate-portable-{version}.zip")

    # 补丁包：只含 app/ 下那两处（jar + cfg）。目录前缀必须是 `app/`，
    # 用户解压到安装目录根下才落在正确位置。
    with zipfile.ZipFile(patch, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(jar, "app/" + os.path.basename(jar))
        z.write(cfg, "app/StuMate.cfg")

    # 绿色版：整目录，根节点是 `StuMate/`（与历史包一致）。
    with zipfile.ZipFile(portable, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _dirs, files in os.walk(dist_dir):
            for f in files:
                full = os.path.join(base, f)
                rel = os.path.relpath(full, os.path.dirname(dist_dir))
                z.write(full, rel.replace("\\", "/"))

    print(f"源目录   {dist_dir}")
    print(f"主 jar   {os.path.basename(jar)}  {human(os.path.getsize(jar))}")
    print()
    for p in (patch, portable):
        n = sum(1 for _ in zipfile.ZipFile(p).namelist())
        print(f"{os.path.basename(p)}")
        print(f"  {human(os.path.getsize(p))}  /  {n} 个条目")
        print(f"  sha256 {sha256(p)}")
    print()
    print("⚠️ 补丁包不含 runtime —— 只能发给 runtime 里已有 java.sql 的安装"
          "（runtime/lib/modules ≥ 46,000,000 字节）。")


# ── release notes ───────────────────────────────────────────────

def git(*a):
    r = subprocess.run(["git", "-C", ROOT, *a], capture_output=True, text=True)
    return r.stdout.strip()


def gen_notes(args):
    version = args.version
    rng = f"{args.since}..HEAD" if args.since else "-20"
    log = git("log", "--pretty=format:%h%x09%s", rng)
    lines = [l for l in log.splitlines() if l.strip()]
    body = [f"## StuMate 桌面版 {version}", ""]
    if not lines:
        body.append("（没有新的提交）")
    for l in lines:
        h, _, subject = l.partition("\t")
        body.append(f"- {subject} (`{h}`)")
    body += ["", "### 下载", "",
             f"- **已有安装**：`StuMate-patch-{version}.zip`（约 1.7 MB，解压覆盖到安装目录，"
             "进程要先退出）",
             f"- **全新安装**：`StuMate-portable-{version}.zip`（免安装，解压即用）"]
    return "\n".join(body)


# ── GitHub API ──────────────────────────────────────────────────

def gh_token():
    path = os.path.expanduser("~/.git-credentials")
    if not os.path.isfile(path):
        die(f"找不到 {path}；也可以用环境变量 GITHUB_TOKEN")
    if os.environ.get("GITHUB_TOKEN"):
        return os.environ["GITHUB_TOKEN"]
    for line in open(path, encoding="utf-8"):
        m = re.match(r"https://([^:]+):([^@]+)@github\.com", line.strip())
        if m:
            return m.group(2)
    die("~/.git-credentials 里没找到 github.com 的凭据")


def api(method, url, token, payload=None, raw=None, ctype="application/json"):
    data = None
    if raw is not None:
        data = raw
    elif payload is not None:
        data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "StuMate-Release-Script")
    if data is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:600]
        die(f"{method} {url} → HTTP {e.code}\n{detail}")


def publish(args):
    version = args.version
    tag = f"v{version}"
    assets = [os.path.join(DIST, f"StuMate-patch-{version}.zip"),
              os.path.join(DIST, f"StuMate-portable-{version}.zip")]
    for a in assets:
        if not os.path.isfile(a):
            die(f"缺资产 {a}；先跑 `python tools/release.py pack --version {version}`")

    if args.notes_file:
        notes = open(args.notes_file, encoding="utf-8").read()
    else:
        notes = gen_notes(args)

    if args.dry_run:
        print(f"[dry-run] 仓库 {args.repo}   tag {tag}   名称 v{version}")
        print(f"[dry-run] 资产：")
        for a in assets:
            print(f"    {os.path.basename(a)}  {human(os.path.getsize(a))}")
        print("[dry-run] notes 前 12 行：")
        print("\n".join(notes.splitlines()[:12]))
        return

    token = gh_token()
    head = git("rev-parse", "HEAD")

    # tag 必须打在本地 HEAD 上，否则 Release 指向的树和资产对不上
    remote_tag = git("ls-remote", "--tags", "origin", tag)
    if remote_tag:
        print(f"tag {tag} 已存在，跳过创建")
    else:
        print(f"创建并推送 tag {tag} → {head[:8]}")
        subprocess.run(["git", "-C", ROOT, "tag", "-a", tag, "-m", f"StuMate 桌面版 {version}"],
                       check=True)
        subprocess.run(["git", "-C", ROOT, "push", "origin", tag], check=True)

    print("创建 Release …")
    rel = api("POST", f"{API}/repos/{args.repo}/releases", token, payload={
        "tag_name": tag,
        "name": f"StuMate 桌面版 {version}",
        "body": notes,
        "draft": False,
        "prerelease": False,
    })
    print("  " + rel["html_url"])

    for a in assets:
        name = os.path.basename(a)
        print(f"上传 {name} …")
        api("POST",
            f"https://uploads.github.com/repos/{args.repo}/releases/{rel['id']}/assets"
            f"?name={name}",
            token, raw=open(a, "rb").read(), ctype="application/zip")
        print("  OK")

    print("\n完成。客户端会从这里读到版本：")
    print(f"  {API}/repos/{args.repo}/releases/latest")


# ── CLI ─────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description="StuMate 桌面端发布工具")
    sub = p.add_subparsers(dest="cmd", required=True)

    pk = sub.add_parser("pack", help="打补丁包 + 绿色版")
    pk.add_argument("--version", required=True)
    pk.add_argument("--dist", help="目录版路径（默认自动找）")
    pk.set_defaults(func=pack)

    nt = sub.add_parser("notes", help="从 git log 生成 release notes")
    nt.add_argument("--version", required=True)
    nt.add_argument("--since", help="起始 tag，如 v1.5.0")
    nt.set_defaults(func=lambda a: print(gen_notes(a)))

    pb = sub.add_parser("publish", help="建 tag + Release + 上传资产")
    pb.add_argument("--version", required=True)
    pb.add_argument("--repo", default=REPO)
    pb.add_argument("--notes-file")
    pb.add_argument("--since")
    pb.add_argument("--dry-run", action="store_true")
    pb.set_defaults(func=publish)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
