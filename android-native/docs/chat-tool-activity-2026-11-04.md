# Chat 模式工具活动（移动端）

> 日期：2026-11-04
> 范围：`android-native/` 的 Chat（普通对话）页。Agent 会话的工具卡不受影响，见
> [`process-group-2026-11-04.md`](./process-group-2026-11-04.md)。

## 1. 之前的状态

桌面 Chat 在 assistant 消息里渲染工具活动（`ChatToolActivityIndicator` → 逐条 `ChatToolBlock`），
历史消息从 `toolActivities` 字段恢复。移动端只映射正文与 `reasoning`：

- `ChatCodec.message` 不解析 `toolActivities`；
- `PocketRepository.onChatEvent` 不处理 `chat:stream:tool-activity`；
- `ConversationMessage.Assistant` 没有工具字段。

结果：手机上 Chat 对话看不到这一轮调用了什么工具，和桌面不是同一个信息面。

## 2. 协议与合并契约

| 来源 | 形状 | 处理 |
|---|---|---|
| `get_conversation_messages` | 消息对象上的 `toolActivities[]`，每条含 `toolCallId / toolName / type(start\|result) / input / result / isError` | `ChatCodec.mergeToolActivities` 按 `toolCallId` 合并，保留首次出现顺序 |
| `chat_event` | `channel = "chat:stream:tool-activity"`，`payload.activity` 与上同形 | `PocketRepository.onChatEvent` 合并进 `ChatStream.toolActivities` |
| `chat:stream:complete` | `payload.messageId` | 流式工具活动随合成消息一起进消息列表；后续历史水合按 id 换成持久化版本 |

合并规则（`ChatCodec.mergeToolActivity`）：

- 同一 `toolCallId` 只保留一条，位置不变（result 晚到不会把行顶到末尾）；
- `done = 已有 done \|\| 本次 type == result`，`isError` 只增不减；
- `input / result / target` 用非空的新值覆盖旧值。

`target` 由数据层从 `input` 里取 `file_path / path / command / pattern / query / url / prompt / description / app`
的第一个非空值，压平空白后截到 72 字符。UI 不再解析 JSON（app 模块单测没有 `org.json` 实现）。

## 3. UI 行为

- **顺序与桌面一致**：工具行 → 思考折叠卡 → 正文。流式与历史走同一套 `ChatToolRow`。
- **逐条折叠**：`图标 + 标题 · 目标 + 状态`；点开显示「参数 / 结果 / 错误」分区，失败走错误色容器。
  Chat 的工具结果由桌面执行并随事件下发，所以这里可展开——与 Agent 会话「投影尚未下发」不是同一回事。
- **截断**：结果 1200 字符、入参 800 字符，尾部加 `…`；不在移动端解析或重排 JSON。
- 状态：`result` 前为 Running（转圈），成功为 Success，`isError` 为 Error。

## 4. 落点

| 位置 | 改动 |
|---|---|
| `core/model/.../Conversation.kt` | 新增 `ChatToolActivity`；`ChatMessage` / `ChatStream` 增加 `toolActivities` |
| `core/data/.../protocol/ChatCodec.kt` | 解析历史 `toolActivities`、合并 start/result、提取 `target` |
| `core/data/.../PocketRepository.kt` | 处理 `chat:stream:tool-activity`；完成时把活动挂到合成消息 |
| `app/.../UiMapping.kt` | 活动 → `ToolBlock`（状态、标题、目标、截断后的参数/结果）；新增 `ConversationUiState.streamTools` |
| `feature/chat/.../ConversationModels.kt` | `Assistant.tools`、`streamTools` |
| `feature/chat/.../ConversationScreen.kt` | 工具行渲染（历史与流式），新增 `ChatToolRow` |
| `feature/chat/.../ToolKindIcon.kt` | `ToolKind.icon()` 从 `ChatScreen` 抽出，Agent / Chat 共用 |

## 5. 验证（2026-11-04，API 35 AVD 720×1280 / 320dpi）

| 项 | 结果 |
|---|---|
| `./gradlew test`（debug + release） | 通过 |
| `:app:connectedDebugAndroidTest` | 19/19 通过（新增 Chat 工具行折叠/展开回归） |
| 单测 | `ChatCodecTest` 合并顺序与 target；`PocketRepositoryTest` 流式活动合并 + 完成落盘；`UiMappingProjectionTest` 状态/截断/流式上屏 |
| 截图 | `.context`：`chat-tools-light.png` / `chat-tools-dark.png` / `chat-tools-large.png`（2.0×）/ `chat-tools-expanded.png` |

未验证 / 未做：

- 真实桌面端联调（本轮用协议 fixture + gallery 稳定样本）；
- 桌面的 `PreviewOpenButton`（文件预览）与语义化短语（`getToolPhrase`）没有移植：移动端用
  「标题 · 目标」+ 原始参数文本，信息等价但不做文件跳转；
- 工具产出的附件/图片在 Chat 页仍不展示（桌面 Chat 的 attachments 渲染是另一条链路）。
