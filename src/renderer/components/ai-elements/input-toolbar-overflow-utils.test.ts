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
    expect(
      calculateVisibleCount(items.map(({ key }) => ({ key, width: 0, measured: false })), 240, 6, 36),
    ).toBeNull()
  })

  test('已测量但渲染为空的项（0 宽）不阻断折叠，也不占宽度', () => {
    // 上下文圆环无 usage 时渲染为空：0 宽必须继续参与计算，
    // 否则折叠计算永久返回 null → 不生成「更多」→ 尾部按钮被裁掉。
    const withEmptyItem = [
      { key: 'model', width: 120, measured: true },
      { key: 'context-usage', width: 0, measured: true },
      { key: 'runtime', width: 49, measured: true },
      { key: 'attach', width: 44, measured: true },
    ]

    expect(calculateVisibleCount(withEmptyItem, 180, 6, 36)).toBe(2)
  })

  test('returns an overflow count on a narrow first layout', () => {
    // 120 + 6 + 36 fits, but reserving More (36 + 6) requires one item.
    expect(calculateVisibleCount(items, 168, 6, 36)).toBe(1)
  })

  test('preserves desktop behavior when every item fits', () => {
    expect(calculateVisibleCount(items, 300, 6, 36)).toBe(items.length)
  })
})
