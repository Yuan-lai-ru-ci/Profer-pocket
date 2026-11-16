/**
 * 上下文占用计算的行为测试（口径必须与桌面 ContextUsageBadge 一致）。
 */
import { describe, expect, test } from 'bun:test'
import type { SDKMessage } from '@profer/shared'
import { contextTokensFromUsage, describeContextUsage, formatTokens, latestContextTokens } from './context-usage'

const assistant = (usage: unknown, parentToolUseId: string | null = null): SDKMessage => ({
  type: 'assistant',
  parent_tool_use_id: parentToolUseId,
  message: { content: [], usage },
} as unknown as SDKMessage)

describe('上下文 token 汇算（口径同桌面）', () => {
  test('Given 缓存命中的 usage When 汇算 Then 未缓存输入 + 缓存读取 + 缓存写入一起算', () => {
    expect(contextTokensFromUsage({
      input_tokens: 247,
      cache_read_input_tokens: 137_984,
      cache_creation_input_tokens: 500,
    })).toBe(138_731)
  })

  test('Given 没有缓存字段 When 汇算 Then 只算 input_tokens', () => {
    expect(contextTokensFromUsage({ input_tokens: 1_200 })).toBe(1_200)
  })

  test('Given 缺字段或脏值 When 汇算 Then 按 0 处理，不返回 NaN', () => {
    expect(contextTokensFromUsage(undefined)).toBe(0)
    expect(contextTokensFromUsage(null)).toBe(0)
    expect(contextTokensFromUsage({ input_tokens: 'x', cache_read_input_tokens: Number.NaN })).toBe(0)
  })
})

describe('当前会话上下文已用', () => {
  test('Given 最后一条 assistant 命中缓存 When 取已用 Then 含缓存读取', () => {
    const messages = [
      assistant({ input_tokens: 100, cache_read_input_tokens: 10_000 }, null),
      { type: 'user', parent_tool_use_id: null, message: { content: [] } } as unknown as SDKMessage,
      assistant({ input_tokens: 247, cache_read_input_tokens: 137_984 }, null),
    ]
    expect(latestContextTokens(messages)).toBe(138_231)
  })

  test('Given 末尾是子代理消息 When 取已用 Then 跳过子代理，用父会话的值', () => {
    const messages = [
      assistant({ input_tokens: 500, cache_read_input_tokens: 100_000 }, null),
      assistant({ input_tokens: 900, cache_read_input_tokens: 0 }, 'toolu-sub'),
    ]
    expect(latestContextTokens(messages)).toBe(100_500)
  })

  test('Given 最后一条没有 usage When 取已用 Then 回退到上一条有值的消息', () => {
    const messages = [
      assistant({ input_tokens: 400, cache_read_input_tokens: 20_000 }, null),
      assistant(undefined, null),
    ]
    expect(latestContextTokens(messages)).toBe(20_400)
  })

  test('Given 完全没有可用 usage When 取已用 Then 返回 0', () => {
    expect(latestContextTokens([])).toBe(0)
    expect(latestContextTokens([assistant(undefined, null)])).toBe(0)
  })
})

describe('上下文占用计算', () => {
  test('Given 已用 120k / 窗口 200k When 计算 Then 60%，未警告（阈值 155k 的 80% = 124k）', () => {
    const info = describeContextUsage(120_000, 200_000)
    expect(info.percent).toBe(60)
    expect(info.warning).toBe(false)
    expect(info.used).toBe(120_000)
  })

  test('Given 已用达到压缩阈值的 80% When 计算 Then 警告（200k 窗口：155k 阈值 × 0.8 = 124k）', () => {
    expect(describeContextUsage(124_000, 200_000).warning).toBe(true)
    expect(describeContextUsage(130_000, 200_000).warning).toBe(true)
  })

  test('Given 没有窗口大小或没有用量 When 计算 Then percent 为 null', () => {
    expect(describeContextUsage(0, 200_000).percent).toBeNull()
    expect(describeContextUsage(120_000, 0).percent).toBeNull()
    expect(describeContextUsage(-5, 200_000).percent).toBeNull()
  })

  test('Given 超过窗口 When 计算 Then 封顶 100%', () => {
    expect(describeContextUsage(250_000, 200_000).percent).toBe(100)
  })

  test('Given tokens 数量 When 格式化 Then 用 k/m 缩写', () => {
    expect(formatTokens(999)).toBe('999')
    expect(formatTokens(12_300)).toBe('12.3k')
    expect(formatTokens(1_200_000)).toBe('1.2m')
    expect(formatTokens(1_000)).toBe('1k')
  })
})
