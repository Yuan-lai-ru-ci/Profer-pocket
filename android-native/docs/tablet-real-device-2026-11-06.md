# 平板真机验收（iPA2453 / Android 16）与三处修复

> 日期：2026-11-06
> 范围：`android-native/` 的双栏侧栏头部、会话行大字号对齐、连接页设备卡文案。
> 工作树仍带着 10-30 重构、11-05 流式/回合合并的未提交改动；本文只记录本轮新增的三处。

## 1. 设备事实

| 项 | 值 |
|---|---|
| 设备 | `iPA2453`（vivo，`DPD2345E`），Android 16 / API 36，`401E5M01HG00000` |
| 分辨率 | 1968×2800 @ 400dpi → `sw787dp` |
| 横屏窗口 | 1120×787dp（`w1120dp h787dp`，`ROTATION_90`） |
| 竖屏窗口 | 787×1120dp |
| 分栏 | 横屏 `Expanded` → 侧栏 314dp、对话区 798dp；竖屏 `Medium` → 侧栏 220dp、对话区 559dp |
| 系统 | 字体 1.0×、手势导航、平板默认横屏使用 |

与 2026-11-01 的模拟器验收（800dp 竖屏 / 1280dp 横屏）相比，这块真机的落点是
**314dp 侧栏**——正好卡在「平板完整档」和「窄侧栏」之间，原规则没有覆盖。

## 2. 发现与修复

### 2.1 侧栏品牌字被压成「Pr…」（真实缺陷）

横屏 1120dp 时侧栏 314dp，头部内容宽 282dp，而「标记 24 + Profer 62 + ⇄Agent 82 +
搜索 48 + 新建 48 + 间距 24」需要 288dp。品牌字被 `weight(1f)` 压到 50dp，实际渲染成「Pr…」；
Chat 模式（胶囊更窄）是「Pro…」。原先的收窄规则只认 `isShort`（高 < 480dp）和侧栏 < 260dp，
这块平板高 787dp，两条都不命中。

修法（`SessionListHeader`）：新增 `tightPane = isTwoPane && listPaneWidth < 340.dp`，
这一档把模式胶囊换成 `compact`（去掉 ⇄ 装饰图标，语义仍在 `contentDescription` 里），
把 26dp 还给品牌字。1.3× 时胶囊本来就落到第二行，不受影响；< 260dp 仍走原来的「连标记一起收」。

### 2.2 2.0× 大字号下置顶图标掉到第二行旁边（真实缺陷）

`fontScale ≥ 1.5` 时标题最多三行、时间换到下一行；置顶/定时图标仍用 `CenterVertically`，
两行标题时图标落在两行之间，看起来像挂在第二行开头。

修法：用 `onTextLayout` 取行数，换行时把图标行的对齐改成 `Top`，并按
`(bodyMedium.lineHeight - 16dp) / 2` 下移，让图标中线落在首行行盒中线上。
单行时保持原来的居中，不改动常规字号下的观感。

### 2.3 连接页设备卡显示「局域网 · 局域网」（文案缺陷）

`scopeLabel` 与 `trustLabel` 在「局域网 + 无信任固定」时都是「局域网」，拼起来是同词重复。
修法：两个标签相同时只显示一个；不同时仍是「公网 · 手动 · 系统证书」这种两段式。

## 3. 验证

截图在 `screenshots/2026-11-06-tablet/`（该目录被 `.gitignore` 排除，不入库）。

| 文件 | 状态 |
|---|---|
| `before-brand-clipped-1120x787.png` | 修复前：侧栏头部「Pr…」 |
| `after-chat-1120x787-light.png` | 修复后：品牌字完整、胶囊去图标 |
| `after-welcome-1120x787-dark.png` | 深色同几何 |
| `after-chat-1120x787-font130.png` | 1.3×：胶囊落第二行，品牌字完整 |
| `after-chat-1120x787-font200.png` | 2.0×：图标跟首行对齐 |
| `after-chat-787x1120-light.png` | 竖屏 787dp：窄侧栏档（无品牌字） |
| `settings-1120x787-light.png` | 设置：左分类 + 右内容 |
| `connection-1120x787-light.png` | 连接：设备卡文案去重 |
| `composer-ime-dark.png` | 键盘弹起：输入条在键盘上方，无遮挡 |
| `fresh-install-disconnected.png` | 重装后未连接态 |

实际跑过的路径：

| 项 | 结果 |
|---|---|
| `./gradlew :app:assembleDebug` | 通过 |
| `./gradlew test` | 通过（全部模块） |
| `adb install` + 冷启动 | 通过（首次 1.2s；后续安装见 §4.2） |
| 横屏浅色/深色、竖屏浅色 | 通过 |
| 1.3×、2.0× | 头部/会话行无截断；2.0× 图标对齐已修 |
| 键盘弹起 | `mInputShown=true`，输入条与发送键完整可见 |
| 列表栏收起/展开 | 折叠后内容居中，图标切换为「展开会话列表」 |
| Agent / Chat 模式切换 | 两种模式头部同一套收窄规则 |
| 设置、连接 | 分栏布局与文案正常 |

## 4. 未验 / 遗留

### 4.1 仪表测试 3 项在本机失败（与本文改动无关）

`./gradlew :app:connectedDebugAndroidTest` 在本机跑完 23 项，3 项失败，都是
`assertIsDisplayed` 找不到节点：`thinking_without_tools_still_folds_into_process_group`、
`multi_step_turn_ends_with_answer_outside_group`、
`new_message_does_not_steal_position_in_240_message_history`。

这三项渲染的是 `ChatScreen` / `ConversationScreen`（测试里 `drawerContent = { _ -> }`），
不经过本文改动的 `SessionListHeader` / `SessionRow` / `ConnectionScreen`；失败更像是
11-05 回合折叠那次未提交改造与测试断言没对齐：
`thinking_without_tools_still_folds_into_process_group` 要求「只有思考」折成
「执行过程：1 段思考」，同文件另一条 `thinking_only_turn_is_promoted_and_visible`
又要求这种情况不被折掉——两条断言互相矛盾。归属需要单独一轮，没在本轮改。

### 4.2 安装会超时（设备侧）

这块平板 `pm install` 的提交阶段会卡在 90% 很久：`adb install` 300s 无返回，
ddmlib 直接抛 `ShellCommandUnresponsiveException`（`installCommit`），
第二轮 `connectedDebugAndroidTest` 因此 0 项执行。APK push + `pm install` 最终能装上，
但需要 5–10 分钟。要在真机上跑仪表测试，建议先用 `adb push` + `pm install` 预热，
或换 USB 3 口/无线调试。

### 4.3 副作用：debug 包的本地数据被清

仪表测试失败后 UTP 卸载了 `com.profer.pocket.debug`，重新安装后 App 内的配对信息
（连接 token、主题、字号偏好）随之丢失，需要重新扫码/手动输入 Token 才能连回电脑。
这是本轮操作造成的，不是代码问题。

### 4.4 手机宽度回归没在本机跑

360dp 紧凑档的断言由 `UiSystemRegressionTest` 的紧凑用例覆盖（本机 23 项里其余 20 项通过）；
真机只有平板，没有用 `wm size` 改窗口去模拟手机，避免动用户的显示设置。
