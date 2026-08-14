/**
 * AppMode Atom — 移动端顶层模式切换（apps/tablet-client）
 *
 * 对齐桌面 @/atoms/app-mode.ts 的 AppMode（agent | chat），决定主区渲染
 * AgentView 还是 ChatView，以及侧边栏展示 Agent 会话还是 Chat 对话列表。
 */

import { atom } from 'jotai'
import { atomWithStorage } from 'jotai/utils'

export type AppMode = 'agent' | 'chat'

/**
 * 顶层模式（默认 agent，与桌面一致）。
 * atomWithStorage 持久化到 localStorage，刷新/冷启动后停留在上次的模式。
 */
export const appModeAtom = atomWithStorage<AppMode>('profer-remote-app-mode', 'agent')
