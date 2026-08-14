# 移动端「Agent 对话流」重写方案详设（只读调研产出）

> 面向对象：后续实现子 Agent。本文档是「桌面正确性逻辑」的权威转述，重写时任何一条行为只要抄错，对应的老 bug 就会复活。
>
> 调研基准文件（本仓库）：
> - `apps/electron/src/renderer/tablet/main.tsx`（平板桥接层，含 run_completed/run_idle 去重、停止兜底、tabletMode 工具栏裁剪）
> - `apps/electron/src/renderer/tablet/electronapi-stub.ts`（伪造 electronAPI 把 WS 事件喂回桌面组件）
> - `apps/electron/src/renderer/tablet/ws-client.ts`（WS 客户端，通信语义的权威来源；已基本迁移到 `apps/tablet-client/src/client/ws-client.ts`）
> - `apps/electron/src/renderer/hooks/useGlobalAgentListeners.ts`（桌面事件 → atom 的完整归并逻辑）
> - `apps/electron/src/renderer/components/agent/AgentView.tsx`（~3040 行，交互 + 展示容器）
> - `apps/electron/src/renderer/components/agent/AgentMessages.tsx`（消息渲染）
> - `apps/electron/src/renderer/components/agent/AgentMessageQueue.tsx`（运行中追加消息队列 UI）
> - `apps/electron/src/renderer/atoms/agent-atoms.ts`（Jotai 状态语义）
> - `packages/shared/src/types/agent.ts`（`AgentStreamPayload` / `ProferEvent` / `AgentStreamCompletePayload` 等类型）

---

## 0. 重写的现状与目标边界

`apps/tablet-client` 已初始化：`ws-client.ts`、`types.ts`、`atoms/connection.ts`、`lib/storage.ts`、`main.tsx` 骨架已从桌面 tablet 迁移（通信层）。本次要重写的是**桌面 `AgentView` 的移动端子集**，且要**甩掉 `electronapi-stub` 这一层**——即：不再伪造 `window.electronAPI` 让桌面组件"零改动复用"，而是新建独立的瘦客户端组件树，直接以 `WsClient` + Jotai atoms 为数据源。

因此文档分两条主线：
1. **正确性逻辑**（第 1、2 节）：必须原样保留，不因"甩掉 stub 层"而丢失。
2. **组件契约**（第 3、4 节）：移动端需要自建的最小组件集，及每个组件的接口。

---

## 1. 流式事件的数据流（从 WS `agent_event` 到 UI 消息列表）

### 1.1 完整链路（桌面 tablet 的现状，也就是正确性的"单源事实"）

```
[远端主进程 remote-service]
        │  WebSocket 帧 { kind: 'agent_event', sessionId, payload }
        ▼
[WsClient.onmessage]  →  handleMessage  →  onAgentEvent({ sessionId, payload })
        │
        ▼
[main.tsx handleAgentEvent(client, evt)]          ← 平板桥接层（本次重写要吸收进自己的模块）
        │  1) emitTabletAgentStreamEvent({sessionId, payload})   ← stub 事件桥（要甩掉）
        │  2) 拦截 profer_event 的 run_completed / run_idle，做去重 + STREAM_COMPLETE 代理
        ▼
[electronapi-stub 事件注册器]  →  emitTabletAgentStreamEvent → useGlobalAgentListeners 的 onAgentStreamEvent
        ▼
[useGlobalAgentListeners.onAgentStreamEvent]
        │  payload.kind === 'sdk_message'  →  累积进 liveMessagesMapAtom（SDKMessage 数组）
        │  payload.kind === 'profer_event' →  payloadToLegacyEvents → applyAgentEvent 状态机
        ▼
[agentStreamingStatesAtom]  Map<sessionId, AgentStreamState>   （toolActivities / content / running / usage）
[agentMessageRefreshAtom]   完成/出错时递增，通知 AgentView 重拉持久化消息
        ▼
[AgentView]  订阅 agentMessageRefreshAtom → getAgentSessionSDKMessages → persistedSDKMessages
[AgentMessages]  合并 persisted + live 渲染
```

### 1.2 关键事件类型与 payload 结构

`AgentStreamPayload`（`packages/shared/src/types/agent.ts`）是一个可辨识联合：

