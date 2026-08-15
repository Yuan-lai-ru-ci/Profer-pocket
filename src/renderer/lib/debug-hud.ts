/**
 * Profer Pocket 开发版调试日志 HUD。
 *
 * 能力（dev 版标配）：
 *  - 显式 debugLog(...) 调用 + 自动捕获 console.warn / console.error，喂进屏显浮窗
 *  - 可折叠：胶囊（嵌入输入框 toolbar、发送按钮左侧、水平对齐）↔ 展开面板（悬浮在输入框上方）
 *  - 可复制：navigator.clipboard 优先，execCommand 降级（WebView 兼容）
 *  - 不透明黑底：手机截图 OCR 友好
 *  - 日志最近 MAX_LOG 条，自动滚动
 *
 * 定位机制：胶囊不固定在视口角落（会遮挡输入框发送按钮，导致发不出消息），
 * 而是嵌入 InputToolbarOverflow 的 trailing 容器（与发送按钮同一行、水平对齐）。
 * 展开面板悬浮在输入框正上方，不遮挡输入。composer 随 React 重渲染重建时，
 * MutationObserver 检测到胶囊脱离工具栏后自动重新挂载。
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
let capEl: HTMLButtonElement | null = null
let panelEl: HTMLDivElement | null = null
let listEl: HTMLDivElement | null = null
/** 胶囊当前挂载的容器（toolbar trailing 容器或 body）；用于 MutationObserver 判断是否脱离 */
let capHostEl: HTMLElement | null = null
let followBottom = true
let copyResetTimer: ReturnType<typeof setTimeout> | null = null
let observer: MutationObserver | null = null

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

/**
 * 找到输入框工具栏（InputToolbarOverflow）的 trailing 容器——发送/停止按钮所在行，
 * 把胶囊插到它的最前面（即发送按钮左侧）。找不到时胶囊暂挂 body 并隐藏（避免遮挡），
 * 待 MutationObserver 检测到工具栏出现后再移入。
 */
function mountCap(): void {
  if (!capEl) return
  const toolbar = document.querySelector<HTMLElement>('[data-profer-navigation-region="toolbar"]')
  if (toolbar) {
    // trailing 容器是 toolbar 的最后一个 div 子元素；找不到则直接挂到 toolbar
    let host: HTMLElement | null = null
    const children = Array.from(toolbar.children).filter((el) => el.tagName === 'DIV')
    const last = children[children.length - 1]
    if (last) host = last as HTMLElement
    host = host ?? toolbar
    // 胶囊插到 trailing 容器最前（发送/停止按钮左侧），保持水平对齐。
    // 必须用 insertBefore 而非 appendChild：appendChild 会落到按钮右侧（默认错误位置）。
    if (capEl.parentElement !== host) host.insertBefore(capEl, host.firstChild)
    else if (host.firstChild !== capEl) host.insertBefore(capEl, host.firstChild)
    capEl.style.display = 'inline-flex'
    capHostEl = host
  } else {
    if (capEl.parentElement !== document.body) document.body.appendChild(capEl)
    // toolbar 未渲染（连接页等）：隐藏胶囊，避免浮动遮挡输入区域
    capEl.style.display = 'none'
    capHostEl = null
  }
}

/** 展开面板悬浮在输入框正上方：面板 bottom = 工具栏顶部再上移 8px，不遮挡输入框 */
function positionPanel(): void {
  if (!panelEl || !capEl) return
  const host = capHostEl?.closest('[data-profer-navigation-region="toolbar"]')
    ?? document.querySelector('[data-profer-navigation-region="toolbar"]')
  let top = 0
  if (host) top = host.getBoundingClientRect().top
  panelEl.style.position = 'fixed'
  panelEl.style.left = '50%'
  panelEl.style.transform = 'translateX(-50%)'
  panelEl.style.bottom = `${Math.max(8, window.innerHeight - top + 8)}px`
  // 面板高度上限：视口高度减去输入框区域高度与顶部间距，避免超出屏幕
  panelEl.style.maxHeight = `calc(100dvh - ${Math.max(8, window.innerHeight - top) + 16}px)`
}

