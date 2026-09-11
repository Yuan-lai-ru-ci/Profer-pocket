import { describe, expect, test } from 'bun:test'
import { buildAssistantTurnRenderItems, buildProcessGroupToolNames, shouldDefaultExpandProcessGroup } from './ProcessBlockGroup'
import type { SDKContentBlock } from '@profer/shared'

const tool = (id: string, name = 'Read'): SDKContentBlock => ({
  type: 'tool_use',
  id,
  name,
  input: {},
})

const thinking = (text = '分析中'): SDKContentBlock => ({
  type: 'thinking',
  thinking: text,
})

const text = (value: string): SDKContentBlock => ({
  type: 'text',
  text: value,
})

describe('Agent 过程块折叠分组', () => {
  test('given continuous thinking and tools before final text when grouping then folds them into one process group', () => {
    const items = buildAssistantTurnRenderItems([
      thinking(),
      tool('tool-1'),
      tool('tool-2'),
      text('最终输出'),
    ])

    expect(items).toHaveLength(2)
    expect(items[0]?.type).toBe('process-group')
    expect(items[1]?.type).toBe('block')
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1, 2])
    }
  })

  test('given intermediate text between tool runs when grouping then keeps only final output outside process group', () => {
    const items = buildAssistantTurnRenderItems([
      tool('tool-1'),
      text('中间说明'),
      tool('tool-2'),
      text('最终输出'),
    ])

    expect(items.map((item) => item.type)).toEqual(['process-group', 'block'])
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1, 2])
    }
    if (items[1]?.type === 'block') {
      expect(items[1].item.index).toBe(3)
    }
  })

  test('given streaming turn with trailing text when grouping then keeps the whole turn inside process group', () => {
    const items = buildAssistantTurnRenderItems([
      tool('tool-1'),
      text('可能还是中间说明'),
    ], { isStreaming: true })

    expect(items).toHaveLength(1)
    expect(items[0]?.type).toBe('process-group')
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1])
    }
  })

  test('given streaming turn with completed tools before trailing text when grouping then keeps final output outside process group', () => {
    const items = buildAssistantTurnRenderItems([
      tool('tool-1'),
      text('最终输出'),
    ], { isStreaming: true, completedToolResultIds: new Set(['tool-1']) })

    expect(items.map((item) => item.type)).toEqual(['process-group', 'block'])
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0])
    }
    if (items[1]?.type === 'block') {
      expect(items[1].item.index).toBe(1)
    }
  })

  test('given keep expanded after complete when grouping then still keeps final output outside process group', () => {
    const items = buildAssistantTurnRenderItems([
      tool('tool-1'),
      text('最终输出'),
    ])

    expect(items.map((item) => item.type)).toEqual(['process-group', 'block'])
    expect(items[0]?.type).toBe('process-group')
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0])
    }
  })

  test('given pure text streaming turn when grouping then keeps text as normal output', () => {
    const items = buildAssistantTurnRenderItems([
      text('普通回答'),
    ], { isStreaming: true })

    expect(items).toHaveLength(1)
    expect(items[0]?.type).toBe('block')
  })

  test('given process only turn when grouping then folds the whole turn', () => {
    const items = buildAssistantTurnRenderItems([
      thinking(),
      tool('tool-1'),
    ])

    expect(items).toHaveLength(1)
    expect(items[0]?.type).toBe('process-group')
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1])
    }
  })

  test('given streaming turn with only thinking before trailing text when grouping then keeps the whole turn inside process group', () => {
    // 仅有 thinking + 尾部 text 时，工具调用可能稍后才出现，
    // 不应把这段尾部 text 提前外置——避免后续完成瞬间从外部又跳回过程组。
    const items = buildAssistantTurnRenderItems([
      thinking(),
      text('暂时的回答片段'),
    ], { isStreaming: true, completedToolResultIds: new Set() })

    expect(items).toHaveLength(1)
    expect(items[0]?.type).toBe('process-group')
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1])
    }
  })

  test('given streaming multi-tool turn with only the last tool result pending when grouping then keeps final text outside process group', () => {
    // 修复「最终回复被折叠」：多工具长序列中最后一个工具结果晚到时，
    // 末尾 text 几乎可确定是最终回复，应外置而非整组折叠。
    const items = buildAssistantTurnRenderItems([
      tool('tool-1'),
      tool('tool-2'),
      tool('tool-3'),
      text('最终回复'),
    ], { isStreaming: true, completedToolResultIds: new Set(['tool-1', 'tool-2']) })

    expect(items.map((item) => item.type)).toEqual(['process-group', 'block'])
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1, 2])
    }
    if (items[1]?.type === 'block') {
      expect(items[1].item.index).toBe(3)
    }
  })

  test('given streaming multi-tool turn with a non-last tool result pending when grouping then keeps the whole turn inside process group', () => {
    // 中间工具未完成：末尾 text 仍可能是给后续工具看的中间说明，保持折叠。
    const items = buildAssistantTurnRenderItems([
      tool('tool-1'),
      tool('tool-2'),
      text('可能的中间说明'),
    ], { isStreaming: true, completedToolResultIds: new Set(['tool-2']) })

    expect(items).toHaveLength(1)
    expect(items[0]?.type).toBe('process-group')
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1, 2])
    }
  })

  test('given repeated tools when building capability icons then returns unique tool names in order', () => {
    const toolNames = buildProcessGroupToolNames([
      tool('tool-1', 'Grep'),
      thinking(),
      tool('tool-2', 'Read'),
      tool('tool-3', 'Grep'),
      tool('tool-4', 'Bash'),
    ])

    expect(toolNames).toEqual(['Grep', 'Read', 'Bash'])
  })
})

