/**
 * Chat 通用工具 — 移动端独立客户端（apps/tablet-client）
 *
 * 从桌面 components/chat/ChatMessageItem.tsx + lib/model-logo.ts 提炼的极简版：
 *  - formatMessageTime：消息时间格式化（今年 mm/dd hh:mm，跨年 yyyy/mm/dd hh:mm）
 *  - resolveModelDisplayName：从渠道列表把 modelId 映射为友好名，找不到则原样返回
 *
 * 桌面 model-logo.ts 依赖 Electron 渠道数据 + 模型 logo 资源，移动端不搬
 * （红线约束），这里只保留纯文本的显示名解析。
 */

import type { ChannelInfo } from '@/atoms/session'

/** 格式化消息时间（对齐桌面 formatMessageTime） */
export function formatMessageTime(timestamp: number): string {
  const date = new Date(timestamp)
  const now = new Date()

  const hh = date.getHours().toString().padStart(2, '0')
  const mm = date.getMinutes().toString().padStart(2, '0')
  const month = (date.getMonth() + 1).toString().padStart(2, '0')
  const day = date.getDate().toString().padStart(2, '0')
  const time = `${hh}:${mm}`

  if (date.getFullYear() === now.getFullYear()) {
    return `${month}/${day} ${time}`
  }
  return `${date.getFullYear()}/${month}/${day} ${time}`
}

/** 从渠道模型列表解析模型友好显示名；找不到则原样返回 modelId */
export function resolveModelDisplayName(modelId: string | undefined, channels: ChannelInfo[]): string | undefined {
  if (!modelId) return undefined
  for (const ch of channels) {
    const found = ch.models?.find((m) => m.id === modelId)
    if (found) return found.name || modelId
  }
  return modelId
}

/** 省略长 modelId 的 provider 前缀，得到更短的展示名（如 anthropic/claude-3 → claude-3） */
export function shortModelId(modelId: string): string {
  const idx = modelId.indexOf('/')
  return idx >= 0 ? modelId.slice(idx + 1) : modelId
}
