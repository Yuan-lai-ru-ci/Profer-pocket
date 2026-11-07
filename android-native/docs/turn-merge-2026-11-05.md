# 一轮一个过程组：把连续助手条目合成回合

> 日期：2026-11-05
> 范围：`android-native/` 的 Agent 对话消息渲染（`ChatScreen`）。
> 起因：用户截图——一轮排查 7 步，屏幕上叠了 7 行「执行过程：1 次工具调用，1 段思考」。

## 1. 为什么会一行一步

上一版的过程组是**按助手消息**折的（`process-group-2026-11-04.md`），桌面的口径是**按回合**折的：

- 桌面 `groupIntoTurns`：上一条用户输入之后的所有 assistant 消息属于同一个 turn，
  整轮只有一个 `ProcessBlockGroup`，摘要是「N 次工具调用，M 段思考」；
- 移动端一条 `ChatEntry.Assistant` 只对应模型的**一步**（一段思考 + 这次发起的工具调用）。
  一轮 7 步就是 7 条条目，逐条投影、逐条折，手机上顶掉大半屏。

桌面那行摘要之所以是 5 次、7 次，正是因为它把整轮的消息一起数；移动端按条数自然永远是 1。

## 2. 现在的做法

### 2.1 合并发生在投影层

`UiMapping.projectedMessages` 在逐条投影之后跑一遍 `mergeAssistantTurns`：

- 相邻的 `ChatMessage.Assistant` 合成一个 `ChatMessage.Turn(id = 首步 id, steps)`；
- 遇到用户消息、来源卡、系统提示就断开——与桌面 `groupIntoTurns` 同一条边界规则；
- 只有一步的回合**仍是** `ChatMessage.Assistant`，单条消息的渲染路径完全不变。

首步 id 当回合 id：后续步骤追加时 `LazyColumn` 的 key 不变，列表不会因为「一轮在长」而重置位置。

### 2.2 合并结果要能复用

回合按首步 id 缓存，缓存命中条件是**步骤列表逐项是同一个实例**：

- 流式时只有最后一步在变，前面的回合仍返回同一个 `Turn`；
- 整张消息表因此还能保持「没变就复用同一个 List 实例」，不会每个 token 重建一遍。

### 2.3 过程组里放什么

`feature/chat/.../TurnLayout.kt` 的 `layoutTurn(steps)` 把回合拆成两部分：

| 归属 | 内容 |
|---|---|
| 过程组（默认折起） | 每步的思考卡、该步的工具卡、**中间步骤**的正文，按发生顺序交替排列 |
| 组外（正文位置） | 最后一段有正文的步骤；它之后如果还有工具调用，说明是给工具看的说明，留在过程里 |

- 思考与工具放在同一个有序列表里（`TurnItem.Thought / Tool / Text`），不是「先铺全部思考再铺全部工具」——
  一轮七八步时，那样看不出哪段思考对应哪次调用。
- 展开后默认只铺**最近 3 步**（`ProcessStepPreviewCount`），更早的折成一行「还有 N 步过程」。
  上一版限的是「最近 3 张工具卡」；按步截断才能让思考卡和它的工具卡不被拆开。
- 思考是本轮唯一产出时，照旧提到正文位置并展开，不折成一行「执行过程：1 段思考」。
- 图片按步骤顺序合并、按路径去重，仍放在正文之后。

### 2.4 顺带修的两处

- 复制按钮复制的是**组外正文**（原来的实现把工具标题也拼进复制内容，一轮几十次工具会复制出一堆噪声）。
- 「跟随滚动」的工具计数按回合统计（`Turn.steps` 展开数）。不改的话，多步回合里新工具到达时
  消息数不变、工具计数也不变，用户停在上面就看不到新内容。

## 3. 实现落点

| 位置 | 改动 |
|---|---|
| `feature/chat/.../ChatModels.kt` | 新增 `ChatMessage.Turn(id, steps)` |
| `feature/chat/.../TurnLayout.kt` | `layoutTurn` + `TurnItem`（思考/工具/中间正文） |
| `feature/chat/.../ChatScreen.kt` | `AssistantRow` 统一外壳；`AssistantMessage` 按回合渲染；步骤截断、思考展开态按步骤 id 记 |
| `app/.../UiMapping.kt` | `mergeAssistantTurns` + 回合实例缓存 |
| `app/src/debug/.../UiGalleryActivity.kt` | `page=turn`：一轮六步的稳定样本（含答案正文） |

## 4. 验证

| 项 | 结果 |
|---|---|
| `./gradlew test`（debug + release 单测） | 384 项 debug 单测 0 失败 |
| `:app:connectedDebugAndroidTest`（API 35 AVD 720×1280 / 320dpi） | 23/23 通过 |
| 新增单测 | `TurnLayoutTest`（7 条：交替顺序、组外正文、单步不变、思考即回答、图片去重）；`UiMappingTurnMergeTest`（5 条：合并、用户消息切断、单步不加壳、实例复用、追加步骤后身份不变） |
| 新增仪器测试 | `multi_step_turn_folds_into_one_process_row`（一行摘要 + 展开 + 「还有 N 步过程」）；`multi_step_turn_ends_with_answer_outside_group`（答案不被折进去） |
| 实拍（`adb exec-out screencap`） | 浅色 / 深色 / 2.0× 大字号三张；折叠态只有一行「执行过程：5 次工具调用，5 段思考」+ 4 个工具类型图标；点开后思考卡与工具卡交替出现 |

未验证：真实桌面端流式联调（本轮用 `page=turn` 的稳定样本 + 仪器测试）；
TalkBack 对回合摘要与「还有 N 步过程」的朗读。
