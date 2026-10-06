"""桌面端发布工具：打补丁包 / 绿色版，建 GitHub Release 并上传资产。

为什么要有这个脚本
------------------
发布流程原先全靠手点：找 `build/compose/binaries/main/app/StuMate/`、挑出主 jar 和
`StuMate.cfg`、压 zip、去网页建 Release、逐个拖文件。**每次都要重新判断哪两个文件
是变的**，很容易压错包 —— 而压错的后果是用户覆盖后应用起不来。

这里把「哪两个文件」变成代码：**主 jar 是 `app/` 里唯一文件名带版本号的那个**，
其余 37 个依赖 jar 跨版本逐字节不变（见 docs/desktop-update-strategy.md）。

⚠️ `runtime/` 不再是「永远不变」的了 —— 1.6.1 就因为裁剪模块集漏了
`jdk.crypto.mscapi` 导致「检查更新」永远报 PKIX。所以补丁包现在会**总是**带上
`runtime/release`（247 字节，写着这个版本期望的模块集），并在模块集真的变了时
额外带上 `runtime/lib/modules`（约 48 MB）。详见 pack()。

用法
----
    # 打包（从已构建的目录版产出 dist/ 下的两个 zip + SHA-256）
    python tools/release.py pack --version 1.6.0

    # 指定基准（默认自动挑 dist/ 里版本号最大的那个更早的 portable zip）
    python tools/release.py pack --version 1.6.2 --base dist/StuMate-portable-1.6.1.zip

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
import zlib

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


# ── runtime 感知 ────────────────────────────────────────────────
#
# 补丁包**总是**带 `runtime/release`（247 字节）—— 客户端拿它跟本地的比，
# 判断「这个版本期望的模块集」和「用户手上那个」一不一样。
#
# 只有真不一样时才额外带 `runtime/lib/modules`（约 48 MB）。
# 为什么不能每次都带：正常发版模块集根本不动，为 48 MB 的冗余让所有人多下 25 倍
# 流量不值得。为什么必须能带：1.6.1 就是漏了 `jdk.crypto.mscapi`，
# 而当时的补丁包换不了 runtime → 修了代码也送不到用户手上。

# runtime 里这两个由「模块集比对」单独处理，其余部分必须逐字节不变
RUNTIME_SKIP = {"lib/modules", "release"}


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def parse_modules(text):
    m = re.search(r'MODULES="([^"]*)"', text)
    return m.group(1).split() if m else None


def runtime_modules_dir(dist_dir):
    p = os.path.join(dist_dir, "runtime", "release")
    if not os.path.isfile(p):
        return None
    with open(p, encoding="utf-8", errors="replace") as f:
        return parse_modules(f.read())


def modules_in_zip(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        for n in z.namelist():
            if n.replace("\\", "/").endswith("/runtime/release"):
                return parse_modules(z.read(n).decode("utf-8", "replace"))
    return None


def find_base_zip(version, explicit=None):
    """找上一版的 portable zip 当基准：`dist/` 里版本号最大、且小于 [version] 的那个。"""
    if explicit:
        if not os.path.isfile(explicit):
            die(f"基准文件不存在：{explicit}")
        return explicit
    best = None
    for f in sorted(os.listdir(DIST)):
        m = re.match(r"StuMate-portable-(\d+(?:\.\d+)*)\.zip$", f)
        if not m:
            continue
        v = m.group(1)
        if vkey(v) >= vkey(version):
            continue
        if best is None or vkey(v) > vkey(best[0]):
            best = (v, os.path.join(DIST, f))
    return best[1] if best else None


def runtime_manifest_zip(zip_path):
    """zip 里 `runtime/` 下除 lib/modules 与 release 外的 (相对路径 → CRC)"""
    out = {}
    with zipfile.ZipFile(zip_path) as z:
        for i in z.infolist():
            if i.is_dir():
                continue
            n = i.filename.replace("\\", "/")
            if "/runtime/" not in n:
                continue
            rel = n.split("/runtime/", 1)[1]
            if rel in RUNTIME_SKIP:
                continue
            out[rel] = i.CRC
    return out


def runtime_manifest_dir(dist_dir):
    """目录版里 `runtime/` 下除 lib/modules 与 release 外的 (相对路径 → CRC)"""
    out = {}
    base = os.path.join(dist_dir, "runtime")
    for root, _dirs, files in os.walk(base):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, base).replace("\\", "/")
            if rel in RUNTIME_SKIP:
                continue
            with open(full, "rb") as fh:
                out[rel] = zlib.crc32(fh.read()) & 0xFFFFFFFF
    return out


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

    # ── ① 模块集比对，决定补丁要不要带 runtime/lib/modules ──────────
    new_modules = runtime_modules_dir(dist_dir)
    if new_modules is None:
        die(f"{dist_dir}/runtime/release 里读不出 MODULES —— 产物不完整")

    base_zip = find_base_zip(version, args.base)
    base_modules = modules_in_zip(base_zip) if base_zip else None
    carry_modules = base_modules != new_modules

    if base_zip is None:
        print("⚠️ dist/ 里找不到可用的基准 portable zip —— 保守起见把 modules 打进补丁")
    else:
        print(f"基准     {os.path.basename(base_zip)}")
        print(f"  模块集 {len(base_modules or [])} 个 → {len(new_modules)} 个："
              + ("**不同**，补丁将带 runtime/lib/modules"
                 if carry_modules else "相同，补丁只带 runtime/release"))

    # ── ② runtime 里除 modules/release 之外的部分必须逐字节不变 ─────
    # 那些是 dll/exe（`bin/*.exe`、`lib/server/jvm.dll`），由 Windows loader
    # 加载、**真的锁死**，运行中换不了。变了就只能走整包 —— 在这里拦住，
    # 而不是等用户更新到一半失败。
    if base_zip is not None:
        base_man = runtime_manifest_zip(base_zip)
        new_man = runtime_manifest_dir(dist_dir)
        diff = sorted({k for k, _ in set(base_man.items()) ^ set(new_man.items())})
        if diff:
            die("runtime 里除 lib/modules 与 release 之外的东西变了，补丁换不了：\n  "
                + "\n  ".join(diff[:10])
                + f"\n（共 {len(diff)} 项；这些是 dll/exe，被 loader 锁死）"
                + "\n→ 这个版本必须走整包发布。")

    # ── ③ 写包 ────────────────────────────────────────────────────
    # 补丁的目录前缀必须是 `app/` / `runtime/`，用户解压到安装目录根下才落在正确位置。
    with zipfile.ZipFile(patch, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(jar, "app/" + os.path.basename(jar))
        z.write(cfg, "app/StuMate.cfg")
        z.write(os.path.join(dist_dir, "runtime", "release"), "runtime/release")
        if carry_modules:
            z.write(os.path.join(dist_dir, "runtime", "lib", "modules"),
                    "runtime/lib/modules")

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
    if carry_modules:
        print("⚠️ 这个补丁带 runtime/lib/modules —— 只有**认识 runtime 条目**的更新器"
              "（≥ 1.6.2）能用它。更老的版本必须装整包。")
    else:
        print("✓ 模块集没变，补丁不含 runtime/lib/modules（体积仍然很小）。")


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
             f"- **已有安装（1.6.2 及以后）**：应用内「设置 → 软件更新 → 检查更新」点一下就行，"
             "不用手动下载。",
             f"- **已有安装（1.6.2 之前）**：请用 `StuMate-portable-{version}.zip` 覆盖安装一次。"
             "更早的更新器不认识补丁包里的 runtime 条目，会静默跳过 —— "
             "看起来更新成功、实际运行时没换。",
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
    pk.add_argument("--base", help="基准 portable zip（默认自动挑 dist/ 里更早的最大版本）")
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
