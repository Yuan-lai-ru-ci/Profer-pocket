import { beforeEach, describe, expect, test } from 'bun:test'
import { applyAgentEvent, type AgentStreamState } from '@/atoms/agent-atoms'
import {
  clearAuthoritativeContextWindow,
  getAuthoritativeContextWindow,
  hydrateAgentRuntimeContexts,
  normalizeAgentRuntimeContextSnapshots,
  rememberAuthoritativeContextWindow,
  resetAuthoritativeContextWindows,
} from './agent-runtime-context'

function state(patch: Partial<AgentStreamState> = {}): AgentStreamState {
  return { running: true, content: '', toolActivities: [], ...patch }
}

describe('Pocket 权威上下文窗口快照解析', () => {
  beforeEach(() => resetAuthoritativeContextWindows())

  test('Given 主端快照 When 解析 Then 保留合法条目并补全 updatedAt', () => {
    const snapshots = normalizeAgentRuntimeContextSnapshots([
      { sessionId: 's1', contextWindow: 1_050_000, updatedAt: 111 },
      { sessionId: 's2', contextWindow: 200_000 },
    ])

    expect(snapshots).toHaveLength(2)
    expect(snapshots[0]).toEqual({ sessionId: 's1', contextWindow: 1_050_000, updatedAt: 111 })
    expect(snapshots[1]!.sessionId).toBe('s2')
    expect(snapshots[1]!.updatedAt).toBeGreaterThan(0)
  })

  test('Given 旧服务端/异常数据 When 解析 Then 静默丢弃且不抛错', () => {
    expect(normalizeAgentRuntimeContextSnapshots(undefined)).toEqual([])
    expect(normalizeAgentRuntimeContextSnapshots({ ok: false, error: '未知指令' })).toEqual([])
    expect(normalizeAgentRuntimeContextSnapshots([
      null,
      'nope',
      { sessionId: '', contextWindow: 200_000 },
      { contextWindow: 200_000 },
      { sessionId: 's1', contextWindow: 0 },
      { sessionId: 's2', contextWindow: Number.NaN },
      { sessionId: 's3', contextWindow: -1 },
    ])).toEqual([])
  })
})

describe('Pocket 权威上下文窗口水合', () => {
  beforeEach(() => resetAuthoritativeContextWindows())

  test('Given 本地按模型名推断的 200K When 主端返回 1.05M Then 覆盖分母并刷新时效', () => {
    const previous = new Map([['terra', state({ inputTokens: 30_000, contextWindow: 200_000, usageUpdatedAt: 1 })]])

    const next = hydrateAgentRuntimeContexts(previous, [
      { sessionId: 'terra', contextWindow: 1_050_000, updatedAt: 999 },
    ])

    const hydrated = next.get('terra')!
    expect(hydrated.contextWindow).toBe(1_050_000)
    expect(hydrated.usageUpdatedAt).toBe(999)
    // 不得改动 token 与运行态
    expect(hydrated.inputTokens).toBe(30_000)
    expect(hydrated.running).toBe(true)
    expect(getAuthoritativeContextWindow('terra')).toBe(1_050_000)
  })

  test('Given 无本地流状态 When 水合 Then 默认不凭空造条目（避免空闲会话被激活）', () => {
    const previous = new Map<string, AgentStreamState>()

    const next = hydrateAgentRuntimeContexts(previous, [
      { sessionId: 'idle', contextWindow: 200_000, updatedAt: 5 },
    ])

    expect(next).toBe(previous)
    expect(next.size).toBe(0)
  })

  test('Given 显式允许补建 When 水合 Then 只写分母且保持空闲态', () => {
    const next = hydrateAgentRuntimeContexts(new Map<string, AgentStreamState>(), [
      { sessionId: 'idle', contextWindow: 200_000, updatedAt: 5 },
    ], { createIfMissing: true })

    const created = next.get('idle')!
    expect(created.contextWindow).toBe(200_000)
    expect(created.running).toBe(false)
  })

  test('Given 只关注当前会话 When 水合 Then 忽略白名单外的会话', () => {
    const previous = new Map([
      ['a', state()],
      ['b', state()],
    ])

    const next = hydrateAgentRuntimeContexts(previous, [
      { sessionId: 'a', contextWindow: 1_000_000, updatedAt: 1 },
      { sessionId: 'b', contextWindow: 1_000_000, updatedAt: 1 },
    ], { sessionIds: new Set(['b']) })

    expect(next.get('a')!.contextWindow).toBeUndefined()
    expect(next.get('b')!.contextWindow).toBe(1_000_000)
  })

  test('Given 值未变化 When 水合 Then 返回原 Map（避免无意义重渲染）', () => {
    const previous = new Map([['s1', state({ contextWindow: 200_000, usageUpdatedAt: 7 })]])

    const next = hydrateAgentRuntimeContexts(previous, [
      { sessionId: 's1', contextWindow: 200_000, updatedAt: 7 },
    ])

    expect(next).toBe(previous)
  })

  test('Given 权威值已水合 When 流式 usage_update 携带推断 fallback Then 不覆盖权威分母', () => {
    const hydrated = hydrateAgentRuntimeContexts(
      new Map([['terra', state({ inputTokens: 12_000 })]]),
      [{ sessionId: 'terra', contextWindow: 1_050_000, updatedAt: 1 }],
    )

    const after = applyAgentEvent(hydrated.get('terra')!, {
      type: 'usage_update',
      usage: { inputTokens: 13_000, contextWindow: 200_000 },
    })

    expect(after.contextWindow).toBe(1_050_000)
    expect(after.inputTokens).toBe(13_000)
    expect(getAuthoritativeContextWindow('terra')).toBe(1_050_000)
  })

  test('Given run 结束 When 清除权威登记 Then 下个 run 回到原有推断语义', () => {
    rememberAuthoritativeContextWindow('terra', 1_050_000)
    clearAuthoritativeContextWindow('terra')

    expect(getAuthoritativeContextWindow('terra')).toBeUndefined()
    const after = applyAgentEvent(state(), {
      type: 'usage_update',
      usage: { inputTokens: 1_000, contextWindow: 200_000 },
    })
    expect(after.contextWindow).toBe(200_000)
  })

  test('Given 非法权威值 When 登记 Then 不写入', () => {
    expect(rememberAuthoritativeContextWindow('s1', 0)).toBe(false)
    expect(rememberAuthoritativeContextWindow('s1', Number.NaN)).toBe(false)
    expect(rememberAuthoritativeContextWindow('s1', '200000')).toBe(false)
    expect(getAuthoritativeContextWindow('s1')).toBeUndefined()
  })
})
