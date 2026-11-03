# 对话运行的系统级实时活动（2026-11-01）

> 范围：`app` 模块的通知层与前台服务。未改协议与数据层。
> 用户确认形态为「系统级实时活动」，内容为「对话运行」。

## 1. 实现

- 新通道 `profer_live`（`IMPORTANCE_LOW`、无声音、无徽章），只承载一条常驻通知 `LIVE_ID = 1002`。
- `PocketNotifications.postLiveUpdate(context, sessionId, title, text, shortText)`：
  - API 36+ 用原生 `Notification.Builder`，`setOngoing(true)` + `CATEGORY_PROGRESS` + `BigTextStyle`，
    反射调用 `setShortCriticalText(String)`（状态栏胶囊文案）。
  - 低版本退回 `NotificationCompat` 静音常驻通知，行为不变。
  - 点击通知带 `EXTRA_SESSION_ID` 回到对应会话。
- `PocketConnectionService` 观察 `repository.chats`，取第一个 `running` 会话：
  - 标题 `Profer · <会话标题>`，正文 `正在回复…` / `正在执行 <工具>`；
  - 胶囊文案 `回复中` / `执行中`（3 个汉字，远低于系统 7 字符上限）；
  - 用 `distinctUntilChanged` 只按「会话 + 当前工具」更新，流式正文每一帧不重建通知；
  - 会话结束或断线时 `cancelLiveUpdate()`。
- Manifest 声明 `POST_PROMOTED_NOTIFICATIONS`（QPR1 文档要求；API 36.0 的 `Manifest.permission`
  还没有这个常量，声明当前只是被记录为 requested）。
- debug 入口：`am start ... --ez debug_live true` / `--ez debug_live_cancel true`，
  不连电脑也能验证通知构建与降级。

## 2. 验证

| 项 | 结果 |
|---|---|
| `setShortCriticalText` 方法签名 | 用 `platforms/android-36/android.jar` 的 `javap` 核对：`setShortCriticalText(java.lang.String)` 存在 |
| `setRequestPromotedOngoing` | **不存在**，已从实现里删掉；提升由系统按 `hasPromotableCharacteristics()` 自动判断 |
| API 35 模拟器 | 通知 id=1002、channel=profer_live、`ONGOING_EVENT`、Silent 分组，标题/正文正确 |
| API 36.0 模拟器 | 通知正常显示，`BigTextStyle` 可展开；`canPostPromotedNotifications()` 返回 **false** |
| 取消 | `debug_live_cancel` 后 `dumpsys notification` 中 id=1002 消失 |
| 回归 | `test` 通过、lint 0 error、设备 15/15（API 36）、debug+release 构建通过 |

截图：`screenshots/2026-11-01-review/live-notification.png`（API 35）、
`live-api36-notification.png`（API 36.0）、`live-api36-statusbar.png`（无 chip）。

## 3. 平台限制（重要）

状态栏胶囊（status chip）要 **Android 16 QPR1 / API 36.1** 才有。在 Android 16.0（API 36）
与全部模拟器上，`canPostPromotedNotifications()` 恒为 false，通知只能在通知栏原地更新、不提升。

也就是说：

- 现在交付的是「通知层已经按 Live Update 规范准备好」：ongoing + progress 类别 + BigTextStyle +
  shortCriticalText + 静音通道 + 点击回会话；
- 在 36.1+ 设备上系统会自动把它提升成状态栏胶囊，不需要再改代码；
- 在 36.0 及以下，表现为一条静默常驻通知（已实机验证）。

## 4. 未验

- 没有 API 36.1（QPR1）设备/镜像，胶囊的实际视觉与放置位置未验。
- 真实桌面端联调下 `running` 的端到端触发（本轮用 debug 入口验证通知构建，`repository.chats`
  的观察逻辑未接真实会话跑过）。
- 前台（app 正在使用）时是否仍显示胶囊：按系统策略会显示；未在 36.1 上确认观感是否重复。

## 5. 2026-11-02 修复：补上提升请求（ColorOS 16 流体云）

上一版把 `setRequestPromotedOngoing` 从实现里删掉了（当时 `platforms/android-36/android.jar`
里确实没有这个方法），结果通知从未「请求提升」，只能停在普通常驻通知。

Android 16 规范里提升请求是必需项，ColorOS 16 的流体云按同一份 Live Updates 规范判定，
所以这一步缺失在 OPPO 上同样不会出现胶囊。

