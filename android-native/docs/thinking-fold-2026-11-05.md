# 思考块：与桌面同形的折叠

> 日期：2026-11-05
> 范围：`android-native/` 的助手消息渲染（Agent 对话页与 Chat 页）。
> 起因：手机上看一段回合，思考是「思考 · 239 lines, 10.9KB. Fine.」这样一行摘要；
> 桌面同一条消息只有一行「执行过程：1 段思考」。

## 1. 桌面是怎么折的

`apps/electron/src/renderer/components/agent/`：

- `render-block-derivation.ts`：一轮里的 **thinking 与 tool_use 全部进同一个过程组**，
  只有本轮尾部的正文（以及尾部交付的图片）留在组外。
- `ProcessBlockGroup.tsx`：过程组是一行「执行过程：N 次工具调用，M 段思考」。
  流式中默认展开；回合结束后 900ms 起跳 3 秒倒计时再自动收起，用户手动点过就不再自动折。
- `ContentBlock.tsx` 的 ThinkingBlock：展开后是**标题行「思考摘要」+ 虚线框正文**，
  正文只露 4 行（`THINKING_COLLAPSE_LINE_THRESHOLD`），超出时底部给「展开摘要」，展开后给「收起」。
- 唯一例外：本轮**只有思考**（没正文、没工具）时，思考块被提到正文位置并强制展开
  （`promoteThinkingAsReply` / `shouldExpandThinkingByDefault`），否则这个回合收起来会只剩一行摘要。

## 2. 之前移动端哪里不对

1. **分组规则错了**：只有 `tools.isNotEmpty()` 才套过程组；「思考 + 正文、没有工具」的回合
   把思考单独铺在外面，于是桌面上是一行「执行过程：1 段思考」，手机上却是一整张思考卡
   （折叠态还带一句英文摘要，因为摘要取的是思考正文首行）。
2. **折叠形态不是桌面的**：移动端把思考折成一行「思考 · 摘要」+ 展开全文，
   桌面是虚线框里露 4 行 + 「展开摘要」。

## 3. 现在的行为

- `ChatScreen.AssistantMessage`：
  - `thinkingOnlyReply = 有思考 && 无工具 && 无正文` → 思考提到正文位置，且**默认展开**
    （展开态只取挂载时的判定，正文随后长出来时不会把思考夹回去，与桌面 `forceExpanded` 同语义）。
  - 其余情况只要有思考或工具，就一起进过程组，摘要按「N 次工具调用，M 段思考」计数。
- `ProferThinkingCard`（`core/designsystem`）改成桌面同形：
  标题行（🧠 + 「思考摘要」）→ 虚线框圆角正文（1.5dp、8/6 虚线，8dp 圆角）→
  超过 4 行时底部给「展开摘要 / 收起」。整张卡可点，避免手机上「展开摘要」这行字撑不到 48dp 触控下限。
  超长判定用 `onTextLayout` 的 `hasVisualOverflow`，不额外测量。
- 删掉 `ThinkingSummary.kt`（`thinkingHeadline` / `thinkingTail`）及其单测：卡片不再需要一行摘要。

没有跟着改的两处（知道但不在这轮范围内）：

- 过程组的自动折叠仍然是「结束 2s 后直接收起」，没有桌面的 3 秒倒计时文案；
- 过程组展开后没有桌面那条「收起 + 虚线分隔」的收尾行。

## 4. 验证

- `./gradlew testDebugUnitTest :app:assembleDebug`：372 项单测 0 失败，构建成功。
- `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`：21/21 通过，新增两条：
  - `thinking_without_tools_still_folds_into_process_group`：思考 + 正文（无工具）→
    出现「执行过程：1 段思考」，思考不展开，正文仍在组外；点开后才看得到思考正文；
  - `thinking_only_turn_is_promoted_and_visible`：只有思考 → 不出现过程组行，直接显示「思考摘要」卡。
- 真机（PMM110）实拍：同一会话里那条「思考 + 正文」的消息现在折成
  `执行过程：1 段思考` + 正文，与桌面一致；展开后的卡片是「思考摘要」+ 虚线框 + 4 行。
