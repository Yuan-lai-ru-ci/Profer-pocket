/**
 * ToolResultRenderer — 工具结果分发渲染器（移动端瘦客户端极简版）
 *
 * 移动端不引入 @pierre/diffs（Read/Edit/Write 的 diff 渲染库，桌面专属重量级依赖），
 * 也不渲染图片（Electron IPC readAttachment 不可用），
 * 因此所有工具结果统一走 DefaultResultRenderer 的纯文本折叠渲染，
 * 满足「工具活动虚线框 + 结果折叠」的核心要求。
 */

import * as React from 'react'
import { CollapsibleResult } from './collapsible-result'

export interface ToolResultRendererProps {
  toolName: string
  input: Record<string, unknown>
  result: string
  isError: boolean
  basePath?: string
}

/** 尝试将结果解析为 key-value 对 */
function tryParseKeyValue(text: string): Array<{ key: string; value: string }> | null {
  try {
    const parsed = JSON.parse(text)
    if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
      return Object.entries(parsed as Record<string, unknown>).map(([key, value]) => ({
        key,
        value: typeof value === 'string' ? value : JSON.stringify(value, null, 2),
      }))
    }
  } catch {
    // 非 JSON
  }
  return null
}

function DefaultResultRenderer({ result, isError }: { result: string; isError: boolean }): React.ReactElement {
  if (isError) {
    return (
      <pre className="break-all overflow-x-auto whitespace-pre-wrap rounded-md bg-destructive/5 p-3 font-mono text-[12px] text-destructive/80">
        {result}
      </pre>
    )
  }

  const keyValues = tryParseKeyValue(result)

  if (keyValues && keyValues.length > 0) {
    return (
      <div className="overflow-hidden rounded-md bg-muted/20">
        <table className="w-full text-[12px]">
          <tbody>
            {keyValues.map(({ key, value }, i) => (
              <tr key={i} className="border-b border-border/20 last:border-b-0">
                <td className="whitespace-nowrap px-3 py-1.5 align-top font-mono text-muted-foreground/60">
                  {key}
                </td>
                <td className="break-all whitespace-pre-wrap px-3 py-1.5 font-mono text-foreground/70">
                  {value}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <CollapsibleResult
      content={result}
      renderContent={(text) => (
        <pre className="max-h-[400px] overflow-y-auto overflow-x-auto whitespace-pre-wrap break-all rounded-md bg-muted/30 p-3 font-mono text-[12px] text-foreground/60">
          {text}
        </pre>
      )}
    />
  )
}

export function ToolResultRenderer({ toolName, input, result, isError, basePath }: ToolResultRendererProps): React.ReactElement {
  // 移动端统一纯文本渲染（不区分工具类型）
  return <DefaultResultRenderer result={result} isError={isError} />
}

export { CollapsibleResult }
