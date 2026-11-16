/**
 * 任务进度：把一轮里散落的 TaskCreate / TaskUpdate / TodoWrite 聚合成
 * 「总共几步、现在做到哪、还剩几条」。
 *
 * 与桌面端 `task-progress.ts` 同一套聚合口径，差别只在数据入口：
 * 桌面拿到的是完整结构化入参，WebUI 的 WS 投影只保留 `file_path` + 一行摘要 +
 * 有界 `inputText`（完整 JSON 的截断副本），所以这里从 inputText 解析任务字段，
 * JSON 被中间截断时再用宽松正则把关键字段捡回来。
 */
import type { SDKMessage } from '@profer/shared'

export type TaskItemStatus = 'pending' | 'in_progress' | 'completed' | 'failed' | 'cancelled' | 'deleted'

export interface TaskItem {
  id: string
  subject: string
  status: TaskItemStatus
  activeForm?: string
  description?: string
}

/** 聚合所需的最小活动形状；由时间线在遍历消息时顺手收集。 */
export interface TaskToolActivity {
  toolUseId: string
  toolName: string
  input: Record<string, unknown>
  result?: string
}

const TASK_GRAPH_PREFIX = /^mcp__task[-_]graph__/
const TASK_GRAPH_CREATE = 'profer_task_create'
const TASK_GRAPH_UPDATE = 'profer_task_update'

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function normalizeTaskGraphName(toolName: string): string {
  return toolName.replace(TASK_GRAPH_PREFIX, '')
}

function isTaskCreateTool(toolName: string): boolean {
  return toolName === 'TaskCreate' || normalizeTaskGraphName(toolName) === TASK_GRAPH_CREATE
}

function isTaskUpdateTool(toolName: string): boolean {
  return toolName === 'TaskUpdate' || normalizeTaskGraphName(toolName) === TASK_GRAPH_UPDATE
}

/** 会产出任务清单的工具（含旧版 TodoWrite）；这些行不再作为普通工具行重复展示。 */
export function isTaskProgressTool(toolName: string): boolean {
  return toolName === 'TodoWrite' || isTaskCreateTool(toolName) || isTaskUpdateTool(toolName)
}

function parseJsonObject(text: string): Record<string, unknown> | null {
  const trimmed = text.trim()
  if (!trimmed) return null
  try {
    const parsed = JSON.parse(trimmed)
    return isRecord(parsed) ? parsed : null
  } catch {
    return null
  }
}

/** inputText 被中间截断时的兜底：从残缺 JSON 里捞回任务字段。 */
function extractStringField(text: string, key: string): string | undefined {
  const match = new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(text)
  if (!match?.[1]) return undefined
  try {
    return JSON.parse(`"${match[1]}"`) as string
  } catch {
    return match[1]
  }
}

function extractNumberField(text: string, key: string): number | undefined {
  const match = new RegExp(`"${key}"\\s*:\\s*(\\d+)`).exec(text)
  return match?.[1] ? Number(match[1]) : undefined
}

const TASK_INPUT_KEYS = ['subject', 'description', 'activeForm', 'status', 'taskId', 'task_id', 'todos', 'task']

/**
 * 还原任务工具入参。
 *
 * 优先 inputText（投影里的完整 JSON 副本）；解析成功就直接用。
 * 解析失败（长描述导致中间被截掉）时：先看消息里是否还带着完整 `input`
 * （测试与旧路径），再从残缺 JSON 里捡 subject / activeForm / status / taskId；
 * 什么都没有时退回摘要（TaskCreate 的摘要是描述首段），至少能显示成人话。
 */
export function parseTaskToolInput(
  inputText: unknown,
  input: unknown,
  summary?: unknown,
): Record<string, unknown> {
  const text = typeof inputText === 'string' ? inputText : ''
  const parsed = text ? parseJsonObject(text) : null
  if (parsed) return parsed

  const inputRecord = isRecord(input) ? input : {}
  if (TASK_INPUT_KEYS.some((key) => inputRecord[key] !== undefined)) return inputRecord

  const recovered: Record<string, unknown> = {}
  const subject = extractStringField(text, 'subject')
  const activeForm = extractStringField(text, 'activeForm')
  const description = extractStringField(text, 'description')
  const status = extractStringField(text, 'status')
  const taskId = extractStringField(text, 'taskId') ?? extractNumberField(text, 'taskId')
  const taskIdSnake = extractStringField(text, 'task_id') ?? extractNumberField(text, 'task_id')
  if (subject) recovered.subject = subject
  if (activeForm) recovered.activeForm = activeForm
  if (description) recovered.description = description
  if (status) recovered.status = status
  if (taskId !== undefined) recovered.taskId = taskId
  else if (taskIdSnake !== undefined) recovered.taskId = taskIdSnake
  if (Object.keys(recovered).length > 0) return recovered

  if (typeof summary === 'string' && summary.trim()) return { description: summary }
  return inputRecord
}

