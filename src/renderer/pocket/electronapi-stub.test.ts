import { beforeEach, describe, expect, test } from 'bun:test'
import {
  getMissingElectronApiKeys,
  installElectronApiStub,
  requestWorkspaceHeatmapDaily,
  resolveAuthoritativeAgentSession,
} from './electronapi-stub'

const entries = [
  { date: '2026-08-20', tokens: 1200 },
  { date: '2026-08-21', tokens: 3400 },
]

describe('requestWorkspaceHeatmapDaily', () => {
  test('透传远程返回的每日 Token 数组', async () => {
    const client = {
      getWorkspaceHeatmapDaily: async (workspaceId: string): Promise<unknown> => {
        expect(workspaceId).toBe('workspace-1')
        return entries
      },
    }

    await expect(requestWorkspaceHeatmapDaily(client, 'workspace-1')).resolves.toEqual(entries)
  })

  test('remote client 未注入时返回空数组', async () => {
    await expect(requestWorkspaceHeatmapDaily(null, 'workspace-1')).resolves.toEqual([])
  })

  test('remote client reject 时保留 reject', async () => {
    const error = new Error('连接已断开')
    const client = {
      getWorkspaceHeatmapDaily: async (): Promise<unknown> => Promise.reject(error),
    }

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
      listSessions: async (): Promise<unknown[]> => [],
    }

    await expect(resolveAuthoritativeAgentSession(
      client,
      persisted.id,
      { sessionId: persisted.id, title: persisted.title },
    )).rejects.toThrow('远端未返回完整会话元数据')
  })
})

/**
 * pocket electronAPI stub 的「未实现能力」语义测试。
 *
 * 背景：stub 原先用 makeDeepStub() 兜底 —— 未命中的 key 返回可调用 Proxy（恒 resolve(undefined)、
 * 可无限嵌套），于是
 *   ① `if (window.electronAPI?.onXxx)` 判真 → 「假注册」；
 *   ② 「存在性检测 + 降级」被 truthy 骗过（通知兜底失效）；
 *   ③ 能力缺口零信号。
 * 现在改为返回真 undefined + 开发期聚合告警，本组用例锁定该语义不被改回去。
 */
type Stub = Record<string, unknown>

const globalWithApi = globalThis as unknown as { electronAPI?: unknown }

function installFresh(): Stub {
  delete globalWithApi.electronAPI
  installElectronApiStub()
  return globalWithApi.electronAPI as Stub
}

describe('pocket electronAPI stub 未实现能力语义', () => {
  beforeEach(() => {
    delete globalWithApi.electronAPI
  })

  test('显式 stub 的成员照常可用（事件注册器返回取消函数）', () => {
    const api = installFresh()
    expect(typeof api.onAgentStreamEvent).toBe('function')
    const off = (api.onAgentStreamEvent as (cb: () => void) => () => void)(() => {})
    expect(typeof off).toBe('function')
    off()
  })

  test('未显式 stub 的成员严格 undefined（不再伪造「永远成功」的可调用对象）', () => {
    const api = installFresh()
    // 桌面存在、pocket 从未 stub 的通知能力：必须 undefined，否则 notifications.ts 的
    // 「存在性检测 + Web Notification 降级」会被 truthy 的可调用兜底骗过 → pocket 彻底收不到通知。
    expect(api.showDesktopNotification).toBeUndefined()
    expect(typeof api.onSomeNeverStubbedEvent).toBe('undefined')
    expect(api.someUnknownNamespace).toBeUndefined()
  })

  test('未知成员被登记为能力缺口，且不重复登记', () => {
    const api = installFresh()
    void api.someUnknownNamespaceForGapCheck
    void api.someUnknownNamespaceForGapCheck
    const keys = getMissingElectronApiKeys()
    expect(keys).toContain('someUnknownNamespaceForGapCheck')
    expect(keys.filter((key) => key === 'someUnknownNamespaceForGapCheck')).toHaveLength(1)
  })

  test('启动路径上被直接调用的 getter 必须有显式实现（缺失即在渲染期抛错 → 白屏）', async () => {
    const api = installFresh()
    await expect((api.getArchivedCounts as () => Promise<unknown>)()).resolves.toEqual({
      conversations: 0,
      agentSessions: 0,
    })
    await expect((api.getCommercialMode as () => Promise<unknown>)()).resolves.toBe(false)
    await expect((api.getPiReasoningCapability as () => Promise<unknown>)()).resolves.toBeUndefined()
    await expect((api.getAgentSessionPath as () => Promise<unknown>)()).resolves.toBeNull()
    await expect((api.clearAgentCompletionState as () => Promise<unknown>)()).rejects.toThrow('平板暂不支持')
  })

  test('已有 electronAPI（Electron 环境）时不覆盖', () => {
    const marker = { sentinel: true }
    globalWithApi.electronAPI = marker
    installElectronApiStub()
    expect(globalWithApi.electronAPI).toBe(marker)
  })
})
