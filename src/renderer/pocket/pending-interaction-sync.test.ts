import { afterEach, describe, expect, test } from 'bun:test'
import {
  clearPocketResolvedInteractionWatermarks,
  markPocketResolvedInteraction,
} from './pending-interaction-reconciliation'
import {
  applyPendingInteractionSnapshot,
  groupPendingInteractionsBySession,
  isPendingInteractionSnapshotPayload,
  syncPendingInteractions,
  type AppliedPendingInteractionSnapshot,
} from './pending-interaction-sync'
import type { AskUserRequest, ExitPlanModeRequest, PermissionRequest } from '@profer/shared'

const allowed = new Set(['session-1', 'session-2'])

function permission(sessionId: string, requestId: string): PermissionRequest {
  return { sessionId, requestId } as unknown as PermissionRequest
}

afterEach(() => clearPocketResolvedInteractionWatermarks())

describe('groupPendingInteractionsBySession', () => {
  test('按 sessionId 分组并保留同会话多条请求', () => {
    const grouped = groupPendingInteractionsBySession(
      [permission('session-1', 'a'), permission('session-1', 'b'), permission('session-2', 'c')],
      'permission',
      allowed,
    )

    expect([...grouped.keys()]).toEqual(['session-1', 'session-2'])
    expect(grouped.get('session-1')?.length).toBe(2)
    expect(grouped.get('session-2')?.length).toBe(1)
  })

  test('白名单外会话被丢弃（团队工作区在 Pocket 刻意隐藏）', () => {
    const grouped = groupPendingInteractionsBySession(
      [permission('session-1', 'a'), permission('team-session', 'b')],
      'permission',
      allowed,
    )

    expect([...grouped.keys()]).toEqual(['session-1'])
  })

  test('allowedSessionIds 为 null 时不过滤', () => {
    const grouped = groupPendingInteractionsBySession([permission('team-session', 'b')], 'permission', null)

    expect([...grouped.keys()]).toEqual(['team-session'])
  })

  test('sessionId / requestId 非法或重复的条目被丢弃', () => {
    const grouped = groupPendingInteractionsBySession(
      [
        permission('session-1', 'a'),
        permission('session-1', 'a'),
        { requestId: 'no-session' } as unknown as PermissionRequest,
        { sessionId: 'session-1' } as unknown as PermissionRequest,
      ],
      'permission',
      allowed,
    )

    expect(grouped.get('session-1')?.length).toBe(1)
  })

  test('resolved 水印命中的条目被过滤（复用 reconciliation 语义）', () => {
    markPocketResolvedInteraction({ kind: 'permission', sessionId: 'session-1', requestId: 'a' }, Date.now())

    const grouped = groupPendingInteractionsBySession(
      [permission('session-1', 'a'), permission('session-1', 'b')],
      'permission',
      allowed,
    )

    expect(grouped.get('session-1')?.map((item) => item.requestId)).toEqual(['b'])
  })
})

describe('applyPendingInteractionSnapshot', () => {
  test('三类请求分别重建（互不串台）', () => {
    const applied = applyPendingInteractionSnapshot(
      {
        permissions: [permission('session-1', 'perm-1')],
        askUsers: [{ sessionId: 'session-1', requestId: 'ask-1' } as unknown as AskUserRequest],
        exitPlans: [{ sessionId: 'session-2', requestId: 'plan-1' } as unknown as ExitPlanModeRequest],
      },
      { allowedSessionIds: allowed },
    )

    expect([...applied.permission.keys()]).toEqual(['session-1'])
    expect([...applied.askUser.keys()]).toEqual(['session-1'])
    expect([...applied.exitPlan.keys()]).toEqual(['session-2'])
  })

  test('缺省字段按空处理（等价于主端确认该类无待处理）', () => {
    const applied = applyPendingInteractionSnapshot({ permissions: [] }, { allowedSessionIds: allowed })

    expect(applied.permission.size).toBe(0)
    expect(applied.askUser.size).toBe(0)
    expect(applied.exitPlan.size).toBe(0)
  })
})

describe('isPendingInteractionSnapshotPayload', () => {
  test('至少含一个数组字段才算合法快照', () => {
    expect(isPendingInteractionSnapshotPayload({ permissions: [], askUsers: [], exitPlans: [] })).toBe(true)
    expect(isPendingInteractionSnapshotPayload({ askUsers: [] })).toBe(true)
  })

  test('空对象 / 非对象 / 字段非数组都判为非法', () => {
    expect(isPendingInteractionSnapshotPayload({})).toBe(false)
    expect(isPendingInteractionSnapshotPayload(null)).toBe(false)
    expect(isPendingInteractionSnapshotPayload([])).toBe(false)
    expect(isPendingInteractionSnapshotPayload({ permissions: {} })).toBe(false)
    expect(isPendingInteractionSnapshotPayload('snapshot')).toBe(false)
  })
})

describe('syncPendingInteractions', () => {
  test('成功时 commit 收到的三类分组结果', async () => {
    const committed: AppliedPendingInteractionSnapshot[] = []

    const result = await syncPendingInteractions({
      fetchSnapshot: async () => ({
        permissions: [permission('session-1', 'perm-1')],
        askUsers: [],
        exitPlans: [],
      }),
      commit: (applied) => committed.push(applied),
      allowedSessionIds: allowed,
    })

    expect(result).toBe('applied')
    expect(committed.length).toBe(1)
    expect(committed[0]?.permission.get('session-1')?.length).toBe(1)
  })

  test('拉取失败时返回 failed 且不改动 atoms（commit 未被调用）', async () => {
    let committed = 0

    const result = await syncPendingInteractions({
      fetchSnapshot: async () => Promise.reject(new Error('指令超时')),
      commit: () => { committed += 1 },
    })

    expect(result).toBe('failed')
    expect(committed).toBe(0)
  })

  test('载荷不是快照时不改动 atoms（防止合法横幅被整体清空）', async () => {
    let committed = 0

    const result = await syncPendingInteractions({
      fetchSnapshot: async () => ({}),
      commit: () => { committed += 1 },
    })

    expect(result).toBe('failed')
    expect(committed).toBe(0)
  })

  test('commit 抛异常时返回 failed（不冒泡打断调用点）', async () => {
    const result = await syncPendingInteractions({
      fetchSnapshot: async () => ({ permissions: [] }),
      commit: () => { throw new Error('store 不可用') },
    })

    expect(result).toBe('failed')
  })
})
