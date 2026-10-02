"""从 check_overlay.py 产出的整屏截图里裁出卡片特写（2x 放大），供验收页使用。

用法：python crop_card.py
输出：
  design-preview/overlay-toast-card-{dark,light}.png   卡片特写 2x
  design-preview/overlay-toast-{dark,light}.png        整屏（证明只占正上方）
"""
import os
import shutil

from PIL import Image

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(PROJECT, "build", "preview")
DST = os.path.join(PROJECT, "design-preview")

# 卡片窗口矩形，与 check_overlay.py 打印的 rect 一致（2800x1224 工作区、density 2.0）
L, T, R, B = 1170, 24, 1630, 200
PAD = 26


def main():
    os.makedirs(DST, exist_ok=True)
    for th in ("dark", "light"):
        src = os.path.join(SRC, f"overlay-toast-verify-{th}.png")
        im = Image.open(src).convert("RGB")
        box = (
            max(0, L - PAD), max(0, T - PAD),
            min(im.width, R + PAD), min(im.height, B + PAD),
        )
        crop = im.crop(box)
        crop = crop.resize((crop.width * 2, crop.height * 2), Image.LANCZOS)
        out = os.path.join(DST, f"overlay-toast-card-{th}.png")
        crop.save(out)
        shutil.copyfile(src, os.path.join(DST, f"overlay-toast-{th}.png"))
        print(f"{th}: full={im.size}  crop={box} -> {crop.size}  {out}")


if __name__ == "__main__":
    main()
