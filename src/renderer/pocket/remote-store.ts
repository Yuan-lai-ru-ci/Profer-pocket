import type {
  AgentSessionMeta,
  AgentSessionUiProjection,
  CommandResult,
  RemoteCommandState,
  RemoteConnectionPhase,
} from '@profer/shared'

export type RemoteRuntimeStatus = 'running' | 'idle' | 'completed' | 'stopped' | 'error'

export interface RemoteRuntimeState {
  status: RemoteRuntimeStatus
  updatedAt: number
  resultSubtype?: string
  error?: string
  stoppedByUser?: boolean
  pending: {
    permission: boolean
    askUser: boolean
    plan: boolean
  }
}

export type RemoteRuntimeEvent =
  | { type: 'run_resumed'; sessionId: string }
  | { type: 'run_idle'; sessionId: string }
  | { type: 'run_completed'; sessionId: string; stoppedByUser?: boolean; resultSubtype?: string; resultErrors?: string[] }
  | { type: 'error'; sessionId: string; message: string }
  | { type: 'permission_request'; sessionId: string }
  | { type: 'permission_resolved'; sessionId: string }
  | { type: 'ask_user_request'; sessionId: string }
  | { type: 'ask_user_resolved'; sessionId: string }
  | { type: 'exit_plan_mode_request'; sessionId: string }
  | { type: 'exit_plan_mode_resolved'; sessionId: string }
  | { type: 'plan_mode_changed'; sessionId: string; active: boolean }

export interface RemoteStoreState {
  sessions: Record<string, AgentSessionMeta>
  tombstones: Record<string, number>
  runtime: Record<string, RemoteRuntimeState>
  runtimeEventKeys: Record<string, true>
  runtimeLastEventId: number
  connection: {
    phase: RemoteConnectionPhase
    cursor: number
    serverInstanceId?: string
    replayComplete: boolean
    requiresSnapshot: boolean
  }
  commands: Record<string, RemoteCommandState>
}

export type RemoteStoreAction =
  | { type: 'connection'; phase: RemoteConnectionPhase }
  | { type: 'hello'; serverInstanceId: string; latestEventId?: number | null }
  | { type: 'snapshot'; sessions: readonly AgentSessionMeta[]; cursor?: number | null }
  | { type: 'replay_started' }
  | { type: 'replay_completed'; cursor?: number | null; requiresSnapshot: boolean }
  | { type: 'projection_upsert'; session: AgentSessionUiProjection }
  | { type: 'session_snapshot_upsert'; session: AgentSessionMeta }
  | { type: 'projection_delete'; sessionId: string; revision: number }
  | { type: 'runtime_event'; event: RemoteRuntimeEvent; eventId?: number }
  | { type: 'command_pending'; commandId: string; expectedRevision?: number; sessionId?: string }
  | { type: 'command_result'; result: CommandResult }

export const initialRemoteStoreState: RemoteStoreState = {
  sessions: {},
  tombstones: {},
  runtime: {},
  runtimeEventKeys: {},
  runtimeLastEventId: 0,
  connection: {
    phase: 'disconnected',
    cursor: 0,
    replayComplete: false,
    requiresSnapshot: false,
  },
  commands: {},
}

function revisionOf(session: AgentSessionMeta): number {
  return session.revision ?? 0
}

