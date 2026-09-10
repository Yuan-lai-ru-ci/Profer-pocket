import { describe, expect, test } from 'bun:test'
import type { AgentSessionMeta } from '@profer/shared'
import {
  sortAgentSessionsByUpdatedAtDesc,
  replaceAgentSessionInFreshnessOrder,
  upsertAgentSession,
  mergeFetchedAgentSessions,
  upsertAgentSessionProjection,
  deleteAgentSessionProjection,
} from './agent-session-list'

function makeSession(
  id: string,
  updatedAt: number,
  extra: Partial<AgentSessionMeta> = {},
): AgentSessionMeta {
  return {
    id,
    title: id,
    createdAt: updatedAt,
    updatedAt,
    ...extra,
  }
}

describe('sortAgentSessionsByUpdatedAtDesc', () => {
  test('Given 乱序会话 When 排序 Then 按 updatedAt 降序', () => {
    const result = sortAgentSessionsByUpdatedAtDesc([
      makeSession('a', 1),
      makeSession('b', 3),
      makeSession('c', 2),
    ])
    expect(result.map((s) => s.id)).toEqual(['b', 'c', 'a'])
  })
})

describe('replaceAgentSessionInFreshnessOrder', () => {
  test('Given 列表中已有同 id When 用新元数据替换 Then 替换并重排', () => {
    const result = replaceAgentSessionInFreshnessOrder(
      [makeSession('a', 1), makeSession('b', 2)],
      makeSession('a', 5, { title: '新标题' }),
    )
    expect(result.map((s) => s.id)).toEqual(['a', 'b'])
    expect(result.find((s) => s.id === 'a')?.title).toBe('新标题')
  })
})

describe('upsertAgentSession', () => {
  test('Given 列表无该会话 When upsert Then 追加并重排', () => {
    const result = upsertAgentSession(
      [makeSession('a', 1)],
      makeSession('b', 3),
    )
    expect(result.map((s) => s.id)).toEqual(['b', 'a'])
  })

  test('Given 列表已有该会话 When upsert Then 浅合并字段，保留未覆盖字段', () => {
    const result = upsertAgentSession(
      [makeSession('a', 1, { channelId: 'ch-1', workspaceId: 'ws-1' })],
      makeSession('a', 9, { title: '改名' }),
    )
    const a = result.find((s) => s.id === 'a')!
    expect(a.title).toBe('改名')
    expect(a.updatedAt).toBe(9)
    // 未在 incoming 中提供的字段从既有条目保留
    expect(a.channelId).toBe('ch-1')
    expect(a.workspaceId).toBe('ws-1')
  })

  test('Given 历史列表已含重复 id When upsert Then 收敛为单条并保留既有字段', () => {
    const result = upsertAgentSession(
      [
        makeSession('a', 1, { workspaceId: 'ws-old' }),
        makeSession('a', 2, { channelId: 'ch-newer' }),
        makeSession('b', 3),
      ],
      makeSession('a', 9, { title: '权威标题' }),
    )

    expect(result.filter((session) => session.id === 'a')).toHaveLength(1)
    expect(result.find((session) => session.id === 'a')).toMatchObject({
      title: '权威标题',
      updatedAt: 9,
    })
    expect(result.map((session) => session.id)).toEqual(['a', 'b'])
  })

  test('核心回归：upsert 子会话时绝不删除其它会话（含刚结束 turn 的父会话）', () => {
    const parent = makeSession('parent', 10)
    const childA = makeSession('child-a', 11, {
      parentSessionId: 'parent',
      sourceDelegationId: 'del-a',
    })
    // 模拟：列表里已有父会话 + 子会话 a，现在子会话 b 启动
    const childB = makeSession('child-b', 12, {
      parentSessionId: 'parent',
      sourceDelegationId: 'del-b',
    })
    const result = upsertAgentSession([parent, childA], childB)
    // 父会话与子会话 a 都必须仍然在列表中
    expect(result.map((s) => s.id).sort()).toEqual(['child-a', 'child-b', 'parent'])
  })

  test('回归：完整委派元数据到达时保留工作区与父子关系', () => {
    const placeholder = makeSession('child', 10, { parentSessionId: 'parent' })
    const persistedChild = makeSession('child', 20, {
      workspaceId: 'project-a',
      parentSessionId: 'parent',
      sourceDelegationId: 'delegation-a',
      delegationStatus: 'completed',
    })

    const result = upsertAgentSession([placeholder], persistedChild)
    expect(result[0]).toMatchObject({
      workspaceId: 'project-a',
      parentSessionId: 'parent',
      sourceDelegationId: 'delegation-a',
      delegationStatus: 'completed',
    })
  })
})

