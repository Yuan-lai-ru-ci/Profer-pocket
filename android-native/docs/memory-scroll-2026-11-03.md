# 长会话内存与滚动优化（2026-11-03）

现象：移动端在长会话里上下滚动、左右滑抽屉时掉帧。根因不是单点，而是「每次 token 都把整份历史重建一遍」
带来的持续分配，加上几处没有上限的图片内存。本次只做内存/分配优化，不改视觉与交互。

## 1. 根因与改动

### 1.1 整表重投影（主因）

`PocketViewModel.chatUiState` 在 `flowOn(Dispatchers.Default)` 上做 `UiMapping.chatState`。
流式 token 60ms 一批，每批都会：

- 对**每条**历史助手消息重新 `parseRichBlocks(text)`（Markdown 分块 + 每段 `AnnotatedString`）；
- 对每条用户消息重新 `stripAttachedFiles` / `parseAttachedFiles` / 格式化时间（每次都新建 `SimpleDateFormat`）；
- 对每个工具调用重新算状态与文案；
- 造出一整份新的 `List<ChatMessage>` 对象图。

300 条消息的会话、每秒 16 批 → 每秒上万次对象分配。分配本身不在主线程，但 GC 是进程级的，
滚动和抽屉侧滑正好会被 GC 停顿打断。

**改动**（`app/.../UiMapping.kt`）：

- 新增 `MessageProjection` 缓存：以「`ChatEntry` 引用 + 影响投影的上下文（在线、running、失败/完成工具集合）」
  命中，一次 token 只重建真正变化的那条。
- 工具 id 集合只增不改（归约层每次 `+` 出新实例），所以用**引用比较**判定，不付出集合比对的成本。
- 表没变时连 `List` 实例一起复用：`StateFlow` 的 equals 去重、`remember(messages)` 派生状态都不会白跑。
- 缓存只保留当前会话；切会话即清空，不会随 875 条目录无限增长。
- 重发/截断后按 id `retainAll`，缓存不跟着长会话只增不减。
- `timeLabel` 改用 ThreadLocal `SimpleDateFormat`，不再每条消息新建 formatter。
- Chat 模式 `conversationUiState` 同样处理（`ConversationProjection`）。

### 1.2 消息图片没有内存上限

`loadMessageImage` 原来把 data URL 直接整张解码（4000×3000 ≈ 48MB ARGB_8888），
缓存按**条数** 24 张，超了整体 `clear()`。同时 `mutableMapOf` 在多协程下并发读写没有保护。

**改动**：

- `ImageDecoding.kt`：先 `inJustDecodeBounds` 读尺寸，按 `MESSAGE_IMAGE_MAX_DIMENSION = 1440` 做 2 的幂降采样再解码。
- `PocketViewModel`：换成 `LruCache<String, Bitmap>`，上限 `min(堆/8, 32MB)`，`sizeOf` 用 `allocationByteCount`。
- 附件大图预览（`AttachmentPreviewDialog`）原来在**组合期主线程**解码原图，改为 `produceState` + `Dispatchers.Default`，
  上限 2048px。

### 1.3 重组期的重复计算

- `ChatScreen.toolCardCount` 原来每次重组都扫整张消息表（输入框每敲一个字都会触发），改为 `remember(state.messages)`。
- 思考卡摘要（`thinkingTail` / `thinkingHeadline`）会 `trim + 正则替换` 整段思考正文（长会话里最长的字符串），
  改为 `remember(body, live)`；`ProferThinkingCard` 内部的首行摘要同样 remember。
- `RichInlineText` 每次重组都重建 `AnnotatedString`，改为 `remember(raw, codeBackground)`。
- Chat 模式流式项的 `parseRichBlocks(streamText)` 与 `thinkingTail` 同样 remember。
- 两个消息 `LazyColumn` 补 `contentType`，同类型项复用组合槽位。

### 1.4 会话正文永不释放

`PocketRepository._chats` 保留每个打开过的会话的完整历史，从不释放：逛过十几个长会话后，
堆里全是历史消息。

**改动**：LRU 窗口 `MAX_LOADED_SESSIONS = 5`，只保留最近打开的几条正文；被回收的会话 `loaded` 不再为 true，
下次打开按 `openSession` 重新水合（历史里已有的消息会与实时消息按 id 合并，不会重复）。

不参与回收：正在跑的、等人确认的、后台还有任务的、还有待发消息的（`outgoingSessionIds`）、
以及当前打开的那个会话。策略抽成纯函数 `sessionsToEvict` 便于单测。
窗口是「软上限」：溢出段里全是受保护会话时，宁可窗口超一点，也不动最新的几条。

## 2. 验证

- 新增单测：`UiMappingProjectionTest`（6 条，含「未变化消息实例复用」「表未变复用同一 List」「切会话不串用」）、
  `SessionRetentionTest`（6 条，含受保护会话与软上限）、`sampleSizeFor` 降采样倍数。
- `./gradlew testDebugUnitTest` 全模块通过；`./gradlew :app:assembleDebug` 通过。
- JVM 基准（300 条混合 Markdown 消息，macOS 桌面 JVM，仅看相对量级）：

  | 场景 | 平均 | p50 |
  | --- | --- | --- |
  | 整表重建（旧行为，每个 token 一次） | 1.23ms | 1.16ms |
  | 增量投影（新行为，只重建流式那条） | 0.07ms | 0.04ms |

  手机上 ART 没有桌面 JIT 预热，绝对值会放大数倍；关键是一次 token 从「O(全部历史)」降到「O(1)」。

## 3. 未做 / 待真机确认

- **抽屉逐帧图层**：`PocketDrawerLayout` 在 `graphicsLayer` 里按 `progress` 每帧改 `shape`/`clip`，
  理论上会让正文区每帧重建裁剪轮廓。没有真机 profile 前不动它：任何改动都会影响侧滑观感，
  且当前无法在设备上验收。建议先按下面的方式采样，确认这一项占多少帧预算再决定。
- **真机采样方法**（需要连设备）：
  - 滚动：`adb shell dumpsys gfxinfo com.profer.pocket framestats`，看 90/95/99 分位；
  - 侧滑：先 `adb shell dumpsys gfxinfo com.profer.pocket reset`，做 5 次开合抽屉后再 dump；
  - 内存：`adb shell dumpsys meminfo com.profer.pocket`，对比「打开 1 个长会话」与「逛过 8 个长会话」的 Java Heap；
  - 若侧滑仍是瓶颈，再评估把圆角按整 dp 量化（`derivedStateOf` 只在整数变化时重组）或把 clip 从逐帧 block 移出。
- **历史消息分页**：极长会话（上千条）目前一次水合全量。若要再降峰值，可只保留最近 N 条 + 「加载更早」。
- **Chat 模式对话正文**：`_conversationMessages` 同样是「打开过就留着」，本次只做了投影缓存，没加 LRU。
  普通对话普遍比 Agent 会话短，先观察；若同样吃紧，照 `MAX_LOADED_SESSIONS` 的思路加窗口。
