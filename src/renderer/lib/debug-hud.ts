/**
 * Profer Pocket 开发版调试日志 HUD。
 *
 * 能力（dev 版标配）：
 *  - 显式 debugLog(...) 调用 + 自动捕获 console.warn / console.error，喂进屏显浮窗
 *  - 可折叠：小胶囊（右下角悬浮）↔ 展开面板
 *  - 可复制：navigator.clipboard 优先，execCommand 降级（WebView 兼容）
 *  - 不透明黑底：手机截图 OCR 友好
 *  - 日志最近 MAX_LOG 条，自动滚动
 *
 * 启用条件：import.meta.env.DEV（本地 vite 联调）或 window.__POCKET_BUILD__ === 'dev'
 * （dev 变体 APK，由 build-apk.mjs 在 sync-web 后向 web/index.html 注入标记）。
 * release 变体不注入标记 → init 为空操作，HUD 完全关闭。
 *
 * 用法（pocket/main.tsx 挂载时）：
 *   import { initDebugHud, debugLog } from '@/lib/debug-hud'
 *   initDebugHud()
 *   debugLog('连接事件:', code)
 */

/** HUD 最多保留的日志条数（超出丢弃最旧） */
const MAX_LOG = 20

type HudLevel = 'log' | 'warn' | 'error'

type HudLogEntry = {
  time: string
  level: HudLevel
  text: string
}

const logs: HudLogEntry[] = []
let rootEl: HTMLDivElement | null = null
let listEl: HTMLDivElement | null = null
let initialized = false
let followBottom = true
let copyResetTimer: ReturnType<typeof setTimeout> | null = null

/** 是否启用 HUD：本地 vite 联调（import.meta.env.DEV）或 dev 变体 APK（__POCKET_BUILD__） */
function isHudEnabled(): boolean {
  if (import.meta.env.DEV) return true
  if (typeof window !== 'undefined') {
    const buildTag = (window as unknown as { __POCKET_BUILD__?: string }).__POCKET_BUILD__
    return buildTag === 'dev'
  }
  return false
}

