import type { AgentStreamState } from '@/atoms/agent-atoms'

export interface AgentRuntimeContextSnapshot {
  sessionId: string
  contextWindow: number
  updatedAt: number
}

/**
 * 将主端权威运行快照写入 Pocket 的既有流状态。
 *
 * 首次连入运行中的会话时，Pocket 可能已错过一次性的 context_window 流事件；
 * 此处必须覆盖 renderer 根据模型名得到的低可信 fallback（例如 Terra 的旧 200K）。
 */
export function hydrateAgentRuntimeContexts(
  previous: Map<string, AgentStreamState>,
  snapshots: readonly AgentRuntimeContextSnapshot[],
  activeSessionIds: ReadonlySet<string>,
): Map<string, AgentStreamState> {
  let next: Map<string, AgentStreamState> | undefined

  for (const snapshot of snapshots) {
    if (!activeSessionIds.has(snapshot.sessionId)) continue
    if (!Number.isFinite(snapshot.contextWindow) || snapshot.contextWindow <= 0) continue

    const current = (next ?? previous).get(snapshot.sessionId)
    const hydrated: AgentStreamState = {
      ...(current ?? { running: true, content: '', toolActivities: [] }),
      // 会话列表与运行时快照均表明主端仍 active；补建状态避免迟到连接持续被视为空闲。
      running: current?.running ?? true,
      contextWindow: snapshot.contextWindow,
      usageUpdatedAt: snapshot.updatedAt,
    }

    if (
      current
      && current.contextWindow === hydrated.contextWindow
      && current.usageUpdatedAt === hydrated.usageUpdatedAt
      && current.running === hydrated.running
    ) continue

    next ??= new Map(previous)
    next.set(snapshot.sessionId, hydrated)
  }

  return next ?? previous
}