修复：

- `buildLiveUpdate` 的 API 36+ 分支加
  `.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })`。
  字符串键对应 `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`；该常量在 compileSdk 35 与
  android-36.0 的 android.jar 里都没有（36.1 才进公开 API），因此用字面量并注释来源。
- `postLiveUpdate` 后打一条 `ProferLive` 日志：`canPostPromotedNotifications` +
  `FLAG_PROMOTED_ONGOING`（0x40000，同样是反射/字面量读取），供真机排查。
- 依赖 `androidx.core` 1.15.0 → 1.16.0。`NotificationCompat.Builder.setRequestPromotedOngoing()`
  实际在 **1.17.0** 才加入（1.16.0 的 AAR 里没有），所以没有走 compat 路线，避免为它把
  compileSdk 顶到 36 再连带升 AGP。

验证：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`
均通过（lint 0 error）。真机胶囊效果待 ColorOS 16 设备实测。

## 6. 2026-11-02 真机实测（OPPO Reno 16 / ColorOS 17）

设备：PMM110，`PMM110_17.0.0.101(CN01)`，Android 17 / API 37（不是 ColorOS 16）。

实测发现，除了代码缺提升请求，ColorOS 还多一道开关：

- 系统里存在每应用开关 **「流体云显示实时活动」**（设置页 action
  `android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS` + `APP_PACKAGE` extra），
  **默认关闭**。关着时 `canPostPromotedNotifications()` 返回 false，通知照发但不提升。
- 打开该开关后：`canPostPromotedNotifications=true`，`dumpsys notification` 里
  flags 变为 `ONGOING_EVENT|ONLY_ALERT_ONCE|PROMOTED_ONGOING`，状态栏出现胶囊，文案「执行中」。
- `android.requestPromotedOngoing=true` 在 dumpsys 里可见，证明 extra 已送达系统。

结论：`targetSdk 35` 不阻碍提升（ColorOS 17 上实测可行），不需要为它升 targetSdk。

配套改动：设置页「通知」区块新增一行 **流体云显示实时活动**，显示「已开启 / 去开启」，
点击直接跳系统开关页，返回时重新读取状态（`feature/settings/LiveUpdateEntry.kt` +
`LifecycleResumeEffect`）。API < 36 不展示该行。

## 7. App 内引导弹窗（同日晚）

`POST_PROMOTED_NOTIFICATIONS` 是 `normal|appop` 权限，没有运行时授权弹窗；官方只给设置页跳转。
因此做了一层应用内引导：

- 启动时若 `canPostPromotedNotifications()` 明确返回 false（且通知开关开着、从未提示过），
  弹一次对话框：「开启流体云显示实时活动」→「去开启」直达系统开关页，「稍后」关闭。
- 外部点击 / 返回键关闭**不写已提示标记**：首次启动常被系统通知权限弹窗挤掉，
  写标记会让用户永远看不到内容（实机复现过）。
- 多用户 / 分身下拉不起系统设置页时用 Toast 兜底给出手动路径。

实测（分身用户全新安装，canPostPromotedNotifications=false）：弹窗正常出现，文案与按钮正确。

### API 不可靠的一处

用户把开关关闭后，`dumpsys appops` 已记为 `POST_PROMOTED_NOTIFICATIONS: ignore`，
但 `canPostPromotedNotifications()` 仍返回 true，通知也仍带 `PROMOTED_ONGOING`、胶囊照常显示。
也就是说这个开关在 ColorOS 17 上「关过一次之后」不会撤销已授权的提升能力。
所以引导逻辑用「明确 false 才提示」，不对 true 做反向推断。

## 8. 追问时的胶囊状态（同日晚）

问题：追问（权限确认 / 提问 / 计划审批）时 `running` 仍是 true，
旧逻辑只按 `running` 挑会话，胶囊会一直显示「回复中」——看起来像还在干活。

改法（`app/LiveState.kt` + `selectLiveState`）：

- 优先级：有 `pendingInteraction` 的会话 > `running` 的会话；
- 追问映射：Permission → 短文案「待确认」/ 正文「等待你确认权限」，
  Question → 「待回答」/「等待你的回答」，PlanApproval → 「待审批」/「等待你审批计划」；
- 多会话时正文补「，另有 N 个会话」（短文案仍只放当前会话状态，不超 96dp 宽度）；
- 点击胶囊仍带 `EXTRA_SESSION_ID`，直达被追问的会话。

验证：`LiveStateTest` 8 个用例（空集合 / 运行中 / 带工具 / 追问优先 / 三种追问映射 /
空标题 / 其它会话去重计数 / 全部结束）；真机实测胶囊显示「待回答」三字不截断。
同时观察到真实会话（非 debug 入口）也能触发：标题「Profer · OPPO灵动岛适配」、
正文「正在执行 Bash」，说明端到端链路已通。

## 9. 跨品牌规范化（2026-11-02）

把「厂商差异」从散落的 if 收成一层能力模型（`core/model/LiveUpdate.kt`）：

```
LiveUpdateCapabilities.resolve(sdkInt, manufacturer, canPostPromoted)
  -> LiveUpdateCapability(supported, requiresUserSwitch, surface)
