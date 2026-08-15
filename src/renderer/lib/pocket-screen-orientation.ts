/**
 * 屏幕方向应用函数（Capacitor 原生插件封装）
 *
 * 平板端通过 Capacitor 自定义插件 ScreenOrientation.setOrientation 控制屏幕方向，
 * 三档可选：跟随系统旋转（auto）/ 固定横屏（landscape）/ 固定竖屏（portrait）。
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
      }
    }
  }
}

/**
 * 应用屏幕方向到原生层（设置页切换时立即调用）
 *
 * 非 Capacitor 环境直接返回；原生插件调用异常时静默降级，不影响本地状态。
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

/**
 * 初始化屏幕方向（App 启动时调用）
 *
 * 读取持久化的初始值并应用到原生层，实现「重启保持」。
 */
export function initPocketScreenOrientation(store: Store): void {
  const initial = store.get(pocketScreenOrientationAtom)
  void applyPocketScreenOrientation(initial)
}
