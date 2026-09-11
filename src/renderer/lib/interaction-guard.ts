/**
 * interaction-guard — 交互请求「是否已过期」判定（R8-P0）
 *
 * 背景：AskUser / Permission / ExitPlanMode 三个横幅在桌面端语义是「关闭并终止 Agent」，
 * 而移动端可能残留「另一端已作答/已关闭」的过期横幅。此时点 X 会经 stopAgent 中止
 * 另一端**正在运行**的 turn（R8 事故根因），提交也会误导用户以为操作生效。
 *
 * 判定一律依赖主端权威快照（`get_pending_interactions`），三态语义：
 * - `pending`：快照仍含该 requestId → 保留原有行为（X = 停止本轮，与桌面端一致）
 * - `resolved`：快照不含该 requestId → 只做本地移除 + 轻提示，**绝不触碰 run**
 * - `unknown`：未注入守卫（桌面端）/ 拉取失败 / 旧服务端不支持 → **保留原有行为**
 *
 * `unknown → 保留原行为` 是刻意选择：既不因「验证不可用」夺走用户关闭横幅的能力，
 * 也不引入新的数据损失（WS 不通时 stopAgent 同样发不出去，见 ws-client.sendCommand）。
 *
 * 本模块刻意保持纯逻辑 + 一个薄 hook：不 import pocket 专有模块，桌面端与移动端可共用。
 */

import * as React from 'react'

/** 三类交互请求（与 pending-interaction-reconciliation.PendingInteractionKind 同构） */
export type InteractionKind = 'permission' | 'askUser' | 'exitPlan'

/** 判定结果：pending = 仍然待处理；resolved = 已过期；unknown = 无法判定 */
export type InteractionVerdict = 'pending' | 'resolved' | 'unknown'

/** X 关闭路径的动作：停止本轮 or 仅本地移除 */
export type DismissAction = 'stop-agent' | 'local-only'

/** 提交路径的动作：回传主端 or 仅本地移除 */
export type SubmitAction = 'respond' | 'local-only'

/**
 * 横幅侧守卫入口：由移动端注入（内部读主端快照）。
 * 桌面端不注入 → 判定恒为 `unknown` → 行为与改动前完全一致。
 */
export type InteractionGuard = (kind: InteractionKind, requestId: string) => Promise<InteractionVerdict>

/** 主端 `get_pending_interactions` 的返回形状（字段可能缺省：旧服务端/异常载荷） */
export interface PendingInteractionSnapshotPayload {
  permissions?: unknown[]
  askUsers?: unknown[]
  exitPlans?: unknown[]
}

/** 能拉取 pending 快照的最小依赖（WsClient 满足；测试可注入假实现） */
export interface PendingInteractionSnapshotSource {
  getPendingInteractions(sessionId?: string): Promise<unknown>
}

/** 判定轮询间隔：横幅可见期间的低频兜底 */
export const PENDING_INTERACTION_POLL_INTERVAL_MS = 5000

