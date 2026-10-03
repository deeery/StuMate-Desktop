# -*- coding: utf-8 -*-
"""生成 StuMate 注册邀请码（服务端 invite_codes 表，明文存储）。

用法:
  python tools/gen_invite_code.py <个数> [--days 30] [--note "批次备注"]
  python tools/gen_invite_code.py 1 --note "桌面端验收"

## 字符集（从服务端现有 19 个码反推得出，**30** 个有效字符）
    23456789ABCDEFGHJKMNPQRSTVWXYZ
剔除了易混淆的 I / L / O / U（1/I、0/O 尤其容易看错）。
格式 `XXXX-XXXX-XXXX`，分隔符 `-` 不参与字符集。
熵 = 12 × log2(30) ≈ 58.9 bit，单个码被猜中的概率可忽略。

🔴 复核字符集时注意：`sqlite3 "SELECT group_concat(code) ..."` 的输出里
**逗号是 SQLite 的分隔符**，不是码字符 —— 直接拿去 `fold -w1 | sort -u`
会多出一个 `,`（我第一版就是这么把断言写成 31 才发现实际 30 的）。

## 为什么走 CLI 而不是 HTTP 接口
服务端**没有**公开的「生成邀请码」路由（注册才是公开面）。
`createInviteCode()` 只在 `lib/stumate/store.ts` 里，由管理员脚本调用。
所以必须在服务器上跑 node/写库。

## 🔴 写库前必须先备份，且要用 SQLite **在线备份**
`cp stumate.db ...` 是**不安全**的：库开着 WAL，实测 `stumate.db-wal` 有 4 MB
（主库才 286 KB），`cp` 只拿到主库文件会**丢掉 WAL 里的数据**。
正确做法：
    sqlite3 stumate.db ".backup '/var/lib/deeer/stumate.db.full-<ts>'"
再 `PRAGMA integrity_check;` 确认。
"""
import argparse
import secrets
import sys
from datetime import datetime, timedelta, timezone

ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
assert len(ALPHABET) == 30, len(ALPHABET)


def gen_code(rng=None):
    """生成一个 `XXXX-XXXX-XXXX` 邀请码。"""
    r = rng or secrets.SystemRandom()
    body = "".join(r.choice(ALPHABET) for _ in range(12))
    return "%s-%s-%s" % (body[0:4], body[4:8], body[8:12])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("count", nargs="?", type=int, default=1)
    ap.add_argument("--days", type=int, default=30, help="有效天数，0 = 永不过期")
    ap.add_argument("--note", default="")
    ap.add_argument("--max-uses", type=int, default=1)
    ap.add_argument("--seed", type=int, default=0,
                    help=">0 时用确定性随机（仅供测试复现，正式生成别用）")
    args = ap.parse_args()

    if args.count < 1:
        raise SystemExit("个数至少 1")

    rng = None
    if args.seed:
        import random
        rng = random.Random(args.seed)

    now = datetime.now(timezone.utc)
    expires = (now + timedelta(days=args.days)).isoformat(
        timespec="milliseconds").replace("+00:00", "Z") if args.days > 0 else None
    created = now.isoformat(timespec="milliseconds").replace("+00:00", "Z")

    print("SERVER\t\thttps://deeer.online/api/stumate/v1")
    print("CREATED\t%s" % created)
    print("EXPIRES\t%s" % (expires or "(永不过期)"))
    print("NOTE\t%s" % (args.note or "(空)"))
    print("PARAMS\t%s" % " | ".join([
        "count=%d" % args.count,
        "max_uses=%d" % args.max_uses,
        ("days=%d" % args.days) if args.days > 0 else "永不过期",
        "charset=%s" % ALPHABET,
    ]))
    print("---")
    for i in range(args.count):
        print("%s\t%s\t%d" % (i + 1, gen_code(rng), args.max_uses))


if __name__ == "__main__":
    main()