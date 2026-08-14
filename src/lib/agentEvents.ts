/**
 * agentEvents 事件处理器 — 移动端独立客户端（apps/tablet-client）
 *
 * 替代桌面「tablet/main.tsx 桥接层 + useGlobalAgentListeners」两层的归并逻辑：
 * 以纯函数形式把 AgentWorkflowEvent 归并写入 atoms，便于单测。
 *
 * 权威契约见 docs/agent-flow-design.md（尤其 1.3 / 1.4 / 2 / 5 章）。
 * 关键正确性常量与字段（从桌面参考实现提炼）：
 *  - RUN_COMPLETED_DEDUP_WINDOW_MS = 3000：run_completed / run_idle 去重窗口。
 *  - STOP_TIMEOUT_MS = 10000：停止兜底，到点仍 running 则强制 { running:false, stopping:false }。
 *  - STREAM_COMPLETE 是唯一收敛 running:false 的信道（complete 只清 retrying 保持 running）。
 *  - startedAt 必须用 run_completed 回传的真实值，run_idle 无此字段才回退 Date.now()。
 *  - stoppedByUser 本地标记「取后清除」，跨轮不泄漏。
 *  - sdk_message 累积必须跳过 isReplay / prompt_suggestion / thinking_tokens。
 *  - liveMessages 清理由消息加载完成后执行（不在本处理器内），且 running 会话跳过。
 *
 * 设计选择：
 *  - 不依赖 jotai 的 React store 单例，而是接收一个「store 访问器」对象注入，
 *    使得本模块既是纯逻辑、又能在 App 顶层被 hook 挂载时绑定真实 atom。
 *  - 内部维护 runCompletedProcessed / pendingStopTimers / tabletStoppedByUser 三个模块级状态，
 *    等价桌面 main.tsx 的模块级 Map/Set。
 */

import type { AgentWorkflowEvent } from '@/client/ws-client'
import type {
  AgentStreamPayload,
  SDKMessage,
  SDKAssistantMessage,
  SDKUserMessage,
  SDKResultMessage,
  SDKSystemMessage,
  SDKContentBlock,
  SDKUserContentBlock,
  ProferEvent,
  PermissionRequest,
  AskUserRequest,
  ExitPlanModeRequest,
} from '@profer/shared'
import {
  agentStreamingStatesAtom,
  liveMessagesMapAtom,
  agentMessageRefreshAtom,
  agentSDKMessagesCacheAtom,
  stoppedByUserSessionsAtom,
  allPendingPermissionRequestsAtom,
  allPendingAskUserRequestsAtom,
  allPendingExitPlanRequestsAtom,
  agentStreamErrorsAtom,
  applyAgentEvent,
  finalizeStreamingActivities,
  setSessionMessagesCache,
  type AgentStreamState,
} from '@/atoms/agent'

// ============================================================================
// 关键正确性常量（从桌面参考实现提炼）
// ============================================================================

/** run_completed / run_idle 去重窗口（ms）。remote-service 在 orchestrator onComplete 发
 *  run_completed、orchestrator finally 又发 run_idle，两者都表示本轮结束；3s 内后到者跳过完整处理。 */
export const RUN_COMPLETED_DEDUP_WINDOW_MS = 3000

/** 停止兜底超时（ms）。点击停止后 10s 内无 run_completed/run_idle 确认则强制清理本地 streaming，防卡死。 */
export const STOP_TIMEOUT_MS = 10_000

// ============================================================================
// 模块级内部状态（等价桌面 main.tsx 的模块级 Map/Set）
// ============================================================================

/** 已由 run_completed 处理过的 sessionId → 时间戳（3s 去重窗口） */
const runCompletedProcessed = new Map<string, number>()

/** 停止兜底定时器 — sessionId → timer handle */
const pendingStopTimers = new Map<string, ReturnType<typeof setTimeout>>()

/** 本地停止标记 — stopAgent 时 add，消费时取后清除（对齐 consumeTabletStoppedByUser 语义） */
const tabletStoppedByUser = new Set<string>()

// ============================================================================
// Store 访问器抽象：让纯逻辑与 jotai 解耦，便于单测
// ============================================================================

/**
 * AgentStore 的最小读写接口。
 * 生产环境由 hook 桥接到 jotai 的 getDefaultStore()；测试可注入纯 Map 实现。
 */
export interface AgentStore {
  /** 读 atom 当前值 */
  get<T>(atom: unknown): T
  /** 写 atom（支持 updater 函数） */
  set<T>(atom: unknown, value: T | ((prev: T) => T)): void
}

