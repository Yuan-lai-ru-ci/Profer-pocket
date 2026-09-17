import { describe, expect, test } from 'bun:test'
import { buildAssistantTurnRenderItems, buildProcessGroupToolNames, resolveProcessGroupCollapse, shouldDefaultExpandProcessGroup } from './ProcessBlockGroup'
import type { ProcessGroupCollapseInput } from './ProcessBlockGroup'
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

  test('given final answer followed by a trailing thinking block when grouping then keeps the answer outside the process group', () => {
    // 修复「最终回复被折叠」：真实流式数据里同一条 assistant 消息可能是 [text, thinking]
    // （reasoning 晚于正文到达），旧实现因末块不是 text 而把整段回复折叠成「执行过程」。
    const items = buildAssistantTurnRenderItems([
      text('这是最终回复'),
      thinking('收尾思考'),
    ])

    expect(items.map((item) => item.type)).toEqual(['process-group', 'block'])
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([1])
    }
    if (items[1]?.type === 'block') {
      expect(items[1].item.index).toBe(0)
    }
  })

  test('given final answer between steps and a trailing thinking block when grouping then only folds the process blocks', () => {
    // turn 内聚合后以 thinking 收尾时，正文仍然必须外置。
    const items = buildAssistantTurnRenderItems([
      thinking('开工思考'),
      text('最终回复'),
      thinking('收尾思考'),
    ])

    expect(items.map((item) => item.type)).toEqual(['process-group', 'block'])
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 2])
    }
    if (items[1]?.type === 'block') {
      expect(items[1].item.index).toBe(1)
    }
  })

  test('given intermediate text with later tools and a trailing thinking block when grouping then keeps the whole turn folded', () => {
    // 正文之后仍有 tool_use：这段 text 是给工具看的中间说明，继续整组折叠。
    const items = buildAssistantTurnRenderItems([
      text('中间说明'),
      thinking('中间思考'),
      tool('tool-1'),
      thinking('还在干活'),
    ])

    expect(items).toHaveLength(1)
    expect(items[0]?.type).toBe('process-group')
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1, 2, 3])
    }
  })

  test('given trailing thinking before final answer when grouping then folds thinking and keeps answer outside', () => {
    const items = buildAssistantTurnRenderItems([
      tool('tool-1'),
      thinking('收尾思考'),
      text('最终回复'),
    ])

    expect(items.map((item) => item.type)).toEqual(['process-group', 'block'])
    if (items[0]?.type === 'process-group') {
      expect(items[0].items.map((item) => item.index)).toEqual([0, 1])
    }
    if (items[1]?.type === 'block') {
      expect(items[1].item.index).toBe(2)
    }
  })

  test('given multi-block pure text answer when grouping then renders all text blocks as normal output', () => {
    const items = buildAssistantTurnRenderItems([
      text('第一段'),
      text('第二段'),
    ])

    expect(items.map((item) => item.type)).toEqual(['block', 'block'])
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

describe('1.7.2 折叠自锁 × R10 默认展开的决策（对齐桌面语义）', () => {
  const base: ProcessGroupCollapseInput = {
    isStreaming: false,
    keepExpandedAfterComplete: false,
    defaultExpanded: false,
    userToggled: false,
    wasStreaming: false,
    collapseScheduled: false,
    collapseDone: false,
  }

  test('given 新一轮 streaming 开始且未排定折叠 when 决策 then 清定时器与倒计时并展开', () => {
    const plan = resolveProcessGroupCollapse({ ...base, isStreaming: true })

    expect(plan.outcome).toBe('expand')
    expect(plan.clearTimers).toBe(true)
    expect(plan.resetCountdown).toBe(true)
    expect(plan.targetExpanded).toBe(true)
    expect(plan.next.wasStreaming).toBe(true)
  })

  test('given 流式刚结束且用户未干预 when 决策 then 排定自动折叠倒计时并置自锁', () => {
    const plan = resolveProcessGroupCollapse({ ...base, wasStreaming: true })

    expect(plan.outcome).toBe('schedule-collapse')
    expect(plan.clearTimers).toBe(false)
    expect(plan.targetExpanded).toBeUndefined()
    expect(plan.next.collapseScheduled).toBe(true)
    expect(plan.next.wasStreaming).toBe(false)
  })

  test('★ given 上一轮已排定折叠 when 新一轮 streaming 开始 then 不取消倒计时、不重新展开', () => {
    const plan = resolveProcessGroupCollapse({ ...base, isStreaming: true, collapseScheduled: true })

    expect(plan.outcome).toBe('hold-scheduled')
    expect(plan.clearTimers).toBe(false)
    expect(plan.resetCountdown).toBe(false)
    expect(plan.targetExpanded).toBeUndefined()
    expect(plan.next.collapseScheduled).toBe(true)
    expect(plan.next.wasStreaming).toBe(true)
  })

  test('★ given 上一轮已排定折叠 when keepExpandedAfterComplete 接力 then 同样不撤销折叠', () => {
    const plan = resolveProcessGroupCollapse({ ...base, keepExpandedAfterComplete: true, collapseScheduled: true })

    expect(plan.outcome).toBe('hold-scheduled')
    expect(plan.clearTimers).toBe(false)
    expect(plan.targetExpanded).toBeUndefined()
  })

  test('★ given 已完成一次自动折叠 when 新一轮 streaming 开始 then 不再被重新展开', () => {
    const plan = resolveProcessGroupCollapse({ ...base, isStreaming: true, collapseDone: true })

    expect(plan.outcome).toBe('skip-collapse-done')
    expect(plan.clearTimers).toBe(false)
    expect(plan.targetExpanded).toBeUndefined()
    expect(plan.next).toEqual({ userToggled: false, wasStreaming: false, collapseScheduled: false, collapseDone: true })
  })

  test('given 折叠已在调度 when 再次进入非流式分支 then 不重复排定也不改展开态', () => {
    const plan = resolveProcessGroupCollapse({ ...base, wasStreaming: true, collapseScheduled: true })

    expect(plan.outcome).toBe('already-scheduled')
    expect(plan.next.collapseScheduled).toBe(true)
    expect(plan.targetExpanded).toBeUndefined()
  })

  test('given 用户手动收起过 when 流式结束 then 不自动折叠、也不覆盖用户选择', () => {
    const plan = resolveProcessGroupCollapse({ ...base, wasStreaming: true, userToggled: true })

    expect(plan.outcome).toBe('collapse-now')
    expect(plan.targetExpanded).toBeUndefined()
    expect(plan.next.userToggled).toBe(true)
  })

  test('given 手动切换已复位两把锁 when 下一轮 streaming then 重新按 streaming 规则展开', () => {
    // 按钮 onClick 会把 userToggled 置 true 并复位 collapseScheduled / collapseDone（自锁解除）
    const plan = resolveProcessGroupCollapse({ ...base, isStreaming: true, userToggled: true })

    expect(plan.outcome).toBe('expand')
    expect(plan.next.userToggled).toBe(false)
    expect(plan.targetExpanded).toBe(true)
  })

  test('given R10 首次挂载（非流式整组）when 决策 then 默认展开', () => {
    const plan = resolveProcessGroupCollapse({ ...base, defaultExpanded: true })

    expect(plan.outcome).toBe('default-expanded')
    expect(plan.targetExpanded).toBe(true)
    expect(plan.resetCountdown).toBe(true)
  })

  test('given R10 且用户已手动收起 when 决策 then 不被重新弹开', () => {
    const plan = resolveProcessGroupCollapse({ ...base, defaultExpanded: true, userToggled: true })

    expect(plan.outcome).toBe('collapse-now')
    expect(plan.targetExpanded).toBeUndefined()
  })

  test('given R10 且流式刚结束 when 决策 then 走自动折叠而非默认展开（默认态只认首次挂载）', () => {
    const plan = resolveProcessGroupCollapse({ ...base, defaultExpanded: true, wasStreaming: true })

    expect(plan.outcome).toBe('schedule-collapse')
    expect(plan.next.collapseScheduled).toBe(true)
  })

  test('★ given R10 且已完成自动折叠 when forceReload 重挂 then 不把过程组重新弹开', () => {
    const plan = resolveProcessGroupCollapse({ ...base, defaultExpanded: true, collapseDone: true })

    expect(plan.outcome).toBe('skip-collapse-done')
    expect(plan.targetExpanded).toBeUndefined()
  })

  test('★ given R10 且折叠倒计时进行中 when 决策 then 默认展开不得撤销已排定的折叠', () => {
    const plan = resolveProcessGroupCollapse({ ...base, defaultExpanded: true, collapseScheduled: true })

    expect(plan.outcome).toBe('already-scheduled')
    expect(plan.targetExpanded).toBeUndefined()
    expect(plan.next.collapseScheduled).toBe(true)
  })
})
