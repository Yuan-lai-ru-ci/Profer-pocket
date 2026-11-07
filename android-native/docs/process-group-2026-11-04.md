# 思考与工具的过程折叠（与桌面同构）

> 日期：2026-11-04
> 范围：`android-native/` 的 Agent 对话消息渲染。Chat（普通对话）页没有工具块，本次不动。

## 1. 之前的问题

移动端把一条助手回复拆成三块分别渲染：顶部「思考 · 摘要」折叠行、正文、正文下方逐条工具卡。
桌面把思考与工具调用收进同一个「执行过程」组，回合结束后只剩一行：

```text
执行过程：5 次工具调用，1 段思考
```

移动端逐条铺开时，一轮 5 次工具调用就有 4~5 行折叠行，手机屏上比桌面多占一大截，
阅读位置也被顶下去。用户反馈「移动版的消息不会像桌面版一样把思考和工具的部分折叠」。

## 2. 现在的行为

- **流式中**：过程组默认展开，思考卡与工具卡照旧可见；摘要行右侧有活动指示。
- **回合结束**：2 秒后自动收起为一行摘要（留出读完最后一条工具结果的时间）。
  摘要与桌面 `buildProcessGroupSummary` 同口径：工具与思考分开计数。
- **手动操作优先**：用户点过展开/收起后，后续不再自动切换（`processTouched`），
  切换会话再回来仍保持选择（`rememberSaveable(message.id)`）。
- **展开后**：思考卡（仍是自己的折叠行）与逐条工具卡（默认只留最近 3 条，其余折成「展开其余 N 条」）。
- **只有思考、没有工具**：保持原来的单行思考卡，不再套一层过程组——它本来就已经是一行。
  > 2026-11-05 修订：这条只对「只回思考、没正文也没工具」的回合成立；有正文时思考也要进过程组，
  > 且思考卡改成桌面同形（标题行 + 虚线框 4 行 + 展开摘要）。见 `thinking-fold-2026-11-05.md`。
- 摘要行最多显示 4 个工具类型图标，其余显示为 `+N`；大字号下摘要允许折两行，避免「1 段思…」被截断。
  > 2026-11-05 修订：合并的单位从「一条助手消息」改成「一个回合」——移动端一条助手消息只是模型的一步，
  > 一轮七八步就会叠出七八行摘要。见 `turn-merge-2026-11-05.md`。

## 3. 实现落点

| 位置 | 改动 |
|---|---|
| `core/designsystem/.../ChatComponents.kt` | 新增 `ProferProcessGroup`：摘要行 + 图标 + 活动指示 + 可展开内容 |
| `feature/chat/.../ProcessGroupSummary.kt` | 纯函数 `processGroupSummary(toolCount, thinkingCount)` |
| `feature/chat/.../ChatScreen.kt` | `AssistantMessage` 把思考与工具放进过程组；`ThinkingRow` / `ToolCallList` 抽出复用 |
| `app/src/androidTest/.../UiSystemRegressionTest.kt` | 折叠、展开、流式结束自动收起三条回归 |

桌面参照：`apps/electron/src/renderer/components/agent/ProcessBlockGroup.tsx`。

## 4. 验证（2026-11-04，API 35 AVD 720×1280 / 320dpi）

| 项 | 结果 |
|---|---|
| `./gradlew test`（debug + release 单测） | 通过 |
| `:app:connectedDebugAndroidTest` | 18/18 通过（含新增 3 条折叠回归） |
| 浅色 / 深色 / 2.0× 大字号 | `adb exec-out screencap` 实拍：摘要行、图标、展开后的思考卡与工具卡均正常 |
| 手动展开 / 收起 | 实机点击验证：展开后思考与工具出现，再次点击收起 |
| 流式结束自动收起 | 仪器测试用 `mainClock.advanceTimeBy(3000)` 验证：从展开变为一行摘要 |
| `:app:lintDebug` | **基线失败**：`AttachmentPreviewDialog.kt:87` 的 `ProduceStateDoesNotAssignValue`，与本次改动无关（该文件未修改） |

未验证：真实桌面端流式联调（本轮用 `UiGalleryActivity` 的稳定样本 + 仪器测试），
以及 TalkBack 对过程组展开状态的朗读。
