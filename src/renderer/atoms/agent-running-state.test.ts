import { describe, expect, test } from 'bun:test'
import { createStore } from 'jotai'
import {
  agentRunningSessionIdsAtom,
  agentSessionIndicatorMapAtom,
  agentStreamingStatesAtom,
  allPendingPermissionRequestsAtom,
  applyAgentEvent,
  isAgentStreamActive,
  shouldClearInactiveAgentStreamState,
  type AgentStreamState,
} from './agent-atoms'

function makeState(overrides: Partial<AgentStreamState> = {}): AgentStreamState {
  return {
    running: true,
    content: '',
    toolActivities: [],
    ...overrides,
  }
}

describe('Agent 用户可见活跃态', () => {
  test('backgroundWaiting 仍被视为活跃，并驱动会话运行指示', () => {
    const store = createStore()
    store.set(agentStreamingStatesAtom, new Map([
      ['background', makeState({ running: false, backgroundWaiting: true })],
      ['idle', makeState({ running: false })],
    ]))

    expect(isAgentStreamActive(store.get(agentStreamingStatesAtom).get('background'))).toBe(true)
    expect(isAgentStreamActive(store.get(agentStreamingStatesAtom).get('idle'))).toBe(false)
    expect(store.get(agentRunningSessionIdsAtom)).toEqual(new Set(['background']))
    expect(store.get(agentSessionIndicatorMapAtom).get('background')).toBe('running')

    store.set(allPendingPermissionRequestsAtom, new Map([['background', [{} as never]]]))
    expect(store.get(agentSessionIndicatorMapAtom).get('background')).toBe('blocked')
  })

  test('run_resumed 从后台等待恢复 running 并保留同一 startedAt', () => {
    const startedAt = 1_234
    const resumed = applyAgentEvent(
      makeState({ running: false, backgroundWaiting: true, startedAt }),
      { type: 'run_resumed' },
    )

    expect(resumed).toMatchObject({ running: true, backgroundWaiting: false, startedAt })
  })
})

describe('inactive 快照对账资格', () => {
  test('请求期间收到同 session 新事件时，inactive 快照不能清除本地活跃态', () => {
    expect(shouldClearInactiveAgentStreamState(makeState(), false, 4, 5)).toBe(false)
  })

  test('请求期间乐观开始新 run、尚未收到 Agent event 时，inactive 快照不能清除新状态', () => {
    const snapshotStartedAt = 1_000
    const optimisticNewRun = makeState({ startedAt: 2_000 })

    expect(shouldClearInactiveAgentStreamState(optimisticNewRun, false, 4, 4, snapshotStartedAt)).toBe(false)
  })

  test('没有新事件且服务端 inactive 时，允许清理 running 与后台等待态', () => {
    expect(shouldClearInactiveAgentStreamState(makeState({ startedAt: 1_000 }), false, 4, 4, 1_000)).toBe(true)
    expect(shouldClearInactiveAgentStreamState(makeState({ running: false, backgroundWaiting: true, startedAt: 1_000 }), false, 4, 4, 1_000)).toBe(true)
  })

  test('没有 startedAt 的旧状态沿用既有 inactive 清理行为', () => {
    expect(shouldClearInactiveAgentStreamState(makeState(), false, 4, 4)).toBe(true)
  })

  test('session revision 独立：一个 session 更新不妨碍另一个 session 的陈旧状态收敛', () => {
    const snapshotRevisions = new Map([['a', 1], ['b', 1]])
    const currentRevisions = new Map([['a', 2], ['b', 1]])

    expect(shouldClearInactiveAgentStreamState(makeState(), false, snapshotRevisions.get('a')!, currentRevisions.get('a')!)).toBe(false)
    expect(shouldClearInactiveAgentStreamState(makeState(), false, snapshotRevisions.get('b')!, currentRevisions.get('b')!)).toBe(true)
  })

  test('远端仍 active 时不清理本地状态，供统一快照恢复逻辑处理', () => {
    expect(shouldClearInactiveAgentStreamState(makeState(), true, 4, 4)).toBe(false)
  })
})
