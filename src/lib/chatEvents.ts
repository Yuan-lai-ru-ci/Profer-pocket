/**
 * chatEvents 事件处理器 — 移动端独立客户端（apps/tablet-client）
 *
 * 将 WS 推送的 chat_event（{ conversationId, channel, payload }）按桌面
 * CHAT_IPC_CHANNELS 同名通道分发，归并写入 Chat atoms。替代桌面
 * useGlobalChatListeners（Electron IPC 监听）+ tablet/main.tsx 桥接两层的归并逻辑。
 *
 * 关键正确性语义（从桌面 useGlobalChatListeners 提炼）：
 *  - chunk / reasoning：累加 delta 到对应流式状态字段。
 *  - complete：置 streaming=false 但保留 content/reasoning 作过渡气泡，
 *    并递增 chatMessageRefreshAtom 通知 ChatView 重拉持久化消息；
 *    「流式状态清除」由 ChatView 在消息加载完成后执行，避免空档闪烁。
 *  - error：置 streaming=false + 写错误信息 + 错误代码，同样递增 refresh。
 *  - tool-activity：追加到 toolActivities 列表。
 *
 * 设计选择（与 lib/agentEvents.ts 同模式）：
 *  - 纯函数 + store 访问器注入，不依赖 jotai 单例；App 顶层挂载时 register。
 *  - 首条消息标题生成由 main.tsx / ChatView 层负责（本文件专注流式状态归并）。
 */

import { CHAT_IPC_CHANNELS } from '@profer/shared'
import type {
  StreamChunkEvent,
  StreamReasoningEvent,
  StreamCompleteEvent,
  StreamErrorEvent,
  StreamToolActivityEvent,
} from '@profer/shared'
import type { ChatWorkflowEvent } from '@/client/ws-client'
import { streamingStatesAtom, chatStreamErrorsAtom, chatStreamErrorCodesAtom, chatMessageRefreshAtom } from '@/atoms/chat'

/** store 访问器（与 agentEvents 的 AgentStore 同构） */
export interface ChatStore {
  get<T>(atom: unknown): T
  set<T>(atom: unknown, value: T | ((prev: T) => T)): void
}

let activeStore: ChatStore | null = null

/** 注册 store 访问器（App 顶层挂载时调用一次；不注册则禁用副作用写回） */
export function registerChatEventStore(store: ChatStore): void {
  activeStore = store
}

/** 空流式状态初始值 */
function initialStreamState(): import('@/atoms/chat').ConversationStreamState {
  return {
    streaming: false,
    content: '',
    reasoning: '',
    model: undefined,
    toolActivities: [],
    startedAt: Date.now(),
  }
}

/** 更新某对话的流式状态（无则创建） */
function updateState(store: ChatStore, convId: string, updater: (prev: import('@/atoms/chat').ConversationStreamState) => import('@/atoms/chat').ConversationStreamState): void {
  store.set(streamingStatesAtom, (prev: Map<string, import('@/atoms/chat').ConversationStreamState>) => {
    const current = prev.get(convId) ?? initialStreamState()
    const next = updater(current)
    const map = new Map(prev)
    map.set(convId, next)
    return map
  })
}

/** 递增某对话的消息刷新版本号 */
function bumpRefresh(store: ChatStore, convId: string): void {
  store.set(chatMessageRefreshAtom, (prev: Map<string, number>) => {
    const map = new Map(prev)
    map.set(convId, (prev.get(convId) ?? 0) + 1)
    return map
  })
}

/**
 * 处理单条 chat_event。返回被处理的 conversationId；未知 channel 返回 null。
 */
export function handleChatEvent(evt: ChatWorkflowEvent): string | null {
  if (!activeStore) return null
  const store = activeStore
  const { conversationId, channel, payload } = evt

  switch (channel) {
    case CHAT_IPC_CHANNELS.STREAM_CHUNK: {
      const p = payload as StreamChunkEvent
      updateState(store, conversationId, (s) => ({ ...s, content: s.content + p.delta }))
      return conversationId
    }

    case CHAT_IPC_CHANNELS.STREAM_REASONING: {
      const p = payload as StreamReasoningEvent
      updateState(store, conversationId, (s) => ({ ...s, reasoning: s.reasoning + p.delta }))
      return conversationId
    }

    case CHAT_IPC_CHANNELS.STREAM_COMPLETE: {
      // 置 streaming=false，保留 content/reasoning 作过渡气泡；流式状态清除交给 ChatView 重载后。
      updateState(store, conversationId, (s) => ({ ...s, streaming: false }))
      bumpRefresh(store, conversationId)
      return conversationId
    }

    case CHAT_IPC_CHANNELS.STREAM_ERROR: {
      const p = payload as StreamErrorEvent
      updateState(store, conversationId, (s) => ({ ...s, streaming: false }))
      store.set(chatStreamErrorsAtom, (prev: Map<string, string>) => {
        const map = new Map(prev)
        map.set(conversationId, p.error)
        return map
      })
      store.set(chatStreamErrorCodesAtom, (prev: Map<string, string>) => {
        const map = new Map(prev)
        if (p.code) map.set(conversationId, p.code)
        else map.delete(conversationId)
        return map
      })
      bumpRefresh(store, conversationId)
      return conversationId
    }

    case CHAT_IPC_CHANNELS.STREAM_TOOL_ACTIVITY: {
      const p = payload as StreamToolActivityEvent
      updateState(store, conversationId, (s) => ({ ...s, toolActivities: [...s.toolActivities, p.activity] }))
      return conversationId
    }

    default:
      // 未知通道，忽略（协议冻结，未来可能新增通道不在此处理）
      return null
  }
}
