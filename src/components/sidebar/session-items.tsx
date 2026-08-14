/**
 * session-items.tsx — 移动端侧边栏列表项子组件（apps/tablet-client）
 *
 * 1:1 复现桌面 left-sidebar/session-items.tsx 的核心视觉 token：
 * - SessionItemActions：相对时间 + hover 按钮组（置顶 / 归档 / 三点菜单）
 * - AgentSessionItem：会话行（Pin / Clock / GitBranch 图标、草稿标记、工作区徽章、委派子树摘要、左 accent 条）
 * - AgentProjectGroupItem：项目分组（折叠/展开、项目切换、新建会话、项目菜单）
 *
 * 移动端瘦客户端差异（相对桌面）：
 * - 移除 SessionMiniMapPopover / 浏览器标记 / 拖拽排序（Electron 专属 / 无意义），
 *   用首字母圆头像 + 简化交互替代。
 * - 移除 interfaceVariantAtom / browserStateMapAtom 等桌面 atoms，改用纯组件状态。
 * - 右键菜单（ContextMenu）保留用于桌面浏览器调试，长按（touch）走 DropdownMenu 三点菜单。
 */

import * as React from 'react'
import {
  Pin, PinOff, Pencil, Trash2, MoreHorizontal, Clock, GitBranch,
  ChevronRight, FolderOpen, Cloud, Plus, Archive, ArchiveRestore,
  ArrowRightLeft,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip'
import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
} from '@/components/ui/context-menu'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu'
import type { AgentSessionMeta, AgentWorkspace } from '@profer/shared'
import { formatRelativeUpdatedAt } from './sidebar-utils'
import {
  type SessionIndicatorStatus,
  type AgentSessionTreeItem,
  getSessionTreeStatus,
  treeContainsSessionId,
  countCompletedDelegatedChildren,
} from './session-tree'

const PROJECT_SESSION_PREVIEW_LIMIT = 5
const PROJECT_TITLE_DOUBLE_CLICK_DELAY_MS = 500
const PROJECT_SESSION_RECENT_WINDOW_MS = 3 * 86_400_000
const PROJECT_SESSION_EXPAND_STEP = 10

const DELEGATION_STATUS_ICON_CLASS: Record<SessionIndicatorStatus, string> = {
  idle: 'text-foreground/40',
  running: 'text-amber-500',
  blocked: 'text-red-500',
  completed: 'text-emerald-500',
}

/** 委派子会话计数摘要 */
export interface DelegationSummary {
  total: number
  completed: number
  expanded: boolean
  onToggle: () => void
}

/** 项目分组（对齐桌面 AgentProjectGroup） */
export interface AgentProjectGroup {
  workspace: AgentWorkspace
  /** 组内会话树（父会话 + 委派子会话层级；由 buildAgentSessionTrees 构建，对齐桌面） */
  treeItems: AgentSessionTreeItem[]
}

/** 会话操作按钮组（相对时间 + hover 置顶/归档/三点） */
interface SessionItemActionsProps {
  updatedAt: number
  relativeTimeNow: number
  pinned: boolean
  archived: boolean
  onTogglePin: () => void
  onToggleArchive: () => void
  menuItems: (
    MenuItem: typeof ContextMenuItem | typeof DropdownMenuItem,
    MenuSeparator: typeof ContextMenuSeparator | typeof DropdownMenuSeparator,
  ) => React.ReactElement
  onMenuOpenChange?: (open: boolean) => void
}

