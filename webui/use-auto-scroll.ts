/**
 * 消息区的滚动行为（贴底跟随 / 上滑阅读 / 翻页锚点）。
 *
 * 从 main.tsx 抽出来的原因：这里有四条互相制衡的机制（rAF 贴底、scroll 事件判读、
 * 前置拼接锚点、首屏「未被用户接管」模式），全部挤在页面组件里时，一改流式行为就容易
 * 误伤另一个。抽成 hook 后只有一个出口：scrollRef / 几个显式动作。
 *
 * 不变量（都是被真机 bug 反推出来的）：
 * 1. 只在 scrollHeight **增长**时贴底 —— 不能见 gap>0 就贴，否则用户小幅上滑的 gap
 *    会被抢先归零、贴底状态永远为真，人就被钉在底部。
 * 2. scrollTop 未变的 scroll 事件是「布局撑高」（图片/卡片异步加载）引发的，不是用户滚动。
 * 3. 前置拼接（加载更早 / 重拉合并）后必须按「距底部距离」还原，否则视口会跳。
 */
import * as React from 'react'

export interface AutoScroll {
  scrollRef: React.MutableRefObject<HTMLDivElement | null>
  /** 是否贴着底部；翻页/主动接管时由调用方置 false */
  atBottomRef: React.MutableRefObject<boolean>
  /** 前置拼接后用于还原视口；由 layout effect 消费 */
  prependAnchorRef: React.MutableRefObject<number | null>
  jumpVisible: boolean
  scrollToLatest: (smooth: boolean) => void
  handleStreamScroll: () => void
  /** 滚轮 / 触摸 / 键盘滚动：交还滚动控制权 */
  handleUserEngage: () => void
  /** 记录「距底部距离」，供前置拼接后还原 */
  capturePrependAnchor: () => void
  /** 用户开始翻历史：取消贴底并露出「回到最新」 */
  markReadingHistory: () => void
}

/**
 * @param messagesLength 消息条数：变化后执行前置拼接的视口还原
 * @param onDiag 可选诊断钩子（判断贴底状态翻转时记一条）
 */
export function useAutoScroll(messagesLength: number, onDiag?: (line: string) => void): AutoScroll {
  const scrollRef = React.useRef<HTMLDivElement | null>(null)
  const prependAnchorRef = React.useRef<number | null>(null)
  const atBottomRef = React.useRef(true)
  /** 上次已知的 scrollTop：区分「用户滚动」与「布局撑高引发的 scroll 事件」。 */
  const lastScrollTopRef = React.useRef(0)
  /**
   * 首屏「自动贴底模式」：刷新后直到用户首次滚动输入之前，任何布局变化
   * （图片/图表加载撑高、GoalBar 等顶部区块迟到插入）都不许把视口拽走。
   * 用户一滚（滚轮/触摸/键盘）立即交还控制，恢复正常贴底语义。
   */
  const userEngagedRef = React.useRef(false)
  const [jumpVisible, setJumpVisible] = React.useState(false)
  /** 诊断回调每次渲染可能变，用 ref 拿最新值，避免把 rAF 循环重建。 */
  const diagRef = React.useRef(onDiag)
  diagRef.current = onDiag

  /** 前置拼接后按距底距离还原滚动位置，否则视口会跳掉一大截。 */
  React.useLayoutEffect(() => {
    const anchor = prependAnchorRef.current
    if (anchor === null) return
    prependAnchorRef.current = null
    const node = scrollRef.current
    if (node) {
      node.scrollTop = node.scrollHeight - anchor
      lastScrollTopRef.current = node.scrollTop
    }
  }, [messagesLength])

  /**
   * 贴底才跟随。用户上滑读历史时不能被新内容拽回底部，只露出一颗「回到最新」。
   * 平滑滚动只给按钮用：流式输出时每帧重置 smooth 动画会明显拖影。
   */
  const scrollToLatest = React.useCallback((smooth: boolean): void => {
    const node = scrollRef.current
    if (!node) return
    node.scrollTo({ top: node.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
    lastScrollTopRef.current = node.scrollTop
    atBottomRef.current = true
    setJumpVisible(false)
  }, [])

  /**
   * 贴底跟随：每帧检查，内容变高的**当帧**就把 scrollTop 顶到底。
   *
   * 同帧修正（rAF 在 paint 之前执行），不产生可见中间态 —— 定时追赶（每 350ms 跳一次）
   * 会在撑高期反复闪烁（iPhone 实测）。首屏自动贴底模式下内容增高即贴；
   * 用户首次滚动后交还控制，只在「仍然贴底且内容增高」时跟随。
   */
  React.useEffect(() => {
    let raf = 0
    let lastHeight = 0
    const tick = (): void => {
      const node = scrollRef.current
      if (node) {
        const height = node.scrollHeight
        if (height > lastHeight && (atBottomRef.current || !userEngagedRef.current)) {
          node.scrollTop = height
          lastScrollTopRef.current = node.scrollTop
        }
        lastHeight = height
      }
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [])

  const handleStreamScroll = React.useCallback((): void => {
    const node = scrollRef.current
    if (!node) return
    // scrollTop 未变的 scroll 事件是「布局撑高」（图片/卡片异步加载）引发的，
    // 不是用户滚动。iOS Safari 会触发这类事件 —— 不许据此改 atBottomRef，
    // 否则贴底跟随被误停，上方继续增高时视口就悬在「上面」。
    if (node.scrollTop === lastScrollTopRef.current) return
    lastScrollTopRef.current = node.scrollTop
    const atBottom = node.scrollHeight - node.scrollTop - node.clientHeight < 48
    if (atBottom !== atBottomRef.current) {
      diagRef.current?.(`atBottom ${atBottomRef.current}→${atBottom} scrollTop=${Math.round(node.scrollTop)} sh=${node.scrollHeight} ch=${node.clientHeight}`)
    }
    atBottomRef.current = atBottom
    // 只在可见性真变化时 setState，否则滚动过程中每次都重渲染整个消息树。
    setJumpVisible((previous) => (previous === !atBottom ? previous : !atBottom))
  }, [])

  const handleUserEngage = React.useCallback((): void => {
    userEngagedRef.current = true
  }, [])

  const capturePrependAnchor = React.useCallback((): void => {
    const node = scrollRef.current
    prependAnchorRef.current = node ? node.scrollHeight - node.scrollTop : null
  }, [])

  const markReadingHistory = React.useCallback((): void => {
    atBottomRef.current = false
    setJumpVisible(true)
  }, [])

  // 键盘滚动（空格/方向键/PgUp/PgDn/Home/End）也算用户接管
  React.useEffect(() => {
    const scrollKeys = new Set([' ', 'ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End'])
    const onKeyDown = (event: KeyboardEvent): void => {
      if (scrollKeys.has(event.key)) userEngagedRef.current = true
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  return {
    scrollRef,
    atBottomRef,
    prependAnchorRef,
    jumpVisible,
    scrollToLatest,
    handleStreamScroll,
    handleUserEngage,
    capturePrependAnchor,
    markReadingHistory,
  }
}
