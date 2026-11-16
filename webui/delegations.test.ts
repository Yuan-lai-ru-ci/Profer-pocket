import { describe, expect, test } from 'bun:test'
import type { AgentSessionMeta } from '@profer/shared'
import {
  applyDelegationBlocked,
  applyDelegationSessionUpdate,
  delegationGoalPreview,
  delegationLineDetail,
  delegationSummary,
  normalizeBlockedEvent,
  normalizeDelegations,
  resolveDelegationBlocked,
} from './delegations'

const runningEntry = (delegationId: string, extra: Record<string, unknown> = {}): Record<string, unknown> => ({
  delegationId,
  childSessionId: 'child-1',
  title: '调研登录实现',
  role: 'research',
  status: 'running',
  goal: '找出登录入口',
  startedAt: 100,
  pendingBlockedEvents: [],
  ...extra,
})

const blockedEvent = (extra: Record<string, unknown> = {}): Record<string, unknown> => ({
  id: 'blocked-1',
  delegationId: 'd1',
  childSessionId: 'child-1',
  kind: 'ask_user',
  requestId: 'req-1',
  questions: [{ question: '用哪种登录方式？', options: [] }],
  createdAt: 200,
  ...extra,
})

describe('委派可见性：快照归一化', () => {
  test('Given 运行中的委派带阻塞事件 When 归一化 Then 保留标题、角色与问题', () => {
    const view = normalizeDelegations({
      running: [runningEntry('d1', { pendingBlockedEvents: [blockedEvent()] })],
    })
    expect(view.running).toHaveLength(1)
    expect(view.running[0]).toMatchObject({
      delegationId: 'd1',
      title: '调研登录实现',
      roleLabel: '调研',
      goal: '找出登录入口',
      blocked: [{ id: 'blocked-1', kind: 'ask_user', requestId: 'req-1', question: '用哪种登录方式？' }],
    })
    expect(view.blockedCount).toBe(1)
  })

  test('Given 权限阻塞 When 归一化 Then 带出工具名', () => {
    const view = normalizeDelegations({
      running: [runningEntry('d1', {
        pendingBlockedEvents: [blockedEvent({ kind: 'permission', toolName: 'Bash', questions: undefined })],
      })],
    })
    expect(view.running[0]!.blocked[0]).toMatchObject({ kind: 'permission', toolName: 'Bash' })
    expect(view.running[0]!.blocked[0]!.question).toBeUndefined()
  })

  test('Given 缺 delegationId 的坏条目 When 归一化 Then 丢弃，不让状态条出现空行', () => {
    const view = normalizeDelegations({ running: [{ title: 'x' }, runningEntry('d1')] })
    expect(view.running.map((entry) => entry.delegationId)).toEqual(['d1'])
  })

  test('Given 未知角色 When 归一化 Then 角色文案回退「子任务」', () => {
    const view = normalizeDelegations({ running: [runningEntry('d1', { role: 'weird' })] })
    expect(view.running[0]!.roleLabel).toBe('weird')
    const empty = normalizeDelegations({ running: [runningEntry('d1', { role: '' })] })
    expect(empty.running[0]!.roleLabel).toBe('子任务')
  })

  test('Given 脏值 When 归一化 Then 返回空状态而不是抛错', () => {
    for (const raw of [null, undefined, {}, { running: 'oops' }]) {
      expect(normalizeDelegations(raw)).toEqual({ running: [], blockedCount: 0 })
    }
  })
})

describe('委派可见性：阻塞生命周期', () => {
  test('Given 快照还没刷新到该委派 When 收到阻塞事件 Then 先亮出占位提示，不静默吞掉', () => {
    const view = normalizeDelegations({ running: [] })
    const update = normalizeBlockedEvent({ delegationId: 'd9', blockedEvent: blockedEvent({ delegationId: 'd9' }) })
    const next = applyDelegationBlocked(view, update!)
    expect(next.running).toHaveLength(1)
    expect(next.running[0]).toMatchObject({ delegationId: 'd9', title: '委派子任务' })
    expect(next.blockedCount).toBe(1)
  })

  test('Given 同一阻塞事件重复投递 When 应用 Then 不重复计数', () => {
    const view = normalizeDelegations({ running: [runningEntry('d1')] })
    const update = normalizeBlockedEvent({ delegationId: 'd1', blockedEvent: blockedEvent() })!
    const once = applyDelegationBlocked(view, update)
    const twice = applyDelegationBlocked(once, update)
    expect(twice.blockedCount).toBe(1)
  })

  test('Given 阻塞按事件 id 解除 When 应用 Then 收起「在等你回答」', () => {
    const view = applyDelegationBlocked(
      normalizeDelegations({ running: [runningEntry('d1')] }),
      normalizeBlockedEvent({ delegationId: 'd1', blockedEvent: blockedEvent() })!,
    )
    const resolved = resolveDelegationBlocked(view, { delegationId: 'd1', blockedEventId: 'blocked-1' })
    expect(resolved.blockedCount).toBe(0)
    expect(resolved.running).toHaveLength(1)
  })

  test('Given 旧事件只有 requestId When 解除 Then 也能收掉', () => {
    const view = applyDelegationBlocked(
      normalizeDelegations({ running: [runningEntry('d1')] }),
      normalizeBlockedEvent({ delegationId: 'd1', blockedEvent: blockedEvent() })!,
    )
    expect(resolveDelegationBlocked(view, { requestId: 'req-1' }).blockedCount).toBe(0)
  })

  test('Given 阻塞事件缺 id / requestId When 归一化 Then 丢弃（无法解除的提示不能出现）', () => {
    expect(normalizeBlockedEvent({ delegationId: 'd1', blockedEvent: { kind: 'ask_user' } })).toBeNull()
  })
})

