import { describe, expect, test } from 'bun:test'
import { projectSdkMessageForPocket, slimAgentPayload, slimSdkMessagesPayload } from './sdk-message-slim'

const large = 'secret tool output '.repeat(1000)

describe('Pocket SDK 消息投影', () => {
  test('移除 thinking、工具入参和工具结果正文，保留正文与工具名', () => {
    const message = {
      type: 'assistant',
      uuid: 'a1',
      message: {
        role: 'assistant',
        content: [
          { type: 'thinking', thinking: large },
          { type: 'text', text: '最终答案' },
          { type: 'tool_use', id: 't1', name: 'Write', input: { content: large, file_path: '/tmp/a' } },
        ],
      },
    }
    const projected = projectSdkMessageForPocket(message as never) as never as { message: { content: Array<Record<string, unknown>> } }
    expect(projected.message.content).toEqual([
      { type: 'text', text: '最终答案' },
      { type: 'tool_use', id: 't1', name: 'Write', input: {} },
    ])
    expect(JSON.stringify(projected)).not.toContain(large)
  })

  test('工具结果只保留关联 ID 和错误状态', () => {
    const projected = projectSdkMessageForPocket({
      type: 'user',
      message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: large, is_error: true }] },
    } as never) as never as { message: { content: Array<Record<string, unknown>> } }
    expect(projected.message.content).toEqual([{ type: 'tool_result', tool_use_id: 't1', is_error: true }])
    expect(JSON.stringify(projected)).not.toContain(large)
  })

  test('事件和历史分页都走同一套投影', () => {
    const message = { type: 'assistant', message: { content: [{ type: 'thinking', thinking: large }] } }
    const event = slimAgentPayload({ kind: 'sdk_message', message }) as { message: { message: { content: unknown[] } } }
    const page = slimSdkMessagesPayload({ messages: [message], hasMore: false }) as { messages: Array<{ message: { content: unknown[] } }> }
    expect(event.message.message.content).toEqual([])
    expect(page.messages[0]!.message.content).toEqual([])
  })
})
