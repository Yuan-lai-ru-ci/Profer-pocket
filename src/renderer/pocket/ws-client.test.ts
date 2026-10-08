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

describe('WsClient.getAgentRuntimeContexts', () => {
  test('Given 指定会话 When 查询权威上下文窗口 Then 带上 sessionIds', async () => {
    const client = new WsClient({ url: 'ws://127.0.0.1/ws', token: 'test-token' })
    let payload: Record<string, unknown> | undefined
    client.sendCommand = async (command): Promise<unknown> => {
      payload = command
      return [{ sessionId: 's1', contextWindow: 1_050_000, updatedAt: 1 }]
    }

    const result = await client.getAgentRuntimeContexts(['s1'])

    expect(payload).toEqual({ type: 'get_agent_runtime_contexts', sessionIds: ['s1'] })
    expect(result).toEqual([{ sessionId: 's1', contextWindow: 1_050_000, updatedAt: 1 }])
  })

  test('Given 未指定会话 When 查询 Then 不提交空 sessionIds（对齐主端“全量活跃会话”语义）', async () => {
    const client = new WsClient({ url: 'ws://127.0.0.1/ws', token: 'test-token' })
    let payload: Record<string, unknown> | undefined
    client.sendCommand = async (command): Promise<unknown> => {
      payload = command
      return []
    }

    await client.getAgentRuntimeContexts()
    expect(payload).toEqual({ type: 'get_agent_runtime_contexts' })

    await client.getAgentRuntimeContexts([])
    expect(payload).toEqual({ type: 'get_agent_runtime_contexts' })
  })
})
