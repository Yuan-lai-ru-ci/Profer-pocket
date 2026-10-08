/**
 * Pocket pending interaction snapshot 对账。
 *
 * resolved 事件与 get_pending_interactions 快照可能乱序到达。短期水印只用于
 * 抵抗这段竞态，不能替代服务端状态；requestId 每次新建请求都应保持唯一。
 */

export type PendingInteractionKind = 'permission' | 'askUser' | 'exitPlan'

export interface PendingInteractionIdentity {
  kind: PendingInteractionKind
  sessionId: string
  requestId: string
}

export type ResolvedInteractionWatermarks = ReadonlyMap<string, number>

export interface PendingInteractionRecord {
  sessionId?: unknown
  requestId?: unknown
}

export const RESOLVED_INTERACTION_WATERMARK_TTL_MS = 2 * 60 * 1000
export const MAX_RESOLVED_INTERACTION_WATERMARKS = 512

function watermarkKey(identity: PendingInteractionIdentity): string {
  return `${identity.kind}\u0000${identity.sessionId}\u0000${identity.requestId}`
}

function isValidIdentity(identity: PendingInteractionIdentity): boolean {
  return identity.sessionId.length > 0 && identity.requestId.length > 0
}

/** 删除过期水印，并限制容量，避免长期运行的 Pocket 无限增长。 */
export function pruneResolvedInteractionWatermarks(
  watermarks: ResolvedInteractionWatermarks,
  now: number,
  ttlMs = RESOLVED_INTERACTION_WATERMARK_TTL_MS,
  maxEntries = MAX_RESOLVED_INTERACTION_WATERMARKS,
): Map<string, number> {
  const active = [...watermarks.entries()]
    .filter(([, resolvedAt]) => Number.isFinite(resolvedAt) && resolvedAt + ttlMs > now)
    .sort(([, left], [, right]) => left - right)
  const retained = active.slice(-Math.max(0, maxEntries))
  return new Map(retained)
}

/** 记录一个 resolved request；同一 requestId 重复记录保持幂等。 */
export function markResolvedInteraction(
  watermarks: ResolvedInteractionWatermarks,
  identity: PendingInteractionIdentity,
  now: number,
  ttlMs = RESOLVED_INTERACTION_WATERMARK_TTL_MS,
  maxEntries = MAX_RESOLVED_INTERACTION_WATERMARKS,
): Map<string, number> {
  if (!isValidIdentity(identity) || !Number.isFinite(now)) return new Map(watermarks)
  const key = watermarkKey(identity)
  const next = new Map(watermarks)
  const previous = next.get(key)
  // 重复 resolved 不延长语义之外的删除范围；更新到较新的观察时间即可。
  next.set(key, previous === undefined ? now : Math.max(previous, now))
  return pruneResolvedInteractionWatermarks(next, now, ttlMs, maxEntries)
}

export function isResolvedInteractionWatermarked(
  watermarks: ResolvedInteractionWatermarks,
  identity: PendingInteractionIdentity,
  now: number,
  ttlMs = RESOLVED_INTERACTION_WATERMARK_TTL_MS,
): boolean {
  if (!isValidIdentity(identity) || !Number.isFinite(now)) return false
  const resolvedAt = watermarks.get(watermarkKey(identity))
  return resolvedAt !== undefined && resolvedAt + ttlMs > now
}

/** 过滤快照中的 resolved 项，同时按 requestId 去重。 */
export function filterPendingInteractionSnapshot<T extends PendingInteractionRecord>(
  items: readonly T[] | undefined,
  kind: PendingInteractionKind,
  watermarks: ResolvedInteractionWatermarks,
  now: number,
  ttlMs = RESOLVED_INTERACTION_WATERMARK_TTL_MS,
): T[] {
  const result: T[] = []
  const requestIds = new Set<string>()
  for (const item of items ?? []) {
    if (typeof item.sessionId !== 'string' || item.sessionId.length === 0) continue
    if (typeof item.requestId !== 'string' || item.requestId.length === 0) continue
    const identity = { kind, sessionId: item.sessionId, requestId: item.requestId }
    if (isResolvedInteractionWatermarked(watermarks, identity, now, ttlMs)) continue
    const requestKey = `${item.sessionId}\u0000${item.requestId}`
    if (requestIds.has(requestKey)) continue
    requestIds.add(requestKey)
    result.push(item)
  }
  return result
}

const pocketResolvedInteractionWatermarks = new Map<string, number>()

/** Pocket WS 事件入口使用的短期水印。 */
export function markPocketResolvedInteraction(identity: PendingInteractionIdentity, now = Date.now()): void {
  const next = markResolvedInteraction(pocketResolvedInteractionWatermarks, identity, now)
  pocketResolvedInteractionWatermarks.clear()
  for (const [key, resolvedAt] of next) pocketResolvedInteractionWatermarks.set(key, resolvedAt)
}

export function isPocketResolvedInteractionWatermarked(
  identity: PendingInteractionIdentity,
  now = Date.now(),
): boolean {
  const next = pruneResolvedInteractionWatermarks(pocketResolvedInteractionWatermarks, now)
  pocketResolvedInteractionWatermarks.clear()
  for (const [key, resolvedAt] of next) pocketResolvedInteractionWatermarks.set(key, resolvedAt)
  return isResolvedInteractionWatermarked(next, identity, now)
}

export function filterPocketPendingInteractionSnapshot<T extends PendingInteractionRecord>(
  items: readonly T[] | undefined,
  kind: PendingInteractionKind,
  now = Date.now(),
): T[] {
  const next = pruneResolvedInteractionWatermarks(pocketResolvedInteractionWatermarks, now)
  pocketResolvedInteractionWatermarks.clear()
  for (const [key, resolvedAt] of next) pocketResolvedInteractionWatermarks.set(key, resolvedAt)
  return filterPendingInteractionSnapshot(items, kind, next, now)
}

/** 仅供测试隔离模块级水印。 */
export function clearPocketResolvedInteractionWatermarks(): void {
  pocketResolvedInteractionWatermarks.clear()
}
