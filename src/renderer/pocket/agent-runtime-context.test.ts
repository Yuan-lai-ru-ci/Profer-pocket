import { describe, expect, test } from 'bun:test'
import { applyAgentEvent, type AgentStreamState } from '@/atoms/agent-atoms'
import { hydrateAgentRuntimeContexts } from './agent-runtime-context'

function streamState(overrides: Partial<AgentStreamState> = {}): AgentStreamState {
  return {
    running: true,
    content: 'existing stream',
    toolActivities: [],
    ...overrides,
  }
}

describe('hydrateAgentRuntimeContexts', () => {
  test('Given a late-connected Terra run with a 200K fallback When a 1.05M runtime snapshot arrives Then the authoritative window replaces the fallback', () => {
    const previous = new Map([
      ['terra-session', streamState({ contextWindow: 200_000, usageUpdatedAt: 10 })],
    ])

    const result = hydrateAgentRuntimeContexts(previous, [{
      sessionId: 'terra-session',
      contextWindow: 1_050_000,
      updatedAt: 20,
    }], new Set(['terra-session']))

    expect(result.get('terra-session')).toMatchObject({
      running: true,
      content: 'existing stream',
      contextWindow: 1_050_000,
      usageUpdatedAt: 20,
    })
  })

  test('Given a snapshot for a non-active session When hydrating Then it does not resurrect a completed stream', () => {
    const previous = new Map<string, AgentStreamState>()

    const result = hydrateAgentRuntimeContexts(previous, [{
      sessionId: 'completed-session',
      contextWindow: 1_050_000,
      updatedAt: 20,
    }], new Set())

    expect(result).toBe(previous)
    expect(result.size).toBe(0)
  })

  test('Given an active session with no local stream state When hydrating Then an active display state is created', () => {
    const result = hydrateAgentRuntimeContexts(new Map(), [{
      sessionId: 'late-session',
      contextWindow: 1_050_000,
      updatedAt: 20,
    }], new Set(['late-session']))

    expect(result.get('late-session')).toEqual({
      running: true,
      content: '',
      toolActivities: [],
      contextWindow: 1_050_000,
      usageUpdatedAt: 20,
    })
  })

  test('Given a model-name 200K fallback When the real-time main-process context event arrives Then it replaces the fallback', () => {
    const result = applyAgentEvent(streamState({ contextWindow: 200_000 }), {
      type: 'context_window',
      contextWindow: 1_050_000,
    })

    expect(result.contextWindow).toBe(1_050_000)
  })
})
