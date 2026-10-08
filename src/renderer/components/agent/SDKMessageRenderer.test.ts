import { describe, expect, test } from 'bun:test'
import type { SDKMessage } from '@profer/shared'
import { buildHistoricalTaskSubjects } from './SDKMessageRenderer'

function taskCreate(id: string, subject: string): SDKMessage {
  return {
    type: 'assistant',
    message: { content: [{ type: 'tool_use', id, name: 'TaskCreate', input: { subject } }] },
  } as unknown as SDKMessage
}

function toolResult(id: string, structured: unknown, content: unknown = 'unused large result'): SDKMessage {
  return {
    type: 'user',
    message: { content: [{ type: 'tool_result', tool_use_id: id, content }] },
    tool_use_result: structured,
  } as unknown as SDKMessage
}

describe('Pocket historical task mapping', () => {
  test('maps only matching TaskCreate results and prefers structured task fields', () => {
    const messages = [
      taskCreate('wanted', 'fallback'),
      toolResult('unrelated', { task: { id: 'wrong', subject: 'wrong' } }),
      toolResult('wanted', { task: { id: 42, subject: 'structured' } }),
    ]

    expect(buildHistoricalTaskSubjects(messages)).toEqual(new Map([['42', 'structured']]))
  })

  test('falls back to the input subject when the structured result has no subject', () => {
    const messages = [taskCreate('wanted', 'fallback'), toolResult('wanted', { task: { id: '7' } })]

    expect(buildHistoricalTaskSubjects(messages).get('7')).toBe('fallback')
  })
})
