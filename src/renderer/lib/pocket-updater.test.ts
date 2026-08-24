import { describe, expect, test } from 'bun:test'
import { normalizeDownloadedPocketUpdate } from './pocket-updater'

describe('PocketUpdater Capacitor bridge', () => {
  test('normalizes an empty native call.resolve payload to null', () => {
    expect(normalizeDownloadedPocketUpdate(undefined)).toBeNull()
    expect(normalizeDownloadedPocketUpdate(null)).toBeNull()
  })

  test('keeps a complete verified native download record', () => {
    const record = normalizeDownloadedPocketUpdate({ versionCode: 11, versionName: '0.1.12', apkPath: '/data/update.apk', sha256: 'a'.repeat(64), url: 'https://github.com/example.apk' })
    expect(record?.apkPath).toBe('/data/update.apk')
    expect(record?.downloadUrl).toBe('https://github.com/example.apk')
  })
})
