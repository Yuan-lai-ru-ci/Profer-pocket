/**
 * AgentSessionItem.tsx — 会话列表单行项（移动端独立客户端 apps/tablet-client）
 *
 * 纯展示组件：标题 + 置顶图标 + 归档样式 + draft 标记 + 选中高亮。
 * 长按 / 右键 / 更多菜单 触发 actions 回调（由父组件 AgentSessionList 调用 ws 方法）。
 *
 * 视觉/交互语义参考桌面 left-sidebar/session-items.tsx 的 AgentSessionItem，
 * 但移动端大幅简化：无 minimap 预览、无委派子树、无浏览器标记、无项目分组。
 *
 * 依赖说明：
 *  - 项目未引入 lucide-react（package.json 无此依赖），图标一律用内联 SVG。
 *  - 项目未接 tailwind，className 使用可读的 tailwind 风格语义类（详见 AgentSessionList 交付说明）。
 */

import * as React from 'react'
import { Pin, Pencil, MoreHorizontal } from 'lucide-react'
import type { AgentSessionMeta } from '@profer/shared'

/** 单行操作回调集合。全部由父组件注入，本组件不感知 ws-client。 */
export interface AgentSessionItemHandlers {
  /** 点击行：切换当前会话 */
  onSelect: (id: string) => void
  /** 置顶/取消置顶 */
  onTogglePin: (id: string) => void
  /** 归档/取消归档 */
  onToggleArchive: (id: string) => void
  /** 重命名：父组件负责弹出输入（移动端无 hover，更多菜单里确认后回调） */
  onRename: (id: string, title: string) => void
  /** 删除 */
  onDelete: (id: string) => void
}

export interface AgentSessionItemProps {
  session: AgentSessionMeta
  /** 是否为当前选中会话 */
  active: boolean
  /** 是否处于归档区（影响样式淡化） */
  archived: boolean
  handlers: AgentSessionItemHandlers
}

  // ===== 图标由 lucide-react 提供，无内联 SVG =====

  /**
   * 单行会话条目。
   *
   * 交互：
   *  - 单击：onSelect 切换会话
   *  - 长按（触屏）或 onContextMenu（桌面浏览器调试）：打开更多菜单（重命名/置顶/归档/删除）
   *  - 更多菜单内的重命名用 window.prompt 简化（移动端弹层可后续替换为自定义 modal）
   */
export const AgentSessionItem = React.memo(function AgentSessionItem({
  session,
  active,
  archived,
  handlers,
}: AgentSessionItemProps): React.ReactElement {
  const [menuOpen, setMenuOpen] = React.useState(false)
  const longPressTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearLongPress = React.useCallback(() => {
    if (longPressTimer.current) {
      clearTimeout(longPressTimer.current)
      longPressTimer.current = null
    }
  }, [])

  // 长按打开菜单（移动端无 hover + 右键）
  const handlePointerDown = (): void => {
    clearLongPress()
    longPressTimer.current = setTimeout(() => setMenuOpen(true), 500)
  }

  const handleRename = (): void => {
    setMenuOpen(false)
    const next = window.prompt('重命名会话', session.title)
    const trimmed = next?.trim()
    if (trimmed && trimmed !== session.title) {
      handlers.onRename(session.id, trimmed)
    }
  }

  const menuItem = (
    label: string,
    onClick: () => void,
    danger = false,
  ): React.ReactElement => (
    <button
      type="button"
      key={label}
      className={`block w-full rounded-md px-2.5 py-1.5 text-left text-[13px] transition-colors ${
        danger
          ? 'text-destructive hover:bg-destructive/10'
          : 'text-foreground hover:bg-muted'
      }`}
      onClick={onClick}
    >
      {label}
    </button>
  )

  const pinned = !!session.pinned

  return (
    <div className="relative">
      <div
        role="button"
        tabIndex={0}
        aria-current={active ? 'true' : undefined}
        onClick={() => handlers.onSelect(session.id)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            handlers.onSelect(session.id)
          }
        }}
        onPointerDown={handlePointerDown}
        onPointerUp={clearLongPress}
        onPointerLeave={clearLongPress}
        onContextMenu={(e) => {
          e.preventDefault()
          setMenuOpen(true)
        }}
        className={`flex w-full cursor-pointer items-center gap-1.5 rounded-lg px-2.5 py-1.5 pl-3 text-left text-[13px] transition-colors ${
          active ? 'bg-muted text-foreground' : 'text-foreground hover:bg-muted/50'
        } ${archived ? 'text-muted-foreground' : ''}`}
      >
        <span className="flex min-w-0 flex-1 items-center gap-1">
          {pinned && (
            <span className="shrink-0 text-primary" aria-label="已置顶">
              <Pin className="h-3 w-3" />
            </span>
          )}
          <span className="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{session.title || '未命名会话'}</span>
          {!!session.draft && (
            <span className="shrink-0 text-muted-foreground/40" aria-label="草稿（尚未持久化）">
              <Pencil className="h-3 w-3" />
            </span>
          )}
        </span>

        <button
          type="button"
          aria-label="更多操作"
          className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-muted-foreground/50 transition-all hover:bg-muted hover:text-foreground ${
            active ? 'opacity-100' : 'opacity-0'
          }`}
          onClick={(e) => {
            e.stopPropagation()
            setMenuOpen((v) => !v)
          }}
        >
          <MoreHorizontal className="h-4 w-4" />
        </button>
      </div>

      {menuOpen && (
        <>
          {/* 点击遮罩关闭菜单 */}
          <div className="fixed inset-0 z-40" onClick={() => setMenuOpen(false)} />
          <div className="absolute right-2 top-full z-50 flex min-w-[140px] flex-col rounded-lg border border-border bg-popover p-1 shadow-lg" role="menu">
            {menuItem(pinned ? '取消置顶' : '置顶会话', () => {
              setMenuOpen(false)
              handlers.onTogglePin(session.id)
            })}
            {menuItem('重命名', handleRename)}
            {menuItem(archived ? '取消归档' : '归档', () => {
              setMenuOpen(false)
              handlers.onToggleArchive(session.id)
            })}
            {menuItem('删除会话', () => {
              setMenuOpen(false)
              handlers.onDelete(session.id)
            }, true)}
          </div>
        </>
      )}
    </div>
  )
})