```text
type AgentStreamPayload =
  | { kind: 'sdk_message'; message: SDKMessage }
  | { kind: 'profer_event'; event: ProferEvent }
```

**`sdk_message`（流式内容主体）**：`message.type` 决定消息类别，是 UI 真实渲染的来源：
- `assistant`：`SDKAssistantMessage { message.content: SDKContentBlock[] }`，content block 有两种——`{ type: 'text', text }`（增量/完整文本）与 `{ type: 'tool_use', id, name, input }`（工具调用）。带 `parent_tool_use_id`（子代理嵌套）、`isReplay`（回放标记，必须跳过）、`message.usage`（token 用量，若存在）。
- `user`：`SDKUserMessage { message.content }`，content 含 `{ type: 'text' }` 与 `{ type: 'tool_result', tool_use_id, content, is_error }`（工具结果回填）。
- `result`：`SDKResultMessage { subtype, usage, total_cost_usd, background_tasks }`，标记一轮结束（注意：`result` 只清 retrying、**保持 running:true**，最终收敛靠 STREAM_COMPLETE）。
- `system`：多种 subtype——`compacting` / `compact_boundary`（压缩）、`task_started` / `task_progress` / `task_notification`（Task 子代理）、`thinking_tokens`（进度估算，不进消息转录）。
- `tool_progress`：工具计时。`prompt_suggestion`：输入框上方建议。`tool_use_summary`：工具使用摘要。

**`profer_event`（控制/交互事件）**：`event.type` 决定，关键子集：
- `permission_request` / `permission_resolved`、`ask_user_request` / `ask_user_resolved`、`exit_plan_mode_request` / `exit_plan_mode_resolved` —— 三类阻塞交互（横幅 + 队列）。
- `enter_plan_mode` / `plan_mode_changed` / `permission_mode_changed` —— Plan 模式状态。
- `external_run_started` / `delegation_session_updated` —— 外部入口唤起的 run（飞书/钉钉/子代理），**不抢占前台 Tab**，只 upsert 会话列表。
- `run_resumed` —— 后台任务完成自动唤醒，`running:false → true`。
- `run_idle`：`{ type: 'run_idle'; sessionId: string }` —— run 结束、active 所有权已释放（orchestrator finally 广播，**可能无结果**）。
- `run_completed`：`{ type: 'run_completed'; sessionId; stoppedByUser?; startedAt?; resultSubtype?; resultErrors?; backgroundTasksPending? }` —— run 真正完成（orchestrator onComplete 广播，携带**真实完成元数据**）。

### 1.3 平板桥接层的独有职责（`main.tsx handleAgentEvent`）

桌面靠 `onAgentStreamComplete` IPC 收尾；平板 WS 没有这条消息，所以桥接层必须**由 `run_completed` / `run_idle` 代理触发** STREAM_COMPLETE。这是重写时最容易被忽略的一环（详见第 2 节）。

其余裸透传逻辑（重写后从桥接层并入自己的事件处理器）：
- `profer_event` 先判断 `external_run_started` / `delegation_session_updated`。
- 收到**未知会话**的事件（跨工作区场景）→ 立即 `listAgentSessions` 刷新会话列表。
- `sdk_message` 累积时，跳过 `isReplay`（避免与持久化消息重复）、跳过 `prompt_suggestion` / `thinking_tokens`（不进消息转录），并为实时消息补 `_createdAt`（缺省时 `Date.now()`），为 assistant 消息注入 `_channelModelId`（流式期间绑定正确模型）。

### 1.4 `agentStreamingStatesAtom` 与 `applyAgentEvent` 状态机语义

`AgentStreamState` 关键字段（`agent-atoms.ts`）：
- `running`：是否有活跃 turn。
- `stopping`：renderer 已请求停止、仍在等真实 run completion（**过渡态，不得据此伪装空闲**）。
- `backgroundWaiting`：软空闲（本轮主体结束、UI 可输入，但 SDK 通道仍开着等后台任务唤醒；`running=false` 但服务端 activeSessions 仍保留）。
- `content`：流式文本铺陈（`text_delta` 追加 / `text_complete` 覆盖）。
- `toolActivities`：`ToolActivity[]`，`tool_start` 追加 / `tool_result` 置 done / `task_backgrounded` 软后台化。
- `startedAt`：本轮开始时间戳（renderer 生成、传给主进程原样回传，**竞态保护用同一值**）。
- `inputTokens` / `outputTokens` / `contextWindow` / `costUsd`：上下文用量（流式 assistant usage_update 写入，result 只在"从未收到流式 usage"时兜底，避免进度环虚高穿透 100%）。
- `retrying`、`isCompacting` / `compactInFlight`。

