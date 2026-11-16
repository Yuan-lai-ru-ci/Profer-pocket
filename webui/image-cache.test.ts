/**
 * 图片 LRU 缓存的行为测试。
 *
 * 这条逻辑唯一的失败模式是「内存悄悄涨」与「热点被误淘汰」，
 * 两者在 UI 上都看不出来，所以用测试钉住淘汰顺序。
 */
import { describe, expect, test } from 'bun:test'
import { createLruCache } from './image-cache'

describe('图片 LRU 缓存', () => {
  test('Given 上限为 2 When 写入 3 条 Then 淘汰最久未用的那条', () => {
    const cache = createLruCache<string>(2)
    cache.set('a', '1')
    cache.set('b', '2')
    cache.set('c', '3')
    expect(cache.size).toBe(2)
    expect(cache.get('a')).toBeUndefined()
    expect(cache.get('b')).toBe('2')
    expect(cache.get('c')).toBe('3')
  })

  test('Given a 已存在 When 命中 a 后再写入 c Then 淘汰的是 b 而不是 a', () => {
    const cache = createLruCache<string>(2)
    cache.set('a', '1')
    cache.set('b', '2')
    expect(cache.get('a')).toBe('1')
    cache.set('c', '3')
    expect(cache.get('a')).toBe('1')
    expect(cache.get('b')).toBeUndefined()
    expect(cache.get('c')).toBe('3')
  })

  test('Given 同一个 key 写两次 When 读取 Then 只占一个位置且是后写入的值', () => {
    const cache = createLruCache<string>(2)
    cache.set('a', '旧')
    cache.set('a', '新')
    cache.set('b', '2')
    expect(cache.size).toBe(2)
    expect(cache.get('a')).toBe('新')
  })

  test('Given 上限写成 0 或负数 When 写入 Then 仍保留最近一条（不会变成永不缓存）', () => {
    for (const limit of [0, -5]) {
      const cache = createLruCache<string>(limit)
      cache.set('a', '1')
      cache.set('b', '2')
      expect(cache.size).toBe(1)
      expect(cache.get('b')).toBe('2')
    }
  })

  test('Given 手动删除 When 再读取 Then 不会复活旧值', () => {
    const cache = createLruCache<string>(4)
    cache.set('a', '1')
    cache.delete('a')
    expect(cache.get('a')).toBeUndefined()
    expect(cache.size).toBe(0)
  })
})
