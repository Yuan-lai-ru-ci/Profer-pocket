/**
 * Agent 流式状态 Atoms — 移动端独立客户端（apps/tablet-client）
 *
 * 职责：承载「Agent 对话流」的流式状态（running / content / toolActivities / usage）、
 * 实时消息累积、会话终止标记与三类阻塞交互队列。
 *
 * 字段语义严格对齐桌面 @/atoms/agent-atoms.ts 的最小集（agentStreamingStatesAtom /
 * liveMessagesMapAtom / agentMessageRefreshAtom / agentSDKMessagesCacheAtom /
 * stoppedByUserSessionsAtom / allPending*RequestsAtom / agentStreamErrorsAtom /
 * currentAgentErrorAtom），但独立定义、不 import 桌面 atoms。
 *
 * 类型来源：
 *  - SDKMessage / AgentStreamPayload / AgentEvent / PermissionRequest / AskUserRequest /
 *    ExitPlanModeRequest / AgentContentBlock 等从 @profer/shared 直接引用（grep 已确认存在）。
 *  - AgentStreamState / ToolActivity 是**渲染层**类型（桌面定义于 electron 的 agent-atoms.ts，
 *    shared 无对应导出），故在本文件本地定义最小版，并注释等价来源字段。
 */

import { atom } from 'jotai'
import type {
  SDKMessage,
  AgentEvent,
  PermissionRequest,
  AskUserRequest,
  ExitPlanModeRequest,
  AgentEventUsage,
  RetryAttempt,
} from '@profer/shared'

// ============================================================================
// 本地定义的渲染层类型（对齐桌面 agent-atoms.ts，shared 无导出）
// ============================================================================

/** 活动状态（对齐桌面 ActivityStatus） */
export type ActivityStatus = 'pending' | 'running' | 'completed' | 'error' | 'backgrounded'

/** 工具活动状态（对齐桌面 ToolActivity） */
export interface ToolActivity {
  toolUseId: string
  toolName: string
  input: Record<string, unknown>
  intent?: string
  displayName?: string
  result?: string
  isError?: boolean
  done: boolean
  parentToolUseId?: string
  elapsedSeconds?: number
  taskId?: string
  shellId?: string
  isBackground?: boolean
}

/**
 * Agent 会话的流式状态（对齐桌面 AgentStreamState 最小集）。
 * 关键语义（详见 docs/agent-flow-design.md 1.4/2 章）：
 *  - running: 是否有活跃 turn。
 *  - stopping: renderer 已请求停止、仍在等真实 run completion（过渡态，不得据此伪装空闲）。
 *  - backgroundWaiting: 软空闲（running=false 但服务端 activeSessions 仍保留，等后台任务唤醒）。
 *  - startedAt: 本轮开始时间戳（renderer 生成，用于 STREAM_COMPLETE 竞态保护）。
 */
export interface AgentStreamState {
  running: boolean
  stopping?: boolean
  backgroundWaiting?: boolean
  content: string
  toolActivities: ToolActivity[]
  model?: string
  inputTokens?: number
  outputTokens?: number
  cacheReadTokens?: number
  cacheCreationTokens?: number
  costUsd?: number
  contextWindow?: number
  thinkingEstimatedTokens?: number
  usageUpdatedAt?: number
  isCompacting?: boolean
  compactInFlight?: boolean
  startedAt?: number
  retrying?: {
    currentAttempt: number
    maxAttempts: number
    history: RetryAttempt[]
    failed: boolean
  }
}

/** 从 ToolActivity 派生展示状态（对齐桌面 getActivityStatus） */
export function getActivityStatus(activity: ToolActivity): ActivityStatus {
  if (activity.isBackground) return 'backgrounded'
  if (!activity.done) return 'running'
  if (activity.isError) return 'error'
  return 'completed'
}

/**
 * 将流式状态中未完成的 toolActivities 标记为终态（对齐桌面 finalizeStreamingActivities）。
 * 用于 complete / STREAM_COMPLETE 等终态入口的兜底清理。
 * 当所有项已处于终态时返回原引用，避免不必要的 React 重渲染。
 */
