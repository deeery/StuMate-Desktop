"""像素扫描量骨架，把「看着差不多」变成可比较的数字。

为什么要有这个：视觉验收最容易出的错是凭印象说「一致」，而实际差 8dp。
这里只用色带边界当判据 —— 侧栏右缘、顶栏底边、卡片左缘、内容留白 ——
全部换算回 **dp**（Lite 截图是 1.5 倍密度，原版参考是 1.0），再逐项对齐。

用法：
  python measure_skeleton.py            # 量 PAIRS 里登记的两组图
  python measure_skeleton.py --dump     # 额外打印色带/主色，便于排查

为什么要「游程 + 最小长度」而不是「相邻像素差 > 阈值」：
  浅色主题里侧栏 #EBEBEB 与内容底 #F3F3F3 只差 8 级，而卡片描边、文字抗锯齿
  又是 1~2px 的孤立跳变。逐像素判边会把描边当成侧栏右缘（实测把 212 报成 228）。
  改成「同色连续 ≥8px 才算一段」，抗锯齿噪声就被吃掉了。

判据：
  sidebar_end   侧栏 surface2 的右缘
  topbar_bottom 顶栏底分隔线所在行
  card_left     内容区里第一块 surface 卡片的左缘
  gutter        侧栏右缘 → 卡片左缘（= 内容区左留白）
"""
import json
import os
import sys
from collections import Counter

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF_DIR = r"C:/Users/Administrator/AppData/Local/Temp/StuMateDpi/ref"
LITE_DIR = os.path.join(ROOT, "design-preview", "acceptance")

# (页面, 原版参考文件, Lite 文件)
#
# 缩放**不写死**：两端的窗口都是 1200×800 dp，所以 scale = 图宽 / 1200 恒成立。
# 写死 1.0/1.5 会踩一个很贵的坑 —— 本机换过显示器（物理 150% ←→ 串流虚拟屏 100%），
# 同一份脚本会因为 DPI 变了而算出一堆假的「差 0.5dp / 差 200dp」。
PAIRS = [
    ("week", "week.png", "dark-week.png"),
    ("notes", "notes.png", "dark-notes.png"),
    ("knowledge", "knowledge.png", "dark-knowledge-list.png"),
    ("week(light)", "week-light.png", "light-week.png"),
    ("notes(light)", "notes-light.png", "light-notes.png"),
]

DESIGN_W = 1200  # 设计稿宽度（dp）

TOL = 6        # 视为「同一段颜色」的容差（三通道平均）
MIN_RUN = 8    # 一段至少这么长才认（px，按原图密度）


def same(a, b, tol=TOL):
    return (abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])) / 3.0 <= tol


def runs_row(im, y, min_run):
    w, _ = im.size
    out, start, cur = [], 0, im.getpixel((0, y))
    for x in range(1, w + 1):
        c = im.getpixel((x, y)) if x < w else None
        if c is None or not same(c, cur):
            if x - start >= min_run:
                out.append((start, x - 1, cur))
            start, cur = x, c
    return out


def runs_col(im, x, min_run):
    _, h = im.size
    out, start, cur = [], 0, im.getpixel((x, 0))
    for y in range(1, h + 1):
        c = im.getpixel((x, y)) if y < h else None
        if c is None or not same(c, cur):
            if y - start >= min_run:
                out.append((start, y - 1, cur))
            start, cur = y, c
    return out