/** tool_result 可能是裸文本，也可能是序列化的 content block 数组。 */
function extractResultText(value: unknown): string | undefined {
  if (typeof value === 'string') return value
  if (isRecord(value) && Array.isArray(value.content)) return extractResultText(value.content)
  if (!Array.isArray(value)) return undefined
  const text = value
    .map((block) => (isRecord(block) && typeof block.text === 'string' ? block.text : ''))
    .join('')
  return text || undefined
}

export function parseTaskCreateResult(result: string | undefined): { id: string; subject?: string } | null {
  if (!result) return null

  const json = parseJsonObject(result)
  const task = json?.task
  if (isRecord(task) && (typeof task.id === 'string' || typeof task.id === 'number')) {
    return {
      id: String(task.id),
      ...(typeof task.subject === 'string' ? { subject: task.subject } : {}),
    }
  }

  const extracted = extractResultText(json)
  if (extracted && extracted !== result) {
    const inner = parseJsonObject(extracted)
    const innerTask = inner?.task
    if (isRecord(innerTask) && (typeof innerTask.id === 'string' || typeof innerTask.id === 'number')) {
      return {
        id: String(innerTask.id),
        ...(typeof innerTask.subject === 'string' ? { subject: innerTask.subject } : {}),
      }
    }
  }

  // 兜底：兼容旧 SDK 文本格式 "Task #7 created successfully"
  const match = result.match(/Task\s*#(\d+)/i)
  return match?.[1] ? { id: match[1] } : null
}

function toTaskStatus(value: unknown, fallback: TaskItemStatus = 'pending'): TaskItemStatus {
  if (
    value === 'pending' ||
    value === 'in_progress' ||
    value === 'completed' ||
    value === 'failed' ||
    value === 'cancelled' ||
    value === 'deleted'
  ) {
    return value
  }
  return fallback
}

function stringId(value: unknown): string | undefined {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return undefined
}

/**
 * 聚合任务项最新状态。
 *
 * 1. TaskCreate 先建条目：id 优先取 SDK 结构化输出 `{ task: { id, subject } }`，
 *    拿不到就用 tool_use id；
 * 2. TaskUpdate 按 taskId 更新；创建发生在其它轮次时，用跨轮 subject 映射补名字；
 * 3. TodoWrite 每次整体替换它自己产出的 `todo-*` 条目（旧清单不残留）。
 */
export function aggregateTaskItems(
  activities: readonly TaskToolActivity[],
  streamEnded: boolean,
  historicalTaskSubjects?: ReadonlyMap<string, string>,
  stoppedByUser?: boolean,
): TaskItem[] {
  const taskMap = new Map<string, TaskItem>()
  let todoAutoId = 0

  const taskCreateIdMap = new Map<string, string>()
  const taskCreateSubjectMap = new Map<string, string>()

  for (const activity of activities) {
    if (!isTaskCreateTool(activity.toolName)) continue
    const parsedResult = parseTaskCreateResult(activity.result)
    const taskId = parsedResult?.id ?? activity.toolUseId
    taskCreateIdMap.set(activity.toolUseId, taskId)
    if (parsedResult?.subject) taskCreateSubjectMap.set(taskId, parsedResult.subject)
  }

  for (const activity of activities) {
    if (activity.toolName === 'TodoWrite') {
      const todos = activity.input.todos
      if (!Array.isArray(todos)) continue
      for (const key of taskMap.keys()) {
        if (key.startsWith('todo-')) taskMap.delete(key)
      }
      for (const todo of todos) {
        if (!isRecord(todo)) continue
        const id = `todo-${todoAutoId++}`
        taskMap.set(id, {
          id,
          subject: String(todo.subject ?? todo.content ?? ''),
          status: toTaskStatus(todo.status),
          ...(typeof todo.activeForm === 'string' ? { activeForm: todo.activeForm } : {}),
        })
      }
    } else if (isTaskCreateTool(activity.toolName)) {
      const id = taskCreateIdMap.get(activity.toolUseId) ?? activity.toolUseId
      const subject = typeof activity.input.subject === 'string'
        ? activity.input.subject
        : taskCreateSubjectMap.get(id)
          ?? (typeof activity.input.description === 'string' ? activity.input.description : '未命名任务')
      const description = typeof activity.input.description === 'string'
        ? activity.input.description
        : undefined
      taskMap.set(id, {
        id,
        subject,
        status: 'pending',
        ...(typeof activity.input.activeForm === 'string' ? { activeForm: activity.input.activeForm } : {}),
        ...(description ? { description } : {}),
      })
    } else if (isTaskUpdateTool(activity.toolName)) {
      const taskId = stringId(activity.input.taskId ?? activity.input.task_id ?? activity.input.id)
      if (!taskId) continue

      const existing = taskMap.get(taskId)
      const status = toTaskStatus(activity.input.status, existing?.status ?? 'pending')
      const description = typeof activity.input.description === 'string'
        ? activity.input.description
        : undefined
      if (existing) {
        taskMap.set(taskId, {
          ...existing,
          status,
          ...(typeof activity.input.subject === 'string' ? { subject: activity.input.subject } : {}),
          ...(typeof activity.input.activeForm === 'string' ? { activeForm: activity.input.activeForm } : {}),
          ...(description !== undefined ? { description } : {}),
        })
      } else {
        // 没有已有条目：只有能给出有意义的名字时才创建，避免「任务 #N」与后续
        // TaskCreate 的正确条目并存；等 TaskCreate 结果到达自然会补齐。
        const subject = typeof activity.input.subject === 'string'
          ? activity.input.subject
          : historicalTaskSubjects?.get(taskId)
        if (subject) {
          taskMap.set(taskId, {
            id: taskId,
            subject,
            status,
            ...(typeof activity.input.activeForm === 'string' ? { activeForm: activity.input.activeForm } : {}),
            ...(description !== undefined ? { description } : {}),
          })
        }
      }
    }
  }

  let items = Array.from(taskMap.values()).filter((item) => item.status !== 'deleted')
  if (stoppedByUser) {
    // 用户手动打断：in_progress 视作「这条路线取消」，而不是还在跑
    items = items.map((item) => item.status === 'in_progress' ? { ...item, status: 'cancelled' as const } : item)
  } else if (streamEnded) {
    // 正常收尾但没标记完成：退回 pending（Agent 已停，不能再转圈）
    items = items.map((item) => item.status === 'in_progress' ? { ...item, status: 'pending' as const } : item)
  }
  return items
}

function contentBlocks(message: SDKMessage): Array<Record<string, unknown>> {
  const content = (message as { message?: { content?: unknown } }).message?.content
  if (!Array.isArray(content)) return []
  return content.filter(isRecord)
}

/**
 * 跨轮「TaskCreate id → subject」映射。
 *
 * TaskUpdate 经常出现在下一个 assistant-turn 里，单看那一轮拿不到任务名；
 * 这里扫一遍全量消息（O(M)），把 TaskCreate 的结构化结果与入参 subject 对上。
 */
export function buildHistoricalTaskSubjects(messages: readonly SDKMessage[]): Map<string, string> {
  const fallbackByToolUseId = new Map<string, string>()
  for (const message of messages) {
    if ((message as { type?: string }).type !== 'assistant') continue
    for (const block of contentBlocks(message)) {
      if (block.type !== 'tool_use' || typeof block.id !== 'string' || typeof block.name !== 'string') continue
      if (!isTaskCreateTool(block.name)) continue
      const input = parseTaskToolInput(block.inputText, block.input, block.summary)
      const subject = typeof input.subject === 'string'
        ? input.subject
        : typeof input.description === 'string'
          ? input.description
          : undefined
      if (subject) fallbackByToolUseId.set(block.id, subject)
    }
  }
  if (fallbackByToolUseId.size === 0) return new Map()

  const subjects = new Map<string, string>()
  for (const message of messages) {
    if ((message as { type?: string }).type !== 'user') continue
    for (const block of contentBlocks(message)) {
      if (block.type !== 'tool_result' || typeof block.tool_use_id !== 'string') continue
      const fallback = fallbackByToolUseId.get(block.tool_use_id)
      if (!fallback) continue
      const parsed = parseTaskCreateResult(typeof block.output === 'string' ? block.output : undefined)
      if (!parsed) continue
      subjects.set(parsed.id, parsed.subject ?? fallback)
    }
  }
  return subjects
}
