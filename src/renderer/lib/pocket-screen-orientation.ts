/**
 * 屏幕方向应用函数（Capacitor 原生插件封装）
 *
 * 平板端通过 Capacitor 自定义插件 ScreenOrientation.setOrientation 控制屏幕方向，
 * 三档可选：跟随系统旋转（auto）/ 固定横屏（landscape）/ 固定竖屏（portrait）。
 *
 * 持久化策略（重启/更新后锁定不失效的关键）：
 *  - 权威层 = Android 原生 SharedPreferences（ScreenOrientationPlugin 在 setOrientation 时写入，
 *    并在插件 load()/Activity 创建时读取应用）。原生层在 WebView/JS 启动前就已锁定屏幕，
 *    不依赖 localStorage（Capacitor 官方视为瞬态存储，重启/更新/系统回收空间都可能被清空）。
 *  - 前端 localStorage 仅承担 UI 状态展示与兼容旧版本；启动 init 时优先以本地值推送原生，
 *    本地值丢失（被清空/首次升级）时回读原生持久化值回填，保证两端最终一致。
 *
 * 安全降级：非 Capacitor 环境（浏览器联调，window.Capacitor 不存在）或插件调用
 * 失败时静默跳过，仅更新本地状态，不影响页面渲染与设置交互。
 */

import type { Store } from 'jotai/vanilla/store'
import { pocketScreenOrientationAtom, type PocketScreenOrientation } from '@/atoms/pocket-settings'

/** Capacitor 全局对象的类型化描述（仅声明用到的字段，避免强依赖 @capacitor/core） */
type PocketCapacitorGlobal = {
  Capacitor?: {
    Plugins?: {
      ScreenOrientation?: {
        setOrientation?: (opts: { orientation: string }) => Promise<unknown>
        getOrientation?: () => Promise<{ value: { orientation: string; configured?: boolean } }>
      }
    }
  }
}

/**
 * 应用屏幕方向到原生层（设置页切换时立即调用）
 *
 * 非 Capacitor 环境直接返回；原生插件调用异常时静默降级，不影响本地状态。
 * 原生层会同步把方向写入 SharedPreferences 持久化。
 */
export async function applyPocketScreenOrientation(orientation: PocketScreenOrientation): Promise<void> {
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  if (!cap?.Plugins?.ScreenOrientation?.setOrientation) return
  try {
    await cap.Plugins.ScreenOrientation.setOrientation({ orientation })
  } catch {
    // 静默降级：插件未注册/调用失败时保持本地状态即可
  }
}

/** 回读原生 SharedPreferences 持久化的方向（插件不可用时返回未配置） */
async function readNativeOrientation(): Promise<{ orientation: PocketScreenOrientation; configured: boolean }> {
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  const value = cap?.Plugins?.ScreenOrientation?.getOrientation?.()
  if (!value) return { orientation: 'auto', configured: false }
  try {
    const result = await value
    const raw = result?.value?.orientation
    return {
      orientation: raw === 'landscape' || raw === 'portrait' ? raw : 'auto',
      configured: result?.value?.configured === true,
    }
  } catch {
    return { orientation: 'auto', configured: false }
  }
}

/** 读取旧版本仅写入 WebView localStorage 的方向，供首次升级时迁移到原生层 */
function readLegacyLocalOrientation(): PocketScreenOrientation {
  try {
    const raw = localStorage.getItem('profer-pocket-screen-orientation')
    return raw === '"landscape"' || raw === 'landscape'
      ? 'landscape'
      : raw === '"portrait"' || raw === 'portrait'
        ? 'portrait'
        : 'auto'
  } catch {
    return 'auto'
  }
}

/**
 * 初始化屏幕方向（App 启动时调用）
 *
 * 做「本地 localStorage ↔ 原生 SharedPreferences」的一致性合并：
 *  - 原生已有明确记录 → 以原生为准，避免 atomWithStorage 的默认 auto 覆盖旧值
 *  - 原生从未记录 → 迁移旧版本 localStorage 的横/竖屏选择
 * 最终两端一致，并已应用方向（原生层在 Activity 创建时已提前应用过一轮）。
 */
export async function initPocketScreenOrientation(store: Store): Promise<void> {
  const native = await readNativeOrientation()
  const local = native.configured ? 'auto' : readLegacyLocalOrientation()
  const effective = native.configured ? native.orientation : local

  // 显式写回一次，避免 atomWithStorage 的异步 hydration 随后把 UI 状态改回默认 auto。
  store.set(pocketScreenOrientationAtom, effective)
  await applyPocketScreenOrientation(effective)
}
