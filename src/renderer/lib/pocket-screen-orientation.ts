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
        getOrientation?: () => Promise<{ value: { orientation: string } }>
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

/** 回读原生 SharedPreferences 持久化的方向（插件不可用时返回 'auto'） */
async function readNativeOrientation(): Promise<PocketScreenOrientation> {
  const cap = (window as unknown as PocketCapacitorGlobal).Capacitor
  const value = cap?.Plugins?.ScreenOrientation?.getOrientation?.().then((r) => r?.value?.orientation)
  if (!value) return 'auto'
  try {
    const v = await value
    return v === 'landscape' || v === 'portrait' ? v : 'auto'
  } catch {
    return 'auto'
  }
}

/**
 * 初始化屏幕方向（App 启动时调用）
 *
 * 做「本地 localStorage ↔ 原生 SharedPreferences」的一致性合并：
 *  - 本地有显式选择（非 auto）→ 以本地为准推送原生（原生持久化，兼更新原生里的旧值）
 *  - 本地丢失（localStorage 被清 / 首次升级到持久化版本）→ 以原生持久化值为准回填本地
 * 最终两端一致，并已应用方向（原生层在 Activity 创建时已提前应用过一轮）。
 */
export async function initPocketScreenOrientation(store: Store): Promise<void> {
  const local = store.get(pocketScreenOrientationAtom)
  const native = await readNativeOrientation()

  // 本地有显式选择则优先；否则以原生为准（localStorage 丢失/首次升级场景）
  const effective: PocketScreenOrientation = local !== 'auto' ? local : native
  if (effective !== local) {
    store.set(pocketScreenOrientationAtom, effective)
  }
  await applyPocketScreenOrientation(effective)
}
