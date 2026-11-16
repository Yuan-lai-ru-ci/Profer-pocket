import { describe, expect, test } from 'bun:test'
import type { MessageGroup } from '@profer/session-core'
import { toTranscript } from '@profer/session-core'
import { findPreviousAssistantUuid, resolveRetryTurn } from './retry-turn'

function user(content: string, extra: Record<string, unknown> = {}): MessageGroup {
  return {
    type: 'user',
    message: {
      type: 'user',
      uuid: `u-${content.slice(0, 8)}`,
      message: { role: 'user', content: [{ type: 'text', text: content }] },
      ...extra,
    },
  } as never as MessageGroup
}

function assistant(uuid: string): MessageGroup {
  const message = {
    type: 'assistant' as const,
    uuid,
    message: { role: 'assistant', content: [{ type: 'text', text: '失败的回复' }] },
  }
  return { type: 'assistant-turn', assistantMessages: [message], turnMessages: [message] } as never as MessageGroup
}

function resolve(groups: MessageGroup[]) {
  return resolveRetryTurn(groups, toTranscript(groups))
}

describe('失败轮重试目标', () => {
  test('Given 最后一轮失败 When 解析 Then 取最后一条用户正文并回退到它之前的助手消息', () => {
    const groups = [user('第一问'), assistant('a-1'), user('第二问'), assistant('a-2')]
    expect(resolve(groups)).toEqual({ text: '第二问', assistantUuid: 'a-1' })
  })

  test('Given 首轮就失败 When 解析 Then 没有锚点（回退到会话开头）', () => {
    const groups = [user('唯一的提问'), assistant('a-1')]
    expect(resolve(groups)).toEqual({ text: '唯一的提问' })
  })

  test('Given 最后一条用户消息带附件 When 解析 Then 不给重试（重发会丢文件）', () => {
    const groups = [
      user('第一问'), assistant('a-1'),
      user('看看这个文件\n\n<attached_files>\n- 报告.pdf: /repo/report.pdf\n</attached_files>'),
      assistant('a-2'),
    ]
    expect(resolve(groups)).toBeNull()
  })

  test('Given 最后一条用户消息带图片标记 When 解析 Then 不给重试（标记会被剥离）', () => {
    const groups = [user('看截图 [PROFER_IMAGE_ATTACHMENT:/repo/shot.png]'), assistant('a-1')]
    expect(resolve(groups)).toBeNull()
  })

  test('Given 最后一条是 /goal 客户端命令 When 解析 Then 不给重试', () => {
    const groups = [user('/goal 把测试补齐'), assistant('a-1')]
    expect(resolve(groups)).toBeNull()
  })

  test('Given 最后一条是 Goal 自动迭代注入 When 解析 Then 不当作用户发言', () => {
    const groups = [user('补齐测试'), assistant('a-1'), user('继续执行目标', { _goalIteration: 2 })]
    expect(resolve(groups)).toBeNull()
  })

  test('Given 最后一条只有上下文注入块 When 解析 Then 正文为空不给重试', () => {
    const groups = [user('<referenced_sessions>\n<s>另一个会话</s>\n</referenced_sessions>'), assistant('a-1')]
    expect(resolve(groups)).toBeNull()
  })

  test('Given 用户正文混着注入块 When 解析 Then 只重发用户自己写的那部分', () => {
    const groups = [
      user('按这个改\n\n<quoted_context>\n旧代码\n</quoted_context>'),
      assistant('a-1'),
    ]
    expect(resolve(groups)).toEqual({ text: '按这个改' })
  })

  test('Given 没有任何用户消息 When 解析 Then 返回 null', () => {
    expect(resolve([assistant('a-1')])).toBeNull()
    expect(resolve([])).toBeNull()
  })
})

describe('回退锚点', () => {
  test('Given 前一条助手回合有多条消息 When 找锚点 Then 取最后一条 assistant 消息', () => {
    const first = { type: 'assistant' as const, uuid: 'a-early', message: { content: [] } }
    const last = { type: 'assistant' as const, uuid: 'a-late', message: { content: [] } }
    const groups = [
      { type: 'assistant-turn', assistantMessages: [first, last], turnMessages: [first, last] },
      user('第二问'),
    ] as never as MessageGroup[]
    expect(findPreviousAssistantUuid(groups, 1)).toBe('a-late')
  })

  test('Given 更早回合的 assistant 没有 uuid When 找锚点 Then 继续向前找有 uuid 的', () => {
    const noUuid = { type: 'assistant' as const, message: { content: [] } }
    const withUuid = { type: 'assistant' as const, uuid: 'a-old', message: { content: [] } }
    const groups = [
      { type: 'assistant-turn', assistantMessages: [withUuid], turnMessages: [withUuid] },
      { type: 'assistant-turn', assistantMessages: [noUuid], turnMessages: [noUuid] },
      user('第三问'),
    ] as never as MessageGroup[]
    expect(findPreviousAssistantUuid(groups, 2)).toBe('a-old')
  })
})
