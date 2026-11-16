/**
 * WebUI 的 Goal 展示与控制。
 *
 * 「取能力不搬文件」：只保留展示需要的字段，不引入 @profer/shared 的完整 AgentGoalState
 * （pocket 那份是滞后快照，且 goal 字段会随主仓演进）。状态机完全在电脑端主进程，
 * 本页只做「看到状态 + 发命令」，不做本地推断。
 *
 * 这一版往桌面端补齐了三块：契约、预算与轮次历史；以及预算耗尽时的「改预算再恢复」。
 * 判定口径（可执行动作、预算耗尽、状态文案）都在 `goal-model.ts` 里，有单测钉住。
 */
import * as React from 'react'
import { useOverlay } from './overlay'
import {
  GOAL_CONTRACT_MAX_CHARS,
  budgetExhaustedReasons,
  formatGoalBudget,
  formatGoalElapsed,
  goalActionState,
  goalElapsed,
  goalLimitsFormValue,
  goalReasonText,
  goalStatusLabel,
  goalStatusText,
  parseGoalLimitsInput,
  type GoalContract,
  type GoalIteration,
  type GoalLimits,
  type GoalLimitsFormInput,
  type GoalState,
  type GoalStatus,
} from './goal-model'

export type GoalAction = 'pause' | 'resume' | 'stop' | 'clear'

/** 需要每秒刷新的状态：运行计时、重试倒计时、空闲自检倒计时。 */
function needsLiveClock(goal: GoalState | undefined): boolean {
  return Boolean(goal) && (goal!.status === 'active' || goal!.status === 'stopping' || goal!.retry !== undefined || goal!.idleCheckin?.nextAt !== undefined)
}

