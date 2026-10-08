import { describe, expect, test } from 'bun:test'
import { presetOf } from './agent-preset-atoms'
import type { AgentPreset } from '@profer/shared'

const preset = (id: string): AgentPreset => ({ id, name: id } as AgentPreset)

describe('presetOf 防御性解析', () => {
  test('Given 命中自定义预设 ID Then 直接返回该预设', () => {
    const presets = [preset('standard'), preset('my-preset')]

    expect(presetOf(presets, 'my-preset')?.id).toBe('my-preset')
  })

  test('Given 未命中 Then 回退内置默认预设', () => {
    const presets = [preset('standard'), preset('my-preset')]

    expect(presetOf(presets, 'not-exist')?.id).toBe('standard')
  })

  test('Given 预设缓存不是数组 Then 返回 undefined 而不是抛错（移动端白屏回归防护）', () => {
    // 旧版桌面端把 list_presets 包装成 { presets: [...] } 时，上层会把它直接写进
    // workspacePresetsAtom；此处若不防御，render 阶段会抛
    // "e.find is not a function"，React 整树卸载 → 移动端白屏。
    expect(presetOf({ presets: [preset('standard')] } as unknown as AgentPreset[], 'standard')).toBeUndefined()
    expect(presetOf(null as unknown as AgentPreset[], 'standard')).toBeUndefined()
    expect(presetOf(undefined as unknown as AgentPreset[], undefined)).toBeUndefined()
  })
})
