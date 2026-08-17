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

/** 写入缓存（覆盖同 key 旧值），默认时效 60s */
export function setPreviewCache<T>(key: string, data: T, ttlMs: number = DEFAULT_TTL_MS): void {
  store.set(key, { data, expiresAt: Date.now() + ttlMs })
}

/** 清理缓存：传 key 清理单条，不传清理全部 */
export function clearPreviewCache(key?: string): void {
  if (key) {
    store.delete(key)
  } else {
    store.clear()
  }
}
