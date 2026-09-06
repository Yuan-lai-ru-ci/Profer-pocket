import { afterEach, describe, expect, test } from 'bun:test'
import {
  clearPocketResolvedInteractionWatermarks,
  filterPendingInteractionSnapshot,
  filterPocketPendingInteractionSnapshot,
  markPocketResolvedInteraction,
  markResolvedInteraction,
  type PendingInteractionIdentity,
} from './pending-interaction-reconciliation'

const permissionA: PendingInteractionIdentity = { kind: 'permission', sessionId: 'session-1', requestId: 'request-a' }
const permissionB: PendingInteractionIdentity = { kind: 'permission', sessionId: 'session-1', requestId: 'request-b' }

const pending = [
  { sessionId: 'session-1', requestId: 'request-a', toolName: 'Write' },
  { sessionId: 'session-1', requestId: 'request-b', toolName: 'Read' },
]

afterEach(() => clearPocketResolvedInteractionWatermarks())

describe('pending interaction snapshot reconciliation', () => {
  test('filters a resolved request without removing another request in the same session', () => {
    const watermarks = markResolvedInteraction(new Map(), permissionA, 100)

    expect(filterPendingInteractionSnapshot(pending, 'permission', watermarks, 101))
      .toEqual([pending[1]])
  })

  test('keeps a request after the watermark expires', () => {
    const watermarks = markResolvedInteraction(new Map(), permissionA, 100)

    expect(filterPendingInteractionSnapshot(pending, 'permission', watermarks, 100 + 120_000))
      .toEqual(pending)
  })

  test('duplicate resolved events are idempotent', () => {
    const once = markResolvedInteraction(new Map(), permissionA, 100)
    const twice = markResolvedInteraction(once, permissionA, 101)

    expect(filterPendingInteractionSnapshot(pending, 'permission', twice, 102))
      .toEqual([pending[1]])
    expect(twice.size).toBe(1)
  })

  test('watermarks are scoped by interaction kind and session', () => {
    const watermarks = markResolvedInteraction(new Map(), permissionA, 100)

    expect(filterPendingInteractionSnapshot(
      [{ sessionId: 'session-1', requestId: 'request-a' }],
      'askUser',
      watermarks,
      101,
    )).toHaveLength(1)
    expect(filterPendingInteractionSnapshot(
      [{ sessionId: 'session-2', requestId: 'request-a' }],
      'permission',
      watermarks,
      101,
    )).toHaveLength(1)
  })

  test('Pocket module watermark blocks an old snapshot after resolved event', () => {
    markPocketResolvedInteraction(permissionA, 100)

    expect(filterPocketPendingInteractionSnapshot(pending, 'permission', 101))
      .toEqual([pending[1]])
  })

  test('deduplicates duplicate requestIds in a snapshot', () => {
    const watermarks = new Map<string, number>()
    expect(filterPendingInteractionSnapshot(
      [...pending, pending[1]],
      'permission',
      watermarks,
      100,
    )).toEqual(pending)
  })

  test('respects watermark capacity while retaining newest observations', () => {
    let watermarks = new Map<string, number>()
    for (let index = 0; index < 3; index += 1) {
      watermarks = markResolvedInteraction(
        watermarks,
        { kind: 'permission', sessionId: 'session-1', requestId: `request-${index}` },
        index,
        120_000,
        2,
      )
    }

    expect(watermarks.size).toBe(2)
    expect(filterPendingInteractionSnapshot(
      [
        { sessionId: 'session-1', requestId: 'request-0' },
        { sessionId: 'session-1', requestId: 'request-2' },
      ],
      'permission',
      watermarks,
      3,
      120_000,
    )).toEqual([{ sessionId: 'session-1', requestId: 'request-0' }])
  })

  test('does not mutate the supplied watermark map', () => {
    const original = new Map<string, number>()
    markResolvedInteraction(original, permissionB, 100)
    expect(original.size).toBe(0)
  })
})
