/**
 * SDKMessageRenderer — 渲染 SDKMessage 对象（移动端瘦客户端精简版）
 *
 * 从桌面 apps/electron/src/renderer/components/agent/SDKMessageRenderer.tsx 精简而来。
 *
 * 保留对话渲染内核：
 *  - Turn 分组（groupIntoTurns / MessageGroup / AssistantTurn）→ 直接复用 @profer/session-core（唯一真源）
 *  - MessageGroupRenderer（user 右对齐品牌色气泡 / assistant-turn 左对齐+头像 / system 分隔）
 *  - SDKMessageRenderer（单条消息渲染，流式用）
 *  - assistant turn 的用法/耗时提取、附件解析、TaskCreate 历史 subject 映射等纯函数
 *
 * 移除（移动端无对应能力）：
 *  - model-logo 模型图标（Electron 资产 + channels 配置）→ 用 Bot 图标 + 原样 model 名
 *  - TaskProgressCard / ProcessBlockGroup（桌面进程组卡片）→ 直接渲染 ContentBlock
 *  - TurnFileChangesSummary（文件改动汇总，依赖 diff 渲染）
 *  - fork/rewind/retry/compact 操作栏（桌面专属交互）
 *  - CopyButton / UserAvatar / formatMessageTime（桌面 chat 组件）→ 内联简化
 *  - knowledge-read-indicator（知识库读取指示）
 *  - @pierre/diffs 等工具结果 diff 渲染
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import { Bot, AlertTriangle, Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { ContentBlock } from './ContentBlock'
import { Badge } from '@/components/ui/badge'
import {
  Message,
  MessageHeader,
  MessageContent,
  UserMessageContent,
} from '@/components/ai-elements/message'
import { UserAvatar } from '@/components/chat/UserAvatar'
import { userProfileAtom, DEFAULT_USER_AVATAR, DEFAULT_USER_NAME } from '@/atoms/ui-atoms'
import {
  groupIntoTurns,
  getGroupPreview,
  extractUserText,
  isUserInputMessage,
  normalizeThinkTagsInContentBlocks,
  stripScheduledRunMarker,
  type MessageGroup,
  type AssistantTurn,
} from '@profer/session-core'
import { resolveContextWindowFromModelUsage } from '@profer/shared'
import type {
  SDKMessage,
  SDKAssistantMessage,
  SDKUserMessage,
  SDKSystemMessage,
  SDKContentBlock,
  SDKResultMessage,
  SDKToolUseBlock,
  SDKToolResultBlock,
  AgentEventUsage,
} from '@profer/shared'

// re-export 供 AgentMessages / 其他模块使用（保持与桌面同名导出）
export { groupIntoTurns, getGroupPreview }
export type { MessageGroup, AssistantTurn }

// ===== 纯函数辅助 =====

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

interface MessageMeta {
  createdAt?: number
}

function extractMeta(message: SDKMessage): MessageMeta {
  const msg = message as Record<string, unknown>
  return {
    createdAt: typeof msg._createdAt === 'number' ? msg._createdAt : undefined,
  }
}

/** 内部判断 tool_result 内容是否为结构化对象（MCP 信封），提取纯文本 */
function extractToolResultText(content: unknown): string | undefined {
  if (typeof content === 'string') return content
  if (isRecord(content) && Array.isArray(content.content)) {
    return extractToolResultText(content.content)
  }
  if (!Array.isArray(content)) return undefined

  const text = content
    .map((block) => {
      if (!isRecord(block)) return ''
      return typeof block.text === 'string' ? block.text : ''
    })
    .join('')

  return text || undefined
}

function extractStructuredToolResultText(message: SDKUserMessage): string | undefined {
  const raw = message as unknown as Record<string, unknown>
  const result = raw.toolUseResult ?? raw.tool_use_result
  if (!isRecord(result)) return undefined
  try {
    return JSON.stringify(result)
  } catch {
    return undefined
  }
}

