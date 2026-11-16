/**
 * Goal 的展示模型与显示策略。
 *
 * 原则与 `goal.tsx` 一致：「取能力不搬文件」。pocket 的 `@profer/shared` 是滞后快照，
 * 主仓 `goal-lifecycle` / `goal-display-policy` 里的判定没有跟着下来，所以这里按同样的
 * 口径重写一遍（可执行动作、预算耗尽、状态文案），并用测试钉住，避免两边漂移后静默不一致。
 *
 * 状态机完全在电脑端主进程：本文件只做归一化与派生，不发命令、不猜状态。
 */

export type GoalStatus = 'active' | 'paused' | 'completed' | 'blocked' | 'failed' | 'stopped' | 'stopping' | 'budget_limited'

/** 与 @profer/shared 的 GOAL_STATUSES 对齐；未知状态一律不渲染，避免把未来新增状态显示错。 */
export const GOAL_STATUSES: readonly GoalStatus[] = ['active', 'paused', 'completed', 'blocked', 'failed', 'stopped', 'stopping', 'budget_limited']

/** 可以被「继续」的状态：与主进程的 RESUMABLE 一致。 */
const RESUMABLE: readonly GoalStatus[] = ['paused', 'blocked', 'failed', 'stopped', 'budget_limited']

const STATUS_LABEL: Record<GoalStatus, string> = {
  active: '执行中',
  stopping: '正在停止',
  paused: '已暂停',
  completed: '已完成',
  blocked: '等待处理',
  failed: '执行失败',
  stopped: '已停止',
  budget_limited: '预算已耗尽',
}

/** 与 shared 的 GOAL_REASON_LABELS 同口径，只保留移动端会碰到的那些。 */
const REASON_LABEL: Record<string, string> = {
  completed: '目标已达成',
  blocked: '需要你的输入',
  budget_limited: '预算已耗尽',
  user_stop: '你主动停止',
  user_pause: '你主动暂停',
  user_interrupt: '你打断了本轮',
  retry_exhausted: '连续出错，重试用尽',
  run_error: '运行出错',
  app_restart: '应用重启，等待恢复',
  cleared: '已清除并归档',
  unknown: '原因未知',
}

export interface GoalContract {
  verification?: string
  constraints?: string
  stopWhen?: string
}

export interface GoalLimits {
  maxIterations?: number
  maxDurationMs?: number
  maxConsecutiveFailures?: number
  maxTokens?: number
}

export interface GoalUsage {
  inputTokens: number
  outputTokens: number
  totalTokens: number
}

export interface GoalIteration {
  iteration: number
  status: string
  outcome: string
  startedAt: number
  finishedAt: number
  summary: string
  evidence: string[]
  error?: string
}

export interface GoalLifecycleEvent {
  id: string
  from: GoalStatus | null
  to: GoalStatus | 'cleared'
  reason?: string
  detail?: string
  at: number
  revision?: number
}

export interface GoalState {
  id: string
  goal: string
  status: GoalStatus
  iteration: number
  consecutiveFailures: number
  startedAt: number
  updatedAt: number
  revision?: number
  activeRunId?: string
  elapsedMs?: number
  contract?: GoalContract
  limits: GoalLimits
  usage?: GoalUsage
  lastSummary?: string
  lastEvidence?: string[]
  reasonCode?: string
  reasonDetail?: string
  stopReason?: string
  retry?: { attempt: number; nextAt: number }
  waiting?: 'plan_mode'
  idleCheckin?: { nextAt?: number; count: number; paused?: boolean }
  history?: GoalIteration[]
  lifecycle?: GoalLifecycleEvent[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function asNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && item.trim() !== '') : []
}

function normalizeContract(value: unknown): GoalContract | undefined {
  if (!isRecord(value)) return undefined
  const contract: GoalContract = {
    verification: asString(value.verification),
    constraints: asString(value.constraints),
    stopWhen: asString(value.stopWhen),
  }
  return contract.verification || contract.constraints || contract.stopWhen ? contract : undefined
}

function normalizeLimits(value: unknown): GoalLimits {
  if (!isRecord(value)) return {}
  return {
    maxIterations: asNumber(value.maxIterations),
    maxDurationMs: asNumber(value.maxDurationMs),
    maxConsecutiveFailures: asNumber(value.maxConsecutiveFailures),
    maxTokens: asNumber(value.maxTokens),
  }
}

function normalizeUsage(value: unknown): GoalUsage | undefined {
  if (!isRecord(value)) return undefined
  const inputTokens = asNumber(value.inputTokens) ?? 0
  const outputTokens = asNumber(value.outputTokens) ?? 0
  const totalTokens = asNumber(value.totalTokens) ?? inputTokens + outputTokens
  if (totalTokens <= 0) return undefined
  return { inputTokens, outputTokens, totalTokens }
}

