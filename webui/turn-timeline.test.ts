import { describe, expect, test } from 'bun:test'
import type { MessageGroup } from '@profer/session-core'
import { buildAssistantTimeline, isTurnFinished, isTurnSettled, summarizeProcess, toolLabel, turnHasTrailingPendingTool } from './turn-timeline'

/** 一轮 = 若干 assistant 消息 + 承载 tool_result 的 user 消息（tail 用来追加 result 等收尾消息）。 */
function turnOf(
  contents: unknown[][],
  results: Array<{ id: string; error?: boolean }> = [],
  tail: unknown[] = [],
): MessageGroup {
  const assistant = contents.map((content, index) => ({
    type: 'assistant' as const,
    uuid: `a${index}`,
    message: { role: 'assistant', content },
  }))
  const toolResults = results.map((result) => ({
    type: 'user' as const,
    uuid: `r-${result.id}`,
    message: {
      content: [{
        type: 'tool_result',
        tool_use_id: result.id,
        ...(result.error ? { is_error: true } : {}),
      }],
    },
  }))
  return {
    type: 'assistant-turn',
    assistantMessages: assistant,
    turnMessages: [...assistant, ...toolResults, ...tail],
  } as never as MessageGroup
}

/** 顶层 result：整轮收尾的唯一可靠信号（子代理的 result 带 parent_tool_use_id）。 */
const result = (parentToolUseId: string | null = null): unknown => ({
  type: 'result', uuid: `res-${parentToolUseId ?? 'top'}`, parent_tool_use_id: parentToolUseId,
})

const text = (value: string): unknown => ({ type: 'text', text: value })
const thinking = (value: string): unknown => ({ type: 'thinking', thinking: value })
const tool = (id: string, name: string, input: Record<string, unknown> = {}, summary?: string): unknown => ({
  type: 'tool_use', id, name, input, ...(summary ? { summary } : {}),
})

describe('过程时间线：顺序与正文外置', () => {
  test('Given 叙述 + 工具 + 结尾正文 When 拆分 Then 过程保持原顺序，正文只留最后一段', () => {
    const timeline = buildAssistantTimeline(turnOf(
      [[text('先看一下目录'), tool('t1', 'Bash', { file_path: '/x' }, 'ls -la')]],
      [{ id: 't1' }],
    ))
    // 结尾没有 text 段 → 没有可外置的正文
    expect(timeline.headline).toBe('')
    expect(timeline.items.map((item) => item.kind)).toEqual(['text', 'tool'])
  })

  test('Given 过程后跟最终答复 When 拆分 Then 答复外置，过程里不再重复它', () => {
    const timeline = buildAssistantTimeline(turnOf([
      [text('先跑一下测试'), tool('t1', 'Bash', {}, 'bun test')],
      [text('测试通过，问题出在缓存。')],
    ], [{ id: 't1' }]))

    expect(timeline.headline).toBe('测试通过，问题出在缓存。')
    expect(timeline.items.map((item) => item.kind)).toEqual(['text', 'tool'])
    expect(timeline.summary).toBe('执行过程：1 次工具调用，1 条消息')
  })

  test('Given 工具结果还没回来 When 拆分 Then 保守地不外置（那段 text 可能是给工具看的说明）', () => {
    const timeline = buildAssistantTimeline(turnOf([
      [tool('t1', 'Bash', {}, 'bun test')],
      [text('看起来快好了')],
    ]))
    expect(timeline.headline).toBe('')
    expect(timeline.items.some((item) => item.kind === 'text')).toBe(true)
  })

  test('Given 正文之后又追加了思考 When 拆分 Then 正文仍外置，思考留在过程里', () => {
    const timeline = buildAssistantTimeline(turnOf([
      [text('开始'), tool('t1', 'Read', {}, 'a.ts')],
      [text('改好了。'), thinking('还要注意缓存')],
    ], [{ id: 't1' }]))
    expect(timeline.headline).toBe('改好了。')
    expect(timeline.items.map((item) => item.kind)).toEqual(['text', 'tool', 'thinking'])
  })

  test('Given 只有思考与工具 When 拆分 Then 没有正文，标题行仍然给出可读计数', () => {
    const timeline = buildAssistantTimeline(turnOf([[thinking('先想一下'), tool('t1', 'Glob', {}, '*.ts')]]))
    expect(timeline.headline).toBe('')
    expect(timeline.summary).toBe('执行过程：1 次工具调用，1 段思考')
  })

  test('Given 有工具结果永不回来但整轮已收尾 When 拆分 Then 正文仍然外置（否则用户以为没有回答）', () => {
    // 真实形态：工具被中断，tool_result 从未落盘；死等结果会让正文永久锁在过程组里
    const timeline = buildAssistantTimeline(turnOf([
      [tool('t1', 'inspect_preview', {}, '预览')],
      [text('预览器有故障，但页面本身可以看。')],
    ], [], [result()]))
    expect(timeline.headline).toBe('预览器有故障，但页面本身可以看。')
    expect(timeline.items.map((item) => item.kind)).toEqual(['tool'])
  })

  test('Given 同样的形态但这轮还在跑 When 拆分 Then 保守地不外置，等结果', () => {
    const timeline = buildAssistantTimeline(turnOf([
      [tool('t1', 'inspect_preview', {}, '预览')],
      [text('我先看看这一页')],
    ]))
    expect(timeline.headline).toBe('')
  })

  test('Given 末尾正文之后还有工具 When 拆分 Then 仍不外置 —— 那是中间说明不是最终答复', () => {
    const timeline = buildAssistantTimeline(turnOf([
      [text('我先确认一下')],
      [tool('t1', 'Bash', {}, 'ls')],
    ], [{ id: 't1' }], [result()]))
    expect(timeline.headline).toBe('')
  })
})