let activeStore: AgentStore | null = null

/** 注册 store 访问器（App 顶层挂载时调用一次；不注册则禁用副作用写回） */
export function registerAgentEventStore(store: AgentStore): void {
  activeStore = store
}

// ============================================================================
// 归并辅助（对齐桌面 payloadToLegacyEvents 的子集）
// ============================================================================

/** 累积 SDKMessage 时是否跳过（isReplay / prompt_suggestion / thinking_tokens 不进转录） */
function shouldSkipLiveMessage(msg: SDKMessage): boolean {
  const r = msg as Record<string, unknown>
  if (r.isReplay) return true
  if (r.type === 'prompt_suggestion') return true
  if (r.type === 'system' && (r as SDKSystemMessage).subtype === 'thinking_tokens') return true
  return false
}

/** 将 AgentStreamPayload 转换为旧 AgentEvent[]（对齐桌面 payloadToLegacyEvents 的归并核心） */
export function payloadToAgentEvents(payload: AgentStreamPayload): import('@profer/shared').AgentEvent[] {
  if (payload.kind === 'profer_event') {
    const evt = payload.event
    switch (evt.type) {
      case 'permission_request':
        return [{ type: 'permission_request', request: evt.request }]
      case 'permission_resolved':
        return [{ type: 'permission_resolved', requestId: evt.requestId, behavior: evt.behavior }]
      case 'ask_user_request':
        return [{ type: 'ask_user_request', request: evt.request }]
      case 'ask_user_resolved':
        return [{ type: 'ask_user_resolved', requestId: evt.requestId }]
      case 'exit_plan_mode_request':
        return [{ type: 'exit_plan_mode_request', request: evt.request }]
      case 'exit_plan_mode_resolved':
        return [{ type: 'exit_plan_mode_resolved', requestId: evt.requestId }]
      default:
        // run_completed / run_idle / run_resumed / external_run_started 等
        // 在 handleAgentEvent 主流程单独处理，不走 legacyEvents 累积。
        return []
    }
  }

  const msg = payload.message
  switch (msg.type) {
    case 'assistant': {
      const aMsg = msg as SDKAssistantMessage
      if (aMsg.isReplay) return []
      if (aMsg.error) return [{ type: 'error', message: aMsg.error.message }]
      const events: import('@profer/shared').AgentEvent[] = []
      for (const block of aMsg.message.content) {
        if (block.type === 'text' && 'text' in block) {
          events.push({
            type: 'text_complete',
            text: (block as { text: string }).text,
            isIntermediate: false,
            parentToolUseId: aMsg.parent_tool_use_id ?? undefined,
          })
        } else if (block.type === 'tool_use') {
          const tb = block as SDKContentBlock & { id: string; name: string; input: Record<string, unknown> }
          events.push({
            type: 'tool_start',
            toolName: tb.name,
            toolUseId: tb.id,
            input: tb.input,
            intent: (tb.input._intent as string | undefined) ?? (tb.name === 'Bash' ? (tb.input.description as string | undefined) : undefined),
            displayName: tb.input._displayName as string | undefined,
            parentToolUseId: aMsg.parent_tool_use_id ?? undefined,
          })
        }
      }
      if (!aMsg.parent_tool_use_id && aMsg.message.usage) {
        const u = aMsg.message.usage
        const inputTokens = u.input_tokens + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0)
        events.push({
          type: 'usage_update',
          usage: {
            inputTokens,
            outputTokens: u.output_tokens,
            cacheReadTokens: u.cache_read_input_tokens,
            cacheCreationTokens: u.cache_creation_input_tokens,
          },
        })
      }
      return events
    }

    case 'user': {
      const uMsg = msg as SDKUserMessage
      if (uMsg.isReplay) return []
      const events: import('@profer/shared').AgentEvent[] = []
      const blocks = uMsg.message?.content ?? []
      for (const block of blocks) {
        if (block.type === 'tool_result') {
          const tb = block as SDKUserContentBlock & { tool_use_id: string; content?: unknown; is_error?: boolean }
          const resultStr = typeof tb.content === 'string' ? tb.content : tb.content != null ? JSON.stringify(tb.content) : ''
          events.push({
            type: 'tool_result',
            toolUseId: tb.tool_use_id,
            result: resultStr,
            isError: tb.is_error ?? false,
            parentToolUseId: uMsg.parent_tool_use_id ?? undefined,
          })
        }
      }
      return events
    }

    case 'result': {
      const rMsg = msg as SDKResultMessage
      const u = rMsg.usage
      const inputTokens = u ? u.input_tokens + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0) : undefined
      return [
        {
          type: 'complete',
          stopReason: rMsg.subtype === 'success' ? 'end_turn' : 'error',
          usage:
            rMsg.total_cost_usd != null || u != null
              ? {
                  costUsd: rMsg.total_cost_usd,
                  ...(inputTokens != null && { inputTokens }),
                  ...(u && { outputTokens: u.output_tokens }),
                  ...(u && { cacheReadTokens: u.cache_read_input_tokens }),
                  ...(u && { cacheCreationTokens: u.cache_creation_input_tokens }),
                }
              : undefined,
        },
      ]
    }

    case 'system': {
      const sMsg = msg as SDKSystemMessage
      if (sMsg.subtype === 'compact_boundary') return [{ type: 'compact_complete' }]
      if (sMsg.subtype === 'compacting') return [{ type: 'compacting' }]
      if (sMsg.subtype === 'task_started' && sMsg.task_id) {
        return [{ type: 'task_started', taskId: sMsg.task_id, description: sMsg.description ?? '', taskType: sMsg.task_type, toolUseId: sMsg.tool_use_id }]
      }
      if (sMsg.subtype === 'task_notification' && sMsg.task_id) {
        return [{
          type: 'task_notification',
          taskId: sMsg.task_id,
          status: (sMsg.status as 'completed' | 'failed' | 'stopped') ?? 'completed',
          summary: sMsg.summary ?? '',
          outputFile: sMsg.output_file,
          toolUseId: sMsg.tool_use_id,
          usage: sMsg.usage ? { totalTokens: sMsg.usage.total_tokens ?? 0, toolUses: sMsg.usage.tool_uses ?? 0, durationMs: sMsg.usage.duration_ms ?? 0 } : undefined,
        }]
      }
      if (sMsg.subtype === 'task_progress' && sMsg.task_id) {
        return [{
          type: 'task_progress',
          taskId: sMsg.task_id,
          toolUseId: sMsg.tool_use_id ?? sMsg.task_id,
          description: sMsg.description,
          lastToolName: sMsg.last_tool_name,
          usage: sMsg.usage ? { totalTokens: sMsg.usage.total_tokens ?? 0, toolUses: sMsg.usage.tool_uses ?? 0, durationMs: sMsg.usage.duration_ms ?? 0 } : undefined,
        }]
      }
      if (sMsg.subtype === 'thinking_tokens' && typeof (sMsg as { estimated_tokens?: number }).estimated_tokens === 'number') {
        const t = sMsg as unknown as { estimated_tokens: number; estimated_tokens_delta?: number }
        return [{ type: 'thinking_tokens', estimatedTokens: t.estimated_tokens, estimatedTokensDelta: typeof t.estimated_tokens_delta === 'number' ? t.estimated_tokens_delta : 0 }]
      }
      return []
    }

    case 'tool_progress': {
      const tp = msg as { tool_use_id: string; elapsed_time_seconds?: number; task_id?: string }
      return [{ type: 'task_progress', toolUseId: tp.tool_use_id, elapsedSeconds: tp.elapsed_time_seconds, taskId: tp.task_id }]
    }

    case 'tool_use_summary': {
      const tus = msg as { summary?: string; preceding_tool_use_ids?: string[] }
      if (tus.summary) return [{ type: 'tool_use_summary', summary: tus.summary, precedingToolUseIds: tus.preceding_tool_use_ids ?? [] }]
      return []
    }

    default:
      return []
  }
}

