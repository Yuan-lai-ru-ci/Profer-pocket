import React from 'react'
import { AgentView } from '@/components/agent'
import { ChatView } from '@/components/chat'
import { Button } from '@/components/ui/button'
import { Plus } from 'lucide-react'

interface PocketSessionContentProps {
  appMode: 'agent' | 'chat' | 'scratch'
  currentSessionId: string | null
  currentChatId: string | null
  userName: string
  wide: boolean
  onCreateSession: () => void
  onCreateConversation: () => void
}

export function PocketSessionContent({
  appMode,
  currentSessionId,
  currentChatId,
  userName,
  wide,
  onCreateSession,
  onCreateConversation,
}: PocketSessionContentProps): React.ReactElement {
  if (appMode === 'chat') {
    if (currentChatId) return <ChatView conversationId={currentChatId} pocketMode hideChatHeader={!wide} />
    return <PocketEmptyState userName={userName} description="开始你的第一个 Chat 对话，与 Agent 共享渠道与模型" actionLabel="新建对话" onAction={onCreateConversation} />
  }

  if (currentSessionId) return <AgentView sessionId={currentSessionId} pocketMode hideAgentHeader={!wide} />
  return <PocketEmptyState userName={userName} description="开始你的第一个 Agent 会话，Token 消耗热力图将在这里显示" actionLabel="新建会话" onAction={onCreateSession} />
}

function PocketEmptyState({ userName, description, actionLabel, onAction }: { userName: string; description: string; actionLabel: string; onAction: () => void }): React.ReactElement {
  return (
    <div className="flex h-full flex-col items-center justify-center px-6 text-center">
      <div className="max-w-sm space-y-2">
        <div className="text-[22px] font-semibold tracking-tight text-foreground">{userName}，早上好</div>
        <p className="mt-16 text-[13px] leading-5 text-muted-foreground">{description}</p>
        <Button type="button" variant="outline" size="sm" onClick={onAction} className="mt-3 h-9 gap-1.5"><Plus className="size-3.5" />{actionLabel}</Button>
      </div>
    </div>
  )
}