describe('轮次是否收尾', () => {
  test('Given 有顶层 result When 判定 Then 已收尾', () => {
    expect(isTurnFinished(turnOf([[text('好了')]], [], [result()]))).toBe(true)
  })

  test('Given 只有子代理 result When 判定 Then 不算收尾（子任务结束≠整轮结束）', () => {
    expect(isTurnFinished(turnOf([[text('进行中')]], [], [result('tool-abc')]))).toBe(false)
  })

  test('Given 没有任何 result When 判定 Then 未收尾', () => {
    expect(isTurnFinished(turnOf([[text('进行中')]]))).toBe(false)
  })

  test('Given 非 assistant-turn 分组 When 判定 Then 一律视为未收尾', () => {
    expect(isTurnFinished(undefined)).toBe(false)
  })
})

describe('过程时间线：工具状态', () => {
  test('Given 结果已回且带 is_error When 拆分 Then 标记失败并置 done', () => {
    const timeline = buildAssistantTimeline(turnOf([[tool('t1', 'Bash', {}, 'exit 1')]], [{ id: 't1', error: true }]))
    const item = timeline.items[0]
    expect(item?.kind).toBe('tool')
    if (item?.kind !== 'tool') throw new Error('期望工具条目')
    expect(item.tool.done).toBe(true)
    expect(item.tool.failed).toBe(true)
  })

  test('Given 结果未回 When 拆分 Then done=false（行内显示呼吸点，不是「已完成」）', () => {
    const timeline = buildAssistantTimeline(turnOf([[tool('t1', 'Bash')]]))
    const item = timeline.items[0]
    if (item?.kind !== 'tool') throw new Error('期望工具条目')
    expect(item.tool.done).toBe(false)
    expect(item.tool.failed).toBeUndefined()
  })

  test('Given 工具带文件路径 When 拆分 Then 路径可展开查看', () => {
    const timeline = buildAssistantTimeline(turnOf([[
      tool('t1', 'Read', { file_path: 'D:/ProferAI/README.md' }, ''),
    ]]))
    const item = timeline.items[0]
    if (item?.kind !== 'tool') throw new Error('期望工具条目')
    expect(item.tool.filePath).toBe('D:/ProferAI/README.md')
  })
})

