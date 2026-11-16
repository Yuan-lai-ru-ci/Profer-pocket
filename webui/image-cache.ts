/**
 * 有上限的 LRU 缓存（单文件实现，不引依赖）。
 *
 * 用在这里的具体理由：图片以 data URL 形式缓存，本身就是 base64 常驻内存 ——
 * 一张 2MB 的截图占 2.7MB。长会话翻历史看几十张图就会一路涨到几百 MB，
 * 手机端直接被杀进程，而现象是「用久了就白屏」，极难归因。
 *
 * 用 Map 的插入顺序当队列：命中后删除再插入 = 移到队尾，队首即最久未用。
 */
export interface LruCache<V> {
  get(key: string): V | undefined
  set(key: string, value: V): void
  delete(key: string): void
  readonly size: number
}

export function createLruCache<V>(limit: number): LruCache<V> {
  const capacity = Math.max(1, Math.floor(limit))
  const store = new Map<string, V>()
  return {
    get size() { return store.size },
    get(key) {
      if (!store.has(key)) return undefined
      const value = store.get(key) as V
      // 命中即刷新到队尾：否则热点图片会因为「最早插入」被先淘汰
      store.delete(key)
      store.set(key, value)
      return value
    },
    set(key, value) {
      store.delete(key)
      store.set(key, value)
      while (store.size > capacity) {
        const oldest = store.keys().next()
        if (oldest.done) break
        store.delete(oldest.value)
      }
    },
    delete(key) { store.delete(key) },
  }
}
