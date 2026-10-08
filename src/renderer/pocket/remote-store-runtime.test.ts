import { describe, expect, test } from 'bun:test'
import { createRemoteStore, initialRemoteStoreState, selectRemoteRuntime } from './remote-store'

describe('Remote Store runtime', () => {
  test('session_projection 不会激活 running，runtime event 才改变运行态', () => {
    const store = createRemoteStore(initialRemoteStoreState)
    store.dispatch({ type: 'projection_upsert', session: { id: 's1', revision: 1, title: 's', createdAt: 1, updatedAt: 1, agentRuntime: 'pi', permissionMode: 'auto', pinned: false, archived: false, draft: false } })
    expect(selectRemoteRuntime(store.getState(), 's1').status).toBe('idle')
    store.dispatch({ type: 'runtime_event', event: { type: 'run_resumed', sessionId: 's1' }, eventId: 10 })
    expect(selectRemoteRuntime(store.getState(), 's1').status).toBe('running')
  })

  test('重复和乱序事件不会重复计数或覆盖终态', () => {
    const store = createRemoteStore(initialRemoteStoreState)
    store.dispatch({ type: 'runtime_event', event: { type: 'run_completed', sessionId: 's1', resultSubtype: 'success' }, eventId: 20 })
    store.dispatch({ type: 'runtime_event', event: { type: 'run_completed', sessionId: 's1', resultSubtype: 'success' }, eventId: 20 })
    store.dispatch({ type: 'runtime_event', event: { type: 'run_idle', sessionId: 's1' }, eventId: 19 })
    expect(selectRemoteRuntime(store.getState(), 's1').status).toBe('completed')
    expect(Object.keys(store.getState().runtimeEventKeys)).toHaveLength(1)
  })

  test('AskUser、permission、plan pending 独立收敛', () => {
    const store = createRemoteStore(initialRemoteStoreState)
    store.dispatch({ type: 'runtime_event', event: { type: 'permission_request', sessionId: 's1' } })
    store.dispatch({ type: 'runtime_event', event: { type: 'ask_user_request', sessionId: 's1' } })
    store.dispatch({ type: 'runtime_event', event: { type: 'exit_plan_mode_request', sessionId: 's1' } })
    expect(selectRemoteRuntime(store.getState(), 's1').pending).toEqual({ permission: true, askUser: true, plan: true })
  })
})
