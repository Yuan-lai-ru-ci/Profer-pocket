/**
 * PresetSelector — Agent 预设选择器
 *
 * 集成在 AgentView 输入工具栏中，展示当前会话绑定的预设（岗位），
 * 点击展开全部预设（内置 standard/code/minimal + 未来自定义），切换即持久化。
 *
 * 心智模型：模型=大脑、Skill=手册、预设=岗位（对齐 DeepSeek Harness）。
 */

import * as React from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
import { BriefcaseBusiness } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { agentSessionPresetMapAtom, workspacePresetsAtom, presetOf } from '@/atoms/agent-preset-atoms'
import { mergeAuthoritativeAgentSession } from '@/lib/agent-session-settings'
import { useSessionSettingMutation } from '@/lib/use-session-setting-mutation'
import { agentSessionsAtom, workspaceCapabilitiesVersionAtom } from '@/atoms/agent-atoms'
import { remoteStoreAtom } from '@/atoms/remote-store-atoms'
import { reduceRemoteStore, selectRemoteSession } from '@/pocket/remote-store'
import { DEFAULT_PRESET_ID } from '@profer/shared'
import type { AgentEffort, ProferPermissionMode } from '@profer/shared'
import { cn } from '@/lib/utils'
import { resolvePresetCompactMode, resolvePresetListClassName } from './pocket-ui-switches'

/** 预设特性 badge 的中文短标签 */
const EFFORT_LABEL: Record<AgentEffort, string> = { low: '低', medium: '中', high: '高', max: '最大' }
const PERMISSION_LABEL: Record<ProferPermissionMode, string> = { auto: '自动审批', bypassPermissions: '完全自动', plan: '计划' }

/** 「极简」开关（紧凑模式）持久化 key：记住用户上次的显示偏好，跨会话/重启生效 */
const COMPACT_MODE_STORAGE_KEY = 'profer-preset-selector-compact'

