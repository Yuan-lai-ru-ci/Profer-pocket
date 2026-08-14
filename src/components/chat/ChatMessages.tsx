/**
 * ChatMessages — 移动端 Chat 消息列表（apps/tablet-client）
 *
 * 对齐桌面 components/chat/ChatMessages.tsx 的渲染，复用已迁移的
 * Conversation / ConversationContent（use-stick-to-bottom）+ useSmoothStream。
 *
 * 移动端删减（对齐 ui-port-map 红线约束）：
 *  - ScrollMinimap / tabMinimapCacheAtom（迷你地图）→ 不搬
 *  - ContextDivider（上下文分隔线）→ 首版不搬（无 update_context_dividers UI）
 *  - 历史消息分页 onLoadMore / ScrollTopLoader → 首版一次性 get_conversation_messages
 */

import * as React from 'react'
import { Bot } from 'lucide-react'
import {
  Conversation,
  ConversationContent,
} from '@/components/ai-elements/conversation'
import {
  Message,
  MessageHeader,
  MessageContent,
  MessageResponse,
  MessageLoading,
  StreamingIndicator,
} from '@/components/ai-elements/message'
import {
  Reasoning,
  ReasoningTrigger,
  ReasoningContent,
} from '@/components/ai-elements/reasoning'
import { ChatMessageItem } from './ChatMessageItem'
import { ChatToolActivityIndicator } from './ChatToolActivityIndicator'
import { TabletModeContext } from '@/components/ai-elements/tablet-mode-context'
import { useSmoothStream } from '@/lib/useSmoothStream'
import { formatMessageTime } from '@/lib/chat-utils'
import type { ChatMessage, ChatToolActivity } from '@profer/shared'

interface ChatMessagesProps {
  messages: ChatMessage[]
  /** 是否流式中 */
  streaming: boolean
  /** 流式累积内容 */
  streamingContent: string
  /** 流式推理内容 */
  streamingReasoning: string
  /** 流式消息绑定模型 */
  streamingModel: string | null
  /** 流式开始时间戳 */
  startedAt?: number
  /** 流式工具活动列表 */
  toolActivities: ChatToolActivity[]
  /** 删除消息回调 */
  onDeleteMessage?: (messageId: string) => Promise<void>
}

export function ChatMessages({
  messages,
  streaming,
  streamingContent,
  streamingReasoning,
  streamingModel,
  startedAt,
  toolActivities,
  onDeleteMessage,
}: ChatMessagesProps): React.ReactElement {
  // 平滑流式输出：内容 + 推理 各一条平滑流
  const { displayedContent: smoothContent } = useSmoothStream({ content: streamingContent, isStreaming: streaming })
  const { displayedContent: smoothReasoning } = useSmoothStream({ content: streamingReasoning, isStreaming: streaming })

  const streamingTime = React.useMemo(() => formatMessageTime(startedAt ?? Date.now()), [startedAt])

  const hasMessages = messages.length > 0
  const showStreamingBubble = streaming || !!smoothContent || !!smoothReasoning

  return (
    <TabletModeContext.Provider value={true}>
      <Conversation className="h-full min-h-0 flex-1">
        <ConversationContent className="px-1">
          {!hasMessages && !showStreamingBubble ? (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-muted-foreground/60">
              <Bot className="size-8" />
              <p className="text-[14px]">开始一段对话吧</p>
            </div>
          ) : (
            <>
              {messages.map((msg) => (
                <div key={msg.id} data-message-id={msg.id}>
                  <ChatMessageItem
                    message={msg}
                    isStreaming={false}
                    isLastAssistant={false}
                    onDeleteMessage={onDeleteMessage}
                  />
                </div>
              ))}

              {/* 正在生成 / 停止后等待磁盘消息加载的临时 assistant 消息 */}
              {showStreamingBubble && (
                <Message from="assistant">
                  <MessageHeader
                    model={streamingModel ?? undefined}
                    time={streamingTime}
                    logo={
                      <div className="flex size-[35px] items-center justify-center rounded-[25%] bg-muted text-muted-foreground">
                        <Bot className="size-4" />
                      </div>
                    }
                  />
                  <MessageContent>
                    <ChatToolActivityIndicator activities={toolActivities} isStreaming={streaming} />

                    {smoothReasoning && (
                      <Reasoning isStreaming={streaming && !smoothContent} defaultOpen>
                        <ReasoningTrigger />
                        <ReasoningContent>{smoothReasoning}</ReasoningContent>
                      </Reasoning>
                    )}

                    {smoothContent ? (
                      <>
                        <MessageResponse>{smoothContent}</MessageResponse>
                        {streaming && <StreamingIndicator />}
                      </>
                    ) : (
                      streaming && !smoothReasoning && <MessageLoading startedAt={startedAt} />
                    )}
                  </MessageContent>
                  <div className="pl-[46px] mt-0.5 min-h-[28px]" />
                </Message>
              )}
            </>
          )}
        </ConversationContent>
      </Conversation>
    </TabletModeContext.Provider>
  )
}
