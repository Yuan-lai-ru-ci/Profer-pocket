/**
 * 覆盖层（菜单 / 抽屉 / 编辑面板）的统一键盘行为。
 *
 * 只有这一份实现：之前大图浮层单独写了 Esc，另外三个浮层都没有 —— 同一个交互在两处
 * 长得一样、行为不一样，键盘用户会被关在面板里，只能靠鼠标点空白处退出。
 *
 * 用法：
 *   const ref = useOverlay(onClose)
 *   <div className="menu" ref={ref} tabIndex={-1}>…
 */
import * as React from 'react'

/** Esc 关闭。`enabled` 为 false 时不响应（保存中、提交中不该被一把 Esc 丢进未知状态）。 */
export function useOverlay(onClose: () => void, enabled = true): React.MutableRefObject<HTMLDivElement | null> {
  const ref = React.useRef<HTMLDivElement | null>(null)

  React.useEffect(() => {
    if (!enabled) return
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') return
      // 阻止冒泡：页面其他层（例如图片浮层）不该收到同一个 Esc
      event.stopPropagation()
      onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose, enabled])

  // 打开即把焦点移进面板：键盘 Tab 才不会从页面顶部重新开始。
  // preventScroll 是必须的 —— 面板通常贴在底部，聚焦会把消息区顶一下。
  React.useEffect(() => {
    ref.current?.focus({ preventScroll: true })
  }, [])

  return ref
}