function useNow(enabled: boolean): number {
  const [now, setNow] = React.useState(() => Date.now())
  React.useEffect(() => {
    if (!enabled) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [enabled])
  return now
}

function DetailRow({ label, value }: { label: string; value: string }): React.ReactElement {
  return (
    <div className="goal-row">
      <span className="goal-row-label">{label}</span>
      <span className="goal-row-value">{value}</span>
    </div>
  )
}

function outcomeLabel(entry: GoalIteration): string {
  if (entry.outcome === 'failed') return '失败'
  if (entry.outcome === 'stopped') return '停止'
  if (entry.status === 'complete') return '完成'
  if (entry.status === 'blocked') return '受阻'
  return '推进'
}

function IterationList({ goal }: { goal: GoalState }): React.ReactElement {
  const entries = [...(goal.history ?? [])].slice(-5).reverse()
  if (entries.length === 0) return <span className="goal-empty">尚无已结束的轮次</span>
  return (
    <div className="goal-iters">
      {entries.map((entry) => (
        <div className="goal-iter" key={entry.iteration}>
          <div className="goal-iter-head">
            第 {entry.iteration} 轮 · {outcomeLabel(entry)}
            {entry.startedAt > 0 && entry.finishedAt > entry.startedAt
              ? ` · ${formatGoalElapsed(entry.finishedAt - entry.startedAt)}`
              : ''}
          </div>
          {entry.summary && <div className="goal-iter-text">{entry.summary}</div>}
          {entry.evidence.length > 0 && <div className="goal-iter-text">证据：{entry.evidence.join('；')}</div>}
          {entry.error && <div className="goal-iter-error">{entry.error}</div>}
        </div>
      ))}
    </div>
  )
}

function ArchivedGoals({ archives }: { archives: GoalState[] }): React.ReactElement | null {
  if (archives.length === 0) return null
  return (
    <details className="goal-archive">
      <summary>已归档 Goal（{archives.length}）</summary>
      <div className="goal-archive-body">
        {archives.slice(-5).reverse().map((item) => (
          <div className="goal-iter" key={`${item.id}:${item.revision ?? 0}`}>
            <div className="goal-iter-head">{item.goal} · {goalStatusText(item.status)}</div>
            {item.contract?.verification && <div className="goal-iter-text">验收：{item.contract.verification}</div>}
            <div className="goal-iter-text">{goalReasonText(item)}</div>
          </div>
        ))}
      </div>
    </details>
  )
}

/**
 * 状态条：一行摘要 + 展开详情 + 操作。
 *
 * 展开区在手机上必须自己滚（`max-height` + `overflow`），否则十轮摘要会把消息区挤没，
 * 表现为「怎么滑都滑不动」——桌面端踩过同一个坑。
 */
export function GoalBar({ goal, archives, busy, waits, onAction, onEdit }: {
  goal: GoalState
  archives: GoalState[]
  busy: boolean
  waits: { askUser: number; permission: number }
  onAction: (action: GoalAction) => void
  onEdit: () => void
}): React.ReactElement {
  const [expanded, setExpanded] = React.useState(false)
  const now = useNow(needsLiveClock(goal))
  const actions = goalActionState(goal)
  const exhausted = budgetExhaustedReasons(goal)
  const elapsed = goalElapsed(goal, now)
  const usageText = goal.usage
    ? `${goal.usage.totalTokens.toLocaleString()}${goal.limits.maxTokens === undefined ? '' : ` / ${goal.limits.maxTokens.toLocaleString()}`} tokens`
    : ''
  const blocking = actions.budgetEditRequired
    && goal.status !== 'active' && goal.status !== 'stopping' && goal.status !== 'completed'

  return (
    <div className="goal-bar">
      <button
        className="goal-line"
        onClick={() => setExpanded((previous) => !previous)}
        aria-expanded={expanded}
        title={expanded ? '收起目标详情' : '展开契约、预算与轮次'}
      >
        <span className={`goal-status ${goal.status}`}>{goalStatusText(goal.status)}</span>
        <span className="goal-text">{goal.goal}</span>
        <span className={`chev ${expanded ? 'open' : ''}`} aria-hidden="true">›</span>
      </button>
      <div className="goal-meta-row">
        <span className="goal-state">{goalStatusLabel(goal, waits, now)}</span>
        <span className="goal-meta">
          第 {goal.iteration} 轮 · {formatGoalElapsed(elapsed)}{usageText ? ` · ${usageText}` : ''}
        </span>
      </div>

      {blocking && (
        <div className="goal-warn">
          <span>{exhausted.join('；') || goal.stopReason || '预算已耗尽'}。提高预算后才能继续。</span>
          <button className="goal-action" disabled={busy || !actions.canEdit} onClick={onEdit}>
            {actions.canEdit ? '修改预算后恢复' : '等待本轮停止'}
          </button>
        </div>
      )}

      {expanded && (
        <div className="goal-expanded">
          {goal.contract?.verification && <DetailRow label="验收" value={goal.contract.verification} />}
          {goal.contract?.constraints && <DetailRow label="约束" value={goal.contract.constraints} />}
          {goal.contract?.stopWhen && <DetailRow label="停止条件" value={goal.contract.stopWhen} />}
          <DetailRow label="预算" value={formatGoalBudget(goal.limits)} />
          {goal.usage && (
            <DetailRow
              label="用量"
              value={`${goal.usage.totalTokens.toLocaleString()} tokens（输入 ${goal.usage.inputTokens.toLocaleString()} · 输出 ${goal.usage.outputTokens.toLocaleString()}）`}
            />
          )}
          {goal.lastSummary && <DetailRow label="最近进展" value={goal.lastSummary} />}
          {goal.lastEvidence && goal.lastEvidence.length > 0 && <DetailRow label="证据" value={goal.lastEvidence.join('；')} />}
          <DetailRow label="原因" value={goalReasonText(goal)} />
          <div className="goal-section">最近轮次</div>
          <IterationList goal={goal} />
          <ArchivedGoals archives={archives} />
        </div>
      )}

      <div className="goal-actions">
        {actions.canEdit && <button className="goal-action" disabled={busy} onClick={onEdit}>编辑</button>}
        {actions.canPause && <button className="goal-action" disabled={busy} onClick={() => onAction('pause')}>暂停</button>}
        {actions.canResume && <button className="goal-action" disabled={busy} onClick={() => onAction('resume')}>继续</button>}
        {actions.canStop && <button className="goal-action danger" disabled={busy} onClick={() => onAction('stop')}>停止</button>}
        {actions.canClear && <button className="goal-action danger" disabled={busy} onClick={() => onAction('clear')}>清除</button>}
      </div>
    </div>
  )
}

/**
 * 没有 Goal 时的入口：默认收起，避免给不用的会话增加视觉噪音。
 *
 * 契约三项是可选的「高阶输入」：默认折叠，需要的人展开填。目标本身仍然只要一句话，
 * 不给必填项 —— 大多数目标一句话就够，契约是给需要反复验收的场景用的。
 */
export function GoalLauncher({ busy, open, onOpenChange, onStart }: {
  busy: boolean
  /** 受控展开：收起态的入口在顶栏左上角（三段式导航），本组件只负责表单 */
  open: boolean
  onOpenChange: (open: boolean) => void
  onStart: (goal: string, contract: GoalContract | undefined) => void
}): React.ReactElement | null {
  const [text, setText] = React.useState('')
  const [contractOpen, setContractOpen] = React.useState(false)
  const [contract, setContract] = React.useState({ verification: '', constraints: '', stopWhen: '' })

  if (!open) return null
  const reset = (): void => {
    onOpenChange(false)
    setText('')
    setContractOpen(false)
    setContract({ verification: '', constraints: '', stopWhen: '' })
  }
  const submit = (): void => {
    const goal = text.trim()
    if (!goal || busy) return
    const filled = {
      ...(contract.verification.trim() ? { verification: contract.verification.trim() } : {}),
      ...(contract.constraints.trim() ? { constraints: contract.constraints.trim() } : {}),
      ...(contract.stopWhen.trim() ? { stopWhen: contract.stopWhen.trim() } : {}),
    }
    onStart(goal, Object.keys(filled).length > 0 ? filled : undefined)
    reset()
  }
  return (
    <div className="goal-launcher">
      <textarea
        value={text}
        placeholder="描述一个需要多轮持续执行的目标，例如：把 X 模块的测试补到全绿"
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); submit() }
        }}
        rows={2}
      />
      <button className="goal-contract-toggle" onClick={() => setContractOpen((previous) => !previous)} aria-expanded={contractOpen}>
        {contractOpen ? '收起契约' : '契约（可选）'}
      </button>
      {contractOpen && (
        <div className="goal-contract">
          <label>
            验收证据
            <input
              value={contract.verification}
              maxLength={GOAL_CONTRACT_MAX_CHARS}
              placeholder="怎么算做完，例如 pnpm test 全绿"
              onChange={(event) => setContract((previous) => ({ ...previous, verification: event.target.value }))}
            />
          </label>
          <label>
            执行约束
            <input
              value={contract.constraints}
              maxLength={GOAL_CONTRACT_MAX_CHARS}
              placeholder="不能碰什么，例如不要改支付相关代码"
              onChange={(event) => setContract((previous) => ({ ...previous, constraints: event.target.value }))}
            />
          </label>
          <label>
            停止条件
            <input
              value={contract.stopWhen}
              maxLength={GOAL_CONTRACT_MAX_CHARS}
              placeholder="什么时候先停下来问，例如需要线上凭据时"
              onChange={(event) => setContract((previous) => ({ ...previous, stopWhen: event.target.value }))}
            />
          </label>
        </div>
      )}
      <div className="goal-launcher-row">
        <span className="hint">Ctrl/⌘ + Enter 开始</span>
        <button onClick={reset}>取消</button>
        <button className="primary" disabled={!text.trim() || busy} onClick={submit}>开始</button>
      </div>
    </div>
  )
}

