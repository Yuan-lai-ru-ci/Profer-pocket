import type { AgentSessionMeta } from '@profer/shared'

/** Remote Store 会话优先；旧 agentSessionsAtom 仅作为冷启动迁移回退。 */
export function resolveAuthoritativeAgentSession(
  remoteSession: AgentSessionMeta | undefined,
  legacySession: AgentSessionMeta | undefined,
): AgentSessionMeta | undefined {
  return remoteSession ?? legacySession
}

/**
 * 将桌面返回的完整会话对象写回兼容镜像。
 * 低 revision 结果不能覆盖本地已经看到的更新。
 */
export function mergeAuthoritativeAgentSession(
  sessions: readonly AgentSessionMeta[],
  updated: AgentSessionMeta,
): AgentSessionMeta[] {
  const incomingRevision = updated.revision ?? 0
  const index = sessions.findIndex((session) => session.id === updated.id)
  if (index < 0) return [updated, ...sessions]

  const current = sessions[index]
  if (!current) return [...sessions]
  if ((current.revision ?? 0) > incomingRevision) return [...sessions]

  const next = [...sessions]
  next[index] = updated
  return next
}

export function sessionRevision(
  remoteSession: AgentSessionMeta | undefined,
  legacySession: AgentSessionMeta | undefined,
): number | undefined {
  return remoteSession?.revision ?? legacySession?.revision
}
