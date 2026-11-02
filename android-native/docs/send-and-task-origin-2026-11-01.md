# 发送逻辑与任务来源适配（2026-11-01）

对应桌面工作树：`真实任务来源与会话派工`（`docs/plans/2026-11-01-task-origins-and-session-dispatch-delivery.md`）与「运行中发送直接插入」。本文只记录移动端消费了什么，以及明确没做什么。

## 1. 协议面

| 字段 | 位置 | 移动端用法 |
|---|---|---|
| `_provenance.author.type` | `sdk_message` / `get_sdk_messages` | 区分用户输入与框架/Agent/外部触发；不是用户就不画用户气泡 |
| `_provenance.source.{type,id}` | 同上 | 换成可读来源（来源会话标题 / 定时任务 / 上级任务 / 外部请求） |
| `subtype: task_trigger` | system 消息 | 单独渲染来源卡；正文到达后由正文卡接管，同一件事不画两张卡 |
| `_goalIteration` | user 消息 | 内部注入的 Goal prompt 不当作普通用户消息，只标「长时任务第 N 轮」 |
| `sourceAutomationId` | 会话列表 / 投影 | 会话行标题前显示钟表标记（与桌面侧栏同口径） |
| `sourceSessionId` / `delegationRelation` / `delegationPlacement` | 会话列表 / 投影 | 解析并保留；`independent` 不挂到母会话下 |
| `interrupt` | `queue_message` 请求 | 运行中发送时软打断当前 turn 后直接插入 |

`_provenance` 只接受 `schemaVersion=1`、合法 `author.type`、合法 `source.type` 与安全 ID（无路径分隔符/控制字符）。校验不过就整份丢弃，**不按正文猜作者**。

## 2. 行为变化

- **运行中发送**：与桌面一致，不再只是「排队等下一轮」。流式回复时 `queue_message` 带 `interrupt=true`（软打断后插入）；后台等待（`run_completed.backgroundTasksPending`）时只注入不打断。
- **busy 竞态**：本地还没看到 `running` 但服务端已有活跃 run 时，`send_message` 会被拒绝；移动端按原 `uuid` 自动降级为 `queue_message`，不把用户输入丢成失败气泡。
- **工具显示**：恢复逐条工具卡，挂在发起它的助手消息上；纯工具回合（没有正文）也会建一条助手消息承载卡片。旧的「同类工具合并成一行图标」已删除。
- **来源卡**：定时任务 / Agent 交接 / 外部请求的输入显示为来源卡（作者标签 + 可读来源 + 任务正文），不再是用户气泡。

## 3. 已知边界

- 移动端**没有**定时任务管理界面：本轮只做显示适配（来源卡、会话来源标记），不消费 `boundSessionId` / `pendingTriggers` / `dispatchBlockReason`。要看排队、受阻、中断原因仍需桌面。
- 来源文案里没有定时任务名称：移动端不持有定时任务目录，只显示「来自定时任务」，不回显内部 ID。
- 工具卡只有工具名 + 文件路径 + 状态：桌面投影只透传 `file_path`，不下发命令、补丁或工具结果，因此卡片不展开详情。
- 未做真机联调：断网重连、自动化触发的实时来源卡、工具卡密度、会话行钟表标记都需要真机确认。

## 4. 验证

| 项 | 结果 |
|---|---|
| `./gradlew test` | 通过（debug+release 双变体） |
| `./gradlew :app:compileDebugKotlin` | 通过 |
| `./gradlew :app:assembleDebug` | 通过 |
| 桌面 `bun test apps/electron/src/main/lib/pocket-agent-projection.test.ts` | 8 通过（含本轮新增 `_goalIteration` 透传） |

单测覆盖：来源解析（合法/非法/路径注入）、task_trigger → 来源卡、自动化作者不进用户气泡、来源卡接管空通知、工具挂载到各自助手消息、`queue_message` 的 `interrupt` 帧、busy 降级重投。
