/**
 * PermissionModeSelector — Agent 权限模式切换器
 *
 * 集成在 AgentView 输入工具栏中。点击按钮展开弹层，明确选择目标模式后才切换
 * （自动审批 / 计划模式 / 完全自动），避免单击循环误触。
 * 每个会话独立维护自己的权限模式，切换走会话级持久化。
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import { toast } from 'sonner'
import { Zap, Compass, Map as MapIcon } from 'lucide-react'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { Button } from '@/components/ui/button'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import { agentPermissionModeMapAtom, agentDefaultPermissionModeAtom, sessionPersistedPermissionModeAtom, sessionExistsAtom, agentPlanModeSessionsAtom, agentWorkspacesAtom, agentSessionsAtom } from '@/atoms/agent-atoms'
import type { ProferPermissionMode } from '@profer/shared'
import { buildPermissionModeMenu, resolveSelectorPermissionMode, PROFER_PERMISSION_MODE_CONFIG } from '@profer/shared'
import { remoteStoreAtom } from '@/atoms/remote-store-atoms'
import { selectRemoteSession, reduceRemoteStore } from '@/pocket/remote-store'
import { agentSessionPresetMapAtom, workspacePresetsAtom, presetOf } from '@/atoms/agent-preset-atoms'
import { updatePlanModeSessionSet } from '@/lib/agent-plan-mode'
import { mergeAuthoritativeAgentSession } from '@/lib/agent-session-settings'
import { isSessionRevisionConflict, useSessionSettingMutation } from '@/lib/use-session-setting-mutation'
import { cn } from '@/lib/utils'

const MODE_ICONS: Record<ProferPermissionMode, React.ComponentType<{ className?: string }>> = {
  auto: Compass,
  bypassPermissions: Zap,
  plan: MapIcon,
}

interface PermissionModeSelectorProps {
  sessionId: string
}

export function PermissionModeSelector({ sessionId }: PermissionModeSelectorProps): React.ReactElement | null {
  const [modeMap, setModeMap] = useAtom(agentPermissionModeMapAtom)
  const setPlanModeSessions = useSetAtom(agentPlanModeSessionsAtom)
  const defaultMode = useAtomValue(agentDefaultPermissionModeAtom)
  const setAgentSessions = useSetAtom(agentSessionsAtom)
  const remoteStore = useAtomValue(remoteStoreAtom)
  const setRemoteStore = useSetAtom(remoteStoreAtom)
  const { pending, mutate } = useSessionSettingMutation()
  const workspaces = useAtomValue(agentWorkspacesAtom)
  const [open, setOpen] = React.useState(false)
  const sessionPresetMap = useAtomValue(agentSessionPresetMapAtom)
  const remoteSession = selectRemoteSession(remoteStore, sessionId)
  const sessionMeta = remoteSession
  const workspaceSlug = sessionMeta?.workspaceId
    ? workspaces.find((workspace) => workspace.id === sessionMeta.workspaceId)?.slug
    : undefined
  const presetId = sessionPresetMap.get(sessionId) ?? sessionMeta?.presetId
  const presets = useAtomValue(workspacePresetsAtom(workspaceSlug))
  const presetPermissionMode = presetOf(presets, presetId)?.permissionMode
  const setPresets = useSetAtom(workspacePresetsAtom(workspaceSlug))
  React.useEffect(() => {
    void window.electronAPI.listAgentPresets(workspaceSlug)
      .then(setPresets)
      .catch(() => {})
  }, [workspaceSlug, setPresets])
  const persistedSessionMode = useAtomValue(sessionPersistedPermissionModeAtom(sessionId))
  const authoritativeSessionMode = remoteSession?.permissionMode ?? persistedSessionMode
  const optimisticModeRef = React.useRef<{ sequence: number; mode: ProferPermissionMode } | null>(null)
  const mode = optimisticModeRef.current?.mode ?? resolveSelectorPermissionMode(
    undefined,
    authoritativeSessionMode ?? modeMap.get(sessionId) ?? defaultMode,
  )
  const sessionExistsInList = useAtomValue(sessionExistsAtom(sessionId)) || remoteSession !== undefined
  // 初始化 + 真源同步：如果当前 session 不在 Map 中，按以下优先级读回：
  // 1. session meta.permissionMode（每个 tab 独立持久化，重启恢复各自的值）
  // 2. 默认完全自动模式
  // 注意：只写入当前 session，不回写到 agentDefaultPermissionModeAtom，避免跨会话污染。
  // 另外：会话 meta（agentSessionsAtom）每次经 SESSION_UPDATED / permission_mode_changed / 列表刷新
  // 变化后，这里会把 modeMap 缓存收敛到持久化值——否则 modeMap 一旦被首次初始化写入就永远不回读 meta，
  // 其它设备（桌面）修改后 pocket 图标仍停留在旧值，或重挂载后仍显示陈旧缓存。
  // 未加载 meta（persistedSessionMode === undefined）时保持旧行为：仅在 map 缺失该会话时写入默认值，
  // 避免乐观更新（selectMode 已把目标写入 map、meta 尚未回包）被默认值覆盖。
  React.useEffect(() => {
    if (!sessionExistsInList) return
    const optimistic = optimisticModeRef.current
    if (optimistic) {
      if (authoritativeSessionMode === optimistic.mode) optimisticModeRef.current = null
      else if (pending) return
      else return
    }

    setModeMap((prev: Map<string, ProferPermissionMode>) => {
      const current = prev.get(sessionId)
      if (authoritativeSessionMode === undefined) {
        if (current !== undefined) return prev
        const next = new Map(prev)
        next.set(sessionId, defaultMode)
        return next
      }
      if (current === authoritativeSessionMode) return prev
      const next = new Map(prev)
      next.set(sessionId, authoritativeSessionMode)
      return next
    })
  }, [sessionId, authoritativeSessionMode, sessionExistsInList, defaultMode, pending, setModeMap])

  /** 切换到指定模式：预设上限只用于显示当前策略，不禁用用户的三项显式选择。 */
  const selectMode = React.useCallback(async (nextMode: ProferPermissionMode) => {
    const prevMode = mode
    if (pending) return
    if (nextMode === prevMode) {
      setOpen(false)
      requestAnimationFrame(() => document.querySelector<HTMLElement>('.ProseMirror')?.focus())
      return
    }
    setOpen(false)
    const requestSequence = (optimisticModeRef.current?.sequence ?? 0) + 1
    optimisticModeRef.current = { sequence: requestSequence, mode: nextMode }
    // 临时状态只用于保持交互响应；成功后以服务端返回的权威 projection 收敛。
    setModeMap((prev: Map<string, ProferPermissionMode>) => {
      const next = new Map(prev)
      next.set(sessionId, nextMode)
      return next
    })
    setPlanModeSessions((prev: Set<string>) =>
      updatePlanModeSessionSet(prev, sessionId, nextMode === 'plan')
    )

    void mutate({
      session: remoteSession,
      execute: (expectedRevision) => window.electronAPI.updateSessionPermissionMode(sessionId, nextMode, expectedRevision),
      applyAuthoritative: (updated) => {
        if (requestSequence !== optimisticModeRef.current?.sequence) return
        const authoritativeMode = updated.permissionMode ?? nextMode
        optimisticModeRef.current = authoritativeMode === nextMode ? null : { sequence: requestSequence, mode: authoritativeMode }
        setModeMap((prev: Map<string, ProferPermissionMode>) => {
          const next = new Map(prev)
          next.set(sessionId, authoritativeMode)
          return next
        })
        setPlanModeSessions((prev: Set<string>) => updatePlanModeSessionSet(prev, sessionId, authoritativeMode === 'plan'))
        setAgentSessions((previous) => mergeAuthoritativeAgentSession(previous, updated))
        setRemoteStore((previous) => reduceRemoteStore(previous, { type: 'session_snapshot_upsert', session: updated }))
      },
      rollback: () => {
        if (requestSequence !== optimisticModeRef.current?.sequence) return
        optimisticModeRef.current = null
        setModeMap((prev: Map<string, ProferPermissionMode>) => {
          const next = new Map(prev)
          next.set(sessionId, prevMode)
          return next
        })
        setPlanModeSessions((prev: Set<string>) => updatePlanModeSessionSet(prev, sessionId, prevMode === 'plan'))
      },
      refresh: async () => {
        const sessions = await window.electronAPI.listAgentSessions()
        return sessions.find((session) => session.id === sessionId)
      },
      onError: (error) => {
        if (!isSessionRevisionConflict(error)) {
          const message = error instanceof Error ? error.message : '切换权限模式失败'
          toast.error('切换权限模式失败', { description: message })
        }
      },
    })

    requestAnimationFrame(() => document.querySelector<HTMLElement>('.ProseMirror')?.focus())
  }, [mode, presetPermissionMode, sessionId, setAgentSessions, setModeMap, setPlanModeSessions, setRemoteStore, pending])

  const menuEntries = buildPermissionModeMenu(undefined)
  const config = PROFER_PERMISSION_MODE_CONFIG[mode]
  const Icon = MODE_ICONS[mode]

  const menu = (
    <div className="flex flex-col py-0.5">
      {menuEntries.map((entry) => {
        const candidate = entry.mode
        const ItemIcon = MODE_ICONS[candidate]
        return (
          <button
            key={candidate}
            type="button"
            onClick={() => { void selectMode(candidate) }}
            disabled={pending}
            aria-label={entry.label}
            aria-current={candidate === mode}
            className={cn(
              'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-xs transition-colors hover:bg-accent',
              candidate === mode && 'bg-accent font-medium',
            )}
          >
            <ItemIcon className="size-4 shrink-0 text-foreground/70" />
            <span className="flex-1 text-left">{entry.label}</span>
            {candidate === mode && <span className="text-primary">✓</span>}
          </button>
        )
      })}
    </div>
  )

  // pocket 无 AgentComposerToolTrigger（不存在 components/ai-elements/composer/ 目录），
  // 外壳继续使用 pocket 既有的 Button + Tooltip；PopoverAnchor 走与 PresetSelector 相同的
  // TooltipTrigger(asChild) > PopoverAnchor(asChild) > Button 嵌套 Slot 结构。
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <TooltipProvider delayDuration={300}>
        <Tooltip>
          <TooltipTrigger asChild>
            <PopoverAnchor asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={config.label}
                onClick={() => setOpen((value) => !value)}
                className="size-[36px] rounded-full text-foreground/60 hover:text-foreground"
              >
                <Icon className="size-5" />
              </Button>
            </PopoverAnchor>
          </TooltipTrigger>
          <TooltipContent side="bottom" className="max-w-[200px]">
            <p className="font-medium">{config.label}</p>
            <p className="text-xs text-muted-foreground mt-0.5">{config.description}</p>
            <p className="text-xs text-muted-foreground mt-1">点击选择模式</p>
            {presetPermissionMode && <p className="text-xs text-muted-foreground mt-1">预设显示上限：{PROFER_PERMISSION_MODE_CONFIG[presetPermissionMode].label}</p>}
          </TooltipContent>
        </Tooltip>
      </TooltipProvider>
      <PopoverContent
        side="top"
        align="center"
        sideOffset={8}
        className="w-40 p-1"
        onOpenAutoFocus={(event) => event.preventDefault()}
        onCloseAutoFocus={(event) => event.preventDefault()}
      >
        {menu}
      </PopoverContent>
    </Popover>
  )
}