`applyAgentEvent` 的核心返回值约定（**重写时必须逐一复刻**）：
- `text_delta` → `content += text`；`text_complete` → `content = text`（回放专用）。
- `complete` → 只清 `retrying` + 未完成 toolActivities 置 done，**`running` 保持 true**（等待 STREAM_COMPLETE 收敛，避免用户在后端未清理时就能发送新消息的竞态）。
- `error` / `typed_error` → `running: false`（error 不重置 retrying）。
- `tool_start` / `tool_result` / `task_backgrounded` 等按 toolUseId 增量更新 toolActivities。
- `run_resumed` → `running: true, backgroundWaiting: false`。

### 1.5 消息加载与三态合并（`AgentView` + `AgentMessages`）

UI 消息 = `persistedSDKMessages`（持久化 JSONL，经 `agentMessageRefreshAtom` 触发加载）+ `liveMessages`（流式累积）。这是防闪屏的关键：
1. 流式中：`liveMessagesMapAtom` 持续累积，`AgentMessages` 用 `persisted + live` 合并渲染（`streaming` 为 true 时直接拼接）。
2. 流式结束：`useGlobalAgentListeners` 递增 `agentMessageRefreshAtom` → `AgentView` 重拉持久化消息 → 加载完成后**同步清空** `liveMessagesMapAtom`（且仍 `running` 的会话不清，防止新流启动时被误清）。
3. `agentSDKMessagesCacheAtom`（LRU 20 条）做切换会话的内存缓存，命中则立即填充，消除"先清空 → 等读盘"空窗。

> **重写注意**：第 3 步把流式展示状态与实时消息的清理"移入消息加载完成之后"、且清理逻辑里对 `running` 会话跳过、对 `backgroundWaiting` 保留标志、对 task 相关 toolActivities 保留（供任务图匹配）——这些保护分支是防"气泡消失 → 持久化消息未到"重影/空档的关键，不能丢。

---

## 2. 完成信号正确性规范（重写时绝不能错的行为清单）

> 本节是最容易"抄错导致老 bug 复活"的部分，逐条标注。

### 2.1 `run_completed` / `run_idle` 的到达时序与 3s 去重

**背景**：`remote-service` 会在 orchestrator `onComplete` 广播 `run_completed`（携带真实元数据），orchestrator `finally` 又发 `run_idle`（active 所有权释放）。两者都表示"本轮结束"，若都完整处理 → 双次提醒音 + 双次 STREAM_COMPLETE。

**规则（`main.tsx` 常量 `RUN_COMPLETED_DEDUP_WINDOW_MS = 3000`）**：
- 用 `runCompletedProcessed: Map<sessionId, number>` 记录「已由 run_completed 处理过」的时间戳。
- `run_idle` 到达时，若命中 3s 窗口内的标记 → **跳过完整处理**（提醒音 / STREAM_COMPLETE），只保留 `loadSessions`（列表刷新，无副作用）。
- 旧服务端（无 run_completed）时 `run_idle` 仍正常作为唯一信号。

**三条硬约束**：
1. `run_completed` 必须 `set(sessionId, now)` 打标记（在判断 dedup 之前/同时）。
2. dedup 判断只对 `!isRunCompleted`（即 run_idle）生效。
3. **无论 deduped 与否，都要撤销该会话的停止超时定时器 + `loadSessions` 刷新列表**（这两步在 dedup 分支外执行）。

### 2.2 `startedAt` 竞态保护

- 用户发送时，renderer 自己生成 `streamStartedAt = Date.now()`，写入 `streamState.startedAt`，并随 `AgentSendInput.startedAt` 传给主进程原样回传。
- `run_completed` 携带真实 `opts.startedAt`；`run_idle` **无此字段**，回退 `Date.now()`。
- 桥接 STREAM_COMPLETE 时，**优先用 run_completed 的真实 startedAt**，**绝不能用 `Date.now()` 伪造**——否则 `onAgentStreamComplete` 的 startedAt 竞态保护（`current.startedAt > data.startedAt` 时忽略旧流 complete）会误判，旧流的 complete 可能重置新流的 running。

