/**
 * 诊断浮层：URL 带 `?diag=1` 时挂在页面右下角，每 400ms 刷新一次实时推导状态。
 *
 * 用途：排查「实时消息流里正文 headline 消失、刷新后又出现」这类只有客户端
 * 累积状态能看见的问题 —— 刷新拉取的数据是干净的，坏的是实时累积过程，
 * 必须把累积态（消息序列、分组、timeline 推导、运行标志）原样打出来对比。
 *
 * 交互：可选中复制；「复制」按钮一键写剪贴板（不可用时退化为全选）；
 * 「收起」缩成右下角小 pill，不挡输入框，随时点开。
 */
import * as React from 'react'

export interface DiagMessageSummary {
  t: string
  u: string
  p: boolean
  r: boolean
  b: string[]
}

export interface DiagLastTurn {
  headlineLen: number
  headline: string
  items: string[]
  pendingTools: number
  trailingPending: boolean
  finished: boolean
  settled: boolean
}

export interface DiagData {
  build: string
  stale: boolean
  events: string[]
  reconnects: number
  connected: boolean
  running: boolean
  sessionActive: boolean
  lastTurnFinished: boolean
  lastNonSystem: { index: number; pending: boolean }
  messages: DiagMessageSummary[]
  lastTurn: DiagLastTurn | null
}

export function DiagOverlay({ data }: { data: DiagData }): React.ReactElement | null {
  const [enabled] = React.useState(
    () => new URLSearchParams(window.location.search).has('diag'),
  )
  const [collapsed, setCollapsed] = React.useState(false)
  const [copied, setCopied] = React.useState<false | true | 'manual'>(false)
  const dataRef = React.useRef(data)
  dataRef.current = data
  const bodyRef = React.useRef<HTMLPreElement>(null)

  React.useEffect(() => {
    if (!enabled || collapsed) return
    // 直接写 textContent，不走 React 重渲染：流式期间每帧渲染，400ms 刷新足够
    const timer = window.setInterval(() => {
      if (bodyRef.current) bodyRef.current.textContent = JSON.stringify(dataRef.current, null, 1)
    }, 400)
    return () => { window.clearInterval(timer) }
  }, [enabled, collapsed])

  const copy = React.useCallback((): void => {
    const text = JSON.stringify(dataRef.current, null, 1)
    // 先同步把最新内容写进面板（interval 里的可能还没渲染），再全选
    const node = bodyRef.current
    if (node) {
      if (node.textContent !== text) node.textContent = text
      const range = document.createRange()
      range.selectNodeContents(node)
      const selection = window.getSelection()
      selection?.removeAllRanges()
      selection?.addRange(range)
    }
    // HTTP 局域网（非安全上下文）没有 navigator.clipboard；execCommand 必须在
    // 用户手势的同步栈内跑，所以先试它，失败才异步走 clipboard API。
    let ok = false
    try { ok = document.execCommand('copy') } catch { ok = false }
    if (ok) {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
      return
    }
    void navigator.clipboard?.writeText?.(text)
      .then(() => {
        setCopied(true)
        window.setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => {
        // 都不可用：内容已全选，给出明确的手动提示
        setCopied('manual')
        window.setTimeout(() => setCopied(false), 2500)
      })
  }, [])

  if (!enabled) return null

  if (collapsed) {
    return (
      <button
        type="button"
        className="diag-pill"
        onClick={() => setCollapsed(false)}
        title="展开诊断面板"
      >
        DIAG
      </button>
    )
  }

  return (
    <div className="diag-panel" data-testid="webui-diag-panel">
      <div className="diag-bar">
        <span className="diag-title">DIAG</span>
        <button type="button" className="diag-btn" onClick={copy}>
          {copied === 'manual' ? '已全选，按 Ctrl+C' : copied ? '已复制 ✓' : '复制'}
        </button>
        <button type="button" className="diag-btn" onClick={() => setCollapsed(true)}>
          收起
        </button>
      </div>
      <pre className="diag-body" ref={bodyRef} data-testid="webui-diag" />
    </div>
  )
}
