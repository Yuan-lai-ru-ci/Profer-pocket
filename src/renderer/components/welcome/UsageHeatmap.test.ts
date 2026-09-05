import { describe, expect, test } from 'bun:test'
import { getHeatmapScale } from '@/lib/heatmap-responsive'

describe('getHeatmapScale', () => {
  test('keeps the normal heatmap size when the container is wide enough', () => {
    expect(getHeatmapScale(391, 391)).toBe(1)
    expect(getHeatmapScale(640, 391)).toBe(1)
  })

  test('scales down proportionally when the container is narrower', () => {
    expect(getHeatmapScale(320, 391)).toBeCloseTo(320 / 391)
    expect(getHeatmapScale(195.5, 391)).toBeCloseTo(0.5)
  })

  test('falls back to the normal scale for unavailable measurements', () => {
    expect(getHeatmapScale(0, 391)).toBe(1)
    expect(getHeatmapScale(-10, 391)).toBe(1)
    expect(getHeatmapScale(Number.NaN, 391)).toBe(1)
  })
})
