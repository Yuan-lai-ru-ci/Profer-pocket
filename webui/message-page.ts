/**
 * 消息分页纯函数层。
 *
 * 抽出来的理由：解析与合并是「错了就静默丢历史」的地方，必须有单测钉住；
 * 而 main.tsx 一 import 就会挂载 React 根节点，不适合放进测试进程。
 */
import type { SDKMessage } from '@profer/shared'

/** 首屏与续页的条数。首屏只取尾部：长会话整份拉回来会拖慢首屏，也让 DOM 无谓膨胀。 */
export const INITIAL_PAGE = 60
export const OLDER_PAGE = 40

/** reload 时额外多取的余量：跑完一轮新消息落进来时，尾部窗口不会立刻往前滑动。 */
export const TAIL_HEADROOM = 20

export interface MessagePage {
  messages: SDKMessage[]
  /** 本页首条在完整消息数组里的下标；续页用它当 before。 */
  startIndex: number
  hasMore: boolean
}

/**
 * 解析 get_sdk_messages 的返回。
 *
 * 三种形态都要认：分页结构（`{messages,total,startIndex,hasMore}`）、
 * 裸数组（旧服务端 / 未传分页参数时的全文），以及外层 `data` 包裹。
 * 任何形态解析失败都返回空页而不是抛错 —— 会话读不出来比页面白屏好排查。
 */
export function decodeMessagePage(raw: unknown): MessagePage {
  const value = raw as { messages?: unknown; startIndex?: unknown; hasMore?: unknown; data?: unknown } | null
  if (Array.isArray(raw)) return { messages: raw as SDKMessage[], startIndex: 0, hasMore: false }
  if (Array.isArray(value?.messages)) {
    return {
      messages: value.messages as SDKMessage[],
      startIndex: typeof value.startIndex === 'number' ? value.startIndex : 0,
      hasMore: value.hasMore === true,
    }
  }
  const data = value?.data as { messages?: unknown; startIndex?: unknown; hasMore?: unknown } | unknown[] | null
  if (Array.isArray(data)) return { messages: data as SDKMessage[], startIndex: 0, hasMore: false }
  if (Array.isArray(data?.messages)) {
    return {
      messages: data.messages as SDKMessage[],
      startIndex: typeof data.startIndex === 'number' ? data.startIndex : 0,
      hasMore: data.hasMore === true,
    }
  }
  return { messages: [], startIndex: 0, hasMore: false }
}

function messageUuid(message: SDKMessage): string {
  const uuid = (message as { uuid?: unknown }).uuid
  return typeof uuid === 'string' ? uuid : ''
}

/**
 * 前置拼接更早的一页。
 *
 * 服务端会把页起点向前扩展到 turn 的 user-input 边界（保证不切断一轮对话），
 * 相邻两页因此可能重叠；按 uuid 去重，避免同一轮被渲染两遍。
 */
export function mergePrepend(older: SDKMessage[], current: SDKMessage[]): SDKMessage[] {
  const seen = new Set(current.map(messageUuid).filter(Boolean))
  const fresh = older.filter((message) => {
    const uuid = messageUuid(message)
    return !uuid || !seen.has(uuid)
  })
  return [...fresh, ...current]
}

/** 尾部窗口按当前持有量决定，至少 INITIAL_PAGE。 */
export function tailPageSize(held: number): number {
  return Math.max(INITIAL_PAGE, held + TAIL_HEADROOM)
}

/**
 * 消息身份：优先 `_provenance`（runId+messageId），uuid 只作兜底。
 *
 * **不能用 uuid 当主键**：流式 partial 帧的 uuid 不可靠 —— coordinator 明言
 * 「部分帧可能没有 UUID，终稿才有」，且为同一条回复按 `message.id` 归一身份
 * （`pendingAssistantId` / `logicalMessageIds` 机制）。partial 与 final 稳定共享的
 * 只有 `_provenance.messageId`。只按 uuid 合并会把每条 partial 增量全部堆进列表
 * （残条）：text 残条之后挂着 tool_use 残条，`findHeadlineRange` 判「正文之后还有
 * 工具」直接收空 headline —— 表现就是「正文一输出完就消失，刷新后又出现」
 * （刷新拉的是按 messageId 合并后的干净 JSONL）。
 */
export function messageIdentity(message: SDKMessage): string | undefined {
  const record = message as { uuid?: unknown; _provenance?: { runId?: unknown; messageId?: unknown } }
  const provenance = record._provenance
  if (provenance && typeof provenance.runId === 'string' && typeof provenance.messageId === 'string') {
    return `p:${provenance.runId}:${provenance.messageId}`
  }
  if (typeof record.uuid === 'string' && record.uuid) return `u:${record.uuid}`
  return undefined
}

