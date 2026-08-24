import { describe, expect, test } from 'bun:test'
import { getDelegatedChildStatus, getSessionTreeStatus, type AgentSessionTreeItem } from './session-tree'
import type { AgentSessionMeta } from '@profer/shared'
import type { SessionIndicatorStatus } from '@/atoms/agent-atoms'

const NOW = 1_752_000_000_000

function makeSession(overrides: Partial<AgentSessionMeta> = {}): AgentSessionMeta {
  return {
    id: `session-${Math.random()}`,
    title: '会话',
    createdAt: NOW,
    updatedAt: NOW,
    ...overrides,
  }
}

function makeMap(entries: Record<string, SessionIndicatorStatus>): Map<string, SessionIndicatorStatus> {
  return new Map(Object.entries(entries))
}

describe('getSessionTreeStatus — 父会话状态聚合', () => {
  test('Given 父会话 completed 且子会话 running When 聚合状态 Then 父会话保持 running', () => {
    const item: AgentSessionTreeItem = {
      session: makeSession({ id: 'parent', title: '父会话' }),
      childSessions: [makeSession({
        id: 'child',
        parentSessionId: 'parent',
        sourceDelegationId: 'delegation-1',
        delegationStatus: 'running',
      })],
    }

    expect(getSessionTreeStatus(item, makeMap({ parent: 'completed', child: 'running' }))).toBe('running')
  })

  test('Given 子会话 stream 暂时 completed 但 delegation metadata 仍为 running When 聚合状态 Then 父会话保持 running', () => {
    const child = makeSession({
      id: 'child',
      parentSessionId: 'parent',
      sourceDelegationId: 'delegation-1',
      delegationStatus: 'running',
    })
    const item: AgentSessionTreeItem = {
      session: makeSession({ id: 'parent', title: '父会话' }),
      childSessions: [child],
    }
    const indicatorMap = makeMap({ child: 'completed' })

    expect(getDelegatedChildStatus(child, indicatorMap)).toBe('running')
    expect(getSessionTreeStatus(item, indicatorMap)).toBe('running')
  })

  test('Given 子会话 completed 但父会话 idle When 聚合状态 Then 子会话不让父会话显示 completed', () => {
    const item: AgentSessionTreeItem = {
      session: makeSession({ id: 'parent', title: '父会话' }),
      childSessions: [makeSession({
        id: 'child',
        parentSessionId: 'parent',
        sourceDelegationId: 'delegation-1',
        delegationStatus: 'completed',
      })],
    }

    expect(getSessionTreeStatus(item, makeMap({ child: 'completed' }))).toBe('idle')
  })
})
