/**
 * localStorage 小工具 — 移动端独立客户端（apps/tablet-client）持久化层
 *
 * 职责：统一封装本设备（平板/手机 WebView）本地持久化的读写，
 * 与桌面 tablet 版（apps/electron/src/renderer/tablet/main.tsx 顶部的一批
 * getStoredToken/storeToken 等私有函数）语义对齐，但独立定义、不 import 桌面代码。
 *
 * 存储 key 保持与桌面 tablet 版一致（profer-remote-*），这样用户在桌面 Web 版与
 * 移动端之间切换浏览器（同源场景）时，已绑定的 token/服务器地址可复用；
 * 移动端独立 App（Capacitor WebView，独立 origin）则各自独立存储，互不影响。
 *
 * 说明：所有读写都做 try/catch 兜底 —— 隐私模式 / WebView 存储受限 / JSON 解析失败
 * 时不抛异常，静默降级为“无缓存”，由上层走默认推导逻辑。
 */

/** localStorage key 集合（与桌面 tablet 版保持一致） */
export const STORAGE_KEYS = {
  /** 访问令牌 */
  token: 'profer-remote-token',
  /** 显式服务器地址（http://host:port 或 ws://host:port/ws；留空 = 自动推导） */
  server: 'profer-remote-server',
  /** 最近打开的视图（整页重载 / 断线重连后恢复现场） */
  lastView: 'profer-remote-last-view',
  /** UI 缩放比例（触屏友好，默认无缓存时 110%） */
  uiScale: 'profer-remote-ui-scale',
  /** Agent 完成提醒音开关 */
  notifyComplete: 'profer-remote-notify-complete',
} as const

/** 上次视图形状（模式 + 会话/对话 ID） */
export interface StoredLastView {
  mode: 'agent' | 'chat'
  sessionId?: string
  conversationId?: string
}

/** 安全读取字符串。异常或不存在返回 null。 */
function getRaw(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

/** 安全写入字符串。异常时静默忽略。 */
function setRaw(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* ignore：存储受限（隐私模式 / 空间满）时降级为内存态 */
  }
}

/** 安全删除。 */
function removeRaw(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    /* ignore */
  }
}

// ===== token =====

export function getStoredToken(): string {
  return getRaw(STORAGE_KEYS.token) || ''
}

export function storeToken(token: string): void {
  setRaw(STORAGE_KEYS.token, token)
}

// ===== 服务器地址 =====

export function getStoredServerUrl(): string {
  return getRaw(STORAGE_KEYS.server) || ''
}

/** 空地址 = 删除，回退自动推导（defaultWsUrl）。 */
export function storeServerUrl(url: string): void {
  if (url) setRaw(STORAGE_KEYS.server, url)
  else removeRaw(STORAGE_KEYS.server)
}

// ===== 上次视图 =====

export function getLastView(): StoredLastView | null {
  const raw = getRaw(STORAGE_KEYS.lastView)
  if (!raw) return null
  try {
    const v = JSON.parse(raw) as Partial<StoredLastView>
    if (v.mode !== 'agent' && v.mode !== 'chat') return null
    return { mode: v.mode, sessionId: v.sessionId, conversationId: v.conversationId }
  } catch {
    return null
  }
}

export function saveLastView(view: StoredLastView): void {
  try {
    setRaw(STORAGE_KEYS.lastView, JSON.stringify(view))
  } catch {
    /* ignore */
  }
}

export function clearLastView(): void {
  removeRaw(STORAGE_KEYS.lastView)
}

// ===== UI 缩放 =====

/** 读取用户选择的缩放比例；无缓存返回 null（调用方回退默认 110%）。 */
export function getStoredUiScale(): number | null {
  const raw = getRaw(STORAGE_KEYS.uiScale)
  if (raw == null) return null
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function storeUiScale(scale: number): void {
  setRaw(STORAGE_KEYS.uiScale, String(scale))
}

// ===== 提醒音开关 =====

export function getStoredNotifyComplete(): boolean {
  return getRaw(STORAGE_KEYS.notifyComplete) === 'true'
}

export function storeNotifyComplete(enabled: boolean): void {
  setRaw(STORAGE_KEYS.notifyComplete, String(enabled))
}

// ===== 是否已绑定（有任一绑定信息即视为已绑定） =====

export function hasStoredBinding(): boolean {
  return Boolean(getStoredToken() || getStoredServerUrl())
}
