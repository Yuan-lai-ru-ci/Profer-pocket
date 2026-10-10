import { describe, expect, test } from 'bun:test'
import { POCKET_TOOL_TEXT_LIMIT, slimSdkMessage, slimSdkMessagesPayload, truncateToolText } from './sdk-message-slim'

const bigText = 'x'.repeat(POCKET_TOOL_TEXT_LIMIT + 500)

describe('移动端 SDK 消息瘦身', () => {
  test('超长 tool_result 文本被截断并保留预览与原始长度', () => {
    const msg = { type: 'user', uuid: 'u1', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: bigText }] } }
    const slim = slimSdkMessage(msg) as typeof msg
    const text = slim.message.content[0].content as string
    expect(text.startsWith('x'.repeat(POCKET_TOOL_TEXT_LIMIT))).toBe(true)
    expect(text).toContain(`原文 ${bigText.length} 字符`)
    expect(text.length).toBeLessThan(bigText.length)
  })

  test('tool_use 入参里的超长字符串被截断，结构字段保持不变', () => {
    const msg = { type: 'assistant', uuid: 'a1', message: { content: [{ type: 'tool_use', id: 't1', name: 'Write', input: { file_path: '/a', content: bigText } }] } }
    const slim = slimSdkMessage(msg) as typeof msg
    const block = slim.message.content[0] as { id: string; name: string; input: { file_path: string; content: string } }
    expect(block.id).toBe('t1')
    expect(block.name).toBe('Write')
    expect(block.input.file_path).toBe('/a')
    expect(block.input.content).toContain('已截断')
  })

  test('短内容和无 content 数组的消息保持原样', () => {
    const short = { type: 'user', uuid: 'u2', message: { content: [{ type: 'text', text: 'hi' }] } }
    expect(slimSdkMessage(short)).toEqual(short)
    const plain = { type: 'system', uuid: 's1' }
    expect(slimSdkMessage(plain)).toBe(plain)
  })

  test('不修改原始消息对象', () => {
    const msg = { type: 'user', uuid: 'u3', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: bigText }] } }
    slimSdkMessage(msg)
    expect(msg.message.content[0].content).toBe(bigText)
  })

  test('分页对象与纯数组两种响应形态都会被处理', () => {
    const msg = { type: 'user', uuid: 'u4', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: bigText }] } }
    const arr = slimSdkMessagesPayload([msg]) as Array<typeof msg>
    expect(arr[0].message.content[0].content as string).toContain('已截断')
    const page = slimSdkMessagesPayload({ messages: [msg], startIndex: 3, hasMore: true }) as { messages: Array<typeof msg>; startIndex: number; hasMore: boolean }
    expect(page.startIndex).toBe(3)
    expect(page.hasMore).toBe(true)
    expect(page.messages[0].message.content[0].content as string).toContain('已截断')
    expect(slimSdkMessagesPayload(null)).toBeNull()
  })

  test('truncateToolText 对边界长度不截断', () => {
    expect(truncateToolText('abc', 3)).toBe('abc')
    expect(truncateToolText('abcd', 3)).toContain('已截断')
  })
})
