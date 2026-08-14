/**
 * ChatMessageItem — 移动端单条 Chat 消息渲染（apps/tablet-client）
 *
 * 对齐桌面 components/chat/ChatMessageItem.tsx 的视觉，复用已迁移的 ai-elements
 * （Message / MessageHeader / MessageContent / MessageResponse / UserMessageContent /
 *  Reasoning / StreamingIndicator / MessageStopped / MessageAttachments / MessageActions）。
 *
 * 移动端删减（对齐 ui-port-map 红线约束）：
 *  - 模型 logo（getModelLogo/resolveModelProvider）→ Bot 图标占位 + resolveModelDisplayName 纯文本
 *  - UserAvatar/userProfile 头像图片 → User 图标占位 + 用户名（本地极简 userProfile）
 *  - CopyButton / MigrateToAgentButton / InlineEditForm / DeleteMessageDialog / KnowledgeReferenceCards
 *    → 删除原子对话框保留（AlertDialog），复制/迁移/编辑/知识库预览全部砍掉
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import { AlertCircle, Bot, Trash2 } from 'lucide-react'
import {
  Message,
  MessageHeader,
  MessageContent,
  MessageActions,
  MessageAction,
  MessageResponse,
  UserMessageContent,
  MessageStopped,
  StreamingIndicator,
  MessageAttachments,
} from '@/components/ai-elements/message'
import {
  Reasoning,
  ReasoningTrigger,
  ReasoningContent,
} from '@/components/ai-elements/reasoning'
import { ChatToolActivityIndicator } from './ChatToolActivityIndicator'
import { UserAvatar } from './UserAvatar'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { channelsAtom } from '@/atoms/session'
import { userProfileAtom, DEFAULT_USER_NAME, DEFAULT_USER_AVATAR } from '@/atoms/ui-atoms'
import { cn } from '@/lib/utils'
import { formatMessageTime, resolveModelDisplayName } from '@/lib/chat-utils'
import type { ChatMessage } from '@profer/shared'

interface ChatMessageItemProps {
  message: ChatMessage
  isStreaming?: boolean
  isLastAssistant?: boolean
  onDeleteMessage?: (messageId: string) => Promise<void>
}

export const ChatMessageItem = React.memo(function ChatMessageItem({
  message,
  isStreaming = false,
  isLastAssistant = false,
  onDeleteMessage,
}: ChatMessageItemProps): React.ReactElement {
  const [deleteDialogOpen, setDeleteDialogOpen] = React.useState(false)
  const [isDeleting, setIsDeleting] = React.useState(false)
  const channels = useAtomValue(channelsAtom)
  const userProfile = useAtomValue(userProfileAtom)

  const userName = userProfile.userName || DEFAULT_USER_NAME

  const handleDeleteConfirm = async (): Promise<void> => {
    if (!onDeleteMessage) return
    setIsDeleting(true)
    try {
      await onDeleteMessage(message.id)
    } finally {
      setIsDeleting(false)
      setDeleteDialogOpen(false)
    }
  }

  const modelDisplay = resolveModelDisplayName(message.model, channels) ?? message.model

  return (
    <>
      <Message from={message.role}>
        {/* assistant 头像 + 模型名 + 时间 */}
        {message.role === 'assistant' && (
          <MessageHeader
            model={modelDisplay}
            time={formatMessageTime(message.createdAt)}
            logo={
              <div className="flex size-[35px] items-center justify-center rounded-[25%] bg-muted text-muted-foreground">
                <Bot className="size-4" />
              </div>
            }
          />
        )}

        {/* user 头像（emoji）+ 用户名 + 时间（对齐桌面 UserAvatar） */}
        {message.role === 'user' && (
          <div className="flex items-start gap-2.5 mb-2.5">
            <UserAvatar avatar={userProfile.avatar ?? DEFAULT_USER_AVATAR} size={35} />
            <div className="flex flex-col justify-between h-[35px]">
              <span className="text-sm font-semibold text-foreground/60 leading-none">{userName}</span>
              <span className="message-time text-[10px] text-foreground/[0.38] leading-none">{formatMessageTime(message.createdAt)}</span>
            </div>
          </div>
        )}

        <MessageContent>
          {message.role === 'assistant' ? (
            <>
              {message.toolActivities && message.toolActivities.length > 0 && (
                <ChatToolActivityIndicator activities={message.toolActivities} />
              )}

              {message.reasoning && (
                <Reasoning isStreaming={isStreaming && !message.content} defaultOpen={isStreaming && !message.content}>
                  <ReasoningTrigger />
                  <ReasoningContent>{message.reasoning}</ReasoningContent>
                </Reasoning>
              )}

              {message.content ? (
                <>
                  <MessageResponse>{message.content}</MessageResponse>
                  {isStreaming && isLastAssistant && !message.stopped && <StreamingIndicator />}
                </>
              ) : message.error ? null : message.stopped ? (
                <MessageStopped />
              ) : null}

              {message.error && (
                <div className="mt-1 px-3 py-2 rounded-md bg-destructive/10 text-destructive text-sm flex items-center gap-2">
                  <AlertCircle className="size-4 shrink-0" />
                  <span className="break-all">{message.error}</span>
                </div>
              )}

              {message.attachments && message.attachments.length > 0 && (
                <MessageAttachments attachments={message.attachments} />
              )}
            </>
          ) : (
            <>
              {message.attachments && message.attachments.length > 0 && (
                <MessageAttachments attachments={message.attachments} />
              )}
              {message.content && <UserMessageContent>{message.content}</UserMessageContent>}
            </>
          )}
        </MessageContent>

        {(message.content || message.error || (message.attachments && message.attachments.length > 0)) && !isStreaming && (
          <MessageActions className="pl-[46px] mt-0.5 min-h-[28px]">
            {onDeleteMessage && (
              <MessageAction tooltip="删除" onClick={() => setDeleteDialogOpen(true)}>
                <Trash2 className="size-3.5" />
              </MessageAction>
            )}
            {message.role === 'assistant' && message.error && (
              <span className={cn('text-[11px] text-destructive ml-1 flex items-center gap-0.5')}>
                <AlertCircle className="size-3" />
                生成失败
              </span>
            )}
            {message.role === 'assistant' && message.stopped && !message.error && (
              <span className="text-[11px] text-foreground/40 ml-1">（已中止）</span>
            )}
          </MessageActions>
        )}
      </Message>

      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除这条消息？</AlertDialogTitle>
            <AlertDialogDescription>删除后不可恢复。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isDeleting}>取消</AlertDialogCancel>
            <AlertDialogAction disabled={isDeleting} onClick={() => void handleDeleteConfirm()}>
              {isDeleting ? '删除中…' : '删除'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
})
