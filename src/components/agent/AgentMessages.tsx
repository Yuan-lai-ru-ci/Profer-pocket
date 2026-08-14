/**
 * AgentMessages — Agent 消息列表（移动端瘦客户端版）
 *
 * 复用桌面「对话渲染内核」：groupIntoTurns + MessageGroupRenderer + ContentBlock，
 * 用 use-stick-to-bottom 的 Conversation 容器做自动滚底，useSmoothStream 做流式平滑。
 *
 * 相较桌面 AgentMessages 移除了：
 *  - ScrollMinimap / StickyUserMessage / ScrollPositionMemory（tabletMode 恒 true 不渲染）
 *  - model-logo 模型图标（用 Bot 头像）
 *  - WelcomeEmptyState（内联空态欢迎语）
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import { Bot, RotateCw, AlertTriangle, ChevronDown, ChevronRight } from 'lucide-react'
import {
  Message,
  MessageHeader,
  MessageContent,
  BasePathsProvider,
} from '@/components/ai-elements/message'
import { TabletModeContext } from '@/components/ai-elements/tablet-mode-context'
import {
  Conversation,
  ConversationContent,
  ConversationScrollButton,
} from '@/components/ai-elements/conversation'
import { useStickToBottomContext } from 'use-stick-to-bottom'
import { useSmoothStream } from '@/lib/useSmoothStream'
import { cn } from '@/lib/utils'
import { Spinner } from '@/components/ui/spinner'
import { groupIntoTurns, MessageGroupRenderer, getGroupId, parseAttachedFiles as sdkParseAttachedFiles, isImageFile as sdkIsImageFile, CompactingIndicator, buildHistoricalTaskSubjects, type MessageGroup } from './SDKMessageRenderer'
import { extractUserText, parseThinkTagsFromText } from '@profer/session-core'
import { buildLiveGroupSet } from './live-group-set'
import { ContentBlock } from './ContentBlock'
import type { SDKMessage } from '@profer/shared'
import type { AgentStreamState } from '@/atoms/agent'

function stableStringify(value: unknown): string {
  if (value == null || typeof value !== 'object') return JSON.stringify(value) ?? String(value)
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  const record = value as Record<string, unknown>
  return `{${Object.keys(record).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(record[key])}`).join(',')}}`
}

const stableKeyCache = new WeakMap<object, string>()
let stableKeyFallbackCounter = 0

function getSDKMessageStableKey(message: SDKMessage): string {
  const record = message as Record<string, unknown>
  if (typeof record.uuid === 'string' && record.uuid.length > 0) {
    return `${message.type}:uuid:${record.uuid}`
  }
  if (stableKeyCache.has(message)) return stableKeyCache.get(message)!

  const parentToolUseId = typeof record.parent_tool_use_id === 'string' ? record.parent_tool_use_id : ''
  const sessionId = typeof record.session_id === 'string' ? record.session_id : ''

  let key: string
  if (message.type === 'result') {
    const result = record as { subtype?: unknown; terminal_reason?: unknown; result?: unknown }
    key = `result:${sessionId}:${String(result.subtype ?? '')}:${String(result.terminal_reason ?? '')}:${String(result.result ?? '')}:${++stableKeyFallbackCounter}`
  } else if (message.type === 'system') {
    const sys = record as { subtype?: unknown; task_id?: unknown; tool_use_id?: unknown }
    key = `system:${sessionId}:${String(sys.subtype ?? '')}:${String(sys.task_id ?? '')}:${String(sys.tool_use_id ?? '')}:${stableStringify(record)}:${++stableKeyFallbackCounter}`
  } else if ('message' in record) {
    const inner = record.message as { content?: unknown } | undefined
    key = `${message.type}:${sessionId}:${parentToolUseId}:${stableStringify(inner?.content)}:${++stableKeyFallbackCounter}`
  } else {
    key = `${message.type}:${sessionId}:${parentToolUseId}:${stableStringify(record)}:${++stableKeyFallbackCounter}`
  }

  stableKeyCache.set(message, key)
  return key
}

/** AgentMessages 属性接口 */
interface AgentMessagesProps {
  sessionId: string
  sessionModelId?: string
  messagesLoaded?: boolean
  persistedSDKMessages?: SDKMessage[]
  streaming: boolean
  streamState?: AgentStreamState
  liveMessages?: SDKMessage[]
  sessionPath?: string | null
  attachedDirs?: string[]
  stoppedByUser?: boolean
  /** 移动端触顶自动加载：还有更早消息时可触发 */
  onLoadEarlierHistory?: () => void
  historyMoreAvailable?: boolean
  historyLoadingEarlier?: boolean
}