export function finalizeStreamingActivities(
  toolActivities: ToolActivity[],
): { toolActivities: ToolActivity[] } {
  if (!Array.isArray(toolActivities)) return { toolActivities: [] }
  const hasUnfinishedTools = toolActivities.some((ta) => !ta.done)
  return {
    toolActivities: hasUnfinishedTools
      ? toolActivities.map((ta) => (ta.done ? ta : { ...ta, done: true }))
      : toolActivities,
  }
}

/**
 * 处理 AgentEvent 并更新流式状态（纯函数）。
 *
 * 这是移动端事件处理器（lib/agentEvents.ts）复用桌面 payloadToLegacyEvents 归并结果后，
 * 逐条 apply 到 AgentStreamState 的状态机。字段语义逐条对齐桌面 agent-atoms.ts 的 applyAgentEvent，
 * 关键正确性规则（详见设计文档 1.4 / 5 章）：
 *  - text_delta 追加 / text_complete 覆盖（回放专用）。
 *  - complete 只清 retrying + 未完成 toolActivities 置 done，**running 保持 true**
 *    （最终收敛靠 STREAM_COMPLETE，避免用户在后端未清理时就能发送新消息的竞态）。
 *  - error / typed_error → running:false（error 不重置 retrying）。
 *  - run_resumed → running:true, backgroundWaiting:false。
 *  - usage_update：流式真实值。
 */
