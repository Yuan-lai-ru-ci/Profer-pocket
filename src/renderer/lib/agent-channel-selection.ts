import type { AgentRuntime, Channel } from '@profer/shared'
import { isAgentChannelCompatibleWithRuntime } from '@profer/shared'
import { getChannelProtocol } from '@/lib/channel-model-groups'

/** Pi can use every enabled channel without changing Claude's compatibility whitelist. */
export function nextAgentChannelIdsAfterModelSelect(
  currentChannelIds: string[],
  selectedChannelId: string,
  runtime: AgentRuntime,
): string[] {
  if (runtime !== 'claude') return currentChannelIds
  return currentChannelIds.includes(selectedChannelId)
    ? currentChannelIds
    : [...currentChannelIds, selectedChannelId]
}

/** Pi can use every enabled channel without changing Claude's compatibility whitelist. */
export interface AgentModelSelection {
  channelId: string
  modelId: string
}

/** Resolve a model that is valid for the selected Agent runtime. */
export function resolveAgentModelSelection(
  channels: Channel[],
  runtime: AgentRuntime,
  allowedChannelIds: string[],
  current?: AgentModelSelection | null,
): AgentModelSelection | null {
  const isEligible = (channel: Channel): boolean => channel.enabled
    && (allowedChannelIds.length === 0 || allowedChannelIds.includes(channel.id))
    && isAgentChannelCompatibleWithRuntime(channel, runtime)

  if (current) {
    const channel = channels.find((item) => item.id === current.channelId)
    if (channel && isEligible(channel) && channel.models.some((model) => model.enabled && model.id === current.modelId)) {
      return current
    }
  }

  const eligibleChannels = channels.filter(isEligible)
  const orderedChannels = runtime === 'pi'
    ? [
        ...eligibleChannels.filter((channel) => getChannelProtocol(channel.provider) === 'openai'),
        ...eligibleChannels.filter((channel) => getChannelProtocol(channel.provider) !== 'openai'),
      ]
    : eligibleChannels

  for (const channel of orderedChannels) {
    const model = channel.models.find((item) => item.enabled)
    if (model) return { channelId: channel.id, modelId: model.id }
  }

  return null
}
