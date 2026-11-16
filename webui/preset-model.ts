/**
 * WebUI 的预设模型与派生。
 *
 * 「取能力不搬文件」：pocket 的 `@profer/shared` 是滞后快照（没有 scope / channelId / modelId
 * 等后续字段），所以这里按服务端返回原样归一化，不 import 它的 AgentPreset 类型。
 *
 * 口径对齐桌面 `preset-selector-utils.ts` 与 `PresetSelector.tsx`：
 * 明确停用的预设不进菜单；当前预设优先按带作用域的 reference 匹配，旧数据才退回 id。
 */
export interface PresetReference {
  presetId: string
  presetScope?: string
  workspaceSlug?: string
  presetVersion?: string
}

export interface WebPreset {
  id: string
  name: string
  description: string
  isBuiltin: boolean
  scope: string
  version?: string
  enabledInWorkspace?: boolean
  modelId?: string
  effort?: string
  permissionMode?: string
  allowSubagents?: boolean
  promptSections?: string[]
  disabledToolGroups?: string[]
  disabledTools?: string[]
  createdAt: number
  updatedAt: number
}

/** 产品内置工具组，与 shared 的 AGENT_PRESET_TOOL_GROUPS 一致。 */
export const TOOL_GROUPS = [
  { id: 'task-graph', label: '任务图' },
  { id: 'memory', label: '记忆' },
  { id: 'collaboration', label: '协作' },
  { id: 'automation', label: '自动化' },
] as const

const EFFORT_LABEL: Record<string, string> = { low: '低', medium: '中', high: '高', max: '最大' }
const PERMISSION_LABEL: Record<string, string> = {
  auto: '自动审批', bypassPermissions: '完全自动', plan: '计划',
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function asNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function asStringArray(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined
  return value.filter((item): item is string => typeof item === 'string' && item.trim() !== '')
}

/** 预设列表归一化；未知项直接丢掉，避免菜单里出现点了必然报错的条目。 */
export function normalizePresets(value: unknown): WebPreset[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item): WebPreset[] => {
    if (!isRecord(item)) return []
    const id = asString(item.id)
    const name = asString(item.name)
    if (!id || !name) return []
    const scope = asString(item.scope) ?? (item.isBuiltin === true ? 'builtin-meta' : 'workspace')
    return [{
      id,
      name,
      description: typeof item.description === 'string' ? item.description : '',
      isBuiltin: item.isBuiltin === true,
      scope,
      version: asString(item.version),
      enabledInWorkspace: item.enabledInWorkspace === false ? false : undefined,
      modelId: asString(item.modelId),
      effort: asString(item.effort),
      permissionMode: asString(item.permissionMode),
      allowSubagents: typeof item.allowSubagents === 'boolean' ? item.allowSubagents : undefined,
      promptSections: asStringArray(item.promptSections),
      disabledToolGroups: asStringArray(item.disabledToolGroups),
      disabledTools: asStringArray(item.disabledTools),
      createdAt: asNumber(item.createdAt),
      updatedAt: asNumber(item.updatedAt),
    }]
  })
}

/** 明确停用的预设不进入任何选择入口（桌面端所有入口共用这条过滤）。 */
export function selectablePresets(presets: readonly WebPreset[]): WebPreset[] {
  return presets.filter((preset) => preset.enabledInWorkspace !== false)
}

/** 当前会话预设：优先按 reference（id + scope）匹配，旧数据才退回纯 id。 */
export function resolveCurrentPreset(
  presets: readonly WebPreset[],
  presetId: string | undefined,
  reference: PresetReference | undefined | null,
): WebPreset | null {
  const selectable = selectablePresets(presets)
  if (reference?.presetId) {
    const matched = selectable.find((preset) => preset.id === reference.presetId
      && (!reference.presetScope || preset.scope === reference.presetScope))
    if (matched) return matched
    // scope 可能因迁移而变化：仍按 id 认，避免把「同一个预设」显示成未知
    const byId = selectable.find((preset) => preset.id === reference.presetId)
    if (byId) return byId
  }
  if (!presetId) return null
  return selectable.find((preset) => preset.id === presetId) ?? null
}

