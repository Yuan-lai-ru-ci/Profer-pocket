import { describe, expect, test } from 'bun:test'
import { getStartupMotionPlan } from './startup-motion'
import { getStartupReadyFadeDelay } from './PocketStartupMotion'
import { POCKET_BRAND_GEOMETRY } from './brand-geometry'

describe('getStartupMotionPlan', () => {
  test('keeps regular startup bounded for compositor-friendly motion', () => {
    const plan = getStartupMotionPlan(false)
    expect(plan.ringCount).toBe(3)
    expect(plan.maxDurationMs).toBeLessThanOrEqual(1800)
    expect(plan.fadeOutMs).toBeGreaterThan(0)
  })

  test('disables expanding rings for reduced motion', () => {
    const plan = getStartupMotionPlan(true)
    expect(plan.reducedMotion).toBe(true)
    expect(plan.ringCount).toBe(0)
    expect(plan.ringsMs).toBe(0)
    expect(plan.maxDurationMs).toBeLessThan(800)
  })

  test('keeps CSS timing values in one plan', () => {
    const plan = getStartupMotionPlan(false)
    expect(plan.wordmarkDelayMs).toBe(180)
    expect(plan.ringDelayMs).toBe(160)
    expect(plan.fadeOutMs).toBe(360)
    expect(plan.maxDurationMs).toBeGreaterThanOrEqual(plan.minVisibleMs + plan.fadeOutMs)
  })

  test('ready hand-off respects the minimum brand frame', () => {
    const plan = getStartupMotionPlan(false)
    expect(getStartupReadyFadeDelay(plan, 100, 0)).toBe(plan.minVisibleMs - 100)
    expect(getStartupReadyFadeDelay(plan, 1_000, 0)).toBe(0)
  })
})

describe('POCKET_BRAND_GEOMETRY', () => {
  test('matches the desktop icon transform contract', () => {
    expect(POCKET_BRAND_GEOMETRY.outer).toEqual({ x: 57.6, y: 67, width: 909.6, height: 892.8, radius: 224 })
    expect(POCKET_BRAND_GEOMETRY.surface).toEqual({ x: 236.8, y: 219.8, width: 551.2, height: 551.2, radius: 120 })
    expect(POCKET_BRAND_GEOMETRY.shadow).toEqual({ x: 519.2, y: 363.8, width: 268.8, height: 407.2 })
    expect(POCKET_BRAND_GEOMETRY.accent).toEqual({ x: 638.4, y: 475, width: 149.6, height: 296 })
  })

  test('keeps the Android splash resource on the same transformed geometry', async () => {
    const xml = await Bun.file('pocket-app/android/app/src/main/res/drawable/profer_logo.xml').text()
    expect(xml).toContain('M281.6,67H743.2A224,224')
    expect(xml).toContain('M356.8,219.8H668A120,120')
    expect(xml).toContain('M519.2,363.8H788V771H519.2Z')
    expect(xml).toContain('M638.4,475H788V771H638.4Z')
  })
})
