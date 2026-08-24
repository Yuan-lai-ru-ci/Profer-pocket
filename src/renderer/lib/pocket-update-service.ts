/** GitHub Releases protocol parser for Pocket APK updates. No file or installer work belongs here. */

export const POCKET_RELEASES_API = 'https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest'

export interface PocketReleaseAsset { name: string; browser_download_url: string }
export interface PocketReleaseResponse { draft?: boolean; prerelease?: boolean; assets?: PocketReleaseAsset[] }
export interface PocketUpdateManifest {
  versionCode: number
  versionName: string
  apkAssetName: string
  sha256: string
  releaseNotes: string
  mandatory: boolean
}
export interface PocketReleaseUpdate extends PocketUpdateManifest { apkDownloadUrl: string }

function invalid(message: string): never { throw new Error(`更新清单无效：${message}`) }
function record(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) invalid('内容不是对象'); return value as Record<string, unknown> }
const GITHUB_ASSET_HOSTS = new Set(['github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com'])

function httpsGithubAssetUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && GITHUB_ASSET_HOSTS.has(url.hostname) &&
      !url.username && !url.password && (url.port === '' || url.port === '443')
  } catch { return false }
}

function httpsGithubApiUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'api.github.com' &&
      !url.username && !url.password && (url.port === '' || url.port === '443')
  } catch { return false }
}

export function parsePocketUpdateManifest(value: unknown): PocketUpdateManifest {
  const manifest = record(value)
  const versionCode = manifest.versionCode
  const versionName = manifest.versionName
  const apkAssetName = manifest.apkAssetName
  const sha256 = manifest.sha256
  const releaseNotes = manifest.releaseNotes
  const mandatory = manifest.mandatory
  if (!Number.isInteger(versionCode) || (versionCode as number) < 1) invalid('versionCode 必须为正整数')
  if (typeof versionName !== 'string' || !versionName.trim()) invalid('versionName 缺失')
  if (typeof apkAssetName !== 'string' || !apkAssetName.trim()) invalid('apkAssetName 缺失')
  if (typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(sha256)) invalid('sha256 必须为 64 位小写十六进制')
  if (typeof releaseNotes !== 'string') invalid('releaseNotes 必须为字符串')
  if (typeof mandatory !== 'boolean') invalid('mandatory 必须为布尔值')
  const normalizedVersionName = (versionName as string).trim()
  const normalizedAssetName = (apkAssetName as string).trim()
  if (normalizedAssetName !== `Profer-Pocket-${normalizedVersionName}.apk`) invalid('apkAssetName 必须与 versionName 一致')
  return { versionCode: versionCode as number, versionName: normalizedVersionName, apkAssetName: normalizedAssetName, sha256, releaseNotes, mandatory }
}

export function parsePocketReleaseUpdate(release: PocketReleaseResponse, manifestValue: unknown): PocketReleaseUpdate {
  if (release.draft || release.prerelease) invalid('只接受正式 GitHub Release')
  if (!Array.isArray(release.assets)) invalid('Release 缺少 assets')
  const manifest = parsePocketUpdateManifest(manifestValue)
  const apk = release.assets.find((asset) => asset?.name === manifest.apkAssetName)
  if (!apk || typeof apk.browser_download_url !== 'string') invalid(`找不到 APK asset：${manifest.apkAssetName}`)
  if (!httpsGithubAssetUrl(apk.browser_download_url)) invalid('APK 下载地址必须是 HTTPS GitHub asset 地址')
  return { ...manifest, apkDownloadUrl: apk.browser_download_url }
}

export function isPocketUpdateAvailable(currentVersionCode: number, update: PocketReleaseUpdate): boolean {
  return Number.isInteger(currentVersionCode) && currentVersionCode > 0 && update.versionCode > currentVersionCode
}

async function responseJson(response: Response, label: string): Promise<unknown> {
  if (!response.ok) throw new Error(`${label}失败（HTTP ${response.status}）`)
  try { return await response.json() } catch { throw new Error(`${label}返回了无法读取的数据`) }
}

export const POCKET_RELEASE_REQUEST_TIMEOUT_MS = 15_000

async function responseJsonWithTimeout(
  fetcher: typeof fetch,
  url: string,
  init: RequestInit,
  label: string,
  timeoutMs = POCKET_RELEASE_REQUEST_TIMEOUT_MS,
  finalUrlValidator?: (url: string) => boolean,
): Promise<unknown> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetcher(url, { ...init, signal: controller.signal })
    if (finalUrlValidator && response.url && !finalUrlValidator(response.url)) throw new Error(`${label}来源地址不受信任`)
    return await responseJson(response, label)
  } catch (error) {
    if (controller.signal.aborted) throw new Error(`${label}超时，请稍后重试`)
    throw error
  } finally {
    clearTimeout(timeout)
  }
}

/** Fetches exactly the latest official GitHub release, then its declared manifest asset. */
export async function fetchLatestPocketRelease(fetcher: typeof fetch = fetch, timeoutMs = POCKET_RELEASE_REQUEST_TIMEOUT_MS): Promise<PocketReleaseUpdate> {
  let release: PocketReleaseResponse
  try {
    release = await responseJsonWithTimeout(fetcher, POCKET_RELEASES_API, { headers: { Accept: 'application/vnd.github+json' } }, '获取 GitHub Release', timeoutMs, httpsGithubApiUrl) as PocketReleaseResponse
  } catch (error) { throw new Error(error instanceof Error ? error.message : '获取 GitHub Release 失败') }
  if (release.draft || release.prerelease) invalid('最新 Release 不是正式版本')
  const manifestAsset = release.assets?.find((asset) => asset?.name === 'pocket-update.json')
  if (!manifestAsset || !httpsGithubAssetUrl(manifestAsset.browser_download_url)) invalid('找不到安全的 pocket-update.json asset')
  const manifest = await responseJsonWithTimeout(fetcher, manifestAsset.browser_download_url, { headers: { Accept: 'application/json' } }, '读取更新清单', timeoutMs, httpsGithubAssetUrl)
  return parsePocketReleaseUpdate(release, manifest)
}
