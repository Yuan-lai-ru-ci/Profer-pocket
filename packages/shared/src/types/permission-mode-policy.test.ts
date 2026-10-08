import { describe, expect, test } from 'bun:test'
import {
  buildPermissionModeMenu,
  canSelectPermissionMode,
  describePermissionModeRestriction,
  resolveEffectivePermissionMode,
  resolveSelectorPermissionMode,
} from './agent'

describe('双端权限模式策略', () => {
  test('plan 预设上限下 bypassPermissions 不可选且不会被静默持久化', () => {
    expect(resolveEffectivePermissionMode('plan', 'bypassPermissions')).toBe('plan')
    expect(canSelectPermissionMode('plan', 'bypassPermissions')).toBe(false)
    expect(buildPermissionModeMenu('plan').find((entry) => entry.mode === 'bypassPermissions')?.selectable).toBe(false)
    expect(describePermissionModeRestriction('plan')).toContain('计划模式')
  })

  test('未显式 override 时按预设模式显示，较严格 override 可选', () => {
    expect(resolveSelectorPermissionMode('auto', undefined)).toBe('auto')
    expect(resolveSelectorPermissionMode('bypassPermissions', 'plan')).toBe('plan')
    expect(canSelectPermissionMode('bypassPermissions', 'plan')).toBe(true)
  })
})
