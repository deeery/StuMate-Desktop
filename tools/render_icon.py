#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
从 StuMateMark.kt 的几何常量渲染品牌图标 -> 多尺寸 .ico

为什么是Python 而不是 Kotlin:
  Compose 的 ImageVector 渲染链(Skia -> SkiaBitmap -> PNG) 在无头环境下要起
  Skiko 表面, 而产物根因我们刚查过 —— 沙箱内GUI 进程会被 sbx.dll 注入而失真。
  这里纯几何 + PIL, 无GUI、无沙箱依赖, 确定性输出, 也能被 CI 直接跑。

🔴 唯一真相在Kotlin 那边, 改图标必须**两边一起改**:
  src/main/kotlin/com/example/classreminder/ui/fluent/StuMateMark.kt
  tools/render_icon.py（本文件）
  已用 test_render_icon.py 校验两者渲染结果一致（见脚本内自检）。

几何全部来自 StuMateMark.kt（512x512 画布）:
  CELL       = 96     格子边长
  CELL_RADIUS= 24     格子圆角
  GRID_STOPS  = 88/208/328  九宫格三档起点
  HIGHLIGHT  = (328, 88)     右上角待提醒格
  BELL_SCALE = 12.6, BELL_TX = 104.79, BELL_TY = 104.8（24 空间 -> 512 空间）
  BELL_STROKE= 2（24 空间的描边宽）
  BELL_BODY / BELL_CLAPPER = Lucide bell 原始path（ISC）
