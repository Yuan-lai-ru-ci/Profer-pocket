# 鸿蒙卓易通底部色带：定位与修复（2026-11-06）

## 现象

用户在 HarmonyOS NEXT 手机上通过「卓易通」（iSulad 容器里跑的 Android 运行时）使用 Profer 移动版时，
屏幕最底部有一条白色/浅色横带：

- 高度比系统手势条底板略高一点；
- 颜色跟随主题走，但和页面背景**有色差**，边界看得出来；
- 卓易通容器里**所有** App 都有，不只 Profer。

## 定位

不是 Profer 的布局画出来的：`proferBackdrop()` 铺满全屏、输入区用 `navigationBarsPadding()` 让位，
真机上底部是连续的背景渐变。也不是卓易通特意画的一条 bar。

根因在 `androidx.activity` 的 edge-to-edge 实现里。`EdgeToEdgeApi29.setUp()` 的最后一步是：

```text
window.setNavigationBarContrastEnforced(navigationBarStyle.nightMode == MODE_NIGHT_AUTO)
```

`SystemBarStyle.auto(...)` 的 `nightMode` 就是 `MODE_NIGHT_AUTO`，于是**对比度强制被重新打开**。
导航栏同时是透明的（`lightScrim = darkScrim = Color.Transparent`），系统便在这块区域叠一层半透明
scrim 来"保证可读性"——这层 scrim 跟随明暗、比手势条高、和背景有色差，就是那条色带。

容器环境放大了它的可见度（容器窗口自己还有一层底色），但**代码侧的开关就在 App 手里**。

`SystemBarStyle.light()` / `dark()` 的 `nightMode` 分别是 `MODE_NIGHT_NO` / `MODE_NIGHT_YES`，
都不是 AUTO，所以 API 29 不会再把强制打开。调试页 `UiGalleryActivity` 一直用的是 `light()/dark()`，
所以它从来没有这个问题；只有 `MainActivity` 用了 `auto()`。

## 修复（`app/src/main/kotlin/com/profer/pocket/app/MainActivity.kt`）

1. `onCreate` 里 `enableEdgeToEdge()` 之后立刻：
   ```kotlin
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
       window.isNavigationBarContrastEnforced = false
   }
   ```
   保证首帧就没有 scrim。
2. `ApplySystemBarStyle` 把 `SystemBarStyle.auto(...)` 换成 `light()` / `dark()`，
   并在每次主题切换后重新关掉对比度强制（API 29 的 `setUp` 里会按 nightMode 重设）。
3. 同时把 `windowBackground` 改成跟随 **App 主题**而不是系统日夜：容器若把窗口内缩，
   露出来的就是主题色而不是系统色（胶囊壳 `pocket-app/MainActivity.syncSystemBars()` 当年
   为同一个问题把窗口底色固定成了 `#0b0d0e`）。

## 打包结论

**不是构建参数，是源码行为**，debug / internal / release 三个变体自动都带上，一个 APK 同时覆盖
普通 Android 和卓易通，不需要分渠道包：

| 设备/API | 行为 |
|---|---|
| API 26–28 | 只走 `light()/dark()` 的 scrim 分支，对比度强制那段不执行 |
| API 29–34 | 导航栏本来透明、内容铺到底；关掉的只是系统 scrim，正常设备观感不变 |
| API 35+ | 系统强制 edge-to-edge，导航栏由内容决定；该行是空操作 |

唯一对所有设备都生效的副作用是"窗口底色跟 App 主题"，只在首帧和窗口内缩时可见，正常设备无感。

## 验证

- 2026-11-06 真机（HarmonyOS + 卓易通）用户确认：色带消失，亮/暗主题都正常。
  测试包：`dist/profer-pocket-debug-navbar-fix.apk`（`com.profer.pocket.debug`）。
- API 35 AVD：安装后底部像素为 `#F6F3F0`（`proferBackdrop` 渐变末端），内容铺到最后一像素，
  没有独立色带，也没有被系统栏顶上去。

## 约束

- **禁止再用 `SystemBarStyle.auto(...)`** 设置系统栏。它会打开导航栏对比度强制，问题会立刻复发。
  需要跟随主题时，自己算 `darkTheme` 后选 `light()` / `dark()`。
- 若将来又在某个容器里看到色带，先确认是不是"容器自己画的独立 bar"：关掉对比度强制后颜色仍不随
  `windowBackground` 变化，就属于这一类。那时才考虑
  `WindowInsetsControllerCompat.hide(Type.navigationBars())` +
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，且必须真机确认返回手势没被容器吃掉。
