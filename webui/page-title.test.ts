import { describe, expect, test } from 'bun:test'
import { DEFAULT_PAGE_TITLE, resolvePageTitle } from './page-title'

describe('标签页标题', () => {
  test('Given 会话有名字 When 取标题 Then 直接用会话名', () => {
    expect(resolvePageTitle('把构建修好')).toBe('把构建修好')
  })

  test('Given 名字首尾有空白 When 取标题 Then 去掉空白（否则标签上会多出空隙）', () => {
    expect(resolvePageTitle('  修登录  ')).toBe('修登录')
  })

  test('Given 新会话还没有名字 When 取标题 Then 退回固定文案，不拿 sessionId 当名字', () => {
    expect(resolvePageTitle('')).toBe(DEFAULT_PAGE_TITLE)
    expect(resolvePageTitle('   ')).toBe(DEFAULT_PAGE_TITLE)
    expect(resolvePageTitle(undefined)).toBe(DEFAULT_PAGE_TITLE)
    expect(resolvePageTitle(null)).toBe(DEFAULT_PAGE_TITLE)
  })

  test('Given 服务端返回非字符串 When 取标题 Then 不把对象变成 [object Object]', () => {
    expect(resolvePageTitle({ title: 'x' })).toBe(DEFAULT_PAGE_TITLE)
    expect(resolvePageTitle(42)).toBe(DEFAULT_PAGE_TITLE)
  })
})
