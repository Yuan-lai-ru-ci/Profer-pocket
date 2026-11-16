/**
 * 协作子会话（委派）可见性的纯派生层。
 *
 * 网页端只回答一个问题：「我发出去的子任务现在是在跑，还是在等我回答？」
 * 因此这里只保留运行中的委派与它们的阻塞事件；不暴露子会话内容、不做跳转 ——
 * WebUI 的发布白名单是会话级的，多开一个子会话页等于绕过这条边界。
 *
 * 三个来源共用同一份状态：`get_session_delegations`（刷新后的快照）、
 * `delegation_blocked` / `delegation_blocked_resolved`（阻塞生命周期）、
 * `delegation_session_updated`（子会话状态变化）。
 */
import type { AgentSessionMeta } from '@profer/shared'

export type DelegationBlockedKind = 'ask_user' | 'permission'

export interface DelegationBlockedInfo {
  id: string
  kind: DelegationBlockedKind
  requestId: string
  toolName?: string
  /** AskUser 的首个问题；权限阻塞没有 */
  question?: string
}

export interface DelegationInfo {
  delegationId: string
  title: string
  role: string
  roleLabel: string
  goal: string
  startedAt: number
  blocked: DelegationBlockedInfo[]
}

export interface DelegationsView {
  running: DelegationInfo[]
  blockedCount: number
}

export const DELEGATION_ROLE_LABELS: Record<string, string> = {
  explore: '探索',
  research: '调研',
  implement: '实现',
  review: '审查',
  custom: '自定义',
}

/** 子会话进入这些状态后就不再属于「在跑/在等」。 */
const TERMINAL_DELEGATION_STATUSES = new Set(['completed', 'failed', 'interrupted', 'cancelled'])

