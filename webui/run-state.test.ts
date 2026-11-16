/**
 * 运行态错误分类的行为测试。
 *
 * 这三条分支决定「发一条消息」的最终走向，判错一次就是：要么把可恢复状态当失败弹红字，
 * 要么在 Pi 上无限重试一个不可能成功的排队。
 */
import { describe, expect, test } from 'bun:test'
import { errorCodeOf, isQueueUnsupportedError, isRunActiveError } from './run-state'

/** 模拟 ws-client：把服务端 code 挂在 Error 上 */
function withCode(message: string, code?: string): Error {
  const error = new Error(message)
  if (code) Object.assign(error, { code })
  return error
}

describe('运行态错误分类', () => {
  test('Given 带 code 的 claim 拒绝 When 判断 Then 识别为运行中', () => {
    expect(isRunActiveError(withCode('上一条消息仍在处理中', 'AGENT_RUN_ALREADY_ACTIVE'))).toBe(true)
  })

  test('Given 只有文案（旧服务端） When 判断 Then 同样识别为运行中', () => {
    expect(isRunActiveError(new Error('AGENT_RUN_ALREADY_ACTIVE: 上一条消息仍在处理中，请稍候再试'))).toBe(true)
  })

  test('Given 排队不受支持 When 判断 Then 识别为 QUEUE_UNSUPPORTED（含 code 与文案两条路）', () => {
    expect(isQueueUnsupportedError(withCode('当前活跃 Agent runtime 不支持追加消息', 'QUEUE_UNSUPPORTED'))).toBe(true)
    expect(isQueueUnsupportedError(new Error('当前活跃 Agent runtime 不支持追加消息'))).toBe(true)
    expect(isQueueUnsupportedError(new Error('[Agent 编排] 当前适配器不支持流式追加消息'))).toBe(true)
  })

  test('Given 普通业务错误 When 判断 Then 两类都不命中（按普通失败提示）', () => {
    const error = withCode('会话不存在', 'NOT_FOUND')
    expect(isRunActiveError(error)).toBe(false)
    expect(isQueueUnsupportedError(error)).toBe(false)
  })

  test('Given 非 Error 输入 When 取 code Then 不崩溃且返回 undefined', () => {
    for (const value of [undefined, null, 'x', 42, { code: 1 }]) {
      expect(errorCodeOf(value)).toBeUndefined()
    }
  })
})
