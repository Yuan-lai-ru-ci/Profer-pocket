/**
 * 全量会话搜索的纯派生层。
 *
 * 与「历史对话」面板原有的本地筛选（只在本页已加载的回合里找）不同：
 * 这里消费服务端 `search_session_messages` 的全文命中，`lineIndex` 与
 * `get_sdk_messages` 的 `before` 游标同一坐标，因此可以直接把命中所在的
 * 消息窗口取回来并跳过去 —— 不需要先加载完整历史。
 *
 * 服务端只回片段与命中位置（不回整段正文），高亮拆分也放在这里，保持可单测。
 */
import type { MessageGroup } from '@profer/session-core'

export type SessionSearchRole = 'user' | 'assistant' | 'tool' | 'status'

/** 服务端要求至少 2 个字符；1 个字符仍由面板的本地筛选处理。 */
export const MIN_SESSION_SEARCH_LENGTH = 2

/** 一次展示的命中上限：再多也没人翻，反而拖慢手机端。 */
export const SESSION_SEARCH_LIMIT = 60

export interface SessionSearchMatch {
  /** JSONL 非空行序号；取窗口时传 `before: lineIndex + 1` */
  lineIndex: number
  messageId: string
  role: SessionSearchRole
  snippet: string
  /** 片段内命中起点（服务端已把前置省略号计入偏移） */
  matchStart: number
}

export const SESSION_SEARCH_ROLE_LABELS: Record<SessionSearchRole, string> = {
  user: '你',
  assistant: 'Profer',
  tool: '工具',
  status: '系统',
}

function toSearchRole(value: unknown): SessionSearchRole {
  return value === 'user' || value === 'assistant' || value === 'tool' || value === 'status'
    ? value
    : 'assistant'
}

/** 服务端返回项可能是脏值：逐字段校验，坏项直接丢弃（不猜）。 */
export function normalizeSessionSearchMatches(
  value: unknown,
  limit = SESSION_SEARCH_LIMIT,
): SessionSearchMatch[] {
  if (!Array.isArray(value)) return []
  const matches: SessionSearchMatch[] = []
  for (const item of value) {
    if (!item || typeof item !== 'object') continue
    const record = item as Record<string, unknown>
    const lineIndex = record.lineIndex
    if (typeof lineIndex !== 'number' || !Number.isInteger(lineIndex) || lineIndex < 0) continue
    const snippet = typeof record.snippet === 'string' ? record.snippet : ''
    if (!snippet) continue
    const matchStart = typeof record.matchStart === 'number' && Number.isFinite(record.matchStart)
      ? Math.max(0, Math.trunc(record.matchStart))
      : 0
    matches.push({
      lineIndex,
      messageId: typeof record.messageId === 'string' ? record.messageId : '',
      role: toSearchRole(record.role),
      snippet,
      matchStart,
    })
    if (matches.length >= limit) break
  }
  return matches
}

export interface SearchSnippetParts {
  before: string
  hit: string
  after: string
}

/**
 * 把片段拆成「命中前 / 命中 / 命中后」。
 *
 * `matchStart` 越界时收敛到片段长度（服务端片段被裁剪过、客户端长度口径不同都可能越界），
 * 宁可不高亮也不能让 slice 吞掉正文。
 */
export function splitSearchSnippet(
  snippet: string,
  matchStart: number,
  queryLength: number,
): SearchSnippetParts {
  const start = Math.min(Math.max(0, matchStart), snippet.length)
  const length = Math.max(0, queryLength)
  const end = Math.min(snippet.length, start + length)
  return {
    before: snippet.slice(0, start),
    hit: snippet.slice(start, end),
    after: snippet.slice(end),
  }
}

/**
 * 命中消息落在哪个分组（分组下标即 `data-turn-index`，也是历史列表的 turnIndex）。
 *
 * 消息 id 在落盘消息里可能是顶层 `uuid`，也可能是 assistant 的 `message.id`；
 * 这里两种都认。找不到时返回 undefined，调用方只换窗口不强跳。
 */
export function findTurnIndexForMessage(
  groups: readonly MessageGroup[],
  messageId: string,
): number | undefined {
  if (!messageId) return undefined
  for (let index = 0; index < groups.length; index += 1) {
    const group = groups[index]
    if (!group) continue
    const messages = group.type === 'assistant-turn' ? group.turnMessages : [group.message]
    for (const message of messages) {
      const record = message as {
        uuid?: unknown
        message?: { id?: unknown }
      }
      if (record.uuid === messageId) return index
      if (record.message?.id === messageId) return index
    }
  }
  return undefined
}