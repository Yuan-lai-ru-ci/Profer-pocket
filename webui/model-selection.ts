/**
 * 模型与推理强度的选择口径。
 *
 * 与桌面端 `AgentModelReasoningPicker` / `AgentReasoningControl` 同源，但只保留
 * 移动端需要的那一层：给一组「可选模型」和「可选档位」，把当前值标出来。
 *
 * 为什么两套档位：Claude 内核用 effort（low/medium/high/max），Pi 内核用模型自己的
 * reasoning levels（off/minimal/low/medium/high/xhigh/max）。哪个生效取决于会话内核，
 * 猜错会让用户看到「选不中」的选项，所以按 runtime + 服务端 capability 判定。
 */
import { isChannelEnabledForRuntime } from '@profer/shared'

export interface ModelOption {
  channelId: string
  channelName: string
  modelId: string
  name: string
  multiplier?: number
}

export interface ModelGroup {
  channelId: string
  channelName: string
  models: ModelOption[]
}

/** 档位文案：xhigh 与 max 必须能区分，否则「最高 / 最大」并排时无从理解。 */
export const THINKING_LEVEL_LABELS: Record<string, string> = {
  off: '关闭',
  minimal: '最低',
  low: '低',
  medium: '中',
  high: '高',
  xhigh: '更高',
  max: '最高',
}

export const EFFORT_LABELS: Record<string, string> = {
  low: '低',
  medium: '中',
  high: '高',
  max: '最大',
}

/** 「跟随默认」的键：与真实档位区分开，写入时翻译成 null（清除会话覆盖）。 */
export const FOLLOW_DEFAULT = '__default__'

const CLAUDE_EFFORT_LEVELS = ['low', 'medium', 'high', 'max'] as const

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

/**
 * 从 `list_channels` 的返回里摊平出当前内核可用的模型。
 *
 * 过滤规则必须和服务端 `update_session_model` 一致（同一份 isChannelEnabledForRuntime）：
 * 否则菜单里会出现一个点下去必然报错的模型。
 */
export function normalizeChannelModels(raw: unknown, runtime: string): ModelOption[] {
  if (!Array.isArray(raw)) return []
  const options: ModelOption[] = []
  for (const item of raw) {
    if (!isRecord(item)) continue
    const channel = item as unknown as Parameters<typeof isChannelEnabledForRuntime>[0]
    if (!isChannelEnabledForRuntime(channel, runtime as Parameters<typeof isChannelEnabledForRuntime>[1])) continue
    const channelId = typeof item.id === 'string' ? item.id : ''
    if (!channelId) continue
    const channelName = typeof item.name === 'string' ? item.name : ''
    const models = Array.isArray(item.models) ? item.models : []
    for (const model of models) {
      if (!isRecord(model) || model.enabled !== true) continue
      const modelId = typeof model.id === 'string' ? model.id : ''
      if (!modelId) continue
      const name = typeof model.name === 'string' && model.name.trim() ? model.name.trim() : modelId
      options.push({
        channelId,
        channelName,
        modelId,
        name,
        ...(typeof model.multiplier === 'number' ? { multiplier: model.multiplier } : {}),
      })
    }
  }
  return options
}

/** 按渠道分组；只有一个渠道时调用方可以直接平铺，不必显示分组标题。 */
export function groupModelsByChannel(options: ModelOption[]): ModelGroup[] {
  const groups: ModelGroup[] = []
  for (const option of options) {
    const existing = groups.find((group) => group.channelId === option.channelId)
    if (existing) {
      existing.models.push(option)
      continue
    }
    groups.push({ channelId: option.channelId, channelName: option.channelName, models: [option] })
  }
  return groups
}

/**
 * 把消息里的模型标识换成人看的名字。
 *
 * 消息里的 `model` 常常比配置里的 id 更长（带日期后缀：`claude-sonnet-4-5-20250929`），
 * 也可能反过来（别名）。所以按「精确 → 前缀 → 截断美化」三级回退，宁可显示 id 也不显示空白。
 */
export function modelLabelFor(modelId: string | undefined, options: ModelOption[]): string {
  const id = modelId?.trim()
  if (!id) return ''

  const exact = options.find((option) => option.modelId === id)
  if (exact) return exact.name

  const byName = options.find((option) => option.name.toLowerCase() === id.toLowerCase())
  if (byName) return byName.name

  // 取最长前缀匹配：`claude-sonnet-4-5` 命中 `claude-sonnet-4-5-20250929`，不会被更短的别名抢先
  let prefix: ModelOption | undefined
  for (const option of options) {
    if (!option.modelId) continue
    if (id.startsWith(option.modelId) || option.modelId.startsWith(id)) {
      if (!prefix || option.modelId.length > prefix.modelId.length) prefix = option
    }
  }
  if (prefix) return prefix.name

  return prettyModelId(id)
}

/** 去掉常见前缀与日期后缀，剩下的原样返回（不认识的模型也要看得懂）。 */
export function prettyModelId(modelId: string): string {
  return modelId
    .replace(/^(models\/|anthropic\/|openai\/|accounts\/[^/]+\/models\/)/i, '')
    .replace(/-\d{8}$/, '')
}

export type StrengthKind = 'thinking' | 'effort'

export interface StrengthOption {
  value: string
  label: string
  hint?: string
}

export interface StrengthState {
  kind: StrengthKind
  /** 第一项永远是「跟随默认」 */
  options: StrengthOption[]
  /** 当前会话覆盖；FOLLOW_DEFAULT 表示没有覆盖 */
  value: string
  /** 顶栏那一颗 chip 的短文案 */
  label: string
}

/**
 * 当前会话该显示哪一套档位。
 *
 * - 服务端给了 capability.levels（Pi 内核）→ 用模型自己的档位；
 * - 否则（Claude 内核、或 Pi 但拿不到能力表）→ 用 effort 四档。
 *   宁可给一套语义正确的通用档位，也不要给一排选不中的选项。
 */
export function resolveStrengthState(input: {
  levels?: readonly string[] | null
  level?: string | null
  effort?: string | null
}): StrengthState {
  const levels = (input.levels ?? []).filter((level) => typeof level === 'string' && level.length > 0)
  if (levels.length > 0) {
    const current = typeof input.level === 'string' && levels.includes(input.level) ? input.level : FOLLOW_DEFAULT
    return {
      kind: 'thinking',
      options: [
        { value: FOLLOW_DEFAULT, label: '跟随默认', hint: '按预设或全局设置' },
        ...levels.map((level) => ({
          value: level,
          label: THINKING_LEVEL_LABELS[level] ?? level,
          ...(level === 'off' ? { hint: '不产出思考' } : {}),
        })),
      ],
      value: current,
      label: current === FOLLOW_DEFAULT ? '默认' : (THINKING_LEVEL_LABELS[current] ?? current),
    }
  }

  const efforts = CLAUDE_EFFORT_LEVELS as readonly string[]
  const current = typeof input.effort === 'string' && efforts.includes(input.effort) ? input.effort : FOLLOW_DEFAULT
  return {
    kind: 'effort',
    options: [
      { value: FOLLOW_DEFAULT, label: '跟随默认', hint: '按预设或全局设置' },
      ...efforts.map((effort) => ({ value: effort, label: EFFORT_LABELS[effort] ?? effort })),
    ],
    value: current,
    label: current === FOLLOW_DEFAULT ? '默认' : (EFFORT_LABELS[current] ?? current),
  }
}
