# 安卓 UI 修复与验收（2026-11-01）

> 范围：`android-native/` 的界面层。未改协议、Repository、ViewModel、存储与前台服务。
> 前提：工作树里还带着 2026-10-30 系统化重构的未提交改动；本次在其上做缺陷修复。

## 1. 怎么找到问题

在 API 35 AVD（720×1280 / 320dpi）上用 debug `UiGalleryActivity` 逐页渲染，
按浅色 / 深色 / 2.0× 字号三组截图（`screenshots/2026-11-01-review/`），
再对着 `DESIGN_SYSTEM.md` 与四个新装 Compose Skill 的规则核对。

判定标准只有一条：能不能说出「用户在这个界面会看到什么错」。
纯审美偏好不进清单。

## 2. 修掉的缺陷

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 深色下顶栏标题胶囊、建议胶囊、设置/连接内容卡比画布更暗，像挖洞 | 直接用了 `surfaceContainerLowest`（深色 `#121110` < 背景 `#191715`） | 浮层改用 `proferFloatingSurface()`；内容卡改用 `surfaceContainerLow` |
| 2 | 新会话（有 sessionId、无 usage）顶栏出现一个没有含义的空灰环 | `ChatTopBar` 只判断 `hasSession` | 追加 `contextUsage?.usedTokens != null || contextWindow != null` |
| 3 | Agent 对话与 Chat 对话顶栏不同构：后者裸文本、字重 SemiBold、无胶囊 | `ConversationTopBar` 自拼样式 | 统一为 `titleMedium` + `proferFloatingSurface()` 胶囊，与 Agent 页同内边距 |
| 4 | 键盘弹起后最后一行被输入条盖住 | 滚动副作用拿到的是 IME 动画前的视口高度 | `LaunchedEffect(imeVisible)` 等 260ms 再 `scrollToBottom()` |
| 5 | 聚焦时输入光标压在占位符首字上 | 占位符与文本同起点 | 占位符 `padding(start = 2.dp)` 让出光标宽度 |
| 6 | 连接页设备卡：空地址仍占一行，2× 下变成大空洞；图标夹在两行中间 | `Text("")` 保留行高；Row 垂直居中 | 地址非空才渲染；字号 ≥1.3× 时图标顶部对齐 |
| 7 | 2× 会话列表里项目置顶图标被推到行尾，悬在空白中 | 项目名 `weight(1f)` 占满整行 | 项目名与图标同一 Row，文字 `weight(1f, fill = false)` |
| 8 | 纯工具回合没有正文，却显示「复制回复」，复制出来是空串 | 复制按钮只判断 `streaming` | 条件改为 `message.blocks.isNotEmpty()`（不能用 `plainText()`，它把工具标题算进去了） |

## 3. 验证

| 项 | 结果 |
|---|---|
| `./gradlew test` | 通过，0 失败 |
| `:app:lintDebug` | 0 error / 56 warning（既有依赖与资源提示） |
| `:app:connectedDebugAndroidTest` | 13/13 通过（新增 2 条回归） |
| `:app:assembleDebug` / `:app:assembleRelease` | 通过（release 未签名） |
| 键盘弹起 | `mInputShown=true`，输入条在键盘上方，最后一条消息完整可见 |
| 深色 parity | 对话 / 空态 / 抽屉 / 设置 / 连接 / 审批 全部复拍 |
| 2.0× 字号 | 对话 / 空态 / 抽屉 / 设置 / 连接 / 审批 无截断、无重叠 |

新增回归（`UiSystemRegressionTest`）：

- `tool_only_turn_has_no_copy_action`：纯工具回合有工具卡、无复制按钮。
- `context_usage_ring_needs_data`：无 usage 时环不存在，有 usage 后出现。

## 4. 截图

`screenshots/2026-11-01-review/`（被 `.gitignore` 排除）：

- `after-{page}-light.png` / `after-{page}-dark.png` / `after-{page}-large.png`
- 修复前对照：同名去掉 `after-` 前缀。

## 5. 未做 / 未验

- 真机字体渲染、OLED 低亮度、TalkBack 全路径仍未走。
- 平板/折叠屏双栏仍未做，只保证 760dp 内容宽度上限。
- 真实桌面端联调（工具卡、图片、附件、审批全链路）仍用本地 fixture。
- 连接页「电脑地址」输入框右侧的粘贴圆键压住输入框边框，视觉上像贴纸；
  改动涉及输入框结构，未在本轮动。
- 2× 下顶栏长标题仍单行省略（符合 `DESIGN_SYSTEM` 3.1 的既定取舍）。
