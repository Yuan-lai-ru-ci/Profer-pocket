/**
 * CodeBlock - 代码块组件（移动端瘦客户端简化版）
 *
 * 替代 @profer/ui 的 CodeBlock（依赖 shiki + @profer/core 的 highlightToTokens，
 * 后者 peer 依赖 @anthropic-ai/claude-agent-sdk，无法在移动端编译）。
 *
 * 移动端降级策略：不做 Shiki 语法高亮，仅渲染深色底 + 语言标签 + 复制按钮，
 * 保持与桌面一致的视觉区块（头部栏 + 代码区），单色代码文本。
 */

import * as React from 'react'

interface CodeElementProps {
  className?: string
  children?: React.ReactNode
}

interface CodeBlockProps {
  children: React.ReactNode
}

function extractText(node: React.ReactNode): string {
  if (typeof node === 'string') return node
  if (typeof node === 'number') return String(node)
  if (!node) return ''
  if (Array.isArray(node)) return node.map(extractText).join('')
  if (React.isValidElement(node)) {
    return extractText((node.props as CodeElementProps).children)
  }
  return ''
}

function extractCodeInfo(children: React.ReactNode): { language: string; code: string } {
  const codeElement = React.Children.toArray(children).find(
    (child): child is React.ReactElement => {
      if (!React.isValidElement(child)) return false
      const t = (child as React.ReactElement).type
      return t === 'code' || typeof t === 'function' || typeof t === 'object'
    }
  ) as React.ReactElement | undefined

  if (!codeElement) {
    return { language: '', code: extractText(children) }
  }

  const props = codeElement.props as CodeElementProps
  const langMatch = props.className?.match(/language-(\S+)/)
  return {
    language: langMatch?.[1] ?? '',
    code: extractText(props.children),
  }
}

function getDisplayName(language: string): string {
  if (!language) return 'text'
  const map: Record<string, string> = {
    js: 'javascript', ts: 'typescript', jsx: 'jsx', tsx: 'tsx',
    py: 'python', rb: 'ruby', go: 'go', rs: 'rust', c: 'c', cpp: 'cpp',
    java: 'java', sh: 'shell', bash: 'shell', json: 'json', yml: 'yaml',
    yaml: 'yaml', html: 'html', css: 'css', md: 'markdown', sql: 'sql',
  }
  return map[language] ?? language
}

const ICON_ATTRS = {
  width: 14, height: 14, viewBox: '0 0 24 24',
  fill: 'none', stroke: 'currentColor', strokeWidth: 2,
  strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const,
}

const copyIconPath = (
  <>
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </>
)

const checkIconPath = <polyline points="20 6 9 17 4 12" />

export function CodeBlock({ children }: CodeBlockProps): React.ReactElement {
  const { language, code } = React.useMemo(() => extractCodeInfo(children), [children])
  const [copied, setCopied] = React.useState(false)

  const trimmedCode = code.replace(/\n$/, '')

  const handleCopy = React.useCallback(async () => {
    try {
      await navigator.clipboard.writeText(trimmedCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch (error) {
      console.error('[CodeBlock] 复制失败:', error)
    }
  }, [trimmedCode])

  return (
    <div className="code-block-wrapper group/code my-2 overflow-hidden rounded-lg border border-border/50">
      <div className="flex h-[34px] items-center justify-between bg-muted/60 px-2 py-1 text-xs text-muted-foreground">
        <span className="select-none font-medium">{getDisplayName(language)}</span>
        <button
          type="button"
          onClick={handleCopy}
          className="flex items-center gap-1.5 rounded px-1.5 py-0.5 text-muted-foreground transition-colors hover:bg-foreground/10 hover:text-foreground"
        >
          <svg {...ICON_ATTRS}>{copied ? checkIconPath : copyIconPath}</svg>
          <span>{copied ? '已复制' : '复制'}</span>
        </button>
      </div>
      <pre className="m-0 overflow-x-auto bg-[hsl(var(--code-bg))] p-4 text-[0.875em] leading-[1.6]" style={{ color: '#e1e4e8', borderRadius: '0 0 8px 8px' }}>
        <code>{trimmedCode}</code>
      </pre>
    </div>
  )
}
