import { describe, expect, test } from 'bun:test'
import type { SDKContentBlock } from '@profer/shared'
import type { AssistantTurnRenderItem } from './ProcessBlockGroup'
import { applyRenderWindow, DEFAULT_RENDER_WINDOW } from './render-window'

const block = (text: string): SDKContentBlock => ({ type: 'text', text } as SDKContentBlock)
const item = (text: string, index: number): AssistantTurnRenderItem => ({
  type: 'block',
  item: { block: block(text), index },
})

function texts(items: AssistantTurnRenderItem[]): string[] {
  return items.flatMap((entry) => entry.type === 'block'
    ? [(entry.item.block as { text: string }).text]
    : entry.items.map((part) => (part.block as { text: string }).text))
}

describe('Pocket Agent render window', () => {
  test('keeps independent process and reply tails, retaining folded items in order', () => {
    const original: AssistantTurnRenderItem[] = [
      { type: 'process-group', items: Array.from({ length: 25 }, (_, index) => ({ block: block(`p${index}`), index })) },
      ...Array.from({ length: 35 }, (_, index) => item(`r${index}`, 25 + index)),
    ]
    const result = applyRenderWindow(original)

    expect(result.foldedProcessItems).toHaveLength(1)
    expect(result.foldedProcessItems[0]?.type === 'process-group' && result.foldedProcessItems[0].items).toHaveLength(5)
    expect(texts(result.items)).toEqual([
      ...Array.from({ length: 20 }, (_, index) => `p${index + 5}`),
      ...Array.from({ length: 30 }, (_, index) => `r${index + 5}`),
    ])
    expect(texts(result.foldedReplyItems)).toEqual(Array.from({ length: 5 }, (_, index) => `r${index}`))
    expect(DEFAULT_RENDER_WINDOW).toEqual({ processSegments: 20, replySegments: 30 })
  })

  test('does not fold turns within configured limits and preserves source arrays', () => {
    const original: AssistantTurnRenderItem[] = [
      { type: 'process-group', items: [{ block: block('p0'), index: 0 }] },
      item('r0', 1),
    ]
    const result = applyRenderWindow(original)

    expect(result.items).toEqual(original)
    expect(result.foldedProcessItems).toEqual([])
    expect(result.foldedReplyItems).toEqual([])
    expect(original).toHaveLength(2)
  })
})
