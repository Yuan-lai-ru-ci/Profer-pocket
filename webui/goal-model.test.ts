import { describe, expect, test } from 'bun:test'
import {
  budgetExhaustedReasons,
  formatGoalBudget,
  formatGoalElapsed,
  goalActionState,
  goalElapsed,
  goalLimitsFormValue,
  goalReasonText,
  goalStatusLabel,
  normalizeGoal,
  normalizeGoalHistory,
  goalStatusText,
  GOAL_STATUSES,
  parseGoalLimitsInput,
  type GoalState,
} from './goal-model'

/** 一个「跑完一轮、暂停」的 Goal，用例在此基础上改字段。 */
function goalOf(overrides: Partial<GoalState> = {}): GoalState {
  const base: GoalState = {
    id: 'g1',
    goal: '把登录页测试补到全绿',
    status: 'paused',
    iteration: 3,
    consecutiveFailures: 0,
    startedAt: 1794558000000,
    updatedAt: 1794558600000,
    limits: { maxConsecutiveFailures: 3 },
  }
  return { ...base, ...overrides }
}

describe('归一化', () => {
  test('Given 完整状态 When 归一化 Then 契约、预算、用量、历史都保留', () => {
    const goal = normalizeGoal({
      id: 'g1', goal: '补测试', status: 'active', iteration: 2, consecutiveFailures: 1,
      startedAt: 1, updatedAt: 2, activeRunId: 'run-1', elapsedMs: 65_000,
      contract: { verification: 'pnpm test 全绿', constraints: '不动支付代码', stopWhen: '需要线上凭据' },
      limits: { maxIterations: 10, maxDurationMs: 1_800_000, maxConsecutiveFailures: 3, maxTokens: 2_000_000 },
      usage: { inputTokens: 1000, outputTokens: 200, totalTokens: 1200 },
      lastSummary: '补了 3 个用例', lastEvidence: ['pytest 12 passed', ''],
      history: [{ iteration: 2, status: 'complete', outcome: 'advanced', startedAt: 10, finishedAt: 20, summary: '一轮', evidence: ['x'] }],
      lifecycle: [{ id: 'e1', from: 'paused', to: 'active', at: 5, reason: 'user_resume' }],
    })
    expect(goal?.status).toBe('active')
    expect(goal?.contract?.verification).toBe('pnpm test 全绿')
    expect(goal?.limits.maxTokens).toBe(2_000_000)
    expect(goal?.usage?.totalTokens).toBe(1200)
    expect(goal?.lastEvidence).toEqual(['pytest 12 passed'])
    expect(goal?.history?.[0]?.summary).toBe('一轮')
    expect(goal?.lifecycle?.[0]?.from).toBe('paused')
  })

  test('Given 未知状态 When 归一化 Then 返回 null（宁可不显示也不猜）', () => {
    expect(normalizeGoal({ id: 'g', goal: 'x', status: 'teleporting' })).toBeNull()
  })

  test('Given 缺 id 或目标 When 归一化 Then 返回 null', () => {
    expect(normalizeGoal({ goal: 'x', status: 'active' })).toBeNull()
    expect(normalizeGoal({ id: 'g', status: 'active' })).toBeNull()
    expect(normalizeGoal(null)).toBeNull()
  })

  test('Given 只有输入输出没有 total When 归一化 Then 自行相加', () => {
    const goal = normalizeGoal({ id: 'g', goal: 'x', status: 'active', usage: { inputTokens: 30, outputTokens: 12 } })
    expect(goal?.usage).toEqual({ inputTokens: 30, outputTokens: 12, totalTokens: 42 })
  })

  test('Given 空契约 When 归一化 Then 视为没有契约（不渲染三行空字段）', () => {
    const goal = normalizeGoal({ id: 'g', goal: 'x', status: 'active', contract: { verification: '  ' } })
    expect(goal?.contract).toBeUndefined()
  })

  test('Given 归档列表 When 归一化 Then 丢掉无法识别的条目', () => {
    const goals = normalizeGoalHistory([
      { id: 'a', goal: '一', status: 'completed' },
      { id: 'b', goal: '二', status: 'nope' },
      { nope: true },
    ])
    expect(goals.map((item) => item.id)).toEqual(['a'])
  })
})

