import { describe, expect, test } from 'bun:test'
import { formatLocalDateKey } from './heatmap-utils'

describe('formatLocalDateKey', () => {
  test('uses the local calendar date instead of shifting through UTC', () => {
    const localDate = new Date(2026, 7, 21, 0, 0, 0, 0)

    expect(formatLocalDateKey(localDate)).toBe('2026-08-21')
  })
})
