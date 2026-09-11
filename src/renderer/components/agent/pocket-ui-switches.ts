/**
 * Pocket 专属界面开关（0.1.15 / R3、R4）
 *
 * 抽成纯函数便于单测，并让「桌面端行为不变」成为可验证的契约：
 * `pocketMode === false` 时一律返回既有行为。
 */

/** R3：顶部「上一条用户消息」悬浮条是否渲染（pocket 不再渲染）。 */
export function shouldRenderStickyUserMessage(pocketMode: boolean, userMessageCount: number): boolean {
  return !pocketMode && userMessageCount > 0
}

/** R4：预设二级菜单是否极简紧凑（pocket 恒定极简；桌面沿用用户的「极简」开关）。 */
export function resolvePresetCompactMode(pocketMode: boolean, userCompactMode: boolean): boolean {
  return pocketMode || userCompactMode
}

/**
 * R4：预设菜单条目列表的类名。
 *
 * pocket 侧限高 + 内部滚动，避免预设过多时条目顶出屏幕（`side="top"` 时尤其致命）。
 * 高度直接吃 Radix 暴露的 `--radix-popover-content-available-height`（已扣除
 * collisionPadding 与视口边界），即「真机当前可用高度」，并在其中给标题行留出 2.5rem。
 */
export function resolvePresetListClassName(pocketMode: boolean): string {
  const base = 'flex flex-col gap-0.5'
  if (!pocketMode) return base
  return `${base} min-h-0 overflow-y-auto overscroll-contain max-h-[calc(var(--radix-popover-content-available-height)-2.5rem)]`
}
