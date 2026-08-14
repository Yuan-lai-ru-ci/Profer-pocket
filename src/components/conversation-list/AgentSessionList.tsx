/**
 * AgentSessionList.tsx — 会话列表组件（移动端瘦客户端左侧会话栏）
 *
 * 职责：展示 sessionsAtom（置顶优先 + 更新时间倒序 + 归档折叠）、切换当前会话、
 * 新建会话，并通过 useConnection().client 触发 rename/pin/archive/delete 等动作。
 *
 * 排序语义（对齐桌面 left-sidebar/use-left-sidebar.ts）：
 *  - 可见会话 = 排除 draft（isVisibleAgentSession 语义：!draft）
 *  - 置顶区：pinned && !archived，按 updatedAt 降序
 *  - 活跃区：!pinned && !archived，按 updatedAt 降序
 *  - 归档区：archived，按 updatedAt 降序，默认折叠，点击「已归档 (N)」展开/收起
 *
 * 加载态：sessionsLoadedAtom 为 false 时显示骨架加载；为 true 且列表为空时显示空态。
 *
 * 工作区切换：移动端首版只做会话（顶部标题栏保留插槽 + TODO 注释），
 * workspacesAtom / currentWorkspaceIdAtom 已就绪但暂不在本组件接入切换 UI。
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import { Plus, Archive, ChevronRight } from 'lucide-react'
import type { AgentSessionMeta } from '@profer/shared'
import {
  sessionsAtom,
  currentSessionIdAtom,
  workspacesAtom,
  currentWorkspaceIdAtom,
  sessionsLoadedAtom,
} from '@/atoms/session'
import { useConnection } from '@/hooks/useConnection'
import { AgentSessionItem } from './AgentSessionItem'

/** 展示字段兜底后的会话（移动端仅依赖 title/pinned/archived/draft/updatedAt） */
type DisplaySession = Pick<AgentSessionMeta, 'id' | 'title' | 'pinned' | 'archived' | 'draft' | 'createdAt' | 'updatedAt'>

function normalize(s: AgentSessionMeta): DisplaySession {
  return {
    id: s.id,
    title: s.title,
    pinned: s.pinned ?? false,
    archived: s.archived ?? false,
    draft: s.draft ?? false,
    createdAt: s.createdAt ?? 0,
    updatedAt: s.updatedAt ?? s.createdAt ?? 0,
  }
}

/** 按 updatedAt 降序（最新在前），同时间按 createdAt 降序兜底 */
function byUpdatedAtDesc(a: DisplaySession, b: DisplaySession): number {
  return b.updatedAt - a.updatedAt || b.createdAt - a.createdAt
}

