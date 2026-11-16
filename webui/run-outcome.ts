/**
 * 运行结局 / 重试 / 落盘告警的纯派生层。
 *
 * 三个来源，优先级从高到低：
 * 1. 实时 `run_completed` / `retry` / `session_persistence` 事件（最完整）；
 * 2. 刷新后的 `session_detail.meta.lastInterrupt*`（主进程落盘的最近一次中断）；
 * 3. 消息流里的 `interruption_record` / 失败 `result`（旧会话与旧服务端兜底）。
 *
 * 只有非正常结束才产出结局：正常完成不需要在界面上多挂一行「已完成」。
 * 所有历史来源都必须过「晚于最后一条用户输入」的时效判据，否则上一轮的中断
 * 会被当成本轮结局，刷新后一直挂在会话末尾。
 */
import type { SDKMessage, SDKUserMessage } from '@profer/shared'
import { isUserInputMessage } from '@profer/session-core'

export type RunOutcomeKind = 'stopped' | 'limited' | 'failed'

export interface RunOutcomeInfo {
  kind: RunOutcomeKind
  /** 一行结局标签（服务端已归一化时直接用它） */
  label: string
  /** 失败原因（首条错误文本，已限长） */
  detail?: string
  /** 原始错误条数；大于 1 时界面显示「等 N 项」 */
  detailCount?: number
  at: number
  source: 'event' | 'history'
}

export interface RetryInfo {
  attempt: number
  maxAttempts?: number
  delaySeconds?: number
  reason?: string
}

export interface PersistenceWarning {
  message: string
}

export interface RunCompletedLike {
  outcomeStatus?: unknown
  stoppedByUser?: unknown
  resultSubtype?: unknown
  resultErrors?: unknown
  endReason?: unknown
  endReasonLabel?: unknown
  backgroundTasksPending?: unknown
}

export interface RetryEventLike {
  status?: unknown
  attempt?: unknown
  maxAttempts?: unknown
  delaySeconds?: unknown
  reason?: unknown
}

export interface PersistenceEventLike {
  status?: unknown
  message?: unknown
}

export interface InterruptMetaLike {
  lastInterruptReason?: unknown
  lastInterruptLabel?: unknown
  lastInterruptAt?: unknown
  stoppedByUser?: unknown
}

/** 与主进程 `agent-end-reason.ts` 同一套兜底文案；服务端给了 label 时不会用到。 */
const END_REASON_LABELS: Record<string, string> = {
  stopped_by_user: '已被用户中断',
  max_turns: '已中断：达到轮次上限',
  max_budget: '已中断：达到预算上限',
  max_tokens: '已中断：达到长度上限',
  error: '执行出错',
  unknown: '任务中断',
}

/** SDK result.subtype → 结局档位；success / 空值不产出结局。 */
const RESULT_SUBTYPE_OUTCOMES: Record<string, { kind: RunOutcomeKind; label: string }> = {
  error_max_turns: { kind: 'limited', label: END_REASON_LABELS.max_turns! },
  error_max_budget_usd: { kind: 'limited', label: END_REASON_LABELS.max_budget! },
  max_tokens: { kind: 'limited', label: END_REASON_LABELS.max_tokens! },
  error_during_execution: { kind: 'failed', label: END_REASON_LABELS.error! },
}

const MAX_DETAIL_CHARS = 240

