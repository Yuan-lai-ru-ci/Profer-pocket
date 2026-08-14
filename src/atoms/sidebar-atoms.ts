/**
 * sidebar-atoms.ts — 移动端侧边栏轻量状态 atoms（apps/tablet-client）
 *
 * 职责：承载侧边栏自身的 UI 状态（归档视图切换 / 项目排序 / 搜索对话框开关）。
 * 语义对齐桌面 @/atoms/sidebar-atoms.ts + @/atoms/search-atoms.ts，但独立定义、
 * 不 import 桌面 atoms（移动端无 Electron 环境）。
 *
 * 注意：工作区/会话/渠道/用户等数据态由同目录 session.ts / ui-atoms.ts 承载，
 * 本文件只管理「侧边栏视图自身的偏好」，避免与数据 atoms 职责重叠。
 */

import { atom } from 'jotai'
import { atomWithStorage } from 'jotai/utils'

/** 侧边栏视图模式（active = 活跃会话，archived = 已归档会话） */
export type SidebarViewMode = 'active' | 'archived'

/** 侧边栏视图模式 */
export const sidebarViewModeAtom = atom<SidebarViewMode>('active')

/** 项目列表排序方式 */
export type WorkspaceSortMode = 'default' | 'recent' | 'name'

/** 项目列表排序方式（default = 默认/创建时间，recent = 最近更新，name = 名称） */
export const workspaceSortModeAtom = atomWithStorage<WorkspaceSortMode>(
  'profer-workspace-sort-mode',
  'default',
)

/** 搜索对话框是否打开（全局唯一，Portal 到 body） */
export const searchDialogOpenAtom = atom(false)
