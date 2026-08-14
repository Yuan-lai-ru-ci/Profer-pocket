/**
 * ChatConversationList — 移动端 Chat 对话列表（apps/tablet-client）
 *
 * 独立的精简列表（不复用 Agent 的 use-left-sidebar 庞杂逻辑）：
 *  - 顶部「新对话」按钮
 *  - 置顶 / 最近（未归档未置顶）两组分组
 *  - 点选进入 ChatView；右键：置顶/取消置顶、归档、删除
 * 数据源：list_conversations → conversationsAtom；选择 → currentConversationIdAtom。
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import { Plus, MessageSquare, Pin, PinOff, Archive, Trash2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useConnection } from '@/hooks/useConnection'
import {
  conversationsAtom,
  currentConversationIdAtom,
  conversationsLoadedAtom,
} from '@/atoms/chat'
import { channelIdAtom, modelIdAtom } from '@/atoms/session'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import { formatRelativeUpdatedAt } from './sidebar-utils'
import type { ConversationMeta } from '@profer/shared'

function ConversationRow({
  conv,
  active,
  now,
  onClick,
  onTogglePin,
  onToggleArchive,
  onDelete,
}: {
  conv: ConversationMeta
  active: boolean
  now: number
  onClick: () => void
  onTogglePin: () => void
  onToggleArchive: () => void
  onDelete: () => void
}): React.ReactElement {
  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>
        <button
          type="button"
          onClick={onClick}
          className={cn(
            'group flex w-full items-center gap-2 rounded-[8px] px-2.5 py-2 text-left text-[13px] transition-colors',
            active ? 'bg-primary/10 text-foreground' : 'text-foreground/80 hover:bg-primary/5',
          )}
        >
          <MessageSquare className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate">{conv.title || '新对话'}</span>
          <span className="shrink-0 text-[10px] text-muted-foreground/60">
            {formatRelativeUpdatedAt(conv.updatedAt, now)}
          </span>
        </button>
      </ContextMenuTrigger>
      <ContextMenuContent className="w-44">
        <ContextMenuItem onClick={onTogglePin}>
          {conv.pinned ? <PinOff className="size-3.5" /> : <Pin className="size-3.5" />}
          {conv.pinned ? '取消置顶' : '置顶'}
        </ContextMenuItem>
        <ContextMenuItem onClick={onToggleArchive}>
          <Archive className="size-3.5" />
          {conv.archived ? '取消归档' : '归档'}
        </ContextMenuItem>
        <ContextMenuSeparator />
        <ContextMenuItem onClick={onDelete} className="text-destructive focus:text-destructive">
          <Trash2 className="size-3.5" />
          删除
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  )
}

export function ChatConversationList(): React.ReactElement {
  const { client } = useConnection()
  const [conversations, setConversations] = useAtom(conversationsAtom)
  const [currentId, setCurrentId] = useAtom(currentConversationIdAtom)
  const loaded = useAtomValue(conversationsLoadedAtom)
  const setLoaded = useSetAtom(conversationsLoadedAtom)
  const channelId = useAtomValue(channelIdAtom)
  const modelId = useAtomValue(modelIdAtom)
  const [now, setNow] = React.useState(() => Date.now())

  // 时间 tick
  React.useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => window.clearInterval(id)
  }, [])

  // 加载对话列表
  const loadConversations = React.useCallback(async (): Promise<void> => {
    if (!client) return
    try {
      const list = (await client.listConversations()) as ConversationMeta[]
      if (Array.isArray(list)) {
        setConversations(list)
        setLoaded(true)
      }
    } catch (e) {
      console.error('[ChatConversationList] 加载对话失败', e)
    }
  }, [client, setConversations, setLoaded])

  React.useEffect(() => {
    void loadConversations()
  }, [loadConversations])

  const pinned = conversations.filter((c) => c.pinned && !c.archived)
  const recent = conversations
    .filter((c) => !c.pinned && !c.archived)
    .sort((a, b) => b.updatedAt - a.updatedAt)

  const handleNewConversation = async (): Promise<void> => {
    if (!client) return
    try {
      const meta = (await client.createConversation({ channelId: channelId ?? undefined, modelId: modelId ?? undefined })) as ConversationMeta
      setConversations((prev) => [meta, ...prev])
      setCurrentId(meta.id)
    } catch (e) {
      console.error('[ChatConversationList] 新建对话失败', e)
    }
  }

  const handleSelect = (id: string): void => setCurrentId(id)

  const handleTogglePin = async (id: string): Promise<void> => {
    if (!client) return
    await client.toggleConversationPin(id)
    await loadConversations()
  }

  const handleToggleArchive = async (id: string): Promise<void> => {
    if (!client) return
    await client.toggleConversationArchive(id)
    await loadConversations()
  }

  const handleDelete = async (id: string): Promise<void> => {
    if (!client) return
    await client.deleteConversation(id)
    if (currentId === id) setCurrentId(null)
    await loadConversations()
  }

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* 新对话按钮 */}
      <div className="px-3 pt-2">
        <button
          onClick={() => void handleNewConversation()}
          className="flex w-full items-center gap-2 px-3 py-2 rounded-[10px] text-[13px] font-medium text-foreground/70 bg-primary/5 hover:bg-primary/10 hover:text-foreground transition-colors border border-border/60 hover:border-border"
        >
          <Plus size={14} />
          <span>新对话</span>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-2 pb-2">
        {!loaded || conversations.length === 0 ? (
          <div className="pt-8 text-center text-[13px] text-muted-foreground/60">
            {loaded ? '暂无对话' : '加载中…'}
          </div>
        ) : (
          <>
            {pinned.length > 0 && (
              <div className="pt-2">
                <div className="pl-[18px] pr-3.5 pb-1 text-[13px] font-medium leading-[18px] text-foreground/40 select-none">置顶</div>
                {pinned.map((c) => (
                  <ConversationRow
                    key={c.id}
                    conv={c}
                    active={c.id === currentId}
                    now={now}
                    onClick={() => handleSelect(c.id)}
                    onTogglePin={() => void handleTogglePin(c.id)}
                    onToggleArchive={() => void handleToggleArchive(c.id)}
                    onDelete={() => void handleDelete(c.id)}
                  />
                ))}
              </div>
            )}

            {recent.length > 0 && (
              <div className={pinned.length > 0 ? 'pt-2' : 'pt-0'}>
                {pinned.length > 0 && (
                  <div className="pl-[18px] pr-3.5 pb-1 text-[13px] font-medium leading-[18px] text-foreground/40 select-none">最近</div>
                )}
                {recent.map((c) => (
                  <ConversationRow
                    key={c.id}
                    conv={c}
                    active={c.id === currentId}
                    now={now}
                    onClick={() => handleSelect(c.id)}
                    onTogglePin={() => void handleTogglePin(c.id)}
                    onToggleArchive={() => void handleToggleArchive(c.id)}
                    onDelete={() => void handleDelete(c.id)}
                  />
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

