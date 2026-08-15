/**
 * Pocket 设置状态原子（仅平板运行时使用）
 *
 * 桌面设置页与平板主界面之间通过 pocketStore（createStore）共享状态：
 *  - pocketConnectionStatusAtom：连接状态（App 内 setConnection 同步写入，供「连接」tab 展示）
 *  - pocketUnbindRequestAtom：解绑请求计数（设置页点击解绑后 +1，App 监听执行 unbind）
 *  - pocketNotifyCompleteAtom：Agent 完成提醒音开关（localStorage 持久化，Web Audio 播放）
 *  - pocketScreenOrientationAtom：屏幕方向（localStorage 持久化，Capacitor 原生插件应用）
 *  - pocketBackgroundMessagingAtom：后台消息通知开关（localStorage 持久化，Capacitor 原生服务保活）
 */

import { atom } from 'jotai'
import { atomWithStorage } from 'jotai/utils'

export type PocketConnectionStatus =
  | 'idle'      // 未绑定 / 已解绑
  | 'connecting'
  | 'open'      // 已连接
  | 'reconnecting'
  | 'error'
  | 'unauthorized'

export const pocketConnectionStatusAtom = atom<PocketConnectionStatus>('idle')

/** 每次解绑请求计数 +1；App 侧 useEffect 监听变化执行 unbind */
export const pocketUnbindRequestAtom = atom(0)

/** Agent 回合完成提醒音开关（默认关） */
export const pocketNotifyCompleteAtom = atomWithStorage<boolean>('profer-remote-notify-complete', false)

/** 屏幕方向：跟随系统旋转（auto）/ 固定横屏（landscape）/ 固定竖屏（portrait） */
export type PocketScreenOrientation = 'auto' | 'landscape' | 'portrait'

/** 屏幕方向（默认跟随系统旋转；localStorage 持久化，重启保持） */
export const pocketScreenOrientationAtom = atomWithStorage<PocketScreenOrientation>('profer-pocket-screen-orientation', 'auto')

/** 后台消息通知开关：App 进入后台/熄屏后保持与电脑的消息连接，重要事件走系统通知（默认关；localStorage 持久化，重启自动恢复） */
export const pocketBackgroundMessagingAtom = atomWithStorage<boolean>('profer-pocket-background-messaging', false)