function extractToolResultForTask(message: SDKUserMessage, resultBlock: SDKToolResultBlock): string | undefined {
  return extractStructuredToolResultText(message) ?? extractToolResultText(resultBlock.content)
}

/** 从 turn 消息列表中提取 result 消息的耗时和用量数据 */
export function extractTurnUsage(
  turnMessages: SDKMessage[],
  sessionModelId?: string,
): { durationMs?: number; usage?: AgentEventUsage } {
  for (const msg of turnMessages) {
    if (msg.type !== 'result') continue
    const resultMsg = msg as SDKResultMessage
    const raw = msg as Record<string, unknown>
    const durationMs = typeof raw._durationMs === 'number' ? raw._durationMs : undefined
    const u = resultMsg.usage
    if (!u) return { durationMs }
    const contextWindow = resolveContextWindowFromModelUsage(
      resultMsg.modelUsage,
      resultMsg._channelModelId ?? sessionModelId,
    )
    return {
      durationMs,
      usage: {
        inputTokens: u.input_tokens + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0),
        outputTokens: u.output_tokens,
        cacheReadTokens: u.cache_read_input_tokens,
        cacheCreationTokens: u.cache_creation_input_tokens,
        costUsd: resultMsg.total_cost_usd,
        contextWindow,
      },
    }
  }
  return {}
}

/**
 * 扫描全部消息，构建跨 turn 的「历史 TaskCreate id → subject」映射。
 * 移动端保留此函数（供折叠摘要 chip 使用）。
 */
export function buildHistoricalTaskSubjects(allMessages: SDKMessage[]): Map<string, string> {
  const historicalTaskSubjects = new Map<string, string>()
  const globalResultMap = new Map<string, string>()
  const pendingTaskCreates: SDKToolUseBlock[] = []

  for (const msg of allMessages) {
    if (msg.type === 'user') {
      const userMsg = msg as SDKUserMessage
      const blocks = userMsg.message?.content
      if (!Array.isArray(blocks)) continue
      for (const b of blocks) {
        if (b.type === 'tool_result') {
          const rb = b as SDKToolResultBlock
          const text = extractToolResultForTask(userMsg, rb)
          if (text) globalResultMap.set(rb.tool_use_id, text)
        }
      }
    } else if (msg.type === 'assistant') {
      const aMsg = msg as SDKAssistantMessage
      const blocks = aMsg.message?.content
      if (!Array.isArray(blocks)) continue
      for (const b of blocks) {
        if (b.type === 'tool_use' && (b as SDKToolUseBlock).name === 'TaskCreate') {
          pendingTaskCreates.push(b as SDKToolUseBlock)
        }
      }
    }
  }

  for (const tb of pendingTaskCreates) {
    const resultText = globalResultMap.get(tb.id)
    if (!resultText) continue
    try {
      const parsed = JSON.parse(resultText) as { task?: { id?: string; subject?: string } }
      const task = parsed?.task
      if (task && (typeof task.id === 'string' || typeof task.id === 'number')) {
        const subject = typeof task.subject === 'string' ? task.subject : undefined
        if (subject) historicalTaskSubjects.set(String(task.id), subject)
      }
    } catch {
      // 忽略解析失败
    }
  }

  return historicalTaskSubjects
}

// ===== 附件解析 =====

export interface AttachedFileRef {
  filename: string
  path: string
}

export interface QuotedFileRef {
  path: string
  filename: string
}

