/**
 * preview-cache — 预览内容内存缓存（Pocket 文件预览 MVP）
 *
 * 反复点击同一文件预览时避免重复走 WS 从电脑端拉取，秒开。
 * - 按文件路径缓存（文本存内容字符串，图片存 base64 data URL）
 * - 带时效（默认 60s），过期后重新拉取：避免 Agent 刚改完文件后手机一直显示旧内容
 * - 应用会话内有效，刷新/重启清空；MVP 不做磁盘持久化（文件内容动态变化，持久化需变更校验）
 */
const DEFAULT_TTL_MS = 60_000

interface CacheEntry {
  data: unknown
  expiresAt: number
}

const store = new Map<string, CacheEntry>()

/** 读取缓存；未命中或已过期返回 undefined（过期条目顺手清理） */
export function getPreviewCache<T>(key: string): T | undefined {
  const entry = store.get(key)
  if (!entry) return undefined
  if (Date.now() > entry.expiresAt) {
    store.delete(key)
    return undefined
  }
  return entry.data as T
}

/**
 * 写入缓存（默认时效 60s）。若响应携带 version，则只接受比当前缓存更新的版本；
 * 这样乱序 WS 响应不会把新内容回退成旧内容。无版本响应不再进入缓存。
 */
export function setPreviewCache<T>(key: string, data: T, ttlMs: number = DEFAULT_TTL_MS): boolean {
  const incomingVersion = getVersion(data)
  if (!incomingVersion) return false
  const currentVersion = getVersion(store.get(key)?.data)
  if (currentVersion && currentVersion.hash === incomingVersion.hash) return true
  if (currentVersion && compareVersions(currentVersion, incomingVersion) > 0) return false
  store.set(key, { data, expiresAt: Date.now() + ttlMs })
  return true
}

interface PreviewVersion {
  revision: string
  mtimeMs: number
  size: number
  hash: string
}

function getVersion(value: unknown): PreviewVersion | undefined {
  if (!value || typeof value !== 'object') return undefined
  const version = (value as { version?: Partial<PreviewVersion> }).version
  if (
    typeof version?.revision !== 'string'
    || typeof version.mtimeMs !== 'number'
    || typeof version.size !== 'number'
    || typeof version.hash !== 'string'
  ) return undefined
  return {
    revision: version.revision,
    mtimeMs: version.mtimeMs,
    size: version.size,
    hash: version.hash,
  }
}

function compareVersions(current: PreviewVersion, incoming: PreviewVersion): number {
  if (current.hash === incoming.hash) return 0
  if (current.mtimeMs !== incoming.mtimeMs) return current.mtimeMs < incoming.mtimeMs ? -1 : 1
  if (current.size !== incoming.size) return current.size < incoming.size ? -1 : 1
  // 相同 mtime/size 但 hash 不同，无法从文件系统证明先后；接受后到响应，
  // 只阻止明确更旧的 mtime/size，避免真实修改被永久挡住。
  return 0
}

/** 清理缓存：传 key 清理单条，不传清理全部 */
export function clearPreviewCache(key?: string): void {
  if (key) {
    store.delete(key)
  } else {
    store.clear()
  }
}
