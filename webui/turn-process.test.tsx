import { describe, expect, test } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import type { MessageGroup } from '@profer/session-core'
import { buildAssistantTimeline } from './turn-timeline'
import { TurnProcess } from './turn-process'
import { normalizeVisualizations } from './visualization'

function turnOf(contents: unknown[][], results: string[] = []): MessageGroup {
  const assistant = contents.map((content, index) => ({
    type: 'assistant' as const,
    uuid: `a${index}`,
    message: { role: 'assistant', content },
  }))
  const toolResults = results.map((id) => ({
    type: 'user' as const,
    uuid: `r-${id}`,
    message: { content: [{ type: 'tool_result', tool_use_id: id }] },
  }))
  return { type: 'assistant-turn', assistantMessages: assistant, turnMessages: [...assistant, ...toolResults] } as never as MessageGroup
}

const text = (value: string): unknown => ({ type: 'text', text: value })
const thinking = (value: string): unknown => ({ type: 'thinking', thinking: value })
const tool = (id: string, name: string, summary?: string): unknown => ({ type: 'tool_use', id, name, input: {}, ...(summary ? { summary } : {}) })

describe('执行过程组渲染', () => {
  const timeline = buildAssistantTimeline(turnOf([
    [thinking('先确认目录结构'), text('看一下工作区'), tool('t1', 'mcp__collaboration__delegate_agent', '一行原文结论，不要复述过程。')],
  ], ['t1']))

  test('Given 运行中 When 渲染 Then 标题行是「N 次工具调用」句式，内容默认展开', () => {
    const html = renderToStaticMarkup(<TurnProcess timeline={timeline} live />)
    expect(html).toContain('执行过程：1 次工具调用，1 段思考，1 条消息')
    // live 时默认展开：工具行与思考行都在 DOM 里
    expect(html).toContain('delegate_agent')
    expect(html).toContain('COLLABORATION')
  })

  test('Given 已结束 When 渲染 Then 默认收起，过程内容不进 DOM（避免长会话一屏全被过程占满）', () => {
    const html = renderToStaticMarkup(<TurnProcess timeline={timeline} live={false} />)
    expect(html).toContain('执行过程：1 次工具调用，1 段思考，1 条消息')
    expect(html).not.toContain('delegate_agent')
  })

  test('Given 工具行 When 渲染 Then 摘要就在行内，不用展开才看得到在做什么', () => {
    const html = renderToStaticMarkup(<TurnProcess timeline={timeline} live />)
    expect(html).toContain('一行原文结论，不要复述过程。')
  })

  test('Given 没有过程条目 When 渲染 Then 返回 null，不留一行空标题', () => {
    const empty = buildAssistantTimeline(turnOf([[text('直接回答')]]))
    expect(renderToStaticMarkup(<TurnProcess timeline={empty} live />)).toBe('')
  })
})

describe('过程组里的图表', () => {
  /** 桌面端 present_visualization 落盘的一条记录，靠 toolCallId 挂回工具行 */
  const chart = (toolCallId: string, title = '各渠道停留时长') => normalizeVisualizations([{
    id: `viz-${toolCallId}`,
    toolCallId,
    title,
    format: 'chart',
    createdAt: 1,
    chart: {
      chartType: 'bar',
      xKey: 'channel',
      series: [{ dataKey: 'minutes' }],
      data: [{ channel: '搜索', minutes: 42 }],
    },
  }])[0]!

  const timeline = buildAssistantTimeline(turnOf([
    [tool('t1', 'mcp__collaboration__delegate_agent', '先取数据'), tool('t2', 'Read', '读取报告')],
  ], ['t1', 't2']))

  test('Given 图表属于本回合 When 过程组展开 Then 卡片紧跟在触发生成它的工具行之后', () => {
    const html = renderToStaticMarkup(
      <TurnProcess timeline={timeline} live visualizations={[chart('t1')]} />,
    )
    const chartAt = html.indexOf('各渠道停留时长')
    expect(chartAt).toBeGreaterThan(html.indexOf('delegate_agent'))
    // 挂在 t1 下面，而不是飘到整轮末尾（下一行工具 t2 之前就该出现）
    expect(chartAt).toBeLessThan(html.indexOf('读取报告'))
  })

  test('Given 过程组收起 When 渲染 Then 图表随组一起不进 DOM（不再单独悬在对话流里）', () => {
    const html = renderToStaticMarkup(
      <TurnProcess timeline={timeline} live={false} visualizations={[chart('t1')]} />,
    )
    expect(html).not.toContain('各渠道停留时长')
  })

  test('Given 图表属于其他回合 When 渲染 Then 不挂到本轮（toolCallId 对不上就不显示）', () => {
    const html = renderToStaticMarkup(
      <TurnProcess timeline={timeline} live visualizations={[chart('t-other')]} />,
    )
    expect(html).not.toContain('各渠道停留时长')
  })
})