function ensureDom(): void {
  if (panelEl) return
  const style = document.createElement('style')
  style.textContent = `#pocket-debug-hud-cap{display:inline-flex;align-items:center;gap:3px;height:36px;padding:0 11px;border-radius:999px;background:rgba(0,0,0,.85);color:#4ade80;border:1px solid #374151;font-size:11px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;cursor:pointer;-webkit-tap-highlight-color:transparent;touch-action:manipulation;user-select:none;line-height:1;white-space:nowrap;flex-shrink:0}
#pocket-debug-hud-cap:active{background:rgba(0,0,0,1)}
#pocket-debug-hud-panel{position:fixed;display:flex;flex-direction:column;width:min(94vw,420px);background:#000;color:#e5e7eb;border:1px solid #374151;border-radius:10px;overflow:hidden;box-shadow:0 8px 24px rgba(0,0,0,.6);z-index:99999;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;line-height:1.5;color-scheme:dark}
#pocket-debug-hud-panel *{box-sizing:border-box}
#pocket-debug-hud-panel .hud-header{display:flex;align-items:center;gap:6px;padding:8px 10px;background:#111827;border-bottom:1px solid #374151}
#pocket-debug-hud-panel .hud-header span{flex:1;font-weight:600;color:#9ca3af;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#pocket-debug-hud-panel .hud-header button{padding:3px 10px;font-size:12px;color:#d1d5db;background:#1f2937;border:1px solid #374151;border-radius:6px;cursor:pointer;touch-action:manipulation}
#pocket-debug-hud-panel .hud-header button:active{background:#374151}
#pocket-debug-hud-panel .hud-list{flex:1;overflow-y:auto;padding:6px 10px;background:#000;-webkit-overflow-scrolling:touch;overscroll-behavior:contain}
#pocket-debug-hud-panel .hud-line{white-space:pre-wrap;word-break:break-all;color:#d1d5db;padding:1px 0}
#pocket-debug-hud-panel .hud-warn{color:#fbbf24}
#pocket-debug-hud-panel .hud-error{color:#f87171}`
  document.head.appendChild(style)

  capEl = document.createElement('button')
  capEl.id = 'pocket-debug-hud-cap'
  capEl.type = 'button'
  capEl.setAttribute('aria-label', '打开调试日志')
  capEl.textContent = '🐞 0'
  capEl.addEventListener('click', () => setExpanded(true))

  panelEl = document.createElement('div')
  panelEl.id = 'pocket-debug-hud-panel'
  panelEl.style.display = 'none'

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

  panelEl.append(header, list)
  document.body.appendChild(panelEl)
  listEl = list
  mountCap()
  render()
  updateBadge()
}

function render(): void {
  if (!listEl) return
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
  if (capEl) capEl.textContent = `🐞 ${logs.length}`
}

function setExpanded(v: boolean): void {
  if (!panelEl) return
  if (v) {
    positionPanel()
    panelEl.style.display = 'flex'
    if (listEl) {
      followBottom = true
      listEl.scrollTop = listEl.scrollHeight
    }
  } else {
    panelEl.style.display = 'none'
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
  const btn = panelEl?.querySelector<HTMLButtonElement>('.hud-header button')
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

/**
 * 监听 body 的子树变化：composer（含 toolbar）随 React 重渲染可能整体重建，
 * 导致胶囊脱离工具栏；检测到后重新挂载回发送按钮左侧。
 */
function startObserver(): void {
  if (observer) return
  let raf = 0
  observer = new MutationObserver(() => {
    cancelAnimationFrame(raf)
    raf = requestAnimationFrame(() => {
      if (!capEl) return
      // 胶囊脱离目标容器、宿主被重建/断开、或不在容器最前（被 React 挪到按钮右侧）时重新挂载
      const hostConnected = capHostEl ? capHostEl.isConnected : false
      if (capEl.parentElement !== capHostEl || !hostConnected || (capHostEl && capEl !== capHostEl.firstChild)) mountCap()
    })
  })
  observer.observe(document.body, { childList: true, subtree: true })
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
  if (typeof document === 'undefined') return
  if (!isHudEnabled()) return
  ensureDom()
  installConsoleCapture()
  startObserver()
  // 视口尺寸变化（横竖屏切换/键盘弹出）时重算面板位置
  window.addEventListener('resize', () => { if (panelEl && panelEl.style.display !== 'none') positionPanel() })
}
