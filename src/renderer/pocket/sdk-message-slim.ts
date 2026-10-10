/**
 * 移动端内存瘦身：在 WS 接收边界把 SDK 消息里的超长工具输出/入参截断为预览。
 *
 * 背景：桌面端 get_sdk_messages 返回完整消息，工具结果（文件内容、命令输出）原样常驻在
 * electronapi-stub 分页缓存与渲染层 atom 中。手机端只需要看清对话脉络和工具概要，
 * 不需要整段输出，因此在此处统一截断。只改字符串内容，不改 type/uuid/role 等去重与结构字段。
 */

/** 单个工具文本/入参字符串保留的最大字符数。 */
export const POCKET_TOOL_TEXT_LIMIT = 2000

export function truncateToolText(text: string, limit: number = POCKET_TOOL_TEXT_LIMIT): string {
  if (text.length <= limit) return text
  return `${text.slice(0, limit)}\n…[移动端已截断，原文 ${text.length} 字符]`
}

function slimDeep(value: unknown, limit: number, depth = 0): unknown {
  if (depth > 8) return value
  if (typeof value === 'string') return truncateToolText(value, limit)
  if (Array.isArray(value)) return value.map((item) => slimDeep(item, limit, depth + 1))
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
      out[key] = slimDeep(child, limit, depth + 1)
    }
    return out
  }
  return value
}

function slimContentBlock(block: unknown, limit: number): unknown {
  if (!block || typeof block !== 'object') return block
  const b = block as Record<string, unknown>
  if (b.type === 'tool_result' && b.content !== undefined) {
    return { ...b, content: slimDeep(b.content, limit) }
  }
  if (b.type === 'tool_use' && b.input !== undefined) {
    return { ...b, input: slimDeep(b.input, limit) }
  }
  return block
}

/** 瘦身单条 SDK 消息；没有 message.content 数组的消息原样返回。 */
export function slimSdkMessage(message: unknown, limit: number = POCKET_TOOL_TEXT_LIMIT): unknown {
  if (!message || typeof message !== 'object') return message
  const m = message as { message?: { content?: unknown } }
  const content = m.message?.content
  if (!Array.isArray(content)) return message
  return {
    ...m,
    message: { ...m.message, content: content.map((block) => slimContentBlock(block, limit)) },
  }
}

/** 兼容两种响应形态：纯数组，或 { messages, startIndex, hasMore, total } 分页对象。 */
export function slimSdkMessagesPayload(payload: unknown, limit: number = POCKET_TOOL_TEXT_LIMIT): unknown {
  if (Array.isArray(payload)) return payload.map((item) => slimSdkMessage(item, limit))
  if (payload && typeof payload === 'object' && Array.isArray((payload as { messages?: unknown }).messages)) {
    const page = payload as { messages: unknown[] }
    return { ...page, messages: page.messages.map((item) => slimSdkMessage(item, limit)) }
  }
  return payload
}
