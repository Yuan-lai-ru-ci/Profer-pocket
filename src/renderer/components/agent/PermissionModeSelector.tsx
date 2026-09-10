/**
 * PermissionModeSelector — Agent 权限模式切换器
 *
 * 集成在 AgentView 输入工具栏中。点击按钮展开弹层，明确选择目标模式后才切换
 * （自动审批 / 计划模式 / 完全自动），避免单击循环误触。
 * 每个会话独立维护自己的权限模式，切换走会话级持久化。
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import { Zap, Compass, Map as MapIcon } from 'lucide-react'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { Button } from '@/components/ui/button'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import { agentPermissionModeMapAtom, agentDefaultPermissionModeAtom, sessionPersistedPermissionModeAtom, sessionExistsAtom, agentPlanModeSessionsAtom } from '@/atoms/agent-atoms'
import type { ProferPermissionMode } from '@profer/shared'
import { PROFER_PERMISSION_MODE_CONFIG, PROFER_PERMISSION_MODE_ORDER } from '@profer/shared'
import { updatePlanModeSessionSet } from '@/lib/agent-plan-mode'
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
  const persistedSessionMode = useAtomValue(sessionPersistedPermissionModeAtom(sessionId))
  const mode = modeMap.get(sessionId) ?? persistedSessionMode ?? defaultMode
  const sessionExistsInList = useAtomValue(sessionExistsAtom(sessionId))
  const [open, setOpen] = React.useState(false)

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

    setModeMap((prev: Map<string, ProferPermissionMode>) => {
      const current = prev.get(sessionId)
      if (persistedSessionMode === undefined) {
        if (current !== undefined) return prev
        const next = new Map(prev)
        next.set(sessionId, defaultMode)
        return next
      }
      if (current === persistedSessionMode) return prev
      const next = new Map(prev)
      next.set(sessionId, persistedSessionMode)
      return next
    })
  }, [sessionId, persistedSessionMode, sessionExistsInList, defaultMode, setModeMap])

  /** 切换到指定模式（弹层选择后触发；失败时回滚 UI/后端一致） */
  const selectMode = React.useCallback(async (nextMode: ProferPermissionMode) => {
    const prevMode = mode
    if (nextMode === prevMode) {
      setOpen(false)
      requestAnimationFrame(() => document.querySelector<HTMLElement>('.ProseMirror')?.focus())
      return
    }
    setOpen(false)

    // 乐观更新当前 session 的模式
    setModeMap((prev: Map<string, ProferPermissionMode>) => {
      const next = new Map(prev)
      next.set(sessionId, nextMode)
      return next
    })
    setPlanModeSessions((prev: Set<string>) =>
      updatePlanModeSessionSet(prev, sessionId, nextMode === 'plan')
    )

    // 热切换运行中的当前 session；失败时回滚 modeMap 保持 UI/后端一致
    try {
      await window.electronAPI.updateSessionPermissionMode(sessionId, nextMode)
    } catch (error) {
      console.error('[PermissionModeSelector] 运行中切换权限模式失败，回滚 UI:', error)
      setModeMap((prev: Map<string, ProferPermissionMode>) => {
        const next = new Map(prev)
        next.set(sessionId, prevMode)
        return next
      })
      setPlanModeSessions((prev: Set<string>) =>
        updatePlanModeSessionSet(prev, sessionId, prevMode === 'plan')
      )
    } finally {
      requestAnimationFrame(() => document.querySelector<HTMLElement>('.ProseMirror')?.focus())
    }
  }, [mode, sessionId, setModeMap, setPlanModeSessions])

  const config = PROFER_PERMISSION_MODE_CONFIG[mode]
  const Icon = MODE_ICONS[mode]

  const menu = (
    <div className="flex flex-col py-0.5">
      {PROFER_PERMISSION_MODE_ORDER.map((candidate) => {
        const itemConfig = PROFER_PERMISSION_MODE_CONFIG[candidate]
        const ItemIcon = MODE_ICONS[candidate]
        return (
          <button
            key={candidate}
            type="button"
            onClick={() => { void selectMode(candidate) }}
            aria-label={itemConfig.label}
            aria-current={candidate === mode}
            className={cn(
              'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-xs transition-colors hover:bg-accent',
              candidate === mode && 'bg-accent font-medium',
            )}
          >
            <ItemIcon className="size-4 shrink-0 text-foreground/70" />
            <span className="flex-1 text-left">{itemConfig.label}</span>
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