### 2.3 `stoppedByUser` 取值规则

- `run_completed`：用服务端真实值 `p.event.stoppedByUser ?? false`。
- `run_idle`：无此字段，用本地 `stopAgent` 记录的标记（`tabletStoppedByUser` Set，`stopAgent` 时 add，`consumeTabletStoppedByUser` 取并清除）。
- **两个分支都必须消费本地标记**（取后清除），避免两者都到达（run_completed → run_idle）或顺序颠倒时残留；也避免把上一轮的停止标记泄漏到下一轮。

### 2.4 停止状态机（`handleStop` + 10s 兜底）

`handleStop`（`AgentView.tsx`）：
- 防重入：`stopInFlightRef` 或 `streamState?.stopping` 已为 true 时直接 return。
- 先 `queueStopEpochRef += 1` + `setQueuedMessages(discardQueuedMessagesOnStop)`（用户明确停止 → 丢弃"运行中追加"队列消息）。
- 置 `stopping: true`（`running`/`backgroundWaiting` 至少其一的会话）。
- 调 `stopAgent`，catch 只 toast「尚未完成」，**必须保持 stopping**（timeout/reject 只说明未确认，不能伪装空闲）。

**10s 停止兜底（`main.tsx`，移动端特有）**：
- 包一层 `window.electronAPI.stopAgent`：调用时 `clearTimeout` 旧 timer，设 10s 定时器；到点若该会话仍 `running` → 强制 `{ running: false, stopping: false }`。
- 场景：SDK abort 延迟 / 事件丢失 / 会话本就不 active 被 stop 守卫跳过，导致"一直跑、停止按钮永久亮"的卡死态。
- **每次 `run_completed` / `run_idle` 到达都 `clearTimeout` 撤销**（本轮已结束）。

**陈旧 streaming 兜底（`loadSessions` 内）**：
- 拉会话列表后，若本地 `st.running` 但远端 `active=false`（完成事件在断线/丢失时没送达）→ 以主进程权威状态强制清理 `{ running:false, stopping:false }`。
- 移动端没有桌面 `restoreActiveAgentStreams` 的 IPC 保底，这条是唯一防"停止按钮永远亮、点击无效（stop 守卫 return）"的兜底。

### 2.5 STREAM_COMPLETE 的完整收敛语义（`useGlobalAgentListeners.onAgentStreamComplete`）

重写时此处理逻辑必须原样复刻到移动端自己的完成处理器：
1. `backgroundTasksPending === true` → 进软空闲（`running:false, backgroundWaiting:true`），**不发"任务完成"通知、不清后台任务列表、不重载消息**（等后台任务完成自动唤醒续轮）。
2. 竞态保护：`!current || (!current.running && !current.backgroundWaiting)` → 忽略重复/陈旧完成；`current.startedAt > data.startedAt` → 忽略旧流完成。
3. 只有 STREAM_COMPLETE 才把 `running:false, stopping:false` 真正收敛下来（`complete` 事件只清 retrying 保持 running）。
4. `data.stoppedByUser` → 写 `stoppedByUserSessionsAtom`。
5. `data.resultSubtype && resultSubtype !== 'success' && !stoppedByUser` → 截断提示（`error_max_turns` / `error_max_budget_usd` / `error_during_execution`，后者优先展示 `resultErrors[0]` 真实原因）。
6. 清除 Plan 模式残留。
7. `finalize()` 内再次 `isNewStreamRunning()` 竞态保护 + 清理后台任务 / 未完成写工具记录；`bumpRefresh()` 通知重拉消息。

---

## 3. 移动端「Agent 对话流」最小功能清单

### 3.1 必须保留（正确性核心）

