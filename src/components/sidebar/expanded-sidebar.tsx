/**
 * expanded-sidebar.tsx — 移动端展开态侧边栏（apps/tablet-client）
 *
 * 1:1 复现桌面 left-sidebar/expanded-sidebar.tsx 的视觉结构：
 * - 顶部「新会话 + 搜索」按钮区
 * - 置顶区（委派树 + 子会话缩进）
 * - 项目分组区（项目切换 / 折叠 / 新建会话 / 新建项目）
 * - 归档视图（按日期分组）
 * - 底部「已归档入口 + 用户头像 + 设置入口」
 *
 * 移动端差异（相对桌面）：
 * - 移除 ModeSwitcher（移动端仅 Agent 模式，无 Chat 数据源）
 * - 移除 planning / skills 入口（tabletMode 下桌面也隐藏）
 * - 移除 SidebarWindowDragStrip / 拖拽排序（桌面多窗口专属）
 * - 用户头像用首字母圆替代 Electron UserAvatar
 */

import * as React from 'react'
import { Plus, Search, FolderOpen, Archive, ArchiveRestore, ArrowLeft, Settings, CalendarDays, History, ArrowDownAZ } from 'lucide-react'
import { toast } from 'sonner'
import { cn } from '@/lib/utils'
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip'
import { UserAvatar } from '@/components/chat/UserAvatar'
import { DEFAULT_USER_AVATAR } from '@/atoms/ui-atoms'
import { WORKSPACE_SORT_LABEL } from './sidebar-utils'
import {
  AgentSessionItem,
  AgentProjectGroupItem,
} from './session-items'
import {
  getSessionTreeStatus,
  treeContainsSessionId,
  countCompletedDelegatedChildren,
} from './session-tree'
import type { SidebarModel } from './use-left-sidebar'

