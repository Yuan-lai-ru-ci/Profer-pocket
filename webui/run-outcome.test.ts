import { describe, expect, test } from 'bun:test'
import type { SDKMessage } from '@profer/shared'
import {
  deriveRunOutcomeFromHistory,
  normalizePersistenceEvent,
  normalizeRetryEvent,
  normalizeRunOutcomeEvent,
  runOutcomeTone,
} from './run-outcome'

const userInput = (createdAt: number): SDKMessage =>
  ({
    type: 'user',
    uuid: 'u1',
    _createdAt: createdAt,
    message: { content: [{ type: 'text', text: '改一下' }] },
  } as unknown as SDKMessage)

const interruption = (createdAt: number, message = '已被用户中断'): SDKMessage =>
  ({ type: 'system', subtype: 'interruption_record', message, _createdAt: createdAt } as unknown as SDKMessage)

const result = (subtype: string | undefined, createdAt: number): SDKMessage =>
  ({ type: 'result', ...(subtype ? { subtype } : {}), _createdAt: createdAt } as unknown as SDKMessage)

describe('运行结局：实时 run_completed', () => {
  test('Given 后台任务还在跑 When 解析 Then 不产出结局（这只是一次软空闲）', () => {
    expect(normalizeRunOutcomeEvent({ backgroundTasksPending: true, outcomeStatus: 'completed' })).toBeNull()
  })

  test('Given 正常完成且无错误 When 解析 Then 不显示结局行', () => {
    expect(normalizeRunOutcomeEvent({ outcomeStatus: 'completed', endReason: 'completed', endReasonLabel: '' })).toBeNull()
  })

  test('Given 用户停止 When 解析 Then 结局为「已被用户中断」且是中性色', () => {
    const outcome = normalizeRunOutcomeEvent({ outcomeStatus: 'stopped', stoppedByUser: true, endReason: 'stopped_by_user', endReasonLabel: '已被用户中断' }, 1000)
    expect(outcome).toMatchObject({ kind: 'stopped', label: '已被用户中断', at: 1000, source: 'event' })
    expect(runOutcomeTone(outcome!.kind)).toBe('muted')
  })

  test('Given 执行失败带多条错误 When 解析 Then 首条作为原因且保留错误条数', () => {
    const outcome = normalizeRunOutcomeEvent({
      outcomeStatus: 'failed',
      endReason: 'error',
      endReasonLabel: '执行出错',
      resultErrors: ['bad gateway', 'retry failed'],
    })
    expect(outcome).toMatchObject({ kind: 'failed', label: '执行出错', detail: 'bad gateway', detailCount: 2 })
    expect(runOutcomeTone(outcome!.kind)).toBe('error')
  })

  test('Given 达到轮次上限 When 解析 Then 结局为限额警告', () => {
    const outcome = normalizeRunOutcomeEvent({ outcomeStatus: 'failed', endReason: 'max_turns', endReasonLabel: '已中断：达到轮次上限' })
    expect(outcome).toMatchObject({ kind: 'limited', label: '已中断：达到轮次上限' })
    expect(runOutcomeTone(outcome!.kind)).toBe('warning')
  })

  test('Given 标记完成但 result 仍带错误 When 解析 Then 留一行失败提示（非致命错误不该被吞）', () => {
    const outcome = normalizeRunOutcomeEvent({ outcomeStatus: 'completed', resultErrors: ['ledger finish failed'] })
    expect(outcome).toMatchObject({ kind: 'failed', detail: 'ledger finish failed' })
  })

  test('Given 事件里没有任何结局信号 When 解析 Then 返回 null 而不是编造结局', () => {
    expect(normalizeRunOutcomeEvent({})).toBeNull()
  })
})

describe('重试与落盘告警：只处理页面真正要显示的状态', () => {
  test('Given retry starting When 解析 Then 给出次数与原因', () => {
    const transition = normalizeRetryEvent({ status: 'starting', attempt: 2, maxAttempts: 5, delaySeconds: 3, reason: 'rate limited' })
    expect(transition).toEqual({
      action: 'set',
      info: { attempt: 2, maxAttempts: 5, delaySeconds: 3, reason: 'rate limited' },
    })
  })

  test('Given retry cleared / failed When 解析 Then 收起提示（失败原因交给 run_completed）', () => {
    expect(normalizeRetryEvent({ status: 'cleared' })).toEqual({ action: 'clear' })
    expect(normalizeRetryEvent({ status: 'failed' })).toEqual({ action: 'clear' })
  })

  test('Given 未知 retry 状态 When 解析 Then 不改状态', () => {
    expect(normalizeRetryEvent({ status: 'weird' })).toBeNull()
  })

  test('Given session_persistence failed 没带文案 When 解析 Then 用兜底中文提示', () => {
    expect(normalizePersistenceEvent({ status: 'failed' })).toEqual({
      action: 'warn',
      warning: { message: '本轮记录未能保存到磁盘' },
    })
  })

  test('Given session_persistence saved When 解析 Then 清除告警', () => {
    expect(normalizePersistenceEvent({ status: 'saved' })).toEqual({ action: 'clear' })
  })
})

describe('运行结局：刷新后的历史推导', () => {
  test('Given meta 里的中断晚于最后一条用户输入 When 推导 Then 恢复结局', () => {
    const outcome = deriveRunOutcomeFromHistory([userInput(100)], {
      lastInterruptReason: 'error',
      lastInterruptLabel: '执行出错',
      lastInterruptAt: 120,
    })
    expect(outcome).toMatchObject({ kind: 'failed', label: '执行出错', at: 120, source: 'history' })
  })

  test('Given meta 里的中断早于最后一条用户输入 When 推导 Then 不当成本轮结局', () => {
    const outcome = deriveRunOutcomeFromHistory([userInput(500), result('success', 600)], {
      lastInterruptReason: 'stopped_by_user',
      lastInterruptAt: 100,
    })
    expect(outcome).toBeNull()
  })

  test('Given 消息里有本轮 interruption_record When 推导 Then 按文案恢复结局', () => {
    const outcome = deriveRunOutcomeFromHistory([userInput(100), interruption(200, '已中断：达到轮次上限')], null)
    expect(outcome).toMatchObject({ kind: 'limited', label: '已中断：达到轮次上限' })
  })

  test('Given 只有失败的 result When 推导 Then 按 subtype 恢复结局', () => {
    const outcome = deriveRunOutcomeFromHistory([userInput(100), result('error_during_execution', 200)], null)
    expect(outcome).toMatchObject({ kind: 'failed', label: '执行出错' })
  })

  test('Given 上一轮的失败 result 早于新用户输入 When 推导 Then 不残留旧结局', () => {
    const outcome = deriveRunOutcomeFromHistory([userInput(100), result('error_during_execution', 120), userInput(300)], null)
    expect(outcome).toBeNull()
  })

  test('Given 正常 success result When 推导 Then 不显示结局行', () => {
    expect(deriveRunOutcomeFromHistory([userInput(100), result('success', 200)], null)).toBeNull()
  })
})