| 能力 | 说明 |
|------|------|
| 流式消息接收 + 增量渲染 | `sdk_message` 累积到 live，`assistant` 文本/工具、`user` 工具结果回填 |
| 停止按钮状态机 | running/stopping/backgroundWaiting 三态，`stopping` 期间禁用、不可重入 |
| 完成信号收敛 | run_completed/run_idle 去重 + STREAM_COMPLETE 收敛 + startedAt 竞态 + stoppedByUser |
| 停止兜底 | 10s 强制清理 + loadSessions 陈旧流兜底 |
| 会话切换加载历史 | `paginateFirst` 首帧分页 + `pullEarlier` 触顶加载（`tabletMode` 特有） |
| 三类阻塞交互 | permission_request / ask_user_request / exit_plan_mode_request 横幅 + 响应 |
| 上下文用量 | ContextUsageBadge（inputTokens/contextWindow/costUsd/压缩） |
| 运行中追加消息队列 | `AgentMessageQueue`（FIFO + 停止丢弃 + 失败回队） |
| 断线重连 + 消息重放 | `WsClient` 已具备（send_message 入队重放、clientMessageId 幂等） |

### 3.2 移动端砍掉（`TABLET_HIDDEN_TOOLBAR_KEYS` + `!tabletMode` 分支）

`AgentView.tsx` 定义：
```text
TABLET_HIDDEN_TOOLBAR_KEYS = new Set([
  'thinking',      // 推理档位切换（依赖 OpenAI thinking config 弹窗）
  'speech',        // 语音输入按钮
  'attach-file',   // 本地文件附件（无本地文件对话框）
  'attach-folder', // 本地目录附件（无本地目录对话框）
  'auto-preview',  // 文件自动预览显示选项
  'graph',         // 任务图画板（无 IPC getGraph 桥）
])
```
另：**任务图画板 Dialog 在 `!tabletMode` 下不渲染**（无数据来源）。保留的工具栏项：`model`（模型选择）、`runtime`（Claude/Pi 切换）、`permission-mode`（权限模式）、`context-usage`（上下文用量）。

### 3.3 移动端特有的额外行为（重写时按需自建）

- 触顶加载更早历史（`handleLoadEarlierHistory` + `getSdkMessagesHasMore`）——桌面是全量加载，移动端是惰性分页。
- 顶栏标题（竖屏由外部顶栏承担，`hideAgentHeader` 隐藏内置 AgentHeader）。
- 触控目标 44px（`toolBtnSize = tabletMode ? 'size-11' : 'size-[36px]'`）。
- 输入框 placeholder 在 tabletMode 下留空（干净，触屏友好）。
- 完成提醒音（非当前会话完成时，`playTabletCompleteChime`，Web Audio 合成）。

---

## 4. 移动端需要自建组件 + 接口契约

> 只列接口契约（props / 局部状态 / 职责），不写实现。命名建议沿用桌面，降低心智负担。

### 4.1 数据层（已有，直接复用/补强）

- `WsClient`：已完成迁移，语义见 `client/ws-client.ts`，**不要重写**。
- `AgentWorkflowEvent = { sessionId, payload: unknown }`。
- 需要新增的 atoms（对齐 `agent-atoms.ts` 的最小集）：
  - `agentSessionsAtom: AgentSessionMeta[]`
  - `currentAgentSessionIdAtom: string | null`
  - `agentStreamingStatesAtom: Map<string, AgentStreamState>`
  - `liveMessagesMapAtom: Map<string, SDKMessage[]>`
  - `agentMessageRefreshAtom: Map<string, number>`
  - `agentSDKMessagesCacheAtom: Map<string, SDKMessage[]>`（LRU 20）
  - `stoppedByUserSessionsAtom: Set<string>`
  - 交互队列：`allPendingPermissionRequestsAtom` / `allPendingAskUserRequestsAtom` / `allPendingExitPlanRequestsAtom`（`Map<sessionId, request[]>`）
  - `currentAgentErrorAtom`（`agentStreamErrorsAtom`）

### 4.2 事件处理器（替代 `useGlobalAgentListeners` + 桥接层，移动端自建）

**建议模块**：`agentEvents.ts`（或 `useAgentEvents()` hook，在 App 顶层挂一次，永不销毁）。
- `handleAgentEvent(evt: AgentWorkflowEvent)`：吸收 `main.tsx handleAgentEvent` 的裸透传 + `useGlobalAgentListeners.onAgentStreamEvent` 的归并逻辑（1.3、1.4 节）。
- 内部维护 `runCompletedProcessed` / `pendingStopTimers` 两个 Map，实现 2.1、2.4 节的去重与兜底。
- 输出：写 atoms。完成时 `bumpRefresh()` 递增 `agentMessageRefreshAtom`。

