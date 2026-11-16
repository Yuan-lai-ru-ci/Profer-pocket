/**
 * 页面诊断层：事件环形缓冲、重连计数、bundle 版本自检、推导状态快照。
 *
 * 抽出来的原因：这些逻辑有一半必须在 effect 里跑（写 ref / 触发 reload），
 * 留在页面组件里很容易顺手塞进 useMemo —— 那就是渲染期副作用，
 * 并发渲染下会被丢弃或重复执行，诊断数据本身反而失真。
 */
import * as React from 'react'
import type { SDKMessage } from '@profer/shared'
import type { MessageGroup } from '@profer/session-core'
import { isTurnFinished, isTurnSettled, turnHasTrailingPendingTool, type AssistantTimeline } from './turn-timeline'
import type { DiagData, DiagMessageSummary } from './diag'
import { WEBUI_BUILD } from './build-info'

/** 事件历史条数：够还原一次「正文消失」的时序，又不至于把浮层撑爆。 */
const DIAG_EVENT_LIMIT = 40

export interface DiagnosticsInput {
  messages: SDKMessage[]
  groups: MessageGroup[]
  timelines: AssistantTimeline[]
  lastNonSystem: { index: number; pending: boolean }
  running: boolean
  sessionActive: boolean
  lastTurnFinished: boolean
  connected: boolean
}

export interface Diagnostics {
  /** 记一条带时间戳的诊断事件（稳定引用，可安全放进回调/effect 依赖） */
  diagEvent: (line: string) => void
  /** 连接（重新）建立：重连计数 +1 */
  noteReconnect: () => void
  diagData: DiagData
  reconnectCount: number
  staleBundle: boolean
}

