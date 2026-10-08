export interface ToolbarItemWidth {
  key: string
  width: number
  /**
   * 是否已完成测量。
   *
   * 首帧（尚未测量）不应参与折叠计算，否则暂态宽度会让工具栏过早折叠；而
   * 「已测量但渲染为空」的项（如无 usage 时的上下文圆环、会话元数据未就绪时的预设）
   * 宽度合法为 0，必须继续参与计算——早期把 0 单纯当作「未测量」会让折叠计算
   * 永久返回 null（不生成「更多」按钮），后面的按钮被父容器 `overflow-hidden` 裁掉、
   * 移动端根本点不到。
   */
  measured?: boolean
}

/**
 * Return how many leading items fit before the overflow button is required.
 * A null result means measurement is incomplete and callers should keep the
 * initial all-items render until the first layout pass has finished.
 */
export function calculateVisibleCount(
  items: readonly ToolbarItemWidth[],
  containerWidth: number,
  gapPx: number,
  moreButtonPx: number,
): number | null {
  if (containerWidth === 0) return items.length
  if (items.some((item) => item.measured === false)) return null
  if (!items.every((item) => Number.isFinite(item.width) && item.width >= 0)) return null

  let total = 0
  for (let i = 0; i < items.length; i++) {
    const width = items[i]!.width
    const next = total + width + (i > 0 ? gapPx : 0)
    if (next > containerWidth) {
      const reserved = moreButtonPx + gapPx
      let fit = i
      let accumulated = total
      while (fit > 0 && accumulated + reserved > containerWidth) {
        fit -= 1
        const fitWidth = items[fit]!.width
        accumulated -= fitWidth + (fit > 0 ? gapPx : 0)
      }
      return Math.max(0, fit)
    }
    total = next
  }
  return items.length
}