describe('委派可见性：子会话状态变化', () => {
  const meta = (patch: Partial<AgentSessionMeta>): AgentSessionMeta =>
    ({
      id: 'child-1',
      title: '登录调研',
      parentSessionId: 'parent-1',
      createdAt: 100,
      updatedAt: 100,
      ...patch,
    } as AgentSessionMeta)

  test('Given 新子会话开始运行 When 应用 Then 加入列表（刷新后未拿到快照也能先提示）', () => {
    const view = normalizeDelegations({ running: [] })
    const next = applyDelegationSessionUpdate(view, meta({
      sourceDelegationId: 'd1',
      delegationStatus: 'running',
      delegationRole: 'explore',
      delegationGoal: '找入口',
    }), 'parent-1')
    expect(next.running[0]).toMatchObject({ delegationId: 'd1', title: '登录调研', roleLabel: '探索', goal: '找入口' })
  })

  test('Given 子会话完成 When 应用 Then 移出「在跑」列表', () => {
    const view = normalizeDelegations({ running: [runningEntry('d1')] })
    const next = applyDelegationSessionUpdate(view, meta({
      sourceDelegationId: 'd1',
      delegationStatus: 'completed',
    }), 'parent-1')
    expect(next.running).toHaveLength(0)
  })

  test('Given 运行中的子会话更新标题 When 应用 Then 保留已有阻塞事件并更新标题', () => {
    const view = applyDelegationBlocked(
      normalizeDelegations({ running: [runningEntry('d1')] }),
      normalizeBlockedEvent({ delegationId: 'd1', blockedEvent: blockedEvent() })!,
    )
    const next = applyDelegationSessionUpdate(view, meta({
      sourceDelegationId: 'd1',
      delegationStatus: 'running',
      title: '登录入口调研（新）',
    }), 'parent-1')
    expect(next.running[0]!.title).toBe('登录入口调研（新）')
    expect(next.blockedCount).toBe(1)
  })

  test('Given 不是当前会话的子会话 When 应用 Then 完全忽略（不能串会话）', () => {
    const view = normalizeDelegations({ running: [runningEntry('d1')] })
    const next = applyDelegationSessionUpdate(view, meta({
      sourceDelegationId: 'd1',
      delegationStatus: 'completed',
    }), 'other-parent')
    expect(next.running).toHaveLength(1)
  })
})

describe('委派可见性：状态条文案', () => {
  test('Given 有阻塞 When 取文案 Then 优先提示「在等你回答」', () => {
    const view = applyDelegationBlocked(
      normalizeDelegations({ running: [runningEntry('d1'), runningEntry('d2')] }),
      normalizeBlockedEvent({ delegationId: 'd1', blockedEvent: blockedEvent() })!,
    )
    expect(delegationSummary(view)).toBe('1 个子任务在等你回答')
  })

  test('Given 只有运行中的子任务 When 取文案 Then 报数量', () => {
    expect(delegationSummary(normalizeDelegations({ running: [runningEntry('d1'), runningEntry('d2')] })))
      .toBe('有 2 个子任务在跑')
  })

  test('Given 没有子任务 When 取文案 Then 返回 null（整条不渲染）', () => {
    expect(delegationSummary(normalizeDelegations({ running: [] }))).toBeNull()
  })

  test('Given 目标为空 When 取行副标题 Then 用角色兜底', () => {
    const view = normalizeDelegations({ running: [runningEntry('d1', { goal: '' })] })
    expect(delegationLineDetail(view.running[0]!)).toBe('调研')
  })

  test('Given 目标是整段 prompt When 取行副标题 Then 折叠空白并截断，不铺满手机屏幕', () => {
    const goal = '共享背景：\n这是一次协作测试。\n\n任务步骤：\n1. 读取 a.txt\n2. 写入 a-result.txt'
      + '……后续还有很多步骤和注意事项。'.repeat(20)
    const preview = delegationGoalPreview(goal)
    expect(preview).not.toContain('\n')
    expect(preview.endsWith('…')).toBe(true)
    expect(preview.length).toBe(73)
    const view = normalizeDelegations({ running: [runningEntry('d1', { goal })] })
    expect(delegationLineDetail(view.running[0]!).length).toBe(73)
  })

  test('Given 目标很短 When 取行副标题 Then 原样保留', () => {
    expect(delegationGoalPreview('找出登录入口')).toBe('找出登录入口')
  })
})