describe('工具名解析', () => {
  test('Given MCP 工具 When 解析 Then 分类取 server，名称取工具段', () => {
    expect(toolLabel('mcp__collaboration__delegate_agent')).toEqual({ category: 'COLLABORATION', label: 'delegate_agent' })
    expect(toolLabel('mcp__automation__delete_automation')).toEqual({ category: 'AUTOMATION', label: 'delete_automation' })
  })

  test('Given 内置工具 When 解析 Then 没有分类，只显示原名', () => {
    expect(toolLabel('BrowserNewTab')).toEqual({ label: 'BrowserNewTab' })
  })

  test('Given 名称里带多个双下划线 When 解析 Then 只切出第一段分类', () => {
    expect(toolLabel('mcp__memory-archive__search_memory')).toEqual({ category: 'MEMORY-ARCHIVE', label: 'search_memory' })
  })
})

describe('过程句式', () => {
  test('Given 三项都有 When 汇总 Then 与桌面端同句式', () => {
    expect(summarizeProcess(54, 17, 6)).toBe('执行过程：54 次工具调用，17 段思考，6 条消息')
  })

  test('Given 只有部分计数 When 汇总 Then 不写 0', () => {
    expect(summarizeProcess(1, 0, 0)).toBe('执行过程：1 次工具调用')
    expect(summarizeProcess(0, 2, 0)).toBe('执行过程：2 段思考')
  })

  test('Given 全为 0 When 汇总 Then 退化成「过程」，不出现空标题', () => {
    expect(summarizeProcess(0, 0, 0)).toBe('执行过程：过程')
  })
})

describe('非助手回合', () => {
  test('Given 用户回合 When 派生 Then 空时间线（用户输入不进过程组）', () => {
    const timeline = buildAssistantTimeline({ type: 'user', message: { message: { content: [text('你好')] } } } as never)
    expect(timeline.items).toEqual([])
    expect(timeline.headline).toBe('')
  })

  test('Given 没有分组 When 派生 Then 空时间线，调用方不必判空', () => {
    expect(buildAssistantTimeline(undefined).summary).toBe('执行过程')
  })
})

describe('缺结果轮次的正文抽出（WebUI 看不到顶层 result）', () => {
  const tool = (id: string) => [{ type: 'tool_use', id, name: 'Read', input: { file_path: '/tmp/a' } }]

  test('Given 末尾工具已收齐、中间工具缺结果 When 判定末尾在飞 Then false', () => {
    // t1 缺结果但它在中间，t2 是末尾且已收齐 → 不算在飞
    const group = turnOf([tool('t1'), tool('t2'), [text('最终答复')]], [{ id: 't2' }])
    expect(turnHasTrailingPendingTool(group)).toBe(false)
  })

  test('Given 末尾工具缺结果 When 判定末尾在飞 Then true（真在跑）', () => {
    const group = turnOf([tool('t1'), [text('临时')], tool('t2')], [{ id: 't1' }])
    expect(turnHasTrailingPendingTool(group)).toBe(true)
  })

  test('Given 末尾工具未收齐 When 判定末尾在飞 Then true', () => {
    const group = turnOf([tool('t1')], [])
    expect(turnHasTrailingPendingTool(group)).toBe(true)
  })

  test('Given 非助手回合 When 判定末尾在飞 Then false', () => {
    expect(turnHasTrailingPendingTool({ type: 'user', message: {} } as never)).toBe(false)
  })

  test('Given 中间缺结果、末尾已收齐 When 不传 settled（旧严格分支） Then 正文被藏', () => {
    // 这就是 WebUI 旧行为：严格分支要求所有工具结果到齐，缺结果的轮次整段正文消失
    const group = turnOf([tool('t1'), tool('t2'), [text('最终答复')]], [{ id: 't2' }])
    expect(buildAssistantTimeline(group).headline).toBe('')
  })

  test('Given 同上 When 传 settled=true（末尾已收齐） Then 正文正常抽出', () => {
    const group = turnOf([tool('t1'), tool('t2'), [text('最终答复')]], [{ id: 't2' }])
    expect(buildAssistantTimeline(group, true).headline).toBe('最终答复')
  })

  test('Given 最终文本后还有工具 When 即便 settled 也收不回正文（文本不是最终）', () => {
    const group = turnOf([[text('临时')], tool('t1')], [])
    expect(buildAssistantTimeline(group, true).headline).toBe('')
  })
})


