/**
 * ChatView — 移动端 Chat 对话流主容器（apps/tablet-client）
 *
 * 对齐桌面 components/chat/ChatView.tsx 的职责（参数化 conversationId 版本），
 * 但数据源从 Electron IPC 换成 remote-service WS 指令（client.xxx）。
 *
 * 三段式布局：ChatHeader | ChatMessages | Composer。
 *
 * 数据链路（与 AgentView 同构）：
 *  - 选中对话 → get_conversation_messages 写 conversationMessagesAtom
 *  - chat_event（chunk/reasoning/complete/error/tool-activity）→ lib/chatEvents.ts 写 atoms
 *  - chatMessageRefreshAtom 版本变化 → 重拉消息 + 清理流式状态（消除空档闪烁）
 *  - handleSend → 乐观清理输入 + chat_send_message；handleStop → chat_stop_generation
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import { ChatHeader } from './ChatHeader'
import { ChatMessages } from './ChatMessages'
import { Composer } from '@/components/agent/Composer'
import { useConnection } from '@/hooks/useConnection'
import {
  conversationsAtom,
  currentConversationAtom,
  conversationMessagesAtom,
  streamingStatesAtom,
  chatStreamErrorsAtom,
  chatStreamErrorCodesAtom,
  chatMessageRefreshAtom,
} from '@/atoms/chat'
import { channelIdAtom, modelIdAtom } from '@/atoms/session'
import type { ChatMessage, ConversationMeta } from '@profer/shared'

interface ChatViewProps {
  conversationId: string
  /** 隐藏 ChatHeader（由外部顶栏承担标题时使用，首版移动端保留 header） */
  hideChatHeader?: boolean
}

export function ChatView({ conversationId, hideChatHeader = false }: ChatViewProps): React.ReactElement {
  const { client } = useConnection()

  const [conversations, setConversations] = useAtom(conversationsAtom)
  const [messagesMap, setMessagesMap] = useAtom(conversationMessagesAtom)
  const [streamingStates, setStreamingStates] = useAtom(streamingStatesAtom)
  const [streamErrors, setStreamErrors] = useAtom(chatStreamErrorsAtom)
  const [streamErrorCodes, setStreamErrorCodes] = useAtom(chatStreamErrorCodesAtom)
  const refreshVersion = useAtomValue(chatMessageRefreshAtom).get(conversationId) ?? 0
  const conversation = useAtomValue(currentConversationAtom)
  const channelId = useAtomValue(channelIdAtom)
  const modelId = useAtomValue(modelIdAtom)

  const [input, setInput] = React.useState('')

  const messages = messagesMap.get(conversationId) ?? []
  const streamState = streamingStates.get(conversationId)
  const streaming = streamState?.streaming ?? false
  const streamError = streamErrors.get(conversationId)
  const streamErrorCode = streamErrorCodes.get(conversationId)

  // ===== 加载对话消息 =====
  const loadMessages = React.useCallback(async (): Promise<void> => {
    if (!client) return
    try {
      const data = (await client.getConversationMessages(conversationId)) as ChatMessage[]
      const list = Array.isArray(data) ? data : []
      setMessagesMap((prev) => {
        const map = new Map(prev)
        map.set(conversationId, list)
        return map
      })
    } catch (e) {
      console.error('[ChatView] 加载消息失败', e)
    }
  }, [client, conversationId, setMessagesMap])

  // 选中对话变化时加载
  React.useEffect(() => {
    void loadMessages()
  }, [loadMessages])

  // ===== 流式完成/错误 → 重拉消息 + 清理流式状态 =====
  React.useEffect(() => {
    if (refreshVersion === 0) return
    void (async () => {
      await loadMessages()
      // 消息重载完成后清理流式状态（气泡已由持久化消息接管）
      setStreamingStates((prev) => {
        if (!prev.has(conversationId)) return prev
        const map = new Map(prev)
        map.delete(conversationId)
        return map
      })
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshVersion])

  // ===== 发送 =====
  const handleSend = React.useCallback(async (): Promise<void> => {
    const text = input.trim()
    if (!text || !client || !channelId) return
    if (streaming) return

    // 清除上一轮错误
    setStreamErrors((prev) => {
      if (!prev.has(conversationId)) return prev
      const map = new Map(prev)
      map.delete(conversationId)
      return map
    })
    setStreamErrorCodes((prev) => {
      if (!prev.has(conversationId)) return prev
      const map = new Map(prev)
      map.delete(conversationId)
      return map
    })

    // 初始化流式状态
    setStreamingStates((prev) => {
      const map = new Map(prev)
      map.set(conversationId, {
        streaming: true,
        content: '',
        reasoning: '',
        model: modelId ?? undefined,
        toolActivities: [],
        startedAt: Date.now(),
      })
      return map
    })

    setInput('')

    try {
      await client.chatSendMessage({
        conversationId,
        userMessage: text,
        channelId,
        modelId: modelId ?? undefined,
      })
    } catch (e) {
      console.error('[ChatView] 发送消息失败', e)
      setStreamingStates((prev) => {
        const cur = prev.get(conversationId)
        if (!cur) return prev
        const map = new Map(prev)
        map.set(conversationId, { ...cur, streaming: false })
        return map
      })
      setStreamErrors((prev) => {
        const map = new Map(prev)
        map.set(conversationId, String(e))
        return map
      })
    }
  }, [input, client, channelId, modelId, streaming, conversationId, setStreamingStates, setStreamErrors, setStreamErrorCodes])

  // ===== 停止 =====
  const handleStop = React.useCallback((): void => {
    if (!client) return
    client.chatStopGeneration(conversationId).catch((e) => console.error('[ChatView] 停止失败', e))
  }, [client, conversationId])

  // ===== 更新标题 =====
  const handleUpdateTitle = React.useCallback(
    async (id: string, title: string): Promise<void> => {
      if (!client) return
      const updated = (await client.updateConversationTitle(id, title)) as ConversationMeta
      setConversations((prev) => prev.map((c) => (c.id === updated.id ? updated : c)))
    },
    [client, setConversations],
  )

  // ===== 删除消息 =====
  const handleDeleteMessage = React.useCallback(
    async (messageId: string): Promise<void> => {
      if (!client) return
      await client.chatDeleteMessage(conversationId, messageId)
      await loadMessages()
    },
    [client, conversationId, loadMessages],
  )

  const canSend = input.trim().length > 0 && !!channelId && !streaming

  return (
    <div className="flex h-full min-h-0 flex-col">
      {!hideChatHeader && <ChatHeader conversation={conversation} onUpdateTitle={handleUpdateTitle} />}

      {/* 结构化错误（如额度不足） */}
      {streamErrorCode && !streaming && (
        <div className="shrink-0 bg-destructive/10 px-4 py-2 text-[13px] text-destructive">{streamError || streamErrorCode}</div>
      )}

      <ChatMessages
        messages={messages}
        streaming={streaming}
        streamingContent={streamState?.content ?? ''}
        streamingReasoning={streamState?.reasoning ?? ''}
        streamingModel={streamState?.model ?? null}
        startedAt={streamState?.startedAt}
        toolActivities={streamState?.toolActivities ?? []}
        onDeleteMessage={handleDeleteMessage}
      />

      <Composer
        value={input}
        onChange={setInput}
        onSend={() => void handleSend()}
        onStop={handleStop}
        canSend={canSend}
        streaming={streaming}
        backgroundWaiting={false}
        stopping={false}
        placeholder="发送消息给 Chat…"
      />
    </div>
  )
}