export function applyAgentEvent(prev: AgentStreamState, event: AgentEvent): AgentStreamState {
  switch (event.type) {
    case 'text_delta':
      return { ...prev, content: prev.content + event.text, retrying: undefined }

    case 'text_complete':
      return { ...prev, content: event.text }

    case 'tool_start': {
      const existing = prev.toolActivities.find((t) => t.toolUseId === event.toolUseId)
      if (existing) {
        return {
          ...prev,
          toolActivities: prev.toolActivities.map((t) =>
            t.toolUseId === event.toolUseId
              ? { ...t, input: event.input, intent: event.intent || t.intent, displayName: event.displayName || t.displayName }
              : t,
          ),
          retrying: undefined,
        }
      }
      return {
        ...prev,
        toolActivities: [
          ...prev.toolActivities,
          {
            toolUseId: event.toolUseId,
            toolName: event.toolName,
            input: event.input,
            intent: event.intent,
            displayName: event.displayName,
            done: false,
            parentToolUseId: event.parentToolUseId,
          },
        ],
        retrying: undefined,
      }
    }

    case 'tool_result':
      return {
        ...prev,
        toolActivities: prev.toolActivities.map((t) =>
          t.toolUseId === event.toolUseId
            ? { ...t, result: event.result, isError: event.isError, done: true }
            : t,
        ),
      }

    case 'task_backgrounded':
      return {
        ...prev,
        toolActivities: prev.toolActivities.map((t) =>
          t.toolUseId === event.toolUseId
            ? { ...t, isBackground: true, taskId: event.taskId, done: true }
            : t,
        ),
      }

    case 'task_progress':
      if (event.elapsedSeconds != null) {
        return {
          ...prev,
          toolActivities: prev.toolActivities.map((t) =>
            t.toolUseId === event.toolUseId ? { ...t, elapsedSeconds: event.elapsedSeconds! } : t,
          ),
        }
      }
      return prev

    case 'task_started': {
      let nextActivities = prev.toolActivities
      if (event.toolUseId && prev.toolActivities.some((t) => t.toolUseId === event.toolUseId)) {
        nextActivities = prev.toolActivities.map((t) =>
          t.toolUseId === event.toolUseId ? { ...t, intent: event.description, taskId: event.taskId } : t,
        )
      }
      return { ...prev, toolActivities: nextActivities }
    }

    case 'shell_backgrounded':
      return {
        ...prev,
        toolActivities: prev.toolActivities.map((t) =>
          t.toolUseId === event.toolUseId ? { ...t, isBackground: true, shellId: event.shellId, done: true } : t,
        ),
      }

    case 'shell_killed':
    case 'task_notification':
    case 'tool_use_summary':
    case 'permission_request':
    case 'permission_resolved':
    case 'ask_user_request':
    case 'ask_user_resolved':
    case 'exit_plan_mode_request':
    case 'exit_plan_mode_resolved':
    case 'prompt_suggestion':
    case 'model_resolved':
      // 这些事件不影响流式状态，由 lib/agentEvents.ts 的队列管理分支处理
      return prev

    case 'thinking_tokens':
      return { ...prev, thinkingEstimatedTokens: event.estimatedTokens }

    case 'complete': {
      // 成功完成 — 只清 retrying + 未完成工具置 done，running 保持 true（等 STREAM_COMPLETE 收敛）。
      // token 默认只信任流式 assistant usage_update（PR #821：result.usage 是累计求和会虚高穿透 100%），
      // 仅当从未收到流式 usage（prev.inputTokens 空/0）时从 result.usage 兜底。
      const needResultFallback = !prev.inputTokens || prev.inputTokens <= 0
      const usage: AgentEventUsage | undefined = event.usage
      return {
        ...prev,
        ...(usage
          ? {
              ...(usage.costUsd != null && { costUsd: usage.costUsd }),
              ...(usage.contextWindow != null && { contextWindow: usage.contextWindow }),
              ...(usage.contextWindow != null && { usageUpdatedAt: Date.now() }),
              ...(needResultFallback && usage.inputTokens != null && { inputTokens: usage.inputTokens }),
              ...(needResultFallback && usage.outputTokens != null && { outputTokens: usage.outputTokens }),
              ...(needResultFallback && usage.cacheReadTokens != null && { cacheReadTokens: usage.cacheReadTokens }),
              ...(needResultFallback && usage.cacheCreationTokens != null && { cacheCreationTokens: usage.cacheCreationTokens }),
              ...(needResultFallback && { usageUpdatedAt: Date.now() }),
            }
          : {}),
        retrying: undefined,
        ...finalizeStreamingActivities(prev.toolActivities),
      }
    }

    case 'run_resumed':
      return { ...prev, running: true, backgroundWaiting: false }

    case 'typed_error':
      return { ...prev, running: false, retrying: undefined }

    case 'error':
      // error 事件不重置 retrying（retrying 由专用 retry_* 事件控制）
      return { ...prev, running: false }

    case 'usage_update': {
      const u = event.usage
      return {
        ...prev,
        ...(u.inputTokens != null && { inputTokens: u.inputTokens }),
        ...(u.outputTokens != null && { outputTokens: u.outputTokens }),
        ...(u.cacheReadTokens != null && { cacheReadTokens: u.cacheReadTokens }),
        ...(u.cacheCreationTokens != null && { cacheCreationTokens: u.cacheCreationTokens }),
        ...(u.costUsd != null && { costUsd: u.costUsd }),
        // 流式 usage_update 推断的 contextWindow 不覆盖 result 已给的真实值
        ...(u.contextWindow && !prev.contextWindow && { contextWindow: u.contextWindow }),
        usageUpdatedAt: Date.now(),
      }
    }

    case 'compacting':
      return { ...prev, isCompacting: true, compactInFlight: true }

    case 'compact_complete':
      return { ...prev, isCompacting: false }

    case 'retrying':
      return {
        ...prev,
        retrying: prev.retrying ?? {
          currentAttempt: event.attempt,
          maxAttempts: event.maxAttempts,
          history: [],
          failed: false,
        },
      }

    case 'retry_attempt': {
      const currentHistory = prev.retrying?.history ?? []
      return {
        ...prev,
        retrying: {
          currentAttempt: event.attemptData.attempt,
          maxAttempts: prev.retrying?.maxAttempts ?? 3,
          history: [...currentHistory, event.attemptData],
          failed: false,
        },
      }
    }

    case 'retry_cleared':
      return { ...prev, retrying: undefined }

    case 'retry_failed': {
      const finalHistory = prev.retrying?.history ?? []
      return {
        ...prev,
        running: false,
        retrying: {
          currentAttempt: event.finalAttempt.attempt,
          maxAttempts: prev.retrying?.maxAttempts ?? 3,
          history: [...finalHistory, event.finalAttempt],
          failed: true,
        },
      }
    }

    default:
      return prev
  }
}