function readStoredCompactMode(): boolean {
  try {
    return localStorage.getItem(COMPACT_MODE_STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

function persistCompactMode(on: boolean): void {
  try {
    localStorage.setItem(COMPACT_MODE_STORAGE_KEY, on ? '1' : '0')
  } catch { /* localStorage 不可用时静默降级为不持久化 */ }
}

interface PresetSelectorProps {
  sessionId: string
  /** 会话 meta 上持久化的预设 ID（跨重启真源） */
  persistedPresetId?: string
  /** 会话所属工作区 slug（预设为工作区级配置） */
  workspaceSlug?: string
  /** 平板远程模式：恒定极简紧凑 + 弹层限高内滚（桌面端不受影响） */
  pocketMode?: boolean
}

export function PresetSelector({ sessionId, persistedPresetId, workspaceSlug, pocketMode = false }: PresetSelectorProps): React.ReactElement | null {
  const [presets, setPresets] = useAtom(workspacePresetsAtom(workspaceSlug))
  const [presetMap, setPresetMap] = useAtom(agentSessionPresetMapAtom)
  const setAgentSessions = useSetAtom(agentSessionsAtom)
  const remoteStore = useAtomValue(remoteStoreAtom)
  const remoteSession = selectRemoteSession(remoteStore, sessionId)
  const setRemoteStore = useSetAtom(remoteStoreAtom)
  const { pending, mutate } = useSessionSettingMutation()
  // 记住上次选择的「极简」紧凑显示偏好（localStorage 惰性初始化）
  const [compactMode, setCompactMode] = React.useState<boolean>(readStoredCompactMode)
  const toggleCompactMode = React.useCallback((on: boolean) => {
    setCompactMode(on)
    persistCompactMode(on)
  }, [])
  // 平板：恒定极简紧凑（用户要求预设二级菜单直接等同桌面端开启「极简」后的效果），
  // 仍不写回 localStorage，避免平板切换影响桌面端偏好。
  const effectiveCompactMode = resolvePresetCompactMode(pocketMode, compactMode)
  // 与 Skills 相同的刷新信号：技能页增删改/导入预设后，这里立即重拉最新列表
  const capabilitiesVersion = useAtomValue(workspaceCapabilitiesVersionAtom)

  React.useEffect(() => {
    void window.electronAPI.listAgentPresets(workspaceSlug)
      .then(setPresets)
      .catch((error) => console.error('[PresetSelector] 加载工作区预设失败:', error))
  }, [workspaceSlug, capabilitiesVersion, setPresets])

  const effectiveId = presetMap.get(sessionId) ?? persistedPresetId ?? DEFAULT_PRESET_ID
  const current = presetOf(presets, effectiveId)

  const selectPreset = React.useCallback(async (presetId: string) => {
    if (pending) return
    const prevId = effectiveId
    setPresetMap((prev: Map<string, string>) => {
      const next = new Map(prev)
      next.set(sessionId, presetId)
      return next
    })
    void mutate({
      session: remoteSession,
      execute: (expectedRevision) => window.electronAPI.updateAgentSessionPreset(sessionId, presetId, expectedRevision),
      applyAuthoritative: (updated) => {
        if (typeof updated.revision !== 'number') throw new Error('服务端未返回完整的权威会话投影')
        setAgentSessions((prev) => mergeAuthoritativeAgentSession(prev, updated))
        setRemoteStore((previous) => reduceRemoteStore(previous, { type: 'session_snapshot_upsert', session: updated }))
      },
      rollback: () => {
        setPresetMap((prev: Map<string, string>) => {
          const next = new Map(prev)
          next.set(sessionId, prevId)
          return next
        })
      },
      onError: (error) => console.error('[PresetSelector] 切换预设失败，回滚 UI:', error),
    })

  }, [effectiveId, mutate, remoteSession, sessionId, setAgentSessions, setPresetMap, setRemoteStore])

  // meta 真源同步（与 PermissionModeSelector 同策略）：其它设备（桌面）修改会话预设后，
  // SESSION_UPDATED → agentSessionsAtom → persistedPresetId 变化时把本地乐观缓存收敛到 meta，
  // 避免 agentSessionPresetMapAtom 的陈旧值长期盖过会话元数据（“预设切换后双端不同步”）。
  // meta 尚未携带预设（persistedPresetId 为空）时不覆盖，保留本地乐观更新/回滚语义。
  React.useEffect(() => {
    if (!persistedPresetId) return
    setPresetMap((prev: Map<string, string>) => {
      const current = prev.get(sessionId)
      if (current === persistedPresetId) return prev
      const next = new Map(prev)
      next.set(sessionId, persistedPresetId)
      return next
    })
  }, [persistedPresetId, sessionId, setPresetMap])

  if (presets.length === 0) return null

  return (
    <TooltipProvider delayDuration={300}>
      <Popover
        onOpenChange={(open) => {
          // 打开时刷新，保证技能页导入/编辑后的最新预设可见
          if (open) {
            void window.electronAPI.listAgentPresets(workspaceSlug)
              .then(setPresets)
              .catch((error) => console.error('[PresetSelector] 刷新工作区预设失败:', error))
          }
        }}
      >
        <Tooltip>
          <TooltipTrigger asChild>
            <PopoverTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`预设：${current?.name ?? '标准'}`}
                className="size-[36px] rounded-full text-foreground/60 hover:text-foreground"
              >
                <BriefcaseBusiness className="size-5" />
              </Button>
            </PopoverTrigger>
          </TooltipTrigger>
          <TooltipContent side="bottom" className="max-w-[220px]">
            <p className="font-medium">预设 · {current?.name ?? '标准'}</p>
          </TooltipContent>
        </Tooltip>
        <PopoverContent
          align="start"
          side="top"
          // 平板弹层可能非常高：给 Radix 留出碰撞内边距，保证顶部条目不被裁到屏幕外。
          collisionPadding={pocketMode ? 12 : 0}
          className="w-72 p-1.5"
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center justify-between px-2 py-1">
              <span className="text-xs font-medium text-foreground/60">Agent 预设（岗位）</span>
              {/* 平板恒定极简，开关无意义且不应写回本地偏好 → 不展示 */}
              {!pocketMode && (
                <label className="flex cursor-pointer items-center gap-1.5 text-[11px] text-foreground/55 select-none">
                  <span>极简</span>
                  <Switch checked={compactMode} onCheckedChange={toggleCompactMode} className="scale-90" />
                </label>
              )}
            </div>
            <div className={resolvePresetListClassName(pocketMode)}>
              {presets.map((preset) => (
                <button
                  key={preset.id}
                  type="button"
                  onClick={() => { selectPreset(preset.id); requestAnimationFrame(() => document.querySelector<HTMLElement>('.ProseMirror')?.focus()) }}
                  className={cn(
                    'flex flex-col gap-0.5 rounded-md px-2 py-1.5 text-left transition-colors hover:bg-muted',
                    preset.id === effectiveId && 'bg-muted',
                  )}
                >
                  <span className="flex items-center gap-1.5 text-xs font-medium">
                    <span className={cn('w-4 text-center', preset.id === effectiveId ? 'text-primary' : 'text-transparent')}>✓</span>
                    {preset.name}
                    {preset.isBuiltin && <span className="rounded bg-muted px-1 py-px text-[10px] font-normal text-foreground/50">内置</span>}
                    {preset.effort && <span className="rounded bg-muted px-1 py-px text-[10px] font-normal text-foreground/50">强度·{EFFORT_LABEL[preset.effort] ?? preset.effort}</span>}
                    {preset.permissionMode && <span className="rounded bg-muted px-1 py-px text-[10px] font-normal text-foreground/50">权限·{PERMISSION_LABEL[preset.permissionMode] ?? preset.permissionMode}</span>}
                  </span>
                  {!effectiveCompactMode && (
                    <span className="pl-5.5 text-[11px] leading-4 text-foreground/55">{preset.description}</span>
                  )}
                </button>
              ))}
            </div>
          </div>
        </PopoverContent>
      </Popover>
    </TooltipProvider>
  )
}
