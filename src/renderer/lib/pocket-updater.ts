/** PocketUpdater Capacitor bridge. Keeps browser previews safe and normalizes empty native payloads. */

export interface PocketNativeVersion { versionCode: number; versionName: string }
/** Raw Java payload uses `url`; the public TypeScript record normalizes it to `downloadUrl`. */
type RawDownloadedPocketUpdate = PocketNativeVersion & { apkPath: string; sha256: string; url: string }
export interface DownloadedPocketUpdate extends PocketNativeVersion { apkPath: string; sha256: string; downloadUrl: string }
export interface PocketDownloadProgress { percent: number; transferred: number; total: number }

type PluginListener = { remove: () => void }
type PocketUpdaterPlugin = {
  getCurrentVersion?: () => Promise<PocketNativeVersion>
  getDownloadedUpdate?: () => Promise<RawDownloadedPocketUpdate | undefined | null>
  downloadUpdate?: (options: { url: string; sha256: string; versionCode: number; versionName: string }) => Promise<void>
  installDownloadedUpdate?: () => Promise<void>
  clearDownloadedUpdate?: () => Promise<void>
  addListener?: (event: 'updateDownloadProgress', callback: (progress: PocketDownloadProgress) => void) => Promise<PluginListener> | PluginListener
}

type PocketCapacitorGlobal = { Capacitor?: { isNativePlatform?: () => boolean; Plugins?: { PocketUpdater?: PocketUpdaterPlugin } } }

function plugin(): PocketUpdaterPlugin | undefined {
  if (typeof window === 'undefined') return undefined
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  return cap?.isNativePlatform?.() ? cap.Plugins?.PocketUpdater : undefined
}

function unavailable(): Error { return new Error('当前环境不支持 APK 更新，请在 Android 应用内操作。') }

export async function getPocketNativeVersion(): Promise<PocketNativeVersion> {
  const value = await plugin()?.getCurrentVersion?.()
  if (!value || !Number.isInteger(value.versionCode) || value.versionCode < 1 || !value.versionName) throw unavailable()
  return value
}

/** Native `call.resolve()` without payload becomes undefined in Capacitor JS; normalize it here. */
export function normalizeDownloadedPocketUpdate(value: RawDownloadedPocketUpdate | undefined | null): DownloadedPocketUpdate | null {
  if (!value || typeof value.apkPath !== 'string' || typeof value.sha256 !== 'string' || typeof value.url !== 'string') return null
  return { versionCode: value.versionCode, versionName: value.versionName, apkPath: value.apkPath, sha256: value.sha256, downloadUrl: value.url }
}

export async function getDownloadedPocketUpdate(): Promise<DownloadedPocketUpdate | null> {
  return normalizeDownloadedPocketUpdate(await plugin()?.getDownloadedUpdate?.())
}

export async function downloadPocketUpdate(options: { url: string; sha256: string; versionCode: number; versionName: string }): Promise<void> {
  const fn = plugin()?.downloadUpdate
  if (!fn) throw unavailable()
  await fn(options)
}

export async function installDownloadedPocketUpdate(): Promise<void> {
  const fn = plugin()?.installDownloadedUpdate
  if (!fn) throw unavailable()
  await fn()
}

export async function clearDownloadedPocketUpdate(): Promise<void> { await plugin()?.clearDownloadedUpdate?.() }

export async function listenPocketUpdateProgress(callback: (progress: PocketDownloadProgress) => void): Promise<() => void> {
  const handle = await plugin()?.addListener?.('updateDownloadProgress', callback)
  return () => { handle?.remove() }
}