describe('可执行动作', () => {
  test('Given 运行中 When 判定 Then 只能暂停/停止，不能编辑或清除', () => {
    const actions = goalActionState(goalOf({ status: 'active', activeRunId: 'run-1' }))
    expect(actions).toMatchObject({ canPause: true, canStop: true, canEdit: false, canResume: false, canClear: false })
  })

  test('Given owner 还占着 run（状态已 stopped） When 判定 Then 仍按运行中处理', () => {
    const actions = goalActionState(goalOf({ status: 'stopped', activeRunId: 'run-1' }))
    expect(actions.canEdit).toBe(false)
    expect(actions.canClear).toBe(false)
  })

  test('Given 已暂停 When 判定 Then 可以继续、编辑、清除', () => {
    const actions = goalActionState(goalOf({ status: 'paused' }))
    expect(actions).toMatchObject({ canPause: false, canEdit: true, canResume: true, canClear: true })
  })

  test('Given 预算耗尽 When 判定 Then 不能直接继续（必须先扩预算）', () => {
    const actions = goalActionState(goalOf({ status: 'budget_limited', iteration: 10, limits: { maxIterations: 10, maxConsecutiveFailures: 3 } }))
    expect(actions.budgetEditRequired).toBe(true)
    expect(actions.canResume).toBe(false)
    expect(actions.canEdit).toBe(true)
  })

  test('Given 已完成 When 判定 Then 只剩清除', () => {
    const actions = goalActionState(goalOf({ status: 'completed' }))
    expect(actions.canStop).toBe(false)
    expect(actions.canClear).toBe(true)
  })
})

describe('预算与耗时', () => {
  test('Given 三个维度都设了且都到顶 When 判定 Then 三条原因都给（与桌面同文案）', () => {
    const reasons = budgetExhaustedReasons(goalOf({
      iteration: 5,
      elapsedMs: 600_000,
      usage: { inputTokens: 900, outputTokens: 100, totalTokens: 1000 },
      limits: { maxIterations: 5, maxDurationMs: 600_000, maxTokens: 1000, maxConsecutiveFailures: 3 },
    }))
    expect(reasons).toEqual(['已达到轮次上限', '已达到净运行时长上限', '已达到 Token 上限'])
  })

  test('Given 预算未设 When 判定 Then 不报耗尽（不限不是耗尽）', () => {
    expect(budgetExhaustedReasons(goalOf({ iteration: 99 }))).toEqual([])
  })

  test('Given 正在跑 When 取耗时 Then 把距上次更新的时间也算上，计时不会停住', () => {
    const goal = goalOf({ status: 'active', activeRunId: 'run-1', elapsedMs: 10_000, updatedAt: 1_000_000 })
    expect(goalElapsed(goal, 1_030_000)).toBe(40_000)
    expect(goalElapsed(goalOf({ elapsedMs: 10_000 }), 9_999_999)).toBe(10_000)
  })

  test('Given 各量级 When 格式化耗时 Then 秒/分/时逐级收敛', () => {
    expect(formatGoalElapsed(9_000)).toBe('9s')
    expect(formatGoalElapsed(65_000)).toBe('1m5s')
    expect(formatGoalElapsed(3_720_000)).toBe('1h2m')
  })

  test('Given 有预算 When 格式化 Then 三项并列并附无进展兜底', () => {
    expect(formatGoalBudget({ maxIterations: 10, maxDurationMs: 90_000, maxTokens: 5000, maxConsecutiveFailures: 3 }))
      .toBe('10 轮 · 1m30s · 5,000 tokens（连续 3 轮无进展时停止）')
    expect(formatGoalBudget({ maxConsecutiveFailures: 3 })).toBe('不限（连续 3 轮无进展时停止）')
  })
})

describe('状态文案', () => {
  const now = 1_000_000
  test('Given active 且有提问/授权 When 取文案 Then 分别提示在等我（不是「执行中」）', () => {
    expect(goalStatusLabel(goalOf({ status: 'active' }), { askUser: 1, permission: 0 }, now)).toBe('等待你的回答')
    expect(goalStatusLabel(goalOf({ status: 'active' }), { askUser: 0, permission: 2 }, now)).toBe('等待审批')
  })

  test('Given 计划模式 When 取文案 Then 说明切换后会自动继续', () => {
    expect(goalStatusLabel(goalOf({ status: 'active', waiting: 'plan_mode' }), { askUser: 0, permission: 0 }, now))
      .toContain('计划模式')
  })

  test('Given 出错重试 When 取文案 Then 带倒计时与第几次', () => {
    const label = goalStatusLabel(goalOf({ status: 'active', retry: { attempt: 2, nextAt: now + 5_000 } }), { askUser: 0, permission: 0 }, now)
    expect(label).toBe('运行出错，5s后第 2 次重试')
  })

  test('Given 空闲自检 When 取文案 Then 带倒计时，暂停时提示发消息继续', () => {
    expect(goalStatusLabel(goalOf({ status: 'active', idleCheckin: { nextAt: now + 60_000, count: 1 } }), { askUser: 0, permission: 0 }, now))
      .toBe('空闲中，1m后自动继续（第 2 次）')
    expect(goalStatusLabel(goalOf({ status: 'active', idleCheckin: { count: 3, paused: true } }), { askUser: 0, permission: 0 }, now))
      .toContain('空闲自检已暂停')
  })

  test('Given 受阻或被打断 When 取文案 Then 说明发消息即可继续', () => {
    expect(goalStatusLabel(goalOf({ status: 'blocked' }), { askUser: 0, permission: 0 }, now)).toBe('等待处理 · 发消息补充后自动继续')
    expect(goalStatusLabel(goalOf({ status: 'paused', reasonCode: 'user_interrupt' }), { askUser: 0, permission: 0 }, now)).toBe('已暂停 · 发消息即可继续')
  })

  test('Given 重启导致暂停 When 取文案 Then 标明待恢复（发消息不会自动续）', () => {
    expect(goalStatusLabel(goalOf({ status: 'paused', reasonCode: 'app_restart' }), { askUser: 0, permission: 0 }, now)).toBe('已暂停 · 重启后待恢复')
  })

  test('Given 旧 stopReason When 取原因文案 Then 归一成新原因', () => {
    expect(goalReasonText(goalOf({ stopReason: 'max_iterations' }))).toBe('预算已耗尽')
    expect(goalReasonText(goalOf({ reasonCode: 'blocked', reasonDetail: '缺少线上凭据' }))).toBe('需要你的输入：缺少线上凭据')
  })
})