describe('过程组收起判据（WebUI 看不到 result）', () => {
  const text = (value: string) => [{ type: 'text', text: value }]
  const tool = (id: string) => [{ type: 'tool_use', id, name: 'Read', input: { file_path: '/tmp/a' } }]

  test('Given 有顶层 result When 判定收尾 Then true（权威判据仍然生效）', () => {
    const group = turnOf([tool('t1')], [{ id: 't1' }], [{ type: 'result', subtype: 'success' }])
    expect(isTurnSettled(group, buildAssistantTimeline(group))).toBe(true)
  })

  test('Given 没有 result 但最终正文已抽出、工具都完成 When 判定收尾 Then true', () => {
    // 这就是网页端的真实形态：projectSdkMessagesToPocketView 会把 result 整条过滤掉
    const group = turnOf([tool('t1'), text('已经做完了。')], [{ id: 't1' }])
    const timeline = buildAssistantTimeline(group)
    expect(timeline.headline).toContain('已经做完了')
    expect(isTurnFinished(group)).toBe(false)
    expect(isTurnSettled(group, timeline)).toBe(true)
  })

  test('Given 最终正文已出现但还有工具没拿到结果 When 判定收尾 Then false（不能提前收起）', () => {
    const group = turnOf([tool('t1'), tool('t2'), text('先写到这里。')], [{ id: 't1' }])
    const timeline = buildAssistantTimeline(group)
    expect(isTurnSettled(group, timeline)).toBe(false)
  })

  test('Given 只有工具调用没有正文 When 判定收尾 Then false', () => {
    const group = turnOf([tool('t1')], [{ id: 't1' }])
    expect(isTurnSettled(group, buildAssistantTimeline(group))).toBe(false)
  })

  test('Given 用户回合 / 空分组 When 判定收尾 Then false', () => {
    expect(isTurnSettled(undefined, undefined)).toBe(false)
    expect(isTurnSettled({ type: 'user', message: {} } as never, undefined)).toBe(false)
  })

  test('Given 收尾后又来了新工具调用 When 重算 Then headline 被收回 items，判据变回 false', () => {
    const settled = turnOf([tool('t1'), text('做完了。')], [{ id: 't1' }])
    expect(isTurnSettled(settled, buildAssistantTimeline(settled))).toBe(true)
    const continued = turnOf([tool('t1'), text('做完了。'), tool('t2')], [{ id: 't1' }])
    const timeline = buildAssistantTimeline(continued)
    expect(timeline.headline).toBe('')
    expect(isTurnSettled(continued, timeline)).toBe(false)
  })
})


