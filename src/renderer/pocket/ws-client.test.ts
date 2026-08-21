import { describe, expect, test } from 'bun:test'
import { WsClient } from './ws-client'

describe('WsClient.getWorkspaceHeatmapDaily', () => {
  test('sends the workspace heatmap command with the workspace id', async () => {
    const client = new WsClient({ url: 'ws://127.0.0.1/ws', token: 'test-token' })
    let payload: Record<string, unknown> | undefined
    client.sendCommand = async (command): Promise<unknown> => {
      payload = command
      return [{ date: '2026-08-21', tokens: 42 }]
    }

    const result = await client.getWorkspaceHeatmapDaily('workspace-1')

    expect(payload).toEqual({ type: 'get_workspace_heatmap_daily', workspaceId: 'workspace-1' })
    expect(result).toEqual([{ date: '2026-08-21', tokens: 42 }])
  })
})