export function useDiagnostics(input: DiagnosticsInput): Diagnostics {
  const eventsRef = React.useRef<string[]>([])
  const reconnectCountRef = React.useRef(0)
  const lastHeadlineLenRef = React.useRef(0)
  const [staleBundle, setStaleBundle] = React.useState(false)
  /** 重连次数要能被 diagData 读到并触发一次渲染，才用 state 暴露出来。 */
  const [reconnectCount, setReconnectCount] = React.useState(0)

  const diagEvent = React.useCallback((line: string): void => {
    const now = new Date()
    const clock = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
    const list = eventsRef.current
    list.push(`${clock} ${line}`)
    if (list.length > DIAG_EVENT_LIMIT) list.shift()
  }, [])

  const noteReconnect = React.useCallback((): void => {
    reconnectCountRef.current += 1
    setReconnectCount(reconnectCountRef.current)
  }, [])

  /**
   * bundle 版本自检：fetch 服务端 index.html（no-store），对比它引用的 JS hash
   * 与当前页面加载的 hash。不一致 = 页面是旧包（bfcache 恢复 / 标签页久开没刷上新包），
   * 页脚红字提示硬刷新 —— 终结「改了但页面没刷上」的来回猜。
   */
  React.useEffect(() => {
    const check = async (): Promise<void> => {
      try {
        const response = await fetch(window.location.pathname, { cache: 'no-store', credentials: 'same-origin' })
        const html = await response.text()
        const remote = html.match(/\/webui\/assets\/(index-[^"' ]+\.js)/)?.[1]
        const localScript = Array.from(document.querySelectorAll('script[src*="index-"]'))
          .map((node) => (node as HTMLScriptElement).src)
          .find((src) => /index-[^/]+\.js/.test(src))
        const local = localScript?.match(/(index-[^/]+\.js)/)?.[1]
        const stale = Boolean(remote && local && remote !== local)
        setStaleBundle(stale)
        // 自动换新：旧包页面拉到新 index.html 后直接 reload（sessionStorage 防循环）。
        // 否则「改了但页面没刷上」会无限来回 —— 旧包里没有这段逻辑救不了自己，
        // 从这版起任何「至少是本版」的页面都能自愈。
        if (stale) {
          const key = `profer-webui-stale-reload:${remote}`
          if (!sessionStorage.getItem(key)) {
            sessionStorage.setItem(key, '1')
            window.location.reload()
          }
        }
      } catch {
        /* 自检失败不影响使用 */
      }
    }
    void check()
    const onVisible = (): void => {
      if (document.visibilityState === 'visible') void check()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])

  const { timelines, lastNonSystem } = input
  /** 最后一段「最终正文」的长度：正文消失的现场信号就藏在这个数的翻转里。 */
  const lastHeadlineLen = lastNonSystem.index >= 0
    ? (timelines[lastNonSystem.index]?.headline.length ?? 0)
    : 0

  /**
   * headline 非空→空 的翻转记进事件历史。
   * 必须在 effect 里：写 ref / 推事件属于副作用，不能放在渲染路径上。
   */
  React.useEffect(() => {
    const previous = lastHeadlineLenRef.current
    if (previous === lastHeadlineLen) return
    lastHeadlineLenRef.current = lastHeadlineLen
    if (previous > 0 && lastHeadlineLen === 0) {
      diagEvent(`headline ${previous} → 0（正文消失）`)
    } else if (previous === 0 && lastHeadlineLen > 0) {
      diagEvent(`headline 0 → ${lastHeadlineLen}（正文出现）`)
    }
  }, [lastHeadlineLen, diagEvent])

  const { messages, groups, running, sessionActive, lastTurnFinished, connected } = input
  /**
   * 诊断快照（`?diag=1` 时右下角浮层展示）。
   *
   * 排查「实时流里 headline 消失、刷新后出现」：刷新拉取的数据是干净的，坏的是实时累积态，
   * 必须把消息序列与推导结果原样打出来对比 —— 尤其是最后一轮的 items（哪个块把 headline
   * 挡了）与最近几条消息的 uuid/partial（残条来源）。
   */
  const diagData = React.useMemo<DiagData>(() => {
    const summary = (message: SDKMessage): DiagMessageSummary => {
      const content = (message as { message?: { content?: unknown } }).message?.content
      const blocks = Array.isArray(content)
        ? content.flatMap((block): string[] =>
          block && typeof block === 'object' && typeof (block as { type?: unknown }).type === 'string'
            ? [(block as { type: string }).type]
            : [])
        : []
      return {
        t: String(message.type ?? ''),
        u: String((message as { uuid?: string }).uuid ?? '').slice(0, 8),
        p: (message as { _partial?: boolean })._partial === true,
        r: (message as { isReplay?: boolean }).isReplay === true,
        b: blocks,
      }
    }
    const lastGroup = lastNonSystem.index >= 0 ? groups[lastNonSystem.index] : undefined
    const lastTimeline = lastNonSystem.index >= 0 ? timelines[lastNonSystem.index] : undefined
    return {
      build: WEBUI_BUILD,
      stale: staleBundle,
      events: eventsRef.current.slice(-25),
      reconnects: reconnectCount,
      connected,
      running,
      sessionActive,
      lastTurnFinished,
      lastNonSystem: { index: lastNonSystem.index, pending: lastNonSystem.pending },
      messages: messages.slice(-14).map(summary),
      lastTurn: lastGroup?.type === 'assistant-turn' && lastTimeline
        ? {
          headlineLen: lastTimeline.headline.length,
          headline: lastTimeline.headline.slice(0, 90),
          items: lastTimeline.items.map((item) => item.kind === 'tool'
            ? `tool:${item.tool.done ? 'done' : 'PEND'}:${item.tool.name}`
            : item.kind),
          pendingTools: lastTimeline.items.filter((item) => item.kind === 'tool' && !item.tool.done).length,
          trailingPending: turnHasTrailingPendingTool(lastGroup),
          finished: isTurnFinished(lastGroup),
          settled: isTurnSettled(lastGroup, lastTimeline),
        }
        : null,
    }
  }, [messages, groups, timelines, lastNonSystem, running, sessionActive, lastTurnFinished, connected, staleBundle, reconnectCount])

  return { diagEvent, noteReconnect, diagData, reconnectCount, staleBundle }
}