describe('任务进度：任务工具抽成卡片', () => {
  const taskCreate = (id: string, input: Record<string, unknown>): unknown => ({
    type: 'tool_use', id, name: 'TaskCreate', input,
  })
  const turnWithResults = (
    contents: unknown[][],
    results: Array<{ id: string; output?: string }>,
  ): MessageGroup => {
    const assistant = contents.map((content, index) => ({
      type: 'assistant' as const,
      uuid: `a${index}`,
      message: { role: 'assistant', content },
    }))
    const toolResults = results.map((result) => ({
      type: 'user' as const,
      uuid: `r-${result.id}`,
      message: {
        content: [{
          type: 'tool_result',
          tool_use_id: result.id,
          ...(result.output ? { output: result.output } : {}),
        }],
      },
    }))
    return {
      type: 'assistant-turn',
      assistantMessages: assistant,
      turnMessages: [...assistant, ...toolResults],
    } as never as MessageGroup
  }

  test('Given TaskCreate + TaskUpdate When 派生 Then 工具行被抽走，任务进入 tasks', () => {
    const timeline = buildAssistantTimeline(turnWithResults([
      [
        taskCreate('t1', { subject: '补任务卡', activeForm: '正在补任务卡' }, '{"task":{"id":"1","subject":"补任务卡"}}'),
        { type: 'tool_use', id: 't2', name: 'TaskUpdate', input: { taskId: '1', status: 'in_progress' } },
        { type: 'tool_use', id: 't3', name: 'Read', input: { file_path: '/a.ts' } },
      ],
    ], [{ id: 't1', output: '{"task":{"id":"1","subject":"补任务卡"}}' }, { id: 't2' }, { id: 't3' }]))
    expect(timeline.items.map((item) => item.kind === 'tool' ? item.tool.name : item.kind)).toEqual(['Read'])
    expect(timeline.tasks).toEqual([
      { id: '1', subject: '补任务卡', status: 'in_progress', activeForm: '正在补任务卡' },
    ])
    expect(timeline.summary).toBe('执行过程：1 次工具调用')
  })

  test('Given 任务工具是唯一内容 When 派生 Then 过程组为空，任务卡仍然有数据', () => {
    const timeline = buildAssistantTimeline(turnWithResults([
      [taskCreate('t1', { subject: '唯一任务' })],
    ], [{ id: 't1', output: '{"task":{"id":"1"}}' }]))
    expect(timeline.items).toEqual([])
    expect(timeline.tasks.map((task) => task.subject)).toEqual(['唯一任务'])
  })

  test('Given 投影只有 inputText When 派生 Then 仍能解析任务名', () => {
    const timeline = buildAssistantTimeline(turnWithResults([
      [{ type: 'tool_use', id: 't1', name: 'TaskCreate', input: {}, inputText: '{"subject":"投影任务"}' }],
    ], [{ id: 't1', output: '{"task":{"id":"2"}}' }]))
    expect(timeline.tasks[0]?.subject).toBe('投影任务')
    expect(timeline.tasks[0]?.id).toBe('2')
  })

  test('Given 传入历史映射 When 派生 Then 后续轮的 TaskUpdate 能还原任务名', () => {
    const timeline = buildAssistantTimeline(
      turnWithResults([[{ type: 'tool_use', id: 't1', name: 'TaskUpdate', input: { taskId: '8', status: 'completed' } }]], [{ id: 't1' }]),
      true,
      { historicalTaskSubjects: new Map([['8', '上一轮创建的任务']]) },
    )
    expect(timeline.tasks).toEqual([{ id: '8', subject: '上一轮创建的任务', status: 'completed' }])
  })

  test('Given 收尾但任务没标完成 When streamEnded Then 不转圈（退回 pending）', () => {
    const timeline = buildAssistantTimeline(
      turnWithResults([[{ type: 'tool_use', id: 't1', name: 'TaskUpdate', input: { taskId: '1', status: 'in_progress' } }]], [{ id: 't1' }]),
      true,
      { historicalTaskSubjects: new Map([['1', '还在做的任务']]), streamEnded: true },
    )
    expect(timeline.tasks[0]?.status).toBe('pending')
  })

  test('Given 仍在运行 When streamEnded=false Then 保持 in_progress', () => {
    const timeline = buildAssistantTimeline(
      turnWithResults([[{ type: 'tool_use', id: 't1', name: 'TaskUpdate', input: { taskId: '1', status: 'in_progress' } }]], [{ id: 't1' }]),
      false,
      { historicalTaskSubjects: new Map([['1', '还在做的任务']]), streamEnded: false },
    )
    expect(timeline.tasks[0]?.status).toBe('in_progress')
  })
})