// ============================================================================
// 交互队列 / 流式错误 / 完成收敛 的副作用写回
// ============================================================================

/**
 * 处理单个 AgentWorkflowEvent，把归并结果写入 atoms。
 *
 * 这是移动端替代桌面 handleAgentEvent（main.tsx）+ useGlobalAgentListeners.onAgentStreamEvent
 * 的纯逻辑入口。App 顶层 hook 把每个 WS agent_event 转发到此处。
 *
 * 返回 { deduped } 供调用方决定是否还需要刷新会话列表（run_idle 去重分支内）。
 */
export function handleAgentEvent(evt: AgentWorkflowEvent): { sessionId: string; deduped: boolean } {
  const sessionId = evt.sessionId
  const payload = evt.payload as AgentStreamPayload
  if (!activeStore) return { sessionId, deduped: false }

  const store = activeStore

  // ---- 完成信号：run_completed / run_idle 的去重 + STREAM_COMPLETE 代理 ----
  if (payload.kind === 'profer_event') {
    const e = payload.event as ProferEvent
    if (e.type === 'run_completed' || e.type === 'run_idle') {
      const isRunCompleted = e.type === 'run_completed'
      const now = Date.now()
      const lastCompleted = runCompletedProcessed.get(sessionId)
      const deduped =
        !isRunCompleted && lastCompleted !== undefined && now - lastCompleted < RUN_COMPLETED_DEDUP_WINDOW_MS
      if (isRunCompleted) runCompletedProcessed.set(sessionId, now)

      // 本地 stoppedByUser：run_completed 用服务端真实值，run_idle 用本地标记；
      // 两分支都「取后清除」本地标记，避免残留/跨轮泄漏。
      const localStopped = consumeTabletStoppedByUser(sessionId)
      const runCompletedEvt = e as Extract<ProferEvent, { type: 'run_completed' }>
      const stoppedByUser = isRunCompleted ? (runCompletedEvt.stoppedByUser ?? false) : localStopped

      if (!deduped) {
        // startedAt 用真实值（run_completed 带 opts.startedAt），run_idle 无字段才回退 Date.now()。
        const startedAt = isRunCompleted ? (runCompletedEvt.startedAt ?? Date.now()) : Date.now()
        onAgentStreamComplete(store, {
          sessionId,
          stoppedByUser,
          startedAt,
          resultSubtype: runCompletedEvt.resultSubtype,
          resultErrors: runCompletedEvt.resultErrors,
          backgroundTasksPending: runCompletedEvt.backgroundTasksPending,
        })
      }

      // 无论 deduped 与否，都要撤销停止兜底定时器（本轮已结束）
      clearStopTimer(sessionId)
      return { sessionId, deduped }
    }

    // run_resumed 单独处理（不出现在 payloadToAgentEvents 中）
    if (e.type === 'run_resumed') {
      store.set(agentStreamingStatesAtom, (prev: Map<string, AgentStreamState>) => {
        const current = prev.get(sessionId)
        if (!current || current.running) return prev
        const map = new Map(prev)
        map.set(sessionId, { ...current, running: true, backgroundWaiting: false })
        return map
      })
      return { sessionId, deduped: false }
    }
  }

  // ---- sdk_message：累积到 liveMessagesMapAtom（跳过 replay/prompt_suggestion/thinking_tokens）----
  if (payload.kind === 'sdk_message') {
    const msg = payload.message
    if (!shouldSkipLiveMessage(msg)) {
      const msgRecord = msg as Record<string, unknown>
      if (typeof msgRecord._createdAt !== 'number') msgRecord._createdAt = Date.now()
      store.set(liveMessagesMapAtom, (prev: Map<string, SDKMessage[]>) => {
        const map = new Map(prev)
        const current = map.get(sessionId) ?? []
        // 简化：按 uuid 去重，无 uuid 直接追加（移动端最小集，完整 upsertByUuid 由 UI 层消息合并负责）
        const next = upsertLiveMessageByUuid(current, msg)
        if (next === current) return prev
        map.set(sessionId, next)
        return map
      })
    }
  }

  // ---- 归并成旧 AgentEvent[]，逐条 apply ----
  const events = payloadToAgentEvents(payload)
  for (const event of events) {
    // 交互队列分支（不影响流式状态）
    switch (event.type) {
      case 'permission_request':
        enqueueRequest(store, allPendingPermissionRequestsAtom, sessionId, event.request as PermissionRequest)
        continue
      case 'ask_user_request':
        enqueueRequest(store, allPendingAskUserRequestsAtom, sessionId, event.request as AskUserRequest)
        continue
      case 'exit_plan_mode_request':
        enqueueRequest(store, allPendingExitPlanRequestsAtom, sessionId, event.request as ExitPlanModeRequest)
        continue
      case 'permission_resolved':
        dequeueByRequestId(store, allPendingPermissionRequestsAtom, sessionId, event.requestId)
        continue
      case 'ask_user_resolved':
        dequeueByRequestId(store, allPendingAskUserRequestsAtom, sessionId, event.requestId)
        continue
      case 'exit_plan_mode_resolved':
        dequeueByRequestId(store, allPendingExitPlanRequestsAtom, sessionId, event.requestId)
        continue
      case 'error':
        // 记录流式错误 + running:false
        store.set(agentStreamErrorsAtom, (prev: Map<string, string>) => {
          const map = new Map(prev)
          map.set(sessionId, event.message)
          return map
        })
        break
    }

    // 更新流式状态
    store.set(agentStreamingStatesAtom, (prev: Map<string, AgentStreamState>) => {
      const current: AgentStreamState =
        prev.get(sessionId) ?? {
          running: true,
          content: '',
          toolActivities: [],
          model: undefined,
          // startedAt 留空：让 STREAM_COMPLETE 竞态保护跳过时间戳比较
          startedAt: undefined,
        }
      const next = applyAgentEvent(current, event)
      const map = new Map(prev)
      map.set(sessionId, next)
      return map
    })
  }

  return { sessionId, deduped: false }
}