function SessionItemActions({
  updatedAt,
  relativeTimeNow,
  pinned,
  archived,
  onTogglePin,
  onToggleArchive,
  menuItems,
  onMenuOpenChange,
}: SessionItemActionsProps): React.ReactElement {
  const [archiveConfirming, setArchiveConfirming] = React.useState(false)
  const [menuOpen, setMenuOpen] = React.useState(false)

  React.useEffect(() => {
    if (!archiveConfirming) return
    const timer = setTimeout(() => setArchiveConfirming(false), 3000)
    return () => clearTimeout(timer)
  }, [archiveConfirming])

  const handleArchiveClick = (): void => {
    if (archived) {
      onToggleArchive()
      return
    }
    if (archiveConfirming) {
      setArchiveConfirming(false)
      onToggleArchive()
      return
    }
    setArchiveConfirming(true)
  }

  const closeTimerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)

  const handleMenuOpenChange = (open: boolean): void => {
    if (open) {
      if (closeTimerRef.current !== null) {
        clearTimeout(closeTimerRef.current)
        closeTimerRef.current = null
      }
      setMenuOpen(true)
    } else {
      closeTimerRef.current = setTimeout(() => {
        closeTimerRef.current = null
        setMenuOpen(false)
      }, 200)
    }
    onMenuOpenChange?.(open)
  }

  React.useEffect(() => {
    return () => {
      if (closeTimerRef.current !== null) clearTimeout(closeTimerRef.current)
    }
  }, [])

  const forceVisible = archiveConfirming || menuOpen

  return (
    <div className="relative flex-shrink-0 h-[18px] w-[58px]" onClick={(e) => e.stopPropagation()}>
      <span
        title={`最后更新：${new Date(updatedAt).toLocaleString('zh-CN')}`}
        className={cn(
          'absolute inset-y-0 right-0 block w-full text-right text-[11px] leading-[18px] tabular-nums text-foreground/35 transition-opacity duration-100',
          forceVisible ? 'opacity-0' : 'opacity-100 group-hover:opacity-0',
        )}
      >
        {formatRelativeUpdatedAt(updatedAt, relativeTimeNow)}
      </span>
      <div
        className={cn(
          'absolute right-0 top-0 flex items-center gap-0.5 transition-opacity duration-100',
          forceVisible ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none group-hover:opacity-100 group-hover:pointer-events-auto',
        )}
      >
        <button
          className={cn(
            'p-0.5 rounded transition-colors',
            pinned ? 'text-primary/60 hover:bg-foreground/[0.08] hover:text-primary' : 'text-foreground/30 hover:bg-foreground/[0.08] hover:text-foreground/60',
          )}
          onClick={onTogglePin}
          aria-label={pinned ? '取消置顶' : '置顶'}
        >
          {pinned ? <PinOff size={14} /> : <Pin size={14} />}
        </button>
        <button
          className={cn(
            'p-0.5 rounded transition-colors',
            archiveConfirming
              ? 'text-destructive bg-destructive/10'
              : archived
                ? 'text-foreground/60 hover:bg-foreground/[0.08]'
                : 'text-foreground/30 hover:bg-foreground/[0.08] hover:text-foreground/60',
          )}
          onClick={handleArchiveClick}
          aria-label={archiveConfirming ? '再次点击确认归档' : archived ? '取消归档' : '归档'}
        >
          {archived ? <ArchiveRestore size={14} /> : <Archive size={14} />}
        </button>
        <DropdownMenu onOpenChange={handleMenuOpenChange}>
          <DropdownMenuTrigger asChild>
            <button
              className={cn(
                'p-0.5 rounded text-foreground/30 hover:bg-foreground/[0.08] hover:text-foreground/60 transition-colors',
                'data-[state=open]:bg-foreground/[0.08] data-[state=open]:text-foreground/60',
              )}
              aria-label="更多操作"
            >
              <MoreHorizontal size={14} />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-40 z-[9999] min-w-0 p-0.5">
            {menuItems(DropdownMenuItem, DropdownMenuSeparator)}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}

// ===== Agent 会话行 =====

interface AgentSessionItemProps {
  session: AgentSessionMeta
  active: boolean
  indicatorStatus: SessionIndicatorStatus
  showPinIcon: boolean
  hasDraft?: boolean
  delegationSummary?: DelegationSummary
  leftAccent?: string
  workspaceName?: string
  relativeTimeNow: number
  onSelect: (id: string, title: string) => void
  onRequestDelete: (id: string) => void
  onRequestMove?: (id: string) => void
  onRename: (id: string, newTitle: string) => Promise<void>
  onTogglePin: (id: string) => Promise<void>
  onToggleArchive: (id: string) => Promise<void>
}