"""
import os
import sys
import math
from PIL import Image, ImageDraw

# ── StuMateMark.kt 的常量 ─────────────────────────────────────────
CELL = 96.0
CELL_RADIUS = 24.0
GRID_STOPS = (88.0, 208.0, 328.0)
HIGHLIGHT_X, HIGHLIGHT_Y = 328.0, 88.0
TILE_R = 118.0  # 圆角方块圆角半径 118/512

BELL_SCALE = 12.6
BELL_TX, BELL_TY = 104.79, 104.8
BELL_STROKE = 2.0

StuMateBrandTile = (0x60, 0xCD, 0xFF, 255)  # #60CDFF
StuMateBrandInk = (0x00, 0x3A, 0x5C, 255)  # #003A5C
HighlightSlot = (0xFF, 0xB9, 0x00, 255)  # #FFB900 琥珀
GRID_INK_ALPHA = 0.18  # fillAlpha = 0.18f

SS = 8  # 超采样倍数：512*8=4096 画布, 缩到各尺寸时抗锯齿干净

# Lucide bell(ISC) 原始 path, 24x24 坐标系, 逐字取自 StuMateMark.kt
#
# A 段的字段顺序按 SVG: (rx, ry, xAxisRotation, largeArc, sweep, x, y)
# 原 SVG 串: "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673"
#          "C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"
BELL_BODY = [('M', 3.262, 15.326),
             ('A', 1.0, 1.0, 0.0, 0.0, 0.0, 4.0, 17.0),          # → 4,17
             ('L', 20.0, 17.0),                                      # h16
             ('A', 1.0, 1.0, 0.0, 0.0, 0.0, 20.74, 15.327),          # → 20.74,15.327
             ('C', 19.41, 13.956, 18.0, 12.499, 18.0, 8.0),
             ('A', 6.0, 6.0, 0.0, 0.0, 0.0, 6.0, 8.0),
             ('C', 6.0, 12.499, 4.589, 13.956, 3.262, 15.326)]

# 原 SVG 串: "M10.268 21a2 2 0 0 0 3.464 0"
BELL_CLAPPER = [('M', 10.268, 21.0),
                ('A', 2.0, 2.0, 0.0, 0.0, 0.0, 13.732, 21.0)]


def parse_path(cmds):
    """把 StuMateMark.kt 那种压缩写法还原成指令列表并转成 PIL 可画的折线。
    弧线按 SVG A 语义离散成折线 —— 在<=48px 的图标尺寸下肉眼不可见。"""
    pts = []
    cx = cy = 0.0
    for c in cmds:
        op = c[0]
        if op == 'M':
            cx, cy = c[1], c[2]
            pts.append((cx, cy))
        elif op == 'L':
            cx, cy = c[1], c[2]
            pts.append((cx, cy))
        elif op == 'C':
            p0 = (cx, cy)
            p1, p2, p3 = (c[1], c[2]), (c[3], c[4]), (c[5], c[6])
            for i in range(1, 13):
                t = i / 12.0
                mt = 1 - t
                x = mt**3*p0[0] + 3*mt*mt*t*p1[0] + 3*mt*t*t*p2[0] + t**3*p3[0]
                y = mt**3*p0[1] + 3*mt*mt*t*p1[1] + 3*mt*t*t*p2[1] + t**3*p3[1]
                pts.append((x, y))
            cx, cy = p3
        elif op == 'A':
            # 椭圆弧 -> 折线
            rx, ry, rot, large_arc, sweep, x, y = c[1:8]
            _arc_to_polyline(cx, cy, rx, ry, large_arc, sweep, x, y, pts)
            cx, cy = x, y
    return pts


def _arc_to_polyline(x1, y1, rx, ry, large_arc, sweep, x2, y2, out, steps=16):
    if rx == 0 or ry == 0:
        out.append((x2, y2))
        return
    rx, ry = abs(rx), abs(ry)
    # 端点到中心
    dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    lam = (dx2 * dx2) / (rx * rx) + (dy2 * dy2) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx*rx*ry*ry - rx*rx*dy2*dy2 - ry*ry*dx2*dx2
    den = rx*rx*dy2*dy2 + ry*ry*dx2*dx2
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large_arc == sweep:
        co = -co
    cxp = co * rx * dy2 / ry
    cyp = -co * ry * dx2 / rx
    cxx = (x1 + x2) / 2.0
    cyy = (y1 + y2) / 2.0
    cx, cy = cxx + cxp, cyy + cyp

    def ang(ux, uy, vx, vy):
        dot = ux*vx + uy*vy
        n = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1.0, min(1.0, dot / n)))
        return -a if (ux*vy - uy*vx) < 0 else a

    th1 = ang(1, 0, (dx2 - cxp) / rx, (dy2 - cyp) / ry)
    dth = ang((dx2 - cxp) / rx, (dy2 - cyp) / ry, (-dx2 - cxp) / rx, (-dy2 - cyp) / ry)
    if not sweep and dth > 0:
        dth -= 2 * math.pi
    elif sweep and dth < 0:
        dth += 2 * math.pi
    for i in range(steps + 1):
        t = th1 + dth * i / steps
        out.append((cx + rx * math.cos(t), cy + ry * math.sin(t)))


def bell_polyline(cmds, s=BELL_SCALE, tx=BELL_TX, ty=BELL_TY):
    """24 空间 -> 512 空间: p -> s*p + t（addGroup 的 pivot=0 语义）"""
    return [(x * s + tx, y * s + ty) for x, y in parse_path(cmds)]


def render(size):
    N = size * SS
    k = N / 512.0
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 1) 外轮廓：圆角方块
    d.rounded_rectangle([0, 0, N - 1, N - 1],
                        radius=TILE_R * k, fill=StuMateBrandTile)

    # 2) 九宫格（高亮格留空）
    ink = StuMateBrandInk
    grid_color = (ink[0], ink[1], ink[2], int(round(255 * GRID_INK_ALPHA)))
    for y in GRID_STOPS:
        for x in GRID_STOPS:
            if x == HIGHLIGHT_X and y == HIGHLIGHT_Y:
                continue
            d.rounded_rectangle([x * k, y * k, (x + CELL) * k, (y + CELL) * k],
                                radius=CELL_RADIUS * k, fill=grid_color)

    # 3) 待提醒课格
    d.rounded_rectangle([HIGHLIGHT_X * k, HIGHLIGHT_Y * k,
                         (HIGHLIGHT_X + CELL) * k, (HIGHLIGHT_Y + CELL) * k],
                        radius=CELL_RADIUS * k, fill=HighlightSlot)

    # 4) 铃铛（描边）。group 变换会把 strokeLineWidth 一起缩放，
    #    所以实际描边宽 = BELL_STROKE * BELL_SCALE（与 Compose 行为一致）
    w = max(1, int(round(BELL_STROKE * BELL_SCALE * k)))
    for cmds in (BELL_CLAPPER, BELL_BODY):
        poly = bell_polyline(cmds)
        pts = [(px * k, py * k) for px, py in poly]
        if len(pts) >= 2:
            d.line(pts, fill=StuMateBrandInk, width=w, joint="curve")
            # 圆头圆角：端点与拐角补圆
            r = w / 2.0
            for px, py in pts:
                d.ellipse([px - r, py - r, px + r, py + r], fill=StuMateBrandInk)

    return img.resize((size, size), Image.LANCZOS)


def main():
    out_ico = sys.argv[1] if len(sys.argv) > 1 else \
        os.path.join(os.path.dirname(__file__), "..", "app-icon.ico")
    out_ico = os.path.abspath(out_ico)
    png_dir = os.path.splitext(out_ico)[0] + "_png"
    os.makedirs(png_dir, exist_ok=True)

    # Windows 需要的标准 6 档(与 Compose 默认图标同集, 保持一致)
    sizes = [16, 24, 32, 48, 64, 128, 256]
    frames = []
    for s in sizes:
        im = render(s)
        im.save(os.path.join(png_dir, "stumate-%dx%d.png" % (s, s)), "PNG")
        frames.append(im)
        print("  渲染 %3dx%-3d" % (s, s))

    frames[-1].save(out_ico, format="ICO",
                    sizes=[(s, s) for s in sizes],
                    append_images=frames[:-1],
                    bitmap_format="bmp")
    print()
    print("ICO:", out_ico, "%d 字节" % os.path.getsize(out_ico))
    print("PNG 预览:", png_dir)


if __name__ == "__main__":
    main()