// ---- 完成收敛（对齐 useGlobalAgentListeners.onAgentStreamComplete 收敛语义）----

interface StreamCompleteArgs {
  sessionId: string
  stoppedByUser: boolean
  startedAt: number
  resultSubtype?: string
  resultErrors?: string[]
  backgroundTasksPending?: boolean
}

function onAgentStreamComplete(store: AgentStore, data: StreamCompleteArgs): void {
  const backgroundTasksPending = data.backgroundTasksPending === true

  store.set(agentStreamingStatesAtom, (prev: Map<string, AgentStreamState>) => {
    const current = prev.get(data.sessionId)
    // 既非运行中、也非软空闲态 → 已彻底结束，忽略重复/陈旧完成
    if (!current || (!current.running && !current.backgroundWaiting)) return prev
    // startedAt 竞态保护：忽略旧流 complete 覆盖新流
    if (current.startedAt != null && (data.startedAt == null || current.startedAt > data.startedAt)) {
      return prev
    }
    const map = new Map(prev)
    map.set(data.sessionId, {
      ...current,
      running: false,
      backgroundWaiting: backgroundTasksPending,
      stopping: false,
      ...finalizeStreamingActivities(current.toolActivities),
    })
    return map
  })

  // 标记用户主动打断
  if (data.stoppedByUser) {
    store.set(stoppedByUserSessionsAtom, (prev: Set<string>) => {
      const next = new Set(prev)
      next.add(data.sessionId)
      return next
    })
  }

  // 截断提示由 UI 层消费 resultSubtype/resultErrors；此处仅记录错误（若为非 success 且非用户停止）
  if (data.resultSubtype && data.resultSubtype !== 'success' && !data.stoppedByUser) {
    if (data.resultSubtype === 'error_during_execution' || data.resultSubtype === 'error') {
      const detail = data.resultErrors?.find((e) => typeof e === 'string' && e.trim().length > 0)?.trim()
      if (detail) {
        store.set(agentStreamErrorsAtom, (prev: Map<string, string>) => {
          const map = new Map(prev)
          map.set(data.sessionId, detail)
          return map
        })
      }
    }
  }

  // 新流竞态保护后递增刷新版本，通知消息加载层重拉持久化消息
  const state = store.get<Map<string, AgentStreamState>>(agentStreamingStatesAtom).get(data.sessionId)
  const isNewStreamRunning = state?.running === true
  if (!isNewStreamRunning && !backgroundTasksPending) {
    store.set(agentMessageRefreshAtom, (prev: Map<string, number>) => {
      const map = new Map(prev)
      map.set(data.sessionId, (prev.get(data.sessionId) ?? 0) + 1)
      return map
    })
  }
}

