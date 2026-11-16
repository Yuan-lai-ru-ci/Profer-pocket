/**
 * Mermaid 图表渲染（WebUI 版，与桌面端 MermaidBlock 同一套渲染顺序）。
 *
 * 优先 beautiful-mermaid（配色直接吃 CSS 变量，切深浅色不用重渲染），
 * 它只覆盖流程图/状态图等部分图型，不支持时回退官方 mermaid。
 *
 * 时序：源码变化 → 稳定 350ms 后后台渲染 → 成功换成 SVG。
 * 源码变化期间保留上一张图（只有渲染失败才回退源码）——
 * 流式输出时每个分片都重跑 effect，若先清空就会在「源码 ↔ SVG」之间反复闪。
 * 渲染失败保持源码，不把解析错误抛到页面上。
 *
 * 与桌面端的差异：移动端不做缩放工具条，SVG 直接按容器宽度铺满（可双指放大页面）。
 */
import * as React from 'react'
import type { RenderOptions } from 'beautiful-mermaid'

const DEBOUNCE_MS = 350
/** 已渲染 SVG 的复用缓存：run 结束重拉、消息窗口滑动都会重挂整段 Markdown，
 *  没有缓存时每次重挂都先回到源码再等渲染，看起来就是图表反复闪烁。 */
const SVG_CACHE_LIMIT = 40
const svgCache = new Map<string, string>()
let officialRenderId = 0

function readCachedSvg(code: string): string | null {
  const hit = svgCache.get(code)
  if (hit === undefined) return null
  // LRU：命中后挪到队尾
  svgCache.delete(code)
  svgCache.set(code, hit)
  return hit
}

function writeCachedSvg(code: string, svg: string): void {
  svgCache.delete(code)
  svgCache.set(code, svg)
  while (svgCache.size > SVG_CACHE_LIMIT) {
    const oldest = svgCache.keys().next().value
    if (oldest === undefined) break
    svgCache.delete(oldest)
  }
}

function isUsableSvg(svg: unknown): svg is string {
  if (typeof svg !== 'string' || !svg.includes('<svg')) return false
  if (/(?:^|[^a-z])(?:NaN|Infinity|-Infinity)(?:[^a-z]|$)/i.test(svg)) return false
  // mermaid 解析失败时会返回带错误标记的 SVG，需要识别后走兜底
  if (svg.includes('aria-roledescription="error"')) return false
  if (svg.includes('class="error-text"')) return false
  return true
}

/** beautiful-mermaid 会无条件塞一条 Google Fonts @import；内网/离线场景只会白等一次请求 */
function stripFontImport(svg: string): string {
  return svg.replace(/@import url\([^)]*\);\s*/g, '')
}

/**
 * 把 beautiful-mermaid 输出的正交折线边转成圆角过渡（与桌面端一致）。
 * 只改走线形状，布局、箭头 marker、虚线样式都原样保留。
 */
function smoothPolylineEdges(svg: string): string {
  const polylineRe = /<polyline([^>]*?)\spoints="([^"]*)"([^>]*?)\/>/g
  return svg.replace(polylineRe, (whole, prefix: string, pointsAttr: string, suffix: string) => {
    const pts = pointsAttr.trim().split(/\s+/)
      .map((point) => point.split(',').map(Number))
      .filter((point) => point.length === 2 && Number.isFinite(point[0]) && Number.isFinite(point[1])) as Array<[number, number]>
    if (pts.length < 3) return whole
    const first = pts[0]
    const last = pts[pts.length - 1]
    if (!first || !last) return whole

    const radius = 10
    let d = `M ${fmt(first[0])},${fmt(first[1])}`
    for (let i = 1; i < pts.length - 1; i++) {
      const prev = pts[i - 1]
      const cur = pts[i]
      const next = pts[i + 1]
      if (!prev || !cur || !next) continue
      const [ax, ay] = prev
      const [bx, by] = cur
      const [cx, cy] = next
      const seg1 = Math.hypot(bx - ax, by - ay)
      const seg2 = Math.hypot(cx - bx, cy - by)
      if (seg1 < 1 || seg2 < 1) continue
      const r = Math.min(radius, seg1 / 2, seg2 / 2)
      const px = bx - ((bx - ax) / seg1) * r
      const py = by - ((by - ay) / seg1) * r
      const qx = bx + ((cx - bx) / seg2) * r
      const qy = by + ((cy - by) / seg2) * r
      d += ` L ${fmt(px)},${fmt(py)} Q ${fmt(bx)},${fmt(by)} ${fmt(qx)},${fmt(qy)}`
    }
    d += ` L ${fmt(last[0])},${fmt(last[1])}`
    return `<path${prefix} d="${d}"${suffix} />`
  })
}

