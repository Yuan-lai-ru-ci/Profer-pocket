import { describe, expect, test } from 'bun:test'
import { requestWorkspaceHeatmapDaily } from './electronapi-stub'

const entries = [{ date: '2026-08-21', tokens: 3400 }]

describe('requestWorkspaceHeatmapDaily', () => {
  test('透传远程返回的每日 Token 数组', async () => {
    let receivedWorkspaceId = ''
    const client = {
      getWorkspaceHeatmapDaily: async (workspaceId: string): Promise<unknown> => {
        receivedWorkspaceId = workspaceId
        return entries
      },
    }

    await expect(requestWorkspaceHeatmapDaily(client, 'workspace-1')).resolves.toEqual(entries)
    expect(receivedWorkspaceId).toBe('workspace-1')
  })

  test('remote client 未注入时返回空数组', async () => {
    await expect(requestWorkspaceHeatmapDaily(null, 'workspace-1')).resolves.toEqual([])
  })

  test('remote client reject 时保留 reject', async () => {
    const error = new Error('连接已断开')
    const client = { getWorkspaceHeatmapDaily: async (): Promise<unknown> => Promise.reject(error) }

    await expect(requestWorkspaceHeatmapDaily(client, 'workspace-1')).rejects.toBe(error)
  })

  test('远程返回非数组时 reject 清晰错误', async () => {
    const client = {
      getWorkspaceHeatmapDaily: async (): Promise<unknown> => ({ date: '2026-08-21', tokens: 1 }),
    }

    await expect(requestWorkspaceHeatmapDaily(client, 'workspace-1'))
      .rejects.toThrow('工作区热力图响应必须是数组')
  })

  test('数组内含非法条目时 reject 清晰错误', async () => {
    const client = {
      getWorkspaceHeatmapDaily: async (): Promise<unknown> => [
        { date: '2026-08-21', tokens: 'not-a-number' },
      ],
    }

    await expect(requestWorkspaceHeatmapDaily(client, 'workspace-1'))
      .rejects.toThrow('工作区热力图响应包含非法条目')
  })
})
