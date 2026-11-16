/**
 * 会话内可视化（原生图表）的展示层。
 *
 * 桌面端用 ECharts 渲染 `VisualizationChartSpec`；这里不引图表库，
 * 直接用 SVG 画柱/线/饼 —— 移动端只有三种图、数据量也不大，
 * 为一个卡片拉进来 ~200KB 的依赖不划算。
 *
 * 记录与消息的连接键是 `toolCallId`：工具调用行里没有图表数据
 * （WS 出口的投影只留 {file_path}），必须靠这个 id 把记录挂回对应回合。
 */
import * as React from 'react'

export interface ChartSeries {
  dataKey: string
  label?: string
  valuePrefix?: string
  valueSuffix?: string
}

export interface ChartSpec {
  chartType: 'bar' | 'line' | 'pie' | 'scatter'
  xKey?: string
  xAxisLabel?: string
  nameKey?: string
  valueKey?: string
  layout?: 'horizontal' | 'vertical'
  series: ChartSeries[]
  data: Array<Record<string, string | number>>
}

export interface VisualizationItem {
  id: string
  toolCallId: string
  title: string
  summary?: string
  format?: 'html' | 'fragment' | 'chart'
  chart?: ChartSpec
  createdAt: number
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function normalizeChart(value: unknown): ChartSpec | undefined {
  if (!isRecord(value)) return undefined
  const chartType = value.chartType
  if (chartType !== 'bar' && chartType !== 'line' && chartType !== 'pie' && chartType !== 'scatter') return undefined
  const data = Array.isArray(value.data)
    ? value.data.filter(isRecord) as Array<Record<string, string | number>>
    : []
  if (data.length === 0) return undefined
  const series = Array.isArray(value.series)
    ? value.series.flatMap((entry) => {
      if (!isRecord(entry) || typeof entry.dataKey !== 'string' || !entry.dataKey) return []
      return [{
        dataKey: entry.dataKey,
        label: asString(entry.label),
        valuePrefix: asString(entry.valuePrefix),
        valueSuffix: asString(entry.valueSuffix),
      }]
    })
    : []
  return {
    chartType,
    xKey: asString(value.xKey),
    xAxisLabel: asString(value.xAxisLabel),
    nameKey: asString(value.nameKey),
    valueKey: asString(value.valueKey),
    layout: value.layout === 'horizontal' ? 'horizontal' : 'vertical',
    series,
    data,
  }
}

/** 记录归一化：没有 id / toolCallId 的脏项直接丢弃，否则会渲染出无处可挂的孤立卡片。 */
export function normalizeVisualizations(raw: unknown): VisualizationItem[] {
  if (!Array.isArray(raw)) return []
  return raw.flatMap((entry) => {
    if (!isRecord(entry)) return []
    const id = asString(entry.id)
    const toolCallId = asString(entry.toolCallId)
    const title = asString(entry.title)
    if (!id || !toolCallId || !title) return []
    const format = entry.format === 'html' || entry.format === 'fragment' || entry.format === 'chart'
      ? entry.format
      : undefined
    return [{
      id,
      toolCallId,
      title,
      summary: asString(entry.summary),
      format,
      chart: normalizeChart(entry.chart),
      createdAt: typeof entry.createdAt === 'number' ? entry.createdAt : 0,
    }]
  })
}

function toNumber(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  const parsed = Number.parseFloat(String(value ?? ''))
  return Number.isFinite(parsed) ? parsed : 0
}

function labelOf(spec: ChartSpec, row: Record<string, string | number>, index: number): string {
  const key = spec.xKey ?? spec.nameKey
  const raw = key ? row[key] : undefined
  return raw === undefined || raw === '' ? String(index + 1) : String(raw)
}

/** 坐标轴上界取 1/2/5×10ⁿ，避免出现 4.7 这种刻度。 */
export function niceCeil(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 1
  const exponent = Math.floor(Math.log10(value))
  const base = 10 ** exponent
  const normalized = value / base
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * base
}

const TICKS = 5
const CHART_H = 300
const PAD = { top: 14, right: 14, bottom: 44, left: 40 }
/* 图表色跟随主题：复用语义 token，浅色下自动换成更深的一档 */
const LINE_COLORS = ['var(--info)', 'var(--success)', 'var(--warning)', 'var(--error)']

function tickLabel(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text
}

/** 纵轴：0 → max 的网格线与刻度，柱/线共用。 */
function ValueAxis({ max, left, right, top, bottom }: {
  max: number
  left: number
  right: number
  top: number
  bottom: number
}): React.ReactElement {
  return (
    <g>
      {Array.from({ length: TICKS + 1 }, (_, index) => {
        const value = (max / TICKS) * index
        const y = top + (bottom - top) * (1 - value / max)
        return (
          <g key={index}>
            <line x1={left} x2={right} y1={y} y2={y} className="viz-grid" />
            <text x={left - 6} y={y + 3.5} className="viz-tick" textAnchor="end">{tickLabel(value)}</text>
          </g>
        )
      })}
    </g>
  )
}

function VerticalBars({ spec }: { spec: ChartSpec }): React.ReactElement {
  const series = spec.series.length > 0 ? spec.series : [{ dataKey: 'value' }]
  const labels = spec.data.map((row, index) => labelOf(spec, row, index))
  const max = niceCeil(Math.max(...spec.data.flatMap((row) => series.map((entry) => toNumber(row[entry.dataKey]))), 0))
  const width = Math.max(340, labels.length * 56 + PAD.left + PAD.right)
  const plotW = width - PAD.left - PAD.right
  const plotH = CHART_H - PAD.top - PAD.bottom
  const band = plotW / Math.max(labels.length, 1)
  const groupW = Math.min(band * 0.62, 64)
  const barW = groupW / series.length

  return (
    <svg viewBox={`0 0 ${width} ${CHART_H}`} role="img" aria-label={spec.xAxisLabel ?? '柱状图'}>
      <ValueAxis max={max} left={PAD.left} right={width - PAD.right} top={PAD.top} bottom={CHART_H - PAD.bottom} />
      {spec.data.map((row, rowIndex) => {
        const center = PAD.left + band * rowIndex + band / 2
        return (
          <g key={rowIndex}>
            {series.map((entry, seriesIndex) => {
              const value = toNumber(row[entry.dataKey])
              const height = plotH * (value / max)
              const x = center - groupW / 2 + barW * seriesIndex
              return (
                <rect
                  key={entry.dataKey}
                  x={x}
                  y={PAD.top + plotH - height}
                  width={Math.max(barW - 2, 1)}
                  height={Math.max(height, 0)}
                  rx={2}
                  style={{ fill: LINE_COLORS[seriesIndex % LINE_COLORS.length] }}
                />
              )
            })}
            <text
              x={center}
              y={CHART_H - PAD.bottom + 16}
              className="viz-tick"
              textAnchor="middle"
            >
              {truncate(labels[rowIndex] ?? '', 6)}
            </text>
          </g>
        )
      })}
      {spec.xAxisLabel && (
        <text x={PAD.left + plotW / 2} y={CHART_H - 6} className="viz-axis-label" textAnchor="middle">
          {spec.xAxisLabel}
        </text>
      )}
    </svg>
  )
}

function HorizontalBars({ spec }: { spec: ChartSpec }): React.ReactElement {
  const series = spec.series.length > 0 ? spec.series : [{ dataKey: 'value' }]
  const labels = spec.data.map((row, index) => labelOf(spec, row, index))
  const max = niceCeil(Math.max(...spec.data.flatMap((row) => series.map((entry) => toNumber(row[entry.dataKey]))), 0))
  const padLeft = 88
  const width = 340 + padLeft
  const height = Math.max(CHART_H, labels.length * 34 + 40)
  const plotW = width - padLeft - 24
  const band = (height - 32) / Math.max(labels.length, 1)
  const barH = Math.min(band * 0.56, 20)

  return (
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={spec.xAxisLabel ?? '条形图'}>
      {Array.from({ length: TICKS + 1 }, (_, index) => {
        const value = (max / TICKS) * index
        const x = padLeft + plotW * (value / max)
        return (
          <g key={index}>
            <line x1={x} x2={x} y1={8} y2={height - 24} className="viz-grid" />
            <text x={x} y={height - 10} className="viz-tick" textAnchor="middle">{tickLabel(value)}</text>
          </g>
        )
      })}
      {spec.data.map((row, rowIndex) => {
        const center = 8 + band * rowIndex + band / 2
        return (
          <g key={rowIndex}>
            <text x={padLeft - 8} y={center + 3.5} className="viz-tick" textAnchor="end">
              {truncate(labels[rowIndex] ?? '', 8)}
            </text>
            {series.map((entry, seriesIndex) => {
              const value = toNumber(row[entry.dataKey])
              return (
                <rect
                  key={entry.dataKey}
                  x={padLeft}
                  y={center - barH / 2 + barH * seriesIndex}
                  width={Math.max(plotW * (value / max), 0)}
                  height={Math.max(barH - 2, 1)}
                  rx={2}
                  style={{ fill: LINE_COLORS[seriesIndex % LINE_COLORS.length] }}
                />
              )
            })}
          </g>
        )
      })}
    </svg>
  )
}

function LineChart({ spec }: { spec: ChartSpec }): React.ReactElement {
  const series = spec.series.length > 0 ? spec.series : [{ dataKey: 'value' }]
  const labels = spec.data.map((row, index) => labelOf(spec, row, index))
  const max = niceCeil(Math.max(...spec.data.flatMap((row) => series.map((entry) => toNumber(row[entry.dataKey]))), 0))
  const width = Math.max(340, labels.length * 56 + PAD.left + PAD.right)
  const plotW = width - PAD.left - PAD.right
  const plotH = CHART_H - PAD.top - PAD.bottom
  const band = plotW / Math.max(labels.length, 1)
  const pointAt = (rowIndex: number, value: number): [number, number] => [
    PAD.left + band * rowIndex + band / 2,
    PAD.top + plotH * (1 - value / max),
  ]

  return (
    <svg viewBox={`0 0 ${width} ${CHART_H}`} role="img" aria-label={spec.xAxisLabel ?? '折线图'}>
      <ValueAxis max={max} left={PAD.left} right={width - PAD.right} top={PAD.top} bottom={CHART_H - PAD.bottom} />
      {series.map((entry, seriesIndex) => {
        const points = spec.data
          .map((row, rowIndex) => pointAt(rowIndex, toNumber(row[entry.dataKey])))
          .map(([x, y]) => `${x},${y}`)
          .join(' ')
        return (
          <g key={entry.dataKey}>
            <polyline points={points} fill="none" style={{ stroke: LINE_COLORS[seriesIndex % LINE_COLORS.length] }} strokeWidth={2} />
            {spec.data.map((row, rowIndex) => {
              const [x, y] = pointAt(rowIndex, toNumber(row[entry.dataKey]))
              return <circle key={rowIndex} cx={x} cy={y} r={3} style={{ fill: LINE_COLORS[seriesIndex % LINE_COLORS.length] }} />
            })}
          </g>
        )
      })}
      {labels.map((label, index) => (
        <text
          key={index}
          x={PAD.left + band * index + band / 2}
          y={CHART_H - PAD.bottom + 16}
          className="viz-tick"
          textAnchor="middle"
        >
          {truncate(label, 6)}
        </text>
      ))}
    </svg>
  )
}

function PieChart({ spec }: { spec: ChartSpec }): React.ReactElement {
  const valueKey = spec.valueKey ?? spec.series[0]?.dataKey ?? 'value'
  const total = spec.data.reduce((sum, row) => sum + Math.max(toNumber(row[valueKey]), 0), 0)
  const size = 280
  const radius = size / 2 - 20
  const center = size / 2
  let angle = -Math.PI / 2
  const slices = spec.data.map((row, index) => {
    const value = Math.max(toNumber(row[valueKey]), 0)
    const sweep = total > 0 ? (value / total) * Math.PI * 2 : 0
    const start = angle
    angle += sweep
    const x1 = center + radius * Math.cos(start)
    const y1 = center + radius * Math.sin(start)
    const x2 = center + radius * Math.cos(angle)
    const y2 = center + radius * Math.sin(angle)
    const large = sweep > Math.PI ? 1 : 0
    return {
      key: index,
      value,
      label: labelOf(spec, row, index),
      path: sweep >= Math.PI * 2 - 1e-6
        // 整圆用两段弧拼，单段弧在 360° 时退化成一条线
        ? `M ${center} ${center - radius} A ${radius} ${radius} 0 1 1 ${center} ${center + radius} A ${radius} ${radius} 0 1 1 ${center} ${center - radius}`
        : `M ${center} ${center} L ${x1} ${y1} A ${radius} ${radius} 0 ${large} 1 ${x2} ${y2} Z`,
    }
  })

  return (
    <div className="viz-pie">
      <svg viewBox={`0 0 ${size} ${size}`} role="img" aria-label="饼图">
        {slices.map((slice) => (
          <path key={slice.key} d={slice.path} className="viz-slice" style={{ fill: LINE_COLORS[slice.key % LINE_COLORS.length] }} />
        ))}
      </svg>
      <ul className="viz-legend">
        {slices.map((slice) => (
          <li key={slice.key}>
            <span className="viz-legend-dot" style={{ background: LINE_COLORS[slice.key % LINE_COLORS.length] }} />
            <span className="viz-legend-label">{slice.label}</span>
            <span className="viz-legend-value">{slice.value}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function ChartView({ spec }: { spec: ChartSpec }): React.ReactElement {
  if (spec.chartType === 'pie') return <PieChart spec={spec} />
  if (spec.chartType === 'line') return <LineChart spec={spec} />
  if (spec.chartType === 'bar') {
    return spec.layout === 'horizontal' ? <HorizontalBars spec={spec} /> : <VerticalBars spec={spec} />
  }
  // scatter 暂时没有专门实现：数据表仍可看，避免悄悄渲染一张空图
  return <p className="viz-fallback">该图表类型暂不支持在这里绘制，可展开下方数据查看。</p>
}

function tableColumns(spec: ChartSpec): string[] {
  const first = spec.xKey ?? spec.nameKey
  const keys = spec.series.map((entry) => entry.dataKey)
  return [...(first ? [first] : []), ...keys]
}

/** 导出数据用；列为空时退回全部键，否则会导出一堆空行。 */
export function toCsv(spec: ChartSpec): string {
  const columns = tableColumns(spec).length > 0
    ? tableColumns(spec)
    : Array.from(new Set(spec.data.flatMap((row) => Object.keys(row))))
  const escape = (value: unknown): string => {
    const text = value === undefined || value === null ? '' : String(value)
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  const lines = [columns.map(escape).join(',')]
  for (const row of spec.data) lines.push(columns.map((column) => escape(row[column])).join(','))
  // 带 BOM，否则 Excel 打开中文会乱码
  return `\uFEFF${lines.join('\n')}\n`
}

/** 单个可视化卡片：标题 + 摘要 + 图形 + 可展开数据表 + 导出 CSV。 */
export function VisualizationCard({ item }: { item: VisualizationItem }): React.ReactElement {
  const [tableOpen, setTableOpen] = React.useState(false)
  const spec = item.chart

  const download = (): void => {
    if (!spec) return
    const blob = new Blob([toCsv(spec)], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${item.title.replace(/[\\/:*?"<>|]/g, '-') || 'chart'}.csv`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  return (
    <section className="viz-card">
      <header className="viz-head">
        <div className="viz-heading">
          <h3 className="viz-title">{item.title}</h3>
          {item.summary && <p className="viz-summary">{item.summary}</p>}
        </div>
        {spec && (
          <button className="viz-icon-btn" onClick={download} title="导出数据（CSV）" aria-label="导出数据">
            ↓
          </button>
        )}
      </header>
      {spec
        ? (
          <>
            <div className="viz-plot">
              <ChartView spec={spec} />
            </div>
            <button className="viz-disclosure" onClick={() => setTableOpen((previous) => !previous)} aria-expanded={tableOpen}>
              <span className={`chev ${tableOpen ? 'open' : ''}`} aria-hidden="true">›</span>
              查看数据
            </button>
            {tableOpen && (
              <div className="viz-table-wrap">
                <table className="viz-table">
                  <thead>
                    <tr>{tableColumns(spec).map((column) => <th key={column}>{column}</th>)}</tr>
                  </thead>
                  <tbody>
                    {spec.data.map((row, index) => (
                      <tr key={index}>
                        {tableColumns(spec).map((column) => <td key={column}>{String(row[column] ?? '')}</td>)}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )
        : (
          // HTML / 片段型可视化依赖桌面端的 bridge 才能交互，移动端只给一句诚实说明
          <p className="viz-fallback">这个可视化是交互式页面，需要在电脑端查看。</p>
        )}
    </section>
  )
}

/** 一个回合可能挂了多张图，按创建时间排序，与对话顺序一致。 */
export function VisualizationList({ items }: { items: VisualizationItem[] }): React.ReactElement | null {
  if (items.length === 0) return null
  const sorted = [...items].sort((a, b) => a.createdAt - b.createdAt)
  return (
    <div className="viz-list">
      {sorted.map((item) => <VisualizationCard key={item.id} item={item} />)}
    </div>
  )
}
