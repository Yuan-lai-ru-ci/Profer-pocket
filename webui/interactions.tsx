/**
 * WebUI 交互卡片 —— 对齐桌面端 PermissionBanner / AskUserBanner / ExitPlanModeBanner 的语义。
 *
 * 「取能力不搬文件」：数据结构、命令语义、FIFO 展示口径照桌面端，
 * UI 完全自写，只依赖 React，不引入 Jotai / Radix / electronapi-stub / globals.css。
 */
import * as React from 'react'

export interface PermissionItem {
  requestId: string
  toolName: string
  command?: string
  description?: string
  sdkTitle?: string
  sdkDescription?: string
  sdkDisplayName?: string
  dangerLevel?: string
  toolInput?: Record<string, unknown>
}

export interface AskUserOption {
  label: string
  description?: string
  preview?: string
}

export interface AskUserQuestionItem {
  question: string
  header?: string
  options: AskUserOption[]
  multiSelect?: boolean
}

export interface AskUserItem {
  requestId: string
  questions: AskUserQuestionItem[]
}

export interface ExitPlanItem {
  requestId: string
  plan?: string
  allowedPrompts?: Array<{ tool?: string; prompt?: string }>
}

export type ExitPlanAction = 'approve_auto' | 'approve_edit' | 'deny' | 'feedback'

/**
 * 模型主动提议建立 Goal。
 *
 * 与另外三类不同，提议**不阻塞当前轮次**：模型把手上这轮做完就结束了，
 * 用户批准后才由主进程接管后续轮次。所以它不该把输入框锁住。
 */
export interface GoalProposalItem {
  id: string
  objective: string
  rationale?: string
  verification?: string
  constraints?: string
  stopWhen?: string
  createdAt: number
}

/** 发消息时透传给服务端的权限模式，取值与 shared 的 PROFER_PERMISSION_MODES 一致。 */
export type SendPermissionMode = 'auto' | 'plan' | 'bypassPermissions'

export interface InteractionHandlers {
  onPermission: (requestId: string, behavior: 'allow' | 'deny', alwaysAllow?: boolean) => void
  onAskUser: (requestId: string, answers: Record<string, string>) => void
  onExitPlan: (requestId: string, action: ExitPlanAction, feedback?: string) => void
  onGoalProposal: (proposalId: string, accepted: boolean) => void
}

