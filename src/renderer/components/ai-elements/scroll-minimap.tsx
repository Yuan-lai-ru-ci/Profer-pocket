/**
 * ScrollMinimap — 消息导航迷你地图 + 滚动进度条
 *
 * 在消息区域右侧显示：
 * 1. 短横杠代表每条消息的位置（迷你地图），悬浮时弹出消息预览列表
 * 2. 可拖拽的滚动进度条，提供丝滑的滚动体验
 * 必须放在 StickToBottom（Conversation）内部使用。
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { AlertTriangle, Search } from 'lucide-react'
import { useStickToBottomContext } from 'use-stick-to-bottom'
import { Input } from '@/components/ui/input'
import { UserAvatar } from '@/components/chat/UserAvatar'
import { getModelLogo, resolveModelProvider } from '@/lib/model-logo'
import { channelsAtom } from '@/atoms/chat-atoms'
import { useShortcut } from '@/hooks/useShortcut'
import { cn } from '@/lib/utils'

export interface MinimapItem {
  id: string
  role: 'user' | 'assistant' | 'status'
  preview: string
  avatar?: string
  model?: string
}

interface ScrollMinimapProps {
  items: MinimapItem[]
  /** 平板触屏模式：hover 自动展开改为点击触发，避免触屏 tap 模拟 mouseenter 误弹 */
  pocketMode?: boolean
  /** 会话/对话标识：变化时强制关闭面板（切会话兜底，不依赖 key 重挂载） */
  sessionKey?: string
}

/** 最少消息数才显示迷你地图 */
const MIN_ITEMS = 1
/** 迷你地图最多渲染的横杠数 */
const MAX_BARS = 20

// ── 调试埋点（排查「切会话仍弹消息导航」问题用；定位完成后整块删除） ──

/** 置 false 即关闭全部调试输出与 HUD */
const MINIMAP_DEBUG = true

const debugLogs: string[] = []
/** 常驻记录：所有 hovered→OPEN 事件（面板每次打开都留痕，不被日志轮转挤掉） */
const debugOpenEvents: string[] = []
let debugHudEl: HTMLDivElement | null = null
let debugHudEnabled = false

/** 追加一行调试日志，并同步到右上角屏显 HUD（OPEN 事件常驻置顶 + 最近 10 条） */
function pushDebugLog(line: string): void {
  if (!MINIMAP_DEBUG) return
  const stamped = `${new Date().toTimeString().slice(0, 8)} ${line}`
  debugLogs.push(stamped)
  if (debugLogs.length > 40) debugLogs.shift()
  // 面板打开（hovered→OPEN）事件单独常驻，避免被后续日志挤掉
  if (line.includes('→OPEN')) {
    debugOpenEvents.push(stamped)
    if (debugOpenEvents.length > 20) debugOpenEvents.shift()
  }
  if (typeof document === 'undefined') return
  try {
    if (!debugHudEl) {
      debugHudEl = document.createElement('div')
      debugHudEl.style.cssText =
        'position:fixed;top:56px;right:8px;z-index:99999;background:rgba(0,0,0,.85);' +
        'color:#4ade80;padding:6px 8px;border-radius:8px;font:10px/1.5 monospace;' +
        'max-width:74vw;max-height:44vh;overflow:auto;white-space:pre-wrap;pointer-events:none;text-align:left;'
      document.body.appendChild(debugHudEl)
    }
    debugHudEnabled = true
    const openBlock = debugOpenEvents.length > 0
      ? `◆ OPEN(${debugOpenEvents.length}):\n  ${debugOpenEvents.join('\n  ')}\n──\n`
      : '◆ OPEN: 无\n──\n'
    debugHudEl.textContent = '── Minimap Debug ──\n' + openBlock + debugLogs.slice(-10).join('\n')
  } catch { /* 调试失败忽略 */ }
}

// ── Markdown 预览配置（轻量级，禁用重量级渲染） ──

const PREVIEW_REMARK_PLUGINS = [remarkGfm]

