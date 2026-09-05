/** 热力图响应式布局工具。 */
export function getHeatmapScale(availableWidth: number, contentWidth: number): number {
  if (!Number.isFinite(availableWidth) || availableWidth <= 0) return 1
  if (!Number.isFinite(contentWidth) || contentWidth <= 0) return 1
  return Math.min(1, availableWidth / contentWidth)
}
