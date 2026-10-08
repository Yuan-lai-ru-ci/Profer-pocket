/**
 * Pocket 强制刷新（R10）— 纯逻辑层
 *
 * 背景：0.1.14 顶栏「刷新」只做「递增 agentMessageRefreshAtom + loadSessions + toast」，
 * 对用户而言体感没有任何变化。原因是它只重拉了一次**增量**尾部，而下面这些状态全部留在原样：
 *
 * 1. 传输层分页缓存（electronapi-stub `sdkMessagesPageCache`）：分页窗口是「已累计」数组，
 *    带缺口/滞后时会与新窗口做前缀合并，刷新后仍是旧序列 ⇒ 必须失效后走全量拉取；
 *    （注意：渲染层 atom `agentSDKMessagesCacheAtom` 刻意**不**清 —— 全量拉取失败时
 *    界面继续用旧数据展示，不会白屏，满足「失败不得把界面清空」）
 * 2. 消息子树的本地视图态：执行过程分组的展开/收起、`visibleGroupStart` 分页切片、
 *    ready 淡入、滚动位置记忆 —— 这些都不在 atom 里，只有重挂载才能归零；
 * 3. 主端权威运行态/上下文窗口/待处理交互：分别由 list_sessions（active）、
 *    get_agent_runtime_contexts、get_pending_interactions 重建。
 *
 * 本模块只放可单测的纯逻辑：nonce 记法、重挂载键、一次性消费判定、弱网失败归因与文案。
 * 真正产生副作用的部分（atom 写入、WS 调用、DOM 失效）留在调用方（pocket/main.tsx、
 * AgentView、electronapi-stub、useScrollPositionMemory），保持可审阅。
 */

/** 强制刷新作用的对象类型：Agent 会话 / Chat 对话 */
export type PocketReloadMode = 'agent' | 'chat'

/** 强制刷新成功后应当被重置/失效的本地状态项（按持有者分组，便于审阅与单测断言） */
export type PocketReloadInvalidation =
  /** electronapi-stub：该会话的传输层分页窗口缓存（不失效会把旧窗口合并回来） */
  | 'sdk-messages-page-cache'
  /** useScrollPositionMemory：该会话的滚动位置记忆（刷新后要求回到底部看最新输出） */
  | 'scroll-position'
  /** 消息子树重挂载：执行过程折叠态 / visibleGroupStart 分页切片 / ready 淡入 */
  | 'message-subtree'

/**
 * 强制刷新的既定步骤（顺序即语义）：
 * ① 失效本地视图态与传输层分页缓存；
 * ② 用主端权威快照重建会话列表（active/running）、权威上下文窗口与待处理交互；
 * ③ 递增 reload nonce + 消息刷新版本 → AgentView 全量水合、消息子树按新 key 重挂载。
 */
export type PocketReloadStep =
  | 'invalidate-local-view-state'
  | 'resync-authoritative-state'
  | 'trigger-full-rehydrate'

export function collectPocketReloadInvalidations(mode: PocketReloadMode): PocketReloadInvalidation[] {
  // Chat 没有传输层分页缓存、也没有「执行过程」分组，消息列表由 refreshVersion 全量重拉，
  // 且刻意不重挂载 ChatView（重挂载会丢掉待发送附件与草稿）。
  if (mode === 'chat') return ['scroll-position']
  return ['sdk-messages-page-cache', 'scroll-position', 'message-subtree']
}

export function collectPocketReloadSteps(): PocketReloadStep[] {
  return ['invalidate-local-view-state', 'resync-authoritative-state', 'trigger-full-rehydrate']
}

export const POCKET_RELOAD_TOAST = {
  agent: '已重新加载当前会话',
  chat: '已重新加载当前对话',
} as const

// ===== nonce 记法（纯 Map 操作，供 jotai atom 写入复用） =====

/** 归一化：非有限数 / 负数 / undefined 一律视为 0（atom 可能被旧结构或测试写入脏值）。 */
export function normalizeReloadNonce(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0
}

