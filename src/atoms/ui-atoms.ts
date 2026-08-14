/**
 * 移动端极简 atom — 替代桌面端依赖 Electron/主进程数据的 atom
 *
 * 这些 atom 在桌面端由主进程/持久化填充，移动端瘦客户端没有对应数据源，
 * 因此提供最小可用默认值，保证渲染内核（ContentBlock / SDKMessageRenderer /
 * AgentMessages）能无 IPC 地消费。
 */

import { atom } from 'jotai'

/** 思考块是否默认展开（桌面 chat-atoms 的 thinkingExpandedAtom，移动端固定 true 展开） */
export const thinkingExpandedAtom = atom<boolean>(true)

/** 渠道/模型列表（桌面 chat-atoms，移动端无配置，空数组） */
export interface ChannelMeta {
  id: string
  name?: string
  provider?: string
}
export const channelsAtom = atom<ChannelMeta[]>([])

/** 用户信息（对齐桌面 user-profile，默认 emoji 头像 + 用户名） */
export interface UserProfile {
  /** 用户名 */
  userName?: string
  /** 头像（emoji 字符串 或 data:image/* base64 URL） */
  avatar?: string
  email?: string
}

/** 默认用户头像 emoji（对齐桌面 DEFAULT_USER_AVATAR） */
export const DEFAULT_USER_AVATAR = '🧑‍💻'
/** 默认用户名（对齐桌面 DEFAULT_USER_NAME） */
export const DEFAULT_USER_NAME = '用户'

export const userProfileAtom = atom<UserProfile>({
  userName: DEFAULT_USER_NAME,
  avatar: DEFAULT_USER_AVATAR,
})

/** 迷你地图缓存（桌面 tab-atoms，移动端不渲染迷你图，空 Map） */
export const tabMinimapCacheAtom = atom<Map<string, unknown>>(new Map())

/** 当前活跃会话 id（桌面 tab-atoms，移动端由 session.ts 维护，这里兜底 null） */
export const activeSessionIdAtom = atom<string | null>(null)

/** 进程组展开偏好（桌面 agent-atoms，移动端无进程组，固定 false） */
export const agentProcessGroupsKeepExpandedAtom = atom<boolean>(false)
