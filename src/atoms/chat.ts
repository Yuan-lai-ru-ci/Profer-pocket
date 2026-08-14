/**
 * Chat Atoms — 移动端独立客户端（apps/tablet-client）
 *
 * 职责：承载从 remote-service 拉取的 Chat 对话列表、当前选中对话、对话消息、
 * 以及 per-conversation 流式状态（content / reasoning / toolActivities / streaming / model）。
 *
 * 字段语义对齐桌面 @/atoms/chat-atoms.ts（conversationsAtom / currentConversationIdAtom /
 * streamingStatesAtom / chatStreamErrorsAtom / chatMessageRefreshAtom），但独立定义、
 * 不 import 桌面 atoms。类型直接复用 @profer/shared 导出的 ConversationMeta / ChatMessage。
 *
 * 与 Agent 流（session.ts / agent.ts）的关系：
 *  - Agent 会话导航态 → session.ts（currentSessionIdAtom / sessionsAtom）
 *  - Chat  对话导航态 → 本文件（currentConversationIdAtom / conversationsAtom）
 *  两者完全独立，由顶层 appMode（agent | chat）决定当前渲染哪个视图。
 */

import { atom } from 'jotai'
import type { ConversationMeta, ChatMessage, ChatToolActivity } from '@profer/shared'

/** Chat 对话列表（轻量索引，不含消息列表，来自 list_conversations） */
export const conversationsAtom = atom<ConversationMeta[]>([])

/** 当前选中对话 ID（null = 未选中） */
export const currentConversationIdAtom = atom<string | null>(null)

/** 对话列表是否已加载完成（首次 list_conversations 成功置 true） */
export const conversationsLoadedAtom = atom(false)

/**
 * 当前对话的消息列表（per-conversation 缓存，本地持有）。
 * 由 ChatView 在选中对话后 get_conversation_messages 拉取并写入。
 */
export const conversationMessagesAtom = atom<Map<string, ChatMessage[]>>(new Map())

/**
 * Chat 流式状态（对齐桌面 ConversationStreamState）。
 * 以 conversationId 为 key；流式进行中 Map 存在该 key，结束后由 UI 在消息
 * 重载完成后删除（避免「气泡消失 → 持久化消息未加载」闪空档）。
 */
export interface ConversationStreamState {
  streaming: boolean
  content: string
  reasoning: string
  model?: string
  /** 记忆工具活动列表（流式期间累积） */
  toolActivities: ChatToolActivity[]
  /** 流式开始时间戳 */
  startedAt?: number
}

export const streamingStatesAtom = atom<Map<string, ConversationStreamState>>(new Map())

/** 流式错误消息（按 conversationId 索引） */
export const chatStreamErrorsAtom = atom<Map<string, string>>(new Map())

/** 结构化错误代码（按 conversationId 索引，如 insufficient_credits） */
export const chatStreamErrorCodesAtom = atom<Map<string, string>>(new Map())

/**
 * 消息刷新版本号（按 conversationId 索引）。
 * 流式 complete / error 时 +1，通知 ChatView 重新拉取持久化消息并清理流式状态。
 */
export const chatMessageRefreshAtom = atom<Map<string, number>>(new Map())

/** 首次拉取消息条数（对齐桌面 INITIAL_MESSAGE_LIMIT） */
export const INITIAL_MESSAGE_LIMIT = 10

/** 当前对话元信息（派生只读） */
export const currentConversationAtom = atom<ConversationMeta | null>((get) => {
  const conversations = get(conversationsAtom)
  const id = get(currentConversationIdAtom)
  if (!id) return null
  return conversations.find((c) => c.id === id) ?? null
})

/** 当前对话是否流式中（派生只读，供输入框禁用等） */
export const currentConversationStreamingAtom = atom<boolean>((get) => {
  const id = get(currentConversationIdAtom)
  if (!id) return false
  return get(streamingStatesAtom).get(id)?.streaming ?? false
})