describe('R10 强制刷新后的过程分组默认展开判定', () => {
  const allInGroup = buildAssistantTurnRenderItems([
    thinking(),
    tool('tool-1'),
    tool('tool-2'),
  ])

  test('given 整轮都在过程分组（无外置回复）when forceReload then 默认展开', () => {
    expect(allInGroup).toHaveLength(1)
    expect(shouldDefaultExpandProcessGroup(allInGroup, 0, { forceReload: true })).toBe(true)
  })

  test('given 无 forceReload then 一律不默认展开（桌面/普通路径行为不变）', () => {
    expect(shouldDefaultExpandProcessGroup(allInGroup, 0, {})).toBe(false)
    expect(shouldDefaultExpandProcessGroup(allInGroup, 0, { forceReload: false })).toBe(false)
  })

  test('given 末尾已有外置最终回复 when forceReload then 不展开过程分组', () => {
    const withFinalText = buildAssistantTurnRenderItems([thinking(), tool('tool-1'), text('最终输出')])

    expect(withFinalText.map((item) => item.type)).toEqual(['process-group', 'block'])
    // 过程分组不是末项，且本轮存在外置 block ⇒ 不默认展开
    expect(shouldDefaultExpandProcessGroup(withFinalText, 0, { forceReload: true })).toBe(false)
    expect(shouldDefaultExpandProcessGroup(withFinalText, 1, { forceReload: true })).toBe(false)
  })

  test('given 末项不是过程分组或下标越界 then 不展开', () => {
    const items = buildAssistantTurnRenderItems([tool('tool-1'), text('最终输出')])
    expect(shouldDefaultExpandProcessGroup(items, 0, { forceReload: true })).toBe(false)
    expect(shouldDefaultExpandProcessGroup(items, 99, { forceReload: true })).toBe(false)
  })

  test('given 流式中尚未拆分 when forceReload then 整组展开（能看到流式内容）', () => {
    const streamingItems = buildAssistantTurnRenderItems([
      tool('tool-1'),
      tool('tool-2'),
      text('可能的中间说明'),
    ], { isStreaming: true, completedToolResultIds: new Set(['tool-2']) })

    expect(streamingItems).toHaveLength(1)
    expect(shouldDefaultExpandProcessGroup(streamingItems, 0, { forceReload: true })).toBe(true)
  })
})
