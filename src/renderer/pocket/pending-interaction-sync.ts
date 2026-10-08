/**
 * Pocket pending 交互快照重建（R8-P0）
 *
 * 背景：`ask_user/permission/exit_plan_mode` 请求不是持久化消息，断线期间的 `*_request`
 * 事件会丢；同时桌面端作答**不广播**（桌面 ipc.ts 只发本窗口 IPC，见 R8 分析 ②.1），
 * 因此移动端只能靠主端权威快照（`get_pending_interactions`）收敛：
 * - 补齐重建时机（回前台 / 重连 / 切会话 / 有横幅时的低频轮询）→ 另一端的解答或关闭
 *   能在 ≤5s 内让本端横幅消失，或让丢失的请求重新出现。
 *
 * 本模块把 main.tsx 里原先内联的重建逻辑抽成可复用、可单测的纯函数层，语义与原实现一致：
 * 过滤 resolved 水印 → 按 sessionId 分组 → 只保留 `allowedSessionIds` 内的会话。
 * 唯一加固：载荷完全不像快照（无任何数组字段）时视为失败，**不改动**现有 atoms，
 * 避免把合法横幅整体清空（`{}` / 非对象响应）。
 */

import type { AskUserRequest, ExitPlanModeRequest, PermissionRequest } from '@profer/shared'
import {
  filterPocketPendingInteractionSnapshot,
  type PendingInteractionKind,
} from './pending-interaction-reconciliation'

/** 快照条目的最小形状（过滤与分组只需要这两个字段） */
export interface PendingInteractionRecord {
  sessionId?: unknown
  requestId?: unknown
}

/** 主端 `get_pending_interactions` 的返回形状 */
export interface PendingInteractionSnapshotPayload {
  permissions?: unknown[]
  askUsers?: unknown[]
  exitPlans?: unknown[]
}

/** 重建结果：三类请求按 sessionId 分组 */
export interface AppliedPendingInteractionSnapshot {
  permission: Map<string, PermissionRequest[]>
  askUser: Map<string, AskUserRequest[]>
  exitPlan: Map<string, ExitPlanModeRequest[]>
}

export type PendingInteractionSyncResult = 'applied' | 'failed'

export interface PendingInteractionSyncDeps {
  /** 拉取主端权威快照（通常为 client.getPendingInteractions(...)） */
  fetchSnapshot: () => Promise<unknown>
  /** 把重建结果写入三个 pending atom（由调用方持有 store，避免本模块依赖 jotai store） */
  commit: (applied: AppliedPendingInteractionSnapshot) => void
  /** 允许的会话集合；null/缺省 = 不过滤 */
  allowedSessionIds?: ReadonlySet<string> | null
  now?: number
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 已提醒过的「有 pending 但会话不在白名单」会话（仅诊断，去重且限容；R-2）。 */
const warnedDroppedSessions = new Set<string>()
const MAX_WARNED_DROPPED_SESSIONS = 64

function warnDroppedSession(sessionId: string): void {
  if (warnedDroppedSessions.has(sessionId)) return
  if (warnedDroppedSessions.size >= MAX_WARNED_DROPPED_SESSIONS) warnedDroppedSessions.clear()
  warnedDroppedSessions.add(sessionId)
  // 不改变过滤语义（团队工作区在 Pocket 刻意隐藏），仅留一条可定位的告警：
  // 该症状是“有 run 没横幅”，与 R8 的“横幅残留”方向相反但同源。
  console.warn('[Pocket] 主端存在待处理交互，但其会话不在个人会话白名单，已被隐藏:', sessionId)
}

/**
 * 载荷是否「像」一份 pending 快照：至少有一个数组字段。
 * 用于挡住 `{}` / 非对象响应（旧服务端、代理错误、命令回包形状变化），
 * 这类响应按失败处理，保留现有 atoms，不让合法横幅被整体清空。
 */
export function isPendingInteractionSnapshotPayload(
  value: unknown,
): value is PendingInteractionSnapshotPayload {
  if (!isRecord(value)) return false
  return Array.isArray(value.permissions) || Array.isArray(value.askUsers) || Array.isArray(value.exitPlans)
}

/**
 * 分组：先按 resolved 水印与 requestId 去重过滤，再按 sessionId 归并，
 * 并丢弃不在 `allowedSessionIds` 内的会话（团队工作区在 Pocket 是刻意隐藏的）。
 */
export function groupPendingInteractionsBySession<T extends PendingInteractionRecord>(
  items: readonly T[] | undefined,
  kind: PendingInteractionKind,
  allowedSessionIds: ReadonlySet<string> | null,
  now?: number,
): Map<string, T[]> {
  const grouped = new Map<string, T[]>()
  for (const item of filterPocketPendingInteractionSnapshot(items, kind, now)) {
    const sessionId = item.sessionId
    if (typeof sessionId !== 'string' || sessionId.length === 0) continue
    if (allowedSessionIds && !allowedSessionIds.has(sessionId)) {
      warnDroppedSession(sessionId)
      continue
    }
    const current = grouped.get(sessionId) ?? []
    grouped.set(sessionId, [...current, item])
  }
  return grouped
}

/** 纯映射：快照 → 三类分组结果（不触碰 atom，便于单测） */
export function applyPendingInteractionSnapshot(
  snapshot: PendingInteractionSnapshotPayload | undefined | null,
  options: { allowedSessionIds?: ReadonlySet<string> | null; now?: number } = {},
): AppliedPendingInteractionSnapshot {
  const allowedSessionIds = options.allowedSessionIds ?? null
  const now = options.now
  return {
    permission: groupPendingInteractionsBySession<PermissionRequest>(
      snapshot?.permissions as PermissionRequest[] | undefined,
      'permission',
      allowedSessionIds,
      now,
    ),
    askUser: groupPendingInteractionsBySession<AskUserRequest>(
      snapshot?.askUsers as AskUserRequest[] | undefined,
      'askUser',
      allowedSessionIds,
      now,
    ),
    exitPlan: groupPendingInteractionsBySession<ExitPlanModeRequest>(
      snapshot?.exitPlans as ExitPlanModeRequest[] | undefined,
      'exitPlan',
      allowedSessionIds,
      now,
    ),
  }
}

/**
 * 拉快照并重建三个 pending atom。
 * 返回 `'failed'` 时**未调用 commit**，现有 atoms 保持不变（与 main.tsx 原有 try/catch 语义一致）。
 */
export async function syncPendingInteractions(
  deps: PendingInteractionSyncDeps,
): Promise<PendingInteractionSyncResult> {
  try {
    const snapshot = await deps.fetchSnapshot()
    if (!isPendingInteractionSnapshotPayload(snapshot)) {
      console.warn('[Pocket] 同步待处理交互失败：返回载荷不是 pending 快照，保留现有状态')
      return 'failed'
    }
    deps.commit(applyPendingInteractionSnapshot(snapshot, {
      allowedSessionIds: deps.allowedSessionIds,
      now: deps.now,
    }))
    return 'applied'
  } catch (error) {
    console.warn('[Pocket] 同步待处理交互失败，保留现有状态:', error)
    return 'failed'
  }
}
