/**
 * 围栏代码高亮。
 *
 * lowlight 只是 highlight.js 语法的「虚拟 DOM 版」：产出 HAST，不拼 HTML 字符串，
 * 这里再把它译成 React 节点 —— 全程不经过 dangerouslySetInnerHTML。
 * 正文（MarkdownWithFiles）与一次性预览页的静态片段共用这一份组件，
 * 配色统一由 styles.css / 预览页样式表里的 `--code-*` 与 `.hljs-*` 决定。
 */
import * as React from 'react'
import { createLowlight, common } from 'lowlight'

const lowlight = createLowlight(common)

/** lowlight 已收录大多数别名（ts / jsx / sh / yml…），这里只补它没注册的常见写法 */
const LANGUAGE_ALIASES: Record<string, string> = {
  console: 'bash',
  terminal: 'bash',
  env: 'ini',
  dotenv: 'ini',
}

/** 未知语言只抛一次：流式输出时同一个代码块会反复渲染，不做缓存会一直抛 */
const unsupportedLanguages = new Set<string>()

type HighlightRoot = ReturnType<typeof lowlight.highlight>
type HighlightContent = HighlightRoot['children'][number]

export type HighlightedCodeProps = React.ComponentProps<'code'> & { node?: unknown }

function languageFromClassName(className: string | undefined): string | null {
  const match = /(?:^|\s)language-([\w+#.-]+)/.exec(className ?? '')
  const raw = match?.[1]?.toLowerCase()
  if (!raw) return null
  return LANGUAGE_ALIASES[raw] ?? raw
}

function classNameOf(value: unknown): string | undefined {
  if (typeof value === 'string') return value || undefined
  if (!Array.isArray(value)) return undefined
  const names = value.filter((item): item is string => typeof item === 'string')
  return names.length > 0 ? names.join(' ') : undefined
}

function renderHighlighted(nodes: HighlightContent[]): React.ReactNode[] {
  return nodes.map((node, index) => {
    if (node.type === 'text') return node.value
    if (node.type !== 'element') return null
    const className = classNameOf(node.properties.className)
    return React.createElement(
      node.tagName,
      className ? { key: index, className } : { key: index },
      renderHighlighted(node.children),
    )
  })
}

export function HighlightedCode({ className, children, node: _node, ...props }: HighlightedCodeProps): React.ReactElement {
  const language = languageFromClassName(className)
  const code = typeof children === 'string' ? children : null
  const highlighted = React.useMemo(() => {
    if (language === null || code === null || unsupportedLanguages.has(language)) return null
    try {
      return renderHighlighted(lowlight.highlight(language, code).children)
    } catch {
      unsupportedLanguages.add(language)
      return null
    }
  }, [language, code])

  // 行内代码、未知语言、高亮失败都保持原样输出
  if (highlighted === null) return <code className={className} {...props}>{children}</code>
  return <code className={className} {...props}>{highlighted}</code>
}