**关键局部状态**：
- `runCompletedProcessed: Map<string, number>`（3s 去重窗口）
- `pendingStopTimers: Map<string, timeout>`（10s 停止兜底）
- `tabletStoppedByUser: Set<string>`（本地停止标记，可复刻 `consumeTabletStoppedByUser` 语义：取后清除）

### 4.3 `AgentView`（自建瘦版）

**props**：
```text
{
  sessionId: string
  hideAgentHeader?: boolean   // 竖屏由外部顶栏承担标题时隐藏内置 header
}
```
（`tabletMode` 在桌面是 props，瘦客户端可省略——它本身就是移动端；但若仍想保留隐藏工具栏语义，可内联为常量。）

**职责与局部状态**：
- `persistedSDKMessages: SDKMessage[]`（+ `persistedSDKMessagesRef`）
- `messagesLoaded: boolean`（首次加载完成标志，auto-send 等待用）
- `loadingSessionIdRef`（区分"切换会话"与"流结束刷新"，切换会话才进 loading + 命中缓存立即填充）
- `tabletHistoryHasMore` / `tabletHistoryLoading` / `tabletPullInFlightRef`（触顶加载三件套）
- 订阅 `agentMessageRefreshAtom` 触发重拉；`paginateFirst: 4` 首帧、`pullEarlier` 补更早。
- 消息加载完成后：写缓存（LRU）+ **同步清理流式展示状态与 liveMessages**（保留 inputTokens/contextWindow/backgroundWaiting/task 活动，见 1.5 节）。
- `handleSend` / `handleStop`（复刻 2.4 节状态机 + 乐观用户消息 + startedAt 生成）。
- `stopInFlightRef` / `queueStopEpochRef` / `autoSendingQueuedRef` / `queuedSendInFlightRef` / `sendingQueuedMessageIdsRef`（防重入）。

**输出**：渲染 `AgentHeader`（条件） + `AgentMessages` + 三个交互横幅 + composer。

### 4.4 `AgentMessages`（自建，渲染消息列表）

**props**：
```text
{
  sessionId: string
  messagesLoaded: boolean
  persistedSDKMessages: SDKMessage[]
  streaming: boolean
  streamState?: AgentStreamState
  liveMessages: SDKMessage[]
  stoppedByUser: boolean
  onLoadEarlierHistory?: () => void      // 触顶加载
  historyMoreAvailable?: boolean
  historyLoadingEarlier?: boolean
  onRetry / onFork / onRewind / onCompact  // 操作回调（移动端可裁剪 retry/fork/rewind）
}
```

**职责**：
- 合并 `persisted + live`（`streaming` 时直接拼接）、`getSDKMessageStableKey` 去重。
- `StreamScrollFollow`（streaming false→true 时回底部跟随）。
- fallback 气泡：无 live assistant 内容且 `streaming || smoothContent || retrying` 时渲染"流式兜底文本"；`streamingContent` 清空且不在流式中 → 立即归零，防重复渲染闪屏。
- 触顶加载 sentinel：`tabletMode && onLoadEarlierHistory` 时监听滚动到顶触发。
- 空态 / 运行指示器（`AgentRunningIndicator`）/ 压缩指示 / 软空闲指示。

### 4.5 输入区 + 工具栏（自建）

- `AgentMessageQueue`（运行中追加队列）：`items` / `canSendNow` / `onSendNow` / `onRecall` / `onRemove` / `onMove`（桌面该组件基本可直接复用/移植）。
- Composer：富文本输入（桌面 `RichTextInput` 依赖 TipTap + MCP/skill 引用解析，移动端可先做纯文本 + 保底 `/skill:` `#mcp:` `&session:` 引用正则解析）。
- 工具栏：`model` + `runtime` + `permission-mode` + `context-usage`（保留 4 项，砍掉 6 项见 3.2）。
- 停止/发送按钮：`inputTrailingNode`（streaming/stopping → 停止按钮；否则发送按钮，`canSend` 门控）。
- 三个交互横幅：`PermissionBanner` / `AskUserBanner` / `ExitPlanModeBanner`（各带 `onRequestStop`）。

