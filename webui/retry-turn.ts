/**
 * 「重试上一轮」的决定层（纯函数，便于测试）。
 *
 * 重试 = 回退到这条用户消息之前，再原样重发。以下情况不给按钮：
 * - 最后一条不是真正的用户输入（`/goal` 是客户端命令，重发会变成普通正文）；
 * - 这一轮带附件/图片：回退只截对话、重发带不了文件，静默丢掉比不给按钮更糟；
 * - 剥掉上下文注入块后正文为空。
 */
import type { MessageGroup, TranscriptTurn } from '@profer/session-core'
import { parseGoalCommand } from './goal-command'
import { extractTurnExtras, stripContextBlocks } from './turn-extras'

export interface RetryTurnTarget {
  /** 重新发送的正文（已剥掉上下文注入块） */
  text: string
  /** 回退锚点：这条用户消息之前最后一条助手消息；首轮为 undefined 表示回退到会话开头 */
  assistantUuid?: string
}

/** 回退到 turnIndex 之前：找它前面最近一条助手消息的 uuid（回退 API 用它做截断锚点）。 */
export function findPreviousAssistantUuid(groups: readonly MessageGroup[], turnIndex: number): string | undefined {
  for (let index = turnIndex - 1; index >= 0; index -= 1) {
    const group = groups[index]
    if (!group || group.type !== 'assistant-turn') continue
    for (let cursor = group.turnMessages.length - 1; cursor >= 0; cursor -= 1) {
      const message = group.turnMessages[cursor] as { type?: string; uuid?: string }
      if (message?.type === 'assistant' && typeof message.uuid === 'string' && message.uuid) return message.uuid
    }
  }
  return undefined
}

export function resolveRetryTurn(
  groups: readonly MessageGroup[],
  turns: readonly TranscriptTurn[],
): RetryTurnTarget | null {
  let userIndex = -1
  for (let index = groups.length - 1; index >= 0; index -= 1) {
    if (groups[index]?.type === 'user') {
      userIndex = index
      break
    }
  }
  if (userIndex < 0) return null
  const group = groups[userIndex]
  if (!group || group.type !== 'user') return null

  // 合成消息（Goal 自动迭代 / 定时任务注入）不是用户发言，重发它没有意义
  const record = group.message as { isSynthetic?: unknown; _goalIteration?: unknown }
  if (record.isSynthetic === true || record._goalIteration != null) return null

  const extras = extractTurnExtras(group)
  if (extras.attachedFiles.length > 0 || extras.imagePaths.length > 0) return null

  const turn = turns.find((entry) => entry.index === userIndex)
  const raw = turn?.text ?? ''
  // 图片附件标记会被 stripContextBlocks 抹掉：允许重试就等于静默丢图
  if (/\[PROFER_IMAGE_ATTACHMENT:/i.test(raw)) return null
  const text = stripContextBlocks(raw)
  if (!text || parseGoalCommand(text).type !== 'not_goal') return null

  const assistantUuid = findPreviousAssistantUuid(groups, userIndex)
  return { text, ...(assistantUuid ? { assistantUuid } : {}) }
}
