/**
 * 平板端 electronAPI 桥
 *
 * 分层职责：
 * 1. 事件桥：onAgentStreamEvent 等 IPC 事件注册器 → WS agent_event 分发。
 *    桌面渲染层（useGlobalAgentListeners / AgentView / LeftSidebar 依赖树）只感知
 *    electronAPI 形状；平板通过 emitPocketAgentStreamEvent() 把 WS 事件喂回注册器，
 *    使桌面组件的事件处理逻辑 100% 复用（与桌面 IPC AgentStreamEvent 同形状）。
 * 2. 命令映射：AgentView / LeftSidebar 用到的 IPC 命令 → WS 远程命令。
 * 3. 降级层：平板无意义的桌面能力（本地文件对话框 / 知识库 / 进程面板 / 分叉回退）→
 *    安全空实现或明确报错，避免复用组件崩溃或出现“可点但无效果”的伪按钮。
 */

import type { AgentStreamEvent, AgentStreamCompletePayload, StreamChunkEvent, StreamReasoningEvent, StreamCompleteEvent, StreamErrorEvent, StreamToolActivityEvent, GenerateTitleInput, CreateExplorationSessionInput } from '@profer/shared'
import { CHAT_IPC_CHANNELS, BUILTIN_DEFAULT_ID, BUILTIN_DEFAULT_PROMPT } from '@profer/shared'
import { debugLog } from '@/lib/debug-hud'
import { getFileBaseName } from '@/lib/file-utils'
import { resolvePocketInteractionVerdict, type InteractionVerdictQuery } from './pending-interaction-guard'

interface HeatmapDailyEntry {
  date: string
  tokens: number
}

function isHeatmapDailyEntry(value: unknown): value is HeatmapDailyEntry {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.date === 'string'
    && record.date.length > 0
    && typeof record.tokens === 'number'
    && Number.isFinite(record.tokens)
    && record.tokens >= 0
}

interface HeatmapRemoteClient {
  getWorkspaceHeatmapDaily(workspaceId: string): Promise<unknown>
}

interface AgentSessionMetaRecord extends Record<string, unknown> {
  id: string
  title: string
  createdAt: number
  updatedAt: number
}

interface AgentSessionMetaRemoteClient {
  listSessions(): Promise<unknown>
}

function isAgentSessionMetaRecord(value: unknown): value is AgentSessionMetaRecord {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.id === 'string'
    && typeof record.title === 'string'
    && typeof record.createdAt === 'number'
    && typeof record.updatedAt === 'number'
}

/**
 * WS 命令结果与 session_updated 事件统一以远端持久化元数据为准。
 * 兼容旧端仅返回 sessionId/title 的命令结果，但不再用本机时钟拼装影子对象。
 */
export async function resolveAuthoritativeAgentSession(
  client: AgentSessionMetaRemoteClient,
  sessionId: string,
  commandResult?: unknown,
): Promise<AgentSessionMetaRecord> {
  const resultRecord = commandResult && typeof commandResult === 'object'
    ? commandResult as Record<string, unknown>
    : undefined
  const directCandidate = resultRecord?.session ?? commandResult
  if (isAgentSessionMetaRecord(directCandidate)) return directCandidate

  const sessions = await client.listSessions()
  if (Array.isArray(sessions)) {
    const persisted = sessions.find((session) => (
      isAgentSessionMetaRecord(session) && session.id === sessionId
    ))
    if (persisted && isAgentSessionMetaRecord(persisted)) return persisted
  }

  throw new Error(`远端未返回完整会话元数据: ${sessionId}`)
}

/**
 * 读取远程工作区热力图，并对不受信任的 WS 响应执行运行时形状校验。
 * 未注入 client 时返回空数组；client 已就绪后的错误必须继续向调用方 reject。
 */
export async function requestWorkspaceHeatmapDaily(
  client: HeatmapRemoteClient | null,
  workspaceId: string,
): Promise<HeatmapDailyEntry[]> {
  if (!client) return []

  const data = await client.getWorkspaceHeatmapDaily(workspaceId)
  if (!Array.isArray(data)) {
    throw new Error('工作区热力图响应必须是数组')
  }

  if (!data.every(isHeatmapDailyEntry)) {
    throw new Error('工作区热力图响应包含非法条目')
  }

  return data
}

/** WsClient 满足的最小远程命令面（与 ws-client.ts 方法一一对应） */
interface PocketRemoteClient extends HeatmapRemoteClient {
  listSessions(): Promise<unknown>
  listWorkspaces(): Promise<unknown>
  getWorkspaceCapabilities(workspaceSlug: string): Promise<unknown>
  createWorkspace(name: string): Promise<unknown>
  deleteSession(sessionId: string): Promise<unknown>
  /** 分叉会话 */
  forkSession(payload: { sessionId: string; upToMessageUuid?: string }): Promise<unknown>
  /** 创建 Pi `/tree` 探索分支（WS 命令 create_exploration_session） */
  createExplorationSession(payload: CreateExplorationSessionInput): Promise<unknown>
  /** 快照回退 */
  rewindSession(payload: { sessionId: string; assistantMessageUuid: string }): Promise<unknown>
  /** 置顶/取消置顶 */
  toggleSessionPin(sessionId: string): Promise<unknown>
  /** 归档/取消归档 */
  toggleSessionArchive(sessionId: string): Promise<unknown>
  /** 移动会话到项目 */
  moveSessionToWorkspace(payload: { sessionId: string; targetWorkspaceId: string }): Promise<unknown>
  /** 设置推理档位（null=恢复全局默认） */
  updateSessionThinkingLevel(sessionId: string, level: string | null): Promise<unknown>
  getUserProfile(): Promise<unknown>
  getPendingInteractions(sessionId?: string): Promise<unknown>
  /** 活跃 Agent 会话的运行时上下文窗口快照（对齐桌面 remote-service `get_agent_runtime_contexts`）。
   *  旧版桌面端不识别该命令时返回 ok:false，调用方静默降级。 */
  getAgentRuntimeContexts(sessionIds?: string[]): Promise<unknown>
  listChannels(): Promise<unknown>
  createSession(payload: { title?: string; channelId?: string; workspaceId?: string; modelId?: string; permissionMode?: 'auto' | 'plan' | 'bypassPermissions' }): Promise<unknown>
  migrateChatToAgent(conversationId: string, agentSessionId: string): Promise<unknown>
  ensureProjectDraftSession(payload: { workspaceId: string; channelId?: string; modelId?: string }): Promise<unknown>
  renameSession(sessionId: string, title: string): Promise<unknown>
  getSdkMessages(
    sessionId: string,
    opts?: { before?: number; targetMessages?: number },
  ): Promise<unknown>
  sendMessage(payload: { sessionId: string; userMessage: string; channelId: string; modelId?: string; workspaceId?: string; permissionMode?: 'auto' | 'plan' | 'bypassPermissions'; uuid?: string; startedAt?: number }): Promise<unknown>
  /** Pi 推理档位能力（服务端 resolvePiReasoningCapability） */
  getPiReasoningCapability(provider: string, modelId: string): Promise<unknown>
  /** 远程搜索会话可引用的工作区文件（roots 由服务端按会话授权推导） */
  searchWorkspaceFiles(sessionId: string, query: string, limit?: number): Promise<unknown>
  /** 向正在运行的 Agent 注入消息（对齐桌面 queueAgentMessage：interrupt 软打断 / uuid 幂等） */
  queueMessage(payload: {
    sessionId: string
    userMessage: string
    rawUserMessage?: string
    uuid?: string
    interrupt?: boolean
    mentionedSkills?: string[]
    mentionedMcpServers?: string[]
    mentionedSessionIds?: string[]
  }): Promise<unknown>
  updateSessionModel(sessionId: string, channelId: string, modelId?: string): Promise<unknown>
  updateSessionRuntime(sessionId: string, runtime: 'claude' | 'pi'): Promise<unknown>
  updatePermissionMode(sessionId: string, mode: 'auto' | 'plan' | 'bypassPermissions'): Promise<unknown>
  stopAgent(sessionId: string): Promise<unknown>
  // ---- 交互式问答/审批响应（AskUserQuestion / 权限审批 / ExitPlanMode） ----
  respondPermission(requestId: string, behavior: 'allow' | 'deny', alwaysAllow?: boolean): Promise<unknown>
  respondAskUser(requestId: string, answers: Record<string, string>): Promise<unknown>
  respondExitPlanMode(requestId: string, action: 'approve_auto' | 'approve_edit' | 'deny' | 'feedback', feedback?: string): Promise<unknown>
  // ---- Chat（聊天工具）----
  listConversations(): Promise<unknown>
  createConversation(payload: { title?: string; modelId?: string; channelId?: string }): Promise<unknown>
  getConversationMessages(conversationId: string): Promise<unknown>
  getRecentMessages(conversationId: string, limit: number): Promise<unknown>
  updateConversationTitle(conversationId: string, title: string): Promise<unknown>
  updateConversationModel(conversationId: string, modelId?: string, channelId?: string): Promise<unknown>
  deleteConversation(conversationId: string): Promise<unknown>
  toggleConversationPin(conversationId: string): Promise<unknown>
  toggleConversationArchive(conversationId: string): Promise<unknown>
  searchChatMessages(query: string): Promise<unknown>
  searchAgentSessionMessages(query: string): Promise<unknown>
  chatSendMessage(payload: {
    conversationId: string
    userMessage: string
    channelId: string
    modelId?: string
    contextLength?: number
    contextDividers?: string[]
    attachments?: unknown[]
    knowledgeReferences?: unknown[]
    thinkingEnabled?: boolean
    systemMessage?: string
    enabledToolIds?: string[]
  }): Promise<unknown>
  chatStopGeneration(conversationId: string): Promise<unknown>
  chatDeleteMessage(conversationId: string, messageId: string): Promise<unknown>
  chatTruncateMessagesFrom(conversationId: string, messageId: string, preserveFirstMessageAttachments?: boolean): Promise<unknown>
  chatUpdateContextDividers(conversationId: string, dividers: string[]): Promise<unknown>
  chatGenerateTitle(input: GenerateTitleInput): Promise<unknown>
  chatSaveAttachment(input: { conversationId: string; filename: string; mediaType: string; data: string }): Promise<unknown>
  chatDeleteAttachment(localPath: string): Promise<unknown>
  chatReadAttachment(localPath: string): Promise<unknown>
  // ---- Agent 预设（预设为工作区级配置，数据与电脑端共享） ----
  listPresets(workspaceSlug?: string): Promise<unknown>
  getDefaultPreset(workspaceSlug?: string): Promise<unknown>
  setDefaultPreset(workspaceSlug: string, presetId: string): Promise<unknown>
  updateSessionPreset(sessionId: string, presetId: string): Promise<unknown>
  createPreset(workspaceSlug: string, input: Record<string, unknown>): Promise<unknown>
  copyPreset(workspaceSlug: string, fromId: string, name?: string): Promise<unknown>
  updatePreset(workspaceSlug: string, presetId: string, updates: Record<string, unknown>): Promise<unknown>
  deletePreset(workspaceSlug: string, presetId: string): Promise<unknown>
  // ---- 文件预览：经 WS 读取电脑端文件（Pocket 文件预览 MVP） ----
  resolveAndReadFile(filePath: string, access?: { sessionId?: string; candidateBasePaths?: string[] }): Promise<unknown>
  readFileAsDataUrl(filePath: string, access?: { sessionId?: string; candidateBasePaths?: string[] }): Promise<unknown>
}

