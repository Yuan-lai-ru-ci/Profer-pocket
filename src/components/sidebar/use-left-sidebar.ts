/**
 * use-left-sidebar.ts — 移动端侧边栏状态与业务逻辑 hook（apps/tablet-client）
 *
 * 对齐桌面 left-sidebar/use-left-sidebar.ts 的语义（会话分组 / 项目分组 / 置顶 / 归档 /
 * 委派树 / 新建会话 / 删除 / 重命名 / 搜索开关 / 底部用户栏），但：
 * - 数据源复用 tablet-client 现有 atoms（session.ts / ui-atoms.ts / sidebar-atoms.ts），
 *   不搬桌面 agent-atoms/chat-atoms/tab-atoms（避免两套 atom 打架）。
 * - 动作走 useConnection().client（WS 命令），无 Electron IPC。
 *
 * 桌面 atom 名 → 移动端 atom 名 映射（记录于 docs/ui-port-map.md）：
 *   agentSessionsAtom          → sessionsAtom          (atoms/session.ts)
 *   agentWorkspacesAtom        → workspacesAtom        (atoms/session.ts)
 *   currentAgentSessionIdAtom  → currentSessionIdAtom  (atoms/session.ts)
 *   currentAgentWorkspaceIdAtom→ currentWorkspaceIdAtom(atoms/session.ts)
 *   agentChannelIdAtom         → channelIdAtom         (atoms/session.ts)
 *   agentModelIdAtom           → modelIdAtom           (atoms/session.ts)
 *   userProfileAtom            → userProfileAtom       (atoms/ui-atoms.ts)
 *   agentSessionIndicatorMapAtom → 本地派生（从 agent.ts 流式状态归并）
 *   sidebarViewModeAtom / workspaceSortModeAtom / searchDialogOpenAtom → atoms/sidebar-atoms.ts
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import { toast } from 'sonner'
import type { AgentSessionMeta, AgentWorkspace } from '@profer/shared'
import {
  sessionsAtom,
  workspacesAtom,
  currentSessionIdAtom,
  currentWorkspaceIdAtom,
  channelIdAtom,
  modelIdAtom,
} from '@/atoms/session'
import { agentStreamingStatesAtom } from '@/atoms/agent'
import { userProfileAtom } from '@/atoms/ui-atoms'
import {
  sidebarViewModeAtom,
  workspaceSortModeAtom,
  searchDialogOpenAtom,
  type SidebarViewMode,
} from '@/atoms/sidebar-atoms'
import { useConnection } from '@/hooks/useConnection'
import {
  groupByDate,
  sortAgentSessionsByUpdatedAtDesc,
  getNextWorkspaceSortMode,
  workspaceNameCollator,
  getRailInitial,
} from './sidebar-utils'
import {
  buildAgentSessionTrees,
  getDirectDelegatedChildren,
  hasPinnedVisibleParent,
  type AgentSessionTreeItem,
  type SessionIndicatorStatus,
} from './session-tree'
import type { AgentProjectGroup } from './session-items'

export function useLeftSidebar() {
  const { client } = useConnection()

  const [sessions, setSessions] = useAtom(sessionsAtom)
  const [workspaces, setWorkspaces] = useAtom(workspacesAtom)
  const [currentSessionId, setCurrentSessionId] = useAtom(currentSessionIdAtom)
  const [currentWorkspaceId, setCurrentWorkspaceId] = useAtom(currentWorkspaceIdAtom)
  const channelId = useAtomValue(channelIdAtom)
  const modelId = useAtomValue(modelIdAtom)
  const streamingStates = useAtomValue(agentStreamingStatesAtom)
  const userProfile = useAtomValue(userProfileAtom)

  const [viewMode, setViewMode] = useAtom(sidebarViewModeAtom)
  const [workspaceSortMode, setWorkspaceSortMode] = useAtom(workspaceSortModeAtom)
  const setSearchDialogOpen = useSetAtom(searchDialogOpenAtom)

  const [pendingDeleteId, setPendingDeleteId] = React.useState<string | null>(null)
  const [moveTargetId, setMoveTargetId] = React.useState<string | null>(null)
  const [expandedExtraCountMap, setExpandedExtraCountMap] = React.useState<Map<string, number>>(new Map())
  const [collapsedWorkspaceIds, setCollapsedWorkspaceIds] = React.useState<Set<string>>(new Set())
  const [expandedDelegationParentIds, setExpandedDelegationParentIds] = React.useState<Set<string>>(new Set())
  const [relativeTimeNow, setRelativeTimeNow] = React.useState(() => Date.now())
  const [creatingProject, setCreatingProject] = React.useState(false)
  const [newProjectName, setNewProjectName] = React.useState('')
  const newProjectInputRef = React.useRef<HTMLInputElement>(null)
  const [busyIds, setBusyIds] = React.useState<Set<string>>(new Set())

  // 时间 tick
  React.useEffect(() => {
    const id = window.setInterval(() => setRelativeTimeNow(Date.now()), 60_000)
    return () => window.clearInterval(id)
  }, [])

  // 指示器状态：从流式状态归并（running/blocked/completed/idle）
  const agentIndicatorMap = React.useMemo<Map<string, SessionIndicatorStatus>>(() => {
    const map = new Map<string, SessionIndicatorStatus>()
    for (const [sessionId, st] of streamingStates) {
      if (st.running) map.set(sessionId, 'running')
    }
    for (const s of sessions) {
      if (!map.has(s.id)) {
        if (s.completedButUnconfirmed) map.set(s.id, 'completed')
        else map.set(s.id, 'idle')
      }
    }
    return map
  }, [streamingStates, sessions])

  const unviewedCompletedSessionIds = React.useMemo<Set<string>>(() => {
    const set = new Set<string>()
    for (const s of sessions) {
      if (s.completedButUnconfirmed) set.add(s.id)
    }
    return set
  }, [sessions])

  // 工作区名称映射
  const workspaceNameMap = React.useMemo(() => {
    const map = new Map<string, string>()
    for (const w of workspaces) map.set(w.id, w.name)
    return map
  }, [workspaces])

  // ===== ws 动作封装 =====
  const runAction = React.useCallback(
    async (id: string, action: (sid: string) => Promise<unknown>) => {
      if (!client) return
      if (busyIds.has(id)) return
      setBusyIds((prev) => new Set(prev).add(id))
      try {
        await action(id)
      } catch (e) {
        console.error('[use-left-sidebar] 会话操作失败', e)
      } finally {
        setBusyIds((prev) => {
          const next = new Set(prev)
          next.delete(id)
          return next
        })
      }
    },
    [client, busyIds],
  )

  /** 切换会话 */
  const handleSelectAgentSession = React.useCallback(
    (id: string, _title: string) => {
      setCurrentSessionId(id)
    },
    [setCurrentSessionId],
  )

  /** 切换置顶 */
  const handleTogglePinAgent = React.useCallback(
    (id: string): Promise<void> => {
      void runAction(id, (sid) => client!.toggleSessionPin(sid))
      return Promise.resolve()
    },
    [client, runAction],
  )

  /** 切换归档 */
  const handleToggleArchiveAgent = React.useCallback(
    (id: string): Promise<void> => {
      void runAction(id, (sid) => client!.toggleSessionArchive(sid))
      return Promise.resolve()
    },
    [client, runAction],
  )

  /** 重命名 */
  const handleAgentRename = React.useCallback(
    (id: string, title: string): Promise<void> => {
      void runAction(id, (sid) => client!.renameSession(sid, title))
      return Promise.resolve()
    },
    [client, runAction],
  )

  /** 请求删除（弹确认框） */
  const handleRequestDelete = React.useCallback((id: string) => {
    setPendingDeleteId(id)
  }, [])

  /** 确认删除 */
  const handleConfirmDelete = React.useCallback(async (): Promise<void> => {
    if (!pendingDeleteId || !client) return
    const id = pendingDeleteId
    try {
      await client.deleteSession(id)
      if (currentSessionId === id) setCurrentSessionId(null)
    } catch (e) {
      console.error('[use-left-sidebar] 删除会话失败', e)
      setSessions((prev) => prev.filter((s) => s.id !== id))
    } finally {
      setPendingDeleteId(null)
    }
  }, [pendingDeleteId, client, currentSessionId, setCurrentSessionId, setSessions])

  /** 请求迁移 */
  const handleRequestMove = React.useCallback((id: string) => {
    setMoveTargetId(id)
  }, [])

  /** 迁移到项目 */
  const handleMoveSession = React.useCallback(
    async (targetWorkspaceId: string) => {
      if (!moveTargetId || !client) return
      try {
        await client.moveSessionToWorkspace({ sessionId: moveTargetId, targetWorkspaceId })
        toast.success('会话已迁移')
      } catch (e) {
        console.error('[use-left-sidebar] 迁移会话失败', e)
        toast.error('迁移失败')
      } finally {
        setMoveTargetId(null)
      }
    },
    [moveTargetId, client],
  )

  /** 新建会话（继承当前项目 + 默认渠道/模型），创建后跳转到新会话并刷新列表（对齐桌面） */
  const createAgentSessionInWorkspace = React.useCallback(
    async (workspaceId?: string) => {
      if (!client) return
      try {
        const targetWorkspaceId = workspaceId ?? currentWorkspaceId ?? undefined
        if (targetWorkspaceId && targetWorkspaceId !== currentWorkspaceId) {
          setCurrentWorkspaceId(targetWorkspaceId)
        }
        const res = (await client.createSession({
          channelId: channelId || undefined,
          modelId: modelId || undefined,
          workspaceId: targetWorkspaceId,
        })) as { sessionId?: string; title?: string } | undefined
        const newSessionId = res?.sessionId
        if (newSessionId) {
          // 跳转到新会话
          setCurrentSessionId(newSessionId)
        }
        // 刷新会话列表（拿到最新含新会话的完整列表）
        try {
          const data = (await client.listSessions()) as AgentSessionMeta[]
          setSessions(data)
        } catch (e) {
          console.error('[use-left-sidebar] 新建会话后刷新列表失败', e)
        }
      } catch (e) {
        console.error('[use-left-sidebar] 新建会话失败', e)
        toast.error(e instanceof Error ? e.message : '新建会话失败')
      }
    },
    [client, channelId, modelId, currentWorkspaceId, setCurrentSessionId, setCurrentWorkspaceId, setSessions],
  )

  const handleNewAgentSession = React.useCallback(async (): Promise<void> => {
    await createAgentSessionInWorkspace()
  }, [createAgentSessionInWorkspace])

  /** 选择项目（设置当前工作区） */
  const handleSelectProject = React.useCallback(
    async (workspaceId: string) => {
      setCurrentWorkspaceId(workspaceId)
      setCollapsedWorkspaceIds((prev) => {
        const next = new Set(prev)
        next.delete(workspaceId)
        return next
      })
      if (!client) return
      // 已有会话则选中最近一个，否则创建草稿会话
      try {
        const target = sessions.filter((s) => s.workspaceId === workspaceId && !s.archived && !s.draft)
        if (target.length > 0) {
          const recent = target.sort((a, b) => b.updatedAt - a.updatedAt)[0]
          if (recent) setCurrentSessionId(recent.id)
          return
        }
        await client.ensureProjectDraftSession({
          workspaceId,
          channelId: channelId || undefined,
          modelId: modelId || undefined,
        })
      } catch (e) {
        console.error('[use-left-sidebar] 选择项目失败', e)
      }
    },
    [client, sessions, channelId, modelId, setCurrentWorkspaceId, setCurrentSessionId],
  )

  const handleToggleProjectCollapse = React.useCallback(
    (workspaceId: string) => {
      setCollapsedWorkspaceIds((prev) => {
        const next = new Set(prev)
        if (next.has(workspaceId)) next.delete(workspaceId)
        else next.add(workspaceId)
        return next
      })
    },
    [],
  )

  const handleShowMoreSessions = React.useCallback((workspaceId: string) => {
    setExpandedExtraCountMap((prev) => {
      const next = new Map(prev)
      next.set(workspaceId, (prev.get(workspaceId) ?? 0) + 10)
      return next
    })
  }, [])

  const handleCollapseExtraSessions = React.useCallback((workspaceId: string) => {
    setExpandedExtraCountMap((prev) => {
      if (!prev.has(workspaceId)) return prev
      const next = new Map(prev)
      next.delete(workspaceId)
      return next
    })
  }, [])

  const handleToggleDelegationParent = React.useCallback((sessionId: string) => {
    setExpandedDelegationParentIds((prev) => {
      const next = new Set(prev)
      if (next.has(sessionId)) next.delete(sessionId)
      else next.add(sessionId)
      return next
    })
  }, [])

  // 新建项目
  const handleStartCreateProject = React.useCallback(() => {
    setCreatingProject(true)
    setNewProjectName('')
    requestAnimationFrame(() => newProjectInputRef.current?.focus())
  }, [])

  const handleCreateProject = React.useCallback(async (): Promise<void> => {
    const trimmed = newProjectName.trim()
    if (!trimmed) {
      setCreatingProject(false)
      return
    }
    if (!client) {
      setCreatingProject(false)
      return
    }
    try {
      await client.createWorkspace(trimmed)
      setCreatingProject(false)
      setNewProjectName('')
      // 刷新工作区列表
      const ws = (await client.listWorkspaces()) as AgentWorkspace[]
      if (Array.isArray(ws)) setWorkspaces(ws)
    } catch (e) {
      console.error('[use-left-sidebar] 新建项目失败', e)
    }
  }, [newProjectName, client, setWorkspaces])

  const handleCreateProjectKeyDown = React.useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter') {
        if (e.nativeEvent.isComposing) return
        e.preventDefault()
        void handleCreateProject()
      } else if (e.key === 'Escape') {
        e.preventDefault()
        setCreatingProject(false)
        setNewProjectName('')
      }
    },
    [handleCreateProject],
  )

  const handleWorkspaceRename = React.useCallback(
    async (workspaceId: string, newName: string): Promise<void> => {
      if (!client) return
      // ws-client 无 updateWorkspace，用 createWorkspace 之外的简化：暂无 rename 命令。
      // 移动端首版：重命名项目走 window.prompt 本地占位（记录于 ui-port-map.md）。
      void workspaceId
      void newName
      toast.error('移动端暂不支持重命名项目')
    },
    [client],
  )

  // 项目排序
  const handleCycleWorkspaceSort = React.useCallback(() => {
    setWorkspaceSortMode((prev) => getNextWorkspaceSortMode(prev))
  }, [setWorkspaceSortMode])

  const sortedWorkspaces = React.useMemo(() => {
    // 侧边栏只显示个人工作区：过滤掉团队工作区（type === 'team'，含团队ID/协作项）
    const personalWorkspaces = workspaces.filter((w) => w.type !== 'team')
    if (workspaceSortMode === 'recent') {
      const recencyByWorkspace = new Map<string, number>()
      for (const s of sessions) {
        const wsId = s.workspaceId
        if (!wsId || s.draft || s.archived) continue
        const prev = recencyByWorkspace.get(wsId) ?? 0
        if (s.updatedAt > prev) recencyByWorkspace.set(wsId, s.updatedAt)
      }
      return [...personalWorkspaces].sort((a, b) => {
        const ra = recencyByWorkspace.get(a.id) ?? a.updatedAt
        const rb = recencyByWorkspace.get(b.id) ?? b.updatedAt
        return rb - ra
      })
    }
    if (workspaceSortMode === 'name') {
      return [...personalWorkspaces].sort((a, b) => workspaceNameCollator.compare(a.name, b.name))
    }
    return personalWorkspaces
  }, [workspaces, workspaceSortMode, sessions])

  // ===== 分组派生 =====
  const pinnedSessions = React.useMemo(
    () => sortAgentSessionsByUpdatedAtDesc(
      sessions.filter((s) => s.pinned && !s.draft && !s.archived && !hasPinnedVisibleParent(s, sessions)),
    ),
    [sessions],
  )

  const pinnedAgentSessionTrees = React.useMemo<AgentSessionTreeItem[]>(
    () => pinnedSessions.map((session) => ({
      session,
      childSessions: getDirectDelegatedChildren(sessions, session.id).filter((c) => !c.archived && !c.draft),
    })),
    [pinnedSessions, sessions],
  )

  // 项目分组（排除置顶/归档/draft）
  const agentProjectGroups = React.useMemo<AgentProjectGroup[]>(() => {
    const sessionsByWorkspace = new Map<string, AgentSessionMeta[]>()
    for (const w of sortedWorkspaces) sessionsByWorkspace.set(w.id, [])

    const visibleHistory = sortAgentSessionsByUpdatedAtDesc(
      sessions.filter(
        (s) => !s.archived && !s.pinned && !s.draft && !hasPinnedVisibleParent(s, sessions),
      ),
    )

    const defaultWsId = sortedWorkspaces.find((w) => w.slug === 'default')?.id ?? sortedWorkspaces[0]?.id
    for (const s of visibleHistory) {
      const targetId = s.workspaceId && sessionsByWorkspace.has(s.workspaceId) ? s.workspaceId : defaultWsId
      if (!targetId) continue
      sessionsByWorkspace.get(targetId)!.push(s)
    }

    return sortedWorkspaces.map((workspace) => {
      const workspaceSessions = sessionsByWorkspace.get(workspace.id) ?? []
      return {
        workspace,
        // 构建委派会话树：父会话 + 子会话层级（对齐桌面 AgentProjectGroup.treeItems）
        treeItems: buildAgentSessionTrees(workspaceSessions),
      }
    })
  }, [sessions, sortedWorkspaces])

  // 归档会话按日期分组
  const agentSessionGroups = React.useMemo(
    () => groupByDate(sortAgentSessionsByUpdatedAtDesc(
      sessions.filter((s) => s.archived && !s.draft),
    )),
    [sessions],
  )

  const archivedAgentSessionCount = React.useMemo(
    () => sessions.filter((s) => s.archived && !s.draft).length,
    [sessions],
  )

  // rail recent items（窄栏最近会话）
  const railRecentItems = React.useMemo(() => {
    return sessions
      .filter((s) => !s.archived && !s.draft && (!currentWorkspaceId || s.workspaceId === currentWorkspaceId))
      .sort((a, b) => {
        const activeDelta = Number(b.id === currentSessionId) - Number(a.id === currentSessionId)
        if (activeDelta !== 0) return activeDelta
        const pinnedDelta = Number(!!b.pinned) - Number(!!a.pinned)
        if (pinnedDelta !== 0) return pinnedDelta
        return b.updatedAt - a.updatedAt
      })
      .slice(0, 5)
      .map((session) => ({
        id: session.id,
        title: session.title,
        type: 'agent' as const,
        initial: getRailInitial(session.title),
        active: session.id === currentSessionId,
        status: (agentIndicatorMap.get(session.id) ?? 'idle') as SessionIndicatorStatus,
        pinned: !!session.pinned,
        workspaceName: session.workspaceId ? workspaceNameMap.get(session.workspaceId) : undefined,
        isAutomation: !!session.sourceAutomationId,
      }))
  }, [sessions, currentWorkspaceId, currentSessionId, agentIndicatorMap, workspaceNameMap])

  return {
    // 视图/模式
    mode: 'agent' as const,
    viewMode,
    setViewMode,
    currentSessionId,
    setCurrentSessionId,
    activeSessionId: currentSessionId,

    // 会话
    sessions,
    setSessions,
    agentIndicatorMap,
    unviewedCompletedSessionIds,
    agentDraftIds: new Set<string>(),
    pinnedAgentSessionTrees,
    agentProjectGroups,
    agentSessionGroups,
    archivedAgentSessionCount,
    handleSelectAgentSession,
    handleAgentRename,
    handleTogglePinAgent,
    handleToggleArchiveAgent,
    handleRequestDelete,
    handleConfirmDelete,
    handleRequestMove,
    handleMoveSession,
    moveTargetId,
    handleToggleDelegationParent,
    expandedDelegationParentIds,
    pendingDeleteId,
    setPendingDeleteId,
    setMoveTargetId,

    // 项目
    workspaces,
    currentWorkspaceId,
    sortedWorkspaces,
    workspaceSortMode,
    handleCycleWorkspaceSort,
    workspaceNameMap,
    createAgentSessionInWorkspace,
    handleNewAgentSession,
    handleSelectProject,
    handleToggleProjectCollapse,
    collapsedWorkspaceIds,
    expandedExtraCountMap,
    handleShowMoreSessions,
    handleCollapseExtraSessions,
    handleStartCreateProject,
    handleCreateProject,
    handleCreateProjectKeyDown,
    creatingProject,
    setCreatingProject,
    newProjectName,
    setNewProjectName,
    newProjectInputRef,
    handleWorkspaceRename,

    // 新建会话按钮
    relativeTimeNow,
    userProfile,
    setSearchDialogOpen,

    // rail
    railRecentItems,
  }
}

export type SidebarModel = ReturnType<typeof useLeftSidebar>
