import { describe, expect, test } from 'bun:test'
import { clearPreviewCache, getPreviewCache, setPreviewCache } from './preview-cache'

const version = (revision: string, mtimeMs: number, size: number, hash: string) => ({
  revision,
  mtimeMs,
  size,
  hash,
})

describe('preview-cache version arbitration', () => {
  test('rejects an explicitly older response', () => {
    clearPreviewCache()
    const key = 'text:session:file'
    expect(setPreviewCache(key, { content: 'new', version: version('2', 200, 3, 'new') })).toBe(true)
    expect(setPreviewCache(key, { content: 'old', version: version('1', 100, 3, 'old') })).toBe(false)
    expect(getPreviewCache<{ content: string }>(key)?.content).toBe('new')
  })

  test('accepts a newer response and treats identical hashes as idempotent', () => {
    clearPreviewCache()
    const key = 'image:session:file'
    expect(setPreviewCache(key, { data: 'v1', version: version('1', 100, 2, 'same') })).toBe(true)
    expect(setPreviewCache(key, { data: 'same', version: version('1-replayed', 100, 2, 'same') })).toBe(true)
    expect(setPreviewCache(key, { data: 'v2', version: version('2', 300, 2, 'new') })).toBe(true)
    expect(getPreviewCache<{ data: string }>(key)?.data).toBe('v2')
  })

  test('does not cache unversioned responses', () => {
    clearPreviewCache()
    expect(setPreviewCache('unversioned', { content: 'unsafe' })).toBe(false)
    expect(getPreviewCache('unversioned')).toBeUndefined()
  })
})
