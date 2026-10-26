import type { AgentStreamPayload, SDKMessage } from '@profer/shared'

/**
 * Pocket 的远程服务已经在出口移除 thinking 和工具大字段；这里保留一个兼容兜底，
 * 以便连接旧版桌面端时也不会把完整思考/工具内容写入移动端缓存。
 */
export function projectSdkMessageForPocket(message: SDKMessage): SDKMessage {
  const record = message as Record<string, unknown>
  const base: Record<string, unknown> = { type: message.type }
  for (const key of ['uuid', 'session_id', 'parent_tool_use_id', '_createdAt', '_partial', '_channelModelId', 'isReplay', 'isSynthetic']) {
    if (record[key] !== undefined) base[key] = record[key]
  }

  if (message.type === 'assistant') {
    const inner = record.message && typeof record.message === 'object' ? record.message as Record<string, unknown> : {}
    const content = Array.isArray(inner.content) ? inner.content : []
    const projectedContent = content.flatMap((block): Array<Record<string, unknown>> => {
      if (!block || typeof block !== 'object') return []
      const item = block as Record<string, unknown>
      if (item.type === 'text' && typeof item.text === 'string') return [{ type: 'text', text: item.text }]
      if (item.type === 'tool_use' && typeof item.id === 'string' && typeof item.name === 'string') {
        return [{ type: 'tool_use', id: item.id, name: item.name, input: {} }]
      }
      return []
    })
    base.message = {
      role: typeof inner.role === 'string' ? inner.role : 'assistant',
      content: projectedContent,
      ...(typeof inner.model === 'string' ? { model: inner.model } : {}),
      ...(inner.stop_reason !== undefined ? { stop_reason: inner.stop_reason } : {}),
    }
    if (record.error && typeof record.error === 'object') base.error = record.error
    return base as SDKMessage
  }

  if (message.type === 'user') {
    const inner = record.message && typeof record.message === 'object' ? record.message as Record<string, unknown> : {}
    const content = Array.isArray(inner.content) ? inner.content : []
    const projectedContent = content.flatMap((block): Array<Record<string, unknown>> => {
      if (!block || typeof block !== 'object') return []
      const item = block as Record<string, unknown>
      if (item.type === 'text' && typeof item.text === 'string') return [{ type: 'text', text: item.text }]
      if (item.type === 'tool_result' && typeof item.tool_use_id === 'string') {
        return [{ type: 'tool_result', tool_use_id: item.tool_use_id, ...(item.is_error === true ? { is_error: true } : {}) }]
      }
      return []
    })
    base.message = { content: projectedContent }
    return base as SDKMessage
  }

  if (message.type === 'result') {
    for (const key of ['subtype', 'total_cost_usd', 'terminal_reason', '_durationMs', '_channelModelId']) {
      if (record[key] !== undefined) base[key] = record[key]
    }
    if (record.usage && typeof record.usage === 'object') {
      const usage = record.usage as Record<string, unknown>
      base.usage = {
        ...(typeof usage.input_tokens === 'number' ? { input_tokens: usage.input_tokens } : {}),
        ...(typeof usage.output_tokens === 'number' ? { output_tokens: usage.output_tokens } : {}),
        ...(typeof usage.cache_read_input_tokens === 'number' ? { cache_read_input_tokens: usage.cache_read_input_tokens } : {}),
        ...(typeof usage.cache_creation_input_tokens === 'number' ? { cache_creation_input_tokens: usage.cache_creation_input_tokens } : {}),
      }
    }
    if (record.modelUsage && typeof record.modelUsage === 'object') {
      const modelUsage: Record<string, unknown> = {}
      for (const [model, value] of Object.entries(record.modelUsage as Record<string, unknown>)) {
        if (value && typeof value === 'object' && typeof (value as Record<string, unknown>).contextWindow === 'number') {
          modelUsage[model] = { contextWindow: (value as Record<string, unknown>).contextWindow }
        }
      }
      if (Object.keys(modelUsage).length > 0) base.modelUsage = modelUsage
    }
    return base as SDKMessage
  }

  if (message.type === 'system') {
    for (const key of ['subtype', 'model', 'task_id', 'description', 'task_type', 'tool_use_id', 'status', 'summary', 'output_file', 'last_tool_name', 'tool_name', 'message', 'decision_reason', 'estimated_tokens', 'estimated_tokens_delta', 'usage']) {
      if (record[key] !== undefined) base[key] = record[key]
    }
    return base as SDKMessage
  }

  for (const key of ['tool_use_id', 'task_id', 'elapsed_time_seconds', 'summary', 'preceding_tool_use_ids']) {
    if (record[key] !== undefined) base[key] = record[key]
  }
  return base as SDKMessage
}

export const slimSdkMessage = projectSdkMessageForPocket

export function slimSdkMessagesPayload(payload: unknown): unknown {
  if (Array.isArray(payload)) return payload.map((item) => projectSdkMessageForPocket(item as SDKMessage))
  if (payload && typeof payload === 'object' && Array.isArray((payload as { messages?: unknown }).messages)) {
    const page = payload as { messages: unknown[] }
    return { ...page, messages: page.messages.map((item) => projectSdkMessageForPocket(item as SDKMessage)) }
  }
  return payload
}

export function slimAgentPayload(payload: unknown): unknown {
  if (!payload || typeof payload !== 'object') return payload
  const record = payload as { kind?: string; message?: SDKMessage }
  return record.kind === 'sdk_message' && record.message
    ? { ...record, message: projectSdkMessageForPocket(record.message) } as AgentStreamPayload
    : payload
}