let remoteClient: PocketRemoteClient | null = null

/** 在 WebSocket 建连后注入，使原生桌面组件沿用 electronAPI 形状调用远程服务。 */
export function setPocketRemoteClient(client: PocketRemoteClient | null): void {
  remoteClient = client
}

/**
 * 读取当前注入的远程客户端。
 *
 * pocket 侧的非 IPC 通道（如权威上下文窗口水合）需要直接发起只读 WS 命令；
 * 未连接/已解绑时为 null，调用方据此静默降级。
 */
export function getPocketRemoteClient(): PocketRemoteClient | null {
  return remoteClient
}

/**
 * 最后成功获取的渠道列表快照。
 *
 * listChannels 在 WS 未就绪 / 获取失败 / 桌面端返回空列表时返回快照而不是空数组：
 * ModelSelector 打开 Dialog 时会调用 listChannels 刷新并 setChannels 覆盖 channelsAtom，
 * 若此时拿到空数组，会把平板已加载的渠道清空 → AgentView 的 hasAvailableModel 变 false，
 * 输入框上方误报“暂无可用模型”黄字且无自动恢复机制，用户无法继续对话。
 * 保留最后一次成功的渠道，既避免误清空，也让用户仍能看到/选择桌面端已配置的模型。
 */
let lastChannelsSnapshot: unknown[] = []

// ===== 会话消息传输层懒加载分页状态 =====
//
// 移动端打开会话时不一次性拉全量，而是按完整 turn 惰性分页（见服务端
// paginateSDKMessages）。stub 为每个 sessionId 维护已累计的消息、起点游标与还有更早的标记；
// AgentView 每次 getAgentSessionSDKMessages(sessionId) 都拿到当前已累计的有序数组。

interface SdkMessagesPageState {
  /** 已累计的完整有序 SDKMessage（按 startIndex 顺序，从最早累积到当前尾部） */
  messages: unknown[]
  /** 已累计部分在服务端完整消息数组中的起点索引（补更早时作为 before 游标） */
  startIndex: number
  /** 更早是否还有消息 */
  hasMore: boolean
}

const sdkMessagesPageCache = new Map<string, SdkMessagesPageState>()

/** 缓存会话数上限：触顶加载/多会话切换会在 stub 侧堆积整份消息数组，无界会随会话数无限增长。
 *  超出上限时淘汰最久未写入的会话（Map 迭代序即插入序）。 */
const SDK_MESSAGES_CACHE_MAX_SESSIONS = 20

/** 写入并执行 LRU 淘汰：delete+set 把该 key 移到末尾（视为最近使用），超出上限删头部最久未用。 */
function setCachedPage(sessionId: string, state: SdkMessagesPageState): void {
  sdkMessagesPageCache.delete(sessionId)
  sdkMessagesPageCache.set(sessionId, state)
  while (sdkMessagesPageCache.size > SDK_MESSAGES_CACHE_MAX_SESSIONS) {
    const oldest = sdkMessagesPageCache.keys().next().value
    if (oldest === undefined) break
    sdkMessagesPageCache.delete(oldest)
  }
}

/** 返回当前会话已累计的消息数组（无则返回空数组，由调用方触发迁移）。 */
function getCachedSdkMessages(sessionId: string): unknown[] {
  return sdkMessagesPageCache.get(sessionId)?.messages ?? []
}

/**
 * R10（强制刷新）：丢弃某个会话的传输层分页窗口。
 *
 * 分页缓存保存的是「已累计」消息数组 + 起点游标，是**增量**语义：若它曾因事件丢失/
 * 早期截断而带缺口，后面的 `paginateFirst` 刷新只会用去重键做前缀合并，缺口永远补不回来
 * （用户看到的就是「桌面已显示、移动端输出缩在执行过程里」）。强制刷新必须先丢缓存，
 * 再走无参全量拉取（`getAgentSessionSDKMessages(sessionId)` 会重建为 startIndex=0/hasMore=false）。
 *
 * 注意：只动传输层缓存，渲染层 atom（agentSDKMessagesCacheAtom）保持不动——全量拉取
 * 失败时界面继续用旧数据展示，不会白屏。
 */
export function invalidatePocketSdkMessagesPageCache(sessionId?: string): void {
  if (typeof sessionId === 'string' && sessionId.length > 0) {
    sdkMessagesPageCache.delete(sessionId)
    return
  }
  sdkMessagesPageCache.clear()
}

/**
 * PB-5（G1-d）：把刚发出、尚未被服务端分页确认的用户消息补进传输层分页缓存。
 *
 * 背景：渲染层（AgentView.pendingOptimisticMessagesRef）有自己的乐观副本，而 stub 侧
 * `sdkMessagesPageCache` 只会在 `getAgentSessionSDKMessages` 时写入——两者是双真源。
 * 若发送后立即下拉刷新/切会话回读，而服务端分页窗口尚未包含该条时，消息列表会短暂缺这一条。
 *
 * 边界：① 仅在会话已有分页缓存时追加（不无中生有创建只有 1 条消息的缓存页）；
 * ② 用 `sdkMessageKey` 去重——服务端持久化后的消息 uuid 与乐观副本一致，不会重复。
 */
function appendOptimisticMessageToPageCache(sessionId: string, message: unknown): void {
  const prev = sdkMessagesPageCache.get(sessionId)
  if (!prev) return
  const key = sdkMessageKey(message)
  if (prev.messages.some((m) => sdkMessageKey(m) === key)) return
  setCachedPage(sessionId, { ...prev, messages: [...prev.messages, message] })
}

// ===== 分页合并辅助 =====

/** 生成单条 SDKMessage 的去重键。优先 uuid；无 uuid 时回退到 type + 内容哈希。
 *  result / system(无 uuid) 等消息靠内容指纹去重，保证跨分页合并时不重不漏。 */
