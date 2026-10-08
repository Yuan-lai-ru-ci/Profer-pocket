/**
 * Pocket 侧交互请求判定桥（R8-P0）
 *
 * 判定规则见 `@/lib/interaction-guard`：用主端 `get_pending_interactions` 快照确认
 * 某个 requestId 是否仍然待处理；失败/旧服务端不支持一律降级为 `unknown`（保留原有行为）。
 *
 * 唯一的消费方是 electronapi-stub 暴露的 `getPendingInteractionVerdict`，
 * 由 AgentView 注入给三个横幅作为「关闭/提交前守卫」。
 */

import { debugLog } from '@/lib/debug-hud'
import {
  fetchInteractionVerdict,
  type InteractionKind,
  type InteractionVerdict,
  type PendingInteractionSnapshotSource,
} from '@/lib/interaction-guard'

/** 判定入参（sessionId 可选：带上是让主端做服务端过滤，省一次全量快照） */
export interface InteractionVerdictQuery {
  kind: InteractionKind
  requestId: string
  sessionId?: string
}

/**
 * 用主端快照判定交互请求是否仍待处理。
 * client 为空（连接未建立）时返回 `unknown`，绝不 reject —— 调用方按「保留原有行为」走。
 */
export async function resolvePocketInteractionVerdict(
  client: PendingInteractionSnapshotSource | null,
  query: InteractionVerdictQuery,
): Promise<InteractionVerdict> {
  const verdict = await fetchInteractionVerdict(client, query.kind, query.requestId, query.sessionId)
  if (verdict === 'resolved') {
    // 过期横幅的收敛过程需要可观测（真机验收靠这条日志确认「另一端的操作已被本端识别」）
    debugLog(`[Pocket] 交互请求已过期 kind=${query.kind} request=${query.requestId}`)
  }
  return verdict
}

export type { InteractionKind, InteractionVerdict }