/** 空状态引导 */
function EmptyState(): React.ReactElement {
  return (
    <div className="flex flex-col items-center gap-2 py-16 text-muted-foreground/50">
      <div className="flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <Bot className="h-7 w-7 text-muted-foreground/60" />
      </div>
      <p className="text-base text-muted-foreground">开始新对话</p>
      <p className="text-[13px]">在下方输入框发送第一条消息</p>
    </div>
  )
}

/**
 * 移动端触顶自动加载控制器（渲染在 <Conversation> 内部获取 scrollRef）。
 */
function TopHistoryLoader({
  onLoadEarlierHistory,
  historyMoreAvailable,
  historyLoadingEarlier,
}: {
  onLoadEarlierHistory?: () => void
  historyMoreAvailable?: boolean
  historyLoadingEarlier?: boolean
}): React.ReactElement | null {
  const { scrollRef } = useStickToBottomContext()
  const armedRef = React.useRef(true)

  React.useEffect(() => {
    if (!onLoadEarlierHistory) return
    const el = scrollRef.current
    if (!el) return
    const handleScroll = (): void => {
      const top = el.scrollTop
      if (top > 40) {
        armedRef.current = true
        return
      }
      if (top <= 40 && historyMoreAvailable !== false && !historyLoadingEarlier && armedRef.current) {
        armedRef.current = false
        onLoadEarlierHistory()
      }
    }
    el.addEventListener('scroll', handleScroll, { passive: true })
    return () => el.removeEventListener('scroll', handleScroll)
  }, [onLoadEarlierHistory, historyMoreAvailable, historyLoadingEarlier, scrollRef])

  return null
}

/** 新一轮对话自动跟随滚动控制器 */
function StreamScrollFollow({ streaming }: { streaming: boolean }): React.ReactElement | null {
  const { scrollToBottom } = useStickToBottomContext()
  const prevStreamingRef = React.useRef(streaming)

  React.useEffect(() => {
    const wasStreaming = prevStreamingRef.current
    prevStreamingRef.current = streaming
    if (streaming && !wasStreaming) {
      scrollToBottom({ animation: 'smooth', wait: true })
    }
  }, [streaming, scrollToBottom])

  return null
}

function AssistantLogo({ model }: { model?: string }): React.ReactElement {
  return (
    <div className="flex size-[35px] items-center justify-center rounded-[25%] bg-primary/10">
      <Bot size={18} className="text-primary" />
    </div>
  )
}