export function getReloadNonce(
  current: ReadonlyMap<string, number> | undefined | null,
  key: string,
): number {
  return normalizeReloadNonce(current?.get(key))
}

/** 递增某个会话/对话的重载版本号；返回新 Map（不修改入参，便于 jotai atom 写入与单测）。 */
export function bumpReloadNonce(
  current: ReadonlyMap<string, number> | undefined | null,
  key: string,
): Map<string, number> {
  const next = new Map(current ?? [])
  next.set(key, getReloadNonce(current, key) + 1)
  return next
}

/**
 * 消息子树的 React key：nonce 变化即重挂载，一次性归零该子树内所有本地视图态。
 * 桌面端 nonce 恒为 0，键与旧实现（仅 sessionId）等价，行为不变。
 */
export function buildReloadKey(mode: PocketReloadMode, id: string, nonce: unknown): string {
  return `${mode}:${id}#${normalizeReloadNonce(nonce)}`
}

// ===== 一次性消费：只有「用户刚点的那次」走全量水合 =====

export interface PocketReloadConsumption {
  /** 本次加载是否应走全量水合（等价于重新进入会话），而不是首帧分页窗口 */
  shouldFullHydrate: boolean
  /** 消费后的游标（未触发时为原值） */
  consumedNonce: number
}

/**
 * nonce > 已消费游标 ⇒ 本次加载是用户刚触发的强制刷新，走全量水合；否则仍走首帧分页窗口。
 * 返回的 `consumedNonce` 由调用方在**全量拉取成功之后**写回游标（失败不推进，下次仍可全量重试），
 * 这样开发模式 StrictMode 双挂载 / 权威态刷新导致的重复调用也会各自拿到一次全量，而不会
 * 把「用户刚点的那次」降级成增量。游标按 sessionId 各自持有，跨会话不会互相污染。
 */
export function consumePocketReloadNonce(
  currentNonce: unknown,
  consumedNonce: unknown,
): PocketReloadConsumption {
  const current = normalizeReloadNonce(currentNonce)
  const consumed = normalizeReloadNonce(consumedNonce)
  if (current > consumed) return { shouldFullHydrate: true, consumedNonce: current }
  return { shouldFullHydrate: false, consumedNonce: consumed }
}

// ===== 弱网容错：失败归因与用户可见文案 =====

export type PocketReloadFailureKind = 'disconnected' | 'timeout' | 'unknown'

/**
 * 归因刷新失败。优先级：连接不可用 > 超时 > 其它。
 * WsClient.sendCommand 的两种典型 reject：未连接时 `连接未就绪，请稍候重试`，
 * 15s 无回包时 `指令超时`；两者都需要给用户明确反馈（而不是静默无变化）。
 */
export function classifyPocketReloadFailure(
  error: unknown,
  options: { connected?: boolean } = {},
): PocketReloadFailureKind {
  if (options.connected === false) return 'disconnected'
  const message = error instanceof Error
    ? error.message
    : typeof error === 'string'
      ? error
      : ''
  if (/超时|timeout/i.test(message)) return 'timeout'
  if (/未就绪|未连接|not\s*open|websocket|连接已?断开|disconnected/i.test(message)) return 'disconnected'
  return 'unknown'
}

/** 失败时的 toast / inline 文案：统一强调「界面未被清空，可重试」，避免用户以为数据丢了。 */
export function describePocketReloadFailure(kind: PocketReloadFailureKind, error?: unknown): string {
  if (kind === 'disconnected') {
    return '未连接到电脑端，已取消重新加载；当前内容保持原样，恢复连接后可重试'
  }
  if (kind === 'timeout') {
    return '重新加载超时（网络较慢）；当前内容保持原样，可稍后重试'
  }
  const detail = error instanceof Error && error.message ? `：${error.message}` : ''
  return `重新加载失败${detail}；当前内容保持原样，可稍后重试`
}