/** 列表项右侧的小徽标：让用户不用点进去就知道这个岗位会改什么。 */
export function presetBadges(preset: WebPreset): string[] {
  const badges: string[] = []
  if (preset.isBuiltin) badges.push('内置')
  if (preset.effort) badges.push(`强度·${EFFORT_LABEL[preset.effort] ?? preset.effort}`)
  if (preset.permissionMode) {
    badges.push(`权限·${PERMISSION_LABEL[preset.permissionMode] ?? preset.permissionMode}`)
  }
  if (preset.allowSubagents === false) badges.push('禁用子代理')
  if (!badges.length && !preset.isBuiltin) badges.push('自定义')
  return badges
}

// ===== 编辑表单 =====

export interface PresetForm {
  name: string
  description: string
  modelId: string
  effort: string
  permissionMode: string
  /** 三态：跟随默认 / 允许 / 禁止 */
  allowSubagents: 'inherit' | 'allow' | 'deny'
  /** 一行一段，编辑时比数组直观 */
  promptSections: string
  disabledToolGroups: string[]
}

export interface PresetPatch {
  name: string
  description: string
  promptSections: string[] | null
  disabledToolGroups: string[]
  modelId: string | null
  effort: string | null
  permissionMode: string | null
  allowSubagents: boolean | null
}

export function presetFormFrom(preset: WebPreset | null): PresetForm {
  return {
    name: preset?.name ?? '',
    description: preset?.description ?? '',
    modelId: preset?.modelId ?? '',
    effort: preset?.effort ?? '',
    permissionMode: preset?.permissionMode ?? '',
    allowSubagents: preset?.allowSubagents === undefined ? 'inherit'
      : preset.allowSubagents ? 'allow' : 'deny',
    promptSections: (preset?.promptSections ?? []).join('\n'),
    disabledToolGroups: [...(preset?.disabledToolGroups ?? [])],
  }
}

/** 表单 → 更新补丁。留空可选项翻译成 null（清除覆盖），不传的字段服务端保持原值。 */
export function buildPresetPatch(form: PresetForm): { patch?: PresetPatch; error?: string } {
  const name = form.name.trim()
  if (!name) return { error: '预设名称不能为空' }
  if (name.length > 80) return { error: '预设名称最多 80 字' }
  const description = form.description.trim()
  if (description.length > 240) return { error: '描述最多 240 字' }
  const sections = form.promptSections.split('\n').map((line) => line.trim()).filter(Boolean)
  if (sections.some((line) => line.length > 2000)) return { error: '单段系统提示最多 2000 字' }
  return {
    patch: {
      name,
      description,
      promptSections: sections.length > 0 ? sections : null,
      disabledToolGroups: form.disabledToolGroups.filter((group) => TOOL_GROUPS.some((item) => item.id === group)),
      modelId: form.modelId.trim() || null,
      effort: form.effort || null,
      permissionMode: form.permissionMode || null,
      allowSubagents: form.allowSubagents === 'inherit' ? null : form.allowSubagents === 'allow',
    },
  }
}

/** 表单 → 新建入参：不发送 null，只带填了的字段。 */
export function buildPresetCreate(form: PresetForm): { input?: Record<string, unknown>; error?: string } {
  const built = buildPresetPatch(form)
  if (built.error || !built.patch) return { error: built.error }
  const patch = built.patch
  const input: Record<string, unknown> = {
    name: patch.name,
    description: patch.description,
    ...(patch.promptSections ? { promptSections: patch.promptSections } : {}),
    ...(patch.disabledToolGroups.length > 0 ? { disabledToolGroups: patch.disabledToolGroups } : {}),
    ...(patch.modelId ? { modelId: patch.modelId } : {}),
    ...(patch.effort ? { effort: patch.effort } : {}),
    ...(patch.permissionMode ? { permissionMode: patch.permissionMode } : {}),
  }
  if (patch.allowSubagents !== null) input.allowSubagents = patch.allowSubagents
  return { input }
}
