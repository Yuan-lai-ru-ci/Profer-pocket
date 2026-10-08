import { describe, expect, test } from 'bun:test'
import { getChannelLogo, getModelLogo, getModelLogoById } from './model-logo'

function channel(overrides: Record<string, unknown> = {}) {
  return {
    provider: 'openai' as const,
    baseUrl: 'https://api.profer.cn/v1',
    models: [],
    ...overrides,
  }
}

describe('渠道 Logo 解析（与桌面端新版对齐）', () => {
  test('OpenAI-compatible Kimi 模型池按模型品牌显示 Kimi Logo', async () => {
    const kimiLogo = await import('@/assets/models/moonshot.png')
    expect(getChannelLogo(channel({ models: [{ id: 'kimi-k3' }] }))).toBe(kimiLogo.default)
  })

  test('模型别名不含品牌名时按 familyId 继承 Kimi Logo', async () => {
    const kimiLogo = await import('@/assets/models/moonshot.png')
    expect(getChannelLogo(channel({ name: 'Kimi', familyId: 'kimi', models: [{ id: 'k3' }] }))).toBe(kimiLogo.default)
  })

  test('没有可识别模型时仍按明确 provider 显示 Logo', async () => {
    const openaiLogo = await import('@/assets/models/openai.png')
    expect(getChannelLogo(channel())).toBe(openaiLogo.default)
  })

  test('泛化 provider 仍按 Base URL 域名识别真实品牌', async () => {
    const proferLogo = await import('@/assets/models/profer.png')
    expect(getChannelLogo({ provider: 'anthropic', baseUrl: 'https://api.proma.cool/anthropic', models: [] })).toBe(proferLogo.default)
  })
})

describe('模型 Logo 解析', () => {
  test('命中模型品牌规则', async () => {
    const claudeLogo = await import('@/assets/models/claude.png')
    expect(getModelLogoById('claude-sonnet-4-5-20250929')).toBe(claudeLogo.default)
  })

  test('模型 ID 未命中时回退供应商 Logo，再回退默认图', async () => {
    const claudeLogo = await import('@/assets/models/claude.png')
    const defaultLogo = await import('@/assets/models/default.png')
    expect(getModelLogo('some-unknown-model', 'anthropic')).toBe(claudeLogo.default)
    expect(getModelLogo('some-unknown-model')).toBe(defaultLogo.default)
  })
})