function fmt(n: number): string {
  return Number(n.toFixed(1)).toString()
}

/** 主题 token 都定义在 :root，取计算值给官方 mermaid 用（它的配色计算不认 var()） */
function cssToken(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

async function renderWithOfficialMermaid(code: string): Promise<string> {
  const { default: mermaid } = await import('mermaid')
  const dark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    // 解析失败直接抛错，不往页面里注入 "Syntax error" 错误图
    suppressErrorRendering: true,
    theme: 'base',
    themeVariables: {
      background: cssToken('--surface-low', dark ? '#211f1c' : '#fcfaf7'),
      mainBkg: cssToken('--surface', dark ? '#292724' : '#efece7'),
      primaryColor: cssToken('--surface', dark ? '#292724' : '#efece7'),
      primaryTextColor: cssToken('--ink', dark ? '#ecebe9' : '#322f2a'),
      primaryBorderColor: cssToken('--outline', dark ? '#4a4640' : '#c6bfb5'),
      lineColor: cssToken('--muted', dark ? '#aaa7a1' : '#66635c'),
      textColor: cssToken('--ink', dark ? '#ecebe9' : '#322f2a'),
      secondaryColor: cssToken('--surface-high', dark ? '#2e2c28' : '#e8e3dc'),
      tertiaryColor: cssToken('--surface-low', dark ? '#211f1c' : '#fcfaf7'),
    },
  })

  const id = `webui-mermaid-${Date.now()}-${officialRenderId++}`
  const { svg } = await mermaid.render(id, code)
  if (!isUsableSvg(svg)) throw new Error('Mermaid 输出了无效 SVG')
  return svg
}

async function renderMermaidSvg(code: string): Promise<string> {
  try {
    const { renderMermaidSVGAsync } = await import('beautiful-mermaid')
    const options: RenderOptions = {
      bg: 'var(--surface-low)',
      fg: 'var(--ink)',
      line: 'var(--muted)',
      accent: 'var(--primary)',
      muted: 'var(--muted-faint)',
      surface: 'var(--surface)',
      border: 'var(--outline)',
      font: 'system-ui, sans-serif',
      padding: 12,
      transparent: true,
    }
    const svg = await renderMermaidSVGAsync(code, options)
    if (isUsableSvg(svg)) return smoothPolylineEdges(stripFontImport(svg))
  } catch {
    // beautiful-mermaid 只覆盖部分图型，不支持时交给官方 mermaid 兜底。
  }
  return renderWithOfficialMermaid(code)
}

export function MermaidBlock({ code }: { code: string }): React.ReactElement {
  // 重挂时直接用缓存里的 SVG 起手：消息重拉/窗口滑动不会让已渲染的图先闪回源码。
  const [renderedSvg, setRenderedSvg] = React.useState<string | null>(() => readCachedSvg(code))
  const codeRef = React.useRef(code)
  const debounceRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)
  const generationRef = React.useRef(0)
  codeRef.current = code

  const renderCurrentCode = React.useCallback(async (generation: number) => {
    const source = codeRef.current
    try {
      const svg = await renderMermaidSvg(source)
      // 即使这一代已过期，结果对 source 仍然有效，缓存留着给重挂用；只缓存成功结果。
      writeCachedSvg(source, svg)
      if (generationRef.current === generation) setRenderedSvg(svg)
    } catch {
      if (generationRef.current === generation) setRenderedSvg(null)
    }
  }, [])

  React.useEffect(() => {
    generationRef.current++
    const currentGen = generationRef.current
    if (debounceRef.current) clearTimeout(debounceRef.current)
    // 不清空 renderedSvg：流式期间 code 每帧都在变，清空会让图表反复闪回源码；
    // 保持上一张图直到新一代渲染成功（失败时 renderCurrentCode 会置回 null）。
    // 缓存只用于重挂时的首帧，这里仍渲染一次：官方兜底路径的配色是取出来的具体值，
    // 深浅色切换后必须重算，不能因为命中缓存就跳过。
    debounceRef.current = setTimeout(() => {
      void renderCurrentCode(currentGen)
    }, DEBOUNCE_MS)
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [code, renderCurrentCode])

  // 官方兜底路径用的是取出来的具体色值，深浅色切换必须重渲染；
  // beautiful-mermaid 路径吃 CSS 变量，重渲染只是多算一次，无副作用。
  React.useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = (): void => {
      generationRef.current++
      void renderCurrentCode(generationRef.current)
    }
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [renderCurrentCode])

  if (!renderedSvg) {
    return (
      <pre className="mermaid-src">
        <code className="language-mermaid">{code}</code>
      </pre>
    )
  }

  return (
    <div className="mermaid-block">
      <div className="mermaid-svg" dangerouslySetInnerHTML={{ __html: renderedSvg }} />
    </div>
  )
}
