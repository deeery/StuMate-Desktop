"""把原版与 Lite 的同页截图拼成左右对照图，用来肉眼验收。

关键约定：**两半必须先归一到同一套「dp 坐标」再拼**。
原版参考是 1.0 密度截的（1200×800 px = 1200×800 dp），Lite 是 1.5 密度
（1800×1200 px）。直接并排会让人误以为 Lite 的字更大、间距更宽，其实只是密度差。
所以 Lite 一律 LANCZOS 降到 1200×800 再拼。

用法：
  python compare_pairs.py           # 输出到 design-preview/compare-v1.7.6/
  python compare_pairs.py --half 900  # 改单侧宽度（默认 1200）

每条对照都带标题栏，写明是哪一端 —— 之前不带标签，回看时根本认不出左右。
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF_DIR = r"C:/Users/Administrator/AppData/Local/Temp/StuMateDpi"
LITE_DIR = os.path.join(ROOT, "design-preview", "acceptance")
OUT = os.path.join(ROOT, "design-preview", "compare-v1.7.6")
TMP = os.path.join(ROOT, "design-preview", "_probe")  # 临时探针图，不算交付物

LEFT_LABEL = "原版 StuMate-desktop v1.7.5 · Compose（基准）"
RIGHT_LABEL = "Lite v1.7.6 · Tauri + React"

# (输出名, 左图, 右图)
# 右图的缩放不写死：由 `图宽 / 1200` 现算（窗口恒为 1200×800 dp）。
# 本机在物理屏 150% 与串流虚拟屏 100% 之间来回切，写死 1.5 时一旦切到 100%，
# 右半会被二次缩小 —— 看着像「Lite 的字小了一号」，其实是脚本自己缩的。
PAIRS = [
    ("today", "ref-today.png", "probe-today-collapsed.png"),
    ("week", "ref/week.png", "dark-week.png"),
    ("notes", "ref/notes.png", "dark-notes.png"),
    ("knowledge", "ref/knowledge.png", "dark-knowledge-list.png"),
    ("week-light", "ref/week-light.png", "light-week.png"),
    ("notes-light", "ref/notes-light.png", "light-notes.png"),
]

GAP = 16
BAR = 26
FONT_CANDIDATES = [
    r"C:/Windows/Fonts/msyh.ttc",
    r"C:/Windows/Fonts/segoeui.ttf",
]


def font(size=13):
    for p in FONT_CANDIDATES:
        if os.path.isfile(p):
            try:
                return ImageFont.truetype(p, size)
            except OSError:
                continue
    return ImageFont.load_default()


def normalize(path, scale, tw, th):
    im = Image.open(path).convert("RGB")
    if im.size == (tw, th):
        return im
    return im.resize((tw, th), Image.LANCZOS)


def build(name, lp, rp, rscale, tw, th):
    left = normalize(lp, 1.0, tw, th)
    right = normalize(rp, rscale, tw, th)

    canvas = Image.new("RGB", (tw * 2 + GAP, th + BAR), (46, 46, 46))
    f = font(13)
    d = ImageDraw.Draw(canvas)
    for i, lab in enumerate((LEFT_LABEL, RIGHT_LABEL)):
        x0 = i * (tw + GAP)
        d.rectangle([x0, 0, x0 + tw, BAR - 1], fill=(58, 58, 58))
        d.text((x0 + 10, 6), lab, font=f, fill=(232, 232, 232))
    canvas.paste(left, (0, BAR))
    canvas.paste(right, (tw + GAP, BAR))
    # 中缝画一条竖线，避免两半在暗色下糊成一张
    d.rectangle([tw, BAR, tw + GAP - 1, th + BAR], fill=(30, 30, 30))
    out = os.path.join(OUT, f"{name}.png")
    canvas.save(out)
    print(f"  {name:<14} {canvas.size[0]}x{canvas.size[1]}")
    return out


def main():
    half_w = 1200
    if "--half" in sys.argv:
        half_w = int(sys.argv[sys.argv.index("--half") + 1])
    half_h = round(half_w * 800 / 1200)
    os.makedirs(OUT, exist_ok=True)

    made = 0
    for name, ref, lite in PAIRS:
        rp = os.path.join(REF_DIR, ref)
        # 右图可能在 acceptance/ 或 _probe/ 里（probe 是临时补拍的同状态图）
        lp = os.path.join(LITE_DIR, lite)
        if not os.path.isfile(lp):
            lp = os.path.join(TMP, lite)
        if not (os.path.isfile(rp) and os.path.isfile(lp)):
            print(f"  跳过 {name}：缺 {os.path.basename(rp) if not os.path.isfile(rp) else lite}")
            continue
        lscale = Image.open(lp).size[0] / 1200
        build(name, rp, lp, lscale, half_w, half_h)
        made += 1
    print(f"done: {made}/{len(PAIRS)} → {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
