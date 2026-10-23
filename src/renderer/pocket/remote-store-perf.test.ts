import { describe, expect, test } from 'bun:test'
import { createRemoteStore, initialRemoteStoreState, reduceRemoteStore, selectRemoteSessions } from './remote-store'

describe('Remote Store 热路径', () => {
  test('无 eventId 的运行时事件去重表有上限，只保留最近窗口', () => {
    const store = createRemoteStore(initialRemoteStoreState)
    for (let i = 0; i < 700; i++) {
      store.dispatch({ type: 'runtime_event', event: { type: 'error', sessionId: `s${i}`, message: 'x' } })
    }
    const keys = Object.keys(store.getState().runtimeEventKeys)
    expect(keys.length).toBeLessThanOrEqual(512)
    expect(keys).toContain('s699:error:')
    expect(keys).not.toContain('s0:error:')
  })

  test('sessions 未变化时 selectRemoteSessions 复用同一排序结果', () => {
    let state = reduceRemoteStore(initialRemoteStoreState, {
      type: 'projection_upsert',
      session: { id: 'a', revision: 1, title: 'a', createdAt: 1, updatedAt: 1, agentRuntime: 'pi', permissionMode: 'auto', pinned: false, archived: false, draft: false },
    })
    const first = selectRemoteSessions(state)
    const afterRuntime = reduceRemoteStore(state, { type: 'runtime_event', event: { type: 'run_resumed', sessionId: 'a' } })
    expect(selectRemoteSessions(afterRuntime)).toBe(first)
    state = reduceRemoteStore(afterRuntime, {
      type: 'projection_upsert',
      session: { id: 'b', revision: 1, title: 'b', createdAt: 1, updatedAt: 2, agentRuntime: 'pi', permissionMode: 'auto', pinned: false, archived: false, draft: false },
    })
    expect(selectRemoteSessions(state).map((session) => session.id)).toEqual(['b', 'a'])
  })
})
