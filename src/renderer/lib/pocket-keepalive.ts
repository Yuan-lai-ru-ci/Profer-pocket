/**
 * 后台消息通道 — 原生插件封装（Capacitor PocketMessenger）
 *
 * 封装 window.Capacitor.Plugins.PocketMessenger.*（原生前台服务 + 原生 WS + 系统通知）：
 *  - requestPocketNotificationPermission：请求通知权限（Android 13+ 弹系统权限框，13 以下直接 granted）
 *  - startPocketKeepalive / stopPocketKeepalive：启动/停止原生前台服务（同参数重复调用幂等）
 *  - setPocketKeepaliveForeground：同步前后台状态（前台抑制原生通知，去重关键）
 *  - getPocketPendingNotification：读取点击系统通知的导航信息（读取即清空）
 *
 * 安全降级：非 Capacitor 环境（浏览器联调，window.Capacitor 不存在）所有函数安全 no-op，
 * 不抛错、不影响页面渲染与设置交互。对齐 pocket-screen-orientation.ts 的封装风格。
 */

/** Capacitor 全局对象的类型化描述（仅声明用到的字段，避免强依赖 @capacitor/core） */
type PocketCapacitorGlobal = {
  Capacitor?: {
    isNativePlatform?: () => boolean
    Plugins?: {
      PocketMessenger?: {
        startService?: (opts: { url: string; token: string }) => Promise<unknown>
        stopService?: () => Promise<unknown>
        setForegroundState?: (opts: { foreground: boolean }) => Promise<unknown>
        getPendingNotification?: () => Promise<{ sessionId?: string; type?: string }>
        requestPermissions?: () => Promise<{ granted: boolean }>
        getStatus?: () => Promise<{ diagnostic?: string }>
      }
    }
  }
}

/** 是否原生 Capacitor App 环境（浏览器联调时为 false） */
function isNativeCapacitor(): boolean {
  return typeof window !== 'undefined' && !!((window as unknown as PocketCapacitorGlobal).Capacitor?.isNativePlatform?.())
}

/**
 * 规范化服务器地址为 WS URL（与 main.tsx 原有实现一致，供设置页/保活联动复用）。支持输入形式：
 *  - http://192.168.1.10:7788 / https://host:port  → ws(s)://host:port/ws
 *  - ws://192.168.1.10:7788 / ws://192.168.1.10:7788/ws → 补 /ws 或原样
 *  - 192.168.1.10:7788（无协议）→ ws://192.168.1.10:7788/ws
 *  - 空字符串 → null（调用方回退 defaultWsUrl 自动推导）
 */
export function normalizeWsUrl(raw: string): string | null {
  const s = raw.trim().replace(/\/+$/, '')
  if (!s) return null
  if (/^https?:\/\//i.test(s)) {
    const proto = /^https:/i.test(s) ? 'wss:' : 'ws:'
    return `${proto}${s.replace(/^https?:/i, '')}/ws`
  }
  if (/^wss?:\/\//i.test(s)) {
    return s.endsWith('/ws') ? s : `${s}/ws`
  }
  return `ws://${s}/ws`
}

/** 请求通知权限；返回 'granted' | 'denied'（denied 时调用方仍可启动服务退化保活） */
export async function requestPocketNotificationPermission(): Promise<'granted' | 'denied'> {
  if (!isNativeCapacitor()) return 'denied'
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  try {
    const res = await cap?.Plugins?.PocketMessenger?.requestPermissions?.()
    return res?.granted ? 'granted' : 'denied'
  } catch {
    return 'denied'
  }
}

/** 启动后台消息保活（原生前台服务 + WS 消息通道；重复调用同参数幂等） */
export async function startPocketKeepalive(url: string, token: string): Promise<void> {
  if (!isNativeCapacitor()) return
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  try {
    await cap?.Plugins?.PocketMessenger?.startService?.({ url, token })
  } catch (e) {
    console.warn('[Pocket Keepalive] 启动后台服务失败', e)
  }
}

/** 停止后台消息保活（解绑 / 关闭开关 / token 失效时调用） */
export async function stopPocketKeepalive(): Promise<void> {
  if (!isNativeCapacitor()) return
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  try {
    await cap?.Plugins?.PocketMessenger?.stopService?.()
  } catch (e) {
    console.warn('[Pocket Keepalive] 停止后台服务失败', e)
  }
}

/** 同步前后台状态：true=前台（抑制原生通知，去重关键），false=后台 */
export async function setPocketKeepaliveForeground(foreground: boolean): Promise<void> {
  if (!isNativeCapacitor()) return
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  try {
    await cap?.Plugins?.PocketMessenger?.setForegroundState?.({ foreground })
  } catch (e) {
    console.warn('[Pocket Keepalive] 同步前后台状态失败', e)
  }
}

/** 读取点击系统通知的导航信息（读取即清空）；无待导航通知则返回 null */
export async function getPocketPendingNotification(): Promise<{ sessionId?: string; type?: string } | null> {
  if (!isNativeCapacitor()) return null
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  try {
    const res = await cap?.Plugins?.PocketMessenger?.getPendingNotification?.()
    return res?.sessionId ? res : null
  } catch (e) {
    console.warn('[Pocket Keepalive] 读取待导航通知失败', e)
    return null
  }
}

/** 读取后台通道诊断信息（排查用）：返回状态字符串或 null（非原生/调用失败） */
export async function getPocketKeepaliveStatus(): Promise<string | null> {
  if (!isNativeCapacitor()) return null
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  try {
    const res = await cap?.Plugins?.PocketMessenger?.getStatus?.()
    return typeof res?.diagnostic === 'string' ? res.diagnostic : null
  } catch (e) {
    console.warn('[Pocket Keepalive] 读取诊断失败', e)
    return null
  }
}