export const AgentSessionItem = React.memo(function AgentSessionItem({
  session,
  active,
  indicatorStatus,
  showPinIcon,
  hasDraft,
  delegationSummary,
  leftAccent,
  workspaceName,
  relativeTimeNow,
  onSelect,
  onRequestDelete,
  onRequestMove,
  onRename,
  onTogglePin,
  onToggleArchive,
}: AgentSessionItemProps): React.ReactElement {
  const [editing, setEditing] = React.useState(false)
  const [editTitle, setEditTitle] = React.useState('')
  const inputRef = React.useRef<HTMLInputElement>(null)
  const justStartedEditing = React.useRef(false)

  const startEdit = (): void => {
    setEditTitle(session.title)
    setEditing(true)
    justStartedEditing.current = true
    setTimeout(() => {
      justStartedEditing.current = false
      inputRef.current?.focus()
      inputRef.current?.select()
    }, 300)
  }

  const saveTitle = async (): Promise<void> => {
    if (justStartedEditing.current) return
    const trimmed = editTitle.trim()
    if (!trimmed || trimmed === session.title) {
      setEditing(false)
      return
    }
    await onRename(session.id, trimmed)
    setEditing(false)
  }

  const handleKeyDown = (e: React.KeyboardEvent): void => {
    if (e.key === 'Enter') {
      e.preventDefault()
      void saveTitle()
    } else if (e.key === 'Escape') {
      setEditing(false)
    }
  }

  const canMove = indicatorStatus === 'idle' || indicatorStatus === 'completed'

  const menuItems = (
    MenuItem: typeof ContextMenuItem | typeof DropdownMenuItem,
    MenuSeparator: typeof ContextMenuSeparator | typeof DropdownMenuSeparator,
  ) => (
    <>
      <MenuItem className="text-xs py-1 [&>svg]:size-3.5" onSelect={() => onTogglePin(session.id)}>
        {session.pinned ? <PinOff size={14} /> : <Pin size={14} />}
        {session.pinned ? '取消置顶' : '置顶会话'}
      </MenuItem>
      {canMove && onRequestMove && (
        <MenuItem className="text-xs py-1 [&>svg]:size-3.5" onSelect={() => onRequestMove(session.id)}>
          <ArrowRightLeft size={14} />
          迁移到其他项目
        </MenuItem>
      )}
      <MenuItem className="text-xs py-1 [&>svg]:size-3.5" onSelect={() => startEdit()}>
        <Pencil size={14} />
        重命名
      </MenuItem>
      <MenuItem className="text-xs py-1 [&>svg]:size-3.5" onSelect={() => onToggleArchive(session.id)}>
        {session.archived ? <ArchiveRestore size={14} /> : <Archive size={14} />}
        {session.archived ? '取消归档' : '归档'}
      </MenuItem>
      <MenuSeparator className="my-0.5" />
      <MenuItem className="text-xs py-1 [&>svg]:size-3.5 text-destructive" onSelect={() => onRequestDelete(session.id)}>
        <Trash2 size={14} />
        删除会话
      </MenuItem>
    </>
  )

  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>
        <div
          role="button"
          tabIndex={0}
          onClick={() => onSelect(session.id, session.title)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              onSelect(session.id, session.title)
            }
          }}
          className={cn(
            'group relative w-full flex items-center gap-1.5 rounded-md py-1 pl-2.5 pr-1.5 transition-colors duration-100 text-left',
            active && 'agent-session-item-active bg-foreground/[0.08]',
            !active && 'hover:bg-foreground/[0.03]',
          )}
        >
          {leftAccent && (
            <span className="absolute inset-y-0 left-0 w-[3px] rounded-l-md pointer-events-none bg-primary" />
          )}
          <div className="flex-1 min-w-0">
            {editing ? (
              <input
                ref={inputRef}
                value={editTitle}
                onChange={(e) => setEditTitle(e.target.value)}
                onKeyDown={handleKeyDown}
                onBlur={saveTitle}
                onClick={(e) => e.stopPropagation()}
                className="w-full bg-transparent text-[13px] leading-5 text-foreground border-b border-primary/50 outline-none px-0 py-0"
                maxLength={100}
              />
            ) : (
              <div className={cn(
                'truncate text-[13px] leading-[18px] flex items-center gap-1.5',
                active ? 'text-foreground' : 'text-foreground/80',
              )}>
                {showPinIcon && <Pin size={11} className="flex-shrink-0 text-primary/60" />}
                {session.sourceAutomationId && !session.sourceDelegationId && (
                  <Clock size={11} className="flex-shrink-0 text-foreground/40" />
                )}
                {session.sourceDelegationId && (
                  <GitBranch size={11} className={cn('flex-shrink-0', DELEGATION_STATUS_ICON_CLASS[indicatorStatus])} />
                )}
                <span className="truncate">{session.title || '未命名会话'}</span>
                {hasDraft && <Pencil size={11} className="flex-shrink-0 text-foreground/40" aria-label="输入框有未发送内容" />}
                {workspaceName && (
                  <span className="flex-shrink-0 px-1.5 py-0 rounded-full bg-primary/10 text-[10px] leading-4 font-medium truncate max-w-[80px]">
                    {workspaceName}
                  </span>
                )}
                {delegationSummary && (
                  <button
                    type="button"
                    aria-label={`${delegationSummary.expanded ? '收起' : '展开'}子会话`}
                    onClick={(event) => {
                      event.stopPropagation()
                      delegationSummary.onToggle()
                    }}
                    className="flex-shrink-0 inline-flex items-center gap-0.5 text-[11px] leading-4 text-foreground/45 hover:text-foreground/65 transition-colors"
                  >
                    <ChevronRight size={10} className={cn('transition-transform duration-150', delegationSummary.expanded && 'rotate-90')} />
                    {delegationSummary.completed}/{delegationSummary.total} 子会话
                  </button>
                )}
              </div>
            )}
          </div>

          {!editing && (
            <SessionItemActions
              updatedAt={session.updatedAt}
              relativeTimeNow={relativeTimeNow}
              pinned={!!session.pinned}
              archived={!!session.archived}
              onTogglePin={() => onTogglePin(session.id)}
              onToggleArchive={() => onToggleArchive(session.id)}
              menuItems={menuItems}
            />
          )}
        </div>
      </ContextMenuTrigger>
      <ContextMenuContent className="w-40 z-[9999] min-w-0 p-0.5">
        {menuItems(ContextMenuItem, ContextMenuSeparator)}
      </ContextMenuContent>
    </ContextMenu>
  )
})