/** 解析消息中的 <attached_files> 块和 <quoted_file> 块 */
export function parseAttachedFiles(content: string): { files: AttachedFileRef[]; quotes: QuotedFileRef[]; text: string } {
  const MAX_PARSE_LENGTH = 100_000
  const safeContent = content.length > MAX_PARSE_LENGTH ? content.slice(0, MAX_PARSE_LENGTH) : content

  const quoteRegex = /<quoted_file[^>]*>[\s\S]*?<\/quoted_file>\n*/g
  const quotes: QuotedFileRef[] = []
  let quoteMatch: RegExpExecArray | null
  while ((quoteMatch = quoteRegex.exec(safeContent)) !== null) {
    const pathMatch = quoteMatch[0].match(/path="([^"]*)"/)
    if (pathMatch) {
      const filePath = pathMatch[1]!
        .replace(/&quot;/g, '"')
        .replace(/&gt;/g, '>')
        .replace(/&lt;/g, '<')
        .replace(/&amp;/g, '&')
      quotes.push({ path: filePath, filename: filePath.split('/').pop() ?? filePath })
    }
  }

  const regex = /<attached_files>\n?([\s\S]*?)\n?<\/attached_files>\n*/
  const match = safeContent.match(regex)
  if (!match) {
    const cleanText = safeContent.replace(/<quoted_file[^>]*>[\s\S]*?<\/quoted_file>\n*/g, '').trim()
    return { files: [], quotes, text: cleanText }
  }

  const files: AttachedFileRef[] = []
  const lines = match[1]!.split('\n')
  for (const line of lines) {
    const lineMatch = line.match(/^-\s+(.+?):\s+(.+)$/)
    if (lineMatch) {
      files.push({ filename: lineMatch[1]!.trim(), path: lineMatch[2]!.trim() })
    }
  }

  let text = safeContent.replace(regex, '')
  text = text.replace(/<quoted_file[^>]*>[\s\S]*?<\/quoted_file>\n*/g, '')
  text = text.trim()
  return { files, quotes, text }
}

/** 判断文件是否为图片类型 */
export function isImageFile(filename: string): boolean {
  return /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i.test(filename)
}

// ===== 稳定 ID =====

const messageIdCache = new WeakMap<object, string>()
let fallbackIdCounter = 0

/** 从 MessageGroup 中提取稳定的 ID */
export function getGroupId(group: MessageGroup): string {
  if (group.type === 'user') {
    if (group.message.uuid) return group.message.uuid
    const stableKey = (group.message as unknown as Record<string, unknown>)._promaStableKey
    if (typeof stableKey === 'string') return stableKey
    if (!messageIdCache.has(group.message)) {
      messageIdCache.set(group.message, `user-${++fallbackIdCounter}`)
    }
    return messageIdCache.get(group.message)!
  }
  if (group.type === 'system') {
    if (!messageIdCache.has(group.message)) {
      messageIdCache.set(group.message, `system-${group.message.subtype ?? 'unknown'}-${++fallbackIdCounter}`)
    }
    return messageIdCache.get(group.message)!
  }
  const first = group.assistantMessages[0]
  if (first?.uuid) return first.uuid
  const stableKey = first ? (first as unknown as Record<string, unknown>)._promaStableKey : undefined
  if (typeof stableKey === 'string') return stableKey
  if (first) {
    if (!messageIdCache.has(first)) {
      messageIdCache.set(first, `turn-${++fallbackIdCounter}`)
    }
    return messageIdCache.get(first)!
  }
  return `turn-empty-${++fallbackIdCounter}`
}

