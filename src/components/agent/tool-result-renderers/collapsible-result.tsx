/**
 * 可折叠长内容包装器（移动端，纯展示）
 *
 * - 短内容直接展示
 * - 长内容默认折叠，显示前 N 行 + 长度指示器
 * - 点击展开/收起全部内容
 */

import * as React from 'react'
import { ChevronDown, ChevronUp } from 'lucide-react'
import { cn } from '@/lib/utils'

interface CollapsibleResultProps {
  content: string
  threshold?: number
  previewLines?: number
  renderContent: (text: string) => React.ReactNode
  className?: string
}

export function CollapsibleResult({
  content,
  threshold = 3000,
  previewLines = 15,
  renderContent,
  className,
}: CollapsibleResultProps): React.ReactElement {
  const safeContent = content ?? ''
  const [expanded, setExpanded] = React.useState(false)
  const needsCollapse = safeContent.length > threshold

  const displayContent = React.useMemo(() => {
    if (!needsCollapse || expanded) return safeContent
    const lines = safeContent.split('\n')
    if (lines.length <= previewLines) return safeContent
    return lines.slice(0, previewLines).join('\n')
  }, [safeContent, needsCollapse, expanded, previewLines])

  return (
    <div className={cn('relative', className)}>
      {renderContent(displayContent)}

      {needsCollapse && (
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className="mt-1.5 flex items-center gap-1 text-[11px] text-muted-foreground/60 transition-colors hover:text-foreground/80"
        >
          {expanded ? (
            <>
              <ChevronUp className="size-3" />
              收起
            </>
          ) : (
            <>
              <ChevronDown className="size-3" />
              显示全部 ({safeContent.length.toLocaleString()} 字符, {safeContent.split('\n').length} 行)
            </>
          )}
        </button>
      )}
    </div>
  )
}
