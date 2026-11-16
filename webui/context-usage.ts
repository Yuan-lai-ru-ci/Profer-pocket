/**
 * 上下文占用计算（口径对齐桌面端 ContextUsageBadge）。
 *
 * 数据源：`context_window` 事件 / `get_agent_runtime_contexts` 快照给「窗口大小」，
 * 最后一条顶层 assistant 消息的 usage 给「已用」。两端相除得百分比。
 *
 * 「已用」必须把输入侧三类 token 全部算上：未缓存输入 + 缓存读取 + 缓存写入。
 * 只取 `input_tokens` 会把命中缓存的上下文漏掉 —— deepseek / Claude 这类渠道命中缓存后
 * `input_tokens` 只剩几百，占用会从 14% 掉成 1%。
 *
 * 压缩阈值 = 窗口 × 0.775（与桌面 COMPACT_THRESHOLD_RATIO 同源：自动压缩发生在窗口的
 * 77.5% 处）；占用达到阈值的 80% 即警告 —— 也就是说警告线在窗口的 62% 附近，
 * 用户还有机会从容决定要不要压缩。
 */
import type { SDKMessage } from '@profer/shared'

export const COMPACT_THRESHOLD_RATIO = 0.775
export const WARNING_RATIO = 0.8

export interface ContextUsageInfo {
  /** 已用 / 窗口 的整数百分比；无法计算时为 null（没拿到窗口大小或还没用量） */
  percent: number | null
  used: number
  window: number
  /** 占用达到压缩阈值的 80%：圆点/文字转警示色 */
  warning: boolean
}

function positiveNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0
}

/**
 * 单条 assistant 消息的 usage → 上下文已用 tokens。
 * `input_tokens` 只是未命中缓存的部分，缓存读写同样是当前上下文的组成部分。
 */
export function contextTokensFromUsage(usage: unknown): number {
  if (!usage || typeof usage !== 'object') return 0
  const record = usage as Record<string, unknown>
  return positiveNumber(record.input_tokens)
    + positiveNumber(record.cache_read_input_tokens)
    + positiveNumber(record.cache_creation_input_tokens)
}

/**
 * 当前会话的上下文已用 tokens：最后一条顶层 assistant 消息的输入侧用量。
 * 子代理消息（parent_tool_use_id 非空）属于另一份上下文，跳过。
 */
export function latestContextTokens(messages: readonly SDKMessage[]): number {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (!message || message.type !== 'assistant') continue
    if (message.parent_tool_use_id) continue
    const usage = (message as { message?: { usage?: unknown } }).message?.usage
    const used = contextTokensFromUsage(usage)
    if (used > 0) return used
  }
  return 0
}

export function describeContextUsage(used: number, window: number | null | undefined): ContextUsageInfo {
  const safeUsed = Number.isFinite(used) && used > 0 ? Math.round(used) : 0
  const safeWindow = typeof window === 'number' && Number.isFinite(window) && window > 0 ? Math.round(window) : 0
  if (safeUsed === 0 || safeWindow === 0) {
    return { percent: null, used: safeUsed, window: safeWindow, warning: false }
  }
  const percent = Math.min(100, Math.max(1, Math.round((safeUsed / safeWindow) * 100)))
  const compactThreshold = Math.floor(safeWindow * COMPACT_THRESHOLD_RATIO)
  return {
    percent,
    used: safeUsed,
    window: safeWindow,
    warning: compactThreshold > 0 && safeUsed / compactThreshold >= WARNING_RATIO,
  }
}

/** tokens 的人类可读（12.3k / 1.2M），顶栏小徽章里放不下完整数字 */
export function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1).replace(/\.0$/, '')}m`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1).replace(/\.0$/, '')}k`
  return String(value)
}
