/**
 * AgentView.tsx — 对话流主容器（移动端瘦客户端）
 *
 * 职责（对齐 docs/agent-flow-design.md 4.3 章）：
 *  - 消费流式 atoms（agentStreamingStatesAtom / liveMessagesMapAtom /
 *    stoppedByUserSessionsAtom / agentStreamErrorsAtom / 三类交互队列）。
 *  - 订阅 agentMessageRefreshAtom 重拉持久化消息；paginateFirst 首帧 + pullEarlier 补更早。
 *  - agentSDKMessagesCacheAtom 做切换会话的 LRU 内存缓存。
 *  - 消息加载完成后清理流式展示状态与 liveMessages（running/backgroundWaiting 会话跳过）。
 *  - handleSend：乐观用户消息 + 生成 startedAt + 写缓存；handleStop：状态机 + 防重入。
 *  - 渲染 AgentHeader + AgentMessages + 三个交互横幅 + Composer。
 *
 * 数据加载走 useConnection().client（WsClient），channelId/modelId/workspaceId 从 atoms 解析。
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import type { SDKMessage, PermissionRequest, AskUserRequest, ExitPlanModeRequest } from '@profer/shared'
import {
  agentStreamingStatesAtom,
  liveMessagesMapAtom,
  agentMessageRefreshAtom,
  agentSDKMessagesCacheAtom,
  stoppedByUserSessionsAtom,
  agentStreamErrorsAtom,
  allPendingPermissionRequestsAtom,
  allPendingAskUserRequestsAtom,
  allPendingExitPlanRequestsAtom,
  setSessionMessagesCache,
  type AgentStreamState,
} from '@/atoms/agent'
import {
  sessionsAtom,
  currentWorkspaceIdAtom,
  channelIdAtom,
  modelIdAtom,
} from '@/atoms/session'
import { useConnection } from '@/hooks/useConnection'
import { requestAgentStop } from '@/lib/agentEvents'
import { AgentMessages } from './AgentMessages'
import { Composer } from './Composer'

/** 首帧分页窗口（对齐桌面 tabletMode paginateFirst） */
const PAGINATE_FIRST = 4

/** get_sdk_messages 分页返回结构 */
interface SDKMessagePage {
  messages: SDKMessage[]
  total: number
  startIndex: number
  endIndex: number
  hasMore: boolean
}

export interface AgentViewProps {
  sessionId: string
  hideAgentHeader?: boolean
}