def dominant(im, n=8):
    small = im.resize((im.size[0] // 2, im.size[1] // 2))
    cnt = Counter(small.getdata())
    return [(c, round(k / sum(cnt.values()) * 100, 1)) for c, k in cnt.most_common(n)]


def measure(path, scale, dump=False):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    mr = max(2, int(MIN_RUN * scale))
    out = {"px": [w, h], "scale": scale}

    # ---- 侧栏右缘：42% 高度横扫黑；第一段（侧栏）结束处即右缘
    y = int(h * 0.42)
    row = runs_row(im, y, mr)
    out["sidebar_end"] = round(row[0][1] / scale, 1) if len(row) >= 2 else None

    # ---- 顶栏底边：在内容区左留白里竖扫。
    # 判据是「第一个与页面底色明显不同的行」而不是「找一段短游程」——
    # 分隔线只有 1px，浅色主题里还会被抗锯齿摊成两级灰，游程法会把它并进底色。
    # 留白里从 y=0 到卡片顶只有两种东西：底色、那条线。所以第一个跳变就是它。
    x = int(((out["sidebar_end"] or 0) + 6) * scale) if out["sidebar_end"] else int(w * 0.25)
    base = im.getpixel((x, 0))
    topbar = None
    for y2 in range(1, int(120 * scale)):
        if not same(im.getpixel((x, y2)), base, 8):
            topbar = y2
            break
    out["topbar_bottom"] = round(topbar / scale, 1) if topbar is not None else None

    # ---- 卡片左缘：横扫黑里侧栏之后的第一段起点
    card_left = None
    if out["sidebar_end"]:
        for (a, b, c) in row[1:]:
            if a / scale > out["sidebar_end"] + 1:
                card_left = a
                break
    out["card_left"] = round(card_left / scale, 1) if card_left else None
    if out["sidebar_end"] and card_left:
        out["gutter"] = round((card_left - (out["sidebar_end"] * scale)) / scale, 1)

    # ---- 关键色：底色取「侧栏右侧 4dp、顶栏中部」——那里恒为页面底色，
    #      不能取宽度百分比（62% 处正好会落在搜索框/按钮文字上，量出来是 #050505）。
    if out["topbar_bottom"] and out["sidebar_end"]:
        ty = int(out["topbar_bottom"] * scale / 2)
        out["bg"] = im.getpixel((int((out["sidebar_end"] + 4) * scale), ty))
    if out["sidebar_end"]:
        out["sidebar"] = im.getpixel((int(out["sidebar_end"] * scale / 2), y))

    if dump:
        out["row_runs"] = [(round(a / scale, 1), round(b / scale, 1), c) for a, b, c in row[:10]]
        out["dominant"] = dominant(im)
    return out


def hexs(c):
    return "#%02X%02X%02X" % c if c else "None"


def main():
    dump = "--dump" in sys.argv
    rows, bad = [], 0
    for name, ref, lite in PAIRS:
        rp, lp = os.path.join(REF_DIR, ref), os.path.join(LITE_DIR, lite)
        if not (os.path.isfile(rp) and os.path.isfile(lp)):
            print(f"跳过 {name}: 缺 {ref} 或 {lite}")
            continue
        rs = Image.open(rp).size[0] / DESIGN_W
        ls = Image.open(lp).size[0] / DESIGN_W
        if abs(rs - ls) > 0.01:
            print(f"  ! {name} 两端密度不同：ref={rs:.2f} lite={ls:.2f}（各自按自身密度换算）")
        r, l = measure(rp, rs, dump), measure(lp, ls, dump)
        print(f"\n=== {name} ===")
        for k in ("sidebar_end", "topbar_bottom", "card_left", "gutter"):
            rv, lv = r.get(k), l.get(k)
            if rv is None or lv is None:
                mark = "?"
            else:
                d = abs(rv - lv)
                mark = "OK" if d <= 2 else f"DIFF {d:.1f}"
                if d > 2:
                    bad += 1
            print(f"  {k:<14} ref={rv!s:>7} lite={lv!s:>7}  {mark}")
        print(f"  bg          ref={hexs(r.get('bg'))} lite={hexs(l.get('bg'))}"
              f"  {'OK' if r.get('bg') == l.get('bg') else 'DIFF'}")
        print(f"  sidebar     ref={hexs(r.get('sidebar'))} lite={hexs(l.get('sidebar'))}"
              f"  {'OK' if r.get('sidebar') == l.get('sidebar') else 'DIFF'}")
        if dump:
            print(f"  ref  runs: {r.get('row_runs')}")
            print(f"  lite runs: {l.get('row_runs')}")
            print(f"  ref  top: {r.get('dominant')}")
            print(f"  lite top: {l.get('dominant')}")
        rows.append({"page": name, "ref": r, "lite": l})

    out = os.path.join(ROOT, "design-preview", "skeleton-measure.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print(f"\n{'全部通过（±2dp）' if bad == 0 else f'{bad} 项超差'} → {out}")
    return 0 if bad == 0 else 3


if __name__ == "__main__":
    sys.exit(main())