// ===== 委派子会话行 =====

interface DelegatedChildSessionItemProps {
  session: AgentSessionMeta
  activeSessionId: string | null
  agentIndicatorMap: Map<string, SessionIndicatorStatus>
  hasDraft?: boolean
  relativeTimeNow: number
  workspaceName?: string
  onSelect: (id: string, title: string) => void
  onRequestDelete: (id: string) => void
  onRequestMove?: (id: string) => void
  onRename: (id: string, newTitle: string) => Promise<void>
  onTogglePin: (id: string) => Promise<void>
  onToggleArchive: (id: string) => Promise<void>
}

export const DelegatedChildSessionItem = React.memo(function DelegatedChildSessionItem({
  session,
  activeSessionId,
  agentIndicatorMap,
  hasDraft,
  relativeTimeNow,
  workspaceName,
  onSelect,
  onRequestDelete,
  onRequestMove,
  onRename,
  onTogglePin,
  onToggleArchive,
}: DelegatedChildSessionItemProps): React.ReactElement {
  return (
    <AgentSessionItem
      session={session}
      active={session.id === activeSessionId}
      indicatorStatus={agentIndicatorMap.get(session.id) ?? 'idle'}
      showPinIcon={false}
      hasDraft={hasDraft}
      workspaceName={workspaceName}
      relativeTimeNow={relativeTimeNow}
      onSelect={onSelect}
      onRequestDelete={onRequestDelete}
      onRequestMove={onRequestMove}
      onRename={onRename}
      onTogglePin={onTogglePin}
      onToggleArchive={onToggleArchive}
    />
  )
})