function normalizeHistory(value: unknown): GoalIteration[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item): GoalIteration[] => {
    if (!isRecord(item)) return []
    const iteration = asNumber(item.iteration)
    if (iteration === undefined) return []
    return [{
      iteration,
      status: typeof item.status === 'string' ? item.status : '',
      outcome: typeof item.outcome === 'string' ? item.outcome : '',
      startedAt: asNumber(item.startedAt) ?? 0,
      finishedAt: asNumber(item.finishedAt) ?? 0,
      summary: typeof item.summary === 'string' ? item.summary : '',
      evidence: asStringArray(item.evidence),
      error: asString(item.error),
    }]
  })
}

function normalizeLifecycle(value: unknown): GoalLifecycleEvent[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item): GoalLifecycleEvent[] => {
    if (!isRecord(item)) return []
    const to = typeof item.to === 'string' ? item.to : ''
    if (!to) return []
    const at = asNumber(item.at)
    if (at === undefined) return []
    return [{
      id: typeof item.id === 'string' ? item.id : `${to}-${at}`,
      from: (typeof item.from === 'string' ? item.from : null) as GoalStatus | null,
      to: to as GoalStatus | 'cleared',
      reason: asString(item.reason),
      detail: asString(item.detail),
      at,
      revision: asNumber(item.revision),
    }]
  })
}

/**
 * 把 WS 返回的 goal 归一化成展示模型。
 *
 * 返回 null 的两种情况都不要渲染：没有 goal，或者状态不在已知集合里
 * （后者说明两端版本不一致，显示一个猜出来的状态比不显示更糟）。
 */
export function normalizeGoal(value: unknown): GoalState | null {
  if (!isRecord(value)) return null
  const id = asString(value.id)
  const goal = asString(value.goal)
  const status = asString(value.status) as GoalStatus | undefined
  if (!id || !goal || !status || !GOAL_STATUSES.includes(status)) return null
  const retry = isRecord(value.retry) && asNumber(value.retry.nextAt) !== undefined
    ? { attempt: asNumber(value.retry.attempt) ?? 1, nextAt: asNumber(value.retry.nextAt) ?? 0 }
    : undefined
  const idleCheckin = isRecord(value.idleCheckin)
    ? {
      nextAt: asNumber(value.idleCheckin.nextAt),
      count: asNumber(value.idleCheckin.count) ?? 0,
      paused: value.idleCheckin.paused === true ? true : undefined,
    }
    : undefined
  return {
    id,
    goal,
    status,
    iteration: asNumber(value.iteration) ?? 0,
    consecutiveFailures: asNumber(value.consecutiveFailures) ?? 0,
    startedAt: asNumber(value.startedAt) ?? 0,
    updatedAt: asNumber(value.updatedAt) ?? 0,
    revision: asNumber(value.revision),
    activeRunId: asString(value.activeRunId),
    elapsedMs: asNumber(value.elapsedMs),
    contract: normalizeContract(value.contract),
    limits: normalizeLimits(value.limits),
    usage: normalizeUsage(value.usage),
    lastSummary: asString(value.lastSummary),
    lastEvidence: asStringArray(value.lastEvidence),
    reasonCode: asString(value.reasonCode),
    reasonDetail: asString(value.reasonDetail),
    stopReason: asString(value.stopReason),
    retry,
    waiting: value.waiting === 'plan_mode' ? 'plan_mode' : undefined,
    idleCheckin,
    history: normalizeHistory(value.history),
    lifecycle: normalizeLifecycle(value.lifecycle),
  }
}

/** 归档 Goal 列表：只要数组，逐条按同一套规则归一化。 */
export function normalizeGoalHistory(value: unknown): GoalState[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item) => {
    const goal = normalizeGoal(item)
    return goal ? [goal] : []
  })
}

// ===== 显示策略 =====

