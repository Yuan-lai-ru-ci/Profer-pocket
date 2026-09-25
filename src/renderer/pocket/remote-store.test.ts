import { describe, expect, test } from 'bun:test'
import type { AgentSessionUiProjection } from '@profer/shared'
import {
  initialRemoteStoreState,
  reduceRemoteStore,
  selectRemoteCommand,
  selectRemoteSessions,
} from './remote-store'

function projection(id: string, revision: number): AgentSessionUiProjection {
  return {
    schemaVersion: 1,
    id,
    revision,
    title: `session-${id}`,
    createdAt: 1,
    updatedAt: revision,
    channelId: null,
    modelId: null,
    agentRuntime: 'claude',
    permissionMode: 'auto',
    presetId: null,
    presetReference: null,
    openAIThinkingLevel: null,
    codexFastMode: false,
    autoQueueSendEnabled: true,
    workspaceId: null,
    pinned: false,
    archived: false,
    draft: false,
    parentSessionId: null,
    rootSessionId: null,
    sourceDelegationId: null,
    explorationParentSessionId: null,
    explorationSourceMessageId: null,
    explorationSourceLabel: null,
    delegationRole: null,
    delegationStatus: null,
    delegationDepth: null,
    sourceAutomationId: null,
    automationGraduated: false,
    completedButUnconfirmed: false,
    stoppedByUser: false,
    lastInterruptReason: null,
    lastInterruptLabel: null,
    lastInterruptAt: null,
  }
}

describe('remote store reducer', () => {
  test('drops stale projections and does not resurrect a tombstone', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, { type: 'projection_upsert', session: projection('a', 2) })
    state = reduceRemoteStore(state, { type: 'projection_delete', sessionId: 'a', revision: 3 })
    state = reduceRemoteStore(state, { type: 'projection_upsert', session: projection('a', 2) })
    expect(state.sessions.a).toBeUndefined()
    expect(state.tombstones.a).toBe(3)
  })

  test('deduplicates command results and keeps the first terminal outcome', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, { type: 'command_pending', commandId: 'c1', expectedRevision: 4, sessionId: 'a' })
    state = reduceRemoteStore(state, { type: 'command_pending', commandId: 'c1', expectedRevision: 4, sessionId: 'a' })
    expect(selectRemoteCommand(state, 'c1')?.status).toBe('pending')
    state = reduceRemoteStore(state, { type: 'command_result', result: { ok: true, commandId: 'c1', data: null } })
    state = reduceRemoteStore(state, { type: 'command_result', result: { ok: false, commandId: 'c1', error: 'late' } })
    expect(state.commands.c1?.status).toBe('succeeded')
  })

  test('成功回执后的 session projection 以桌面权威字段覆盖本地临时状态', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, { type: 'projection_upsert', session: projection('a', 2) })
    state = reduceRemoteStore(state, {
      type: 'session_snapshot_upsert',
      session: {
        id: 'a',
        title: 'authoritative',
        createdAt: 1,
        updatedAt: 3,
        revision: 3,
        permissionMode: 'plan',
        modelId: 'model-authoritative',
        agentRuntime: 'pi',
      },
    })
    expect(state.sessions.a?.title).toBe('authoritative')
    expect(state.sessions.a?.permissionMode).toBe('plan')
    expect(state.sessions.a?.modelId).toBe('model-authoritative')
    expect(state.sessions.a?.agentRuntime).toBe('pi')
    expect(state.sessions.a?.revision).toBe(3)
  })

  test('同 revision 的迟到旧 projection 不覆盖当前权限状态', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, { type: 'session_snapshot_upsert', session: {
      id: 'a', title: 'latest', createdAt: 1, updatedAt: 4, revision: 4, permissionMode: 'bypassPermissions',
    } })
    state = reduceRemoteStore(state, { type: 'projection_upsert', session: { ...projection('a', 4), permissionMode: 'auto', title: 'stale' } })
    expect(state.sessions.a?.title).toBe('latest')
    expect(state.sessions.a?.permissionMode).toBe('bypassPermissions')
  })
  test('REVISION_CONFLICT 失败不覆盖旧状态，随后只接受更高 revision 的恢复快照', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, { type: 'projection_upsert', session: projection('a', 4) })
    state = reduceRemoteStore(state, { type: 'command_pending', commandId: 'conflict', expectedRevision: 4, sessionId: 'a' })
    state = reduceRemoteStore(state, {
      type: 'command_result',
      result: {
        ok: false,
        commandId: 'conflict',
        error: '会话状态已更新，请刷新后重试',
        conflict: { code: 'REVISION_CONFLICT', expectedRevision: 4, actualRevision: 5, sessionId: 'a' },
      },
    })
    expect(state.sessions.a?.revision).toBe(4)
    expect(selectRemoteCommand(state, 'conflict')?.status).toBe('failed')
    state = reduceRemoteStore(state, {
      type: 'session_snapshot_upsert',
      session: { id: 'a', title: 'remote-newer', createdAt: 1, updatedAt: 5, revision: 5, permissionMode: 'auto' },
    })
    expect(state.sessions.a?.title).toBe('remote-newer')
    expect(state.sessions.a?.revision).toBe(5)
  })

  test('moves through snapshot and replay phases, and resets cursor on instance change', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, { type: 'hello', serverInstanceId: 'one' })
    expect(state.connection.phase).toBe('replaying')
    state = reduceRemoteStore(state, { type: 'replay_completed', cursor: 10, requiresSnapshot: false })
    expect(state.connection.phase).toBe('live')
    state = reduceRemoteStore(state, { type: 'hello', serverInstanceId: 'two', latestEventId: 2 })
    expect(state.connection.phase).toBe('snapshot_loading')
    expect(state.connection.cursor).toBe(0)
  })

  test('selectors return revision-arbitrated sessions in freshness order', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, { type: 'projection_upsert', session: projection('old', 1) })
    state = reduceRemoteStore(state, { type: 'projection_upsert', session: projection('new', 4) })
    expect(selectRemoteSessions(state).map((session) => session.id)).toEqual(['new', 'old'])
  })
})