// ===== 项目分组 =====

interface AgentProjectGroupItemProps {
  group: AgentProjectGroup
  currentWorkspaceId: string | null
  /** 工作区是否已折叠（点击标题收起箭头时切换；对齐桌面 collapsedWorkspaceIds 判断） */
  collapsed: boolean
  expanded: boolean
  extraCount: number
  activeSessionId: string | null
  agentIndicatorMap: Map<string, SessionIndicatorStatus>
  agentDraftIds: Set<string>
  expandedDelegationParentIds: Set<string>
  relativeTimeNow: number
  onShowMore: (workspaceId: string) => void
  onCollapseExtra: (workspaceId: string) => void
  onSelectProject: (workspaceId: string) => void | Promise<void>
  onToggleProjectCollapse: (workspaceId: string) => void
  onNewSession: (workspaceId: string) => Promise<void>
  onRenameWorkspace: (workspaceId: string, newName: string) => Promise<void>
  onSelectSession: (id: string, title: string) => void
  onRequestDelete: (id: string) => void
  onRequestMove: (id: string) => void
  onRename: (id: string, newTitle: string) => Promise<void>
  onTogglePin: (id: string) => Promise<void>
  onToggleArchive: (id: string) => Promise<void>
  onToggleDelegationParent: (id: string) => void
}