---

## 5. 风险点：最容易抄错/漏掉的时序细节

1. **`run_idle` 去重分支外仍要 `clearTimeout` + `loadSessions`**：漏掉会导致停止兜底 timer 在已成 idle 后仍触发强制清理（无害但脏），或列表不刷新。
2. **STREAM_COMPLETE 是唯一收敛 `running:false` 的信道**：`complete` 事件只清 retrying。若移动端在 `complete`/`result` 时就置 running:false，会出现"助手结果还没写完就能再发消息"的竞态。
3. **startedAt 必须用 renderer 生成值回传，不能用 receive 时 `Date.now()`**：run_idle 分支回退 `Date.now()` 也要意识到它可能造成竞态保护误判（旧流 complete 覆盖新流）。
4. **`stoppedByUser` 本地标记必须"取后清除"**：否则跨轮泄漏，下一轮正常完成也会显示"已停止"。
5. **liveMessages 清理必须"移入消息加载完成后"且对 `running` 会话跳过**：否则"气泡消失 → 持久化消息未到"空档，或新流启动时被误清。
6. **`backgroundWaiting` 软空闲态**：清理时保留该标志，否则 `handleSend` 会误走"新建 run"路径；服务端 activeSessions 仍保留，新建 run 会被并发保护拒绝。
7. **task 相关 toolActivities 在清理时要保留**：否则跨 turn 的 `TaskUpdate` 匹配不到 `TaskCreate`，任务状态（completed/in_progress）丢失。
8. **optimistic 用户消息要同步写缓存**：`appendOptimisticPersistedMessage` 同时写 `persistedSDKMessages` + `agentSDKMessagesCacheAtom`，否则"发送后切走再切回"回退到旧数组。
9. **流式 assistant usage 不能被 result 覆盖**（`needResultFallback` 兜底）：否则进度环虚高穿透 100%（PR #821 教训）。
10. **`sdk_message` 累积要跳过 `isReplay`、`prompt_suggestion`、`thinking_tokens`**：replay 会与持久化重复，后两者会进消息转录被错误渲染。
11. **停止按钮 `stopping` 期间不可重入、不可伪装空闲**：catch 分支保持 stopping；10s 兜底只清 running/stopping，不碰 backgroundWaiting 语义冲突。
12. **断线重连的消息重放依赖 `clientMessageId` 幂等 + `_cmdId` 与业务 `requestId` 分离**：`WsClient.sendCommand` 的 `_cmdId` 不能被业务 payload 的 requestId 覆盖（曾导致"提问请求不存在"）。

---

## 附：关键代码速查索引

| 关注点 | 文件:行 |
|--------|---------|
| run_completed/run_idle 去重 + STREAM_COMPLETE 代理 | `apps/electron/.../tablet/main.tsx`（`RUN_COMPLETED_DEDUP_WINDOW_MS`、`handleAgentEvent`） |
| 10s 停止兜底 + 陈旧流兜底 | 同上（`pendingStopTimers`、`loadSessions` 内 staleIds） |
| sdk_message 归并 + 未知会话刷新 + isReplay 跳过 | `apps/electron/.../hooks/useGlobalAgentListeners.ts`（`onAgentStreamEvent` ~700 行） |
| STREAM_COMPLETE 收敛语义 | 同上（`onAgentStreamComplete` ~1080 行起） |
| applyAgentEvent 状态机 | `apps/electron/.../atoms/agent-atoms.ts`（`applyAgentEvent`） |
| 消息三态合并 + 缓存 + 触顶加载 | `apps/electron/.../components/agent/AgentView.tsx`（565、990-1122、1845-2270） |
| fallback 气泡 + 滚动跟随 | `apps/electron/.../components/agent/AgentMessages.tsx`（499-860） |
| 事件类型定义 | `packages/shared/src/types/agent.ts`（`ProferEvent` 611、`AgentStreamPayload` 640、`AgentStreamCompletePayload` 1241） |
| 通信语义（已迁移） | `apps/tablet-client/src/client/ws-client.ts` ↔ `apps/electron/.../tablet/ws-client.ts` |