export function formatGoalElapsed(ms: number): string {
  const seconds = Math.floor(Math.max(0, ms) / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m${seconds % 60 > 0 ? `${seconds % 60}s` : ''}`
  return `${Math.floor(minutes / 60)}h${minutes % 60 > 0 ? `${minutes % 60}m` : ''}`
}

/** 正在跑时把「距上次更新」也计入，否则计时会停在最后一轮结束的时刻。 */
export function isGoalRunning(goal: GoalState | undefined): boolean {
  return Boolean(goal) && (goal!.status === 'active' || goal!.status === 'stopping') && Boolean(goal!.activeRunId)
}

export function goalElapsed(goal: GoalState, now: number): number {
  const base = Math.max(0, goal.elapsedMs ?? 0)
  return base + (isGoalRunning(goal) ? Math.max(0, now - goal.updatedAt) : 0)
}

/** 已设置的预算维度；未设置视为不限，不出现在结果里。 */
export function goalBudgetDimensions(goal: GoalState): Array<{ kind: 'iterations' | 'duration' | 'tokens'; used: number; limit: number }> {
  const dimensions: Array<{ kind: 'iterations' | 'duration' | 'tokens'; used: number; limit: number }> = []
  if (goal.limits.maxIterations !== undefined) {
    dimensions.push({ kind: 'iterations', used: goal.iteration, limit: goal.limits.maxIterations })
  }
  if (goal.limits.maxDurationMs !== undefined) {
    dimensions.push({ kind: 'duration', used: goal.elapsedMs ?? 0, limit: goal.limits.maxDurationMs })
  }
  if (goal.limits.maxTokens !== undefined) {
    dimensions.push({ kind: 'tokens', used: goal.usage?.totalTokens ?? 0, limit: goal.limits.maxTokens })
  }
  return dimensions
}

const BUDGET_EXHAUSTED_LABEL = {
  iterations: '已达到轮次上限',
  duration: '已达到净运行时长上限',
  tokens: '已达到 Token 上限',
} as const

/** 展示、恢复准入与轮次准入共用的预算耗尽判定。 */
export function budgetExhaustedReasons(goal: GoalState): string[] {
  return goalBudgetDimensions(goal)
    .filter((item) => item.used >= item.limit)
    .map((item) => BUDGET_EXHAUSTED_LABEL[item.kind])
}

export function formatGoalBudget(limits: GoalLimits): string {
  const parts = [
    limits.maxIterations === undefined ? '' : `${limits.maxIterations} 轮`,
    limits.maxDurationMs === undefined ? '' : formatGoalElapsed(limits.maxDurationMs),
    limits.maxTokens === undefined ? '' : `${limits.maxTokens.toLocaleString()} tokens`,
  ].filter(Boolean)
  const tail = limits.maxConsecutiveFailures ? `连续 ${limits.maxConsecutiveFailures} 轮无进展时停止` : ''
  const head = parts.length > 0 ? parts.join(' · ') : '不限'
  return tail ? `${head}（${tail}）` : head
}

export interface GoalActionState {
  canPause: boolean
  canStop: boolean
  canEdit: boolean
  canResume: boolean
  canClear: boolean
  budgetEditRequired: boolean
}

/**
 * 当前状态下允许的操作。与主仓 `getGoalActions` 同口径：
 * owner 还占着 run 时一律按「运行中」处理（停止超时后 activeRunId 仍会保留）。
 */
export function goalActionState(goal: GoalState): GoalActionState {
  const ownerBusy = Boolean(goal.activeRunId)
  const running = goal.status === 'active' || goal.status === 'stopping' || ownerBusy
  const budgetEditRequired = budgetExhaustedReasons(goal).length > 0
  return {
    canPause: goal.status === 'active',
    canStop: goal.status === 'active' || (!running && goal.status !== 'completed' && goal.status !== 'stopped'),
    canEdit: !running,
    canResume: !running && !budgetEditRequired && RESUMABLE.includes(goal.status),
    canClear: !running,
    budgetEditRequired,
  }
}

/** 旧原因字符串保留在 detail 中；不从模型正文猜原因。 */
export function normalizeGoalReason(reason: string | undefined): string {
  if (!reason) return 'unknown'
  if (reason in REASON_LABEL) return reason
  if (reason === 'user') return 'user_stop'
  if (reason === 'process_exit') return 'app_restart'
  if (reason === 'max_iterations' || reason === 'max_tokens' || reason === 'max_duration' || reason.startsWith('已达到 Goal')) {
    return 'budget_limited'
  }
  return 'unknown'
}

/** 发消息就能自动续上的状态：受阻，或被中断/重试用尽而暂停。显式暂停不行。 */
export function isGoalResumableByMessage(goal: Pick<GoalState, 'status' | 'reasonCode' | 'stopReason'>): boolean {
  if (goal.status === 'blocked') return true
  const reason = normalizeGoalReason(goal.reasonCode ?? goal.stopReason)
  return goal.status === 'paused' && (reason === 'user_interrupt' || reason === 'retry_exhausted')
}

/**
 * 状态文案。active 期间要区分「真的在跑」与各种等待：否则一个卡在等审批的 Goal
 * 会一直显示「执行中」，用户不知道球在自己这边。
 */
export function goalStatusLabel(goal: GoalState, waits: { askUser: number; permission: number }, now: number): string {
  if (goal.status === 'active') {
    if (waits.askUser > 0) return '等待你的回答'
    if (waits.permission > 0) return '等待审批'
    if (goal.waiting === 'plan_mode') return '计划模式中，切换为可执行模式后继续'
    if (goal.retry && !goal.activeRunId) {
      return `运行出错，${formatGoalElapsed(Math.max(0, goal.retry.nextAt - now))}后第 ${goal.retry.attempt} 次重试`
    }
    if (goal.activeRunId) return STATUS_LABEL.active
    if (goal.idleCheckin?.paused) return '空闲自检已暂停 · 发消息即可继续'
    if (goal.idleCheckin?.nextAt !== undefined) {
      const count = goal.idleCheckin.count > 0 ? `（第 ${goal.idleCheckin.count + 1} 次）` : ''
      return `空闲中，${formatGoalElapsed(Math.max(0, goal.idleCheckin.nextAt - now))}后自动继续${count}`
    }
    return '等待会话空闲'
  }
  if (isGoalResumableByMessage(goal)) {
    return `${STATUS_LABEL[goal.status]} · ${goal.status === 'blocked' ? '发消息补充后自动继续' : '发消息即可继续'}`
  }
  if (normalizeGoalReason(goal.reasonCode ?? goal.stopReason) === 'app_restart') {
    return `${STATUS_LABEL[goal.status]} · 重启后待恢复`
  }
  return STATUS_LABEL[goal.status]
}

export function goalStatusText(status: GoalStatus): string {
  return STATUS_LABEL[status]
}

/** 「原因」那一行：reasonCode 优先，stopReason 作为旧数据兜底。 */
export function goalReasonText(goal: GoalState): string {
  const code = normalizeGoalReason(goal.reasonCode ?? goal.stopReason)
  const label = REASON_LABEL[code] ?? REASON_LABEL.unknown!
  // 与桌面同口径：只有「已经给了结构化 reasonCode」或「原因未知」时，stopReason 才算补充说明；
  // 否则它本身就是被归一化的旧枚举值，拼进文案会变成「预算已耗尽：max_iterations」。
  const detail = goal.reasonDetail
    ?? (goal.reasonCode || code === 'unknown' ? goal.stopReason : undefined)
  return detail && detail !== label && detail !== code ? `${label}：${detail}` : label
}

// ===== 编辑表单 =====

export interface GoalLimitsFormInput {
  maxIterations: string
  maxDurationMinutes: string
  maxConsecutiveFailures: string
  maxTokens: string
}

export interface GoalLimitsPatchResult {
  limits?: { maxIterations?: number | null; maxDurationMs?: number | null; maxTokens?: number | null; maxConsecutiveFailures?: number }
  error?: string
}

/**
 * 预算表单 → 更新补丁。
 *
 * 空串表示「不限」，翻译成 `null`（清除该项）；「连续无进展」必填，因为它是
 * 死循环的唯一兜底。分钟 → 毫秒，且必须是正整数（服务端校验只收 safe integer）。
 */
export function parseGoalLimitsInput(input: GoalLimitsFormInput): GoalLimitsPatchResult {
  const parseOptional = (raw: string, integer: boolean): number | null | undefined => {
    const text = raw.trim()
    if (!text) return null
    const value = Number(text)
    if (!Number.isFinite(value) || value <= 0) return undefined
    if (integer && !Number.isInteger(value)) return undefined
    return value
  }

  const maxIterations = parseOptional(input.maxIterations, true)
  if (maxIterations === undefined) return { error: '轮次上限必须是正整数，或留空表示不限' }
  const maxTokens = parseOptional(input.maxTokens, true)
  if (maxTokens === undefined) return { error: 'Token 上限必须是正整数，或留空表示不限' }

  const minutes = parseOptional(input.maxDurationMinutes, false)
  if (minutes === undefined) return { error: '运行时长必须是正数（分钟），或留空表示不限' }
  const maxDurationMs = minutes === null ? null : Math.round(minutes * 60_000)

  const consecutive = Number(input.maxConsecutiveFailures.trim())
  if (!Number.isInteger(consecutive) || consecutive <= 0) return { error: '连续无进展上限必须是正整数' }

  return {
    limits: {
      maxIterations,
      maxDurationMs,
      maxTokens,
      maxConsecutiveFailures: consecutive,
    },
  }
}

/** 表单默认值：把当前预算回填成可编辑文本。 */
export function goalLimitsFormValue(limits: GoalLimits): GoalLimitsFormInput {
  return {
    maxIterations: limits.maxIterations === undefined ? '' : String(limits.maxIterations),
    maxDurationMinutes: limits.maxDurationMs === undefined ? '' : String(limits.maxDurationMs / 60_000),
    maxConsecutiveFailures: String(limits.maxConsecutiveFailures ?? 3),
    maxTokens: limits.maxTokens === undefined ? '' : String(limits.maxTokens),
  }
}

/** 契约每项的字符上限，与桌面编辑器一致（防止一行契约把提示词撑爆）。 */
export const GOAL_CONTRACT_MAX_CHARS = 240