// ============================================================================
// Atoms
// ============================================================================

/** Agent 会话列表（本轨道不拥有，供事件处理器读取「未知会话」判断；由 session.ts 维护） */
// 说明：此处不重复定义 sessionsAtom，循环依赖风险。事件处理器通过回调注入列表访问器。

/** 流式状态 Map — sessionId → AgentStreamState */
export const agentStreamingStatesAtom = atom<Map<string, AgentStreamState>>(new Map())

/** 实时 SDKMessage 累积 Map — 流式期间逐条追加，完成后由消息加载清理（对齐桌面 liveMessagesMapAtom） */
export const liveMessagesMapAtom = atom<Map<string, SDKMessage[]>>(new Map())

/** 消息刷新版本 Map — 完成/出错时递增，通知消息加载层重拉持久化消息 */
export const agentMessageRefreshAtom = atom<Map<string, number>>(new Map())

/** 持久化 SDKMessage 内存缓存 — 消除「切换会话先清空→等读盘」空窗，LRU 20 */
export const AGENT_MSG_CACHE_MAX = 20
export const agentSDKMessagesCacheAtom = atom<Map<string, SDKMessage[]>>(new Map())

/**
 * 写入会话消息缓存并执行 LRU 淘汰（对齐桌面 setSessionMessagesCache）。
 * 利用 Map 插入顺序：删除旧 key 再 set 移到末尾；超限从头（最旧）删。
 */
export function setSessionMessagesCache(
  prev: Map<string, SDKMessage[]>,
  sessionId: string,
  messages: SDKMessage[],
): Map<string, SDKMessage[]> {
  const next = new Map(prev)
  next.delete(sessionId)
  next.set(sessionId, messages)
  while (next.size > AGENT_MSG_CACHE_MAX) {
    const oldest = next.keys().next().value
    if (oldest === undefined) break
    next.delete(oldest)
  }
  return next
}

/** 被用户手动打断的会话集合（仅当前流式周期有效，对齐桌面 stoppedByUserSessionsAtom） */
export const stoppedByUserSessionsAtom = atom<Set<string>>(new Set<string>())

/** 待处理权限请求 Map — sessionId → request[] */
export const allPendingPermissionRequestsAtom = atom<Map<string, readonly PermissionRequest[]>>(new Map())

/** 待处理 AskUser 请求 Map — sessionId → request[] */
export const allPendingAskUserRequestsAtom = atom<Map<string, readonly AskUserRequest[]>>(new Map())

/** 待处理 ExitPlanMode 请求 Map — sessionId → request[] */
export const allPendingExitPlanRequestsAtom = atom<Map<string, readonly ExitPlanModeRequest[]>>(new Map())

/** Agent 流式错误消息 Map — sessionId → error string（对齐桌面 agentStreamErrorsAtom） */
export const agentStreamErrorsAtom = atom<Map<string, string>>(new Map())

/** 当前 Agent 会话的错误消息（派生只读，对齐桌面 currentAgentErrorAtom） */
export const currentAgentErrorAtom = atom<string | null>((get) => {
  const currentId = currentSessionGetter ? currentSessionGetter() : null
  if (!currentId) return null
  return get(agentStreamErrorsAtom).get(currentId) ?? null
})

// 说明：移动端「当前会话」由 session.ts 的 currentSessionIdAtom 维护。
// 为避免 agent.ts 反向依赖 session.ts 造成循环，这里用一个可注入的只读 getter，
// 由事件处理器 / UI 层在启动时注入（见 lib/agentEvents.ts 的 registerCurrentSessionGetter）。
let currentSessionGetter: (() => string | null) | null = null
export function registerCurrentSessionGetter(getter: () => string | null): void {
  currentSessionGetter = getter
}
