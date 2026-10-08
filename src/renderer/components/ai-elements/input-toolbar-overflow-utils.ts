export interface ToolbarItemWidth {
  key: string
  width: number
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
  if (!items.every((item) => Number.isFinite(item.width) && item.width > 0)) return null

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
