import type { AssistantTurnRenderItem } from './ProcessBlockGroup'

export interface RenderWindowLimits {
  processSegments: number
  replySegments: number
}

export const DEFAULT_RENDER_WINDOW: RenderWindowLimits = {
  processSegments: 20,
  replySegments: 30,
}

export interface WindowedTurnItems {
  items: AssistantTurnRenderItem[]
  foldedProcessItems: AssistantTurnRenderItem[]
  foldedReplyItems: AssistantTurnRenderItem[]
}

/** 限制首次挂载的历史块数量；被折叠内容仍保留原始顺序，按需展开。 */
export function applyRenderWindow(
  items: AssistantTurnRenderItem[],
  limits: RenderWindowLimits = DEFAULT_RENDER_WINDOW,
): WindowedTurnItems {
  const processLimit = Math.max(0, limits.processSegments)
  const replyLimit = Math.max(0, limits.replySegments)
  const windowed: AssistantTurnRenderItem[] = []
  const foldedProcessItems: AssistantTurnRenderItem[] = []
  const foldedReplyItems: AssistantTurnRenderItem[] = []
  let visibleReplies = 0

  for (const item of items) {
    if (item.type === 'process-group') {
      const splitAt = Math.max(0, item.items.length - processLimit)
      if (splitAt > 0) {
        foldedProcessItems.push({ type: 'process-group', items: item.items.slice(0, splitAt) })
      }
      if (splitAt < item.items.length) {
        windowed.push({ type: 'process-group', items: item.items.slice(splitAt) })
      }
      continue
    }
    visibleReplies++
  }

  let replyStart = Math.max(0, visibleReplies - replyLimit)
  let replyIndex = 0
  for (const item of items) {
    if (item.type === 'process-group') continue
    if (replyIndex++ < replyStart) foldedReplyItems.push(item)
    else windowed.push(item)
  }

  return { items: windowed, foldedProcessItems, foldedReplyItems }
}
