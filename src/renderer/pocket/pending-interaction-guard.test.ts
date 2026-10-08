import { describe, expect, test } from 'bun:test'
import { resolvePocketInteractionVerdict, type InteractionVerdictQuery } from './pending-interaction-guard'

const snapshot = {
  permissions: [{ sessionId: 'session-1', requestId: 'perm-1' }],
  askUsers: [{ sessionId: 'session-1', requestId: 'ask-1' }],
  exitPlans: [{ sessionId: 'session-1', requestId: 'plan-1' }],
}

function createSource(payload: unknown, seen: Array<string | undefined> = []) {
  return {
    seen,
    getPendingInteractions: async (sessionId?: string): Promise<unknown> => {
      seen.push(sessionId)
      return payload
    },
  }
}

describe('resolvePocketInteractionVerdict', () => {
  test('快照仍含 requestId → pending（保留「X = 停止本轮」原行为）', async () => {
    const source = createSource(snapshot)

    await expect(resolvePocketInteractionVerdict(source, { kind: 'askUser', requestId: 'ask-1', sessionId: 'session-1' }))
      .resolves.toBe('pending')
    expect(source.seen).toEqual(['session-1'])
  })

  test('快照不含 requestId → resolved（另一端已处理）', async () => {
    const source = createSource(snapshot)

    await expect(resolvePocketInteractionVerdict(source, { kind: 'askUser', requestId: 'ask-404' }))
      .resolves.toBe('resolved')
    expect(source.seen).toEqual([undefined])
  })

  test('三类请求各按自己的数组判定，不互相顶替', async () => {
    const query = (kind: InteractionVerdictQuery['kind'], requestId: string): InteractionVerdictQuery => ({ kind, requestId })

    await expect(resolvePocketInteractionVerdict(createSource(snapshot), query('permission', 'perm-1'))).resolves.toBe('pending')
    await expect(resolvePocketInteractionVerdict(createSource(snapshot), query('permission', 'ask-1'))).resolves.toBe('resolved')
    await expect(resolvePocketInteractionVerdict(createSource(snapshot), query('exitPlan', 'plan-1'))).resolves.toBe('pending')
  })

  test('连接未建立（client 为空）→ unknown，不 reject', async () => {
    await expect(resolvePocketInteractionVerdict(null, { kind: 'permission', requestId: 'perm-1' }))
      .resolves.toBe('unknown')
  })

  test('旧服务端不支持该命令（reject）→ unknown', async () => {
    const source = {
      getPendingInteractions: async (): Promise<unknown> => Promise.reject(new Error('指令超时')),
    }

    await expect(resolvePocketInteractionVerdict(source, { kind: 'permission', requestId: 'perm-1' }))
      .resolves.toBe('unknown')
  })

  test('载荷形状异常 → unknown', async () => {
    await expect(resolvePocketInteractionVerdict(createSource({}), { kind: 'permission', requestId: 'perm-1' }))
      .resolves.toBe('unknown')
    await expect(resolvePocketInteractionVerdict(createSource(null), { kind: 'permission', requestId: 'perm-1' }))
      .resolves.toBe('unknown')
  })
})
