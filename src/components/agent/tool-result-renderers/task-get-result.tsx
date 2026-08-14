/**
 * TaskGet 结果解析（移动端：仅保留纯解析函数，供折叠摘要 chip 使用）
 *
 * 富渲染器 TaskGetResultRenderer 依赖 default-result → ImageLightbox（Electron），
 * 移动端不搬，展开时由极简 ToolResultRenderer 的纯文本渲染承接。
 */

export interface ParsedTaskGetResult {
  id?: string
  subject?: string
  status?: string
  description?: string
  blocks: string[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function stringValue(value: unknown): string | undefined {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return undefined
}

function normalizeBlockId(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) return ''
  return trimmed.startsWith('#') ? trimmed : `#${trimmed}`
}

function parseBlocks(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => normalizeBlockId(String(item))).filter(Boolean)
  }
  if (typeof value === 'number') return [`#${value}`]
  if (typeof value !== 'string') return []

  if (/^(?:none|null|undefined|n\/a|无|暂无)$/i.test(value.trim())) return []

  const matches = value.match(/#[A-Za-z0-9._-]+|[A-Za-z0-9._-]+/g)
  return matches ? matches.map(normalizeBlockId).filter(Boolean) : []
}

function parseJsonTask(text: string): ParsedTaskGetResult | null {
  try {
    const parsed = JSON.parse(text)
    if (!isRecord(parsed)) return null

    const task = isRecord(parsed.task) ? parsed.task : parsed
    return {
      id: stringValue(task.id ?? task.taskId),
      subject: stringValue(task.subject ?? task.title ?? task.name),
      status: stringValue(task.status),
      description: stringValue(task.description),
      blocks: parseBlocks(task.blocks ?? task.blockIds ?? task.block_ids),
    }
  } catch {
    return null
  }
}

function parseTextTask(text: string): ParsedTaskGetResult | null {
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  if (lines.length === 0) return null

  const result: ParsedTaskGetResult = { blocks: [] }
  for (const line of lines) {
    const taskMatch = /^Task\s*#?([^:\s]+)\s*:?\s*(.*)$/i.exec(line)
    if (taskMatch) {
      result.id = taskMatch[1]
      if (taskMatch[2]) result.subject = taskMatch[2]
      continue
    }

    const fieldMatch = /^([A-Za-z][A-Za-z\s_-]*):\s*(.*)$/.exec(line)
    if (!fieldMatch) continue

    const key = fieldMatch[1]?.trim().toLowerCase()
    const value = fieldMatch[2]?.trim() ?? ''
    if (key === 'status') {
      result.status = value
    } else if (key === 'description') {
      result.description = value
    } else if (key === 'blocks') {
      result.blocks = parseBlocks(value)
    }
  }

  return result.id || result.subject || result.status || result.description || result.blocks.length > 0
    ? result
    : null
}

export function parseTaskGetResult(text: string): ParsedTaskGetResult | null {
  const trimmed = text.trim()
  if (!trimmed) return null
  return parseJsonTask(trimmed) ?? parseTextTask(trimmed)
}

export function getTaskGetStatusLabel(status: string | undefined): string {
  switch (status) {
    case 'in_progress': return '进行中'
    case 'completed': return '已完成'
    case 'blocked': return '已阻塞'
    case 'deleted': return '已删除'
    case 'cancelled': return '已取消'
    case 'error': return '出错'
    case 'pending': return '待处理'
    default: return status || '未知状态'
  }
}
