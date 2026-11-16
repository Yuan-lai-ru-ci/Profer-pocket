import { describe, expect, test } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { VisualizationCard, VisualizationList, normalizeVisualizations, type ChartSpec } from './visualization'

function renderChart(chart: Record<string, unknown>, extra: Record<string, unknown> = {}): string {
  const [item] = normalizeVisualizations([{
    id: 'viz-1',
    toolCallId: 'call-1',
    title: '各渠道停留时长',
    summary: '按渠道统计',
    format: 'chart',
    createdAt: 1,
    chart,
    ...extra,
  }])
  return renderToStaticMarkup(<VisualizationCard item={item!} />)
}

const barChart = {
  chartType: 'bar',
  xKey: 'channel',
  xAxisLabel: '渠道',
  series: [{ dataKey: 'minutes' }],
  data: [
    { channel: '搜索', minutes: 42 },
    { channel: '直连', minutes: 18 },
  ],
}

describe('图表卡片渲染', () => {
  test('Given 柱状图 When 渲染 Then 出标题、纵轴刻度与每根柱子', () => {
    const html = renderChart(barChart)
    expect(html).toContain('各渠道停留时长')
    expect(html).toContain('按渠道统计')
    expect(html).toContain('<svg')
    expect(html).toContain('渠道')
    // 两根柱子 + 刻度线，用 rect 数量至少覆盖数据条数
    expect(html.match(/<rect/g)?.length).toBeGreaterThanOrEqual(2)
    expect(html).toContain('查看数据')
  })

  test('Given 横向条形图 When 渲染 Then 走横向布局而不是退回纵向', () => {
    const html = renderChart({ ...barChart, layout: 'horizontal' })
    expect(html).toContain('<svg')
    expect(html).toContain('搜索')
  })

  test('Given 饼图 When 渲染 Then 每段都有对应图例', () => {
    const html = renderChart({
      chartType: 'pie',
      nameKey: 'channel',
      valueKey: 'minutes',
      series: [{ dataKey: 'minutes' }],
      data: [
        { channel: '搜索', minutes: 42 },
        { channel: '直连', minutes: 18 },
      ],
    })
    expect(html.match(/<li/g)?.length).toBe(2)
    expect(html).toContain('viz-legend-dot')
  })

  test('Given 折线图 When 渲染 Then 出 polyline 与数据点', () => {
    const html = renderChart({ ...barChart, chartType: 'line' })
    expect(html).toContain('<polyline')
    expect(html.match(/<circle/g)?.length).toBe(2)
  })

  test('Given 数值字段是非数字字符串 When 渲染 Then 几何里不能出现 NaN（SVG 会整块消失）', () => {
    const html = renderChart({
      ...barChart,
      data: [{ channel: '搜索', minutes: '无数据' }, { channel: '直连', minutes: null }],
    })
    expect(html).not.toContain('NaN')
  })

  test('Given scatter When 渲染 Then 给诚实降级文案，不画一张空图', () => {
    const html = renderChart({ chartType: 'scatter', series: [], data: [{ a: 1 }] })
    expect(html).toContain('暂不支持')
  })

  test('Given HTML 型可视化 When 渲染 Then 提示去电脑端查看，且不渲染导出按钮', () => {
    const html = renderChart(undefined, { format: 'html', chart: undefined })
    expect(html).toContain('需要在电脑端查看')
    expect(html).not.toContain('viz-icon-btn')
  })
})

describe('图表列表', () => {
  test('Given 同一回合多张图 When 渲染 Then 按 createdAt 升序，与对话顺序一致', () => {
    const items = normalizeVisualizations([
      { id: 'b', toolCallId: 'c2', title: '第二张', createdAt: 20 },
      { id: 'a', toolCallId: 'c1', title: '第一张', createdAt: 10 },
    ])
    const html = renderToStaticMarkup(<VisualizationList items={items} />)
    expect(html.indexOf('第一张')).toBeLessThan(html.indexOf('第二张'))
  })

  test('Given 空列表 When 渲染 Then 什么都不输出（不占位、不留空白块）', () => {
    expect(renderToStaticMarkup(<VisualizationList items={[]} />)).toBe('')
  })
})

describe('图表规格直连', () => {
  test('Given series 为空 When 渲染 Then 退回单值列，不因为没 series 就画空图', () => {
    const spec: ChartSpec = {
      chartType: 'bar',
      xKey: 'name',
      series: [],
      data: [{ name: 'A', value: 3 }, { name: 'B', value: 5 }],
    }
    const html = renderToStaticMarkup(<VisualizationCard item={{
      id: 'v', toolCallId: 'c', title: 't', format: 'chart', chart: spec, createdAt: 0,
    }} />)
    expect(html.match(/<rect/g)?.length).toBeGreaterThanOrEqual(2)
    expect(html).not.toContain('NaN')
  })
})
