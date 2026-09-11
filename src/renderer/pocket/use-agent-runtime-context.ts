/**
 * Pocket 权威上下文窗口水合 hook（R2）
 *
 * 把桌面端 `get_agent_runtime_contexts` 的权威快照写进流状态，覆盖按模型名推断的 fallback。
 * 触发点（由调用方决定）：
 *  - WS 连接/重连成功后（pocket/main.tsx 的 loadSessions，主端 active 会话）
 *  - 会话切换（pocket/main.tsx 的 openSession）
 *  - 打开上下文圆环弹层时（ContextUsageBadge）
 *
 * 降级：未连接 / 旧版桌面端不识别该命令 / 超时 / 返回空，一律静默保留现状，
 * 不弹报错、不让圆环消失。真正的权威登记逻辑在 `agent-runtime-context.ts`（纯函数，可单测）。
 */

import * as React from 'react'
import { useSetAtom } from 'jotai'
import { agentStreamingStatesAtom } from '@/atoms/agent-atoms'
import { getPocketRemoteClient } from './electronapi-stub'
import { hydrateAgentRuntimeContexts, normalizeAgentRuntimeContextSnapshots } from './agent-runtime-context'

export interface AgentRuntimeContextHydration {
  /** 拉取并水合权威快照；sessionIds 为空数组时不做任何事（避免无意义的空查询）。 */
  refresh: (sessionIds?: string[]) => void
}

export function useAgentRuntimeContextHydration(): AgentRuntimeContextHydration {
  const setStreamingStates = useSetAtom(agentStreamingStatesAtom)

  const refresh = React.useCallback((sessionIds?: string[]): void => {
    const client = getPocketRemoteClient()
    if (!client) return
    const ids = sessionIds?.filter((id): id is string => typeof id === 'string' && id.length > 0)
    if (ids && ids.length === 0) return

    // 只读命令：失败/超时/旧服务端不支持都只是拿不到权威分母，静默保留现状。
    void client.getAgentRuntimeContexts(ids && ids.length > 0 ? ids : undefined)
      .then((data) => {
        const snapshots = normalizeAgentRuntimeContextSnapshots(data)
        if (snapshots.length === 0) return
        setStreamingStates((previous) => hydrateAgentRuntimeContexts(previous, snapshots, {
          sessionIds: ids && ids.length > 0 ? new Set(ids) : undefined,
        }))
      })
      .catch((error) => {
        console.debug('[Pocket] 获取 Agent 运行时上下文快照失败，已降级为实时事件/模型推断:', error)
      })
  }, [setStreamingStates])

  return { refresh }
}