function sdkMessageKey(msg: unknown): string {
  const m = msg as { uuid?: string; type?: string; message?: unknown; _createdAt?: number; error?: unknown }
  if (typeof m?.uuid === 'string' && m.uuid.length > 0) return `u:${m.uuid}`
  // 无 uuid：用 type + message 内容 + _createdAt 组合指纹；同一消息跨分页返回时 fingerprint 不变，
  // 不同消息即使 type 相同、内容长度相近也能靠内容差异区分（降低哈希碰撞风险）。
  try {
    const content = JSON.stringify(m?.message ?? m?.error ?? {})
    // 简单可复现的滚动哈希（djb2），避免全量内容字符串占用内存/日志。
    let h = 5381
    for (let i = 0; i < content.length; i++) {
      h = ((h << 5) + h + content.charCodeAt(i)) | 0
    }
    return `h:${m?.type ?? '?'}:${h}:${typeof m?._createdAt === 'number' ? m._createdAt : ''}`
  } catch {
    return `h:${m?.type ?? '?'}:${String(msg).slice(0, 64)}`
  }
}

/**
 * 稳健合并"旧缓存 + 最新分页窗口"，返回新的 SdkMessagesPageState。
 *
 * 背景：服务端 paginateSDKMessages 会把窗口起点快进到 user-turn 边界，且两次分页之间
 * 若新增消息数超过 targetMessages，新窗口起点与旧缓存尾部之间可能存在整段消息。
 * 用 page.startIndex - prev.startIndex 切旧缓存前缀会丢消息。这里改为基于去重键：
 *  1. 在旧缓存 messages 中定位 latest 首条消息（去重键逐条比对），找到重叠点。
 *  2. 找不到重叠点（两窗口完全不重叠）→ 丢弃旧缓存，以最新窗口为准（宁可重拉更早，也不丢最新）。
 *  3. 找到重叠点 → 旧缓存前缀（重叠点之前）+ latest（自重叠点起的最新连续窗口）。
 * 这样无论 startIndex 如何快进、新增多少条，都保证尾部消息完整、前缀不重复。
 */
function mergePageWithCache(
  prevMessages: unknown[],
  latest: unknown[],
  prevStartIndex: number,
  pageStartIndex: number,
  pageHasMore: boolean,
): SdkMessagesPageState {
  if (latest.length === 0) {
    return { messages: prevMessages, startIndex: prevStartIndex, hasMore: pageHasMore }
  }

  const firstKey = sdkMessageKey(latest[0])
  // 从旧缓存末尾向前找 latest 首条消息的匹配位置（重叠点通常在旧缓存尾部附近）。
  let overlap = -1
  for (let i = prevMessages.length - 1; i >= 0; i--) {
    if (sdkMessageKey(prevMessages[i]) === firstKey) {
      overlap = i
      break
    }
  }

  if (overlap < 0) {
    // 完全不重叠：新窗口已经越过旧缓存尾部（新增消息过多）。以后面连续的最新窗口为准，
    // 避免用失效的索引差值拼接出中间缺口的坏序列。更早历史由触顶加载按 before 补齐。
    return { messages: latest, startIndex: pageStartIndex, hasMore: pageHasMore }
  }

  // 有重叠：旧缓存 [0, overlap) 前缀 + latest 全部（latest 从重叠点开始是连续更新的尾部）。
  const prefix = prevMessages.slice(0, overlap)
  return {
    messages: [...prefix, ...latest],
    // startIndex 仍是前缀首条消息的绝对索引（= 旧起点），与 overlap 无关。
    startIndex: prevStartIndex,
    hasMore: pageHasMore,
  }
}

// ===== 事件桥：注册器（供桌面组件注册）+ emit（供平板 WS 层喂事件） =====

type Listener<T> = (payload: T) => void

const agentStreamEventListeners = new Set<Listener<AgentStreamEvent>>()
const agentStreamCompleteListeners = new Set<Listener<AgentStreamCompletePayload>>()
const agentStreamErrorListeners = new Set<Listener<{ sessionId: string; error: unknown }>>()
const agentTitleUpdatedListeners = new Set<Listener<{ sessionId: string; title: string }>>()
const agentSessionUpdatedListeners = new Set<Listener<{ session: unknown }>>()
const todoAgentSessionReadyListeners = new Set<Listener<unknown>>()
const runtimeProcessesChangedListeners = new Set<Listener<unknown>>()

// ---- Chat 流式事件注册器（useGlobalChatListeners 消费；WS chat_event 按通道喂回） ----
const chatChunkListeners = new Set<Listener<StreamChunkEvent>>()
const chatReasoningListeners = new Set<Listener<StreamReasoningEvent>>()
const chatCompleteListeners = new Set<Listener<StreamCompleteEvent>>()
const chatErrorListeners = new Set<Listener<StreamErrorEvent>>()
const chatToolActivityListeners = new Set<Listener<StreamToolActivityEvent>>()

function register<T>(set: Set<Listener<T>>, cb: Listener<T>): () => void {
  set.add(cb)
  return () => { set.delete(cb) }
}

function emitTo<T>(set: Set<Listener<T>>, payload: T): void {
  for (const listener of [...set]) {
    try {
      listener(payload)
    } catch (e) {
      console.error('[Pocket] 事件监听器执行异常', e)
    }
  }
}

/** 平板 WS agent_event → 桌面 IPC AgentStreamEvent 形状，喂给 useGlobalAgentListeners。 */
export function emitPocketAgentStreamEvent(event: AgentStreamEvent): void {
  emitTo(agentStreamEventListeners, event)
}

export function emitPocketChatStreamEvent(channel: string, payload: unknown): void {
  switch (channel) {
    case CHAT_IPC_CHANNELS.STREAM_CHUNK:
      emitTo(chatChunkListeners, payload as StreamChunkEvent)
      break
    case CHAT_IPC_CHANNELS.STREAM_REASONING:
      emitTo(chatReasoningListeners, payload as StreamReasoningEvent)
      break
    case CHAT_IPC_CHANNELS.STREAM_COMPLETE:
      emitTo(chatCompleteListeners, payload as StreamCompleteEvent)
      break
    case CHAT_IPC_CHANNELS.STREAM_ERROR:
      emitTo(chatErrorListeners, payload as StreamErrorEvent)
      break
    case CHAT_IPC_CHANNELS.STREAM_TOOL_ACTIVITY:
      emitTo(chatToolActivityListeners, payload as StreamToolActivityEvent)
      break
    default:
      console.warn('[Pocket] 未知 Chat 流式通道:', channel)
  }
}

/** 供 future WS 协议扩展时调用（当前 remote-service 暂无对应事件源）。 */
export function emitPocketAgentStreamComplete(payload: AgentStreamCompletePayload): void {
  emitTo(agentStreamCompleteListeners, payload)
}

/** 用户主动停止标记（stopAgent 记录，run_idle 桥接 STREAM_COMPLETE 时消费）。 */
const pocketStoppedByUser = new Set<string>()

/** 取并清除指定会话的用户停止标记（未标记返回 false）。 */
export function consumePocketStoppedByUser(sessionId: string): boolean {
  const stopped = pocketStoppedByUser.has(String(sessionId))
  pocketStoppedByUser.delete(String(sessionId))
  return stopped
}

// ===== 降级工具 =====

function noop(..._args: unknown[]): unknown {
  return undefined
}

/** 常见但平板不需要真正实现的方法 → 安全空实现 */
const safeNoop = (): Promise<unknown> => Promise.resolve(undefined)

/** 平板明确不支持的能力 → 拒绝并给出中文提示（调用方 catch 后 toast 呈现） */
const unsupported = (what: string): Promise<never> =>
  Promise.reject(new Error(`平板暂不支持${what}`))

/**
 * 未显式 stub 的 electronAPI 成员名（按首次访问顺序）。
 * 用于开发期聚合告警，也供诊断/测试断言「能力缺口被识别」而不是静默成功。
 */
const missingElectronApiKeys = new Set<string>()

/** 供诊断/测试：已探测到但未在 pocket stub 中实现的 electronAPI 成员名。 */
export function getMissingElectronApiKeys(): string[] {
  return [...missingElectronApiKeys]
}

/** 是否开发构建（渲染层由 vite 注入 import.meta.env；bun test 等环境安全回退 false）。 */
function isDevBuild(): boolean {
  try {
    return Boolean((import.meta as unknown as { env?: { DEV?: boolean } })?.env?.DEV)
  } catch {
    return false
  }
}

/**
 * 安装平板版 electronAPI 桥。
 * 在业务 React 渲染之前调用（main.tsx 顶部）。
 */