interface InteractionCardsProps extends InteractionHandlers {
  permissions: PermissionItem[]
  askUsers: AskUserItem[]
  exitPlans: ExitPlanItem[]
  goalProposals: GoalProposalItem[]
  busy: boolean
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

/** 归一化权限请求：服务端字段可能与主仓类型略有出入，这里按字段宽容读取。 */
export function normalizePermissionItems(raw: unknown): PermissionItem[] {
  return asArray(raw).flatMap((entry) => {
    if (!isRecord(entry) || typeof entry.requestId !== 'string') return []
    return [{
      requestId: entry.requestId,
      toolName: asString(entry.toolName) ?? '',
      command: asString(entry.command),
      description: asString(entry.description),
      sdkTitle: asString(entry.sdkTitle),
      sdkDescription: asString(entry.sdkDescription),
      sdkDisplayName: asString(entry.sdkDisplayName),
      dangerLevel: asString(entry.dangerLevel),
      toolInput: isRecord(entry.toolInput) ? entry.toolInput : undefined,
    }]
  })
}

/** 归一化 AskUser 问题：答案提交时以 question 文本为 key，与桌面端口径一致。 */
export function normalizeAskUserItems(raw: unknown): AskUserItem[] {
  return asArray(raw).flatMap((entry) => {
    if (!isRecord(entry) || typeof entry.requestId !== 'string') return []
    const questions = asArray(entry.questions).flatMap((q) => {
      if (!isRecord(q) || typeof q.question !== 'string') return []
      const options = asArray(q.options).flatMap((option) => {
        if (!isRecord(option) || typeof option.label !== 'string') return []
        return [{
          label: option.label,
          description: asString(option.description),
          preview: asString(option.preview),
        }]
      })
      return [{
        question: q.question,
        header: asString(q.header),
        options,
        multiSelect: q.multiSelect === true,
      }]
    })
    return [{ requestId: entry.requestId, questions }]
  })
}

/** 归一化 ExitPlanMode：plan 正文在 toolInput.plan 里。 */
export function normalizeExitPlanItems(raw: unknown): ExitPlanItem[] {
  return asArray(raw).flatMap((entry) => {
    if (!isRecord(entry) || typeof entry.requestId !== 'string') return []
    const toolInput = isRecord(entry.toolInput) ? entry.toolInput : null
    const allowedPrompts = asArray(entry.allowedPrompts).flatMap((prompt) => {
      if (!isRecord(prompt)) return []
      return [{ tool: asString(prompt.tool), prompt: asString(prompt.prompt) }]
    })
    return [{
      requestId: entry.requestId,
      plan: toolInput ? asString(toolInput.plan) : undefined,
      allowedPrompts,
    }]
  })
}

/** 归一化 Goal 提议：objective 为空视为无效（服务端也只在这种情况下不肯入队）。 */
export function normalizeGoalProposals(raw: unknown): GoalProposalItem[] {
  return asArray(raw).flatMap((entry) => {
    if (!isRecord(entry) || typeof entry.id !== 'string') return []
    const objective = asString(entry.objective)?.trim()
    if (!entry.id || !objective) return []
    return [{
      id: entry.id,
      objective,
      rationale: asString(entry.rationale),
      verification: asString(entry.verification),
      constraints: asString(entry.constraints),
      stopWhen: asString(entry.stopWhen),
      createdAt: typeof entry.createdAt === 'number' ? entry.createdAt : 0,
    }]
  })
}

/**
 * 权限模式只认这三种；缺失或异常时回退 Pocket 默认的「完全自动」。
 * 服务端把该值当作本轮 permissionModeOverride，写死 'auto' 会把「完全自动」降级成每步确认。
 */
export function normalizePermissionMode(value: unknown): SendPermissionMode {
  return value === 'auto' || value === 'plan' || value === 'bypassPermissions'
    ? value
    : 'bypassPermissions'
}

/** get_pending_interactions 的统一入口，事件流与快照轮询共用同一套归一化。 */
export function decodeInteractions(raw: unknown): {
  permissions: PermissionItem[]
  askUsers: AskUserItem[]
  exitPlans: ExitPlanItem[]
  goalProposals: GoalProposalItem[]
} {
  const data = isRecord(raw) ? raw : {}
  return {
    permissions: normalizePermissionItems(data.permissions),
    askUsers: normalizeAskUserItems(data.askUsers),
    exitPlans: normalizeExitPlanItems(data.exitPlans),
    goalProposals: normalizeGoalProposals(data.goalProposals),
  }
}

function CardHead({ title, tool, extra, danger = false }: {
  title: string
  tool?: string
  extra?: string
  danger?: boolean
}): React.ReactElement {
  return (
    <div className="icard-head">
      <div className="icard-heading">
        <span className={`icard-title ${danger ? 'danger' : ''}`}>{title}</span>
        {extra && <span className="icard-extra">{extra}</span>}
      </div>
      {tool && <span className="icard-tool">{tool}</span>}
    </div>
  )
}

function PermissionCard({ item, handlers, busy, extra }: {
  item: PermissionItem
  handlers: InteractionHandlers
  busy: boolean
  extra?: string
}): React.ReactElement {
  const danger = item.dangerLevel === 'dangerous'
  const tool = item.sdkDisplayName || item.toolName || '工具'
  // 命令优先展示代码块；没有命令时退回 SDK 入参或人类可读描述。
  const fallback = !item.command && !item.sdkTitle && item.toolInput
    ? JSON.stringify(item.toolInput, null, 2)
    : (!item.sdkTitle ? item.description : undefined)

  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Enter' || event.shiftKey || busy) return
      const target = event.target as HTMLElement | null
      // 焦点在按钮上时必须放行：Enter 是按钮的激活键，这里 preventDefault 会把
      // 「拒绝」「本次会话总是允许」的默认动作吃掉，再按老逻辑当成「允许」发出去 ——
      // 结果就是「想点拒绝却批了权限」。同类元素一并跳过，避免输入框里回车被吞。
      if (target && (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT'
        || target.tagName === 'BUTTON' || target.tagName === 'A'
        || target.tagName === 'SELECT' || target.isContentEditable)) return
      event.preventDefault()
      handlers.onPermission(item.requestId, 'allow')
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [item.requestId, handlers, busy])

