# 周课表 UI 优化 · v1.6.4 验收截图

场景：`./gradlew uiPreview -Ppreview=week`（预览窗口 1200×800dp，2 倍缩放 → 2400×1600 px）
种子数据：`tools/seed_demo.py`（12 门课，含长课名、跨节、今日列）

| 文件 | 证明什么 |
| --- | --- |
| `00-before.png` | 改造前基线。网格线与斑马纹双双不可见，表头只有「周一」没有日期，课程块色条是固定 26dp 短杠 |
| `01-dark.png` | 深色主题最终态。四级层次成立：卡片面 → 斑马纹 → 网格线 → 课程块 |
| `02-light.png` | 浅色主题最终态。同一套 token 在亮色下不塌陷（`gridLine` / `gridZebra` 双主题独立取值） |
| `03-min-width.png` | 最小宽度 900dp。窄列下孤行判定生效，长课名单行截断、地点完整、无文字挤压 |
| `04-crop-block.png` | 课程块 4x 特写。课名 / 地点 / 贴底时间区间三段式，色条贯穿块高 |
| `05-crop-long-title.png` | 「马克思主义基本原理」特写。判定为孤行 → 单行截断为「马克思主义基本…」，不再出现只剩一个「理」的第二行 |
| `06-crop-today.png` | 今日列左侧强调条特写。2dp 竖线贯穿整列，消除「课程块之间蓝底断层」 |
| `07-crop-min-block.png` | 最小宽度下的课程块特写。高度不足 76dp 时自动降级为「课名 + 地点」，不硬塞时间行 |

## 复现命令

```bash
# 深色
python tools/shoot_scenarios.py design-preview/out week=week.png

# 浅色
STUMATE_THEME=light python tools/shoot_scenarios.py design-preview/out week=week-light.png
```

> 预览窗口 `minimumSize` 写死 900×600dp（`UiPreview.MIN_W_DP` / `MIN_H_DP`），
> 外部 `SetWindowPos` 改不动它 —— 这与生产窗口的最小尺寸一致，
> 所以「比 900dp 更窄」在真实使用中不会出现，无需覆盖。
