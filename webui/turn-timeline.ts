/**
 * 把一个 assistant-turn 拆成「最终正文 + 过程时间线」。
 *
 * 桌面端的过程组是「叙述 → 工具行 → 思考摘要」按原始顺序交织的（见
 * `render-block-derivation.ts` + `ProcessBlockGroup.tsx`），而 WebUI 之前只把
 * 工具名压成一列扁平清单：54 次调用滚下来，分不清哪一步在干什么。
 *
 * 这里只做纯派生，不持有折叠状态。口径对齐桌面端，但简化了两处：
 * 1. 不做折叠窗口（`FoldedSegmentsRegion`）——分页已经限制了窗口大小；
 * 2. 不做分段动画与倒数自动收起。
 */
import type { MessageGroup } from '@profer/session-core'
import {
  aggregateTaskItems,
  isTaskProgressTool,
  parseTaskToolInput,
  type TaskItem,
  type TaskToolActivity,
} from './task-progress'

export interface TimelineTool {
  id: string
  name: string
  /** 主进程投影下来的一行摘要（命令 / prompt / 业务 id…），可能没有。 */
  summary?: string
  failed?: boolean
  /** 工具结果是否已回来：决定行内是「进行中」还是完成态。 */
  done: boolean
  /** 投影里保留的文件路径，展开时能看到。 */
  filePath?: string
  /** 完整入参（JSON，有界截断）：展开时按等宽正文查看。 */
  inputText?: string
  /** 工具输出（有界截断）：展开时按等宽正文查看。 */
  output?: string
}

export type TimelineItem =
  | { kind: 'text'; key: string; text: string }
  | { kind: 'thinking'; key: string; text: string; partial: boolean }
  | { kind: 'tool'; key: string; tool: TimelineTool }