function trimmedString(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function finiteNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function capDetail(value: string): string {
  const line = value.replace(/\s+/g, ' ').trim()
  return line.length > MAX_DETAIL_CHARS ? `${line.slice(0, MAX_DETAIL_CHARS)}…` : line
}

function normalizeErrors(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    .map((item) => capDetail(item))
}

function kindForEndReason(reason: string): RunOutcomeKind {
  if (reason === 'stopped_by_user') return 'stopped'
  if (reason === 'max_turns' || reason === 'max_budget' || reason === 'max_tokens') return 'limited'
  return 'failed'
}

function labelForEndReason(reason: string): string {
  return END_REASON_LABELS[reason] ?? END_REASON_LABELS.unknown!
}

function kindForLabel(label: string): RunOutcomeKind {
  if (label.includes('用户中断') || label.includes('已停止')) return 'stopped'
  if (label.includes('上限')) return 'limited'
  return 'failed'
}

/**
 * `run_completed` → 用户可见结局。
 *
 * 返回 null 的三种情况都表示「不需要显示结局」：正常完成、后台任务还在跑
 * （这只是一次软空闲）、以及事件里没有任何结局信息。
 */
export function normalizeRunOutcomeEvent(event: RunCompletedLike, now = Date.now()): RunOutcomeInfo | null {
  if (event.backgroundTasksPending === true) return null

  const reason = trimmedString(event.endReason)
  const outcomeStatus = trimmedString(event.outcomeStatus)
  const errors = normalizeErrors(event.resultErrors)
  const stoppedByUser = event.stoppedByUser === true

  if (outcomeStatus === 'completed' || reason === 'completed') {
    // 正常完成但 result 仍带错误：说明有非致命错误，值得留一行提示
    if (errors.length === 0) return null
    return {
      kind: 'failed',
      label: trimmedString(event.endReasonLabel) || END_REASON_LABELS.error!,
      detail: errors[0],
      detailCount: errors.length,
      at: now,
      source: 'event',
    }
  }

  const stopped = outcomeStatus === 'stopped' || stoppedByUser || reason === 'stopped_by_user'
  const kind = stopped
    ? 'stopped'
    : reason
      ? kindForEndReason(reason)
      : outcomeStatus === 'failed'
        ? 'failed'
        : errors.length > 0
          ? 'failed'
          : null
  if (!kind) return null

  const label = trimmedString(event.endReasonLabel)
    || (reason ? labelForEndReason(reason) : stopped ? END_REASON_LABELS.stopped_by_user! : END_REASON_LABELS.error!)
  return {
    kind,
    label,
    ...(errors.length > 0 ? { detail: errors[0], detailCount: errors.length } : {}),
    at: now,
    source: 'event',
  }
}

export type RetryTransition =
  | { action: 'set'; info: RetryInfo }
  | { action: 'clear' }

/** `retry` 事件：进行中显示，cleared / failed 都收起（最终失败由 run_completed 呈现原因）。 */
export function normalizeRetryEvent(event: RetryEventLike): RetryTransition | null {
  const status = trimmedString(event.status)
  if (status === 'starting' || status === 'attempt') {
    const attempt = finiteNumber(event.attempt) ?? 1
    const maxAttempts = finiteNumber(event.maxAttempts)
    const delaySeconds = finiteNumber(event.delaySeconds)
    const reason = trimmedString(event.reason)
    return {
      action: 'set',
      info: {
        attempt: attempt > 0 ? Math.trunc(attempt) : 1,
        ...(maxAttempts !== undefined && maxAttempts > 0 ? { maxAttempts: Math.trunc(maxAttempts) } : {}),
        ...(delaySeconds !== undefined && delaySeconds >= 0 ? { delaySeconds } : {}),
        ...(reason ? { reason: capDetail(reason) } : {}),
      },
    }
  }
  if (status === 'cleared' || status === 'failed') return { action: 'clear' }
  return null
}

export type PersistenceTransition =
  | { action: 'warn'; warning: PersistenceWarning }
  | { action: 'clear' }

/** `session_persistence` 事件：只有 failed 值得打断阅读。 */
export function normalizePersistenceEvent(event: PersistenceEventLike): PersistenceTransition | null {
  const status = trimmedString(event.status)
  if (status === 'failed') {
    const message = trimmedString(event.message) || '本轮记录未能保存到磁盘'
    return { action: 'warn', warning: { message: capDetail(message) } }
  }
  if (status === 'saved' || status === 'pending') return { action: 'clear' }
  return null
}

function messageCreatedAt(message: SDKMessage): number | undefined {
  return finiteNumber((message as { _createdAt?: unknown })._createdAt)
}

function lastUserInputAt(messages: readonly SDKMessage[]): number | undefined {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (!message || message.type !== 'user') continue
    if (!isUserInputMessage(message as SDKUserMessage)) continue
    return messageCreatedAt(message)
  }
  return undefined
}

function isFresh(at: number | undefined, lastUserAt: number | undefined): boolean {
  if (at === undefined) return false
  return lastUserAt === undefined || at >= lastUserAt
}

/**
 * 刷新 / 重连 / 重新打开会话时的结局来源。
 *
 * 先看主进程落盘的 meta（最准），再看消息里的中断记录，最后看失败 result。
 * 三处都必须晚于最后一条真实用户输入；否则会被上一轮的残留误导。
 */
export function deriveRunOutcomeFromHistory(
  messages: readonly SDKMessage[],
  meta: InterruptMetaLike | null | undefined,
): RunOutcomeInfo | null {
  const lastUserAt = lastUserInputAt(messages)

  if (meta) {
    const at = finiteNumber(meta.lastInterruptAt)
    const reason = trimmedString(meta.lastInterruptReason)
    if (at !== undefined && isFresh(at, lastUserAt)) {
      if (reason === 'completed') return null
      const stopped = reason === 'stopped_by_user' || meta.stoppedByUser === true
      const kind = stopped ? 'stopped' : reason ? kindForEndReason(reason) : 'stopped'
      const label = trimmedString(meta.lastInterruptLabel) || (reason ? labelForEndReason(reason) : END_REASON_LABELS.stopped_by_user!)
      return { kind, label, at, source: 'history' }
    }
  }

  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (!message || message.type !== 'system') continue
    const record = message as { subtype?: unknown; message?: unknown }
    if (record.subtype !== 'interruption_record') continue
    const at = messageCreatedAt(message)
    if (!isFresh(at, lastUserAt)) continue
    const label = trimmedString(record.message) || END_REASON_LABELS.unknown!
    return { kind: kindForLabel(label), label, at: at ?? Date.now(), source: 'history' }
  }

  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (!message || message.type !== 'result') continue
    const at = messageCreatedAt(message)
    if (!isFresh(at, lastUserAt)) continue
    const subtype = trimmedString((message as { subtype?: unknown }).subtype)
    if (!subtype || subtype === 'success') return null
    const known = RESULT_SUBTYPE_OUTCOMES[subtype]
    if (known) return { kind: known.kind, label: known.label, at: at ?? Date.now(), source: 'history' }
    return { kind: 'failed', label: END_REASON_LABELS.unknown!, at: at ?? Date.now(), source: 'history' }
  }

  return null
}

/** 结局行的 tone：停止是中性，限额是警告，失败是错误。 */
export function runOutcomeTone(kind: RunOutcomeKind): 'muted' | 'warning' | 'error' {
  return kind === 'stopped' ? 'muted' : kind === 'limited' ? 'warning' : 'error'
}