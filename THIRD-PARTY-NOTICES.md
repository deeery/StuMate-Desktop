# 第三方许可声明 / Third-Party Notices

本文件汇总本项目源码中引用的第三方图形资源及其许可要求。
本文件**不是**本项目自身的许可声明。

---

## Lucide Icons — `bell`

- **用途**：`src/main/kotlin/com/example/classreminder/ui/fluent/StuMateMark.kt` 中品牌标识的铃铛主体几何
- **上游**：<https://lucide.dev/icons/bell> · <https://github.com/lucide-icons/lucide>
- **许可**：ISC
- **说明**：`bell` 不在 Lucide 的 Feather(MIT) 衍生清单内，因此只需满足下列 ISC 条款，无 MIT 附加义务。

```
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any purpose
with or without fee is hereby granted, provided that the above copyright notice
and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
THIS SOFTWARE.
```

ISC 要求「the above copyright notice and this permission notice appear in all
copies」，因此同一段声明也以 KDoc 形式内嵌在 `StuMateMark.kt` 里。

### 相对上游的改动

仅整体缩放与落位，**形状与描边参数未改**：

- 保留原图 `fill="none"` + `stroke-width="2"` + `stroke-linecap="round"` + `stroke-linejoin="round"` 的描边样式。
- 用 `ImageVector.Builder.addGroup(scaleX = 12.6, scaleY = 12.6, translationX = 104.79, translationY = 104.8)`
  把 24×24 坐标系映射到本图标的 512×512 画布。