export function AgentView({ sessionId, hideAgentHeader }: AgentViewProps): React.ReactElement {
  const { client } = useConnection()

  const sessions = useAtomValue(sessionsAtom)
  const channelId = useAtomValue(channelIdAtom)
  const modelId = useAtomValue(modelIdAtom)
  const workspaceId = useAtomValue(currentWorkspaceIdAtom)

  const streamingStates = useAtomValue(agentStreamingStatesAtom)
  const liveMessagesMap = useAtomValue(liveMessagesMapAtom)
  const refreshMap = useAtomValue(agentMessageRefreshAtom)
  const cacheMap = useAtomValue(agentSDKMessagesCacheAtom)
  const stoppedByUserSet = useAtomValue(stoppedByUserSessionsAtom)
  const streamErrors = useAtomValue(agentStreamErrorsAtom)
  const permissionRequests = useAtomValue(allPendingPermissionRequestsAtom)
  const askUserRequests = useAtomValue(allPendingAskUserRequestsAtom)
  const exitPlanRequests = useAtomValue(allPendingExitPlanRequestsAtom)

  const setStreamingStates = useSetAtom(agentStreamingStatesAtom)
  const setLiveMessages = useSetAtom(liveMessagesMapAtom)
  const setCache = useSetAtom(agentSDKMessagesCacheAtom)
  const setStoppedByUser = useSetAtom(stoppedByUserSessionsAtom)
  const setStreamErrors = useSetAtom(agentStreamErrorsAtom)

  const [persistedSDKMessages, setPersistedSDKMessages] = React.useState<SDKMessage[]>([])
  const [messagesLoaded, setMessagesLoaded] = React.useState(false)
  const [historyHasMore, setHistoryHasMore] = React.useState(false)
  const [historyLoading, setHistoryLoading] = React.useState(false)
  const [historyStartIndex, setHistoryStartIndex] = React.useState<number | undefined>(undefined)
  const [input, setInput] = React.useState('')

  const pullInFlightRef = React.useRef(false)
  const stopInFlightRef = React.useRef(false)
  const loadingSessionIdRef = React.useRef<string | null>(null)
  const loadedOnceRef = React.useRef(false)

  const sessionMeta = React.useMemo(
    () => sessions.find((s) => s.id === sessionId),
    [sessions, sessionId],
  )

  const streamState: AgentStreamState | undefined = streamingStates.get(sessionId)
  const liveMessages: SDKMessage[] = liveMessagesMap.get(sessionId) ?? []
  const refreshVersion = refreshMap.get(sessionId) ?? 0
  const stoppedByUser = stoppedByUserSet.has(sessionId)
  const streamError = streamErrors.get(sessionId)

  const streaming = !!streamState?.running
  const backgroundWaiting = !!streamState?.backgroundWaiting
  const stopping = !!streamState?.stopping

  // 解析会话的实际 channelId / modelId / workspaceId（会话元数据优先，全局默认兜底）
  const sessionChannelId = sessionMeta?.channelId ?? channelId ?? ''
  const sessionModelId = sessionMeta?.modelId ?? modelId ?? undefined
  const sessionWorkspaceId = sessionMeta?.workspaceId ?? workspaceId ?? undefined

  // 当前会话的阻塞交互请求
  const curPermission = permissionRequests.get(sessionId) ?? []
  const curAskUser = askUserRequests.get(sessionId) ?? []
  const curExitPlan = exitPlanRequests.get(sessionId) ?? []

  // ===== 消息加载：首帧分页 + 触顶补更早 + 缓存 LRU =====
  const loadFirstPage = React.useCallback(async (): Promise<void> => {
    if (!client) return
    // 命中缓存立即填充，消除"先清空→等读盘"空窗
    const cached = cacheMap.get(sessionId)
    if (cached && cached.length > 0) {
      setPersistedSDKMessages(cached)
      setMessagesLoaded(true)
      loadedOnceRef.current = true
    }
    try {
      const data = (await client.getSdkMessages(sessionId, { targetMessages: PAGINATE_FIRST })) as SDKMessagePage
      const msgs = Array.isArray(data?.messages) ? data.messages : (Array.isArray(data) ? (data as unknown as SDKMessage[]) : [])
      // 兼容两种返回：分页结构 or 原始数组
      if (Array.isArray(data) && !Array.isArray(data?.messages)) {
        // 原始数组返回
        setPersistedSDKMessages(msgs)
        setHistoryHasMore(false)
        setHistoryStartIndex(undefined)
      } else {
        const page = data as SDKMessagePage
        setPersistedSDKMessages(page.messages ?? [])
        setHistoryHasMore(page.hasMore ?? false)
        setHistoryStartIndex(page.hasMore ? page.startIndex : undefined)
      }
      setMessagesLoaded(true)
      loadedOnceRef.current = true
      // 写缓存（LRU）
      setCache((prev) => setSessionMessagesCache(prev, sessionId, msgs))
    } catch (e) {
      console.error('[AgentView] 加载消息失败', e)
      setMessagesLoaded(true)
      loadedOnceRef.current = true
    }
  }, [client, sessionId, cacheMap, setCache, setPersistedSDKMessages, setMessagesLoaded])

  const loadEarlier = React.useCallback(async (): Promise<void> => {
    if (!client || pullInFlightRef.current || !historyHasMore || historyStartIndex === undefined) return
    pullInFlightRef.current = true
    setHistoryLoading(true)
    try {
      const data = (await client.getSdkMessages(sessionId, { before: historyStartIndex, targetMessages: PAGINATE_FIRST })) as SDKMessagePage
      const page = data as SDKMessagePage
      setPersistedSDKMessages((prev) => {
        const seen = new Set(prev.map(sdkStableKey))
        const earlier = (page.messages ?? []).filter((m) => !seen.has(sdkStableKey(m)))
        return [...earlier, ...prev]
      })
      setHistoryHasMore(page.hasMore ?? false)
      setHistoryStartIndex(page.hasMore ? page.startIndex : undefined)
    } catch (e) {
      console.error('[AgentView] 加载更早消息失败', e)
    } finally {
      pullInFlightRef.current = false
      setHistoryLoading(false)
    }
  }, [client, sessionId, historyHasMore, historyStartIndex])

  // 会话切换：切到新会话时立即加载首帧
  React.useEffect(() => {
    if (loadingSessionIdRef.current === sessionId) return
    loadingSessionIdRef.current = sessionId
    loadedOnceRef.current = false
    setMessagesLoaded(false)
    setHistoryHasMore(false)
    setHistoryStartIndex(undefined)
    // 命中缓存立即填充，否则显示加载态
    const cached = cacheMap.get(sessionId)
    if (cached && cached.length > 0) {
      setPersistedSDKMessages(cached)
      setMessagesLoaded(true)
      loadedOnceRef.current = true
    } else {
      setPersistedSDKMessages([])
    }
    void loadFirstPage()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  // ===== 消息加载完成后：清理流式展示状态 + liveMessages =====
  // （对齐设计文档 1.5 节：清理在消息加载完成后执行；running/backgroundWaiting 会话跳过）
  React.useEffect(() => {
    if (!refreshVersion || !loadedOnceRef.current) return
    // 流结束触发刷新：清理当前会话的实时消息累积，避免与持久化消息重复
    const st = streamingStates.get(sessionId)
    if (st?.running || st?.backgroundWaiting) return
    setLiveMessages((prev) => {
      if (!prev.has(sessionId)) return prev
      const next = new Map(prev)
      next.delete(sessionId)
      return next
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshVersion, sessionId])

  // 切换会话时清理上一个会话的实时消息（保留 running 会话）
  React.useEffect(() => {
    return () => {
      const st = streamingStates.get(sessionId)
      if (st?.running || st?.backgroundWaiting) return
      setLiveMessages((prev) => {
        if (!prev.has(sessionId)) return prev
        const next = new Map(prev)
        next.delete(sessionId)
        return next
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  // ===== handleSend =====
  const handleSend = React.useCallback(async (): Promise<void> => {
    const text = input.trim()
    if (!text || !client) return
    if (streaming || backgroundWaiting) return
    if (stopping) return
    if (!sessionChannelId) return

    // 清除上一轮打断标记 + 错误
    setStoppedByUser((prev) => {
      if (!prev.has(sessionId)) return prev
      const next = new Set(prev)
      next.delete(sessionId)
      return next
    })
    setStreamErrors((prev) => {
      if (!prev.has(sessionId)) return prev
      const next = new Map(prev)
      next.delete(sessionId)
      return next
    })

    const streamStartedAt = Date.now()

    // 初始化流式状态（startedAt 由渲染进程生成，主进程原样回传）
    setStreamingStates((prev) => {
      const map = new Map(prev)
      const existing = prev.get(sessionId)
      map.set(sessionId, {
        running: true,
        content: '',
        toolActivities: existing?.toolActivities ?? [],
        model: sessionModelId,
        startedAt: streamStartedAt,
        inputTokens: existing?.inputTokens,
        contextWindow: existing?.contextWindow,
      })
      return map
    })

    // 乐观用户消息（写 persisted + cache，避免"切走再切回"回退旧数组）
    const tempUserMsg: SDKMessage = {
      type: 'user',
      message: { content: [{ type: 'text', text }] },
      parent_tool_use_id: null,
    } as unknown as SDKMessage
    ;(tempUserMsg as Record<string, unknown>)._createdAt = Date.now()
    setPersistedSDKMessages((prev) => [...prev, tempUserMsg])
    setCache((prev) => setSessionMessagesCache(prev, sessionId, [...(prev.get(sessionId) ?? []), tempUserMsg]))

    setInput('')

    try {
      await client.sendMessage({
        sessionId,
        userMessage: text,
        channelId: sessionChannelId,
        modelId: sessionModelId,
        workspaceId: sessionWorkspaceId,
      })
    } catch (e) {
      console.error('[AgentView] 发送消息失败', e)
      setStreamingStates((prev) => {
        const cur = prev.get(sessionId)
        if (!cur) return prev
        const map = new Map(prev)
        map.set(sessionId, { ...cur, running: false })
        return map
      })
      setStreamErrors((prev) => {
        const next = new Map(prev)
        next.set(sessionId, String(e))
        return next
      })
    }
  }, [
    input, client, streaming, backgroundWaiting, stopping, sessionChannelId, sessionModelId,
    sessionWorkspaceId, sessionId, setStoppedByUser, setStreamErrors, setStreamingStates,
    setPersistedSDKMessages, setCache, setInput,
  ])

  // ===== handleStop =====
  const handleStop = React.useCallback((): void => {
    if (stopInFlightRef.current || streamState?.stopping) return
    stopInFlightRef.current = true

    setStreamingStates((prev) => {
      const cur = prev.get(sessionId)
      if (!cur || (!cur.running && !cur.backgroundWaiting)) return prev
      const map = new Map(prev)
      map.set(sessionId, { ...cur, stopping: true })
      return map
    })

    requestAgentStop(sessionId, (sid) => {
      if (!client) return Promise.reject(new Error('无连接'))
      return client.stopAgent(sid)
    })

    stopInFlightRef.current = false
  }, [sessionId, streamState?.stopping, setStreamingStates, client])

  // ===== 交互横幅响应 =====
  const respondPermission = React.useCallback(
    (requestId: string, behavior: 'allow' | 'deny') => {
      client?.respondPermission(requestId, behavior).catch((e) => console.error('[AgentView] respondPermission 失败', e))
    },
    [client],
  )

  const respondAskUser = React.useCallback(
    (requestId: string, answers: Record<string, string>) => {
      client?.respondAskUser(requestId, answers).catch((e) => console.error('[AgentView] respondAskUser 失败', e))
    },
    [client],
  )

  const respondExitPlan = React.useCallback(
    (requestId: string, action: 'approve_auto' | 'approve_edit' | 'deny' | 'feedback') => {
      client?.respondExitPlanMode(requestId, action).catch((e) => console.error('[AgentView] respondExitPlan 失败', e))
    },
    [client],
  )

  const canSend = input.trim().length > 0 && !!sessionChannelId && messagesLoaded && !streaming && !backgroundWaiting && !stopping && !!client

  return (
    <div className="flex h-full min-h-0 flex-col">
      {!hideAgentHeader && <AgentHeader title={sessionMeta?.title ?? '会话'} />}

      {streamError && !streaming && (
        <div className="shrink-0 bg-destructive/10 px-4 py-2 text-[13px] text-destructive">
          <span>{streamError}</span>
        </div>
      )}

      {curPermission.map((req) => (
        <PermissionBanner
          key={req.requestId}
          request={req}
          onRespond={respondPermission}
          onRequestStop={handleStop}
        />
      ))}
      {curAskUser.map((req) => (
        <AskUserBanner
          key={req.requestId}
          request={req}
          onRespond={respondAskUser}
          onRequestStop={handleStop}
        />
      ))}
      {curExitPlan.map((req) => (
        <ExitPlanBanner
          key={req.requestId}
          request={req}
          onRespond={respondExitPlan}
          onRequestStop={handleStop}
        />
      ))}

      <AgentMessages
        sessionId={sessionId}
        sessionModelId={sessionModelId}
        messagesLoaded={messagesLoaded}
        persistedSDKMessages={persistedSDKMessages}
        streaming={streaming}
        streamState={streamState}
        liveMessages={liveMessages}
        stoppedByUser={stoppedByUser}
        onLoadEarlierHistory={historyHasMore ? loadEarlier : undefined}
        historyMoreAvailable={historyHasMore}
        historyLoadingEarlier={historyLoading}
      />

      <Composer
        value={input}
        onChange={setInput}
        onSend={handleSend}
        onStop={handleStop}
        canSend={canSend}
        streaming={streaming}
        backgroundWaiting={backgroundWaiting}
        stopping={stopping}
        disabled={!client}
      />
    </div>
  )
}

// ============================================================================
// AgentHeader
// ============================================================================

function AgentHeader({ title }: { title: string }): React.ReactElement {
  return (
    <header className="flex shrink-0 items-center border-b border-border px-4 py-2.5">
      <span className="overflow-hidden text-ellipsis whitespace-nowrap text-sm font-semibold">{title || '未命名会话'}</span>
    </header>
  )
}

// ============================================================================
// 交互横幅（最小可用）
// ============================================================================

function PermissionBanner({
  request,
  onRespond,
  onRequestStop,
}: {
  request: PermissionRequest
  onRespond: (requestId: string, behavior: 'allow' | 'deny') => void
  onRequestStop: () => void
}): React.ReactElement {
  return (
    <div className="flex shrink-0 flex-col gap-2 border-b border-border bg-primary/5 px-4 py-3">
      <div className="text-[13px] font-bold text-primary">权限请求</div>
      <div className="break-words text-[13px] text-muted-foreground">{request.description || request.toolName}</div>
      <div className="flex flex-wrap gap-2">
        <button type="button" className="interaction-btn interaction-btn-primary" onClick={() => onRespond(request.requestId, 'allow')}>
          允许
        </button>
        <button type="button" className="interaction-btn" onClick={() => onRespond(request.requestId, 'deny')}>
          拒绝
        </button>
        <button type="button" className="interaction-btn interaction-btn-danger" onClick={onRequestStop}>
          停止
        </button>
      </div>
    </div>
  )
}

function AskUserBanner({
  request,
  onRespond,
  onRequestStop,
}: {
  request: AskUserRequest
  onRespond: (requestId: string, answers: Record<string, string>) => void
  onRequestStop: () => void
}): React.ReactElement {
  // 首版：每个问题渲染为可选 chip，选一个后提交
  const [selections, setSelections] = React.useState<Record<string, string>>({})

  const submit = (): void => {
    onRespond(request.requestId, selections)
  }

  return (
    <div className="flex shrink-0 flex-col gap-2 border-b border-border bg-primary/5 px-4 py-3">
      <div className="text-[13px] font-bold text-primary">问答</div>
      {request.questions.map((q) => (
        <div key={q.question} className="flex flex-col gap-1.5">
          <div className="text-[13px] text-foreground">{q.question}</div>
          <div className="flex flex-wrap gap-1.5">
            {q.options.map((opt) => (
              <button
                key={opt.label}
                type="button"
                className={`interaction-chip ${selections[q.question] === opt.label ? 'interaction-chip-active' : ''}`}
                onClick={() => setSelections((prev) => ({ ...prev, [q.question]: opt.label }))}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
      ))}
      <div className="flex flex-wrap gap-2">
        <button type="button" className="interaction-btn interaction-btn-primary" onClick={submit} disabled={request.questions.some((q) => !selections[q.question])}>
          提交
        </button>
        <button type="button" className="interaction-btn interaction-btn-danger" onClick={onRequestStop}>
          停止
        </button>
      </div>
    </div>
  )
}

function ExitPlanBanner({
  request,
  onRespond,
  onRequestStop,
}: {
  request: ExitPlanModeRequest
  onRespond: (requestId: string, action: 'approve_auto' | 'approve_edit' | 'deny' | 'feedback') => void
  onRequestStop: () => void
}): React.ReactElement {
  return (
    <div className="flex shrink-0 flex-col gap-2 border-b border-border bg-primary/5 px-4 py-3">
      <div className="text-[13px] font-bold text-primary">退出计划模式</div>
      {request.allowedPrompts.length > 0 && (
        <div className="break-words text-[13px] text-muted-foreground">
          {request.allowedPrompts.map((p) => p.prompt).join('；')}
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        <button type="button" className="interaction-btn interaction-btn-primary" onClick={() => onRespond(request.requestId, 'approve_auto')}>
          批准并执行
        </button>
        <button type="button" className="interaction-btn" onClick={() => onRespond(request.requestId, 'deny')}>
          拒绝
        </button>
        <button type="button" className="interaction-btn interaction-btn-danger" onClick={onRequestStop}>
          停止
        </button>
      </div>
    </div>
  )
}

// ============================================================================
// 稳定 key（与 AgentMessages.tsx 保持一致，用于 earlier 去重）
// ============================================================================

function sdkStableKey(message: SDKMessage): string {
  const record = message as Record<string, unknown>
  if (typeof record.uuid === 'string' && record.uuid.length > 0) {
    return `${message.type}:uuid:${record.uuid}`
  }
  const sessionId = typeof record.session_id === 'string' ? record.session_id : ''
  const parent = typeof record.parent_tool_use_id === 'string' ? record.parent_tool_use_id : ''
  const content = 'message' in record ? (record.message as { content?: unknown })?.content : undefined
  return `${message.type}:${sessionId}:${parent}:${JSON.stringify(content ?? record)}`
}
