import { describe, expect, test } from 'bun:test'
import {
  requestWorkspaceHeatmapDaily,
  resolveAuthoritativeAgentSession,
} from './electronapi-stub'

describe('requestWorkspaceHeatmapDaily', () => {
  test('returns daily token entries from the desktop remote service', async () => {
    const entries = [
      { date: '2026-08-20', tokens: 1200 },
      { date: '2026-08-21', tokens: 3400 },
    ]
    const client = {
      getWorkspaceHeatmapDaily: async (workspaceId: string): Promise<unknown> => {
        expect(workspaceId).toBe('workspace-1')
        return entries
      },
    }

    await expect(requestWorkspaceHeatmapDaily(client, 'workspace-1')).resolves.toEqual(entries)
  })

  test('returns an empty array before the remote connection is ready', async () => {
    await expect(requestWorkspaceHeatmapDaily(null, 'workspace-1')).resolves.toEqual([])
  })

  test('returns an empty array when the remote query fails or returns invalid data', async () => {
    const failedClient = {
      getWorkspaceHeatmapDaily: async (): Promise<unknown> => {
        throw new Error('连接已断开')
      },
    }
    const invalidClient = {
      getWorkspaceHeatmapDaily: async (): Promise<unknown> => ({ date: '2026-08-21', tokens: 1 }),
    }

    await expect(requestWorkspaceHeatmapDaily(failedClient, 'workspace-1')).resolves.toEqual([])
    await expect(requestWorkspaceHeatmapDaily(invalidClient, 'workspace-1')).resolves.toEqual([])
  })
})

describe('resolveAuthoritativeAgentSession', () => {
  const persisted = {
    id: 'session-1',
    title: '远端标题',
    workspaceId: 'workspace-1',
    draft: true,
    pinned: false,
    createdAt: 100,
    updatedAt: 200,
  }

  test('命令直接返回完整元数据时不额外拉取列表', async () => {
    let listCalls = 0
    const client = {
      listSessions: async (): Promise<unknown> => {
        listCalls += 1
        return [persisted]
      },
    }

    await expect(resolveAuthoritativeAgentSession(client, persisted.id, persisted)).resolves.toEqual(persisted)
    expect(listCalls).toBe(0)
  })

  test('旧端仅返回 sessionId/title 时从列表取得持久化真源', async () => {
    const client = {
      listSessions: async (): Promise<unknown> => [persisted],
    }

    await expect(resolveAuthoritativeAgentSession(
      client,
      persisted.id,
      { sessionId: persisted.id, title: '不完整标题' },
    )).resolves.toEqual(persisted)
  })

  test('列表也没有完整条目时明确失败，不用 Date.now 拼影子对象', async () => {
    const client = {
      listSessions: async (): Promise<unknown> => [],
    }

    await expect(resolveAuthoritativeAgentSession(
      client,
      persisted.id,
      { sessionId: persisted.id, title: persisted.title },
    )).rejects.toThrow('远端未返回完整会话元数据')
  })
})