const SNAPSHOT_KEY: Record<InteractionKind, keyof PendingInteractionSnapshotPayload> = {
  permission: 'permissions',
  askUser: 'askUsers',
  exitPlan: 'exitPlans',
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * 从快照中取出某一类请求的数组。
 * 键缺失或不是数组时返回 `null`（= 无法判定），调用方必须保留原有行为。
 */
export function extractPendingSnapshotItems(
  snapshot: unknown,
  kind: InteractionKind,
): unknown[] | null {
  if (!isRecord(snapshot)) return null
  const items = snapshot[SNAPSHOT_KEY[kind]]
  return Array.isArray(items) ? items : null
}

/**
 * 纯判定：requestId 是否仍在主端待处理。
 *
 * 注意：这里**刻意不做** resolved 水印过滤（pending-interaction-reconciliation 的 2 分钟 TTL）。
 * 水印是为了抵抗「resolved 事件先到、快照滞后」的竞态；而本判定的输入就是快照本身，
 * 若叠加水印会把「仍然待处理」的请求误判为已过期，导致横幅被错误移除。
 */
export function resolveInteractionVerdict(
  snapshot: unknown,
  kind: InteractionKind,
  requestId: string,
): InteractionVerdict {
  if (requestId.length === 0) return 'unknown'
  const items = extractPendingSnapshotItems(snapshot, kind)
  if (items === null) return 'unknown'
  for (const item of items) {
    if (isRecord(item) && item.requestId === requestId) return 'pending'
  }
  return 'resolved'
}

/** X 关闭路径决策表 */
export function decideDismissAction(verdict: InteractionVerdict): DismissAction {
  return verdict === 'resolved' ? 'local-only' : 'stop-agent'
}

/** 提交路径决策表 */
export function decideSubmitAction(verdict: InteractionVerdict): SubmitAction {
  return verdict === 'resolved' ? 'local-only' : 'respond'
}

/**
 * 拉快照并判定；任何失败（未连接 / 超时 / 旧服务端不支持 / 载荷异常）一律降级为 `unknown`。
 * 失败只打一条 warn，不打扰用户：降级后行为与改动前一致。
 */
export async function fetchInteractionVerdict(
  source: PendingInteractionSnapshotSource | null,
  kind: InteractionKind,
  requestId: string,
  sessionId?: string,
): Promise<InteractionVerdict> {
  if (!source) return 'unknown'
  try {
    const snapshot = await source.getPendingInteractions(sessionId)
    return resolveInteractionVerdict(snapshot, kind, requestId)
  } catch (error) {
    console.warn('[Pocket] 判定交互请求状态失败，按未过期处理:', error)
    return 'unknown'
  }
}

/** 横幅侧调用入口：守卫缺失、requestId 为空或抛异常都降级为 `unknown`（= 保留原有行为） */
export async function checkInteractionVerdict(
  guard: InteractionGuard | undefined,
  kind: InteractionKind,
  requestId: string,
): Promise<InteractionVerdict> {
  // 空 requestId 无法判定（正常流程不会出现；出现时按 unknown → 保留原行为，最保守）
  if (!guard || requestId.length === 0) return 'unknown'
  try {
    return await guard(kind, requestId)
  } catch (error) {
    console.warn('[Pocket] 交互请求守卫执行失败，按未过期处理:', error)
    return 'unknown'
  }
}

/**
 * X 关闭路径的统一编排：调用方先完成本地移除（保证视觉即时反馈），再调它决定是否停止 run。
 *
 * 返回实际采取的动作，便于调用方与单测断言「resolved 时不停止运行中的会话」（R8 事故根因回归）。
 */
export async function runDismissFlow(options: {
  guard: InteractionGuard | undefined
  kind: InteractionKind
  requestId: string | null
  /** verdict !== 'resolved' 时调用（含 unknown：保留原有行为） */
  requestStop: () => void
  /** verdict === 'resolved' 时调用：只提示，不触碰 run */
  notifyResolved: () => void
}): Promise<DismissAction> {
  const verdict = await checkInteractionVerdict(options.guard, options.kind, options.requestId ?? '')
  const action = decideDismissAction(verdict)
  if (action === 'stop-agent') options.requestStop()
  else options.notifyResolved()
  return action
}

/**
 * 提交路径的统一编排：另一端已处理时不再回传（回传只会拿到 `{ok:false}`），只做本地移除 + 提示。
 *
 * `onStale` / `submit` 都由调用方提供（它们需要访问各自组件的本地状态）。
 */
export async function runSubmitFlow(options: {
  guard: InteractionGuard | undefined
  kind: InteractionKind
  requestId: string
  /** 真正的回传（应包含成功后的本地移除） */
  submit: () => Promise<void>
  /** 判定已过期：本地移除 + 轻提示 */
  onStale: () => void
}): Promise<SubmitAction> {
  const verdict = await checkInteractionVerdict(options.guard, options.kind, options.requestId)
  const action = decideSubmitAction(verdict)
  if (action === 'local-only') {
    options.onStale()
    return action
  }
  await options.submit()
  return action
}

/**
 * 横幅可见期间的低频轮询：判定为 `resolved` 时回调 onResolved（同一 requestId 只触发一次）。
 *
 * 只在「横幅确实渲染着某个 requestId」时启用，无横幅即不轮询，避免空转；
 * 页面不可见时跳过该轮，回前台后的下一轮自然收敛（覆盖 Android 后台冻结场景）。
 * 未注入守卫（桌面端）时整个 effect 不做任何事。
 */
export function useInteractionGuardWatch(options: {
  guard: InteractionGuard | undefined
  kind: InteractionKind
  requestId: string | null
  onResolved: (requestId: string) => void
  enabled?: boolean
  intervalMs?: number
}): void {
  const { guard, kind, requestId, onResolved, enabled = true, intervalMs = PENDING_INTERACTION_POLL_INTERVAL_MS } = options

  // 回调走 ref：避免每次渲染重建 interval
  const onResolvedRef = React.useRef(onResolved)
  onResolvedRef.current = onResolved
  // 已判定过期的 requestId：防止重复回调（也避免卸载前的重复 toast）
  const handledRef = React.useRef<string | null>(null)

  React.useEffect(() => {
    if (!guard || !requestId || !enabled) return
    let disposed = false

    const tick = async (): Promise<void> => {
      if (disposed || handledRef.current === requestId) return
      if (typeof document !== 'undefined' && document.hidden) return
      const verdict = await checkInteractionVerdict(guard, kind, requestId)
      if (disposed || verdict !== 'resolved') return
      handledRef.current = requestId
      onResolvedRef.current(requestId)
    }

    // 立即判定一次：断线重连/回前台后无需等满一个间隔
    void tick()
    const timer = window.setInterval(() => void tick(), intervalMs)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [guard, kind, requestId, enabled, intervalMs])
}
