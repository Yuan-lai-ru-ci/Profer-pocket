/**
 * Pocket 权威运行时上下文窗口（R2）
 *
 * 背景：`context_window` 是 run 启动时主端从 SDK result 拿到的**瞬时**流事件；
 * Pocket 晚连接 / 重连 / 事件日志过期后的快照恢复都会错过它。此时 renderer 只能按模型名
 * 推断 fallback（`inferContextWindow`，未知模型一律 200K），于是圆环弹层的「上下文 x/y」
 * 与「占用 %」和电脑端不一致。
 *
 * 本模块负责两件事：
 *  1. 把主端权威快照（WS `get_agent_runtime_contexts`，仅覆盖活跃 run 的会话）水合进流状态；
 *  2. 登记「权威窗口」，让后续按模型名推断的 fallback 不能把它覆盖回去。
 *
 * 数据来源与降级：桌面端只返回 `{ sessionId, contextWindow, updatedAt }`；旧版桌面端不识别
 * 该命令时返回 `ok:false`，调用方静默降级（保留流事件/模型名推断的现状），不报错、不清空圆环。
 */

import type { AgentStreamState } from '@/atoms/agent-atoms'

export interface AgentRuntimeContextSnapshot {
  sessionId: string
  contextWindow: number
  updatedAt: number
}

/**
 * 权威上下文窗口登记表（会话 ID → 主端/SDK 确认的窗口大小）。
 *
 * 只由 pocket 侧的权威数据路径（实时 `context_window` 事件 / 快照水合）写入，
 * 且在 run 结束（STREAM_COMPLETE）时清除，避免跨 run、跨模型切换时残留旧分母。
 */
const authoritativeContextWindows = new Map<string, number>()

/**
 * 解析 `get_agent_runtime_contexts` 返回值。
 *
 * 形状不符的条目（旧服务端、异常数据、非活跃会话的空值）直接丢弃 —— 全量不合法时返回空数组，
 * 由调用方静默降级为现状，不做任何提示。
 */
export function normalizeAgentRuntimeContextSnapshots(data: unknown): AgentRuntimeContextSnapshot[] {
  if (!Array.isArray(data)) return []
  const snapshots: AgentRuntimeContextSnapshot[] = []
  for (const raw of data) {
    if (raw == null || typeof raw !== 'object') continue
    const record = raw as { sessionId?: unknown; contextWindow?: unknown; updatedAt?: unknown }
    if (typeof record.sessionId !== 'string' || record.sessionId.length === 0) continue
    const contextWindow = record.contextWindow
    if (typeof contextWindow !== 'number' || !Number.isFinite(contextWindow) || contextWindow <= 0) continue
    snapshots.push({
      sessionId: record.sessionId,
      contextWindow,
      updatedAt: typeof record.updatedAt === 'number' && Number.isFinite(record.updatedAt)
        ? record.updatedAt
        : Date.now(),
    })
  }
  return snapshots
}

/** 登记权威窗口；返回是否写入了新的权威值。 */
export function rememberAuthoritativeContextWindow(sessionId: string, contextWindow: unknown): boolean {
  if (!sessionId) return false
  if (typeof contextWindow !== 'number' || !Number.isFinite(contextWindow) || contextWindow <= 0) return false
  if (authoritativeContextWindows.get(sessionId) === contextWindow) return false
  authoritativeContextWindows.set(sessionId, contextWindow)
  return true
}

/** 读取权威窗口；无权威值（未水合 / run 已结束）时返回 undefined。 */
export function getAuthoritativeContextWindow(sessionId: string): number | undefined {
  return authoritativeContextWindows.get(sessionId)
}

/** run 结束后清除权威值：下一个 run 若没有新的权威事件，应回到原有推断语义，而不是沿用旧分母。 */
export function clearAuthoritativeContextWindow(sessionId: string): void {
  authoritativeContextWindows.delete(sessionId)
}

/** 清空全部权威登记（单测隔离用）。 */
export function resetAuthoritativeContextWindows(): void {
  authoritativeContextWindows.clear()
}

interface HydrateOptions {
  /** 只水合这些会话（主端 active 会话白名单）；缺省表示不限。 */
  sessionIds?: ReadonlySet<string>
  /** 本地没有该会话流状态时是否补建条目（默认 false：不凭空激活空闲会话）。 */
  createIfMissing?: boolean
}

/**
 * 把主端权威快照写入流状态。
 *
 * - 只覆盖 `contextWindow` 与 `usageUpdatedAt`，不碰 token/运行态字段；
 * - 默认只更新已有条目，避免给没有用户可见活动的会话造出「空闲流状态」；
 * - 命中即登记权威值：此后 `usage_update` 里按模型名推断的 fallback 不会再覆盖它
 *   （推断值只在 `prev.contextWindow` 为空时才会被采纳）。
 */
export function hydrateAgentRuntimeContexts(
  previous: Map<string, AgentStreamState>,
  snapshots: readonly AgentRuntimeContextSnapshot[],
  options: HydrateOptions = {},
): Map<string, AgentStreamState> {
  const { sessionIds, createIfMissing = false } = options
  let next: Map<string, AgentStreamState> | undefined

  for (const snapshot of snapshots) {
    if (sessionIds && !sessionIds.has(snapshot.sessionId)) continue
    const current = (next ?? previous).get(snapshot.sessionId)
    if (!current && !createIfMissing) continue

    rememberAuthoritativeContextWindow(snapshot.sessionId, snapshot.contextWindow)

    if (
      current
      && current.contextWindow === snapshot.contextWindow
      && current.usageUpdatedAt === snapshot.updatedAt
    ) {
      continue
    }

    next ??= new Map(previous)
    next.set(snapshot.sessionId, {
      ...(current ?? { running: false, content: '', toolActivities: [] }),
      contextWindow: snapshot.contextWindow,
      usageUpdatedAt: snapshot.updatedAt,
    })
  }

  return next ?? previous
}