/** 重试提示组件 - 折叠式（移动端保留重试状态展示） */
function RetryingNotice({ retrying }: { retrying: NonNullable<AgentStreamState['retrying']> }): React.ReactElement {
  const [expanded, setExpanded] = React.useState(false)
  const [countdown, setCountdown] = React.useState(0)

  React.useEffect(() => {
    if (retrying.failed || retrying.history.length === 0) {
      setCountdown(0)
      return
    }
    const lastAttempt = retrying.history[retrying.history.length - 1]
    if (!lastAttempt) return
    const updateCountdown = (): void => {
      const elapsed = (Date.now() - lastAttempt.timestamp) / 1000
      const remaining = Math.max(0, lastAttempt.delaySeconds - elapsed)
      setCountdown(Math.ceil(remaining))
      if (remaining <= 0) setCountdown(0)
    }
    updateCountdown()
    const timer = setInterval(updateCountdown, 100)
    return () => clearInterval(timer)
  }, [retrying.failed, retrying.history])

  return (
    <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50/50 p-3 dark:border-amber-800 dark:bg-amber-950/20">
      <button
        type="button"
        className="flex w-full items-center gap-2 text-left transition-opacity hover:opacity-80"
        onClick={() => setExpanded(!expanded)}
      >
        {retrying.failed ? (
          <AlertTriangle className="size-4 shrink-0 text-amber-600 dark:text-amber-400" />
        ) : (
          <RotateCw className="size-4 shrink-0 animate-spin text-amber-600 dark:text-amber-400" />
        )}
        <span className="flex-1 text-sm text-amber-900 dark:text-amber-100">
          {retrying.failed
            ? `重试失败 (${retrying.currentAttempt}/${retrying.maxAttempts})`
            : countdown > 0
              ? `重试倒计时 ${countdown}秒 (${retrying.currentAttempt}/${retrying.maxAttempts})`
              : `重试中 (${retrying.currentAttempt}/${retrying.maxAttempts})`}
          {retrying.history.length > 0 && ` · ${retrying.history[retrying.history.length - 1]?.reason}`}
        </span>
        {expanded ? (
          <ChevronDown className="size-4 shrink-0 text-amber-600 dark:text-amber-400" />
        ) : (
          <ChevronRight className="size-4 shrink-0 text-amber-600 dark:text-amber-400" />
        )}
      </button>
    </div>
  )
}

/** Agent 运行指示器 */
function AgentRunningIndicator({ startedAt }: { startedAt?: number }): React.ReactElement {
  const [elapsed, setElapsed] = React.useState(0)

  React.useEffect(() => {
    const start = startedAt ?? Date.now()
    const update = (): void => setElapsed((Date.now() - start) / 1000)
    update()
    const timer = setInterval(update, 100)
    return () => clearInterval(timer)
  }, [startedAt])

  const formatTime = (seconds: number): string => {
    if (seconds < 60) return `${seconds.toFixed(1)}s`
    const m = Math.floor(seconds / 60)
    const s = seconds % 60
    return `${m}m ${s.toFixed(1)}s`
  }

  return (
    <div className="flex min-h-[28px] items-center gap-2">
      <Spinner size="sm" className="text-primary/75" />
      <span className="text-[13px] font-light tabular-nums text-muted-foreground/75">Agent Running {formatTime(elapsed)}</span>
    </div>
  )
}