export function installElectronApiStub(): void {
  const existing = (globalThis as unknown as { electronAPI?: unknown }).electronAPI
  if (existing) return // 若已存在（Electron 环境）则不覆盖

  const stub: Record<string, unknown> = {
    // ---- 事件桥（注册器；WS 事件由 emitPocketAgentStreamEvent 喂回） ----
    onAgentStreamEvent: (cb: Listener<AgentStreamEvent>) => register(agentStreamEventListeners, cb),
    onAgentStreamComplete: (cb: Listener<AgentStreamCompletePayload>) => register(agentStreamCompleteListeners, cb),
    onAgentStreamError: (cb: Listener<{ sessionId: string; error: unknown }>) => register(agentStreamErrorListeners, cb),
    onAgentTitleUpdated: (cb: Listener<{ sessionId: string; title: string }>) => register(agentTitleUpdatedListeners, cb),
    onAgentSessionUpdated: (cb: Listener<{ session: unknown }>) => register(agentSessionUpdatedListeners, cb),
    onTodoAgentSessionReady: (cb: Listener<unknown>) => register(todoAgentSessionReadyListeners, cb),
    onRuntimeProcessesChanged: (cb: Listener<unknown>) => register(runtimeProcessesChangedListeners, cb),
    // ---- Chat 流式事件注册器（useGlobalChatListeners 消费；WS chat_event 按通道喂回） ----
    onStreamChunk: (cb: Listener<StreamChunkEvent>) => register(chatChunkListeners, cb),
    onStreamReasoning: (cb: Listener<StreamReasoningEvent>) => register(chatReasoningListeners, cb),
    onStreamComplete: (cb: Listener<StreamCompleteEvent>) => register(chatCompleteListeners, cb),
    onStreamError: (cb: Listener<StreamErrorEvent>) => register(chatErrorListeners, cb),
    onStreamToolActivity: (cb: Listener<StreamToolActivityEvent>) => register(chatToolActivityListeners, cb),
    // 大刷新后恢复活跃流：平板刷新时事件已通过 WS 继续推送（断线期间的历史事件丢失），
    // 无需 main 回放，返回空列表即可（桌面调用方会遍历 sessionIds 写 streaming 占位）。
    restoreActiveAgentStreams: () => Promise.resolve([]),

    // ---- 命令映射：Agent 核心动作 → WS 远程命令 ----
    sendAgentMessage: (input: Record<string, unknown>) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      // G1-a：渲染进程预生成的 uuid / startedAt 透传给服务端。
      // uuid 让服务端把持久化消息与乐观气泡按同一身份对齐（回传后气泡让位、不重复）；
      // startedAt 让 STREAM_COMPLETE 的竞态保护比较同源于本机时钟，避免跨机绝对时钟偏差。
      // 旧服务端不认识这两个字段会直接忽略，不构成协议破坏。
      const uuid = typeof input.uuid === 'string' ? input.uuid : undefined
      const startedAt = typeof input.startedAt === 'number' ? input.startedAt : undefined
      const payload = {
        sessionId: String(input.sessionId || ''),
        userMessage: String(input.userMessage || ''),
        channelId: String(input.channelId || ''),
        modelId: input.modelId as string | undefined,
        workspaceId: input.workspaceId as string | undefined,
        permissionMode: input.permissionModeOverride as 'auto' | 'plan' | 'bypassPermissions' | undefined,
        uuid,
        startedAt,
      }
      debugLog(`[WS send] session=${payload.sessionId} chars=${payload.userMessage.length}`)
      const cacheSessionId = payload.sessionId
      return remoteClient.sendMessage(payload).then((result) => {
        // PB-5：服务端已接受该消息 → 同步写入传输层分页缓存（加固，见函数注释）。
        if (typeof uuid === 'string' && uuid.length > 0) {
          appendOptimisticMessageToPageCache(cacheSessionId, {
            type: 'user',
            uuid,
            message: { content: [{ type: 'text', text: payload.userMessage }] },
            parent_tool_use_id: null,
            _createdAt: typeof startedAt === 'number' ? startedAt : Date.now(),
          })
        }
        return result
      })
    },
    queueAgentMessage: async (input: Record<string, unknown>) => {
      // 平板队列消息必须走主进程 queue_message 指令（注入正在运行的 Agent）：
      // 直接降级为 send_message 会被编排器并发保护拒绝（"上一条消息仍在处理中"），
      // 表现为“队列消息立即插入不了”。这里保留 uuid/interrupt/mention 语义。
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      try {
        return await remoteClient.queueMessage({
          sessionId: String(input.sessionId || ''),
          userMessage: String(input.userMessage || ''),
          rawUserMessage: typeof input.rawUserMessage === 'string' ? input.rawUserMessage : undefined,
          uuid: typeof input.uuid === 'string' ? input.uuid : undefined,
          interrupt: input.interrupt === true,
          mentionedSkills: Array.isArray(input.mentionedSkills) ? input.mentionedSkills as string[] : undefined,
          mentionedMcpServers: Array.isArray(input.mentionedMcpServers) ? input.mentionedMcpServers as string[] : undefined,
          mentionedSessionIds: Array.isArray(input.mentionedSessionIds) ? input.mentionedSessionIds as string[] : undefined,
        })
      } catch (error) {
        // 目标不再活跃（上一轮 turn 已结束、renderer 尚未同步）：与桌面
        // isQueueTargetNoLongerActiveError → startNewRun 降级一致，转为直接发送新 run。
        if (error instanceof Error && /^\[Agent 编排\] 会话未运行，无法追加消息: /.test(error.message)) {
          return remoteClient.sendMessage({
            sessionId: String(input.sessionId || ''),
            userMessage: String(input.userMessage || ''),
            channelId: String(input.channelId || ''),
            modelId: input.modelId as string | undefined,
            workspaceId: input.workspaceId as string | undefined,
          })
        }
        throw error
      }
    },
    getAgentSessionSDKMessages: async (
      sessionId: string,
      opts?: { before?: number; targetMessages?: number; pullEarlier?: boolean; paginateFirst?: boolean },
    ) => {
      if (!remoteClient) return Promise.resolve([])
      // pullEarlier：由 AgentView 在移动端“触顶加载更早”时触发，用缓存已追踪的 startIndex 补前页。
      if (opts && opts.pullEarlier === true) {
        const prev = sdkMessagesPageCache.get(sessionId)
        if (prev && prev.hasMore && prev.startIndex > 0) {
          const res = await remoteClient.getSdkMessages(sessionId, {
            before: prev.startIndex,
            targetMessages: opts.targetMessages ?? 20,
          })
          const page = res as { messages?: unknown[]; startIndex?: number; hasMore?: boolean; total?: number }
          if (Array.isArray(page?.messages) && typeof page?.startIndex === 'number') {
            const merged = [...(page.messages as unknown[]), ...prev.messages]
            setCachedPage(sessionId, {
              messages: merged,
              startIndex: page.startIndex,
              hasMore: page.hasMore !== false,
            })
          }
        }
        return sdkMessagesPageCache.get(sessionId)?.messages ?? []
      }
      // 明确请求“更早一页”用 before（兼容外部调用者）。
      if (opts && opts.before !== undefined) {
        const res = await remoteClient.getSdkMessages(sessionId, {
          before: opts.before,
          targetMessages: opts.targetMessages,
        })
        const page = res as { messages?: unknown[]; startIndex?: number; total?: number; hasMore?: boolean }
        const incoming = Array.isArray(page?.messages) ? page.messages as unknown[] : []
        // 旧端不支持分页（返回原始数组而非 {messages,startIndex,...}）→退回：直接返回全量。
        if (!Array.isArray(page?.messages) || typeof page?.startIndex !== 'number') {
          setCachedPage(sessionId, {
            messages: Array.isArray(res) ? (res as unknown[]) : [],
            startIndex: 0,
            hasMore: false,
          })
          return sdkMessagesPageCache.get(sessionId)!.messages
        }
        const prev = sdkMessagesPageCache.get(sessionId)
        if (prev && typeof prev.startIndex === 'number' && prev.startIndex >= page.startIndex) {
          // 新页起点 == 上一页起点，说明服务端该档没更早内容了（已到顶），保持现状。
          if (incoming.length === prev.messages.length) {
            return prev.messages
          }
        }
        // 新页覆盖 [page.startIndex, before)；合并为：新页 + 已累计（有序）。
        const merged = [...incoming, ...(prev?.messages ?? [])]
        setCachedPage(sessionId, {
          messages: merged,
          startIndex: page.startIndex,
          hasMore: page.hasMore !== false,
        })
        return merged
      }
      // 无任何分页标记（opts 为空）：返回全量（与桌面无参语义一致）。
      // 移动端侧栏悬浮预览（SessionMiniMapPopover）等走全量，计数/预览恢复真实内容。
      if (!opts || opts.paginateFirst !== true) {
        const raw = await remoteClient.getSdkMessages(sessionId)
        const messages = Array.isArray(raw) ? (raw as unknown[]) : []
        // 全量读取也必须覆盖分页缓存：否则冷启动先取过尾页，再因待交互快照升级为
        // 全量时，后续 refresh 仍会从旧的 4 条分页窗口合并，重新造成历史缺口。
        setCachedPage(sessionId, { messages, startIndex: 0, hasMore: false })
        return messages
      }
      // 显式 paginateFirst：打开会话首帧取最新 targetMessages 条；已有缓存则刷新尾部并保留更早。
      const prev = sdkMessagesPageCache.get(sessionId)
      if (prev && prev.messages.length > 0) {
        // 刷新尾部（流式/新 turn 处理后）：拉最新窗口并向前合并，保留已加载的更早历史。
        const res = await remoteClient.getSdkMessages(sessionId, {
          targetMessages: opts.targetMessages ?? 4,
        })
        const page = res as { messages?: unknown[]; startIndex?: number; total?: number; hasMore?: boolean }
        if (Array.isArray(page?.messages) && typeof page?.startIndex === 'number') {
          const latest = page.messages as unknown[]
          // 稳健合并：不依赖 page.startIndex - prev.startIndex 的绝对索引差。
          // startIndex 会被 user-turn 边界快进，两次分页之间若新增消息数 > targetMessages，
          // 新窗口起点会跳过旧缓存尾部之间的整段消息，用索引差值切 prefix 会丢消息。
          // 这里改成"按去重键求前缀"：从 prev 里找到 latest 首条消息的位置，截断重叠，避免丢/重。
          const merged = mergePageWithCache(prev.messages, latest, prev.startIndex, page.startIndex, page.hasMore !== false)
          setCachedPage(sessionId, merged)
          return merged.messages
        }
        // 旧端返回原始数组：直接用新数据覆盖。
        const raw = Array.isArray(res) ? (res as unknown[]) : prev.messages
        setCachedPage(sessionId, { messages: raw, startIndex: 0, hasMore: false })
        return raw
      }
      // 首帧（无缓存）：拉最新 targetMessages 条，奠基缓存。
      const res = await remoteClient.getSdkMessages(sessionId, {
        targetMessages: opts.targetMessages ?? 4,
      })
      if (Array.isArray(res)) {
        // 旧端/未分页：直接返回原始数组。
        setCachedPage(sessionId, { messages: res as unknown[], startIndex: 0, hasMore: false })
        return res
      }
      const page = res as { messages?: unknown[]; startIndex?: number; total?: number; hasMore?: boolean }
      const msgs = Array.isArray(page?.messages) ? page.messages as unknown[] : []
      setCachedPage(sessionId, {
        messages: msgs,
        startIndex: typeof page?.startIndex === 'number' ? page.startIndex : 0,
        hasMore: page?.hasMore !== false,
      })
      return msgs
    },
    getSdkMessagesHasMore: (sessionId: string) => {
      return sdkMessagesPageCache.get(sessionId)?.hasMore ?? false
    },
    updateAgentSessionModel: (sessionId: string, channelId: string, modelId?: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.updateSessionModel(sessionId, channelId, modelId).then((r) => {
        // 契约兜底：旧版服务端可能只返回 { channelId, modelId }，补全 id 等字段，
        // 保证桌面组件 .then((updated) => updated.id / updated.updatedAt) 拿到完整对象。
        const updated = (r ?? {}) as Record<string, unknown>
        return { ...updated, id: updated.id ?? sessionId }
      })
    },
    updateSessionAgentRuntime: (sessionId: string, runtime: 'claude' | 'pi') => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.updateSessionRuntime(sessionId, runtime)
    },
    updateSessionPermissionMode: (sessionId: string, mode: 'auto' | 'plan' | 'bypassPermissions') => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.updatePermissionMode(sessionId, mode)
    },
    // ---- Agent 预设：全部走 WS 远程命令（预设数据在电脑端主进程持久化，两端共享） ----
    listAgentPresets: (workspaceSlug?: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.listPresets(workspaceSlug)
    },
    getDefaultAgentPreset: (workspaceSlug?: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.getDefaultPreset(workspaceSlug)
    },
    updateAgentSessionPreset: (sessionId: string, presetId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.updateSessionPreset(sessionId, presetId).then((r) => {
        // 契约兜底：旧版服务端可能未返回完整 meta，补全 id / presetId 字段，
        // 保证桌面组件 .then((updated) => updated.presetId) 拿到持久化真源。
        const updated = (r ?? {}) as Record<string, unknown>
        return { ...updated, id: updated.id ?? sessionId, presetId: updated.presetId ?? presetId }
      })
    },
    setDefaultAgentPreset: (workspaceSlug: string, presetId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.setDefaultPreset(workspaceSlug, presetId)
    },
    createAgentPreset: (workspaceSlug: string, input: unknown) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.createPreset(workspaceSlug, input as Record<string, unknown>)
    },
    copyAgentPreset: (workspaceSlug: string, fromId: string, name?: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.copyPreset(workspaceSlug, fromId, name)
    },
    updateAgentPreset: (workspaceSlug: string, presetId: string, updates: unknown) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.updatePreset(workspaceSlug, presetId, updates as Record<string, unknown>)
    },
    deleteAgentPreset: (workspaceSlug: string, presetId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.deletePreset(workspaceSlug, presetId)
    },
    // 预设导出/导入（JSON 文件）与跨工作区导入：平板暂不支持，显式拒绝避免 Proxy 兜底 undefined 静默失效
    exportAgentPresets: () => unsupported('预设导出/导入'),
    importAgentPresets: () => unsupported('预设导出/导入'),
    getOtherWorkspacePresets: () => unsupported('跨工作区导入'),
    importPresetFromWorkspace: () => unsupported('跨工作区导入'),
    // 预设编辑对话框的 Skill/MCP 白名单数据源：平板无对应 WS 命令，返回合法空结构
    // （Proxy 兜底的 safeNoop 返回 undefined 会让 availableSkills.map 崩溃），白名单留空 = 不限制。
    getWorkspaceSkills: (): Promise<unknown[]> => Promise.resolve([]),
    getWorkspaceMcpConfig: (): Promise<{ servers: Record<string, unknown> }> => Promise.resolve({ servers: {} }),
    // ---- 交互式问答/审批响应：AskUserQuestion、权限审批、ExitPlanMode 的选项必须真正回传给主进程 ----
    respondAskUser: ({ requestId, answers }: { requestId: string; answers: Record<string, string> }) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.respondAskUser(requestId, answers)
    },
    respondPermission: ({ requestId, behavior, alwaysAllow }: { requestId: string; behavior: 'allow' | 'deny'; alwaysAllow?: boolean }) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.respondPermission(requestId, behavior, alwaysAllow ?? false)
    },
    respondExitPlanMode: ({ requestId, action, feedback }: { requestId: string; action: string; feedback?: string }) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.respondExitPlanMode(requestId, action as 'approve_auto' | 'approve_edit' | 'deny' | 'feedback', feedback)
    },
    // 交互请求过期判定（R8-P0）：三个横幅在关闭/提交前用主端 pending 快照确认该 requestId
    // 是否仍待处理。连接未建立 / 旧服务端不支持该命令时返回 'unknown'，调用方按原有行为处理。
    getPendingInteractionVerdict: (query: InteractionVerdictQuery) =>
      resolvePocketInteractionVerdict(remoteClient, query),
    stopAgent: (sessionId: string) => {
      // 记录用户主动停止标记：run_idle 桥接 STREAM_COMPLETE 时用（stoppedByUser 展示“已停止”）
      if (sessionId) pocketStoppedByUser.add(String(sessionId))
      const stack = new Error().stack?.split('\n').slice(1, 4).map((line) => line.trim()).join(' ← ')
      debugLog(`[WS stop] session=${String(sessionId)} source=${stack || 'unknown'}`)
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.stopAgent(sessionId)
    },
    // ---- 命令映射：LeftSidebar 会话管理（已在 WebSocket 建连后注入） ----
    listAgentSessions: () => remoteClient?.listSessions() ?? Promise.resolve([]),
    createAgentSession: async (title?: string, channelId?: string, workspaceId?: string, modelId?: string, permissionMode?: 'auto' | 'plan' | 'bypassPermissions') => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      const created = await remoteClient.createSession({ title, channelId, workspaceId, modelId, permissionMode }) as Record<string, unknown>
      const sessionId = typeof created.id === 'string' ? created.id : String(created.sessionId || '')
      if (!sessionId) throw new Error('远端创建会话未返回 sessionId')
      return resolveAuthoritativeAgentSession(remoteClient, sessionId, created)
    },
    ensureProjectDraftAgentSession: async (workspaceId: string, channelId?: string, modelId?: string) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      // 复用语义：项目已有草稿会话则返回它（不再每次新建），对齐桌面 ensureProjectDraftAgentSession
      const created = await remoteClient.ensureProjectDraftSession({ workspaceId, channelId, modelId }) as Record<string, unknown>
      const sessionId = typeof created.id === 'string' ? created.id : String(created.sessionId || '')
      if (!sessionId) throw new Error('远端草稿会话未返回 sessionId')
      return resolveAuthoritativeAgentSession(remoteClient, sessionId, created)
    },
    updateAgentSessionTitle: async (id: string, title: string) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      const updated = await remoteClient.renameSession(id, title)
      return resolveAuthoritativeAgentSession(remoteClient, id, updated)
    },
    getAgentSessionMeta: async (id: string) => {
      const sessions = await (remoteClient?.listSessions() ?? Promise.resolve([])) as Array<{ id: string }>
      return sessions.find((session) => session.id === id)
    },
    // 优先走真实工作区列表（list_workspaces 由 remote-service 返回桌面同构数据，含真实项目名称）；
    // 旧版服务端无此指令时只兜底默认工作区（不再从会话归纳 workspaceId，避免孤儿会话伪装成幽灵项目）。
    listAgentWorkspaces: async () => {
      if (remoteClient) {
        try {
          const workspaces = await remoteClient.listWorkspaces() as Array<{ id: string; name: string; slug: string; type?: string; createdAt?: number; updatedAt?: number }> | undefined
          if (Array.isArray(workspaces) && workspaces.length > 0) {
            // 与 main.tsx loadSessions 的团队过滤一致：LeftSidebar 会主动调本方法刷新侧栏，
            // 若不过滤会把团队工作区重新塞回 agentWorkspacesAtom，覆盖 loadSessions 的过滤结果。
            return workspaces
              .filter((w) => w.type !== 'team')
              .map((w) => ({
                id: w.id,
                name: w.name,
                slug: w.slug,
                type: w.type ?? 'personal',
                createdAt: w.createdAt ?? 0,
                updatedAt: w.updatedAt ?? 0,
              }))
          }
        } catch {
          /* 服务端不支持时走下面的兜底 */
        }
      }
      // 兜底：服务端不支持 list_workspaces 时，只回退默认工作区。
      // ⚠️ 不能从历史会话归纳全部 workspaceId：被删除项目的会话仍然存在（孤儿会话），
      // 归纳会把已删除项目以 UUID 名字伪装成“幽灵项目”重新出现在平板侧栏。
      return [{ id: 'default', name: '默认工作区', slug: 'default', type: 'personal', createdAt: 0, updatedAt: 0 }]
    },
    // 删除会话：走 remote-service 的 delete_session 指令（对齐桌面 stop-and-wait + 清理持久化语义）。
    // ⚠️ 必须显式 stub：缺省时 Proxy noop 会让 UI“假删除成功”（本地列表移除、主进程未删，刷新后复活）。
    deleteAgentSession: async (sessionId: string) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      await remoteClient.deleteSession(sessionId)
    },
    // 创建项目：通过 remote-service 的 create_workspace 指令在远端主实例创建。
    // ⚠️ 必须显式 stub：缺省时 Proxy 兜底 noop 返回 undefined，
    // 会让左侧栏把 undefined 塞进 workspaces 列表 → find(w => w.id) 读 undefined.id 崩溃（必现白屏）。
    createAgentWorkspace: async (name: string) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      const created = await remoteClient.createWorkspace(name) as
        | { id: string; name: string; slug: string; type?: string; createdAt?: number; updatedAt?: number }
        | undefined
      if (!created || typeof created !== 'object') {
        throw new Error('创建工作区失败：远端返回异常')
      }
      return {
        id: created.id,
        name: created.name,
        slug: created.slug,
        type: created.type ?? 'personal',
        createdAt: created.createdAt ?? 0,
        updatedAt: created.updatedAt ?? 0,
      }
    },
    // 重命名/删除/排序：平板暂无对应指令，显式拒绝（绝不能靠 Proxy 兜底 noop，
    // 否则“删除成功”是假的、重命名静默失败，且可能污染列表/造成桌面索引不一致）。
    updateAgentWorkspace: () => unsupported('重命名项目'),
    deleteAgentWorkspace: () => unsupported('删除项目'),
    reorderAgentWorkspaces: () => unsupported('项目排序'),

    // ---- 降级：只读/空数据，维持复用组件可渲染 ----
    getSettings: () => Promise.resolve({}),
    updateSettings: safeNoop,
    // 真实用户档案：从主进程 user-profile-service 读取（LeftSidebar 挂载时自动消费写入 userProfileAtom，
    // 侧栏底部用户名/头像即真实值）；旧版服务端无 get_user_profile 指令时回退默认档案。
    getUserProfile: async () => {
      if (remoteClient) {
        try {
          const profile = await remoteClient.getUserProfile() as { userName?: string; avatar?: string } | undefined
          if (profile && typeof profile === 'object') {
            return {
              userName: profile.userName || 'Profer 用户',
              avatar: profile.avatar || '',
            }
          }
        } catch {
          /* 服务端不支持，走兜底 */
        }
      }
      return { userName: 'Profer 用户', avatar: '' }
    },
    getPendingInteractions: (sessionId?: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.getPendingInteractions(sessionId)
    },
    // Pocket 不经过桌面主进程；直接读取 WebView 暴露的系统外观，避免始终回报暗色。
    getSystemTheme: () => Promise.resolve(
      typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches,
    ),
    // SystemPromptSelector（ChatHeader）挂载时拉取提示词配置并 setConfig 覆写 promptConfigAtom：
    // 必须返回桌面同构默认配置，否则 Proxy 兜底的 undefined 会把 promptConfigAtom 覆写成 undefined，
    // 导致 defaultPromptIdAtom 等派生 atom 抛 “Cannot read properties of undefined (reading 'defaultPromptId')”，
    // 整个 Chat 视图（含 LeftSidebar 新建对话）崩溃。
    getSystemPromptConfig: () => Promise.resolve({
      prompts: [BUILTIN_DEFAULT_PROMPT],
      defaultPromptId: BUILTIN_DEFAULT_ID,
      appendDateTimeAndUserName: true,
    }),
    // 工具选择器：平板不修改工具开关，返回空列表（避免 undefined 覆写 chatToolsAtom）
    getChatTools: () => Promise.resolve([]),
    // ---- Chat（聊天工具）命令映射：桌面 ChatView / LeftSidebar / useGlobalChatListeners 的 IPC 命令 → WS 远程命令 ----
    listConversations: () => remoteClient?.listConversations() ?? Promise.resolve([]),
    createConversation: (title?: string, modelId?: string, channelId?: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.createConversation({ title, modelId, channelId })
    },
    getConversationMessages: (conversationId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.getConversationMessages(conversationId)
    },
    getRecentMessages: (conversationId: string, limit: number) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.getRecentMessages(conversationId, limit)
    },
    updateConversationTitle: (conversationId: string, title: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.updateConversationTitle(conversationId, title)
    },
    updateConversationModel: (conversationId: string, modelId?: string, channelId?: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.updateConversationModel(conversationId, modelId, channelId)
    },
    deleteConversation: (conversationId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.deleteConversation(conversationId)
    },
    togglePinConversation: (conversationId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.toggleConversationPin(conversationId)
    },
    toggleArchiveConversation: (conversationId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.toggleConversationArchive(conversationId)
    },
    searchConversationMessages: (query: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.searchChatMessages(query)
    },
    searchAgentSessionMessages: (query: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.searchAgentSessionMessages(query)
    },
    sendMessage: (input: Record<string, unknown>) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      const conv = input as {
        conversationId: string
        userMessage: string
        channelId: string
        modelId?: string
        contextLength?: number
        contextDividers?: string[]
        attachments?: unknown[]
        knowledgeReferences?: unknown[]
        thinkingEnabled?: boolean
        systemMessage?: string
        enabledToolIds?: string[]
      }
      return remoteClient.chatSendMessage({
        conversationId: conv.conversationId,
        userMessage: conv.userMessage,
        channelId: conv.channelId,
        modelId: conv.modelId,
        contextLength: conv.contextLength,
        contextDividers: conv.contextDividers,
        attachments: conv.attachments,
        knowledgeReferences: conv.knowledgeReferences,
        thinkingEnabled: conv.thinkingEnabled,
        systemMessage: conv.systemMessage,
        enabledToolIds: conv.enabledToolIds,
      })
    },
    stopGeneration: (conversationId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatStopGeneration(conversationId)
    },
    deleteMessage: (conversationId: string, messageId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatDeleteMessage(conversationId, messageId)
    },
    truncateMessagesFrom: (conversationId: string, messageId: string, preserveFirstMessageAttachments?: boolean) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatTruncateMessagesFrom(conversationId, messageId, preserveFirstMessageAttachments)
    },
    updateContextDividers: (conversationId: string, dividers: string[]) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatUpdateContextDividers(conversationId, dividers)
    },
    generateTitle: (input: GenerateTitleInput) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatGenerateTitle(input)
    },
    saveAttachment: (input: { conversationId: string; filename: string; mediaType: string; data: string }) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatSaveAttachment(input)
    },
    deleteAttachment: (localPath: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatDeleteAttachment(localPath)
    },
    readAttachment: (localPath: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.chatReadAttachment(localPath)
    },
    getChannels: () => Promise.resolve([]),
    // ChannelPlanQuotaBadge（Chat 模型选择器/会话头部展示渠道额度）会调用 getChannelPlanQuota：
    // 必须返回明确的“不支持”结果对象，不能靠 Proxy 兜底 undefined——fetchChannelPlanQuota
    // 会把返回值写进缓存，若写入 undefined，后续切换模型时 getCachedPlanQuota 读
    // cached.result.updatedAt 会抛 “Cannot read properties of undefined (reading 'updatedAt')”。
    getChannelPlanQuota: () => Promise.resolve({
      supported: false,
      provider: 'custom',
      windows: [],
      updatedAt: Date.now(),
      message: '平板暂不支持订阅额度查询',
    }),
    // ModelSelector 打开时会调用 listChannels 刷新渠道列表：
    // 必须返回 WS 真实渠道，否则空数组会覆盖平板已喂好的 channelsAtom，模型选择器变成空列表。
    // 注意：WS 返回的是桌面渠道原始形状，没有平板补丁后的 enabled 语义（桌面 enabled 由
    // ChannelSettings 持久化，WS 不应用用户设置）。这里与 loadChannels 一样补全 enabled，
    // 否则 ModelSelector 的 modelOptions（enabled && models[].enabled）恒空 → “暂无可用模型”。
    // WS 未就绪 / 获取失败 / 返回空列表时返回快照（lastChannelsSnapshot），绝不返回空数组，
    // 防止 setChannels([]) 覆盖已加载渠道导致 Agent 模式误报“暂无可用模型”且无法自行恢复。
    listChannels: async () => {
      let data: unknown
      try {
        data = await (remoteClient?.listChannels() ?? Promise.resolve(null))
      } catch {
        return lastChannelsSnapshot
      }
      const raw = Array.isArray(data) ? data : []
      if (raw.length === 0) return lastChannelsSnapshot
      const normalized = raw.map((c) => ({
        ...c,
        enabled: true,
        models: ((c as { models?: Array<{ id?: string }> }).models || []).map((m) => ({ ...m, enabled: true })),
      }))
      lastChannelsSnapshot = normalized
      return normalized
    },
    getModels: () => Promise.resolve([]),
    getWorkspaceCapabilities: async (workspaceSlug: string) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      const result = await remoteClient.getWorkspaceCapabilities(workspaceSlug)
      return result ?? null
    },
    getWorkspaceHeatmapDaily: (workspaceId: string) =>
      requestWorkspaceHeatmapDaily(remoteClient, workspaceId),
    getAccountCapabilities: () => Promise.resolve({ membershipTier: 'free', canSelfConfig: true }),
    getWorkspaceFilesPath: () => Promise.resolve(null),
    getGitRepoStatus: () => Promise.resolve(null),
    getWorkspaceDirectories: () => Promise.resolve([]),
    getWorkspaceAttachedFiles: () => Promise.resolve([]),
    getSessionProcessCount: () => Promise.resolve(0),
    listSessionProcesses: () => Promise.resolve([]),
    // 归档会话计数：pocket 当前左侧栏无归档徽标调用点（grep=0），按主仓库 tablet 参照补 0 计数兜底。
    getArchivedCounts: () => Promise.resolve({ conversations: 0, agentSessions: 0 }),
    // 商业版开关：pocket 无主进程配置源，恒按「非商业版」处理（useCreditsLoader → clearCreditsState）。
    getCommercialMode: () => Promise.resolve(false),
    // Pi 模型推理档位能力：pocket 无该数据源 → undefined（档位菜单按「未知能力」渲染）。
    // Pi 推理档位能力（G3）：改由 WS 命令取服务端 resolvePiReasoningCapability 的计算结果。
    // 为何不在客户端推导：catalog 分支需要主进程加载的 pi-ai 目录（renderer 拿不到）；
    // profile 分支虽可算，但 pocket 的 shared 快照比桌面旧，会在 glm-5.3/grok-4.6 等模型上
    // 给出与桌面不同的档位集合 → 等于把双端漂移换个地方复现。
    // 旧服务端不认识该命令 → sendCommand 拒绝或 ok:false → 这里返回 undefined，
    // 与改动前「恒 undefined」的降级完全等价（调用方 AgentView:733 已带 .catch 兜底）。
    getPiReasoningCapability: async (provider: string, modelId?: string) => {
      if (!remoteClient || !provider) return undefined
      try {
        const result = (await remoteClient.getPiReasoningCapability(provider, modelId ?? '')) as
          | { levels?: unknown }
          | null
          | undefined
        // 形状校验：非 ReasoningCapability（如旧服务端的错误对象）一律视为不可用。
        if (!result || typeof result !== 'object' || !Array.isArray(result.levels)) return undefined
        return result
      } catch {
        return undefined
      }
    },
    // 会话本地目录：pocket 无本地文件系统，远程协议未暴露会话路径 → null（调用方走「无路径」分支）。
    getAgentSessionPath: () => Promise.resolve(null),
    // @ 引用文件搜索（G2-c）：桌面端是主进程本地 fs 递归扫描（rootPath 由 renderer 传入），
    // pocket 无本地文件系统 → 改走 WS `search_workspace_files`，roots 由服务端从会话
    // （会话工作目录 + attachedDirectories + 工作区附加目录 + attachedFiles）推导并做授权校验，
    // 客户端不提交 rootPath/candidateBasePaths（与 resolve_and_read_file 同一授权策略）。
    // 注意：pocket 语义下第一个参数承载 **sessionId**（调用方 file-mention-suggestion 已同步）；
    // 形参名保持与 electron-api.d.ts 一致，避免两端 API 面分裂。
    // 旧服务端不认识该命令 → sendCommand 拒绝/ok:false → 返回 null；调用方回退到
    // 「暂时无法引用文件」的既有降级，不报错、不白屏。
    searchWorkspaceFiles: async (sessionId: string, query: string, limit?: number) => {
      if (!remoteClient || !sessionId) return null
      try {
        const result = (await remoteClient.searchWorkspaceFiles(sessionId, query, limit)) as
          | { entries?: unknown }
          | null
          | undefined
        if (!result || typeof result !== 'object' || !Array.isArray(result.entries)) return null
        return result
      } catch {
        return null
      }
    },
    // 清除「已完成未确认」标记：pocket 无本地会话库 → 明确拒绝；调用方带 .catch（仅记日志）。
    clearAgentCompletionState: () => unsupported('清除会话完成标记'),
    // git diff 缓存失效：pocket 无本地 git 缓存 → 安全空操作（useGlobalAgentListeners 写工具完成路径直接调用）。
    invalidateGitDiffCache: safeNoop,
    getAgentKnowledgeReferences: () => Promise.resolve([]),
    knowledge: {
      getLibrarySnapshot: () => Promise.resolve({ items: [] }),
      // 知识库引用选择器：平板暂不支持知识库，返回空列表（必须显式返回数组，
      // 否则 Proxy 兜底的 undefined 会让调用方 snapshot.map 崩溃）
      listItems: () => Promise.resolve([]),
    },
    searchAgentSessionReferences: () => Promise.resolve([]),
    getPathForFile: () => Promise.resolve(''),
    checkPathsType: () => Promise.resolve({ directories: [], files: [] }),
    saveImageAs: safeNoop,
    openExternal: noop,
    team: { onWorkspacesSynced: () => noop },
    notifications: { show: safeNoop },

    // ---- 降级：明确拒绝（调用方 catch → toast 提示“平板暂不支持”） ----
    forkAgentSession: async (input: { sessionId: string; upToMessageUuid?: string }) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      // remote 已返回桌面同构 buildSessionItem（含 createdAt/updatedAt/draft/pinned 等），
      // 直接透传，保证 fork 后 setAgentSessions 插入的元数据与桌面一致（LeftSidebar 渲染/排序依赖）。
      return remoteClient.forkSession(input) as Promise<Record<string, unknown>>
    },
    // 探索分支：与分叉同源但语义不同（不传 modelId，分支挂主线血缘下）。
    // 必须显式 stub——否则 Proxy noop 会让“探索成功”是假的，且调用方读 meta.id 会拿到
    // undefined 导致 openSession 崩溃。服务端返回的已是含探索血缘字段的完整会话对象。
    createExplorationSession: async (input: { sessionId: string; upToMessageUuid: string; explorationSourceLabel?: string }) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      return remoteClient.createExplorationSession(input) as Promise<Record<string, unknown>>
    },
    rewindSession: async (input: { sessionId: string; assistantMessageUuid: string }) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      return remoteClient.rewindSession(input) as Promise<{ remainingMessages: number; fileRewind?: { canRewind: boolean; error?: string; filesChanged?: string[]; insertions?: number; deletions?: number } }>
    },
    // 置顶/归档/移动/推理档位：走 remote 指令（对齐桌面 IPC 语义），
    // 必须显式 stub——否则 Proxy noop 会让“置顶/归档成功”是假的，
    // 且 LeftSidebar 读 updated.pinned/updated.id 会拿到 undefined 崩溃。
    togglePinAgentSession: async (id: string) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      const updated = await remoteClient.toggleSessionPin(id)
      return resolveAuthoritativeAgentSession(remoteClient, id, updated)
    },
    toggleArchiveAgentSession: async (id: string) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      return remoteClient.toggleSessionArchive(id) as Promise<Record<string, unknown>>
    },
    moveAgentSessionToWorkspace: async (input: { sessionId: string; targetWorkspaceId: string }) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      return remoteClient.moveSessionToWorkspace(input) as Promise<Record<string, unknown>>
    },
    updateSessionOpenAIThinkingLevel: async (sessionId: string, level: string | null) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      return remoteClient.updateSessionThinkingLevel(sessionId, level) as Promise<Record<string, unknown>>
    },
    attachFile: () => unsupported('附加本地文件'),
    attachDirectory: () => unsupported('附加本地目录'),
    detachFile: () => unsupported('移除本地文件'),
    detachDirectory: () => unsupported('移除本地目录'),
    openFileDialog: () => unsupported('本地文件选择'),
    openFolderDialog: () => unsupported('本地目录选择'),
    getSkins: () => unsupported('皮肤管理'),
    getSkinCss: () => unsupported('皮肤管理'),
    getSkinPreview: () => unsupported('皮肤管理'),
    selectSkinZip: () => unsupported('皮肤管理'),
    selectSkinFolder: () => unsupported('皮肤管理'),
    installSkinZip: () => unsupported('皮肤管理'),
    installSkinFolder: () => unsupported('皮肤管理'),
    deleteUserSkin: () => unsupported('皮肤管理'),
    openUserSkinsFolder: () => unsupported('皮肤管理'),
    openSkinTemplateFolder: () => unsupported('皮肤管理'),
    refreshSkins: () => unsupported('皮肤管理'),
    onSkinsChanged: () => () => undefined,
    writeClipboardPreview: () => unsupported('剪贴板预览'),
    // 文件预览：经 WS 读取电脑端文件内容（Pocket 文件预览 MVP）。
    // 桌面端 FilePreviewDialog 走 electronAPI.resolveAndReadFile / registerPreviewPath，
    // 平板改为 WS 远程指令；access 透传 candidateBasePaths 让电脑端解析相对路径。
    resolveAndReadFile: async (filePath: string, access?: { sessionId?: string; candidateBasePaths?: string[] }) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      return remoteClient.resolveAndReadFile(filePath, access)
    },
    readFileAsDataUrl: async (filePath: string, access?: { sessionId?: string; candidateBasePaths?: string[] }) => {
      if (!remoteClient) throw new Error('移动端连接未就绪')
      return remoteClient.readFileAsDataUrl(filePath, access)
    },
    // 文件存在性解析（resolveFilePath）：pocket 无本地文件系统，无法判断桌面端文件是否存在。
    // 返回非 null 的 `{ url: '' }` 使 chip 的 `resolved !== null` 判真 → 保持 resolved 态（与改动前
    // deep stub 返回 undefined → `undefined !== null` 判真一致），零视觉回归；图片/媒体预览拿到
    // 空 url 走「无数据」分支（与改动前 `if (undefined)` 走 else 一致）。
    resolveFilePath: () => Promise.resolve({ url: '' }),
    // 打开文件（systemOpenFile）：pocket 无本地文件系统，无法让桌面程序真打开 → 语义冻结为
    // 「应用内只读预览」。派发既有 'profer:file-preview' 事件（与 file-path-chip.tsx:200 同一模式），
    // 由 pocket/main.tsx 挂载的 FilePreviewContainer → FilePreviewDialog → WS read_file_as_data_url
    // （服务端命令已存在）完成预览。旧行为 safeNoop 会让所有 pocket 可达调用点静默无反应：
    // file-path-chip:184 / message:601,605,608 / reasoning:233 / DefaultAppOpenButton:33 /
    // TeamWorkspaceView:1850,1857。无路径时保持静默（不抛错），兼容既有 `.catch` 调用方。
    systemOpenFile: async (filePath: string) => {
      if (typeof filePath === 'string' && filePath.length > 0) {
        window.dispatchEvent(new CustomEvent('profer:file-preview', {
          detail: { path: filePath, name: getFileBaseName(filePath) },
        }))
      }
    },
    saveFilesToAgentSession: () => unsupported('保存文件到会话'),
    addAgentKnowledgeReferences: () => unsupported('知识库引用'),
    removeAgentKnowledgeReference: () => unsupported('知识库引用'),
    migrateChatToAgent: (conversationId: string, agentSessionId: string) => {
      if (!remoteClient) return Promise.reject(new Error('移动端连接未就绪'))
      return remoteClient.migrateChatToAgent(conversationId, agentSessionId)
    },
    // 提示词编辑（PromptEditorSidebar/SystemPromptSelector 的 CRUD）：平板不暴露设置入口，
    // 必须明确拒绝，避免 Proxy 兜底 undefined 污染 promptConfigAtom / selectedPromptIdAtom。
    createSystemPrompt: () => unsupported('提示词编辑'),
    deleteSystemPrompt: () => unsupported('提示词编辑'),
    updateSystemPrompt: () => unsupported('提示词编辑'),
    setDefaultPrompt: () => unsupported('提示词编辑'),
    updateAppendSetting: () => unsupported('提示词编辑'),
    killProcess: () => unsupported('进程管理'),
  }

  // ===== 未显式 stub 的能力：返回真 undefined，而不是「永远成功」的可调用对象 =====
  //
  // 历史行为：未命中的 key → 可调用 Proxy（恒 resolve(undefined)、可无限嵌套）。
  // 它把能力缺口全变成静默失败：
  //   ① `if (window.electronAPI?.onXxx)` 判真 → 「假注册」（监听永不触发、零报错零日志）；
  //   ② 存在性检测 + 降级逻辑被骗过（如 showDesktopNotification → Web Notification 兜底失效）；
  //   ③ 缺口没有任何可观测信号，只能靠人工 grep 发现。
  // 现在：未显式 stub 的 key 一律返回 undefined（存在性检测看到真相，调用方据此降级或隐藏入口），
  // 开发构建下按首次访问聚合告警并列出缺失方法名。
  const reportMissingKey = (key: string): undefined => {
    if (!missingElectronApiKeys.has(key)) {
      missingElectronApiKeys.add(key)
      if (isDevBuild()) {
        console.warn(
          `[Pocket] electronAPI.${key} 未在 pocket stub 中显式实现，已按 undefined 返回。` +
            `累计缺失 ${missingElectronApiKeys.size} 项：${[...missingElectronApiKeys].join(', ')}`,
        )
      }
    }
    return undefined
  }

  // 顶层：显式 stub 的成员照常返回，其余返回 undefined（不再伪造可调用对象）
  const top = new Proxy(stub, {
    get(t, p) {
      if (typeof p !== 'string') return undefined
      if (Object.prototype.hasOwnProperty.call(t, p)) return Reflect.get(t, p)
      return reportMissingKey(p)
    },
  }) as unknown as Record<string, unknown>

  ;(globalThis as unknown as { electronAPI?: Record<string, unknown> }).electronAPI = top
}

/** 检查当前是否在 Electron/有真实 electronAPI（供平板逻辑判断） */
export function hasRealElectronApi(): boolean {
  return Boolean((globalThis as unknown as { electronAPI?: unknown }).electronAPI)
}