  return (
    <div className="icard">
      <CardHead title={danger ? '危险操作需要确认' : '需要确认'} tool={tool} extra={extra} danger={danger} />
      <div className="icard-body">
        {item.sdkTitle && <p className="icard-text">{item.sdkTitle}</p>}
        {item.sdkDescription && item.sdkDescription !== item.sdkTitle && (
          <p className="icard-muted">{item.sdkDescription}</p>
        )}
        {(item.command || fallback) && <pre className="icard-pre">{item.command || fallback}</pre>}
      </div>
      <div className="icard-actions">
        <span className="icard-hint">Enter 允许</span>
        <button className="ibtn ghost" disabled={busy} onClick={() => handlers.onPermission(item.requestId, 'deny')}>
          拒绝
        </button>
        <button className="ibtn" disabled={busy} onClick={() => handlers.onPermission(item.requestId, 'allow', true)}>
          本次会话总是允许
        </button>
        <button className="ibtn primary" disabled={busy} onClick={() => handlers.onPermission(item.requestId, 'allow')}>
          允许
        </button>
      </div>
    </div>
  )
}

function AskUserCard({ item, handlers, busy, extra }: {
  item: AskUserItem
  handlers: InteractionHandlers
  busy: boolean
  extra?: string
}): React.ReactElement {
  const [selected, setSelected] = React.useState<Record<number, string[]>>({})
  const [custom, setCustom] = React.useState<Record<number, string>>({})

  const toggle = (index: number, label: string, multiSelect: boolean): void => {
    setSelected((previous) => {
      const current = previous[index] ?? []
      if (!multiSelect) return { ...previous, [index]: [label] }
      return {
        ...previous,
        [index]: current.includes(label) ? current.filter((value) => value !== label) : [...current, label],
      }
    })
  }

  const submit = (): void => {
    const answers: Record<string, string> = {}
    item.questions.forEach((question, index) => {
      const chosen = selected[index] ?? (question.options[0] ? [question.options[0].label] : [])
      const text = custom[index]?.trim()
      answers[question.question || String(index)] = text || chosen.join(', ')
    })
    handlers.onAskUser(item.requestId, answers)
  }

  return (
    <div className="icard">
      <CardHead title="请回答问题" tool="AskUserQuestion" extra={extra} />
      <div className="icard-body">
        {item.questions.map((question, index) => (
          <div className="iquestion" key={`${item.requestId}-${index}`}>
            <p className="icard-text">{question.header ? `${question.header} · ${question.question}` : question.question}</p>
            <div className="ioptions">
              {question.options.map((option) => {
                const active = (selected[index] ?? (question.options[0] ? [question.options[0].label] : [])).includes(option.label)
                return (
                  <button
                    key={option.label}
                    className={`ioption ${active ? 'selected' : ''}`}
                    disabled={busy}
                    onClick={() => toggle(index, option.label, question.multiSelect === true)}
                  >
                    <span>{option.label}</span>
                    {option.description && <span className="ioption-desc">{option.description}</span>}
                  </button>
                )
              })}
            </div>
            <input
              className="iinput"
              placeholder="或输入自定义回答…"
              value={custom[index] ?? ''}
              disabled={busy}
              onChange={(event) => setCustom((previous) => ({ ...previous, [index]: event.target.value }))}
            />
          </div>
        ))}
      </div>
      <div className="icard-actions">
        <button className="ibtn primary" disabled={busy} onClick={submit}>提交回答</button>
      </div>
    </div>
  )
}

