/**
 * main.tsx — 移动端 App 组装入口（apps/tablet-client）
 *
 * 这是把协议层 / 连接层 / atoms / 事件处理器 / 会话列表 / 对话流 UI 全部串起来
 * 的关键装配点。三条关键接线（对应子任务交付要求）：
 *
 *  1. onAgentEvent → handleAgentEvent：
 *     下方 App 内 useConnection({ onAgentEvent }) 把每个 WS agent_event 转发给
 *     lib/agentEvents.ts 的 handleAgentEvent（纯函数状态机），写回流式 atoms。
 *
 *  2. registerAgentEventStore(store)：
 *     下方 App 挂载时把 jotai store 注入 agentEvents 模块，让 handleAgentEvent /
 *     requestAgentStop / reconcileStaleStreams 能读写真实 atoms。
 *
 *  3. registerCurrentSessionGetter(getter)：
 *     下方 App 挂载时注入「读取当前会话 ID」的只读 getter，供 agentStreamErrorsAtom 的
 *     派生原子 currentAgentErrorAtom 使用（避免 agent.ts 反向依赖 session.ts 循环）。
 *
 * 额外补上 handleAgentEvent 没做的两件事（对齐 desktop tablet main.tsx）：
 *  - run_completed / run_idle 到达后刷新会话列表（标题/时间/active 更新）。
 *  - 刷新列表后用 reconcileStaleStreams 做陈旧流兜底（断线丢事件时强制清理 running）。
 *
 * 注意：整个 App 使用同一个显式创建的 jotai store（模块级 tabletStore），
 * Provider 注入同一实例，agentEvents 模块的 registerAgentEventStore /
 * registerCurrentSessionGetter 也绑定同一实例，避免 getDefaultStore() 与 Provider
 * 各自持有一个 store 导致 atoms 写读分裂。
 */

import React from 'react'
import { createRoot } from 'react-dom/client'
import { Provider, createStore, useAtomValue, useSetAtom } from 'jotai'
import { Toaster } from 'sonner'
// Inter Variable 字体（对齐桌面 main.tsx，自托管 Inter，保证与桌面渲染同一字体）
import '@fontsource-variable/inter/index.css'
import './styles/globals.css'

import { useConnection } from '@/hooks/useConnection'
import { getLastView, saveLastView } from '@/lib/storage'
import { handleAgentEvent, registerAgentEventStore, reconcileStaleStreams } from '@/lib/agentEvents'
import { handleChatEvent, registerChatEventStore } from '@/lib/chatEvents'
import { registerCurrentSessionGetter } from '@/atoms/agent'
import { currentSessionIdAtom, sessionsAtom, sessionsLoadedAtom } from '@/atoms/session'
import { appModeAtom } from '@/atoms/app-mode'
import { currentConversationIdAtom, conversationsAtom, conversationsLoadedAtom, chatMessageRefreshAtom, streamingStatesAtom as chatStreamingStatesAtom } from '@/atoms/chat'
import { channelIdAtom, modelIdAtom } from '@/atoms/session'
import type { AgentWorkflowEvent, ChatWorkflowEvent } from '@/client/ws-client'
import type { AgentSessionMeta, ConversationMeta, ChatMessage } from '@profer/shared'
import { ConnectionGate } from '@/components/ConnectionGate'
import { AgentView } from '@/components/agent/AgentView'
import { ChatView } from '@/components/chat/ChatView'
import { NativeTabletSidebar } from '@/components/sidebar/NativeTabletSidebar'
import { TooltipProvider } from '@/components/ui/tooltip'

// ===== 移动模式标记（模块顶层执行，在对齐桌面 tablet/main.tsx）=====
// Portal 到 body 的组件（设置弹窗/工具提示）需要 CSS 定向；
// body.tablet-mode 触发 globals.css 里的移动端专属覆盖（消息气泡对齐/隐藏滚动条/安全区）。
if (typeof document !== 'undefined') {
  document.body.classList.add('tablet-mode')
}

// 模块级单一 store：Provider / agentEvents 模块 / 只读派生均绑定同一实例
const tabletStore: ReturnType<typeof createStore> = createStore()

// ============================================================================
// App 顶层：注册 store 访问器 + 当前会话 getter + 事件接线 + 响应式布局
// ============================================================================