// ---- 交互队列辅助 ----

function enqueueRequest<T>(
  store: AgentStore,
  atomRef: unknown,
  sessionId: string,
  request: T,
): void {
  store.set(atomRef, (prev: Map<string, readonly T[]>) => {
    const map = new Map(prev)
    const current = map.get(sessionId) ?? []
    map.set(sessionId, [...current, request])
    return map
  })
}

interface HasRequestId {
  requestId: string
}

function dequeueByRequestId(
  store: AgentStore,
  atomRef: unknown,
  sessionId: string,
  requestId: string,
): void {
  store.set(atomRef, (prev: Map<string, readonly HasRequestId[]>) => {
    const map = new Map(prev)
    const current = map.get(sessionId) ?? []
    const next = current.filter((r) => r.requestId !== requestId)
    if (next.length > 0) map.set(sessionId, next)
    else map.delete(sessionId)
    return map
  })
}

/** 按 uuid 累积 sdk_message（对齐桌面 upsertLiveMessageByUuid 的简化版：无 uuid 直接追加） */
function upsertLiveMessageByUuid(current: SDKMessage[], next: SDKMessage): SDKMessage[] {
  const nextUuid = (next as { uuid?: string }).uuid
  if (nextUuid == null) return [...current, next]
  const idx = current.findIndex((m) => (m as { uuid?: string }).uuid === nextUuid)
  if (idx === -1) return [...current, next]
  const copy = current.slice()
  copy[idx] = next
  return copy
}