export const AgentProjectGroupItem = React.memo(function AgentProjectGroupItem({
  group,
  currentWorkspaceId,
  collapsed,
  expanded,
  extraCount,
  activeSessionId,
  agentIndicatorMap,
  agentDraftIds,
  expandedDelegationParentIds,
  relativeTimeNow,
  onShowMore,
  onCollapseExtra,
  onSelectProject,
  onToggleProjectCollapse,
  onNewSession,
  onRenameWorkspace,
  onSelectSession,
  onRequestDelete,
  onRequestMove,
  onRename,
  onTogglePin,
  onToggleArchive,
  onToggleDelegationParent,
}: AgentProjectGroupItemProps): React.ReactElement {
  const isCurrent = group.workspace.id === currentWorkspaceId
  const isTeamWorkspace = (group.workspace as unknown as { type?: string }).type === 'team'
  const renderWorkspaceIcon = (size: number, className: string) =>
    isTeamWorkspace ? <Cloud size={size} className={className} /> : <FolderOpen size={size} className={className} />

  const [renamingWorkspace, setRenamingWorkspace] = React.useState(false)
  const [workspaceEditName, setWorkspaceEditName] = React.useState('')
  const workspaceEditRef = React.useRef<HTMLInputElement>(null)
  const justStartedRenamingRef = React.useRef(false)
  const projectClickTimerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)

  React.useEffect(() => () => {
    if (projectClickTimerRef.current) clearTimeout(projectClickTimerRef.current)
  }, [])

  const handleStartWorkspaceRename = (): void => {
    setWorkspaceEditName(group.workspace.name)
    setRenamingWorkspace(true)
    justStartedRenamingRef.current = true
    setTimeout(() => {
      justStartedRenamingRef.current = false
      workspaceEditRef.current?.focus()
      workspaceEditRef.current?.select()
    }, 300)
  }

  const handleWorkspaceRenameCommit = async (): Promise<void> => {
    if (justStartedRenamingRef.current) return
    const trimmed = workspaceEditName.trim()
    if (!trimmed || trimmed === group.workspace.name) {
      setRenamingWorkspace(false)
      return
    }
    await onRenameWorkspace(group.workspace.id, trimmed)
    setRenamingWorkspace(false)
  }

  const handleWorkspaceRenameKeyDown = (e: React.KeyboardEvent): void => {
    if (e.key === 'Enter') {
      if (e.nativeEvent.isComposing) return
      e.preventDefault()
      void handleWorkspaceRenameCommit()
    } else if (e.key === 'Escape') {
      setRenamingWorkspace(false)
    }
  }

  // 折叠时展示策略（对齐桌面）：活跃会话全展示 + 最近 3 天至多 5 条 + 额外展开
  const recentCutoff = relativeTimeNow - PROJECT_SESSION_RECENT_WINDOW_MS
  const previewTree = group.treeItems.slice(0, PROJECT_SESSION_PREVIEW_LIMIT + extraCount)
  const hiddenCount = Math.max(0, group.treeItems.length - previewTree.length)

  return (
    <section className="relative py-0.5 rounded-md transition-opacity">
      <div className="group/project relative flex translate-x-[2px] items-center">
        {renamingWorkspace ? (
          <div className={cn(
            'relative flex-1 min-w-0 flex items-center gap-1 pl-[9px] pr-1 py-1 rounded-md text-left group-hover/project:pl-4 group-hover/project:pr-11',
            isCurrent ? 'agent-project-item-current text-foreground' : 'text-foreground/65',
          )}>
            {renderWorkspaceIcon(13, 'flex-shrink-0 text-foreground/40')}
            <input
              ref={workspaceEditRef}
              value={workspaceEditName}
              onChange={(e) => setWorkspaceEditName(e.target.value)}
              onKeyDown={handleWorkspaceRenameKeyDown}
              onBlur={() => void handleWorkspaceRenameCommit()}
              className="flex-1 min-w-0 bg-transparent text-[13px] font-medium text-foreground border-b border-primary/50 outline-none px-0.5 leading-[18px]"
              maxLength={50}
            />
          </div>
        ) : (
          <button
            type="button"
            aria-expanded={!collapsed}
            onClick={(e) => {
              e.stopPropagation()
              if (e.target instanceof Element && e.target.closest('[data-project-collapse]')) {
                onToggleProjectCollapse(group.workspace.id)
                return
              }
              if (projectClickTimerRef.current) clearTimeout(projectClickTimerRef.current)
              projectClickTimerRef.current = setTimeout(() => {
                projectClickTimerRef.current = null
                void onSelectProject(group.workspace.id)
              }, PROJECT_TITLE_DOUBLE_CLICK_DELAY_MS)
            }}
            onDoubleClick={(e) => {
              e.stopPropagation()
              if (projectClickTimerRef.current) {
                clearTimeout(projectClickTimerRef.current)
                projectClickTimerRef.current = null
              }
              onToggleProjectCollapse(group.workspace.id)
            }}
            className={cn(
              'relative flex-1 min-w-0 flex items-center gap-1 pl-[9px] pr-1 py-1 rounded-md text-left transition-[padding,color,background-color] group-hover/project:pl-4 group-hover/project:pr-11 hover:bg-foreground/[0.025]',
              isCurrent ? 'agent-project-item-current text-foreground' : 'text-foreground/65 hover:text-foreground/88',
            )}
          >
            {renderWorkspaceIcon(13, 'flex-shrink-0 text-foreground/40')}
            <span className="flex-1 min-w-0 truncate text-[13px] font-medium leading-[18px]">
              {group.workspace.name}
            </span>
            <span
              data-project-collapse
              title={collapsed ? '展开项目会话' : '收起项目会话'}
              className="flex-shrink-0 text-foreground/30 transition-colors hover:text-foreground/70"
            >
              <ChevronRight size={12} className={`transition-transform duration-150 ${collapsed ? '-rotate-90' : 'rotate-90'}`} />
            </span>
          </button>
        )}

        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              aria-label={`在「${group.workspace.name}」中新建会话`}
              onClick={(e) => {
                e.stopPropagation()
                void onNewSession(group.workspace.id)
              }}
              className="absolute right-5 top-1/2 flex size-5 -translate-y-1/2 items-center justify-center rounded-md text-foreground/30 transition-colors hover:bg-foreground/[0.055] hover:text-foreground/65 group-hover/project:opacity-100"
            >
              <Plus size={13} />
            </button>
          </TooltipTrigger>
          <TooltipContent side="top">在此项目中新建会话</TooltipContent>
        </Tooltip>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label="项目菜单"
              className="absolute right-0 top-1/2 flex size-5 -translate-y-1/2 items-center justify-center rounded-md text-foreground/30 transition-colors hover:bg-foreground/[0.055] hover:text-foreground/60 group-hover/project:opacity-100 data-[state=open]:opacity-100"
            >
              <MoreHorizontal size={13} />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-44 z-[9999] min-w-0 p-0.5">
            <DropdownMenuItem className="text-xs py-1 [&>svg]:size-3.5" onSelect={() => handleStartWorkspaceRename()}>
              <Pencil size={14} />
              重命名项目
            </DropdownMenuItem>
            <DropdownMenuItem className="text-xs py-1 [&>svg]:size-3.5" onSelect={() => void onNewSession(group.workspace.id)}>
              <Plus size={14} />
              新建会话
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {!collapsed && previewTree.length > 0 && (
        <div id={`project-sessions-${group.workspace.id}`} className="ml-3 pl-2 flex flex-col gap-0.5">
          {previewTree.map((item) => {
            const childCount = item.childSessions.length
            const treeActive = treeContainsSessionId(item, activeSessionId)
            const activeChildVisible = item.childSessions.some((child) => child.id === activeSessionId)
            const expandedChildren = expandedDelegationParentIds.has(item.session.id) || activeChildVisible
            const rowStatus = getSessionTreeStatus(item, agentIndicatorMap)

            return (
              <div key={item.session.id} className="flex flex-col gap-0.5">
                <AgentSessionItem
                  session={item.session}
                  active={treeActive}
                  indicatorStatus={rowStatus}
                  showPinIcon={!!item.session.pinned}
                  hasDraft={agentDraftIds.has(item.session.id)}
                  delegationSummary={childCount > 0
                    ? {
                        total: childCount,
                        completed: countCompletedDelegatedChildren(item.childSessions),
                        expanded: expandedChildren,
                        onToggle: () => onToggleDelegationParent(item.session.id),
                      }
                    : undefined}
                  workspaceName={undefined}
                  relativeTimeNow={relativeTimeNow}
                  onSelect={onSelectSession}
                  onRequestDelete={onRequestDelete}
                  onRequestMove={onRequestMove}
                  onRename={onRename}
                  onTogglePin={onTogglePin}
                  onToggleArchive={onToggleArchive}
                />

                {childCount > 0 && expandedChildren && (
                  <div className="ml-3 border-l border-foreground/10 pl-2 flex flex-col gap-0.5">
                    {item.childSessions.map((childSession) => (
                      <DelegatedChildSessionItem
                        key={childSession.id}
                        session={childSession}
                        activeSessionId={activeSessionId}
                        agentIndicatorMap={agentIndicatorMap}
                        hasDraft={agentDraftIds.has(childSession.id)}
                        relativeTimeNow={relativeTimeNow}
                        onSelect={onSelectSession}
                        onRequestDelete={onRequestDelete}
                        onRequestMove={onRequestMove}
                        onRename={onRename}
                        onTogglePin={onTogglePin}
                        onToggleArchive={onToggleArchive}
                      />
                    ))}
                  </div>
                )}
              </div>
            )
          })}

          {hiddenCount > 0 && (
            <button
              type="button"
              onClick={() => onShowMore(group.workspace.id)}
              className="w-full text-left px-1.5 py-1 rounded-md text-[12px] text-foreground/35 hover:bg-foreground/[0.03] hover:text-foreground/60 transition-colors"
            >
              显示更多
            </button>
          )}

          {expanded && (
            <button
              type="button"
              onClick={() => onCollapseExtra(group.workspace.id)}
              className="w-full text-left px-1.5 py-1 rounded-md text-[12px] text-foreground/35 hover:bg-foreground/[0.03] hover:text-foreground/60 transition-colors"
            >
              收起
            </button>
          )}
        </div>
      )}
    </section>
  )
})