// ===== 格式化耗时 =====

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}m ${s.toFixed(0)}s`
}

// ===== system 消息分隔 =====

function CompactBoundaryDivider(): React.ReactElement {
  return (
    <div className="my-4 flex items-center gap-3 px-1">
      <div className="h-px flex-1 bg-border/40" />
      <span className="shrink-0 rounded-full border border-border/30 bg-muted/20 px-2 py-0.5 text-[11px] text-muted-foreground/60">
        上下文已压缩
      </span>
      <div className="h-px flex-1 bg-border/40" />
    </div>
  )
}

export function CompactingIndicator(): React.ReactElement {
  return (
    <div className="my-4 flex items-center gap-3 px-1">
      <div className="h-px flex-1 bg-border/40" />
      <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full border border-border/30 bg-muted/20 px-2 py-0.5 text-[11px] text-muted-foreground/70">
        <Loader2 className="size-3 animate-spin" />
        正在压缩...
      </span>
      <div className="h-px flex-1 bg-border/40" />
    </div>
  )
}

function formatSystemToolName(toolName: string): string {
  const parts = toolName.split('__')
  if (parts[0] === 'mcp' && parts.length >= 3) {
    return `${parts[1]} / ${parts.slice(2).join('__')}`
  }
  return toolName
}

function PermissionDeniedNotice({ message }: { message: SDKSystemMessage }): React.ReactElement {
  const toolName = typeof message.tool_name === 'string' ? formatSystemToolName(message.tool_name) : undefined
  const denialMessage = typeof message.message === 'string' ? message.message : undefined

  return (
    <div className="my-3 pl-[46px] pr-1">
      <div className="flex items-start gap-2.5 rounded-md border border-amber-500/20 bg-amber-500/5 px-3 py-2.5 text-xs text-foreground/80">
        <AlertTriangle className="mt-0.5 size-3.5 shrink-0 text-amber-500" />
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium text-foreground">自动审批已拒绝操作</span>
            {toolName && (
              <span className="rounded bg-background/60 px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground">
                {toolName}
              </span>
            )}
          </div>
          {denialMessage && <p className="break-words text-muted-foreground">{denialMessage}</p>}
        </div>
      </div>
    </div>
  )
}

// ===== 助手头像 =====

function AssistantLogo({ model }: { model?: string }): React.ReactElement {
  return (
    <div className="flex size-[35px] items-center justify-center rounded-[25%] bg-primary/10">
      <Bot size={18} className="text-primary" />
    </div>
  )
}

// ===== 用户消息（对齐桌面：emoji 头像 + 用户名 + 时间 + 浅色气泡） =====

function UserInputMessage({ message }: { message: SDKUserMessage }): React.ReactElement {
  const userProfile = useAtomValue(userProfileAtom)
  const rawText = extractUserText(message) ?? ''
  const { text } = parseAttachedFiles(stripScheduledRunMarker(rawText))
  const meta = extractMeta(message as unknown as SDKMessage)

  if (!text) return <React.Fragment />

  return (
    <Message from="user">
      <div className="flex items-start gap-2.5 mb-2.5">
        <UserAvatar avatar={userProfile.avatar ?? DEFAULT_USER_AVATAR} size={35} />
        <div className="flex flex-col justify-between h-[35px]">
          <span className="text-sm font-semibold text-foreground/60 leading-none">{userProfile.userName || DEFAULT_USER_NAME}</span>
          {meta.createdAt && (
            <span className="message-time text-[10px] text-foreground/[0.38] leading-none">{formatMessageTime(meta.createdAt)}</span>
          )}
        </div>
      </div>
      <MessageContent>
        <UserMessageContent>{text}</UserMessageContent>
      </MessageContent>
    </Message>
  )
}

// ===== 错误消息渲染 =====

function ErrorMessage({ message }: { message: SDKAssistantMessage }): React.ReactElement {
  const errorText = message.error?.message ?? '未知错误'
  return (
    <Message from="assistant">
      <MessageHeader logo={<AssistantLogo />} />
      <MessageContent>
        <div className="flex items-start gap-2 rounded-md border border-destructive/20 bg-destructive/5 px-3 py-2.5 text-sm text-destructive">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <span className="break-words">{errorText}</span>
        </div>
      </MessageContent>
    </Message>
  )
}

// ===== SDKMessageRendererProps =====

export interface SDKMessageRendererProps {
  message: SDKMessage
  allMessages: SDKMessage[]
  basePath?: string
  showHeader?: boolean
  sessionModelId?: string
}

// ===== SDKMessageRenderer 主组件（单条消息渲染） =====

export function SDKMessageRenderer({
  message,
  allMessages,
  basePath,
  showHeader = true,
  sessionModelId,
}: SDKMessageRendererProps): React.ReactElement | null {
  const msgType = message.type

  if (msgType === 'assistant') {
    const aMsg = message as SDKAssistantMessage
    if (aMsg.isReplay) return null
    if (aMsg.error) return <ErrorMessage message={aMsg} />

    const rawBlocks = aMsg.message?.content
    if (!Array.isArray(rawBlocks) || rawBlocks.length === 0) return null
    const blocks = normalizeThinkTagsInContentBlocks(rawBlocks)
    if (blocks.length === 0) return null

    const model = aMsg._channelModelId || aMsg.message?.model || sessionModelId
    const meta = extractMeta(message)

    const hasTextContent = blocks.some(
      (b) => b.type === 'text' && 'text' in b && !!(b as { text: string }).text,
    )

    return (
      <Message from="assistant">
        {showHeader && (
          <MessageHeader
            model={model}
            time={meta.createdAt ? formatMessageTime(meta.createdAt) : undefined}
            logo={<AssistantLogo model={model} />}
          />
        )}
        <MessageContent>
          <div className={cn('space-y-2')}>
            {blocks.map((block, i) => (
              <ContentBlock
                key={i}
                block={block}
                allMessages={allMessages}
                basePath={basePath}
                index={i}
                dimmed={hasTextContent && block.type !== 'text'}
              />
            ))}
          </div>
        </MessageContent>
      </Message>
    )
  }

  if (msgType === 'user') {
    const uMsg = message as SDKUserMessage
    if (isUserInputMessage(uMsg)) {
      return <UserInputMessage message={uMsg} />
    }
    return null
  }

  if (msgType === 'system') {
    const sysMsg = message as SDKSystemMessage
    const subtype = sysMsg.subtype
    if (subtype === 'compact_boundary') return <CompactBoundaryDivider />
    if (subtype === 'permission_denied') return <PermissionDeniedNotice message={sysMsg} />
    return null
  }

  return null
}

// ===== MessageGroup 渲染 =====

export interface MessageGroupRendererProps {
  group: MessageGroup
  allMessages: SDKMessage[]
  historicalTaskSubjects: Map<string, string>
  basePath?: string
  isStreaming?: boolean
  stoppedByUser?: boolean
  sessionModelId?: string
}

function formatMessageTime(ts: number): string {
  const d = new Date(ts)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

/** 渲染 assistant-turn（左对齐 + 头像 + ContentBlock 列表） */
function AssistantTurnRenderer({
  turn,
  allMessages,
  basePath,
  isStreaming,
  stoppedByUser,
}: {
  turn: AssistantTurn
  allMessages: SDKMessage[]
  basePath?: string
  isStreaming?: boolean
  stoppedByUser?: boolean
}): React.ReactElement | null {
  interface EnrichedBlock {
    block: SDKContentBlock
    parentToolUseId?: string | null
  }

  const enrichedBlocks: EnrichedBlock[] = []
  let hasError = false
  let errorContent: SDKAssistantMessage | null = null

  for (const aMsg of turn.assistantMessages) {
    if (aMsg.error) {
      hasError = true
      errorContent = aMsg
      continue
    }
    const blocks = aMsg.message?.content
    if (Array.isArray(blocks)) {
      for (const block of blocks) {
        for (const normalizedBlock of normalizeThinkTagsInContentBlocks([block])) {
          enrichedBlocks.push({ block: normalizedBlock, parentToolUseId: aMsg.parent_tool_use_id })
        }
      }
    }
  }

  // 构建 Agent/Task tool_use → 子代理内容块映射
  const agentToolIds = new Set<string>()
  for (const eb of enrichedBlocks) {
    if (eb.block.type === 'tool_use') {
      const tu = eb.block as { name: string; id: string }
      if (tu.name === 'Agent' || tu.name === 'Task') {
        agentToolIds.add(tu.id)
      }
    }
  }

  const childBlocksMap = new Map<string, SDKContentBlock[]>()
  const topLevelBlocks: SDKContentBlock[] = []
  for (const eb of enrichedBlocks) {
    if (eb.parentToolUseId && agentToolIds.has(eb.parentToolUseId)) {
      const children = childBlocksMap.get(eb.parentToolUseId) ?? []
      children.push(eb.block)
      childBlocksMap.set(eb.parentToolUseId, children)
    } else {
      topLevelBlocks.push(eb.block)
    }
  }

  const hasTextContent = topLevelBlocks.some(
    (b) => b.type === 'text' && 'text' in b && !!(b as { text: string }).text,
  )

  if (enrichedBlocks.length === 0 && hasError && errorContent) {
    return <ErrorMessage message={errorContent} />
  }
  if (enrichedBlocks.length === 0 && !hasError) return null

  const { durationMs, usage } = extractTurnUsage(turn.turnMessages)
  const showStoppedBadge = !!stoppedByUser

  return (
    <Message from="assistant">
      <MessageHeader
        model={turn.model}
        time={turn.createdAt ? formatMessageTime(turn.createdAt) : undefined}
        logo={<AssistantLogo model={turn.model} />}
      />
      <MessageContent>
        <div className={cn('space-y-2')}>
          {topLevelBlocks.map((block, i) => {
            const isAgentTool = block.type === 'tool_use'
              && ((block as { name: string }).name === 'Agent' || (block as { name: string }).name === 'Task')
            const childBlocks = isAgentTool
              ? childBlocksMap.get((block as { id: string }).id)
              : undefined
            return (
              <ContentBlock
                key={i}
                block={block}
                allMessages={allMessages}
                basePath={basePath}
                animate={!!isStreaming}
                index={i}
                dimmed={hasTextContent && block.type !== 'text'}
                childBlocks={childBlocks}
                isStreaming={isStreaming}
              />
            )
          })}
        </div>
        {hasError && errorContent && topLevelBlocks.length > 0 && (
          <div className="mt-3 text-sm text-destructive">
            {errorContent.error?.message ?? '未知错误'}
          </div>
        )}
      </MessageContent>
      {/* 底部：耗时 + 中断徽章（移动端去掉 fork/rewind/retry/compact 操作栏） */}
      {!isStreaming && (durationMs != null || showStoppedBadge) && (
        <div className={cn('flex min-h-[28px] items-center gap-2 pl-[46px] pt-0.5 text-muted-foreground')}>
          {durationMs != null && (
            <span className="text-[13px] tabular-nums font-light">{formatDuration(durationMs)}</span>
          )}
          {showStoppedBadge && (
            <Badge variant="outline" className="shrink-0 text-xs text-muted-foreground/70">
              已被用户中断
            </Badge>
          )}
        </div>
      )}
    </Message>
  )
}

export function MessageGroupRenderer({
  group,
  allMessages,
  historicalTaskSubjects,
  basePath,
  isStreaming,
  stoppedByUser,
  sessionModelId,
}: MessageGroupRendererProps): React.ReactElement | null {
  const groupId = getGroupId(group)

  if (group.type === 'user') {
    return (
      <div data-message-id={groupId} data-message-role="user">
        <UserInputMessage message={group.message} />
      </div>
    )
  }

  if (group.type === 'system') {
    const subtype = group.message.subtype
    if (subtype === 'compact_boundary') return <div data-message-id={groupId}><CompactBoundaryDivider /></div>
    if (subtype === 'compacting') return null
    if (subtype === 'permission_denied') return <div data-message-id={groupId}><PermissionDeniedNotice message={group.message} /></div>
    return null
  }

  return (
    <div data-message-id={groupId} data-message-role="assistant">
      <AssistantTurnRenderer
        turn={group}
        allMessages={allMessages}
        basePath={basePath}
        isStreaming={isStreaming}
        stoppedByUser={stoppedByUser}
      />
    </div>
  )
}
