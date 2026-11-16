import { describe, expect, test } from 'bun:test'
import {
  buildPresetCreate,
  buildPresetPatch,
  normalizePresets,
  presetBadges,
  presetFormFrom,
  resolveCurrentPreset,
  selectablePresets,
  TOOL_GROUPS,
  type PresetForm,
} from './preset-model'

const raw = [
  {
    id: 'standard', name: '标准', description: '通用写法', isBuiltin: true,
    effort: 'high', permissionMode: 'auto', createdAt: 1, updatedAt: 2,
  },
  {
    id: 'p1', name: '调研', description: '只读调研', isBuiltin: false,
    scope: 'user-global', modelId: 'claude-opus-4-1', allowSubagents: false,
    promptSections: ['先看已有文档'], disabledToolGroups: ['automation'],
    createdAt: 3, updatedAt: 4,
  },
  {
    id: 'p2', name: '停用', description: '', isBuiltin: false,
    scope: 'workspace', enabledInWorkspace: false, createdAt: 5, updatedAt: 6,
  },
]

const presets = normalizePresets(raw)

function form(overrides: Partial<PresetForm> = {}): PresetForm {
  return {
    name: '我的预设', description: '一句话', modelId: '', effort: '', permissionMode: '',
    allowSubagents: 'inherit', promptSections: '', disabledToolGroups: [], ...overrides,
  }
}

describe('预设归一化与可选性', () => {
  test('Given 三种预设 When 归一化 Then 作用域按内置/显式声明推导', () => {
    expect(presets).toHaveLength(3)
    expect(presets[0]?.scope).toBe('builtin-meta')
    expect(presets[1]?.scope).toBe('user-global')
    expect(presets[2]?.scope).toBe('workspace')
  })

  test('Given 明确停用的预设 When 取可选列表 Then 被排除', () => {
    expect(selectablePresets(presets).map((item) => item.id)).toEqual(['standard', 'p1'])
  })

  test('Given 脏数据 When 归一化 Then 丢掉无 id/无名的项', () => {
    expect(normalizePresets([{ name: 'x' }, { id: 'a' }, null, { id: 'b', name: '好' }]))
      .toHaveLength(1)
    expect(normalizePresets('nope')).toEqual([])
  })

  test('Given 带作用域引用 When 找当前预设 Then 按 id + scope 命中', () => {
    const current = resolveCurrentPreset(presets, undefined, { presetId: 'p1', presetScope: 'user-global' })
    expect(current?.name).toBe('调研')
  })

  test('Given scope 因迁移变化 When 找当前预设 Then 仍按 id 认，不显示「未知」', () => {
    const current = resolveCurrentPreset(presets, undefined, { presetId: 'p1', presetScope: 'workspace' })
    expect(current?.id).toBe('p1')
  })

  test('Given 只有旧 presetId When 找当前预设 Then 回退到 id 匹配', () => {
    expect(resolveCurrentPreset(presets, 'standard')?.name).toBe('标准')
  })

  test('Given 会话绑定的预设已停用 When 找当前预设 Then 返回 null（提示重新选）', () => {
    expect(resolveCurrentPreset(presets, 'p2')).toBeNull()
    expect(resolveCurrentPreset(presets)).toBeNull()
  })

  test('Given 各有覆盖的预设 When 取徽标 Then 内置/强度/权限/子代理都可读', () => {
    expect(presetBadges(presets[0]!)).toEqual(['内置', '强度·高', '权限·自动审批'])
    expect(presetBadges(presets[1]!)).toContain('禁用子代理')
    expect(presetBadges(normalizePresets([{ id: 'x', name: 'X' }])[0]!)).toEqual(['自定义'])
  })
})

describe('预设编辑表单', () => {
  test('Given 已有预设 When 回填表单 Then 提示段落还原成换行文本', () => {
    const value = presetFormFrom(presets[1]!)
    expect(value.name).toBe('调研')
    expect(value.promptSections).toBe('先看已有文档')
    expect(value.disabledToolGroups).toEqual(['automation'])
    expect(value.allowSubagents).toBe('deny')
  })

  test('Given 新预设 When 回填表单 Then 是干净的空白表单', () => {
    expect(presetFormFrom(null).allowSubagents).toBe('inherit')
    expect(presetFormFrom(null).promptSections).toBe('')
  })

  test('Given 留空可选项 When 生成补丁 Then 翻译成 null 清除覆盖', () => {
    const result = buildPresetPatch(form())
    expect(result.error).toBeUndefined()
    expect(result.patch).toEqual({
      name: '我的预设', description: '一句话', promptSections: null,
      disabledToolGroups: [], modelId: null, effort: null, permissionMode: null, allowSubagents: null,
    })
  })

  test('Given 三态子代理 When 生成补丁 Then 跟随=null、允许=true、禁止=false', () => {
    expect(buildPresetPatch(form({ allowSubagents: 'allow' })).patch?.allowSubagents).toBe(true)
    expect(buildPresetPatch(form({ allowSubagents: 'deny' })).patch?.allowSubagents).toBe(false)
    expect(buildPresetPatch(form({ allowSubagents: 'inherit' })).patch?.allowSubagents).toBeNull()
  })

  test('Given 多行提示与合法工具组 When 生成补丁 Then 去空行、过滤未知组', () => {
    const patch = buildPresetPatch(form({
      promptSections: '第一段\n\n  第二段 \n',
      disabledToolGroups: ['memory', 'unknown'],
    })).patch
    expect(patch?.promptSections).toEqual(['第一段', '第二段'])
    expect(patch?.disabledToolGroups).toEqual(['memory'])
    expect(TOOL_GROUPS.map((group) => group.id)).toContain('memory')
  })

  test('Given 名称为空或过长 When 生成补丁 Then 给出可读错误，不发坏请求', () => {
    expect(buildPresetPatch(form({ name: '  ' })).error).toContain('名称')
    expect(buildPresetPatch(form({ name: 'x'.repeat(81) })).error).toContain('80')
    expect(buildPresetPatch(form({ promptSections: 'x'.repeat(2001) })).error).toContain('2000')
  })

  test('Given 新建 When 生成入参 Then 不带 null，也不带空的提示/工具组', () => {
    const result = buildPresetCreate(form({
      modelId: 'claude-sonnet-4-5', effort: 'high', allowSubagents: 'deny',
      promptSections: '只做调研', disabledToolGroups: ['automation'],
    }))
    expect(result.input).toEqual({
      name: '我的预设', description: '一句话', promptSections: ['只做调研'],
      disabledToolGroups: ['automation'], modelId: 'claude-sonnet-4-5',
      effort: 'high', allowSubagents: false,
    })
    expect(JSON.stringify(result.input)).not.toContain('null')
  })
})
