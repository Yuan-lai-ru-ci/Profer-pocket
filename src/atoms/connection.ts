/**
 * 连接状态 Atoms — 移动端独立客户端（apps/tablet-client）
 *
 * 职责：管理与电脑端 remote-service 的 WebSocket 连接状态、绑定信息与断线提示。
 * 语义完全对齐桌面 tablet 版的 setConnection 本地 state + tabletConnectionStatusAtom，
 * 但独立定义，不 import 桌面 atoms。
 *
 * 与桌面关键差异（移动端瘦客户端）：
 *  - 状态机枚举保持一致（idle/connecting/open/reconnecting/error/unauthorized），
 *    使 UI 层的“登录页 / 断线横幅 / 主界面”三种呈现分支可直接沿用既有逻辑。
 *  - 绑定信息（token / server）不再拆散为本地 state，而是用 atomWithStorage 持久化，
 *    便于多组件（连接页输入框、设置页、顶栏状态点）共享同一数据源。
 */

import { atom } from 'jotai'
import { atomWithStorage } from 'jotai/utils'
import { STORAGE_KEYS } from '@/lib/storage'

/** 连接状态机（与桌面 tabletConnectionStatusAtom 枚举一致） */
export type ConnectionStatus =
  | 'idle' // 未绑定 / 已解绑
  | 'connecting' // 正在建立 WS 连接
  | 'open' // 已连接
  | 'reconnecting' // 已绑定但断线，正在自动重连
  | 'error' // 连接失败
  | 'unauthorized' // token 被服务端拒绝（4001），需重新输入

/**
 * 当前 WS 连接状态。
 * 由 useConnection 通过 WsClient 的 onStatusChange 回调驱动写入；
 * UI 层据此切换“登录页 / 断线横幅 / 主界面”并同步设置页状态徽标。
 */
export const connectionStatusAtom = atom<ConnectionStatus>('idle')

/**
 * 断线提示文案（可选）。
 * 当状态为 error / reconnecting / unauthorized 时由 useConnection 写入，
 * 供断线横幅 / 登录页错误信息直接展示；open 时置空。
 */
export const connectionNoticeAtom = atom<string | undefined>(undefined)

/**
 * 访问令牌（持久化到 localStorage）。
 * 首次连接页提交时写入；解绑时清空。
 */
export const tokenAtom = atomWithStorage<string>(STORAGE_KEYS.token, '')

/**
 * 显式服务器地址（持久化）。留空 = 自动推导（defaultWsUrl）。
 * App 场景（Capacitor WebView 的 location.host 是 localhost）必须显式配置。
 */
export const serverUrlAtom = atomWithStorage<string>(STORAGE_KEYS.server, '')

/** 是否已绑定（有任一绑定信息即视为已绑定）；派生只读。 */
export const hasBindingAtom = atom<boolean>((get) => {
  return Boolean(get(tokenAtom) || get(serverUrlAtom))
})