export function ExpandedSidebar({ s }: { s: SidebarModel }): React.ReactElement {
  const {
    viewMode,
    setViewMode,
    activeSessionId,
    agentIndicatorMap,
    agentDraftIds,
    workspaceNameMap,
    handleSelectAgentSession,
    handleRequestDelete,
    handleRequestMove,
    handleAgentRename,
    handleTogglePinAgent,
    handleToggleArchiveAgent,
    handleToggleDelegationParent,
    relativeTimeNow,
    workspaceSortMode,
    handleCycleWorkspaceSort,
    handleStartCreateProject,
    creatingProject,
    setCreatingProject,
    newProjectName,
    setNewProjectName,
    newProjectInputRef,
    handleCreateProjectKeyDown,
    agentProjectGroups,
    currentWorkspaceId,
    expandedExtraCountMap,
    collapsedWorkspaceIds,
    expandedDelegationParentIds,
    handleShowMoreSessions,
    handleCollapseExtraSessions,
    handleSelectProject,
    handleToggleProjectCollapse,
    createAgentSessionInWorkspace,
    handleWorkspaceRename,
    handleNewAgentSession,
    pinnedAgentSessionTrees,
    agentSessionGroups,
    archivedAgentSessionCount,
    userProfile,
    setSearchDialogOpen,
  } = s

  return (
    <div className="relative h-full flex flex-col overflow-hidden">
      {/* 新会话按钮 + 搜索按钮 */}
      <div className="px-3 pt-2 flex items-center gap-1.5">
        <button
          onClick={() => void handleNewAgentSession()}
          className="flex-1 flex items-center gap-2 px-3 py-2 rounded-[10px] text-[13px] font-medium text-foreground/70 bg-primary/5 hover:bg-primary/10 hover:text-foreground transition-[background-color,border-color,color] duration-150 border border-border/60 hover:border-border"
        >
          <Plus size={14} />
          <span>新会话</span>
        </button>
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              onClick={() => setSearchDialogOpen(true)}
              className="flex-shrink-0 size-[36px] flex items-center justify-center rounded-[10px] text-foreground/40 bg-primary/5 hover:bg-primary/10 hover:text-foreground/60 transition-[background-color,border-color,color] duration-150 border border-border/60 hover:border-border"
            >
              <Search size={14} />
            </button>
          </TooltipTrigger>
          <TooltipContent side="bottom">搜索会话</TooltipContent>
        </Tooltip>
      </div>

      {/* 活跃视图 */}
      {viewMode === 'active' ? (
        <div className="flex-1 flex flex-col min-h-0">
          {/* 置顶区 */}
          {pinnedAgentSessionTrees.length > 0 && (
            <div className="pt-2 pb-1 flex-shrink-0">
              <div className="pl-[18px] pr-3.5 pb-1 text-[13px] font-medium leading-[18px] text-foreground/40 select-none">
                置顶
              </div>
              <div className="px-2">
                <div className="ml-4 flex flex-col gap-0.5">
                  {pinnedAgentSessionTrees.map((item) => {
                    const childCount = item.childSessions.length
                    const rowStatus = getSessionTreeStatus(item, agentIndicatorMap)
                    const treeActive = treeContainsSessionId(item, activeSessionId)
                    const activeChildVisible = item.childSessions.some((child) => child.id === activeSessionId)
                    const expandedChildren = expandedDelegationParentIds.has(item.session.id) || activeChildVisible

                    return (
                      <div key={`pinned-${item.session.id}`} className="flex flex-col gap-0.5">
                        <AgentSessionItem
                          session={item.session}
                          active={treeActive}
                          indicatorStatus={rowStatus}
                          showPinIcon={false}
                          hasDraft={agentDraftIds.has(item.session.id)}
                          delegationSummary={childCount > 0 ? {
                            total: childCount,
                            completed: countCompletedDelegatedChildren(item.childSessions),
                            expanded: expandedChildren,
                            onToggle: () => handleToggleDelegationParent(item.session.id),
                          } : undefined}
                          workspaceName={item.session.workspaceId ? workspaceNameMap.get(item.session.workspaceId) : undefined}
                          relativeTimeNow={relativeTimeNow}
                          onSelect={handleSelectAgentSession}
                          onRequestDelete={handleRequestDelete}
                          onRequestMove={handleRequestMove}
                          onRename={handleAgentRename}
                          onTogglePin={handleTogglePinAgent}
                          onToggleArchive={handleToggleArchiveAgent}
                        />

                        {childCount > 0 && expandedChildren && (
                          <div className="ml-3 border-l border-foreground/10 pl-2 flex flex-col gap-0.5">
                            {item.childSessions.map((childSession) => (
                              <AgentSessionItem
                                key={childSession.id}
                                session={childSession}
                                active={childSession.id === activeSessionId}
                                indicatorStatus={agentIndicatorMap.get(childSession.id) ?? 'idle'}
                                showPinIcon={false}
                                hasDraft={agentDraftIds.has(childSession.id)}
                                workspaceName={childSession.workspaceId ? workspaceNameMap.get(childSession.workspaceId) : undefined}
                                relativeTimeNow={relativeTimeNow}
                                onSelect={handleSelectAgentSession}
                                onRequestDelete={handleRequestDelete}
                                onRequestMove={handleRequestMove}
                                onRename={handleAgentRename}
                                onTogglePin={handleTogglePinAgent}
                                onToggleArchive={handleToggleArchiveAgent}
                              />
                            ))}
                          </div>
                        )}
                      </div>
                    )
                  })}
                </div>
              </div>
            </div>
          )}

          {/* 项目标题栏 */}
          <div className="px-2 pt-2 pb-1 flex items-center justify-between flex-shrink-0">
            <span className="ml-[4px] px-1.5 text-[13px] font-medium leading-[18px] text-foreground/40 select-none">项目</span>
            <div className="flex items-center gap-0.5">
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    onClick={handleCycleWorkspaceSort}
                    className="size-6 flex items-center justify-center rounded-md text-foreground/35 hover:bg-foreground/[0.06] hover:text-foreground/60 transition-colors"
                    aria-label={`项目排序：当前${WORKSPACE_SORT_LABEL[workspaceSortMode]}排序，点击切换`}
                  >
                    {workspaceSortMode === 'recent'
                      ? <History size={14} />
                      : workspaceSortMode === 'name'
                        ? <ArrowDownAZ size={14} />
                        : <CalendarDays size={14} />}
                  </button>
                </TooltipTrigger>
                <TooltipContent side="top">当前{WORKSPACE_SORT_LABEL[workspaceSortMode]}排序，点击切换排序方式</TooltipContent>
              </Tooltip>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    onClick={handleStartCreateProject}
                    className="size-6 flex items-center justify-center rounded-md text-foreground/40 hover:bg-foreground/[0.06] hover:text-foreground/60 transition-colors"
                    aria-label="新建项目"
                  >
                    <Plus size={16} />
                  </button>
                </TooltipTrigger>
                <TooltipContent side="top">新建项目</TooltipContent>
              </Tooltip>
            </div>
          </div>

          {/* 项目分组历史 */}
          <div className="flex-1 overflow-y-auto px-2 pb-3 scrollbar-thin min-h-0">
            {creatingProject && (
              <div className="flex items-center gap-2 px-2 py-1.5 mb-1 rounded-md bg-foreground/[0.04]">
                <FolderOpen size={14} className="flex-shrink-0 text-foreground/40" />
                <input
                  ref={newProjectInputRef}
                  value={newProjectName}
                  onChange={(e) => setNewProjectName(e.target.value)}
                  onKeyDown={handleCreateProjectKeyDown}
                  onBlur={() => {
                    setCreatingProject(false)
                    setNewProjectName('')
                  }}
                  placeholder="项目名称..."
                  className="flex-1 min-w-0 bg-transparent text-[13px] text-foreground border-b border-primary/50 outline-none px-0.5"
                  maxLength={50}
                />
              </div>
            )}

            <div className="flex flex-col gap-0.5">
              {agentProjectGroups.map((group) => (
                <AgentProjectGroupItem
                  key={group.workspace.id}
                  group={group}
                  currentWorkspaceId={currentWorkspaceId}
                  collapsed={collapsedWorkspaceIds.has(group.workspace.id)}
                  expanded={(expandedExtraCountMap.get(group.workspace.id) ?? 0) > 0}
                  extraCount={expandedExtraCountMap.get(group.workspace.id) ?? 0}
                  activeSessionId={activeSessionId}
                  agentIndicatorMap={agentIndicatorMap}
                  agentDraftIds={agentDraftIds}
                  expandedDelegationParentIds={expandedDelegationParentIds}
                  relativeTimeNow={relativeTimeNow}
                  onShowMore={handleShowMoreSessions}
                  onCollapseExtra={handleCollapseExtraSessions}
                  onSelectProject={handleSelectProject}
                  onToggleProjectCollapse={handleToggleProjectCollapse}
                  onNewSession={createAgentSessionInWorkspace}
                  onRenameWorkspace={handleWorkspaceRename}
                  onSelectSession={handleSelectAgentSession}
                  onRequestDelete={handleRequestDelete}
                  onRequestMove={handleRequestMove}
                  onRename={handleAgentRename}
                  onTogglePin={handleTogglePinAgent}
                  onToggleArchive={handleToggleArchiveAgent}
                  onToggleDelegationParent={handleToggleDelegationParent}
                />
              ))}
            </div>
          </div>
        </div>
      ) : (
        <>
          {/* 归档视图标题栏 */}
          <div className="px-6 pt-3 pb-1">
            <div className="text-[12px] font-medium text-foreground/40">已归档会话</div>
          </div>

          <div className="flex-1 overflow-y-auto px-3 pt-2 pb-3 scrollbar-thin">
            {agentSessionGroups.map((group) => (
              <div key={group.label} className="mb-1">
                <div className="px-3 pt-2 pb-1 text-[11px] font-medium text-foreground/40 select-none">
                  {group.label}
                </div>
                <div className="flex flex-col gap-0.5">
                  {group.items.map((session) => (
                    <AgentSessionItem
                      key={session.id}
                      session={session}
                      active={session.id === activeSessionId}
                      indicatorStatus={agentIndicatorMap.get(session.id) ?? 'idle'}
                      showPinIcon={!!session.pinned}
                      hasDraft={agentDraftIds.has(session.id)}
                      workspaceName={session.workspaceId ? workspaceNameMap.get(session.workspaceId) : undefined}
                      relativeTimeNow={relativeTimeNow}
                      onSelect={handleSelectAgentSession}
                      onRequestDelete={handleRequestDelete}
                      onRequestMove={handleRequestMove}
                      onRename={handleAgentRename}
                      onTogglePin={handleTogglePinAgent}
                      onToggleArchive={handleToggleArchiveAgent}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {/* 已归档入口 / 返回活跃 */}
      <div className="px-3 pb-1">
        {viewMode === 'active' ? (
          archivedAgentSessionCount > 0 && (
            <button
              onClick={() => setViewMode('archived')}
              className="w-full flex items-center gap-2 px-3 py-2 rounded-[10px] text-[12px] text-foreground/40 hover:bg-foreground/[0.04] hover:text-foreground/60 transition-colors"
            >
              <Archive size={13} className="text-foreground/30" />
              <span>已归档 ({archivedAgentSessionCount})</span>
            </button>
          )
        ) : (
          <button
            onClick={() => setViewMode('active')}
            className="w-full flex items-center gap-2 px-3 py-2 rounded-[10px] text-[12px] text-foreground/60 bg-foreground/[0.04] hover:bg-foreground/[0.07] hover:text-foreground/80 transition-colors"
          >
            <ArrowLeft size={13} className="text-foreground/50" />
            <span>返回活跃会话</span>
          </button>
        )}
      </div>

      {/* 底部：用户资料 + 设置入口（对齐桌面 left-sidebar/expanded-sidebar.tsx） */}
      <div className="px-3 pb-3 space-y-1.5">
        <button
          type="button"
          onClick={() => toast.info('设置面板暂未开放（移动端待实现）')}
          className="w-full flex items-center gap-3 px-3 py-2 rounded-[10px] transition-colors text-foreground/70 hover:bg-foreground/[0.04] hover:text-foreground"
        >
          <UserAvatar avatar={userProfile.avatar || DEFAULT_USER_AVATAR} size={28} />
          <span className="flex-1 text-sm truncate text-left">{userProfile.userName || userProfile.email || '用户'}</span>
          <div className="relative flex-shrink-0 text-foreground/40">
            <Settings size={16} />
          </div>
        </button>
      </div>
    </div>
  )
}