function ExitPlanCard({ item, handlers, busy, extra }: {
  item: ExitPlanItem
  handlers: InteractionHandlers
  busy: boolean
  extra?: string
}): React.ReactElement {
  const [feedback, setFeedback] = React.useState('')
  return (
    <div className="icard">
      <CardHead title="计划待审批" tool="ExitPlanMode" extra={extra} />
      <div className="icard-body">
        {item.plan && <pre className="icard-pre">{item.plan}</pre>}
        {item.allowedPrompts && item.allowedPrompts.length > 0 && (
          <div className="iprompts">
            {item.allowedPrompts.map((prompt, index) => (
              <div key={index}>{prompt.tool ? `${prompt.tool} · ` : ''}{prompt.prompt}</div>
            ))}
          </div>
        )}
        <input
          className="iinput"
          placeholder="填写修改意见后点「提交反馈」…"
          value={feedback}
          disabled={busy}
          onChange={(event) => setFeedback(event.target.value)}
        />
      </div>
      <div className="icard-actions">
        <button className="ibtn ghost" disabled={busy} onClick={() => handlers.onExitPlan(item.requestId, 'deny')}>
          拒绝计划
        </button>
        <button className="ibtn" disabled={busy || !feedback.trim()} onClick={() => handlers.onExitPlan(item.requestId, 'feedback', feedback.trim())}>
          提交反馈
        </button>
        <button className="ibtn" disabled={busy} onClick={() => handlers.onExitPlan(item.requestId, 'approve_edit')}>
          批准但逐项确认
        </button>
        <button className="ibtn primary" disabled={busy} onClick={() => handlers.onExitPlan(item.requestId, 'approve_auto')}>
          批准并自动执行
        </button>
      </div>
    </div>
  )
}

function GoalProposalCard({ item, handlers, busy, extra }: {
  item: GoalProposalItem
  handlers: InteractionHandlers
  busy: boolean
  extra?: string
}): React.ReactElement {
  // 契约三段都可缺省：模型只给目标也算有效提议，别为了排版编造字段。
  const contract = [
    item.verification ? { key: '验收', value: item.verification } : null,
    item.constraints ? { key: '约束', value: item.constraints } : null,
    item.stopWhen ? { key: '停止', value: item.stopWhen } : null,
  ].filter((row): row is { key: string; value: string } => row !== null)

  return (
    <div className="icard">
      <CardHead title="建议转为持续目标" tool="propose_goal" extra={extra} />
      <div className="icard-body">
        <p className="icard-text">{item.objective}</p>
        {item.rationale && <p className="icard-muted">{item.rationale}</p>}
        {contract.length > 0 && (
          <div className="icontract">
            {contract.map((row) => (
              <div className="icontract-row" key={row.key}>
                <span className="icontract-key">{row.key}</span>
                <span className="icontract-val">{row.value}</span>
              </div>
            ))}
          </div>
        )}
        <p className="icard-muted">
          批准后 Profer 会以此为长期目标持续多轮执行，可随时在状态条里暂停或停止；
          忽略也不影响当前这一轮正常跑完。
        </p>
      </div>
      <div className="icard-actions">
        <span className="icard-hint">不阻塞当前轮次</span>
        <button className="ibtn ghost" disabled={busy} onClick={() => handlers.onGoalProposal(item.id, false)}>
          忽略
        </button>
        <button className="ibtn primary" disabled={busy} onClick={() => handlers.onGoalProposal(item.id, true)}>
          开始持续执行
        </button>
      </div>
    </div>
  )
}

/** FIFO 展示：一次只显示一张卡片，其余用 (+N) 提示，与桌面端横幅口径一致。 */
export function InteractionCards({ permissions, askUsers, exitPlans, goalProposals, busy, ...handlers }: InteractionCardsProps): React.ReactElement | null {
  const total = permissions.length + askUsers.length + exitPlans.length + goalProposals.length
  if (total === 0) return null
  const extra = total > 1 ? `(+${total - 1})` : undefined
  const firstPermission = permissions[0]
  if (firstPermission) return <PermissionCard item={firstPermission} handlers={handlers} busy={busy} extra={extra} />
  const firstAskUser = askUsers[0]
  if (firstAskUser) return <AskUserCard item={firstAskUser} handlers={handlers} busy={busy} extra={extra} />
  const firstExitPlan = exitPlans[0]
  if (firstExitPlan) return <ExitPlanCard item={firstExitPlan} handlers={handlers} busy={busy} extra={extra} />
  // 提议排最后：它不阻塞运行，有真正卡住的东西时不该抢在最前面。
  const firstProposal = goalProposals[0]
  if (firstProposal) return <GoalProposalCard item={firstProposal} handlers={handlers} busy={busy} extra={extra} />
  return null
}