/* eslint-disable @typescript-eslint/no-explicit-any -- react-markdown components 类型复杂，使用内联对象即可 */
const PREVIEW_MD_COMPONENTS = {
  pre: ({ children }: { children?: React.ReactNode }) => <pre className="text-[11px] opacity-70 truncate">{children}</pre>,
  code: ({ children }: { children?: React.ReactNode }) => <code className="text-[11px] bg-muted/50 px-0.5 rounded">{children}</code>,
  img: () => null as unknown as React.ReactElement,
  a: ({ children }: { children?: React.ReactNode }) => <span>{children}</span>,
} as const
/* eslint-enable @typescript-eslint/no-explicit-any */

// ── 辅助函数 ──

/** 计算 node 相对于 container 的实际顶部偏移（递归累积 offsetTop） */
function getOffsetTopRelativeTo(node: HTMLElement, container: HTMLElement): number {
  let top = 0
  let el: HTMLElement | null = node
  while (el && el !== container) {
    top += el.offsetTop
    el = el.offsetParent as HTMLElement | null
  }
  return top
}

/** 转义正则特殊字符 */
function escapeRegExp(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// ── 主组件 ──

export function ScrollMinimap({ items, pocketMode = false, sessionKey }: ScrollMinimapProps): React.ReactElement | null {
  const { scrollRef, stopScroll, state: stickyState } = useStickToBottomContext()
  const [hovered, setHovered] = React.useState(false)
  const [isLeaving, setIsLeaving] = React.useState(false)
  // 触屏设备自动进触屏模式（hover:none 匹配手机/平板 WebView）；pocketMode 为显式覆盖。
  // 这样即使上层 pocketMode 传递中断，触屏设备也不会走桌面 hover 逻辑误弹。
  const isTouchDevice = React.useMemo(
    () => typeof window !== 'undefined' && (window.matchMedia('(hover: none)').matches || 'ontouchstart' in window),
    []
  )
  const touchMode = pocketMode || isTouchDevice
  // 切会话/对话时记录旧标识与变化时间，用于强制关闭面板 + 防「幽灵点击」
  const lastSessionKeyRef = React.useRef<string | undefined>(sessionKey)
  const sessionKeyChangeAtRef = React.useRef(0)
  const [visibleIds, setVisibleIds] = React.useState<Set<string>>(new Set())
  /** 主区视口几何中心当前对应的消息 id —— 面板打开时作为列表居中锚点 */
  const [centerVisibleId, setCenterVisibleId] = React.useState<string | undefined>(undefined)
  const [canScroll, setCanScroll] = React.useState(false)
  const [searchQuery, setSearchQuery] = React.useState('')
  const [isDragging, setIsDragging] = React.useState(false)
  const [scrollMetrics, setScrollMetrics] = React.useState({ scrollTop: 0, scrollHeight: 1, clientHeight: 1 })
  const closeTimerRef = React.useRef<ReturnType<typeof setTimeout>>()
  const fadeTimerRef = React.useRef<ReturnType<typeof setTimeout>>()
  const openTimerRef = React.useRef<ReturnType<typeof setTimeout>>()
  const searchInputRef = React.useRef<HTMLInputElement>(null)
  const trackRef = React.useRef<HTMLDivElement>(null)
  const listRef = React.useRef<HTMLDivElement>(null)
  const triggerRef = React.useRef<HTMLDivElement>(null)
  /** 调试：追踪 canScroll 变化，观察「切会话可滚动 false→true 首次渲染」时序 */
  const canScrollRef = React.useRef(false)

  // ── 调试：统一包装 setHovered，记录每次开/关的来源与上下文 ──

  const setHoveredDbg = React.useCallback(
    (source: string) => (next: boolean | ((prev: boolean) => boolean)): void => {
      setHovered((prev) => {
        const n = typeof next === 'function' ? (next as (prev: boolean) => boolean)(prev) : next
        if (n !== prev) {
          pushDebugLog(`hovered ${prev ? 'OPEN' : 'closed'}→${n ? 'OPEN' : 'closed'} via ${source} | touch=${touchMode} | sk=${sessionKey ?? '∅'} | Δ=${Date.now() - sessionKeyChangeAtRef.current}ms`)
        }
        return n
      })
    },
    [touchMode, sessionKey],
  )

  // ── 调试：首挂载时输出环境判定（确认 touchMode 在真机上是否生效） ──

  React.useEffect(() => {
    pushDebugLog(`MOUNT | pocket=${pocketMode} | touchDetect=${isTouchDevice} | touchMode=${touchMode} | sk=${sessionKey ?? '∅'} | items=${items.length}`)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── 组件卸载时清理计时器 ──

  React.useEffect(() => {
    return () => {
      if (closeTimerRef.current) clearTimeout(closeTimerRef.current)
      if (fadeTimerRef.current) clearTimeout(fadeTimerRef.current)
      if (openTimerRef.current) clearTimeout(openTimerRef.current)
    }
  }, [])

  // ── 可见消息 + 滚动指标追踪 ──

  React.useEffect(() => {
    const el = scrollRef.current
    if (!el) return

    const update = (): void => {
      const { scrollTop, scrollHeight, clientHeight } = el
      const nextCanScroll = scrollHeight > clientHeight + 10
      if (nextCanScroll !== canScrollRef.current) {
        canScrollRef.current = nextCanScroll
        pushDebugLog(`canScroll→${nextCanScroll ? 'true' : 'false'} | scrollTop=${Math.round(scrollTop)} sh=${scrollHeight} ch=${clientHeight}`)
      }
      setCanScroll(nextCanScroll)
      setScrollMetrics({ scrollTop, scrollHeight, clientHeight })
      if (scrollHeight <= 0) return

      const viewportCenter = scrollTop + clientHeight / 2
      const nodes = el.querySelectorAll<HTMLElement>('[data-message-id]')
      const ids = new Set<string>()
      let centerId: string | undefined
      for (const node of nodes) {
        const top = getOffsetTopRelativeTo(node, el)
        const bottom = top + node.offsetHeight
        if (bottom > scrollTop && top < scrollTop + clientHeight) {
          const id = node.getAttribute('data-message-id')
          if (id) ids.add(id)
        }
        if (centerId === undefined && top <= viewportCenter && bottom > viewportCenter) {
          centerId = node.getAttribute('data-message-id') ?? undefined
        }
      }
      setVisibleIds(ids)
      setCenterVisibleId(centerId)
    }

    update()
    el.addEventListener('scroll', update, { passive: true })
    const observer = new ResizeObserver(update)
    observer.observe(el)

    return () => {
      el.removeEventListener('scroll', update)
      observer.disconnect()
    }
  }, [scrollRef])

  // ── 面板打开时自动聚焦搜索框 ──

  React.useEffect(() => {
    if (hovered && searchInputRef.current) {
      const timer = setTimeout(() => searchInputRef.current?.focus(), 80)
      return () => clearTimeout(timer)
    }
  }, [hovered])

  // ── 面板打开时把当前可见消息滚到列表中间，避免每次都从顶部开始 ──

  React.useEffect(() => {
    if (!hovered) return
    const timer = setTimeout(() => {
      const list = listRef.current
      if (!list) return
      const target = list.querySelector<HTMLElement>('[data-minimap-visible="true"]')
      if (!target) return
      // listRef 没有 position 设置，offsetTop / getOffsetTopRelativeTo 都不可靠，
      // 直接用 getBoundingClientRect 计算 target 相对 list 视口的偏移
      const listRect = list.getBoundingClientRect()
      const targetRect = target.getBoundingClientRect()
      const offsetInList = (targetRect.top - listRect.top) + list.scrollTop
      const offset = offsetInList - (list.clientHeight - target.offsetHeight) / 2
      list.scrollTo({ top: Math.max(0, offset), behavior: 'auto' })
    }, 0)
    return () => clearTimeout(timer)
  }, [hovered])

  // ── 面板关闭时清空搜索 ──

  React.useEffect(() => {
    if (!hovered) setSearchQuery('')
  }, [hovered])

  // ── Cmd+F / Ctrl+F 快捷键：打开面板并聚焦搜索 ──

  const handleShortcutOpen = React.useCallback(() => {
    if (closeTimerRef.current) clearTimeout(closeTimerRef.current)
    if (fadeTimerRef.current) clearTimeout(fadeTimerRef.current)
    if (openTimerRef.current) { clearTimeout(openTimerRef.current); openTimerRef.current = undefined }
    setIsLeaving(false)
    setHoveredDbg('shortcut')(true)
  }, [setHoveredDbg])

  useShortcut('file-find', handleShortcutOpen, items.length >= MIN_ITEMS && canScroll)

  // ── 鼠标进出控制（仅迷你地图区域） ──

  /** 鼠标进入后需停留此时间（ms）才展开面板，防止掠过时闪烁 */
  const OPEN_DELAY = 180

  const handleMouseEnter = (): void => {
    // 调试：若真机触屏在此仍触发，说明 touchMode 判定失效——这是关键线索
    if (MINIMAP_DEBUG) pushDebugLog(`mouseenter FIRED | touch=${touchMode}${touchMode ? ' →returned' : ' →OPEN_DELAY...'}`)
    // 触屏无 hover 语义（tap 会模拟 mouseenter），触屏/平板模式不在此展开，改由点击触发
    if (touchMode) return
    if (closeTimerRef.current) clearTimeout(closeTimerRef.current)
    if (fadeTimerRef.current) clearTimeout(fadeTimerRef.current)
    setIsLeaving(false)

    // 面板已打开则无需重复触发
    if (hovered) return

    // 延迟打开：鼠标需在触发条上停留足够时间
    if (!openTimerRef.current) {
      openTimerRef.current = setTimeout(() => {
        setHoveredDbg('mouseenter')(true)
        openTimerRef.current = undefined
      }, OPEN_DELAY)
    }
  }

  const handleMouseLeave = (): void => {
    // 触屏模式下收起改由「点击面板外」控制，不走 mouseleave
    if (touchMode) return
    // 尚未打开就离开了 → 取消打开定时器
    if (openTimerRef.current) {
      clearTimeout(openTimerRef.current)
      openTimerRef.current = undefined
    }

    if (!hovered) return

    closeTimerRef.current = setTimeout(() => {
      setIsLeaving(true)
      fadeTimerRef.current = setTimeout(() => {
        setHoveredDbg('mouseleave')(false)
        setIsLeaving(false)
      }, 80)
    }, 40)
  }

  // ── 触屏点击触发（pocketMode：无 hover，点击触发条展开/收起） ──

  const handleTriggerClick = React.useCallback((e: React.MouseEvent): void => {
    e.stopPropagation()
    const delta = Date.now() - sessionKeyChangeAtRef.current
    if (MINIMAP_DEBUG) {
      pushDebugLog(`trigger-click raw (${Math.round(e.clientX)},${Math.round(e.clientY)}) Δ=${delta}ms${delta < 300 ? ' →IGNORED(ghost窗口内)' : ' →TOGGLE'}`)
    }
    // 切会话/对话后的短暂窗口内忽略触发条点击，防触屏合成 click 的「幽灵点击」误展开
    if (delta < 300) return
    if (closeTimerRef.current) clearTimeout(closeTimerRef.current)
    if (fadeTimerRef.current) clearTimeout(fadeTimerRef.current)
    if (openTimerRef.current) { clearTimeout(openTimerRef.current); openTimerRef.current = undefined }
    setIsLeaving(false)
    setHoveredDbg('trigger-click')((prev) => !prev)
  }, [setHoveredDbg])

  // ── 触屏点击面板外关闭 ──

  React.useEffect(() => {
    if (!touchMode || !hovered) return
    const onDocClick = (e: MouseEvent): void => {
      const target = e.target as Node
      const inPanel = listRef.current?.closest('[data-minimap-panel]')?.contains(target) ?? false
      const inTrigger = triggerRef.current?.contains(target) ?? false
      if (!inPanel && !inTrigger) setHoveredDbg('outside-click')(false)
    }
    document.addEventListener('click', onDocClick)
    return () => document.removeEventListener('click', onDocClick)
  }, [touchMode, hovered])

  // ── 切换会话/对话：强制关闭面板（兜底，不依赖 key 重挂载） ──

  React.useEffect(() => {
    const changed = lastSessionKeyRef.current !== sessionKey
    lastSessionKeyRef.current = sessionKey
    // 防幽灵点击窗口必须在每次会话变化（含首次挂载）都武装：
    // 首次挂载 lastSessionKeyRef 初始等于 sessionKey，changed=false 提前 return 会漏掉武装，
    // 导致切会话后任意触发条点击都直接 toggle（bug 来源候选）。
    sessionKeyChangeAtRef.current = Date.now()
    if (MINIMAP_DEBUG) pushDebugLog(`session-key effect | changed=${changed} | sk=${sessionKey ?? '∅'} | ghostGuard=armed`)
    if (!changed) return
    if (closeTimerRef.current) clearTimeout(closeTimerRef.current)
    if (fadeTimerRef.current) clearTimeout(fadeTimerRef.current)
    if (openTimerRef.current) { clearTimeout(openTimerRef.current); openTimerRef.current = undefined }
    setIsLeaving(false)
    setHoveredDbg('session-key')(false)
  }, [sessionKey, setHoveredDbg])

  // ── 跳转到指定消息（直接操作 scrollTop，绕过 scrollIntoView） ──

  const scrollToMessage = React.useCallback((id: string) => {
    const el = scrollRef.current
    if (!el) return
    const target = Array.from(el.querySelectorAll<HTMLElement>('[data-message-id]')).find(
      (node) => node.getAttribute('data-message-id') === id
    )
    if (!target) return

    stopScroll()
    stickyState.animation = undefined
    stickyState.velocity = 0
    stickyState.accumulated = 0

    const offsetTop = getOffsetTopRelativeTo(target, el)
    const targetHeight = target.offsetHeight
    const viewportHeight = el.clientHeight
    const scrollTarget = targetHeight < viewportHeight
      ? offsetTop - (viewportHeight - targetHeight) / 2
      : offsetTop - 32
    el.scrollTo({ top: Math.max(0, scrollTarget), behavior: 'smooth' })

    setHoveredDbg('scroll-to-msg')(false)
  }, [scrollRef, stopScroll, stickyState, setHoveredDbg])

  // ── 搜索过滤 ──

  const filteredItems = React.useMemo(() => {
    if (!searchQuery.trim()) return items
    const q = searchQuery.toLowerCase()
    return items.filter((item) => item.preview.toLowerCase().includes(q))
  }, [items, searchQuery])

  /** 列表居中锚点：优先用主区视口中心对应的消息；该消息被搜索过滤掉时退回第一条可见消息 */
  const anchorId = React.useMemo(() => {
    if (centerVisibleId && filteredItems.some((item) => item.id === centerVisibleId)) {
      return centerVisibleId
    }
    return filteredItems.find((item) => visibleIds.has(item.id))?.id
  }, [centerVisibleId, filteredItems, visibleIds])

  // ── 滚动条滑块拖拽 ──

  const handleThumbMouseDown = React.useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()

    const el = scrollRef.current
    const track = trackRef.current
    if (!el || !track) return

    // 停止 StickToBottom 自动滚动
    stopScroll()
    stickyState.animation = undefined
    stickyState.velocity = 0
    stickyState.accumulated = 0

    setIsDragging(true)
    const startY = e.clientY
    const startScrollTop = el.scrollTop
    const trackHeight = track.clientHeight
    const { scrollHeight, clientHeight } = el
    const scrollRange = scrollHeight - clientHeight
    const thumbHeight = Math.max(trackHeight * 0.1, (clientHeight / scrollHeight) * trackHeight)
    const scrollableTrack = trackHeight - thumbHeight

    const onMouseMove = (ev: MouseEvent): void => {
      ev.preventDefault()
      const delta = ev.clientY - startY
      const scrollDelta = scrollableTrack > 0 ? (delta / scrollableTrack) * scrollRange : 0
      el.scrollTop = Math.max(0, Math.min(scrollRange, startScrollTop + scrollDelta))
    }

    const onMouseUp = (): void => {
      setIsDragging(false)
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
      document.body.style.userSelect = ''
      document.body.style.cursor = ''
    }

    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'grabbing'
    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
  }, [scrollRef, stopScroll, stickyState])

  // ── 轨道点击跳转 ──

  const handleTrackMouseDown = React.useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    // 只响应直接点击轨道背景，忽略点击滑块
    if (e.target !== e.currentTarget) return

    const track = trackRef.current
    const el = scrollRef.current
    if (!track || !el) return

    stopScroll()
    stickyState.animation = undefined
    stickyState.velocity = 0
    stickyState.accumulated = 0

    const rect = track.getBoundingClientRect()
    const clickRatio = (e.clientY - rect.top) / rect.height
    const { scrollHeight, clientHeight } = el
    const targetTop = clickRatio * (scrollHeight - clientHeight)
    el.scrollTo({ top: Math.max(0, targetTop), behavior: 'smooth' })
  }, [scrollRef, stopScroll, stickyState])

  if (items.length < MIN_ITEMS || !canScroll) return null

  // ── 迷你地图条纹 ──

  const barCount = Math.min(items.length, MAX_BARS)

  // ── 滚动条滑块尺寸计算 ──

  const { scrollTop, scrollHeight, clientHeight } = scrollMetrics
  const scrollRange = scrollHeight - clientHeight
  const thumbRatio = scrollHeight > 0 ? Math.min(clientHeight / scrollHeight, 1) : 1
  const thumbHeightPct = Math.max(10, thumbRatio * 100)
  const thumbTopPct = scrollRange > 0 ? (scrollTop / scrollRange) * (100 - thumbHeightPct) : 0

  return (
    <div className="absolute right-1 top-0 bottom-0 z-30 flex pointer-events-none">
      {/* ── 迷你地图悬停区域（面板 + 横杠） ── */}
      <div className="flex items-start h-full">
        {/* 展开面板 */}
        {hovered && (
          <div
            data-minimap-panel
            className={cn(
              'mr-1 w-[280px] rounded-lg border bg-popover shadow-xl origin-top-right flex flex-col overflow-hidden pointer-events-auto',
              isLeaving
                ? 'animate-out fade-out-0 zoom-out-95 duration-75'
                : 'animate-in fade-in-0 zoom-in-95 duration-150'
            )}
            style={{ maxHeight: 'min(420px, 60vh)', marginTop: 12 }}
            onMouseEnter={handleMouseEnter}
            onMouseLeave={handleMouseLeave}
          >
            {/* 标题栏 */}
            <div className="flex items-center justify-between px-3 py-2 border-b shrink-0">
              <span className="text-xs font-medium text-popover-foreground/70">消息导航</span>
              <span className="text-[11px] text-muted-foreground tabular-nums">
                {visibleIds.size}/{items.length}
              </span>
            </div>

            {/* 搜索框 */}
            <div className="px-2 py-1.5 border-b shrink-0">
              <div className="relative">
                <Search className="absolute left-2 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground/50" />
                <Input
                  ref={searchInputRef}
                  placeholder="搜索消息..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onFocus={() => {
                    if (closeTimerRef.current) clearTimeout(closeTimerRef.current)
                    if (fadeTimerRef.current) clearTimeout(fadeTimerRef.current)
                    setIsLeaving(false)
                  }}
                  className="h-7 text-xs pl-7"
                />
              </div>
            </div>

            {/* 消息列表 */}
            <div ref={listRef} className="overflow-y-auto flex-1 p-1.5 space-y-0.5 scrollbar-thin">
              {filteredItems.length === 0 ? (
                <div className="py-6 text-center text-xs text-muted-foreground">
                  未找到匹配消息
                </div>
              ) : (
                filteredItems.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    data-minimap-visible={item.id === anchorId ? 'true' : undefined}
                    className={cn(
                      'flex items-start gap-2 w-full rounded-md px-2 py-1.5 text-left transition-colors hover:bg-accent',
                      visibleIds.has(item.id) && 'bg-accent/50'
                    )}
                    onClick={() => scrollToMessage(item.id)}
                  >
                    <ItemIcon item={item} />
                    <div className="flex-1 min-w-0">
                      <HighlightedPreview text={item.preview} query={searchQuery} />
                    </div>
                  </button>
                ))
              )}
            </div>
          </div>
        )}

        {/* ── 迷你地图横杠（紧凑排列）—— 只有这里触发面板展开 ── */}
        <div
          ref={triggerRef}
          className="relative mt-3 flex-shrink-0 pointer-events-auto"
          style={{ width: 24, height: barCount * 6 }}
          onMouseEnter={handleMouseEnter}
          onMouseLeave={handleMouseLeave}
          onClick={touchMode ? handleTriggerClick : undefined}
        >
          {Array.from({ length: barCount }, (_, i) => {
            const start = Math.floor((i * items.length) / barCount)
            const end = Math.floor(((i + 1) * items.length) / barCount)
            const group = items.slice(start, end)
            const isVisible = group.some((it) => visibleIds.has(it.id))
            const hasUser = group.some((it) => it.role === 'user')
            const top = ((i + 0.5) / barCount) * 100
            return (
              <div
                key={i}
                className={cn(
                  'absolute left-1 h-[2px] w-[20px] rounded-full transition-colors',
                  isVisible
                    ? 'bg-primary dark:bg-primary/70 minimap-visible-indicator'
                    : hasUser
                      ? 'bg-primary/25 dark:bg-primary/15'
                      : 'bg-primary/40 dark:bg-primary/25'
                )}
                style={{ top: `${top}%` }}
              />
            )
          })}
        </div>
      </div>

      {/* ── 滚动进度条 ── */}
      <div className="relative ml-[4px] py-4 flex-shrink-0 pointer-events-auto" style={{ width: 7 }}>
        <div
          ref={trackRef}
          className="relative h-full rounded-full cursor-pointer scroll-progress-track"
          onMouseDown={handleTrackMouseDown}
        >
          <div
            className={cn(
              'absolute left-0 right-0 rounded-full transition-colors duration-100 scroll-progress-thumb',
              isDragging
                ? 'scroll-progress-thumb-active cursor-grabbing'
                : 'cursor-grab'
            )}
            style={{
              height: `${thumbHeightPct}%`,
              top: `${thumbTopPct}%`,
            }}
            onMouseDown={handleThumbMouseDown}
          />
        </div>
      </div>
    </div>
  )
}

