/**
 * TaskList 结果解析（移动端：仅保留纯解析函数，供折叠摘要 chip 使用）
 *
 * 富渲染器 TaskListResultRenderer 依赖 default-result → ImageLightbox（Electron），
 * 移动端不搬，展开时由极简 ToolResultRenderer 的纯文本渲染承接。
 */

export interface ParsedTaskListItem {
  id: string
  subject: string
  status: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function stringValue(value: unknown): string | undefined {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return undefined
}

function parseJsonTaskList(text: string): ParsedTaskListItem[] | null {
  try {
    const parsed = JSON.parse(text)
    const list = Array.isArray(parsed)
      ? parsed
      : isRecord(parsed) && Array.isArray(parsed.tasks)
        ? parsed.tasks
        : null
    if (!list) return null

    const items = list
      .filter(isRecord)
      .map((item) => {
        const id = stringValue(item.id ?? item.taskId ?? item.task_id)
        const subject = stringValue(item.subject ?? item.title ?? item.name ?? item.description)
        const status = stringValue(item.status) ?? 'pending'
        return id && subject ? { id, subject, status } : null
      })
      .filter((item): item is ParsedTaskListItem => item !== null)

    return items.length > 0 ? items : null
  } catch {
    return null
  }
}

function parseTextTaskList(text: string): ParsedTaskListItem[] | null {
  const items: ParsedTaskListItem[] = []
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)

  for (const line of lines) {
    const match = /^#?([A-Za-z0-9._-]+)\s+\[([A-Za-z0-9_-]+)\]\s+(.+)$/.exec(line)
    if (!match) continue
    items.push({ id: match[1]!, status: match[2]!, subject: match[3]! })
  }

  return items.length > 0 ? items : null
}

export function parseTaskListResult(text: string): ParsedTaskListItem[] | null {
  const trimmed = text.trim()
  if (!trimmed) return null
  return parseJsonTaskList(trimmed) ?? parseTextTaskList(trimmed)
}