describe('预算表单', () => {
  test('Given 留空 When 解析 Then 翻译成 null（不限），连续无进展必填', () => {
    const result = parseGoalLimitsInput({ maxIterations: '', maxDurationMinutes: '', maxConsecutiveFailures: '3', maxTokens: '' })
    expect(result.error).toBeUndefined()
    expect(result.limits).toEqual({ maxIterations: null, maxDurationMs: null, maxTokens: null, maxConsecutiveFailures: 3 })
  })

  test('Given 正常填写 When 解析 Then 分钟换算成毫秒', () => {
    const result = parseGoalLimitsInput({ maxIterations: '10', maxDurationMinutes: '2.5', maxConsecutiveFailures: '4', maxTokens: '500000' })
    expect(result.limits).toEqual({ maxIterations: 10, maxDurationMs: 150_000, maxTokens: 500_000, maxConsecutiveFailures: 4 })
  })

  test('Given 非法输入 When 解析 Then 给出可读错误而不是发一个坏补丁', () => {
    expect(parseGoalLimitsInput({ maxIterations: '-1', maxDurationMinutes: '', maxConsecutiveFailures: '3', maxTokens: '' }).error).toContain('轮次')
    expect(parseGoalLimitsInput({ maxIterations: '', maxDurationMinutes: 'abc', maxConsecutiveFailures: '3', maxTokens: '' }).error).toContain('时长')
    expect(parseGoalLimitsInput({ maxIterations: '', maxDurationMinutes: '', maxConsecutiveFailures: '0', maxTokens: '' }).error).toContain('连续无进展')
    expect(parseGoalLimitsInput({ maxIterations: '1.5', maxDurationMinutes: '', maxConsecutiveFailures: '3', maxTokens: '' }).error).toContain('轮次')
  })

  test('Given 当前预算 When 回填表单 Then 毫秒还原成分钟', () => {
    expect(goalLimitsFormValue({ maxIterations: 10, maxDurationMs: 90_000, maxTokens: 5000, maxConsecutiveFailures: 3 }))
      .toEqual({ maxIterations: '10', maxDurationMinutes: '1.5', maxConsecutiveFailures: '3', maxTokens: '5000' })
    expect(goalLimitsFormValue({})).toEqual({ maxIterations: '', maxDurationMinutes: '', maxConsecutiveFailures: '3', maxTokens: '' })
  })
})

describe('状态覆盖', () => {
  test('Given 每个稳定状态 When 取动作 Then 至少有一个可用按钮（不存在卡死状态）', () => {
    // stopping 是唯一例外：它本来就是一个几秒内自己走完的过渡态，
    // 这时给任何按钮都会和「正在停止」的真实状态打架（桌面端此时也不给按钮）。
    for (const status of GOAL_STATUSES.filter((item) => item !== 'stopping')) {
      const actions = goalActionState(goalOf({ status }))
      const usable = actions.canPause || actions.canStop || actions.canEdit || actions.canResume || actions.canClear
      expect(usable).toBe(true)
    }
  })

  test('Given 每个已知状态 When 取短文案 Then 都是非空中文，不暴露内部枚举', () => {
    for (const status of GOAL_STATUSES) {
      const label = goalStatusText(status)
      expect(label.length).toBeGreaterThan(0)
      expect(label).not.toBe(status)
    }
  })
})
