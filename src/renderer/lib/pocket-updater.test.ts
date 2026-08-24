import { describe, expect, test } from 'bun:test'
import { fetchPocketGithubJson, normalizeDownloadedPocketUpdate, parsePocketGithubJsonPayload } from './pocket-updater'

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

  test('safely parses the native GitHub JSON envelope', () => {
    expect(parsePocketGithubJsonPayload({ json: '{"versionCode":12}' })).toEqual({ versionCode: 12 })
    expect(() => parsePocketGithubJsonPayload({ json: '{' })).toThrow('无法读取')
    expect(() => parsePocketGithubJsonPayload(undefined)).toThrow('无法读取')
  })

  test('uses the Android bridge JSON response when supported', async () => {
    const originalWindow = (globalThis as { window?: unknown }).window
    let requestedUrl = ''
    try {
      ;(globalThis as { window?: unknown }).window = {
        Capacitor: {
          isNativePlatform: () => true,
          Plugins: {
            PocketUpdater: {
              fetchGithubJson: async ({ url }: { url: string }) => {
                requestedUrl = url
                return { json: '{"release":"native"}' }
              },
            },
          },
        },
      }
      const response = await fetchPocketGithubJson('https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest', { headers: { Accept: 'application/json' } })
      expect(requestedUrl).toBe('https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest')
      expect(await response.json()).toEqual({ release: 'native' })
    } finally {
      ;(globalThis as { window?: unknown }).window = originalWindow
    }
  })

  test('uses standard fetch when the Android bridge is unavailable', async () => {
    const originalWindow = (globalThis as { window?: unknown }).window
    const originalFetch = globalThis.fetch
    const browserFetch = async (): Promise<Response> => ({ ok: true, status: 200, json: async () => ({ release: 'browser' }) } as Response)
    try {
      ;(globalThis as { window?: unknown }).window = { Capacitor: { isNativePlatform: () => false } }
      globalThis.fetch = browserFetch
      const response = await fetchPocketGithubJson('https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest', {})
      expect(await response.json()).toEqual({ release: 'browser' })
    } finally {
      ;(globalThis as { window?: unknown }).window = originalWindow
      globalThis.fetch = originalFetch
    }
  })
})
