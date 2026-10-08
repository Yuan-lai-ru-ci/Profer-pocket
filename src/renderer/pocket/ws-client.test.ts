import { describe, expect, test } from 'bun:test'
import { WsClient, normalizePresetList } from './ws-client'

describe('normalizePresetList 归一化', () => {
  test('Given 数组回包 Then 过滤掉无 id 的脏数据', () => {
    expect(normalizePresetList([{ id: 'standard' }, { name: '坏数据' }, null, 'x'])).toEqual([{ id: 'standard' }])
  })

  test('Given 桌面端包装成对象 Then 取出内部数组（不再白屏）', () => {
    expect(normalizePresetList({ presets: [{ id: 'a' }] })).toEqual([{ id: 'a' }])
    expect(normalizePresetList({ items: [{ id: 'b' }] })).toEqual([{ id: 'b' }])
  })

  test('Given 非数组也无可取字段 Then 退回空列表', () => {
    expect(normalizePresetList({ ok: false, error: '未知指令' })).toEqual([])
    expect(normalizePresetList('boom')).toEqual([])
    expect(normalizePresetList(null)).toEqual([])
    expect(normalizePresetList(undefined)).toEqual([])
  })
})

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
