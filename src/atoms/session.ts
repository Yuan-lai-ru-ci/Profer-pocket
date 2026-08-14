/**
 * Session / Workspace Atoms — 移动端独立客户端（apps/tablet-client）
 *
 * 职责：承载从 remote-service 拉取的 Agent 会话列表、工作区（项目）列表，
 * 以及当前选中会话 / 工作区 / 渠道 / 模型等导航态。
 *
 * 字段语义对齐桌面 @/atoms/agent-atoms.ts（agentSessionsAtom / agentWorkspacesAtom /
 * currentAgentSessionIdAtom / currentAgentWorkspaceIdAtom / agentChannelIdAtom /
 * agentModelIdAtom / agentChannelIdsAtom），但独立定义、不 import 桌面 atoms。
 * 类型直接复用 @profer/shared 导出的 AgentSessionMeta / AgentWorkspace。
 *
 * 注意：本文件只管理“列表 + 导航选择”这一类轻量状态；per-session 的流式状态
 * （running / tool activities / content 等）属于另一子代理的轨道，此处不重复定义。
 */

import { atom } from 'jotai'
import type { AgentSessionMeta, AgentWorkspace } from '@profer/shared'

/** Agent 会话列表（已过滤团队工作区内容，供侧栏展示） */
export const sessionsAtom = atom<AgentSessionMeta[]>([])

/** 工作区（项目）列表（个人 + 团队；移动端 UI 层自行决定是否隐藏 team） */
export const workspacesAtom = atom<AgentWorkspace[]>([])

/** 当前选中会话 ID（null = 未选中 / 登录页） */
export const currentSessionIdAtom = atom<string | null>(null)

/** 当前选中工作区 ID（默认回退 'default'） */
export const currentWorkspaceIdAtom = atom<string | null>(null)

/** 全局默认渠道 ID（从 list_channels 结果取第一个可用，新会话继承用） */
export const channelIdAtom = atom<string | null>(null)

/** 全局默认模型 ID（从默认渠道取第一个可用模型，新会话继承用） */
export const modelIdAtom = atom<string | null>(null)

/** 可用渠道 ID 列表（全量纳入，runtime/protocol 兼容性由 UI 层现有机制处理） */
export const channelIdsAtom = atom<string[]>([])

/** 渠道详情列表（id/name/provider/models），已补全 enabled 语义供模型选择器使用 */
export interface ChannelInfo {
  id: string
  name: string
  provider: string
  models: Array<{ id: string; name: string; enabled?: boolean }>
  enabled?: boolean
}

export const channelsAtom = atom<ChannelInfo[]>([])

/** 渠道是否已加载（连接就绪后由 useConnection 拉取并置 true） */
export const channelsLoadedAtom = atom(false)

/**
 * 当前选中会话的元信息（派生只读）。
 * 读取 sessionsAtom 中匹配 currentSessionIdAtom 的项。
 */
export const currentSessionAtom = atom<AgentSessionMeta | null>((get) => {
  const sessions = get(sessionsAtom)
  const id = get(currentSessionIdAtom)
  if (!id) return null
  return sessions.find((s) => s.id === id) ?? null
})

/**
 * 会话列表是否已加载完成（首次 list_sessions 成功置 true）。
 * 供 UI 区分“加载中空态”与“确实无会话空态”。
 */
export const sessionsLoadedAtom = atom(false)

/** 工作区列表是否已加载完成 */
export const workspacesLoadedAtom = atom(false)