function projectionToSession(projection: AgentSessionUiProjection): AgentSessionMeta {
  return {
    id: projection.id,
    revision: projection.revision,
    title: projection.title,
    createdAt: projection.createdAt,
    updatedAt: projection.updatedAt,
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
}

function mergeSession(
  sessions: Record<string, AgentSessionMeta>,
  tombstones: Record<string, number>,
  session: AgentSessionMeta,
): Record<string, AgentSessionMeta> {
  const incomingRevision = revisionOf(session)
  if (incomingRevision <= (tombstones[session.id] ?? -1)) return sessions
  const current = sessions[session.id]
  // 同 revision 只接受完全相同/幂等回放；不允许旧 projection 的字段覆盖当前乐观或权威状态。
  if (current && incomingRevision <= revisionOf(current)) return sessions
  return { ...sessions, [session.id]: current ? { ...current, ...session } : session }
}

export function reduceRemoteStore(
  state: RemoteStoreState,
  action: RemoteStoreAction,
): RemoteStoreState {
  switch (action.type) {
    case 'connection':
      return { ...state, connection: { ...state.connection, phase: action.phase } }
    case 'hello': {
      const instanceChanged = state.connection.serverInstanceId !== undefined
        && state.connection.serverInstanceId !== action.serverInstanceId
      return {
        ...state,
        connection: {
          ...state.connection,
          phase: instanceChanged ? 'snapshot_loading' : 'replaying',
          cursor: instanceChanged ? 0 : state.connection.cursor,
          serverInstanceId: action.serverInstanceId,
          replayComplete: false,
          requiresSnapshot: instanceChanged,
        },
      }
    }
    case 'snapshot': {
      const nextSessions: Record<string, AgentSessionMeta> = {}
      for (const session of action.sessions) {
        const current = state.sessions[session.id]
        if (!current || revisionOf(session) > revisionOf(current)) {
          nextSessions[session.id] = current ? { ...current, ...session } : session
        } else {
          nextSessions[session.id] = current
        }
      }
      const nextTombstones = { ...state.tombstones }
      for (const session of action.sessions) {
        if ((nextTombstones[session.id] ?? -1) < revisionOf(session)) delete nextTombstones[session.id]
      }
      return {
        ...state,
        sessions: nextSessions,
        tombstones: nextTombstones,
        connection: {
          ...state.connection,
          phase: 'replaying',
          cursor: action.cursor ?? state.connection.cursor,
          replayComplete: false,
          requiresSnapshot: false,
        },
      }
    }
    case 'replay_started':
      return { ...state, connection: { ...state.connection, phase: 'replaying', replayComplete: false } }
    case 'replay_completed':
      return {
        ...state,
        connection: {
          ...state.connection,
          phase: action.requiresSnapshot ? 'snapshot_loading' : 'live',
          cursor: action.cursor ?? state.connection.cursor,
          replayComplete: !action.requiresSnapshot,
          requiresSnapshot: action.requiresSnapshot,
        },
      }
    case 'projection_upsert':
      return {
        ...state,
        sessions: mergeSession(state.sessions, state.tombstones, projectionToSession(action.session)),
      }
    case 'runtime_event': {
      if (action.eventId !== undefined && action.eventId <= state.runtimeLastEventId) return state
      const eventKey = action.eventId === undefined ? `${action.event.sessionId}:${action.event.type}:${action.event.type === 'run_completed' ? action.event.resultSubtype ?? '' : ''}` : String(action.eventId)
      if (state.runtimeEventKeys[eventKey]) return state
      const current = state.runtime[action.event.sessionId] ?? {
        status: 'idle' as const,
        updatedAt: 0,
        pending: { permission: false, askUser: false, plan: false },
      }
      const next = { ...current, updatedAt: Date.now() }
      switch (action.event.type) {
        case 'run_resumed':
          next.status = 'running'
          break
        case 'run_idle':
          next.status = 'idle'
          break
        case 'run_completed':
          next.status = action.event.stoppedByUser ? 'stopped' : action.event.resultErrors?.length ? 'error' : 'completed'
          next.stoppedByUser = action.event.stoppedByUser
          next.resultSubtype = action.event.resultSubtype
          next.error = action.event.resultErrors?.[0]
          break
        case 'error':
          next.status = 'error'
          next.error = action.event.message
          break
        case 'permission_request':
          next.pending = { ...next.pending, permission: true }
          break
        case 'permission_resolved':
          next.pending = { ...next.pending, permission: false }
          break
        case 'ask_user_request':
          next.pending = { ...next.pending, askUser: true }
          break
        case 'ask_user_resolved':
          next.pending = { ...next.pending, askUser: false }
          break
        case 'exit_plan_mode_request':
          next.pending = { ...next.pending, plan: true }
          break
        case 'exit_plan_mode_resolved':
          next.pending = { ...next.pending, plan: false }
          break
        case 'plan_mode_changed':
          next.pending = { ...next.pending, plan: action.event.active }
          break
      }
      return {
        ...state,
        runtime: { ...state.runtime, [action.event.sessionId]: next },
        runtimeEventKeys: { ...state.runtimeEventKeys, [eventKey]: true },
        runtimeLastEventId: action.eventId ?? state.runtimeLastEventId,
      }
    }
    case 'session_snapshot_upsert':
      const next = { ...state, sessions: mergeSession(state.sessions, state.tombstones, action.session) }
      return next
    case 'projection_delete': {
      const currentTombstone = state.tombstones[action.sessionId] ?? -1
      if (action.revision <= currentTombstone) return state
      const current = state.sessions[action.sessionId]
      if (current && revisionOf(current) > action.revision) return state
      const nextSessions = { ...state.sessions }
      delete nextSessions[action.sessionId]
      return {
        ...state,
        sessions: nextSessions,
        tombstones: { ...state.tombstones, [action.sessionId]: action.revision },
      }
    }
    case 'command_pending':
      return {
        ...state,
        commands: {
          ...state.commands,
          [action.commandId]: {
            commandId: action.commandId,
            expectedRevision: action.expectedRevision,
            sessionId: action.sessionId,
            status: 'pending',
          },
        },
      }
    case 'command_result': {
      const commandId = action.result.commandId
      const existing = state.commands[commandId]
      if (existing && existing.status !== 'pending') return state
      return {
        ...state,
        commands: {
          ...state.commands,
          [commandId]: action.result.ok
            ? { commandId, expectedRevision: existing?.expectedRevision, status: 'succeeded' }
            : { commandId, expectedRevision: existing?.expectedRevision, status: 'failed', error: action.result.error },
        },
      }
    }
  }
}

export function selectRemoteSessions(state: RemoteStoreState): AgentSessionMeta[] {
  return Object.values(state.sessions).sort((a, b) => b.updatedAt - a.updatedAt)
}

export function selectRemoteSession(state: RemoteStoreState, sessionId: string): AgentSessionMeta | undefined {
  return state.sessions[sessionId]
}

export function selectRemoteCommand(state: RemoteStoreState, commandId: string): RemoteCommandState | undefined {
  return state.commands[commandId]
}

export function selectRemoteRuntime(state: RemoteStoreState, sessionId: string): RemoteRuntimeState {
  return state.runtime[sessionId] ?? {
    status: 'idle',
    updatedAt: 0,
    pending: { permission: false, askUser: false, plan: false },
  }
}

export function createRemoteStore(initial: RemoteStoreState = initialRemoteStoreState) {
  let state = initial
  const listeners = new Set<(state: RemoteStoreState) => void>()
  return {
    getState: () => state,
    dispatch: (action: RemoteStoreAction) => {
      const next = reduceRemoteStore(state, action)
      if (next === state) return state
      state = next
      listeners.forEach((listener) => listener(state))
      return state
    },
    subscribe: (listener: (state: RemoteStoreState) => void) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
