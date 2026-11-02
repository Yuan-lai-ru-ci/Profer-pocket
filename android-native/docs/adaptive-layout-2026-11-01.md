# 平板与折叠屏适配（2026-11-01）

> 范围：`android-native/` 的布局层。新增 `androidx.window` 依赖（仅用于读折叠屏铰链）。
> 工作树仍带着 10-30 重构与同日 UI 修复的未提交改动。

## 1. 断点与分栏

| 宽度 | 档位 | 布局 |
|---|---|---|
| < 600dp | Compact | 单栏 + 抽屉（原样） |
| 600–840dp | Medium | 双栏，列表 320dp（上限屏宽 46%） |
| ≥ 840dp | Expanded | 双栏，列表 360dp（上限屏宽 46%） |

- 320dp 是「品牌 + 模式胶囊 + 搜索 + 新建」一行放得下的下限；740dp 折叠展开正好落在 320dp，
  600dp 分屏退回 276dp，内容仍有 324dp。
- 内容区（顶栏、消息、输入条）限制 760dp 居中，背景铺满内容区。
- 双栏时：隐藏顶栏菜单键、抽屉状态归零、返回手势不再拦截；列表与内容之间一条 hairline。

实现位置：

- `core/designsystem/.../adaptive/ProferWindowSize.kt`：断点、列表宽度、`LocalProferWindowSize`。
- `core/designsystem/.../adaptive/FoldingFeature.kt`：`rememberProferHinge()`，把 `FoldingFeature`
  转成 `ProferHinge(vertical, isSeparating, size, start)`。
- `feature/chat/.../PocketDrawerLayout.kt`：`BoxWithConstraints` 测量并注入窗口尺寸；
  Medium/Expanded 走 `TwoPaneLayout`；`resolveTwoPaneSpec()` 是纯函数，单独可测。

## 2. 折叠屏

- **垂直铰链**（书本式/双屏）：分栏线对齐铰链左侧，铰链宽度留白，正文不跨折痕。
  铰链在屏宽 25%–60% 之外时忽略，退回默认分栏。
- **水平铰链**（帐篷/桌面模式）：可用高度只剩一半，退回单栏，整块内容停到铰链下方
  （`hingeTopPadding = start + size`）。
- 柔性屏折痕（`isSeparating = false`）不参与分栏，只保证内容不压在折痕上。

## 3. 验证

| 项 | 结果 |
|---|---|
| `./gradlew test` | 通过（新增 `TwoPaneSpecTest` 7 项） |
| `:app:lintDebug` | 0 error |
| `:app:connectedDebugAndroidTest` | 15/15（新增双栏/单栏各 1 条） |
| `:app:assembleDebug` / `:app:assembleRelease` | 通过 |
| 800dp 平板竖屏 | 双栏、列表 320dp、内容 760dp 居中 |
| 1280dp 平板横屏 | 双栏、列表 360dp、标题胶囊按内容收窄、⋮ 贴右 |
| 740dp 折叠展开 | 双栏、品牌完整、浅色/深色/2× 均无截断 |
| 360dp 手机 | 单栏回归通过，菜单键仍在 |

截图：`screenshots/2026-11-01-review/` 下的 `tablet-*`、`fold-*`、`phone-*-regression.png`。

## 4. 未验 / 取舍

- 没有真折叠屏设备，铰链只做了纯函数单测与模拟器尺寸验证；真实铰链坐标、折痕观感未验。
- 设置、连接、扫码在平板上仍是全屏路由（会盖住列表），只做了 760dp 居中，没做「右栏内嵌」。
  要保留列表需要改导航结构，留到下一轮。
- 折叠屏折叠态（< 600dp）走单栏，未单独截图。