describe('upsertAgentSessionProjection', () => {
  const projection = (revision: number, extra: Partial<import('@profer/shared').AgentSessionUiProjection> = {}): import('@profer/shared').AgentSessionUiProjection => ({
    schemaVersion: 1,
    id: 'a',
    revision,
    title: `标题-${revision}`,
    createdAt: 1,
    updatedAt: revision,
    channelId: revision === 2 ? null : 'channel',
    modelId: revision === 2 ? null : 'model',
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
    ...extra,
  })

  test('Given 乱序完整快照 When 低 revision 晚到 Then 保留较新权威状态与本地附件', () => {
    const local = makeSession('a', 20, {
      revision: 3,
      title: '新状态',
      channelId: 'new-channel',
      attachedDirectories: ['C:/local-only'],
    })
    const result = upsertAgentSessionProjection([local], projection(2, { title: '旧状态' }))

    expect(result[0]).toMatchObject({ revision: 3, title: '新状态', channelId: 'new-channel' })
    expect(result[0]?.attachedDirectories).toEqual(['C:/local-only'])
  })

  test('Given 高 revision 完整快照 When 应用 Then 显式 null 清除旧安全字段并保留本地附件', () => {
    const local = makeSession('a', 1, {
      revision: 1,
      channelId: 'old-channel',
      modelId: 'old-model',
      attachedFiles: ['C:/local-only.txt'],
    })
    const result = upsertAgentSessionProjection([local], projection(2))

    expect(result[0]).toMatchObject({ revision: 2, title: '标题-2', channelId: undefined, modelId: undefined })
    expect(result[0]?.attachedFiles).toEqual(['C:/local-only.txt'])
  })

  test('Given 相同 revision 重复投递 When 应用 Then 结果幂等', () => {
    const once = upsertAgentSessionProjection([], projection(4))
    const twice = upsertAgentSessionProjection(once, projection(4))
    expect(twice).toEqual(once)
  })

  test('Given 删除墓碑已到 When 旧或同 revision upsert 晚到 Then 会话不会复活', () => {
    expect(upsertAgentSessionProjection([], projection(4), 4)).toEqual([])
    expect(upsertAgentSessionProjection([], projection(3), 4)).toEqual([])
    expect(upsertAgentSessionProjection([], projection(5), 4)).toHaveLength(1)
  })
})

describe('deleteAgentSessionProjection', () => {
  test('Given 删除墓碑先到 When 陈旧本地状态存在 Then 移除；更高 revision 不被旧墓碑删除', () => {
    expect(deleteAgentSessionProjection([makeSession('a', 1, { revision: 2 })], 'a', 2)).toEqual([])
    expect(deleteAgentSessionProjection([makeSession('a', 3, { revision: 3 })], 'a', 2)).toHaveLength(1)
  })
})

describe('mergeFetchedAgentSessions', () => {
  test('Given 后端快照含全部会话 When 合并 Then 以快照为准并重排', () => {
    const prev = [makeSession('a', 1)]
    const fetched = [makeSession('a', 5), makeSession('b', 6)]
    const result = mergeFetchedAgentSessions(prev, fetched)
    expect(result.map((s) => s.id)).toEqual(['b', 'a'])
    expect(result.find((s) => s.id === 'a')?.updatedAt).toBe(5)
  })

  test('核心回归：陈旧快照缺失刚结束 turn 的父会话 When 合并 Then 父会话被保留不被冲掉', () => {
    // 父会话刚结束 turn，updatedAt 较新；但某个并发回调的 fetch 早于它落盘，快照里没有它
    const parent = makeSession('parent', 100)
    const childA = makeSession('child-a', 90, {
      parentSessionId: 'parent',
      sourceDelegationId: 'del-a',
    })
    const prev = [parent, childA]
    // 陈旧快照：只有 child-a，没有 parent（且没有比 parent 更新的条目）
    const staleFetched = [makeSession('child-a', 90)]
    const result = mergeFetchedAgentSessions(prev, staleFetched)
    // parent.updatedAt(100) >= 快照水位(90)，应被判定为「快照尚未看到的新条目」而保留
    expect(result.some((s) => s.id === 'parent')).toBe(true)
  })

  test('删除语义：后端确实删除的旧会话（updatedAt 早于快照水位）不被保留', () => {
    // old 会话已被后端删除，且它的 updatedAt 早于快照里的最新条目
    const old = makeSession('old', 1)
    const prev = [old, makeSession('keep', 5)]
    // 权威快照里没有 old，且快照水位(8) > old.updatedAt(1) → old 视为已删除
    const fetched = [makeSession('keep', 8)]
    const result = mergeFetchedAgentSessions(prev, fetched)
    expect(result.some((s) => s.id === 'old')).toBe(false)
    expect(result.some((s) => s.id === 'keep')).toBe(true)
  })

  test('幂等：prev 与 fetched 完全一致 When 合并 Then 内容不变', () => {
    const sessions = [makeSession('a', 5), makeSession('b', 3)]
    const result = mergeFetchedAgentSessions(sessions, sessions)
    expect(result.map((s) => s.id)).toEqual(['a', 'b'])
    expect(result).toHaveLength(2)
  })
})
