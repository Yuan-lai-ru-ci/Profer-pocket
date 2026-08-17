/**
 * useLongPress — 触屏长按检测 hook
 *
 * 触屏设备没有右键 / 双击语义，Pocket 左侧栏用「长按弹菜单」替代：
 * - touchstart：记录起点坐标，启动 delay 毫秒定时器
 * - touchmove：位移超过 moveTolerance 视为滚动，取消长按
 * - 定时器触发：调用 onLongPress()，并标记已触发
 * - touchend：清理定时器；若已触发，置 suppressClick 标记（拦截松手后的合成 click，避免误打开会话）
 *
 * 长按范式对齐 SessionMiniMapPopover 的 useSessionMiniMapHover 触屏实现，精简为纯检测用途。
 * 桌面（hover / 右键 / 双击）路径完全不经过本 hook 的 handler。
 */

import * as React from 'react'

/** 长按后手指位移超过该阈值视为滚动 / 取消（与 SessionMiniMapPopover 一致） */
const LONG_PRESS_MOVE_TOLERANCE = 12

export interface UseLongPressOptions {
  /** 长按触发延迟（ms），默认 500 */
  delay?: number
  /** 位移容差（px），默认 12；超过视为滚动取消长按 */
  moveTolerance?: number
  /** 长按触发回调 */
  onLongPress: () => void
  /** 禁用长按（如非触屏设备） */
  disabled?: boolean
}

export interface LongPressHandlers {
  handleTouchStart: (e: React.TouchEvent) => void
  handleTouchMove: (e: React.TouchEvent) => void
  handleTouchEnd: () => void
  handleTouchCancel: () => void
  /** 消费「长按已触发」标记：每次调用后清除；返回 true 表示应跳过本次 click 的默认行为 */
  shouldSuppressClick: () => boolean
}

export function useLongPress({
  delay = 500,
  moveTolerance = LONG_PRESS_MOVE_TOLERANCE,
  onLongPress,
  disabled = false,
}: UseLongPressOptions): LongPressHandlers {
  const timerRef = React.useRef<ReturnType<typeof setTimeout>>()
  const triggeredRef = React.useRef(false)
  const suppressClickRef = React.useRef(false)
  const startPointRef = React.useRef<{ x: number; y: number } | null>(null)
  // onLongPress 闭包引用始终指向最新，避免定时器回调捕获过期值
  const onLongPressRef = React.useRef(onLongPress)
  onLongPressRef.current = onLongPress

  React.useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [])

  const clearTimer = React.useCallback((): void => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = undefined
    }
  }, [])

  const reset = React.useCallback((): void => {
    clearTimer()
    startPointRef.current = null
    triggeredRef.current = false
  }, [clearTimer])

  const handleTouchStart = React.useCallback((e: React.TouchEvent): void => {
    if (disabled) return
    reset()
    const touch = e.touches[0]
    if (!touch) return
    startPointRef.current = { x: touch.clientX, y: touch.clientY }
    timerRef.current = setTimeout(() => {
      triggeredRef.current = true
      startPointRef.current = null
      onLongPressRef.current()
    }, delay)
  }, [disabled, delay, reset])

  const handleTouchMove = React.useCallback((e: React.TouchEvent): void => {
    const start = startPointRef.current
    if (!start) return
    const touch = e.touches[0]
    if (!touch) return
    const dx = touch.clientX - start.x
    const dy = touch.clientY - start.y
    // 位移超阈值视为滚动，取消长按
    if (Math.hypot(dx, dy) > moveTolerance) {
      clearTimer()
      startPointRef.current = null
      triggeredRef.current = false
    }
  }, [moveTolerance, clearTimer])

  const handleTouchEnd = React.useCallback((): void => {
    clearTimer()
    startPointRef.current = null
    // 长按已触发：拦截随后的合成 click（避免误打开会话 / 误选中项目）
    if (triggeredRef.current) {
      triggeredRef.current = false
      suppressClickRef.current = true
    }
  }, [clearTimer])

  const handleTouchCancel = React.useCallback((): void => {
    clearTimer()
    startPointRef.current = null
    triggeredRef.current = false
    suppressClickRef.current = false
  }, [clearTimer])

  /** 消费「长按已触发」标记；返回 true 表示应跳过本次 click 的默认行为 */
  const shouldSuppressClick = React.useCallback((): boolean => {
    const suppress = suppressClickRef.current
    suppressClickRef.current = false
    return suppress
  }, [])

  return {
    handleTouchStart,
    handleTouchMove,
    handleTouchEnd,
    handleTouchCancel,
    shouldSuppressClick,
  }
}
