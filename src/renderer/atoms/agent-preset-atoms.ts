/**
 * Agent Preset Atoms — 按工作区缓存的预设列表与会话绑定映射
 *
 * - agentPresetsAtom：Map<workspaceSlug, AgentPreset[]>，预设为工作区级配置，
 *   按需加载后缓存（技能视图/会话工具栏各自通过 workspacePresetsAtom 读写）。
 * - agentSessionPresetMapAtom：会话 ID → 预设 ID 的内存映射（乐观更新，重启后
 *   以 session meta.presetId 为准回读）。
 */

import { atom } from 'jotai'
import { atomFamily } from 'jotai/utils'
import type { AgentPreset } from '@profer/shared'
import { BUILTIN_AGENT_PRESETS, DEFAULT_PRESET_ID } from '@profer/shared'

export const NO_WORKSPACE_PRESET_CACHE_KEY = '__no_workspace__'

/** 按工作区缓存的预设列表（预设为工作区级配置） */
export const agentPresetsAtom = atom<Map<string, AgentPreset[]>>(new Map())

/** 区分尚未请求与“确实没有可用预设”，避免空列表把工具栏静默隐藏。 */
const agentPresetsLoadedStateAtom = atom<Set<string>>(new Set<string>())
export const agentPresetsLoadedAtom = atom<Set<string>, [Set<string> | ((previous: Set<string>) => Set<string>)], void>(
  (get) => get(agentPresetsLoadedStateAtom),
  (get, set, update) => set(agentPresetsLoadedStateAtom, typeof update === 'function' ? update(get(agentPresetsLoadedStateAtom)) : update),
)

/** 某工作区的预设缓存读写原子；无工作区使用内置预设缓存键。 */
export const workspacePresetsAtom = atomFamily((workspaceSlug: string | undefined) =>
  atom<AgentPreset[], [AgentPreset[]], void>(
    (get) => {
      const key = workspaceSlug ?? NO_WORKSPACE_PRESET_CACHE_KEY
      return get(agentPresetsAtom).get(key) ?? (workspaceSlug ? [] : BUILTIN_AGENT_PRESETS)
    },
    (get, set, presets: AgentPreset[]) => {
      const key = workspaceSlug ?? NO_WORKSPACE_PRESET_CACHE_KEY
      const next = new Map(get(agentPresetsAtom))
      next.set(key, Array.isArray(presets) ? presets : [])
      set(agentPresetsAtom, next)
    },
  ),
)

export function presetCacheKey(workspaceSlug: string | undefined): string {
  return workspaceSlug ?? NO_WORKSPACE_PRESET_CACHE_KEY
}

/** 会话 ID → 预设 ID 的内存映射（乐观更新） */
export const agentSessionPresetMapAtom = atom<Map<string, string>>(new Map())

/**
 * 在预设列表中解析预设。
 *
 * 注意：不能用 shared 的 normalizePresetId（只认内置），自定义预设 ID 必须直接匹配。
 */
export function presetOf(presets: AgentPreset[], presetId: string | undefined): AgentPreset | undefined {
  // 防御：预设缓存一旦被写成非数组（例如旧版桌面端回包形状不一致、异常响应体），
  // 这里的 `.find` 会抛 "presets.find is not a function"，React 整树卸载 → 移动端白屏。
  if (!Array.isArray(presets)) return undefined
  const enabledPresets = presets.filter((preset) => preset.enabledInWorkspace !== false)
  if (presetId) {
    const selected = enabledPresets.find((preset) => preset.id === presetId)
    if (selected) return selected
  }
  return enabledPresets.find((preset) => preset.id === DEFAULT_PRESET_ID) ?? enabledPresets[0]
}

export const sessionPresetIdAtom = atomFamily((sessionId: string) =>
  atom<string | undefined, [string], void>(
    (get) => get(agentSessionPresetMapAtom).get(sessionId),
    (get, set, presetId: string) => {
      const map = get(agentSessionPresetMapAtom)
      const next = new Map(map)
      next.set(sessionId, presetId)
      set(agentSessionPresetMapAtom, next)
    },
  ),
)