function nowTime(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 把任意参数格式化为可读文本（对象 JSON 序列化，Error 取 stack，失败兜底 String） */
function fmtArg(v: unknown): string {
  if (typeof v === 'string') return v
  if (v instanceof Error) return v.stack ?? v.message
  if (typeof v === 'undefined') return 'undefined'
  if (v === null) return 'null'
  try {
    const s = JSON.stringify(v)
    return s === undefined ? String(v) : s
  } catch {
    return String(v)
  }
}

function pushLog(level: HudLevel, args: unknown[]): void {
  logs.push({ time: nowTime(), level, text: args.map(fmtArg).join(' ') })
  if (logs.length > MAX_LOG) logs.splice(0, logs.length - MAX_LOG)
  render()
  updateBadge()
}

/** 显式打一条日志（开发期在关键路径调用） */
export function debugLog(...args: unknown[]): void {
  if (!isHudEnabled()) return
  pushLog('log', args)
}

function ensureDom(): void {
  if (rootEl) return
  const style = document.createElement('style')
  style.textContent = `#pocket-debug-hud{position:fixed;right:14px;bottom:14px;z-index:99999;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;line-height:1.5;color-scheme:dark}
#pocket-debug-hud *{box-sizing:border-box}
#pocket-debug-hud-cap{display:flex;align-items:center;gap:6px;padding:8px 14px;background:rgba(0,0,0,.92);color:#4ade80;border:1px solid #374151;border-radius:999px;cursor:pointer;font-size:12px;font-family:inherit;-webkit-tap-highlight-color:transparent;touch-action:manipulation;user-select:none}
#pocket-debug-hud-panel{display:flex;flex-direction:column;width:min(92vw,420px);max-height:60vh;background:#000;color:#e5e7eb;border:1px solid #374151;border-radius:10px;overflow:hidden;box-shadow:0 8px 24px rgba(0,0,0,.6)}
#pocket-debug-hud .hud-header{display:flex;align-items:center;gap:6px;padding:8px 10px;background:#111827;border-bottom:1px solid #374151}
#pocket-debug-hud .hud-header span{flex:1;font-weight:600;color:#9ca3af;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#pocket-debug-hud .hud-header button{padding:3px 10px;font-size:12px;color:#d1d5db;background:#1f2937;border:1px solid #374151;border-radius:6px;cursor:pointer;touch-action:manipulation}
#pocket-debug-hud .hud-header button:active{background:#374151}
#pocket-debug-hud .hud-list{flex:1;overflow-y:auto;padding:6px 10px;background:#000;-webkit-overflow-scrolling:touch;overscroll-behavior:contain}
#pocket-debug-hud .hud-line{white-space:pre-wrap;word-break:break-all;color:#d1d5db;padding:1px 0}
#pocket-debug-hud .hud-warn{color:#fbbf24}
#pocket-debug-hud .hud-error{color:#f87171}`
  document.head.appendChild(style)

  rootEl = document.createElement('div')
  rootEl.id = 'pocket-debug-hud'

  const cap = document.createElement('button')
  cap.id = 'pocket-debug-hud-cap'
  cap.type = 'button'
  cap.setAttribute('aria-label', '打开调试日志')
  cap.textContent = '🐞 0'
  cap.addEventListener('click', () => setExpanded(true))

  const panel = document.createElement('div')
  panel.id = 'pocket-debug-hud-panel'
  panel.style.display = 'none'

  const header = document.createElement('div')
  header.className = 'hud-header'
  const title = document.createElement('span')
  title.textContent = '调试日志'
  const copyBtn = document.createElement('button')
  copyBtn.type = 'button'
  copyBtn.textContent = '复制'
  copyBtn.addEventListener('click', copyLogs)
  const clearBtn = document.createElement('button')
  clearBtn.type = 'button'
  clearBtn.textContent = '清空'
  clearBtn.addEventListener('click', clearLogs)
  const collapseBtn = document.createElement('button')
  collapseBtn.type = 'button'
  collapseBtn.textContent = '收起'
  collapseBtn.addEventListener('click', () => setExpanded(false))
  header.append(title, copyBtn, clearBtn, collapseBtn)

  const list = document.createElement('div')
  list.className = 'hud-list'
  // 用户手动上滑查看历史时暂停自动滚动；滚到底部后恢复跟随
  list.addEventListener('scroll', () => {
    followBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 24
  })

  panel.append(header, list)
  rootEl.append(cap, panel)
  document.body.appendChild(rootEl)
  listEl = list
  render()
  updateBadge()
}

function render(): void {
  if (!listEl || !initialized) return
  listEl.textContent = ''
  for (const e of logs) {
    const line = document.createElement('div')
    line.className = `hud-line hud-${e.level}`
    line.textContent = `${e.time} [${e.level}] ${e.text}`
    listEl.appendChild(line)
  }
  if (followBottom) listEl.scrollTop = listEl.scrollHeight
}

function updateBadge(): void {
  if (!rootEl) return
  const cap = rootEl.querySelector<HTMLButtonElement>('#pocket-debug-hud-cap')
  if (cap) cap.textContent = `🐞 ${logs.length}`
}

function setExpanded(v: boolean): void {
  if (!rootEl) return
  const cap = rootEl.querySelector<HTMLButtonElement>('#pocket-debug-hud-cap')
  const panel = rootEl.querySelector<HTMLDivElement>('#pocket-debug-hud-panel')
  if (cap) cap.style.display = v ? 'none' : 'flex'
  if (panel) panel.style.display = v ? 'flex' : 'none'
  if (v && listEl) {
    followBottom = true
    listEl.scrollTop = listEl.scrollHeight
  }
}

function copyLogs(): void {
  const text = logs.map((e) => `${e.time} [${e.level}] ${e.text}`).join('\n')
  if (!text) return
  const done = () => flashCopyState()
  // WebView 里 navigator.clipboard 可能受权限限制失败；execCommand 兜底
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(() => { legacyCopy(text); done() })
  } else {
    legacyCopy(text)
    done()
  }
}

/** execCommand('copy') 降级路径（旧 WebView 无 async clipboard API） */
function legacyCopy(text: string): void {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none;'
  document.body.appendChild(ta)
  ta.focus()
  ta.select()
  try {
    document.execCommand('copy')
  } catch {
    /* 复制失败静默：截图 / 手动复制兜底 */
  }
  document.body.removeChild(ta)
}

/** 复制按钮短暂显示「已复制」反馈 */
function flashCopyState(): void {
  const btn = rootEl?.querySelector<HTMLButtonElement>('#pocket-debug-hud-panel .hud-header button')
  if (!btn) return
  if (copyResetTimer) clearTimeout(copyResetTimer)
  const original = btn.textContent
  btn.textContent = '已复制'
  copyResetTimer = setTimeout(() => {
    btn.textContent = original
    copyResetTimer = null
  }, 1200)
}

function clearLogs(): void {
  logs.length = 0
  render()
  updateBadge()
}

let consolePatched = false
/** 自动捕获 console.warn / console.error：保留原始输出行为，同时喂进 HUD */
function installConsoleCapture(): void {
  if (consolePatched) return
  consolePatched = true
  const origWarn = console.warn.bind(console)
  const origError = console.error.bind(console)
  console.warn = (...args: unknown[]) => { origWarn(...args); pushLog('warn', args) }
  console.error = (...args: unknown[]) => { origError(...args); pushLog('error', args) }
}

/**
 * 初始化调试日志 HUD。幂等：仅首次调用生效。
 * 非 dev 环境（release APK / import.meta.env.PROD）为空操作。
 */
export function initDebugHud(): void {
  if (initialized) return
  initialized = true
  if (typeof document === 'undefined') return
  if (!isHudEnabled()) return
  ensureDom()
  installConsoleCapture()
}