/**
 * 流式期间同一回合会落多行快照；按身份覆盖而不是追加，避免重复气泡与残条。
 *
 * 方向保护：`partial` 不覆盖已有的 `final`。正常时序是 partial 先、final 后
 * （终稿覆盖增量）；但回前台重拉（wake reload / requiresSnapshot reload）会先把
 * JSONL 里的 final 放进列表，而这一轮的 partial 帧可能还在飞 —— 不拦这一步会让
 * 已完整的正文被更旧的增量顶掉、内容倒退，直到下一条终稿才恢复。
 */
export function upsertMessage(list: SDKMessage[], incoming: SDKMessage): SDKMessage[] {
  const identity = messageIdentity(incoming)
  if (identity) {
    const index = list.findIndex((item) => messageIdentity(item) === identity)
    const existing = index >= 0 ? list[index] : undefined
    if (existing) {
      const incomingPartial = (incoming as { _partial?: unknown })._partial === true
      const existingPartial = (existing as { _partial?: unknown })._partial === true
      if (incomingPartial && !existingPartial) return list
      const next = list.slice()
      next[index] = incoming
      return next
    }
  }
  return [...list, incoming]
}

/**
 * 重拉页面与当前持有的**合并**（按身份 upsert，保持原顺序）。
 *
 * 与整体替换（setMessages(page)）的区别：**流式中的 partial 不落盘**，替换会把
 * 正在输出的正文冲掉。回前台（wake / 桌面切窗口）与 requiresSnapshot 重连
 * （游标落事件窗口外、漏帧不补发）都发生在流式中 —— 替换后漏掉的 final 不会
 * 补发，正文消失且不恢复。合并的语义：
 * - 页面里已有身份的消息：按 upsert 覆盖（final 覆盖 partial；partial 不覆盖 final）；
 * - 页面里没有、当前持有的（在飞的 partial / 前置加载的更早消息）：原样保留；
 * - 页面里新的消息：按页面顺序追加到尾部。
 */
export function mergeReloadPage(current: readonly SDKMessage[], page: readonly SDKMessage[]): SDKMessage[] {
  const isPartial = (message: SDKMessage): boolean => (message as { _partial?: unknown })._partial === true
  /** compacting 是 run 中瞬态（WS 广播有、JSONL 不落盘）：不在页面里的直接丢，
   *  否则合并只加不减，「正在压缩上下文...」会永久残留（实测两次压缩留两行）。
   *  compact_boundary（上下文已压缩，落盘）不受影响，作为历史分隔保留。 */
  const isTransientCompacting = (message: SDKMessage): boolean =>
    (message as { type?: unknown; subtype?: unknown }).type === 'system'
    && (message as { subtype?: unknown }).subtype === 'compacting'
  const createdAtOf = (message: SDKMessage): number | undefined => {
    const value = (message as { _createdAt?: unknown })._createdAt
    return typeof value === 'number' && Number.isFinite(value) ? value : undefined
  }
  // 1) 以页面（JSONL 权威顺序）为基准序列
  const merged: SDKMessage[] = []
  const identityIndex = new Map<string, number>()
  for (const message of page) {
    const identity = messageIdentity(message)
    const existing = identity !== undefined ? identityIndex.get(identity) : undefined
    if (existing !== undefined) {
      if (!(isPartial(message) && !isPartial(merged[existing] as SDKMessage))) {
        merged[existing] = message
      }
      continue
    }
    if (identity !== undefined) identityIndex.set(identity, merged.length)
    merged.push(message)
  }
  // 2) 当前持有的消息：同身份按方向保护回填（final 不被 stale partial 顶退），
  //    不同身份按 createdAt 归位 —— 更早的进头部、在飞的留尾部。
  //    此前无脑追加尾部，页面里「当前没持有的更早消息」会被顶到末尾 → 顺序错乱、
  //    最新消息跳到上面去（「模型回复完消息跳到上面去」的根因）。
  const pageFirst = page.map(createdAtOf).find((value) => value !== undefined)
  const head: SDKMessage[] = []
  const tail: SDKMessage[] = []
  for (const message of current) {
    const identity = messageIdentity(message)
    const index = identity !== undefined ? identityIndex.get(identity) : undefined
    if (index !== undefined) {
      const existing = merged[index] as SDKMessage
      if (!(isPartial(message) && !isPartial(existing))) merged[index] = message
      continue
    }
    if (isTransientCompacting(message)) continue
    const createdAt = createdAtOf(message)
    if (createdAt !== undefined && pageFirst !== undefined && createdAt <= pageFirst) head.push(message)
    else tail.push(message)
  }
  for (const message of tail) {
    if (isTransientCompacting(message)) continue
    const identity = messageIdentity(message)
    const index = identity !== undefined ? identityIndex.get(identity) : undefined
    if (index !== undefined) {
      const existing = merged[index] as SDKMessage
      if (!(isPartial(message) && !isPartial(existing))) merged[index] = message
      continue
    }
    if (identity !== undefined) identityIndex.set(identity, merged.length)
    merged.push(message)
  }
  return [...head, ...merged]
}
