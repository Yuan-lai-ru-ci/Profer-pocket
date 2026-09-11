import { describe, expect, test } from 'bun:test'
import {
  checkInteractionVerdict,
  decideDismissAction,
  decideSubmitAction,
  extractPendingSnapshotItems,
  fetchInteractionVerdict,
  resolveInteractionVerdict,
  runDismissFlow,
  runSubmitFlow,
  type InteractionGuard,
} from './interaction-guard'

/** 与服务端 get_pending_interactions 同形状的最小快照 */
const snapshot = {
  permissions: [
    { sessionId: 'session-1', requestId: 'perm-1', toolName: 'Write' },
    { sessionId: 'session-2', requestId: 'perm-2', toolName: 'Bash' },
  ],
  askUsers: [{ sessionId: 'session-1', requestId: 'ask-1' }],
  exitPlans: [{ sessionId: 'session-1', requestId: 'plan-1' }],
}

describe('extractPendingSnapshotItems', () => {
  test('按 kind 取对应数组', () => {
    expect(extractPendingSnapshotItems(snapshot, 'permission')).toEqual(snapshot.permissions)
    expect(extractPendingSnapshotItems(snapshot, 'askUser')).toEqual(snapshot.askUsers)
    expect(extractPendingSnapshotItems(snapshot, 'exitPlan')).toEqual(snapshot.exitPlans)
  })

  test('键缺失或非数组时返回 null（无法判定）', () => {
    expect(extractPendingSnapshotItems({}, 'permission')).toBeNull()
    expect(extractPendingSnapshotItems({ permissions: 'oops' }, 'permission')).toBeNull()
    expect(extractPendingSnapshotItems(null, 'permission')).toBeNull()
    expect(extractPendingSnapshotItems([], 'permission')).toBeNull()
    expect(extractPendingSnapshotItems('snapshot', 'permission')).toBeNull()
  })
})

describe('resolveInteractionVerdict', () => {
  test('快照仍含该 requestId → pending', () => {
    expect(resolveInteractionVerdict(snapshot, 'permission', 'perm-2')).toBe('pending')
    expect(resolveInteractionVerdict(snapshot, 'askUser', 'ask-1')).toBe('pending')
    expect(resolveInteractionVerdict(snapshot, 'exitPlan', 'plan-1')).toBe('pending')
  })

  test('快照不含该 requestId → resolved（已过期）', () => {
    expect(resolveInteractionVerdict(snapshot, 'permission', 'perm-404')).toBe('resolved')
    // 同 session 的其它请求不能互相顶替
    expect(resolveInteractionVerdict(snapshot, 'permission', 'ask-1')).toBe('resolved')
  })

  test('空 requestId / 载荷异常 / 键缺失 → unknown（保留原有行为）', () => {
    expect(resolveInteractionVerdict(snapshot, 'permission', '')).toBe('unknown')
    expect(resolveInteractionVerdict(null, 'permission', 'perm-1')).toBe('unknown')
    expect(resolveInteractionVerdict({}, 'permission', 'perm-1')).toBe('unknown')
    expect(resolveInteractionVerdict({ askUsers: [] }, 'permission', 'perm-1')).toBe('unknown')
  })

  test('空数组快照 = 服务端确认无待处理 → resolved', () => {
    expect(resolveInteractionVerdict({ permissions: [], askUsers: [], exitPlans: [] }, 'permission', 'perm-1'))
      .toBe('resolved')
  })

  test('条目缺少 requestId 字段时不影响判定', () => {
    expect(resolveInteractionVerdict({ permissions: [{ sessionId: 'session-1' }] }, 'permission', 'perm-1'))
      .toBe('resolved')
  })
})

describe('判定决策表', () => {
  test('dismiss：只有 resolved 才降级为 local-only（绝不 stopAgent）', () => {
    expect(decideDismissAction('resolved')).toBe('local-only')
    expect(decideDismissAction('pending')).toBe('stop-agent')
    expect(decideDismissAction('unknown')).toBe('stop-agent')
  })

  test('submit：只有 resolved 才跳过回传', () => {
    expect(decideSubmitAction('resolved')).toBe('local-only')
    expect(decideSubmitAction('pending')).toBe('respond')
    expect(decideSubmitAction('unknown')).toBe('respond')
  })

  test('resolved 判定下绝不返回 stop-agent（回归 R8 事故根因）', () => {
    const verdicts = ['pending', 'resolved', 'unknown'] as const
    for (const verdict of verdicts) {
      if (verdict === 'resolved') expect(decideDismissAction(verdict)).not.toBe('stop-agent')
    }
  })
})

