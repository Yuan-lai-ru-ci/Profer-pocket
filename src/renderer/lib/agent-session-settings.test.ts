import { describe, expect, test } from 'bun:test'
import type { AgentSessionMeta } from '@profer/shared'
import { mergeAuthoritativeAgentSession, resolveAuthoritativeAgentSession, sessionRevision } from './agent-session-settings'

function session(id: string, revision: number, extra: Partial<AgentSessionMeta> = {}): AgentSessionMeta {
  return {
    id,
    revision,
    title: id,
    createdAt: 1,
    updatedAt: revision,
    ...extra,
  }
}

describe('agent session settings state helpers', () => {
  test('prefers Remote Store session over legacy mirror', () => {
    const remote = session('a', 3, { permissionMode: 'plan' })
    const legacy = session('a', 2, { permissionMode: 'auto' })
    expect(resolveAuthoritativeAgentSession(remote, legacy)).toBe(remote)
    expect(sessionRevision(remote, legacy)).toBe(3)
  })

  test('falls back to legacy mirror during cold start', () => {
    const legacy = session('a', 2)
    expect(resolveAuthoritativeAgentSession(undefined, legacy)).toBe(legacy)
    expect(sessionRevision(undefined, legacy)).toBe(2)
  })

  test('does not let a lower revision overwrite the mirror', () => {
    const current = session('a', 5, { permissionMode: 'plan' })
    const stale = session('a', 4, { permissionMode: 'auto' })
    expect(mergeAuthoritativeAgentSession([current], stale)[0]).toBe(current)
  })

  test('accepts an equal or higher revision authoritative session', () => {
    const current = session('a', 5, { permissionMode: 'auto' })
    const updated = session('a', 6, { permissionMode: 'plan' })
    expect(mergeAuthoritativeAgentSession([current], updated)[0]).toBe(updated)
  })
})
