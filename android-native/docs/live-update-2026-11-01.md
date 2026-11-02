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