```

- `supported`：API 36+ 才有状态栏胶囊，低版本一律 false（入口不展示，通知照发）；
- `requiresUserSwitch`：只有系统明确回答 false 才为 true；探测失败（null）不催用户；
- `surface`：厂商叫法，只用于文案，不影响行为。

对外文案统一用「状态栏实时活动」，厂商名只放副标题（「本机叫『流体云』」），
避免暗示已打通厂商私有通道。

### 品牌矩阵

| 品牌 | 形态 | 接入方式 | 无设备可做 | 状态 |
|---|---|---|---|---|
| OPPO / 一加 / realme | 流体云 | 原生 API（ColorOS 16+ 完整接入） | ✅ | **真机已验证** |
| 三星 One UI 8 | Now Bar | 原生 API（官方确认对第三方开放） | ✅ | 代码通用，未真机 |
| Google Pixel / AOSP | Live Updates | 原生 API | ✅ | **API 36 模拟器已验证降级路径** |
| 小米 HyperOS 3 | 超级岛 | 原生 API 部分支持，第三方范围未明确 | ⚠️ | 代码通用，待真机 |
| vivo / iQOO | 原子岛 | 需厂商「原子通知接入权限」，走厂商通道 | ❌ | 未接 |
| 荣耀 | 灵动胶囊 | 需厂商合作接入 | ❌ | 未接 |
| 华为 HarmonyOS NEXT | — | 非 Android，APK 不可运行 | ❌ | 不适用 |

厂商私有通道（vivo / 荣耀 / 小米深度样式）需要申请与真机联调，没设备没权限就无法验证，
不写「假适配」：这些品牌走原生 API 能提升就用，不能就退到静音常驻通知。

### 无设备验证手段

- `LiveUpdateCapabilityTest`（core:model）7 个用例：版本门槛、需要开关、探测失败不打扰、
  10 个厂商别名映射、大小写空格、文案表。
- API 36 模拟器实测：`surface=LiveUpdate needSwitch=true`，弹窗与设置页文案正确，
  通知正常发出（`ONGOING_EVENT|ONLY_ALERT_ONCE`，无 `PROMOTED_ONGOING`），不崩。
- 未验：API 35 模拟器（入口隐藏逻辑由单测覆盖）、小米 / 三星 / vivo / 荣耀真机。

## 10. 视觉与交互确认（2026-11-02）

- **胶囊点击会展开成卡片**（ColorOS 17 真机确认）。之前写「点不动」是错的：
  `adb shell input tap` 对 SystemUI 状态栏区域无效（点状态栏时间也没反应），不能用来验证胶囊交互。
  通知栏卡片的点击已验证：能正常拉起 App（焦点切到 MainActivity）。
- 胶囊/卡片的**外框、圆角、字体、背景全部由系统渲染**，第三方只能控制：
  小图标、3 字短文案、展开卡片标题/正文、`ProgressStyle`、`setColor`。
  `setColor(品牌陶土色)` 实测被 ColorOS 忽略，已保留（其它厂商可能生效）。
- 图标从「三个色块」改为**圆角方框 + 镂空 P**（`ic_stat_profer.xml`）：
  状态栏尺寸下三个色块糊成一团，P 字标可辨识。
- 完成态：胶囊停 10 秒，卡片标题「Profer · 任务完成」/「Profer · 任务结束」，
  正文带会话名。完成态**不能程序化展开**（系统交互），只能靠停留时长 + 点击。
