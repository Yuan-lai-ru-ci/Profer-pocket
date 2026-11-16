/**
 * WebUI ↔ 主进程的 WS 命令契约（**只有类型，没有运行时**）。
 *
 * 为什么需要它：页面与服务端是**两个仓库**维护的，服务端在
 * `ProferAI/apps/electron/src/main/lib/remote-service.ts` 的 `handleRemoteCommand`，
 * 以前页面这边每个调用点都写一遍 `as { goal?: unknown }`，字段一改只能靠全局 grep。
 *
 * 口径：这里声明的字段必须与服务端一致；`unknown` 表示「服务端可能不给 / 可能给脏值」，
 * 由各自的 normalize 函数负责校验 —— 契约固定形状，校验仍然留在使用处。
 */
import type { SDKMessage } from '@profer/shared'

/** `get_goal` / `start_goal` / `pause_goal` / `resume_goal` / `stop_goal` / `update_goal` 的 data */
export interface GoalEnvelope {
  goal?: unknown
}

/** `get_goal_history` 的 data */
export interface GoalHistoryEnvelope {
  goals?: unknown
}

/** `list_visualizations` 的 data（只回元数据与 chart 规格，不回 html 正文） */
export interface VisualizationEnvelope {
  records?: unknown
}

/** `search_session_messages` 的 data：`matches` 项由 normalizeSessionSearchMatches 校验 */
export interface SessionSearchEnvelope {
  query?: unknown
  matches?: unknown
}

/** `get_session_delegations` 的 data：`running` 项由 normalizeDelegations 校验 */
export interface DelegationSnapshotEnvelope {
  running?: unknown
  blockedCount?: unknown
}

/** `read_file_as_data_url` 的 data */
export interface DataUrlEnvelope {
  dataUrl?: unknown
}

/** `get_pi_reasoning_capability` 的 data：Pi 内核下模型自带的档位表 */
export interface PiReasoningCapabilityEnvelope {
  levels?: unknown
}

/**
 * `session_detail` 的 data。
 *
 * 老版本把 meta 包在 `data.meta` 里，新版本直接给 `meta` —— 两种都要认（见 loadSessionMeta）。
 */
export interface SessionMetaPayload {
  title?: unknown
  channelId?: unknown
  modelId?: unknown
  workspaceId?: unknown
  workspaceSlug?: unknown
  permissionMode?: unknown
  agentRuntime?: unknown
  agentEffort?: unknown
  openAIThinkingLevel?: unknown
  presetId?: unknown
  presetReference?: unknown
  revision?: unknown
  /** 服务端权威运行态：客户端的事件推断会落后，发送前以它为准 */
  active?: unknown
  /** 最近一次非正常结束（主进程落盘）；刷新后据此恢复结局行 */
  lastInterruptReason?: unknown
  lastInterruptLabel?: unknown
  lastInterruptAt?: unknown
  stoppedByUser?: unknown
}

export interface SessionDetailEnvelope {
  meta?: SessionMetaPayload
  data?: { meta?: SessionMetaPayload }
}

/** `save_agent_attachment` 的 data：filename 是服务端加了序号后的真实文件名 */
export interface SavedAttachmentEnvelope {
  filename?: unknown
  path?: unknown
  size?: unknown
}

/**
 * `send_message` 的请求体。
 *
 * `clientKind` 只接受白名单值（服务端把其它字符串一律当作 `remote`），
 * 页面固定发 `'webui'`，用于在桌面端/手机上标注「这条是在网页里发的」。
 */
export interface SendMessagePayload {
  sessionId: string
  userMessage: string
  channelId: string
  modelId?: string
  workspaceId?: string
  permissionMode?: string
  clientKind?: 'webui'
  attachments?: Array<{ filename: string }>
}

/** `get_sdk_messages` 的分页返回（裸数组 / `{messages,startIndex,hasMore}` / `data` 包裹三种形态） */
export interface MessagePagePayload {
  messages?: SDKMessage[]
  startIndex?: number
  hasMore?: boolean
  data?: MessagePagePayload | SDKMessage[]
}

/**
 * `profer_event` 帧里 `event` 字段的形状。
 *
 * 只列出页面会消费的字段；其余字段由具体分支按需读取。
 */
export interface ProferEventPayload {
  type?: string
  request?: Record<string, unknown>
  requestId?: string
  goal?: unknown
  proposal?: unknown
  proposalId?: string
  mode?: unknown
  record?: unknown
  /** 上下文窗口大小（tokens）：模型/内核随事件下发 */
  contextWindow?: unknown
  // run_completed：结局与重试/落盘告警共用同一帧形状
  outcomeStatus?: unknown
  stoppedByUser?: unknown
  resultSubtype?: unknown
  resultErrors?: unknown
  endReason?: unknown
  endReasonLabel?: unknown
  backgroundTasksPending?: unknown
  // retry
  status?: unknown
  attempt?: unknown
  maxAttempts?: unknown
  delaySeconds?: unknown
  reason?: unknown
  // session_persistence / system 消息共用
  message?: unknown
  // 委派子会话
  delegationId?: unknown
  blockedEvent?: unknown
  blockedEventId?: unknown
  resolution?: unknown
  session?: unknown
}

/** `agent_event` 帧（kind=sdk_message / profer_event）的 payload */
export interface AgentEventPayload {
  kind?: string
  message?: SDKMessage
  event?: ProferEventPayload
}

/** rewind_prepare 计划里与页面相关的字段（完整契约见主仓 packages/shared/src/types/agent-rewind.ts） */
export interface RewindPlanFilePayload {
  itemId?: string
  action?: 'restore' | 'delete' | 'keep' | 'conflict' | string
  path?: string
  relativePath?: string
}

export interface RewindPlanPayload {
  planId?: string
  revision?: { session?: number; files?: number; state?: number }
  capability?: { supportsFiles?: boolean }
  files?: RewindPlanFilePayload[]
}

export interface RewindPrepareEnvelope {
  plan?: RewindPlanPayload
}

export interface RewindExecuteEnvelope {
  status?: 'committed' | 'recovery-required' | 'rejected' | 'failed' | string
  conversation?: { remainingMessages?: number }
  files?: { restored?: string[]; deleted?: string[]; conflicts?: string[] }
}
