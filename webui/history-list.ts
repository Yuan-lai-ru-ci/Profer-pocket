/**
 * 「历史对话」列表的纯派生层。
 *
 * 口径：一个回合一条，用户回合显示原文摘要，助手回合显示最终正文；
 * 只有工具调用、没有正文的回合用执行过程摘要兜底 —— 否则长会话里会出现
 * 「消息跳了一段」的错觉。
 *
 * 顺序是**时间正序（最新在最下）**，跟正文一致：面板打开时贴到底部，
 * 往上翻就是更早的消息 —— 与用户翻聊天记录的直觉一致，也和正文的阅读方向相同。
 */
import { stripContextBlocks } from './turn-extras'

export interface HistoryEntry {
  turnIndex: number
  role: 'user' | 'assistant'
  /** 该回合的时间（HH:mm），缺失时为空串 */
  clock: string
  /** 展示用的一行摘要（已截断） */
  preview: string
  /**
   * 匹配用的全文（未截断）。
   *
   * 搜索必须按全文命中：关键词常常出现在正文后半段，而列表里只显示前 68 个字 ——
   * 用 preview 匹配会漏掉「明明有这句话」的结果，用户只会觉得搜索坏了。
   */
  text: string
}

/** 一行预览的字数：够认出是哪一条，又不至于把面板挤成正文。 */
export const HISTORY_PREVIEW_MAX = 68

export interface HistoryEntryInput {
  turns: ReadonlyArray<{ index: number; role: string; text: string }>
  /** 与 turns 同下标：助手回合的最终正文 */
  headlines: ReadonlyArray<string | undefined>
  /** 与 turns 同下标：助手回合的执行过程摘要（无正文时的兜底） */
  summaries: ReadonlyArray<string | undefined>
  /** 与 turns 同下标：时间文案 */
  clocks: ReadonlyArray<string>
}

/** 压成一行并截断：历史列表里不该出现换行与缩进。 */
export function toHistoryPreview(text: string, max = HISTORY_PREVIEW_MAX): string {
  const line = text.replace(/\s+/g, ' ').trim()
  if (line.length <= max) return line
  return `${line.slice(0, max)}…`
}

export function buildHistoryEntries(input: HistoryEntryInput): HistoryEntry[] {
  const entries: HistoryEntry[] = []
  for (const turn of input.turns) {
    // 系统消息是分隔条（压缩边界 / 任务来源 / 中断记录），不是对话内容：
    // 放进"历史对话"列表会让人以为模型说过这句话，也跳不到任何"消息"。
    if (turn.role !== 'user' && turn.role !== 'assistant') continue
    // 下标一律用 turn.index：headlines / summaries / clocks 都按**分组下标**存放，
    // 而 turns 是过滤后的列表（system 分组不在其中），用数组位置会整体错位。
    const at = turn.index
    const isUser = turn.role === 'user'
    const raw = isUser
      ? stripContextBlocks(turn.text)
      : (input.headlines[at] ?? '') || (input.summaries[at] ?? '')
    const preview = toHistoryPreview(raw)
    // 空回合（例如只有系统注入块）不进列表：点进去也是一片空白
    if (!preview) continue
    entries.push({
      turnIndex: at,
      role: isUser ? 'user' : 'assistant',
      clock: input.clocks[at] ?? '',
      preview,
      text: raw.trim(),
    })
  }
  // 时间正序：turnIndex 递增即时间递增，最新在最后（面板打开时贴底）
  return entries
}

/**
 * 关键词筛选。
 *
 * 规则：按空白切词，**每个词都要命中**（AND），大小写不敏感、按子串匹配。
 * 不做高亮、不做模糊音：历史列表是「找回某句话」的工具，宁可能多不能漏，
 * 但也不能把「搜 a 出全部」这种噪音引进来。
 */
export function filterHistoryEntries(entries: readonly HistoryEntry[], query: string): HistoryEntry[] {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (terms.length === 0) return [...entries]
  return entries.filter((entry) => {
    const haystack = entry.text.toLowerCase()
    return terms.every((term) => haystack.includes(term))
  })
}
