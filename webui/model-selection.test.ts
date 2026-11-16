import { describe, expect, test } from 'bun:test'
import {
  FOLLOW_DEFAULT,
  groupModelsByChannel,
  modelLabelFor,
  normalizeChannelModels,
  prettyModelId,
  resolveStrengthState,
} from './model-selection'

const channels = [
  {
    id: 'c1',
    name: 'Anthropic 官方',
    provider: 'anthropic',
    enabled: true,
    models: [
      { id: 'claude-sonnet-4-5', name: 'Sonnet 4.5', enabled: true, multiplier: 1 },
      { id: 'claude-opus-4-1', name: 'Opus 4.1', enabled: false },
    ],
  },
  {
    id: 'c2',
    name: 'DeepSeek',
    provider: 'deepseek',
    enabled: true,
    models: [{ id: 'deepseek-chat', name: 'DeepSeek Chat', enabled: true, multiplier: 0.2 }],
  },
  {
    id: 'c3',
    name: '已停用渠道',
    provider: 'anthropic',
    enabled: false,
    models: [{ id: 'claude-haiku', name: 'Haiku', enabled: true }],
  },
]

describe('模型列表归一化', () => {
  test('Given 多渠道 When 归一化 Then 只留启用渠道里启用的模型', () => {
    const options = normalizeChannelModels(channels, 'claude')
    expect(options.map((option) => option.modelId)).toEqual(['claude-sonnet-4-5', 'deepseek-chat'])
    expect(options[0]).toEqual({
      channelId: 'c1',
      channelName: 'Anthropic 官方',
      modelId: 'claude-sonnet-4-5',
      name: 'Sonnet 4.5',
      multiplier: 1,
    })
  })

  test('Given 渠道声明了 agentRuntimes When 当前内核不在其中 Then 该渠道整个不出现', () => {
    const scoped = [{ ...channels[0], agentRuntimes: ['pi'] }]
    expect(normalizeChannelModels(scoped, 'claude')).toEqual([])
    expect(normalizeChannelModels(scoped, 'pi').map((option) => option.modelId)).toEqual(['claude-sonnet-4-5'])
  })

  test('Given 非数组或脏数据 When 归一化 Then 返回空数组，不抛异常', () => {
    expect(normalizeChannelModels(null, 'claude')).toEqual([])
    expect(normalizeChannelModels([{ id: 'x', models: 'nope' }], 'claude')).toEqual([])
  })

  test('Given 两个渠道 When 分组 Then 顺序保持首次出现顺序', () => {
    const groups = groupModelsByChannel(normalizeChannelModels(channels, 'claude'))
    expect(groups.map((group) => group.channelId)).toEqual(['c1', 'c2'])
    expect(groups[0]?.models).toHaveLength(1)
  })
})

describe('模型显示名', () => {
  const options = normalizeChannelModels(channels, 'claude')

  test('Given 精确 id When 取名 Then 用配置里的名称', () => {
    expect(modelLabelFor('claude-sonnet-4-5', options)).toBe('Sonnet 4.5')
  })

  test('Given 消息里的 id 带日期后缀 When 取名 Then 前缀命中，不显示一长串版本号', () => {
    expect(modelLabelFor('claude-sonnet-4-5-20250929', options)).toBe('Sonnet 4.5')
  })

  test('Given 消息 id 比配置更短 When 取名 Then 仍能命中（别名）', () => {
    expect(modelLabelFor('claude-sonnet-4', [{ channelId: 'c', channelName: '', modelId: 'claude-sonnet-4-5', name: 'Sonnet 4.5' }]))
      .toBe('Sonnet 4.5')
  })

  test('Given 配置里没有这个模型 When 取名 Then 去掉日期后缀原样显示，不显示空白', () => {
    expect(modelLabelFor('gpt-5.6-terra-20260101', options)).toBe('gpt-5.6-terra')
  })

  test('Given 空值 When 取名 Then 空串（界面据此不渲染 tag）', () => {
    expect(modelLabelFor(undefined, options)).toBe('')
    expect(modelLabelFor('   ', options)).toBe('')
  })

  test('Given 带供应商前缀 When 美化 Then 去前缀与日期', () => {
    expect(prettyModelId('anthropic/claude-opus-4-1-20250805')).toBe('claude-opus-4-1')
    expect(prettyModelId('models/gemini-3-pro')).toBe('gemini-3-pro')
  })
})

describe('推理强度', () => {
  test('Given 服务端给了档位 When 解析 Then 走 thinking，首项是跟随默认', () => {
    const state = resolveStrengthState({ levels: ['off', 'low', 'high', 'max'], level: 'high' })
    expect(state.kind).toBe('thinking')
    expect(state.value).toBe('high')
    expect(state.label).toBe('高')
    expect(state.options[0]?.value).toBe(FOLLOW_DEFAULT)
    expect(state.options.map((option) => option.value)).toEqual([FOLLOW_DEFAULT, 'off', 'low', 'high', 'max'])
    expect(state.options[1]?.hint).toBe('不产出思考')
  })

  test('Given 会话没有覆盖 When 解析 Then 显示「默认」而不是瞎猜一个档', () => {
    const state = resolveStrengthState({ levels: ['off', 'high'], level: null })
    expect(state.value).toBe(FOLLOW_DEFAULT)
    expect(state.label).toBe('默认')
  })

  test('Given 覆盖值已不在档位里 When 解析 Then 退回跟随默认（模型换过）', () => {
    const state = resolveStrengthState({ levels: ['low', 'high'], level: 'xhigh' })
    expect(state.value).toBe(FOLLOW_DEFAULT)
  })

  test('Given 没有能力表 When 解析 Then 用 effort 四档（Claude 内核）', () => {
    const state = resolveStrengthState({ levels: null, effort: 'max' })
    expect(state.kind).toBe('effort')
    expect(state.value).toBe('max')
    expect(state.label).toBe('最大')
    expect(state.options.map((option) => option.value)).toEqual([FOLLOW_DEFAULT, 'low', 'medium', 'high', 'max'])
  })

  test('Given 空能力表 When 解析 Then 同样回退 effort，不返回空菜单', () => {
    const state = resolveStrengthState({ levels: [], effort: null })
    expect(state.kind).toBe('effort')
    expect(state.options.length).toBeGreaterThan(1)
  })
})