function stringField(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function numberField(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

export function delegationRoleLabel(role: string): string {
  return DELEGATION_ROLE_LABELS[role] ?? (role || '子任务')
}

function normalizeBlocked(value: unknown): DelegationBlockedInfo | null {
  if (!value || typeof value !== 'object') return null
  const record = value as Record<string, unknown>
  const id = stringField(record.id)
  const requestId = stringField(record.requestId)
  if (!id || !requestId) return null
  const kind: DelegationBlockedKind = record.kind === 'permission' ? 'permission' : 'ask_user'
  const toolName = stringField(record.toolName)
  const questions = Array.isArray(record.questions) ? record.questions : []
  const firstQuestion = questions.find((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
  const question = stringField(firstQuestion?.question)
  return {
    id,
    kind,
    requestId,
    ...(toolName ? { toolName } : {}),
    ...(question ? { question } : {}),
  }
}

function withBlockedCount(running: DelegationInfo[]): DelegationsView {
  return {
    running,
    blockedCount: running.reduce((sum, entry) => sum + entry.blocked.length, 0),
  }
}

/** `get_session_delegations` 的 data → 页面状态。 */
export function normalizeDelegations(value: unknown): DelegationsView {
  if (!value || typeof value !== 'object') return { running: [], blockedCount: 0 }
  const records = (value as { running?: unknown }).running
  if (!Array.isArray(records)) return { running: [], blockedCount: 0 }

  const running: DelegationInfo[] = []
  for (const item of records) {
    if (!item || typeof item !== 'object') continue
    const record = item as Record<string, unknown>
    const delegationId = stringField(record.delegationId)
    if (!delegationId) continue
    const role = stringField(record.role)
    const blocked: DelegationBlockedInfo[] = []
    if (Array.isArray(record.pendingBlockedEvents)) {
      for (const raw of record.pendingBlockedEvents) {
        const event = normalizeBlocked(raw)
        if (event) blocked.push(event)
      }
    }
    running.push({
      delegationId,
      title: stringField(record.title) || '委派子任务',
      role,
      roleLabel: delegationRoleLabel(role),
      goal: stringField(record.goal),
      startedAt: numberField(record.startedAt),
      blocked,
    })
  }
  return withBlockedCount(running)
}

export interface BlockedEventUpdate {
  delegationId: string
  blocked: DelegationBlockedInfo
  createdAt: number
}

/** `delegation_blocked` 事件 → 可直接套进状态的增量。 */
export function normalizeBlockedEvent(value: unknown): BlockedEventUpdate | null {
  if (!value || typeof value !== 'object') return null
  const record = value as Record<string, unknown>
  const blocked = normalizeBlocked(record.blockedEvent)
  const delegationId = stringField(record.delegationId) || stringField((record.blockedEvent as Record<string, unknown> | undefined)?.delegationId)
  if (!blocked || !delegationId) return null
  const createdAt = numberField((record.blockedEvent as Record<string, unknown>).createdAt) || Date.now()
  return { delegationId, blocked, createdAt }
}

/**
 * 阻塞事件是「子任务在等你」的唯一强信号，必须有独立于快照的兜底：
 * 快照还没刷新到（或委派刚创建）时，先用占位条目把提示亮起来。
 */
export function applyDelegationBlocked(view: DelegationsView, update: BlockedEventUpdate): DelegationsView {
  const index = view.running.findIndex((entry) => entry.delegationId === update.delegationId)
  if (index < 0) {
    return withBlockedCount([
      ...view.running,
      {
        delegationId: update.delegationId,
        title: '委派子任务',
        role: '',
        roleLabel: delegationRoleLabel(''),
        goal: '',
        startedAt: update.createdAt,
        blocked: [update.blocked],
      },
    ])
  }
  const next = view.running.slice()
  const entry = next[index]!
  // 同一事件可能重复投递（父会话与用户都能解答）：按 id 去重。
  if (entry.blocked.some((item) => item.id === update.blocked.id)) return view
  next[index] = { ...entry, blocked: [...entry.blocked, update.blocked] }
  return withBlockedCount(next)
}

export interface BlockedResolution {
  delegationId?: string
  blockedEventId?: string
  requestId?: string
}

/** 阻塞解除：按事件 id 或 requestId 都能收（旧事件可能没有 blockedEventId）。 */
export function resolveDelegationBlocked(view: DelegationsView, resolution: BlockedResolution): DelegationsView {
  const { blockedEventId, requestId } = resolution
  if (!blockedEventId && !requestId) return view
  let changed = false
  const next = view.running.map((entry) => {
    if (resolution.delegationId && entry.delegationId !== resolution.delegationId) return entry
    const blocked = entry.blocked.filter((item) =>
      blockedEventId ? item.id !== blockedEventId : item.requestId !== requestId)
    if (blocked.length === entry.blocked.length) return entry
    changed = true
    return { ...entry, blocked }
  })
  return changed ? withBlockedCount(next) : view
}

/** 子会话状态变化：终端状态把它移出「在跑」，运行中则更新标题/角色/目标。 */
export function applyDelegationSessionUpdate(
  view: DelegationsView,
  session: AgentSessionMeta,
  parentSessionId: string,
): DelegationsView {
  if (session.parentSessionId !== parentSessionId) return view
  const delegationId = session.sourceDelegationId
  if (!delegationId) return view

  const status = session.delegationStatus ?? ''
  const index = view.running.findIndex((entry) => entry.delegationId === delegationId)
  if (TERMINAL_DELEGATION_STATUSES.has(status)) {
    if (index < 0) return view
    return withBlockedCount(view.running.filter((entry) => entry.delegationId !== delegationId))
  }
  if (status !== 'running' && index < 0) return view

  const role = session.delegationRole ?? ''
  const nextEntry: DelegationInfo = {
    delegationId,
    title: session.title || '委派子任务',
    role,
    roleLabel: delegationRoleLabel(role),
    goal: session.delegationGoal ?? '',
    startedAt: session.createdAt,
    blocked: index >= 0 ? view.running[index]!.blocked : [],
  }
  const next = view.running.slice()
  if (index >= 0) next[index] = nextEntry
  else next.push(nextEntry)
  return withBlockedCount(next)
}

/** 状态条主文案；没有子任务时返回 null（整条不渲染）。 */
export function delegationSummary(view: DelegationsView): string | null {
  if (view.blockedCount > 0) {
    return view.blockedCount === 1 ? '1 个子任务在等你回答' : `${view.blockedCount} 个子任务在等你回答`
  }
  if (view.running.length > 0) return `有 ${view.running.length} 个子任务在跑`
  return null
}

/**
 * 目标在状态条里只做「一眼知道在干什么」的预览。
 *
 * 委派目标是完整 prompt（实测单个 2-4 KB、带换行与步骤清单），原样铺开会把手机屏幕
 * 整屏占满 —— 状态条是「在跑/在等」的提示，不是子会话正文。这里折叠空白并截断；
 * 完整内容留给桌面端查看。
 */
export function delegationGoalPreview(goal: string, maxLength = 72): string {
  const text = goal.replace(/\s+/g, ' ').trim()
  if (!text) return ''
  if (text.length <= maxLength) return text
  return `${text.slice(0, maxLength)}…`
}

/** 展开列表里每一行的副标题：目标预览优先，空则给角色。 */
export function delegationLineDetail(entry: DelegationInfo): string {
  return delegationGoalPreview(entry.goal) || entry.roleLabel
}