function formatMessageTime(ts: number): string {
  const d = new Date(ts)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

export function AgentMessages({
  sessionId,
  sessionModelId,
  messagesLoaded,
  persistedSDKMessages,
  streaming,
  streamState,
  liveMessages,
  sessionPath,
  attachedDirs,
  stoppedByUser,
  onLoadEarlierHistory,
  historyMoreAvailable,
  historyLoadingEarlier,
}: AgentMessagesProps): React.ReactElement {
  const [ready, setReady] = React.useState(false)
  const [skipFadeIn, setSkipFadeIn] = React.useState(false)
  const prevSessionIdRef = React.useRef<string | null>(null)

  React.useEffect(() => {
    if (sessionId !== prevSessionIdRef.current) {
      prevSessionIdRef.current = sessionId
      setReady(false)
      setSkipFadeIn(false)
    }
  }, [sessionId])

  React.useEffect(() => {
    if (ready) return
    if (messagesLoaded === false) return
    if (streaming && liveMessages && liveMessages.length > 0) {
      setReady(true)
      return
    }
    if ((!persistedSDKMessages || persistedSDKMessages.length === 0) && !streaming) {
      setSkipFadeIn(true)
      setReady(true)
      return
    }
    let cancelled = false
    requestAnimationFrame(() => {
      if (!cancelled) setReady(true)
    })
    return () => { cancelled = true }
  }, [streaming, liveMessages, persistedSDKMessages, messagesLoaded])

  const streamingContent = streamState?.content ?? ''
  const streamingModelId = streamState?.model || sessionModelId
  const retrying = streamState?.retrying
  const startedAt = streamState?.startedAt

  const { displayedContent: rawSmoothContent } = useSmoothStream({
    content: streamingContent,
    isStreaming: streaming,
  })

  const smoothContent = (streaming || streamingContent) ? rawSmoothContent : ''
  const smoothContentBlocks = React.useMemo(() => {
    if (!smoothContent) return []
    return parseThinkTagsFromText(smoothContent)
  }, [smoothContent])
  const hasSmoothTextContent = smoothContentBlocks.some((block) => block.type === 'text')

  // 合并持久化 + 实时 SDKMessage
  const allSDKMessages = React.useMemo(() => {
    const persisted = persistedSDKMessages ?? []
    const live = liveMessages ?? []
    const stampStableKey = (message: SDKMessage): SDKMessage => {
      const key = getSDKMessageStableKey(message)
      ;(message as Record<string, unknown>)._promaStableKey = key
      return message
    }
    const keyOf = (message: SDKMessage): string => (message as Record<string, unknown>)._promaStableKey as string

    const persistedWithKeys = persisted.map(stampStableKey)
    const liveWithKeys = live.map(stampStableKey)
    if (streaming || liveWithKeys.length === 0 || persistedWithKeys.length === 0) {
      return [...persistedWithKeys, ...liveWithKeys]
    }

    let overlap = Math.min(persistedWithKeys.length, liveWithKeys.length)
    for (; overlap > 0; overlap--) {
      const persistedStart = persistedWithKeys.length - overlap
      const liveStart = liveWithKeys.length - overlap
      let matches = true
      for (let i = 0; i < overlap; i++) {
        if (keyOf(persistedWithKeys[persistedStart + i]!) !== keyOf(liveWithKeys[liveStart + i]!)) {
          matches = false
          break
        }
      }
      if (matches) break
    }

    if (overlap === 0) return [...persistedWithKeys, ...liveWithKeys]
    return [...persistedWithKeys.slice(0, persistedWithKeys.length - overlap), ...liveWithKeys]
  }, [persistedSDKMessages, liveMessages, streaming])

  const hasContent = allSDKMessages.length > 0
  const suppressAgentRunning = streamState?.isCompacting || streamState?.compactInFlight

  const allGroups = React.useMemo(() => {
    return groupIntoTurns(allSDKMessages, sessionModelId)
  }, [allSDKMessages, sessionModelId])

  const historicalTaskSubjects = React.useMemo(() => {
    return buildHistoricalTaskSubjects(allSDKMessages)
  }, [allSDKMessages])

  const liveGroupSet = React.useMemo(() => {
    return buildLiveGroupSet({ allGroups, liveMessages, streaming })
  }, [allGroups, liveMessages, streaming])

  const hasLiveAssistantContent = streaming
    ? allGroups.some((g) => g.type === 'assistant-turn' && liveGroupSet.has(g))
    : (liveMessages != null && liveMessages.some((m) => (m as { type: string }).type === 'assistant'))

  return (
    <TabletModeContext.Provider value={true}>
      <BasePathsProvider basePaths={attachedDirs}>
        <div className="relative flex min-h-0 flex-1 flex-col">
          <Conversation
            className={ready ? (skipFadeIn ? 'opacity-100' : 'opacity-100 transition-opacity duration-200') : 'opacity-0'}
          >
            <TopHistoryLoader
              onLoadEarlierHistory={onLoadEarlierHistory}
              historyMoreAvailable={historyMoreAvailable}
              historyLoadingEarlier={historyLoadingEarlier}
            />
            <StreamScrollFollow streaming={streaming} />
            <ConversationContent>
              {hasContent && (historyLoadingEarlier || historyMoreAvailable === false) && (
                <div className="flex items-center justify-center py-2 text-xs text-muted-foreground/50">
                  {historyLoadingEarlier ? (
                    <span className="inline-flex items-center gap-1.5">
                      <Spinner size="sm" className="text-muted-foreground/40" />
                      正在加载更早消息…
                    </span>
                  ) : (
                    <span>— 已到最早消息 —</span>
                  )}
                </div>
              )}

              {!hasContent && !streaming ? (
                <EmptyState />
              ) : (
                <>
                  {allGroups.map((group) => {
                    const isLive = liveGroupSet.has(group)
                    const isErrorGroup = group.type === 'assistant-turn'
                      && group.assistantMessages.some((m) => !!m.error)
                    const shouldDisableActions = isLive && !isErrorGroup
                    const isLastAssistantTurn = !streaming && stoppedByUser
                      && group.type === 'assistant-turn'
                      && getGroupId(group) === getGroupId(allGroups.findLast((g) => g.type === 'assistant-turn') ?? group)
                    return (
                      <MessageGroupRenderer
                        key={getGroupId(group)}
                        group={group}
                        allMessages={allSDKMessages}
                        historicalTaskSubjects={historicalTaskSubjects}
                        basePath={sessionPath || undefined}
                        isStreaming={isLive || undefined}
                        stoppedByUser={isLastAssistantTurn || undefined}
                        sessionModelId={sessionModelId}
                      />
                    )
                  })}

                  {/* 有实时助手内容时：运行指示器 */}
                  {hasLiveAssistantContent && !suppressAgentRunning && (
                    <div className="min-h-[28px] pl-[56px]">
                      {retrying && <RetryingNotice retrying={retrying} />}
                      {streaming && <AgentRunningIndicator startedAt={startedAt} />}
                    </div>
                  )}

                  {/* 无实时助手内容时：完整流式气泡（fallback） */}
                  {!hasLiveAssistantContent && !suppressAgentRunning && (streaming || smoothContent || retrying) && (
                    <Message from="assistant">
                      <MessageHeader
                        model={streamingModelId}
                        time={formatMessageTime(Date.now())}
                        logo={<AssistantLogo model={streamingModelId} />}
                      />
                      <MessageContent>
                        {retrying && <RetryingNotice retrying={retrying} />}
                        {smoothContent ? (
                          <>
                            <div className={cn('space-y-2')}>
                              {smoothContentBlocks.map((block, index) => (
                                <ContentBlock
                                  key={index}
                                  block={block}
                                  allMessages={allSDKMessages}
                                  basePath={sessionPath || undefined}
                                  basePaths={attachedDirs}
                                  index={index}
                                  dimmed={hasSmoothTextContent && block.type !== 'text'}
                                  isStreaming={streaming}
                                />
                              ))}
                            </div>
                            {streaming && <AgentRunningIndicator startedAt={startedAt} />}
                          </>
                        ) : (
                          streaming && <AgentRunningIndicator startedAt={startedAt} />
                        )}
                      </MessageContent>
                    </Message>
                  )}

                  {streamState?.isCompacting && <CompactingIndicator />}

                  {streamState?.backgroundWaiting && !suppressAgentRunning && !streamState?.isCompacting && (
                    <div className="min-h-[28px] pl-[56px]">
                      <div className="flex items-center gap-2">
                        <Spinner size="sm" className="text-muted-foreground/40" />
                        <span className="text-[13px] font-light tabular-nums text-muted-foreground/45">
                          后台任务执行中
                        </span>
                      </div>
                    </div>
                  )}
                </>
              )}
            </ConversationContent>
            <ConversationScrollButton />
          </Conversation>
        </div>
      </BasePathsProvider>
    </TabletModeContext.Provider>
  )
}