export interface AssistantTimeline {
  /** 最终正文，留在过程组外直接可见；可能为空。 */
  headline: string
  /** 过程组内的条目，保持原始顺序；任务工具已抽成 `tasks`，不在这里重复。 */
  items: TimelineItem[]
  /** 本轮任务清单（TaskCreate/TaskUpdate/TodoWrite 聚合）；没有任务工具时为空。 */
  tasks: TaskItem[]
  toolCount: number
  thinkingCount: number
  textCount: number
  /** 「执行过程：54 次工具调用，17 段思考，6 条消息」 */
  summary: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

/**
 * 工具名 → 「分类 + 名称」。
 *
 * MCP 工具是 `mcp__server__tool`，分类就是 server（大写的 `AUTOMATION` / `COLLABORATION`）；
 * 内置工具没有分类，直接显示原名 —— 与桌面端过程行的呈现一致。
 */
export function toolLabel(name: string): { category?: string; label: string } {
  const parts = name.split('__')
  if (parts[0] === 'mcp' && parts.length >= 3 && parts[1]) {
    return { category: parts[1].toUpperCase(), label: parts.slice(2).join('_') }
  }
  return { label: name }
}

function emptyTimeline(): AssistantTimeline {
  return { headline: '', items: [], tasks: [], toolCount: 0, thinkingCount: 0, textCount: 0, summary: '执行过程' }
}

export function summarizeProcess(toolCount: number, thinkingCount: number, textCount: number): string {
  const parts: string[] = []
  if (toolCount > 0) parts.push(`${toolCount} 次工具调用`)
  if (thinkingCount > 0) parts.push(`${thinkingCount} 段思考`)
  if (textCount > 0) parts.push(`${textCount} 条消息`)
  return `执行过程：${parts.join('，') || '过程'}`
}

/**
 * 定位「最终正文」区间：最后一段连续 text。
 *
 * 与桌面端同一条判据：该 text 之后只能是 thinking（reasoning 晚于正文到达的形态），
 * 且它之前的所有 tool_use 结果都得到齐 —— 否则这段 text 更可能是「给工具看的中间说明」，
 * 整轮应当收在过程组里。中途（结果未到）不外置，等结果回来再挪出去。
 *
 * `finished` 时放宽结果要求：这一轮已经结束（数据里出现了顶层 result），就不该再等结果。
 * 真实会话里存在「工具被中断、结果永不落盘」的调用（实测有 1 个 inspect_preview 没有
 * tool_result），死等会让整轮正文永久留在过程组里，用户以为没有回答。
 */
function findHeadlineRange(items: TimelineItem[], finished: boolean): { start: number; end: number } | null {
  let end = -1
  for (let index = items.length - 1; index >= 0; index--) {
    if (items[index]?.kind === 'text') { end = index; break }
  }
  if (end < 0) return null
  for (let index = end + 1; index < items.length; index++) {
    if (items[index]?.kind !== 'thinking') return null
  }
  if (!finished) {
    for (let index = 0; index < end; index++) {
      const item = items[index]
      if (item?.kind === 'tool' && !item.tool.done) return null
    }
  }
  let start = end
  while (start > 0 && items[start - 1]?.kind === 'text') start--
  return { start, end }
}

/**
 * 这一轮是否已经跑完。
 *
 * 判据用数据而不是客户端的 `running` 状态：只要 turn 里出现了**顶层** result（子代理的
 * result 带 parent_tool_use_id，不算），就说明这轮已收尾。客户端状态会被迟到的收尾
 * 消息重新顶成 true，靠它决定「过程组是否收起」会永远收不起来。
 */
export function isTurnFinished(group: MessageGroup | undefined): boolean {
  if (!group || group.type !== 'assistant-turn') return false
  return group.turnMessages.some((message) => {
    const record = message as { type?: string; parent_tool_use_id?: string | null }
    return record.type === 'result' && !record.parent_tool_use_id
  })
}

/**
 * 这一轮是否「已经收尾到可以收起过程组」。
 *
 * 权威判据是顶层 `result`（`isTurnFinished`）——注意它在 WebUI **可用**：
 * 消息馈送（`get_sdk_messages` 与实时流）走 `projectSdkMessageForPocket`，
 * result 原样透传（会过滤 result 的 `projectSdkMessagesToPocketView` 是详情视图的
 * role 映射，另一条消费路径，别看混）。
 *
 * isTurnSettled 在 result 之外再补一道兜底：最终正文已抽出（`headline` 非空）
 * 且没有还没拿到结果的工具调用。二者同时成立时，这一轮对用户而言已经完成 ——
 * 覆盖「工具被中断、结果永不落盘」（实测有 inspect_preview 无 tool_result）这类
 * 顶层 result 因故缺席的轮次，以及 running 状态被迟到消息顶住的场景。
 * 若之后又来了新工具调用，`findHeadlineRange` 会把这段 text 重新收回 `items`，
 * headline 变空，过程组自然再展开 —— 不会误伤仍在继续的一轮。
 */
export function isTurnSettled(
  group: MessageGroup | undefined,
  timeline: AssistantTimeline | undefined,
): boolean {
  if (!group || group.type !== 'assistant-turn') return false
  if (isTurnFinished(group)) return true
  if (!timeline) return false
  const hasHeadline = Boolean(timeline.headline.trim())
  const pendingTool = timeline.items.some((item) => item.kind === 'tool' && !item.tool.done)
  return hasHeadline && !pendingTool
}

/**
 * 这一轮是否「还在飞」：只看**最后一个**工具调用是否还没拿到结果。
 *
 * 中途（被中断、结果永不落盘）缺失结果的工具不算 pending —— 真实会话里，还在飞的工具
 * 永远追加在末尾，丢失的结果只会出现在中间。用「末尾工具未完成」当 liveness 信号，既能
 * 在跑完时正确收尾，也不会被一个早夭的中间工具卡住（`findHeadlineRange` 的严格分支就
 * 是被这种缺失结果误伤的：严格分支要求所有工具结果到齐（缺结果的轮次即便有顶层 result，
 * finished 标志也只放宽「结果到齐」而非「文本之后只允许 thinking」），于是
 * 整段最终正文被判成「还在过程里」而消失）。
 */
export function turnHasTrailingPendingTool(group: MessageGroup | undefined): boolean {
  if (!group || group.type !== 'assistant-turn') return false
  let lastToolId: string | null = null
  const resultIds = new Set<string>()
  for (const message of group.turnMessages) {
    const content = (message as { message?: { content?: unknown } }).message?.content
    if (!Array.isArray(content)) continue
    for (const block of content) {
      if (!isRecord(block)) continue
      if (block.type === 'tool_result' && typeof block.tool_use_id === 'string') {
        resultIds.add(block.tool_use_id)
      } else if (block.type === 'tool_use' && typeof block.id === 'string') {
        lastToolId = block.id
      }
    }
  }
  return lastToolId !== null && !resultIds.has(lastToolId)
}

export interface TimelineOptions {
  /** 跨轮 TaskCreate 的 taskId → subject 映射（TaskUpdate 出现在后续轮时补任务名）。 */
  historicalTaskSubjects?: ReadonlyMap<string, string>
  /** 这一轮是否已收尾：结束后 in_progress 任务不再转圈。默认与 settled 同口径。 */
  streamEnded?: boolean
  /** 这一轮是否被用户手动打断：in_progress 任务标记为 cancelled。 */
  stoppedByUser?: boolean
}

export function buildAssistantTimeline(
  group: MessageGroup | undefined,
  /** 这一轮是否已确定收尾。默认按顶层 result 判断；WebUI 拿不到 result，由调用方用
   * 「末尾工具已收齐」等更可靠的数据信号传入，否则严格分支会藏起缺结果的轮次正文。 */
  settled?: boolean,
  options?: TimelineOptions,
): AssistantTimeline {
  if (!group || group.type !== 'assistant-turn') return emptyTimeline()
  const resultIds = new Set<string>()
  const failedIds = new Set<string>()
  /** 结果正文（投影有界下发）：展开工具行时显示「输出」。 */
  const resultOutputs = new Map<string, string>()
  for (const message of group.turnMessages) {
    const content = (message as { message?: { content?: unknown } }).message?.content
    if (!Array.isArray(content)) continue
    for (const block of content) {
      if (!isRecord(block) || block.type !== 'tool_result') continue
      if (typeof block.tool_use_id !== 'string') continue
      resultIds.add(block.tool_use_id)
      if (block.is_error === true) failedIds.add(block.tool_use_id)
      const output = block.output
      if (typeof output === 'string' && output.trim()) resultOutputs.set(block.tool_use_id, output)
    }
  }

  const items: TimelineItem[] = []
  const taskActivities: TaskToolActivity[] = []
  for (const message of group.turnMessages) {
    const record = message as { message?: { content?: unknown }; _partial?: unknown }
    const content = record.message?.content
    if (!Array.isArray(content)) continue
    const partial = record._partial === true
    for (const block of content) {
      if (!isRecord(block)) continue
      if (block.type === 'text') {
        const text = asString(block.text)
        if (text) items.push({ kind: 'text', key: `text-${items.length}`, text })
        continue
      }
      if (block.type === 'thinking') {
        const text = asString(block.thinking)
        if (text) items.push({ kind: 'thinking', key: `think-${items.length}`, text, partial })
        continue
      }
      if (block.type !== 'tool_use' || typeof block.name !== 'string') continue
      const id = typeof block.id === 'string' ? block.id : `tool-${items.length}`
      const input = isRecord(block.input) ? block.input : {}
      if (isTaskProgressTool(block.name)) {
        // 任务工具聚合成卡片，不再作为普通工具行重复展示
        taskActivities.push({
          toolUseId: id,
          toolName: block.name,
          input: parseTaskToolInput(block.inputText, block.input, block.summary),
          ...(resultOutputs.has(id) ? { result: resultOutputs.get(id) } : {}),
        })
        continue
      }
      items.push({
        kind: 'tool',
        key: id,
        tool: {
          id,
          name: block.name,
          summary: asString(block.summary),
          filePath: asString(input.file_path),
          done: resultIds.has(id),
          ...(failedIds.has(id) ? { failed: true } : {}),
          ...(asString(block.inputText) ? { inputText: asString(block.inputText) } : {}),
          ...(resultOutputs.has(id) ? { output: resultOutputs.get(id) } : {}),
        },
      })
    }
  }

  const range = findHeadlineRange(items, settled ?? isTurnFinished(group))
  const headline = range
    ? items.slice(range.start, range.end + 1)
      .map((item) => (item.kind === 'text' ? item.text : ''))
      .join('\n\n')
      .trim()
    : ''
  const processItems = range
    ? [...items.slice(0, range.start), ...items.slice(range.end + 1)]
    : items

  let toolCount = 0
  let thinkingCount = 0
  let textCount = 0
  for (const item of processItems) {
    if (item.kind === 'tool') toolCount += 1
    else if (item.kind === 'thinking') thinkingCount += 1
    else textCount += 1
  }

  const tasks = taskActivities.length > 0
    ? aggregateTaskItems(
      taskActivities,
      options?.streamEnded ?? (settled ?? isTurnFinished(group)),
      options?.historicalTaskSubjects,
      options?.stoppedByUser,
    )
    : []

  return {
    headline,
    items: processItems,
    tasks,
    toolCount,
    thinkingCount,
    textCount,
    summary: summarizeProcess(toolCount, thinkingCount, textCount),
  }
}
