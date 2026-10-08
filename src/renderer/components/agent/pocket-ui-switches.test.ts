import { describe, expect, test } from 'bun:test'
import {
  resolvePresetCompactMode,
  resolvePresetListClassName,
  shouldRenderStickyUserMessage,
} from './pocket-ui-switches'

describe('R3 顶部悬浮条开关', () => {
  test('Given pocket When 有用户消息 Then 不渲染悬浮条', () => {
    expect(shouldRenderStickyUserMessage(true, 5)).toBe(false)
  })

  test('Given 桌面端 When 有用户消息 Then 维持原有渲染', () => {
    expect(shouldRenderStickyUserMessage(false, 5)).toBe(true)
  })

  test('Given 没有用户消息 Then 两侧都不渲染', () => {
    expect(shouldRenderStickyUserMessage(false, 0)).toBe(false)
    expect(shouldRenderStickyUserMessage(true, 0)).toBe(false)
  })
})

describe('R4 预设菜单极简与限高', () => {
  test('Given pocket When 本地偏好为宽松 Then 仍恒定极简', () => {
    expect(resolvePresetCompactMode(true, false)).toBe(true)
  })

  test('Given 桌面端 Then 完全沿用用户偏好', () => {
    expect(resolvePresetCompactMode(false, false)).toBe(false)
    expect(resolvePresetCompactMode(false, true)).toBe(true)
  })

  test('Given pocket When 取列表类名 Then 带最大高度与内部滚动', () => {
    const className = resolvePresetListClassName(true)

    expect(className).toContain('overflow-y-auto')
    expect(className).toContain('overscroll-contain')
    expect(className).toContain('max-h-[calc(var(--radix-popover-content-available-height)-2.5rem)]')
  })

  test('Given 桌面端 When 取列表类名 Then 不加限高（呈现不变）', () => {
    const className = resolvePresetListClassName(false)

    expect(className).toBe('flex flex-col gap-0.5')
    expect(className).not.toContain('max-h')
    expect(className).not.toContain('overflow-y-auto')
  })
})
