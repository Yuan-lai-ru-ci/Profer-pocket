/**
 * ChatHeader — 移动端对话头部（apps/tablet-client）
 *
 * 对齐桌面 components/chat/ChatHeader.tsx 的标题 + 点击编辑视觉，删减：
 *  - SystemPromptSelector（系统提示词选择器，桌面专属）
 *  - 并排模式切换 / 置顶按钮（移动端首版砍掉）
 * 保留标题编辑（update_conversation_title）。
 */

import * as React from 'react'
import { Pencil, Check, X } from 'lucide-react'
import type { ConversationMeta } from '@profer/shared'

interface ChatHeaderProps {
  conversation: ConversationMeta | null
  onUpdateTitle: (conversationId: string, title: string) => Promise<void>
}

export function ChatHeader({ conversation, onUpdateTitle }: ChatHeaderProps): React.ReactElement | null {
  const [editing, setEditing] = React.useState(false)
  const [editTitle, setEditTitle] = React.useState('')
  const inputRef = React.useRef<HTMLInputElement>(null)

  if (!conversation) return null

  const startEdit = (): void => {
    setEditTitle(conversation.title)
    setEditing(true)
    requestAnimationFrame(() => inputRef.current?.focus())
  }

  const saveTitle = async (): Promise<void> => {
    const trimmed = editTitle.trim()
    if (!trimmed || trimmed === conversation.title) {
      setEditing(false)
      return
    }
    try {
      await onUpdateTitle(conversation.id, trimmed)
    } catch (error) {
      console.error('[ChatHeader] 更新标题失败:', error)
    }
    setEditing(false)
  }

  const handleKeyDown = (e: React.KeyboardEvent): void => {
    if (e.key === 'Enter') {
      e.preventDefault()
      void saveTitle()
    } else if (e.key === 'Escape') {
      setEditing(false)
    }
  }

  return (
    <div className="relative z-[51] flex items-center gap-2 px-4 h-[48px]">
      {editing ? (
        <div className="flex items-center gap-1.5 flex-1 min-w-0">
          <input
            ref={inputRef}
            value={editTitle}
            onChange={(e) => setEditTitle(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={() => void saveTitle()}
            className="flex-1 bg-transparent text-sm font-medium border-b border-primary/50 outline-none px-0 py-0.5 min-w-0"
            maxLength={100}
          />
          <button type="button" onClick={() => void saveTitle()} className="p-1 text-muted-foreground hover:text-foreground transition-colors">
            <Check className="size-3.5" />
          </button>
          <button type="button" onClick={() => setEditing(false)} className="p-1 text-muted-foreground hover:text-foreground transition-colors">
            <X className="size-3.5" />
          </button>
        </div>
      ) : (
        <div className="flex items-center gap-1.5 flex-1 min-w-0">
          <span className="truncate text-sm font-medium text-foreground">{conversation.title}</span>
          <button
            type="button"
            onClick={startEdit}
            className="p-1 text-muted-foreground hover:text-foreground transition-colors"
            aria-label="编辑标题"
          >
            <Pencil className="size-3.5" />
          </button>
        </div>
      )}
    </div>
  )
}