describe('fetchInteractionVerdict', () => {
  test('透传 sessionId 并返回判定', async () => {
    const seen: Array<string | undefined> = []
    const source = {
      getPendingInteractions: async (sessionId?: string): Promise<unknown> => {
        seen.push(sessionId)
        return snapshot
      },
    }

    await expect(fetchInteractionVerdict(source, 'permission', 'perm-1', 'session-1')).resolves.toBe('pending')
    await expect(fetchInteractionVerdict(source, 'permission', 'perm-404', 'session-1')).resolves.toBe('resolved')
    expect(seen).toEqual(['session-1', 'session-1'])
  })

  test('client 为空（连接未建立）→ unknown，不 reject', async () => {
    await expect(fetchInteractionVerdict(null, 'permission', 'perm-1')).resolves.toBe('unknown')
  })

  test('拉取失败（旧服务端不支持 / 超时）→ unknown', async () => {
    const source = {
      getPendingInteractions: async (): Promise<unknown> => Promise.reject(new Error('未知指令')),
    }

    await expect(fetchInteractionVerdict(source, 'permission', 'perm-1')).resolves.toBe('unknown')
  })
})

describe('checkInteractionVerdict', () => {
  test('未注入守卫（桌面端）→ unknown', async () => {
    await expect(checkInteractionVerdict(undefined, 'askUser', 'ask-1')).resolves.toBe('unknown')
  })

  test('守卫正常返回时透传', async () => {
    const guard: InteractionGuard = async (kind, requestId) => (kind === 'askUser' && requestId === 'ask-1' ? 'resolved' : 'pending')

    await expect(checkInteractionVerdict(guard, 'askUser', 'ask-1')).resolves.toBe('resolved')
    await expect(checkInteractionVerdict(guard, 'askUser', 'ask-2')).resolves.toBe('pending')
  })

  test('守卫抛异常时降级 unknown（不打断横幅交互）', async () => {
    const guard: InteractionGuard = async () => Promise.reject(new Error('boom'))

    await expect(checkInteractionVerdict(guard, 'askUser', 'ask-1')).resolves.toBe('unknown')
  })
})

describe('runDismissFlow', () => {
  const createGuard = (verdict: 'pending' | 'resolved' | 'unknown'): InteractionGuard => async () => verdict

  /** 采集一次关闭流程的实际副作用 */
  async function run(guard: InteractionGuard | undefined, requestId: string | null = 'ask-1') {
    const calls = { stopped: 0, notified: 0 }
    const action = await runDismissFlow({
      guard,
      kind: 'askUser',
      requestId,
      requestStop: () => { calls.stopped += 1 },
      notifyResolved: () => { calls.notified += 1 },
    })
    return { action, ...calls }
  }

  test('resolved：只轻提示，绝不调用 requestStop（R8 事故根因回归）', async () => {
    expect(await run(createGuard('resolved'))).toEqual({ action: 'local-only', stopped: 0, notified: 1 })
  })

  test('pending：沿用原有行为（停止本轮）', async () => {
    expect(await run(createGuard('pending'))).toEqual({ action: 'stop-agent', stopped: 1, notified: 0 })
  })

  test('unknown / 未注入守卫：保留原有行为，不静默改变语义', async () => {
    expect(await run(createGuard('unknown'))).toEqual({ action: 'stop-agent', stopped: 1, notified: 0 })
    expect(await run(undefined)).toEqual({ action: 'stop-agent', stopped: 1, notified: 0 })
  })

  test('requestId 为空按 unknown 处理（无法判定 → 原行为）', async () => {
    expect(await run(createGuard('resolved'), null)).toEqual({ action: 'stop-agent', stopped: 1, notified: 0 })
  })

  test('守卫抛异常时降级 unknown，不把异常丢给 UI', async () => {
    const throwing: InteractionGuard = async () => Promise.reject(new Error('boom'))

    expect(await run(throwing)).toEqual({ action: 'stop-agent', stopped: 1, notified: 0 })
  })
})

describe('runSubmitFlow', () => {
  const createGuard = (verdict: 'pending' | 'resolved' | 'unknown'): InteractionGuard => async () => verdict

  async function run(guard: InteractionGuard | undefined) {
    const calls = { submitted: 0, stale: 0 }
    const action = await runSubmitFlow({
      guard,
      kind: 'permission',
      requestId: 'perm-1',
      submit: async () => { calls.submitted += 1 },
      onStale: () => { calls.stale += 1 },
    })
    return { action, ...calls }
  }

  test('resolved：不回传主端（避开「请求不存在或已处理」），只做本地收敛', async () => {
    expect(await run(createGuard('resolved'))).toEqual({ action: 'local-only', submitted: 0, stale: 1 })
  })

  test('pending：正常回传', async () => {
    expect(await run(createGuard('pending'))).toEqual({ action: 'respond', submitted: 1, stale: 0 })
  })

  test('unknown：仍回传（让主端返回 ok:false，与改动前一致）', async () => {
    expect(await run(createGuard('unknown'))).toEqual({ action: 'respond', submitted: 1, stale: 0 })
    expect(await run(undefined)).toEqual({ action: 'respond', submitted: 1, stale: 0 })
  })

  test('回传失败时异常向上抛（由横幅 catch 记录，不误报成功）', async () => {
    await expect(runSubmitFlow({
      guard: createGuard('pending'),
      kind: 'askUser',
      requestId: 'ask-1',
      submit: async () => Promise.reject(new Error('提问请求不存在或已处理')),
      onStale: () => undefined,
    })).rejects.toThrow('提问请求不存在或已处理')
  })
})