// ============================================================================
// 停止状态机：stopAgent + 本地停止标记 + 10s 兜底
// ============================================================================

/**
 * 请求停止某会话（对齐 main.tsx 的 stopAgent 包装 + desktop handleStop 的 stopping 语义）。
 *
 * 调用方（UI 层）应先在 UI 侧做防重入（stopping 已 true 则 return），此处负责：
 *  - 写本地停止标记（供 run_idle 分支消费）
 *  - 置 stopping:true（running/backgroundWaiting 至少其一的会话）
 *  - 设 10s 兜底定时器，到点仍 running 则强制 { running:false, stopping:false }
 *  - 调底层的 stopAgent（由调用方传入真正的 sendCommand 函数，本模块不直接依赖 ws-client 实例）
 */
export function requestAgentStop(
  sessionId: string,
  stopFn: (sessionId: string) => Promise<unknown>,
): void {
  const store = activeStore
  if (!store) return

  const sid = String(sessionId)

  // 写本地停止标记（run_idle 分支会取后清除）
  tabletStoppedByUser.add(sid)

  // 置 stopping 过渡态
  store.set(agentStreamingStatesAtom, (prev: Map<string, AgentStreamState>) => {
    const cur = prev.get(sid)
    if (!cur || !(cur.running || cur.backgroundWaiting)) return prev
    const map = new Map(prev)
    map.set(sid, { ...cur, stopping: true })
    return map
  })

  // 10s 停止兜底
  const existing = pendingStopTimers.get(sid)
  if (existing) clearTimeout(existing)
  pendingStopTimers.set(
    sid,
    setTimeout(() => {
      pendingStopTimers.delete(sid)
      store.set(agentStreamingStatesAtom, (prev: Map<string, AgentStreamState>) => {
        const cur = prev.get(sid)
        if (!cur?.running) return prev
        const map = new Map(prev)
        map.set(sid, { ...cur, running: false, stopping: false })
        return map
      })
    }, STOP_TIMEOUT_MS),
  )

  // 调用真实 stop（catch 静默，UI 层负责 toast；失败时停止兜底定时器仍生效）
  stopFn(sid).catch(() => {
    /* 停止未确认：不伪装空闲，保持 stopping；10s 兜底会强制清理 */
  })
}

/** 撤销某会话的停止兜底定时器（run_completed/run_idle 到达时调用） */
export function clearStopTimer(sessionId: string): void {
  const sid = String(sessionId)
  const timer = pendingStopTimers.get(sid)
  if (timer) {
    clearTimeout(timer)
    pendingStopTimers.delete(sid)
  }
}

/** 取并清除本地停止标记（对齐 consumeTabletStoppedByUser 语义） */
export function consumeTabletStoppedByUser(sessionId: string): boolean {
  const sid = String(sessionId)
  if (tabletStoppedByUser.has(sid)) {
    tabletStoppedByUser.delete(sid)
    return true
  }
  return false
}

// ============================================================================
// 陈旧流兜底（对齐 main.tsx loadSessions 内 staleIds 兜底）
// ============================================================================

/**
 * 拉取会话列表后，若本地仍 running 但远端 active=false（完成事件在断线时丢失），
 * 以主进程权威状态强制清理（防「停止按钮永远亮、点击无效」卡死态）。
 *
 * 调用方在 list_sessions 成功后传入远端 active 集合。
 */
export function reconcileStaleStreams(remoteActiveIds: Set<string>): void {
  const store = activeStore
  if (!store) return
  const states = store.get<Map<string, AgentStreamState>>(agentStreamingStatesAtom)
  const staleIds = new Set<string>()
  for (const [sid, st] of states) {
    if (st?.running && !remoteActiveIds.has(sid)) staleIds.add(sid)
  }
  if (staleIds.size === 0) return
  store.set(agentStreamingStatesAtom, (prev: Map<string, AgentStreamState>) => {
    const map = new Map(prev)
    for (const sid of staleIds) {
      const cur = map.get(sid)
      if (cur?.running) map.set(sid, { ...cur, running: false, stopping: false })
    }
    return map
  })
}

// re-export 供 UI 层复用（缓存写入不再由本模块触发，消息加载层主动写入）
export { setSessionMessagesCache, agentSDKMessagesCacheAtom }
