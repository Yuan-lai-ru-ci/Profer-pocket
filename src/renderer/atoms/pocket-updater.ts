import { atom } from 'jotai'
import type { Store } from 'jotai/vanilla/store'
import { downloadPocketUpdate, getDownloadedPocketUpdate, getPocketNativeVersion, installDownloadedPocketUpdate, listenPocketUpdateProgress, type PocketDownloadProgress } from '@/lib/pocket-updater'
import { fetchLatestPocketRelease, isPocketUpdateAvailable, type PocketReleaseUpdate } from '@/lib/pocket-update-service'

export type PocketUpdateStatus =
  | { status: 'idle' }
  | { status: 'checking' }
  | { status: 'not-available' }
  | { status: 'available'; update: PocketReleaseUpdate }
  | { status: 'downloading'; update: PocketReleaseUpdate; progress: PocketDownloadProgress }
  | { status: 'downloaded'; update: PocketReleaseUpdate; apkPath: string }
  | { status: 'error'; phase: 'check' | 'download' | 'verify' | 'install'; message: string; update?: PocketReleaseUpdate; apkPath?: string }

export const pocketUpdateStatusAtom = atom<PocketUpdateStatus>({ status: 'idle' })
export const pocketNativeVersionAtom = atom<{ versionCode: number; versionName: string } | null>(null)
/** Deliberately in-memory only: reset after app restart. */
export const pocketUpdateDeferredAtom = atom(false)
export const pocketUpdateCardHiddenAtom = atom(false)

function message(error: unknown, fallback: string): string { return error instanceof Error && error.message ? error.message : fallback }
function cachedUpdate(versionCode: number, versionName: string, sha256: string, downloadUrl: string): PocketReleaseUpdate {
  return { versionCode, versionName, sha256, apkAssetName: `Profer-Pocket-${versionName}.apk`, releaseNotes: '', mandatory: false, apkDownloadUrl: downloadUrl }
}

function matchesPocketUpdate(downloaded: Awaited<ReturnType<typeof getDownloadedPocketUpdate>> | null, update: PocketReleaseUpdate): downloaded is NonNullable<typeof downloaded> {
  return Boolean(downloaded && downloaded.versionCode === update.versionCode && downloaded.versionName === update.versionName && downloaded.sha256 === update.sha256)
}

export async function initializePocketUpdater(store: Store): Promise<boolean> {
  try {
    const current = await getPocketNativeVersion()
    store.set(pocketNativeVersionAtom, current)
    const downloaded = await getDownloadedPocketUpdate()
    if (downloaded && downloaded.versionCode > current.versionCode) {
      store.set(pocketUpdateStatusAtom, { status: 'downloaded', update: cachedUpdate(downloaded.versionCode, downloaded.versionName, downloaded.sha256, downloaded.downloadUrl), apkPath: downloaded.apkPath })
    }
  } catch {
    // Browser preview and older native shells do not have the updater. They remain usable without an error card.
    return false
  }
  await listenPocketUpdateProgress((progress) => {
    const current = store.get(pocketUpdateStatusAtom)
    if (current.status === 'downloading') store.set(pocketUpdateStatusAtom, { ...current, progress })
  })
  return true
}

let latestCheckSequence = 0