/**
 * 编辑目标、契约与预算。
 *
 * 「保存并恢复」是关键：预算耗尽后用户唯一的出路就是提高预算再继续，
 * 分两步做（先保存、再去点继续）会让 reserve 校验失败一次，体验上像是不让改。
 */
export function GoalEditor({ goal, busy, onClose, onSave }: {
  goal: GoalState
  busy: boolean
  onClose: () => void
  onSave: (patch: { goal: string; contract: GoalContract; limits: GoalLimits }, resume: boolean) => void
}): React.ReactElement {
  const [goalText, setGoalText] = React.useState(goal.goal)
  const [contract, setContract] = React.useState({
    verification: goal.contract?.verification ?? '',
    constraints: goal.contract?.constraints ?? '',
    stopWhen: goal.contract?.stopWhen ?? '',
  })
  const [limits, setLimits] = React.useState<GoalLimitsFormInput>(() => goalLimitsFormValue(goal.limits))
  const [error, setError] = React.useState('')
  // 保存中不响应 Esc：与预设面板同一口径
  const sheetRef = useOverlay(onClose, !busy)
  const actions = goalActionState(goal)
  const canResumeAfterSave = actions.canResume || actions.budgetEditRequired

  const field = (key: keyof GoalLimitsFormInput) => ({
    value: limits[key],
    inputMode: key === 'maxDurationMinutes' ? 'decimal' as const : 'numeric' as const,
    onChange: (event: React.ChangeEvent<HTMLInputElement>) => {
      const value = event.target.value
      setLimits((previous) => ({ ...previous, [key]: value }))
    },
  })

  const submit = (resume: boolean): void => {
    if (busy) return
    const text = goalText.trim()
    if (!text) { setError('目标不能为空'); return }
    if ([contract.verification, contract.constraints, contract.stopWhen].some((value) => value.trim().length > GOAL_CONTRACT_MAX_CHARS)) {
      setError(`契约每项最多 ${GOAL_CONTRACT_MAX_CHARS} 字符`)
      return
    }
    const parsed = parseGoalLimitsInput(limits)
    if (parsed.error) { setError(parsed.error); return }
    // 服务端只收正整数：这里再把时长过一遍，避免 0.0001 分钟变成 0ms 被拒
    const nextLimits: GoalLimits = {
      maxIterations: parsed.limits?.maxIterations ?? undefined,
      maxDurationMs: parsed.limits?.maxDurationMs ?? undefined,
      maxTokens: parsed.limits?.maxTokens ?? undefined,
      maxConsecutiveFailures: parsed.limits?.maxConsecutiveFailures,
    }
    if (nextLimits.maxDurationMs !== undefined && nextLimits.maxDurationMs <= 0) {
      setError('运行时长太短，至少 1 分钟')
      return
    }
    setError('')
    onSave({
      goal: text,
      contract: {
        verification: contract.verification.trim(),
        constraints: contract.constraints.trim(),
        stopWhen: contract.stopWhen.trim(),
      },
      limits: nextLimits,
    }, resume)
  }

  return (
    <>
      <div className="sheet-scrim" onClick={() => { if (!busy) onClose() }} />
      <div className="sheet" role="dialog" aria-modal="true" aria-label="编辑目标、契约与预算" ref={sheetRef} tabIndex={-1}>
        <div className="sheet-head">
          <strong>编辑目标、契约与预算</strong>
          <span className="sheet-desc">沿用当前会话上下文与已有执行历史；修改只在非运行时生效。</span>
        </div>
        <label className="sheet-field">
          <span>目标</span>
          <textarea rows={3} value={goalText} disabled={busy} onChange={(event) => setGoalText(event.target.value)} />
        </label>
        <label className="sheet-field">
          <span>验收证据</span>
          <input
            value={contract.verification}
            maxLength={GOAL_CONTRACT_MAX_CHARS}
            disabled={busy}
            onChange={(event) => setContract((previous) => ({ ...previous, verification: event.target.value }))}
          />
        </label>
        <label className="sheet-field">
          <span>执行约束</span>
          <input
            value={contract.constraints}
            maxLength={GOAL_CONTRACT_MAX_CHARS}
            disabled={busy}
            onChange={(event) => setContract((previous) => ({ ...previous, constraints: event.target.value }))}
          />
        </label>
        <label className="sheet-field">
          <span>停止条件</span>
          <input
            value={contract.stopWhen}
            maxLength={GOAL_CONTRACT_MAX_CHARS}
            disabled={busy}
            onChange={(event) => setContract((previous) => ({ ...previous, stopWhen: event.target.value }))}
          />
        </label>
        <div className="sheet-grid">
          <label className="sheet-field">
            <span>轮次上限</span>
            <input {...field('maxIterations')} placeholder="不限" disabled={busy} />
          </label>
          <label className="sheet-field">
            <span>净运行时长（分钟）</span>
            <input {...field('maxDurationMinutes')} placeholder="不限" disabled={busy} />
          </label>
          <label className="sheet-field">
            <span>连续无进展上限</span>
            <input {...field('maxConsecutiveFailures')} disabled={busy} />
          </label>
          <label className="sheet-field">
            <span>Token 上限</span>
            <input {...field('maxTokens')} placeholder="不限" disabled={busy} />
          </label>
        </div>
        <p className="sheet-note">
          已执行 {goal.iteration} 轮 · {formatGoalElapsed(goal.elapsedMs ?? 0)} · {(goal.usage?.totalTokens ?? 0).toLocaleString()} tokens。
          预算留空表示不限；时长与 token 在每轮结束时检查，快用完时会提示 Agent 收尾。
        </p>
        {error && <p className="sheet-error" role="alert">{error}</p>}
        <div className="sheet-actions">
          <button disabled={busy} onClick={onClose}>取消</button>
          <button disabled={busy} onClick={() => submit(false)}>保存</button>
          {canResumeAfterSave && goal.status !== 'completed' && (
            <button className="primary" disabled={busy} onClick={() => submit(true)}>
              {busy ? '处理中…' : '保存并恢复'}
            </button>
          )}
        </div>
      </div>
    </>
  )
}

export type { GoalState, GoalStatus, GoalContract, GoalLimits }
