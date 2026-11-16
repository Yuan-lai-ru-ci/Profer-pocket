import { describe, expect, test } from 'bun:test'
import type { MessageGroup } from '@profer/session-core'
import type { SDKMessage } from '@profer/shared'
import {
  findTurnIndexForMessage,
  normalizeSessionSearchMatches,
  splitSearchSnippet,
} from './session-search'

describe('全量搜索：服务端命中归一化', () => {
  test('Given 正常命中 When 归一化 Then 保留行号、身份、角色与命中位置', () => {
    const matches = normalizeSessionSearchMatches([
      { lineIndex: 12, messageId: 'm1', role: 'user', snippet: '...改一下这个函数...', matchStart: 6 },
    ])
    expect(matches).toEqual([{
      lineIndex: 12,
      messageId: 'm1',
      role: 'user',
      snippet: '...改一下这个函数...',
      matchStart: 6,
    }])
  })

  test('Given 缺行号 / 缺片段 / 行号为负 When 归一化 Then 丢弃坏项，不静默跳到错误位置', () => {
    const matches = normalizeSessionSearchMatches([
      { messageId: 'no-index', snippet: 'x', matchStart: 0 },
      { lineIndex: 1, snippet: '' },
      { lineIndex: -1, snippet: 'x' },
      null,
      'oops',
    ])
    expect(matches).toHaveLength(0)
  })

  test('Given 未知角色 When 归一化 Then 回退 assistant，不把系统消息伪装成用户', () => {
    const matches = normalizeSessionSearchMatches([{ lineIndex: 1, snippet: 'x', role: 'weird' }])
    expect(matches[0]!.role).toBe('assistant')
  })

  test('Given 命中数超过上限 When 归一化 Then 截断到上限', () => {
    const raw = Array.from({ length: 5 }, (_, index) => ({ lineIndex: index, snippet: 'x' }))
    expect(normalizeSessionSearchMatches(raw, 3)).toHaveLength(3)
  })
})

describe('全量搜索：片段高亮拆分', () => {
  test('Given 命中在片段中间 When 拆分 Then 三段拼回原文', () => {
    const parts = splitSearchSnippet('前面【改了这里】后面', 3, 5)
    expect(parts).toEqual({ before: '前面【', hit: '改了这里】', after: '后面' })
  })

  test('Given matchStart 越界 When 拆分 Then 收敛到片段末尾，不吞正文', () => {
    const parts = splitSearchSnippet('短片段', 999, 4)
    expect(parts.hit).toBe('')
    expect(parts.before).toBe('短片段')
  })

  test('Given 查询长度超过剩余片段 When 拆分 Then 命中段截到片段末尾', () => {
    const parts = splitSearchSnippet('abc', 1, 99)
    expect(parts).toEqual({ before: 'a', hit: 'bc', after: '' })
  })
})

describe('全量搜索：命中消息定位到回合', () => {
  const userGroup = (uuid: string): MessageGroup =>
    ({ type: 'user', message: { uuid, message: { content: [{ type: 'text', text: 'hi' }] } } as unknown as SDKMessage })
  const assistantGroup = (uuids: string[], innerId?: string): MessageGroup =>
    ({
      type: 'assistant-turn',
      assistantMessages: [],
      turnMessages: uuids.map((uuid) => ({ type: 'assistant', uuid, message: { id: innerId, content: [] } }) as unknown as SDKMessage),
    } as unknown as MessageGroup)

  test('Given 命中用户消息 When 定位 Then 返回对应用户回合下标', () => {
    const groups = [userGroup('u1'), assistantGroup(['a1']), userGroup('u2')]
    expect(findTurnIndexForMessage(groups, 'u1')).toBe(0)
    expect(findTurnIndexForMessage(groups, 'u2')).toBe(2)
  })

  test('Given 命中 assistant 的 message.id（不是顶层 uuid）When 定位 Then 也能找到回合', () => {
    const groups = [userGroup('u1'), assistantGroup(['a1'], 'inner-1')]
    expect(findTurnIndexForMessage(groups, 'inner-1')).toBe(1)
  })

  test('Given 消息不在当前窗口 / 没有 id When 定位 Then 返回 undefined（只换窗口不强跳）', () => {
    expect(findTurnIndexForMessage([userGroup('u1')], 'missing')).toBeUndefined()
    expect(findTurnIndexForMessage([userGroup('u1')], '')).toBeUndefined()
  })
})