export async function checkPocketUpdate(store: Store): Promise<void> {
  const sequence = ++latestCheckSequence
  const isLatest = (): boolean => sequence === latestCheckSequence
  const current = store.get(pocketNativeVersionAtom)
  if (!current) {
    try { store.set(pocketNativeVersionAtom, await getPocketNativeVersion()) }
    catch (error) {
      if (isLatest()) store.set(pocketUpdateStatusAtom, { status: 'error', phase: 'check', message: message(error, '无法读取当前应用版本') })
      return
    }
  }
  const native = store.get(pocketNativeVersionAtom)!
  const previous = store.get(pocketUpdateStatusAtom)
  if (isLatest()) store.set(pocketUpdateStatusAtom, { status: 'checking' })
  let downloaded: Awaited<ReturnType<typeof getDownloadedPocketUpdate>> = null
  try {
    const update = await fetchLatestPocketRelease()
    downloaded = await getDownloadedPocketUpdate()
    if (!isLatest()) return
    if (downloaded && downloaded.versionCode === update.versionCode && downloaded.sha256 === update.sha256 && update.versionCode > native.versionCode) {
      store.set(pocketUpdateStatusAtom, { status: 'downloaded', update, apkPath: downloaded.apkPath })
    } else if (isPocketUpdateAvailable(native.versionCode, update)) {
      store.set(pocketUpdateStatusAtom, { status: 'available', update })
    } else {
      store.set(pocketUpdateStatusAtom, { status: 'not-available' })
    }
  } catch (error) {
    if (!isLatest()) return
    const update = previous.status === 'available' || previous.status === 'downloading' || previous.status === 'downloaded' || previous.status === 'error' ? previous.update : undefined
    const apkPath = downloaded?.apkPath ?? ((previous.status === 'downloaded' || previous.status === 'error') ? previous.apkPath : undefined)
    store.set(pocketUpdateStatusAtom, { status: 'error', phase: 'check', message: message(error, '检查更新失败，请稍后重试'), update, apkPath })
  }
}

export async function downloadAvailablePocketUpdate(store: Store): Promise<void> {
  const current = store.get(pocketUpdateStatusAtom)
  const update = current.status === 'available' || current.status === 'error' ? current.update : undefined
  if (!update) return
  store.set(pocketUpdateCardHiddenAtom, false)
  store.set(pocketUpdateStatusAtom, { status: 'downloading', update, progress: { percent: 0, transferred: 0, total: 0 } })
  try {
    await downloadPocketUpdate({ url: update.apkDownloadUrl, sha256: update.sha256, versionCode: update.versionCode, versionName: update.versionName })
    const downloaded = await getDownloadedPocketUpdate()
    if (!downloaded || downloaded.versionCode !== update.versionCode || downloaded.sha256 !== update.sha256) throw new Error('安装包校验结果不可用，请重新下载')
    store.set(pocketUpdateStatusAtom, { status: 'downloaded', update, apkPath: downloaded.apkPath })
  } catch (error) {
    const cached = await getDownloadedPocketUpdate().catch(() => null)
    store.set(pocketUpdateStatusAtom, { status: 'error', phase: 'download', message: message(error, '下载更新失败，请重试'), update, apkPath: matchesPocketUpdate(cached, update) ? cached.apkPath : undefined })
  }
}

export async function installPocketUpdate(store: Store): Promise<void> {
  const current = store.get(pocketUpdateStatusAtom)
  const update = current.status === 'downloaded' || (current.status === 'error' && (current.phase === 'install' || (current.phase === 'check' && current.apkPath))) ? current.update : undefined
  if (!update) return
  const currentApkPath = current.status === 'downloaded' || current.status === 'error' ? current.apkPath : undefined
  try { await installDownloadedPocketUpdate() }
  catch (error) {
    const cached = await getDownloadedPocketUpdate().catch(() => null)
    store.set(pocketUpdateStatusAtom, { status: 'error', phase: 'install', message: message(error, '无法打开系统安装器'), update, apkPath: matchesPocketUpdate(cached, update) ? cached.apkPath : currentApkPath })
  }
}

/** Retry the failed operation without falling back to a different source or action. */
export async function retryPocketUpdate(store: Store): Promise<void> {
  const current = store.get(pocketUpdateStatusAtom)
  if (current.status !== 'error') return
  if (current.phase === 'check') return checkPocketUpdate(store)
  if (current.phase === 'install') return installPocketUpdate(store)
  return downloadAvailablePocketUpdate(store)
}

export function deferPocketUpdate(store: Store): void { store.set(pocketUpdateDeferredAtom, true); store.set(pocketUpdateCardHiddenAtom, true) }
export function hidePocketUpdateCard(store: Store): void { store.set(pocketUpdateCardHiddenAtom, true) }