// ── 子组件 ──

function ItemIcon({ item }: { item: MinimapItem }): React.ReactElement {
  const channels = useAtomValue(channelsAtom)
  if (item.role === 'user' && item.avatar) {
    return <UserAvatar avatar={item.avatar} size={16} className="mt-0.5" />
  }
  if ((item.role === 'assistant') && item.model) {
    return (
      <img
        src={getModelLogo(item.model, resolveModelProvider(item.model, channels))}
        alt=""
        className="size-4 shrink-0 mt-0.5 rounded-[20%] object-cover"
      />
    )
  }
  if (item.role === 'status') {
    return <AlertTriangle className="size-4 shrink-0 mt-0.5 text-destructive" />
  }
  return <div className="size-4 shrink-0 mt-0.5 rounded-[20%] bg-muted" />
}

/** Markdown 预览（无搜索时）或 纯文本+高亮（搜索时） */
function HighlightedPreview({ text, query }: { text: string; query: string }): React.ReactElement {
  if (!text) {
    return <span className="text-xs opacity-40">(空消息)</span>
  }

  if (query.trim()) {
    const escaped = escapeRegExp(query)
    const parts = text.split(new RegExp(`(${escaped})`, 'gi'))
    return (
      <span className="text-xs text-popover-foreground/80 line-clamp-3">
        {parts.map((part, i) =>
          part.toLowerCase() === query.toLowerCase()
            ? <mark key={i} className="bg-primary/20 text-primary rounded-sm px-0.5">{part}</mark>
            : part
        )}
      </span>
    )
  }

  return (
    <div className="prose prose-sm dark:prose-invert max-w-none text-xs text-popover-foreground/80 prose-p:my-0 prose-headings:my-0.5 prose-headings:text-xs prose-li:my-0 [&>*:first-child]:mt-0 [&>*:last-child]:mb-0 line-clamp-3 overflow-hidden">
      <Markdown remarkPlugins={PREVIEW_REMARK_PLUGINS} components={PREVIEW_MD_COMPONENTS}>
        {text}
      </Markdown>
    </div>
  )
}
