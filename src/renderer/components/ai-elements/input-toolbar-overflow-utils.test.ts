import { describe, expect, test } from 'bun:test'
import { calculateVisibleCount } from './input-toolbar-overflow-utils'

describe('calculateVisibleCount', () => {
  const items = [
    { key: 'model', width: 120 },
    { key: 'thinking', width: 36 },
    { key: 'speech', width: 36 },
    { key: 'attach', width: 36 },
  ]

  test('keeps the initial render pending until every item is measured', () => {
    expect(calculateVisibleCount(items.map(({ key }) => ({ key, width: 0 })), 240, 6, 36)).toBeNull()
  })

  test('returns an overflow count on a narrow first layout', () => {
    // 120 + 6 + 36 fits, but reserving More (36 + 6) requires one item.
    expect(calculateVisibleCount(items, 168, 6, 36)).toBe(1)
  })

  test('preserves desktop behavior when every item fits', () => {
    expect(calculateVisibleCount(items, 300, 6, 36)).toBe(items.length)
  })
})
