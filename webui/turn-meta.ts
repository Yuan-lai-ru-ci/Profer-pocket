/**
 * 回合元信息：发生时间、来源客户端、token 消耗。
 *
 * 三样东西都来自消息本身，不额外请求服务端：
 * - 时间：`_createdAt`（毫秒），user 消息与 assistant 分组都有；
 * - 来源：user 消息的 `_clientId`（远程入口写入）或 `_provenance.author`；
 * - token：assistant 消息的 `message.usage`，按消息 id 去重后累加为一轮的量。
 *
 * 纯派生、不持有状态，方便单测。
 */
import type { MessageGroup } from '@profer/session-core'

export interface TurnUsage {
  /** 非缓存输入 */
  input: number
  output: number
  cacheRead: number
  cacheCreate: number
}

export type TurnSource = 'desktop' | 'web' | 'remote' | 'automation' | 'agent' | 'system'

export const SOURCE_LABELS: Record<TurnSource, string> = {
  desktop: '软件',
  web: '网页',
  remote: '远程',
  automation: '定时',
  agent: '代理',
  system: '系统',
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function numberOrZero(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0
}

/**
 * 消息的去重键。
 *
 * 流式期间同一条 assistant 消息会落多份快照（共享 `message.id`，没有 id 时退化到 uuid），
 * 每份都带同一份 usage；不去重会把一轮的 token 重复计成好几倍。
 */
function messageKey(message: unknown, index: number): string {
  if (!isRecord(message)) return `#${index}`
  const inner = isRecord(message.message) ? message.message : undefined
  const id = inner?.id
  if (typeof id === 'string' && id) return `id:${id}`
  if (typeof message.uuid === 'string' && message.uuid) return `uuid:${message.uuid}`
  return `#${index}`
}

/** 一轮的总 token。没有 usage（例如本地模型）时返回 null，界面就不显示。 */
export function sumTurnUsage(group: MessageGroup | undefined): TurnUsage | null {
  if (!group || group.type !== 'assistant-turn') return null
  // 同一条的后续快照是「更完整的版本」（output 只会更多），所以同键**后写覆盖**，
  // 不能取第一份，否则流式期间会少算。
  const latest = new Map<string, unknown>()
  group.assistantMessages.forEach((message, index) => {
    latest.set(messageKey(message, index), message)
  })

  const usage: TurnUsage = { input: 0, output: 0, cacheRead: 0, cacheCreate: 0 }
  let found = false

  for (const message of latest.values()) {
    const raw = (message as unknown as { message?: { usage?: unknown } }).message?.usage
    if (!isRecord(raw)) continue
    const input = numberOrZero(raw.input_tokens)
    const output = numberOrZero(raw.output_tokens)
    const cacheRead = numberOrZero(raw.cache_read_input_tokens)
    const cacheCreate = numberOrZero(raw.cache_creation_input_tokens)
    if (input + output + cacheRead + cacheCreate === 0) continue
    found = true
    usage.input += input
    usage.output += output
    usage.cacheRead += cacheRead
    usage.cacheCreate += cacheCreate
  }

  return found ? usage : null
}

/** 含缓存的输入总量：缓存读/写同样是这一轮真实消耗的上下文。 */
export function totalInput(usage: TurnUsage): number {
  return usage.input + usage.cacheRead + usage.cacheCreate
}

/**
 * 这条消息是谁发的。
 *
 * `_clientId` 由远程入口按白名单写入（webui / remote），桌面端不写 —— 缺省即本机软件。
 * 定时任务与子代理用 `_provenance.author` 兜底，避免把自动化消息标成「软件」。
 */
export function resolveTurnSource(group: MessageGroup | undefined): TurnSource | null {
  if (!group) return null
  const message = group.type === 'user' ? (group.message as unknown) : undefined
  if (!isRecord(message)) return null

  const clientId = message._clientId
  if (clientId === 'webui') return 'web'
  if (typeof clientId === 'string' && clientId) return 'remote'

  const provenance = isRecord(message._provenance) ? message._provenance : undefined
  const author = provenance && isRecord(provenance.author) ? provenance.author : undefined
  const type = author?.type
  if (type === 'automation') return 'automation'
  if (type === 'agent') return 'agent'
  if (type === 'system') return 'system'
  // author.type === 'user' 且没有 clientId：本机软件发送
  if (type === 'user') return 'desktop'
  return null
}

/** 这一轮用的是哪个模型（只有助手回合有）。用于在回合末尾标模型 tag。 */
export function resolveTurnModel(group: MessageGroup | undefined): string | undefined {
  if (!group || group.type !== 'assistant-turn') return undefined
  const model = (group as { model?: unknown }).model
  return typeof model === 'string' && model.trim() ? model.trim() : undefined
}

/** 这一轮的时间戳（毫秒）；拿不到返回 undefined。 */
export function resolveTurnTime(group: MessageGroup | undefined): number | undefined {
  if (!group) return undefined
  if (group.type === 'user') {
    const at = (group.message as unknown as { _createdAt?: unknown })._createdAt
    return typeof at === 'number' && Number.isFinite(at) ? at : undefined
  }
  if (group.type === 'assistant-turn') return group.createdAt
  return undefined
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/**
 * 时钟文案：当天只显示 `HH:mm`，跨天才补日期。
 *
 * 会话常常横跨好几天，一律显示完整日期会把每条消息都撑成一行噪音。
 */
export function formatClock(at: number | undefined, now: number = Date.now()): string {
  if (typeof at !== 'number' || !Number.isFinite(at) || at <= 0) return ''
  const date = new Date(at)
  const today = new Date(now)
  const sameDay = date.getFullYear() === today.getFullYear()
    && date.getMonth() === today.getMonth()
    && date.getDate() === today.getDate()
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  return sameDay ? clock : `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${clock}`
}

/** 千分位以下直出，上万折算成 k：14.5k / 1.2M，避免一长串数字挤占一行。 */
export function formatTokens(count: number): string {
  if (!Number.isFinite(count) || count <= 0) return '0'
  if (count < 1000) return String(Math.round(count))
  if (count < 10000) return `${(count / 1000).toFixed(1).replace(/\.0$/, '')}k`
  if (count < 1000000) return `${Math.round(count / 1000)}k`
  return `${(count / 1000000).toFixed(1).replace(/\.0$/, '')}M`
}

/** 一行摘要：`↑14.5k ↓1.2k`（上行=输入含缓存，下行=输出） */
export function describeUsage(usage: TurnUsage): string {
  return `↑${formatTokens(totalInput(usage))} ↓${formatTokens(usage.output)}`
}

/** 悬停明细：缓存命中单独列出，方便判断是「重复读上下文」还是真的在算。 */
export function describeUsageDetail(usage: TurnUsage): string {
  const parts = [`输入 ${totalInput(usage).toLocaleString()}`, `输出 ${usage.output.toLocaleString()}`]
  if (usage.cacheRead > 0) parts.push(`缓存命中 ${usage.cacheRead.toLocaleString()}`)
  if (usage.cacheCreate > 0) parts.push(`缓存写入 ${usage.cacheCreate.toLocaleString()}`)
  return parts.join(' · ')
}