export function AgentSessionList(): React.ReactElement {
  const sessions = useAtomValue(sessionsAtom)
  const loaded = useAtomValue(sessionsLoadedAtom)
  const currentId = useAtomValue(currentSessionIdAtom)
  const setCurrentId = useSetAtom(currentSessionIdAtom)

  // workspaces / currentWorkspaceId 仅读取占位，首版不做切换 UI（见文件头 TODO）
  const workspaces = useAtomValue(workspacesAtom)
  void workspaces
  const currentWorkspaceId = useAtomValue(currentWorkspaceIdAtom)
  void currentWorkspaceId

  const { client } = useConnection()

  const [expandedArchive, setExpandedArchive] = React.useState(false)
  const [creating, setCreating] = React.useState(false)
  const [busyIds, setBusyIds] = React.useState<Set<string>>(new Set())

  // ===== 分组 + 排序 =====
  const { pinned, active, archived } = React.useMemo(() => {
    const visible = sessions.map(normalize).filter((s) => !s.draft)
    const p = visible.filter((s) => s.pinned && !s.archived).sort(byUpdatedAtDesc)
    const a = visible.filter((s) => !s.pinned && !s.archived).sort(byUpdatedAtDesc)
    const ar = visible.filter((s) => s.archived).sort(byUpdatedAtDesc)
    return { pinned: p, active: a, archived: ar }
  }, [sessions])

  // 切换会话
  const handleSelect = React.useCallback(
    (id: string) => {
      setCurrentId(id)
    },
    [setCurrentId],
  )

  // 标记某个会话处于异步操作中（避免重复触发）
  const markBusy = React.useCallback((id: string, busy: boolean) => {
    setBusyIds((prev) => {
      const next = new Set(prev)
      if (busy) next.add(id)
      else next.delete(id)
      return next
    })
  }, [])

  // ===== ws 动作（复用 useConnection().client，不直接改 atoms） =====
  const runAction = React.useCallback(
    async (id: string, action: (sid: string) => Promise<unknown>) => {
      if (!client) return
      if (busyIds.has(id)) return
      markBusy(id, true)
      try {
        await action(id)
      } catch (e) {
        console.error('[AgentSessionList] 会话操作失败', e)
      } finally {
        markBusy(id, false)
      }
    },
    [client, busyIds, markBusy],
  )

  const handleTogglePin = React.useCallback(
    (id: string) => {
      void runAction(id, (sid) => client!.toggleSessionPin(sid))
    },
    [client, runAction],
  )

  const handleToggleArchive = React.useCallback(
    (id: string) => {
      void runAction(id, (sid) => client!.toggleSessionArchive(sid))
    },
    [client, runAction],
  )

  const handleRename = React.useCallback(
    (id: string, title: string) => {
      void runAction(id, (sid) => client!.renameSession(sid, title))
    },
    [client, runAction],
  )

  const handleDelete = React.useCallback(
    (id: string) => {
      if (!window.confirm('确定删除该会话？此操作不可撤销。')) return
      void runAction(id, async (sid) => {
        await client!.deleteSession(sid)
        // 删除当前选中会话时清空选中态
        if (currentId === sid) setCurrentId(null)
      })
    },
    [client, runAction, currentId, setCurrentId],
  )

  // 新建会话
  const handleCreate = React.useCallback(async () => {
    if (!client || creating) return
    setCreating(true)
    try {
      // 首版不传 channelId/modelId/workspaceId，由 remote-service 继承默认渠道/模型
      await client.createSession({})
    } catch (e) {
      console.error('[AgentSessionList] 新建会话失败', e)
    } finally {
      setCreating(false)
    }
  }, [client, creating])

  const handlers = React.useMemo(
    () => ({ onSelect: handleSelect, onTogglePin: handleTogglePin, onToggleArchive: handleToggleArchive, onRename: handleRename, onDelete: handleDelete }),
    [handleSelect, handleTogglePin, handleToggleArchive, handleRename, handleDelete],
  )

  const renderItem = (s: DisplaySession, archivedFlag: boolean): React.ReactElement => (
    <AgentSessionItem
      key={s.id}
      session={s as AgentSessionMeta}
      active={s.id === currentId}
      archived={archivedFlag}
      handlers={handlers}
    />
  )

  return (
    <div className="flex h-full flex-col text-foreground" style={{ backgroundColor: 'hsl(var(--sidebar-surface))' }}>
      {/* 顶部标题栏：移动端首版仅会话；工作区切换留 TODO */}
      <header className="flex shrink-0 items-center justify-between border-b border-border px-3 pb-2 pt-3 pl-4">
        <span className="text-sm font-semibold">会话</span>
        {/* TODO: 工作区切换 —— workspacesAtom/currentWorkspaceIdAtom 已就绪，
            后续接入一个下拉/抽屉来 setCurrentWorkspaceId + client.moveSessionToWorkspace */}
        <button
          type="button"
          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40"
          onClick={handleCreate}
          disabled={!client || creating}
          aria-label="新建会话"
        >
          <Plus className="h-4 w-4" />
        </button>
      </header>

      <div className="flex-1 overflow-y-auto p-1.5">
        {!loaded ? (
          <div className="flex flex-col items-center gap-2 px-4 py-10 text-[13px] text-muted-foreground/50">
            <span className="h-[18px] w-[18px] animate-spin rounded-full border-2 border-border border-t-foreground/40" />
            <span>正在加载会话…</span>
          </div>
        ) : pinned.length === 0 && active.length === 0 && archived.length === 0 ? (
          <div className="flex flex-col items-center gap-2 px-4 py-10 text-center text-[13px] text-muted-foreground/50">
            <p className="text-sm text-muted-foreground">暂无会话</p>
            <p className="text-xs">点击右上角「+」新建一个会话</p>
          </div>
        ) : (
          <>
            {pinned.length > 0 && (
              <section className="mb-2">
                <div className="px-3 py-1 text-[11px] uppercase tracking-wide text-muted-foreground/40">置顶</div>
                {pinned.map((s) => renderItem(s, false))}
              </section>
            )}

            {active.length > 0 && (
              <section className="mb-2">
                {pinned.length > 0 && (
                  <div className="px-3 py-1 text-[11px] uppercase tracking-wide text-muted-foreground/40">会话</div>
                )}
                {active.map((s) => renderItem(s, false))}
              </section>
            )}

            {archived.length > 0 && (
              <section className="mb-2">
                <button
                  type="button"
                  className="flex w-full items-center gap-1.5 px-3 py-1.5 text-xs text-muted-foreground/50 transition-colors hover:text-muted-foreground"
                  onClick={() => setExpandedArchive((v) => !v)}
                  aria-expanded={expandedArchive}
                >
                  <Archive className="h-3 w-3 shrink-0" />
                  <span>已归档 ({archived.length})</span>
                  <ChevronRight className={`h-3 w-3 transition-transform ${expandedArchive ? 'rotate-90' : ''}`} />
                </button>
                {expandedArchive && archived.map((s) => renderItem(s, true))}
              </section>
            )}
          </>
        )}
      </div>
    </div>
  )
}
