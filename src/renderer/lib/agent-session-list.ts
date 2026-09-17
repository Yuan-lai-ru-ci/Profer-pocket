import type { AgentSessionMeta, AgentSessionUiProjection } from '@profer/shared'

/** 按最近更新时间排序 Agent 会话，保持与主进程 listAgentSessions 一致。 */
export function sortAgentSessionsByUpdatedAtDesc(
  sessions: readonly AgentSessionMeta[],
): AgentSessionMeta[] {
  return [...sessions].sort((a, b) => b.updatedAt - a.updatedAt)
}

/** 用后端返回的新元数据替换本地条目，并按最近更新时间重新排序。 */
export function replaceAgentSessionInFreshnessOrder(
  sessions: readonly AgentSessionMeta[],
  updated: AgentSessionMeta,
): AgentSessionMeta[] {
  const others = sessions.filter((session) => session.id !== updated.id)
  return sortAgentSessionsByUpdatedAtDesc([updated, ...others])
}

/**
 * 只接受更高实体 revision 的完整安全投影；同版本幂等、旧版本丢弃。
 * projection 只替换权威安全字段，桌面附加目录/文件等本地字段保留。
 */
export function upsertAgentSessionProjection(
  sessions: readonly AgentSessionMeta[],
  projection: AgentSessionUiProjection,
  tombstoneRevision = -1,
): AgentSessionMeta[] {
  const existing = sessions.find((session) => session.id === projection.id)
  const incomingRevision = projection.revision
  if (incomingRevision <= tombstoneRevision) return [...sessions]
  if (existing && incomingRevision < (existing.revision ?? 0)) return [...sessions]

  const safeMeta: AgentSessionMeta = {
    ...(existing ?? {}),
    id: projection.id,
    title: projection.title,
    createdAt: projection.createdAt,
    updatedAt: projection.updatedAt,
    revision: incomingRevision,
    channelId: projection.channelId ?? undefined,
    modelId: projection.modelId ?? undefined,
    agentRuntime: projection.agentRuntime,
    permissionMode: projection.permissionMode,
    presetId: projection.presetId ?? undefined,
    presetReference: projection.presetReference ?? undefined,
    openAIThinkingLevel: projection.openAIThinkingLevel ?? undefined,
    codexFastMode: projection.codexFastMode,
    autoQueueSendEnabled: projection.autoQueueSendEnabled,
    workspaceId: projection.workspaceId ?? undefined,
    pinned: projection.pinned,
    archived: projection.archived,
    draft: projection.draft,
    parentSessionId: projection.parentSessionId ?? undefined,
    rootSessionId: projection.rootSessionId ?? undefined,
    sourceDelegationId: projection.sourceDelegationId ?? undefined,
    // 探索血缘：实时 session_updated 事件也走这条白名单，缺了这三行会把探索分支
    // 裁剪成「独立会话」（且不会触发全量刷新兜底，见 update 工作包 design.md §4.2）
    explorationParentSessionId: projection.explorationParentSessionId ?? undefined,
    explorationSourceMessageId: projection.explorationSourceMessageId ?? undefined,
    explorationSourceLabel: projection.explorationSourceLabel ?? undefined,
    delegationRole: projection.delegationRole ?? undefined,
    delegationStatus: projection.delegationStatus ?? undefined,
    delegationDepth: projection.delegationDepth ?? undefined,
    sourceAutomationId: projection.sourceAutomationId ?? undefined,
    automationGraduated: projection.automationGraduated,
    completedButUnconfirmed: projection.completedButUnconfirmed,
    stoppedByUser: projection.stoppedByUser,
    lastInterruptReason: projection.lastInterruptReason ?? undefined,
    lastInterruptLabel: projection.lastInterruptLabel ?? undefined,
    lastInterruptAt: projection.lastInterruptAt ?? undefined,
  }
  const others = sessions.filter((session) => session.id !== projection.id)
  return sortAgentSessionsByUpdatedAtDesc([safeMeta, ...others])
}

/** 删除墓碑只影响 revision 不低于墓碑的本地条目；旧 upsert 不能复活它。 */
export function deleteAgentSessionProjection(
  sessions: readonly AgentSessionMeta[],
  sessionId: string,
  revision: number,
): AgentSessionMeta[] {
  const existing = sessions.find((session) => session.id === sessionId)
  if (existing && (existing.revision ?? 0) > revision) return [...sessions]
  return sessions.filter((session) => session.id !== sessionId)
}

/** 仅插入或更新单个会话条目，保留其余条目原样。 */
export function upsertAgentSession(
  sessions: readonly AgentSessionMeta[],
  incoming: AgentSessionMeta,
): AgentSessionMeta[] {
  const existing = sessions.find((session) => session.id === incoming.id)
  if (existing && (incoming.revision ?? 0) < (existing.revision ?? 0)) return [...sessions]
  const merged: AgentSessionMeta = existing ? { ...existing, ...incoming } : incoming
  const others = sessions.filter((session) => session.id !== incoming.id)
  return sortAgentSessionsByUpdatedAtDesc([merged, ...others])
}

/** 把后端权威全量快照合并进本地列表。 */
export function mergeFetchedAgentSessions(
  prev: readonly AgentSessionMeta[],
  fetched: readonly AgentSessionMeta[],
): AgentSessionMeta[] {
  const fetchedIds = new Set(fetched.map((session) => session.id))
  const snapshotWatermark = fetched.reduce(
    (max, session) => Math.max(max, session.updatedAt),
    0,
  )
  const survivingLocalOnly = prev.filter(
    (session) =>
      !fetchedIds.has(session.id) && session.updatedAt >= snapshotWatermark,
  )
  // 以当前列表为基底逐项应用快照，revision 更高的本地 projection 不能被旧回包覆盖；
  // 之后只按快照水位清理确实已删除且不是新写入的本地条目。
  const merged = fetched.reduce(
    (next, session) => upsertAgentSession(next, session),
    [...prev],
  )
  const surviving = merged.filter(
    (session) => fetchedIds.has(session.id) || session.updatedAt >= snapshotWatermark,
  )
  // fetched 为空时没有删除水位，保留本地列表，避免短暂断网/旧服务端误清空。
  return sortAgentSessionsByUpdatedAtDesc(
    fetched.length === 0 ? prev : surviving,
  )
}