function App(): React.ReactElement {
  const setSessions = useSetAtom(sessionsAtom)
  const setSessionsLoaded = useSetAtom(sessionsLoadedAtom)
  const setConversations = useSetAtom(conversationsAtom)
  const setConversationsLoaded = useSetAtom(conversationsLoadedAtom)
  const setCurrentSessionId = useSetAtom(currentSessionIdAtom)
  const setCurrentConversationId = useSetAtom(currentConversationIdAtom)
  const setAppMode = useSetAtom(appModeAtom)

  // 三条接线中的 2、3 条：注册 store 访问器与当前会话 getter（挂载即完成一次）
  React.useEffect(() => {
    registerAgentEventStore({
      get: (atom) => tabletStore.get(atom as never) as never,
      set: (atom, value) => {
        tabletStore.set(atom as never, value as never)
      },
    })
    registerChatEventStore({
      get: (atom) => tabletStore.get(atom as never) as never,
      set: (atom, value) => {
        tabletStore.set(atom as never, value as never)
      },
    })
    registerCurrentSessionGetter(() => tabletStore.get(currentSessionIdAtom))
  }, [])

  // 第 1 条接线：useConnection 的 onAgentEvent 转发给 handleAgentEvent；
  // 并在 run_completed / run_idle 后刷新会话列表 + 陈旧流兜底。
  const { client, status } = useConnection({
    onAgentEvent: (evt: AgentWorkflowEvent) => {
      handleAgentEvent(evt)

      const p = evt.payload as { kind?: string; event?: { type?: string } } | null
      const isRunEnd =
        p?.kind === 'profer_event' && p.event && (p.event.type === 'run_completed' || p.event.type === 'run_idle')
      if (!isRunEnd) return

      // 刷新会话列表（标题/时间/active 更新），完成后做陈旧流兜底。
      // client 在断线时可能为 null，跳过（重连后 useConnection 会自动重拉）。
      const c = client
      if (!c || !c.isOpen()) return
      void (async () => {
        try {
          const data = (await c.listSessions()) as AgentSessionMeta[]
          if (!Array.isArray(data)) return
          setSessions(data)
          setSessionsLoaded(true)
          const remoteActiveIds = new Set(
            data.filter((s) => (s as unknown as { active?: boolean }).active).map((s) => s.id),
          )
          reconcileStaleStreams(remoteActiveIds)
        } catch (e) {
          console.error('[main.tsx] 刷新会话列表失败', e)
        }
      })()
    },
    // Chat 流式事件 → handleChatEvent 写 atoms；并在 complete 后刷新对话列表 + 首条消息生成标题。
    onChatEvent: (evt: ChatWorkflowEvent) => {
      handleChatEvent(evt)

      // 流式完成/错误时刷新对话列表（updatedAt 已更新）
      const c = client
      if (!c || !c.isOpen()) return
      const isEnd = evt.channel === 'chat:stream:complete' || evt.channel === 'chat:stream:error'
      if (!isEnd) return

      void (async () => {
        try {
          const list = (await c.listConversations()) as ConversationMeta[]
          if (!Array.isArray(list)) return
          setConversations(list)
          setConversationsLoaded(true)

          // 首条消息完成后生成标题（仅当标题为空）
          const conv = list.find((x) => x.id === evt.conversationId)
          if (conv && (!conv.title || conv.title.trim() === '')) {
            void generateChatTitle(c, evt.conversationId)
          }
        } catch (e) {
          console.error('[main.tsx] 刷新对话列表失败', e)
        }
      })()
    },
  })

  // 横屏宽屏（≥1024 且 landscape）固定侧栏；竖屏/小屏隐藏侧栏，用抽屉式侧栏（顶栏汉堡菜单触发）。
  const [wide, setWide] = React.useState(() =>
    typeof window !== 'undefined' && window.matchMedia('(orientation: landscape) and (min-width: 1024px)').matches,
  )
  React.useEffect(() => {
    const mq = window.matchMedia('(orientation: landscape) and (min-width: 1024px)')
    const onChange = (): void => setWide(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  // 竖屏抽屉开关（桌面原版 NativeTabletSidebar 的 sidebarOpen / setSidebarOpen）
  const [sidebarOpen, setSidebarOpen] = React.useState(false)

  // 用于恢复上次视图时查找目标是否存在
  const sessions = useAtomValue(sessionsAtom)
  const conversations = useAtomValue(conversationsAtom)
  const currentSessionId = useAtomValue(currentSessionIdAtom)
  const currentConversationId = useAtomValue(currentConversationIdAtom)
  const appMode = useAtomValue(appModeAtom)

  // ===== 连接就绪后恢复上次视图（登录状态留存：刷新/冷启动回到上次位置）=====
  // 对齐桌面 tablet main.tsx 的 last-view 恢复逻辑。
  React.useEffect(() => {
    if (status !== 'open') return
    // 已有选中项（用户可能已手动点选）则不抢覆盖
    if (currentSessionId || currentConversationId) return

    const last = getLastView()
    if (last?.mode === 'chat') {
      const conv = last.conversationId ? conversations.find((c) => c.id === last.conversationId) : undefined
      if (conv) {
        setAppMode('chat')
        setCurrentConversationId(conv.id)
        return
      }
      const first = conversations.find((c) => !c.archived)
      if (first) {
        setAppMode('chat')
        setCurrentConversationId(first.id)
        return
      }
    }
    if (last?.mode === 'agent') {
      const s = last.sessionId ? sessions.find((x) => x.id === last.sessionId) : undefined
      if (s) {
        setAppMode('agent')
        setCurrentSessionId(s.id)
        return
      }
      if (sessions[0]) {
        setAppMode('agent')
        setCurrentSessionId(sessions[0].id)
        return
      }
    }
    // 无记录：按当前持久化 appMode 兕底
    if (appMode === 'chat') {
      const first = conversations.find((c) => !c.archived)
      if (first) {
        setCurrentConversationId(first.id)
        return
      }
    }
    if (sessions[0]) setCurrentSessionId(sessions[0].id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, sessions, conversations, appMode])

  // ===== 保存上次视图（当前模式 + 会话/对话 ID 变化时持久化）=====
  React.useEffect(() => {
    if (!currentSessionId && !currentConversationId) return
    saveLastView({
      mode: appMode,
      sessionId: currentSessionId ?? undefined,
      conversationId: currentConversationId ?? undefined,
    })
  }, [appMode, currentSessionId, currentConversationId])

  return (
    <ConnectionGate>
      <div
        className="tablet-app-root flex h-full w-full overflow-hidden bg-background p-0 text-foreground landscape:min-[1024px]:p-2 tablet-safe-area"
        data-wide={wide ? 'true' : 'false'}
      >
        {/* 横屏固定侧栏 + 竖屏抽屉（二选一渲染，由 NativeTabletSidebar 内部 media 控制） */}
        <NativeTabletSidebar mobileOpen={sidebarOpen} onDismiss={() => setSidebarOpen(false)} />

        <main className="flex h-full min-w-0 flex-1 flex-col">
          {/* 竖屏顶栏：汉堡菜单（横屏隐藏） */}
          <button
            type="button"
            className="landscape:min-[1024px]:hidden fixed left-3 top-3 z-40 flex h-9 w-9 items-center justify-center rounded-md border border-border bg-background/80 backdrop-blur-sm text-foreground/70 shadow-sm"
            onClick={() => setSidebarOpen(true)}
            aria-label="打开会话导航"
          >
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
              <path d="M2 4.5h14M2 9h14M2 13.5h14" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
          <SessionRouter />
        </main>
      </div>
      <Toaster position="top-center" theme="system" richColors />
    </ConnectionGate>
  )
}

/** 根据当前模式 + 会话/对话是否选中，渲染 AgentView / ChatView / 空态 */
function SessionRouter(): React.ReactElement {
  const mode = useAtomValue(appModeAtom)
  const currentSessionId = useAtomValue(currentSessionIdAtom)
  const currentConversationId = useAtomValue(currentConversationIdAtom)

  if (mode === 'chat') {
    if (!currentConversationId) {
      return (
        <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground/50">
          <p className="text-[15px] text-muted-foreground">选择一个对话开始</p>
          <p className="text-[13px]">或点击左上角「菜单」打开会话导航新建对话</p>
        </div>
      )
    }
    return <ChatView conversationId={currentConversationId} />
  }

  if (!currentSessionId) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground/50">
        <p className="text-[15px] text-muted-foreground">选择一个会话开始</p>
        <p className="text-[13px]">或点击左上角「菜单」打开会话导航新建会话</p>
      </div>
    )
  }

  return <AgentView sessionId={currentSessionId} hideAgentHeader={false} />
}

/**
 * 首条消息完成后生成对话标题。
 * 取对话第一条 user 消息 → chat_generate_title → update_conversation_title。
 */
async function generateChatTitle(client: NonNullable<ReturnType<typeof useConnection>['client']>, conversationId: string): Promise<void> {
  try {
    const msgs = (await client.getConversationMessages(conversationId)) as ChatMessage[]
    const firstUser = Array.isArray(msgs) ? msgs.find((m) => m.role === 'user') : undefined
    if (!firstUser?.content) return
    const channelId = tabletStore.get(channelIdAtom)
    const modelId = tabletStore.get(modelIdAtom)
    if (!channelId || !modelId) return
    const title = (await client.chatGenerateTitle({
      userMessage: firstUser.content,
      channelId,
      modelId,
    })) as string
    if (!title) return
    const updated = (await client.updateConversationTitle(conversationId, title)) as ConversationMeta
    tabletStore.set(conversationsAtom, (prev: ConversationMeta[]) =>
      prev.map((c) => (c.id === updated.id ? updated : c)),
    )
  } catch (e) {
    console.error('[main.tsx] 生成标题失败', e)
  }
}

// ============================================================================
// root 挂载
// ============================================================================

const container = document.getElementById('root')
if (container) {
  const root = createRoot(container)
  root.render(
    <React.StrictMode>
      <Provider store={tabletStore}>
        {/* radix Tooltip 必须包在 TooltipProvider 内；侧边栏/对话区大量使用 Tooltip */}
        <TooltipProvider>
          <App />
        </TooltipProvider>
      </Provider>
    </React.StrictMode>,
  )
}
