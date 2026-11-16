/**
 * Mermaid 代码块识别（与 `@profer/ui` 的 mermaid-detection 同一套口径）。
 *
 * WebUI 不引 @profer/ui（那是桌面 Tailwind 组件栈），所以把判定逻辑放在本地：
 * - 显式 `language-mermaid` / `language-mmd` 直接认定；
 * - 标了其它语言的一律不认（避免把 python 里的 graph 拼音误判）；
 * - 没标语言时看首行的图型关键字，flowchart/graph 必须带方向才认。
 */

const MERMAID_START_KEYWORDS = [
  'sequenceDiagram',
  'classDiagram',
  'stateDiagram',
  'stateDiagram-v2',
  'erDiagram',
  'journey',
  'gantt',
  'pie',
  'gitGraph',
  'mindmap',
  'timeline',
  'quadrantChart',
  'xychart-beta',
  'block',
  'block-beta',
  'packet',
  'packet-beta',
  'architecture',
  'architecture-beta',
  'sankey',
  'sankey-beta',
  'requirementDiagram',
  'kanban',
  'radar-beta',
  'treeView-beta',
  'treemap',
  'venn-beta',
  'ishikawa-beta',
  'wardley-beta',
  'C4Context',
  'C4Container',
  'C4Component',
  'C4Dynamic',
  'C4Deployment',
] as const

const MERMAID_START_PATTERN = new RegExp(
  `^(?:${MERMAID_START_KEYWORDS.map((keyword) => keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})\\b`,
  'i',
)
const MERMAID_DIRECTED_PATTERN = /^(?:flowchart-elk|flowchart|graph)\s+(?:TB|TD|BT|RL|LR)\b/i
const LANGUAGE_CLASS_PATTERN = /(?:^|\s)language-\S+/i
const MERMAID_LANGUAGE_CLASS_PATTERN = /(?:^|\s)language-(?:mermaid|mmd)(?:\s|$)/i
const MERMAID_DIRECTIVE_PATTERN = /^%%\{[\s\S]*\}%%$/
const MERMAID_COMMENT_PATTERN = /^%%(?!\{)/

export function looksLikeMermaidDefinition(code: string): boolean {
  for (const line of code.trimStart().split(/\r?\n/)) {
    const candidate = line.trim()
    if (!candidate || MERMAID_DIRECTIVE_PATTERN.test(candidate) || MERMAID_COMMENT_PATTERN.test(candidate)) continue
    return MERMAID_DIRECTED_PATTERN.test(candidate) || MERMAID_START_PATTERN.test(candidate)
  }
  return false
}

export function shouldRenderMermaidCodeBlock(className: string | undefined, code: string): boolean {
  if (className && MERMAID_LANGUAGE_CLASS_PATTERN.test(className)) return true
  if (className && LANGUAGE_CLASS_PATTERN.test(className)) return false
  return looksLikeMermaidDefinition(code)
}

/** react-markdown 传给自定义组件的 hast 节点（只取用到的字段，避免引 hast 类型包）。 */
export interface MarkdownHastNode {
  type?: string
  tagName?: string
  value?: string
  properties?: Record<string, unknown>
  children?: MarkdownHastNode[]
}

function classNameOf(value: unknown): string | undefined {
  if (typeof value === 'string') return value || undefined
  if (!Array.isArray(value)) return undefined
  const names = value.filter((item): item is string => typeof item === 'string')
  return names.length > 0 ? names.join(' ') : undefined
}

function nodeText(node: MarkdownHastNode): string {
  if (node.type === 'text') return node.value ?? ''
  return (node.children ?? []).map(nodeText).join('')
}

/**
 * 从 `<pre>` 的 hast 节点里取出 mermaid 源码；不是 mermaid 代码块时返回 null。
 * react-markdown v10 会把 `<code>` 换成自定义组件，React 层的 type 不再是字符串，
 * 所以这里认 hast 结构而不是 React 元素。
 */
export function mermaidFenceFromPre(node: unknown): string | null {
  const pre = node as MarkdownHastNode | undefined
  const codeNode = pre?.children?.find((child) => child.type === 'element' && child.tagName === 'code')
  if (!codeNode) return null
  const className = classNameOf(codeNode.properties?.className)
  const code = nodeText(codeNode).replace(/\r\n?/g, '\n').replace(/\n$/, '')
  if (!code.trim()) return null
  return shouldRenderMermaidCodeBlock(className, code) ? code : null
}
