import { describe, expect, test } from 'bun:test'
import { fetchLatestPocketRelease, isPocketUpdateAvailable, parsePocketReleaseUpdate, parsePocketUpdateManifest } from './pocket-update-service'

const manifest = { versionCode: 11, versionName: '0.1.12', apkAssetName: 'Profer-Pocket-0.1.12.apk', sha256: 'a'.repeat(64), releaseNotes: '- 修复更新', mandatory: false }
const release = { assets: [
  { name: 'pocket-update.json', browser_download_url: 'https://github.com/Yuan-lai-ru-ci/Profer-pocket/releases/download/v0.1.12/pocket-update.json' },
  { name: manifest.apkAssetName, browser_download_url: 'https://github.com/Yuan-lai-ru-ci/Profer-pocket/releases/download/v0.1.12/Profer-Pocket-0.1.12.apk' },
] }

describe('Pocket GitHub Release update contract', () => {
  test('accepts a complete manifest and its declared HTTPS GitHub APK asset', () => {
    const update = parsePocketReleaseUpdate(release, manifest)
    expect(update.versionCode).toBe(11)
    expect(update.apkDownloadUrl).toContain(manifest.apkAssetName)
  })

  test('rejects malformed SHA-256 and missing APK assets', () => {
    expect(() => parsePocketUpdateManifest({ ...manifest, sha256: 'ABC' })).toThrow('sha256')
    expect(() => parsePocketReleaseUpdate({ assets: [] }, manifest)).toThrow('找不到 APK asset')
  })

  test('rejects non-GitHub, lookalike-host, or non-HTTPS download URLs', () => {
    for (const browser_download_url of [
      'http://github.com/example.apk',
      'https://mirror.example/update.apk',
      'https://attacker.github.com/update.apk',
      'https://github.com:444/update.apk',
      'https://user:pass@github.com/update.apk',
    ]) {
      expect(() => parsePocketReleaseUpdate({ assets: [{ name: manifest.apkAssetName, browser_download_url }] }, manifest)).toThrow('HTTPS GitHub')
    }
  })

  test('requires the APK asset name to match versionName', () => {
    expect(() => parsePocketUpdateManifest({ ...manifest, apkAssetName: 'Profer-Pocket-11.apk' })).toThrow('apkAssetName')
  })

  test('converts an aborted GitHub request into a timeout error', async () => {
    const fetcher: typeof fetch = (_input, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    })
    await expect(fetchLatestPocketRelease(fetcher, 1)).rejects.toThrow('超时')
  })

  test('rejects a manifest response redirected outside trusted GitHub hosts', async () => {
    const response = (body: unknown, url: string): Response => ({ ok: true, status: 200, url, json: async () => body } as Response)
    const fetcher: typeof fetch = async (input) => String(input) === 'https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest'
      ? response(release, 'https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest')
      : response(manifest, 'https://mirror.example/pocket-update.json')
    await expect(fetchLatestPocketRelease(fetcher)).rejects.toThrow('来源地址不受信任')
  })

  test('uses strict versionCode comparison rather than versionName', () => {
    const update = parsePocketReleaseUpdate(release, manifest)
    expect(isPocketUpdateAvailable(10, update)).toBe(true)
    expect(isPocketUpdateAvailable(11, update)).toBe(false)
    expect(isPocketUpdateAvailable(1010, update)).toBe(false)
  })
})
