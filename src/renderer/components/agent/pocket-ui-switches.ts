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

/**
 * R11：移动版输入框工具栏的展示优先级（桌面端信息项前置）。
 *
 * 背景：竖屏手机（720×1280 @320dpi + UI 缩放 110%）输入框工具栏只有约 260px，
 * `InputToolbarOverflow` 会把「靠右」的项折进「更多」Popover，于是桌面端一眼可见的
 * 「当前模型」与「上下文用量圆环」在手机上被折走/看不见。这里把桌面端的核心信息项
 * 前置：模型 → 上下文用量 → 内核（信息优先），功能项（权限 / 预设 / 附件）后置。
 */
export const POCKET_TOOLBAR_PRIORITY = [
  'model',
  'context-usage',
  'runtime',
  'permission-mode',
  'preset',
  'attach-file',
] as const

/**
 * 按 pocket 优先级重排工具栏项（纯函数，stable sort）。
 *
 * 未列入优先级的 key 保持彼此原有相对顺序，并排在已列入者之后。
 * 只有 `pocketMode === true` 的调用方使用本函数，桌面端顺序零变化。
 */
export function orderToolbarItemsForPocket<T extends { key: string }>(items: readonly T[]): T[] {
  const rank = new Map<string, number>(POCKET_TOOLBAR_PRIORITY.map((key, index) => [key, index]))
  const fallbackRank = POCKET_TOOLBAR_PRIORITY.length
  return [...items].sort((a, b) => (rank.get(a.key) ?? fallbackRank) - (rank.get(b.key) ?? fallbackRank))
}
