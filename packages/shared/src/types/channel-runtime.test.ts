import { describe, expect, test } from 'bun:test'
import { isChannelEnabledForRuntime, isAgentChannelCompatibleWithRuntime, type Channel } from './channel'

function channel(provider: Channel['provider'], overrides: Partial<Channel> = {}): Pick<Channel, 'provider' | 'enabled' | 'agentRuntimes' | 'agentExperimentalEnabled'> {
  return { provider, enabled: true, agentRuntimes: undefined, agentExperimentalEnabled: undefined, ...overrides }
}

describe('Agent runtime 渠道门禁', () => {
  test('Anthropic provider defaults to both runtimes when no explicit whitelist exists', () => {
    expect(isAgentChannelCompatibleWithRuntime(channel('anthropic'), 'pi')).toBe(true)
    expect(isAgentChannelCompatibleWithRuntime(channel('anthropic'), 'claude')).toBe(true)
  })

  test('DeepSeek 在 Claude runtime 下与桌面一致可用', () => {
    expect(isAgentChannelCompatibleWithRuntime(channel('deepseek'), 'claude')).toBe(true)
    expect(isAgentChannelCompatibleWithRuntime(channel('deepseek'), 'pi')).toBe(true)
  })
  test('显式 runtime 白名单优先于 provider 推导', () => {
    expect(isChannelEnabledForRuntime(channel('anthropic', { agentRuntimes: ['claude'] }), 'pi')).toBe(false)
    expect(isChannelEnabledForRuntime(channel('openai', { agentRuntimes: ['pi'] }), 'pi')).toBe(true)
    expect(isAgentChannelCompatibleWithRuntime(channel('openai', { agentRuntimes: ['pi'] }), 'pi')).toBe(true)
  })

  test('xAI 仅在实验开关开启时允许 Pi', () => {
    expect(isChannelEnabledForRuntime(channel('xai'), 'pi')).toBe(false)
    expect(isAgentChannelCompatibleWithRuntime(channel('xai', { agentExperimentalEnabled: true }), 'pi')).toBe(true)
  })
})
