import { describe, expect, test } from 'bun:test'
import type { MessageGroup } from '@profer/session-core'
import {
  describeUsage,
  describeUsageDetail,
  formatClock,
  formatTokens,
  resolveTurnModel,
  resolveTurnSource,
  resolveTurnTime,
  sumTurnUsage,
  totalInput,
} from './turn-meta'

function assistantTurn(
  usages: Array<{ input: number; output: number; read?: number; create?: number; id?: string }>,
  createdAt = 1794558663188,
): MessageGroup {
  const messages = usages.map((usage, index) => ({
    type: 'assistant' as const,
    uuid: `a${index}`,
    message: {
      role: 'assistant',
      // 同一 message.id = 同一条消息的多份流式快照
      ...(usage.id ? { id: usage.id } : {}),
      content: [{ type: 'text', text: '好了' }],
      usage: {
        input_tokens: usage.input,
        output_tokens: usage.output,
        cache_read_input_tokens: usage.read ?? 0,
        cache_creation_input_tokens: usage.create ?? 0,
      },
    },
  }))
  return { type: 'assistant-turn', assistantMessages: messages, turnMessages: messages, createdAt } as never as MessageGroup
}

function userTurn(options: { at?: number; clientId?: string; authorType?: string }): MessageGroup {
  return {
    type: 'user',
    message: {
      type: 'user',
      uuid: 'u1',
      ...(options.at ? { _createdAt: options.at } : {}),
      ...(options.clientId ? { _clientId: options.clientId } : {}),
      ...(options.authorType ? { _provenance: { schemaVersion: 1, author: { type: options.authorType } } } : {}),
      message: { content: [{ type: 'text', text: '看下这个' }] },
    },
  } as never as MessageGroup
}

describe('token 消耗', () => {
  test('Given 一轮里有多次 assistant 调用 When 汇总 Then 输入含缓存读写，输出单列', () => {
    const usage = sumTurnUsage(assistantTurn([
      { input: 1000, output: 200, read: 2000 },
      { input: 1000, output: 100 },
    ]))
    expect(usage).toEqual({ input: 2000, output: 300, cacheRead: 2000, cacheCreate: 0 })
    expect(totalInput(usage!)).toBe(4000)
    expect(describeUsage(usage!)).toBe('↑4k ↓300')
  })

  test('Given 同一条消息的多份流式快照 When 汇总 Then 按 message.id 去重，只取最后一份', () => {
    const usage = sumTurnUsage(assistantTurn([
      { input: 1000, output: 50, id: 'msg_1' },
      { input: 1000, output: 120, id: 'msg_1' },
      // 最后一份最完整：不能取第一份，否则流式期间会少算输出
      { input: 1000, output: 200, id: 'msg_1' },
    ]))
    expect(usage).toEqual({ input: 1000, output: 200, cacheRead: 0, cacheCreate: 0 })
  })

  test('Given 没有任何 usage When 汇总 Then 返回 null（界面上不显示 0）', () => {
    const empty = {
      type: 'assistant-turn',
      assistantMessages: [{ type: 'assistant', uuid: 'a', message: { content: [] } }],
      turnMessages: [],
    } as never as MessageGroup
    expect(sumTurnUsage(empty)).toBeNull()
    expect(sumTurnUsage(undefined)).toBeNull()
  })

  test('Given 用户回合 When 汇总 Then 没有 token 概念', () => {
    expect(sumTurnUsage(userTurn({ at: 1 }))).toBeNull()
  })

  test('Given 全为 0 的 usage When 汇总 Then 当作没有（本地模型常见）', () => {
    expect(sumTurnUsage(assistantTurn([{ input: 0, output: 0 }]))).toBeNull()
  })

  test('Given 有缓存 When 明细 Then 单独列出命中与写入', () => {
    const usage = sumTurnUsage(assistantTurn([{ input: 900, output: 100, read: 4000, create: 100 }]))!
    expect(describeUsageDetail(usage)).toContain('缓存命中 4,000')
    expect(describeUsageDetail(usage)).toContain('缓存写入 100')
  })
})

describe('来源客户端', () => {
  test('Given 网页发的 When 判定 Then 网页', () => {
    expect(resolveTurnSource(userTurn({ clientId: 'webui', authorType: 'user' }))).toBe('web')
  })

  test('Given 其它远程端发的 When 判定 Then 远程（不冒充网页）', () => {
    expect(resolveTurnSource(userTurn({ clientId: 'remote', authorType: 'user' }))).toBe('remote')
  })

  test('Given 桌面端发的 When 判定 Then 软件', () => {
    expect(resolveTurnSource(userTurn({ authorType: 'user' }))).toBe('desktop')
  })

  test('Given 定时任务 When 判定 Then 定时，不标成软件', () => {
    expect(resolveTurnSource(userTurn({ authorType: 'automation' }))).toBe('automation')
  })

  test('Given 老消息没有来源信息 When 判定 Then null（宁可不显示也不猜）', () => {
    expect(resolveTurnSource(userTurn({}))).toBeNull()
    expect(resolveTurnSource(undefined)).toBeNull()
    expect(resolveTurnSource(assistantTurn([{ input: 1, output: 1 }]))).toBeNull()
  })
})

describe('时间与格式', () => {
  test('Given 当天消息 When 格式化 Then 只给时分', () => {
    const at = new Date(2026, 10, 13, 9, 5).getTime()
    expect(formatClock(at, new Date(2026, 10, 13, 18, 0).getTime())).toBe('09:05')
  })

  test('Given 跨天消息 When 格式化 Then 补月日', () => {
    const at = new Date(2026, 10, 9, 22, 30).getTime()
    expect(formatClock(at, new Date(2026, 10, 13, 18, 0).getTime())).toBe('11-09 22:30')
  })

  test('Given 没有时间戳 When 格式化 Then 空串（不显示 1970）', () => {
    expect(formatClock(undefined)).toBe('')
    expect(formatClock(0)).toBe('')
  })

  test('Given 用户回合 When 取时间 Then 用消息自带的 _createdAt', () => {
    expect(resolveTurnTime(userTurn({ at: 1794558663188 }))).toBe(1794558663188)
  })

  test('Given 助手回合 When 取时间 Then 用分组创建时间', () => {
    expect(resolveTurnTime(assistantTurn([{ input: 1, output: 1 }], 1794558596454))).toBe(1794558596454)
  })

  test('Given 助手回合带模型 When 取名 Then 返回模型标识（用户回合没有）', () => {
    const group = assistantTurn([{ input: 1, output: 1 }]) as never as { model?: string }
    group.model = 'claude-sonnet-4-5'
    expect(resolveTurnModel(group as never)).toBe('claude-sonnet-4-5')
    expect(resolveTurnModel(userTurn({ at: 1 }))).toBeUndefined()
  })

  test('Given 模型字段为空 When 取名 Then undefined（界面不渲染空 tag）', () => {
    const group = assistantTurn([{ input: 1, output: 1 }]) as never as { model?: string }
    group.model = '   '
    expect(resolveTurnModel(group as never)).toBeUndefined()
  })

  test('Given 各种量级 When 格式化 token Then 千位以下直出、上万折 k', () => {
    expect(formatTokens(0)).toBe('0')
    expect(formatTokens(999)).toBe('999')
    expect(formatTokens(1200)).toBe('1.2k')
    expect(formatTokens(14500)).toBe('15k')
    expect(formatTokens(1000000)).toBe('1M')
  })
})
