/**
 * PermissionBanner — Agent 权限请求横幅
 *
 * 内联在 Agent 对话流底部，当有待处理的权限请求时显示。
 * 显示工具名、命令内容、危险等级，提供允许/拒绝/总是允许操作。
 * 支持队列模式：多个并发请求按 FIFO 逐个展示。
 *
 * 设计参考 Craft Agents OSS 的内联权限 UI。
 */

import * as React from 'react'
import { useAtom } from 'jotai'
import { Shield, ShieldAlert, Check, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { allPendingPermissionRequestsAtom } from '@/atoms/agent-atoms'
import {
  runDismissFlow,
  runSubmitFlow,
  useInteractionGuardWatch,
  type InteractionGuard,
} from '@/lib/interaction-guard'
import type { DangerLevel } from '@profer/shared'

/** 危险等级对应的图标颜色 */
const DANGER_ICON_STYLES: Record<DangerLevel, string> = {
  safe: 'text-green-500',
  normal: 'text-primary',
  dangerous: 'text-amber-500',
}

/** 解析工具显示名称（MCP 工具显示 server / tool） */
function formatToolName(toolName: string): string {
  const parts = toolName.split('__')
  if (parts[0] === 'mcp' && parts.length >= 3) {
    return `${parts[1]} / ${parts.slice(2).join('__')}`
  }
  return toolName
}

/** PermissionBanner 属性接口 */
interface PermissionBannerProps {
  sessionId: string
  onRequestStop: () => void
  /**
   * 交互守卫（仅移动端注入）：关闭/提交前先用主端快照判定请求是否仍待处理。
   * 未注入（桌面端）或判定失败时恒为 unknown → 完全保留原有行为。
   */
  interactionGuard?: InteractionGuard
}

export function PermissionBanner({ sessionId, onRequestStop, interactionGuard }: PermissionBannerProps): React.ReactElement | null {
  const [allRequests, setAllRequests] = useAtom(allPendingPermissionRequestsAtom)
  const requests = allRequests.get(sessionId) ?? []
  const [responding, setResponding] = React.useState(false)
  const respondRef = React.useRef<(behavior: 'allow' | 'deny', alwaysAllow?: boolean) => void>()

  const request = requests[0] ?? null
  const requestId = request?.requestId ?? null

  /** 本地移除某个请求（不动 run）：过期横幅收敛与提交成功共用。 */
  const removeRequest = React.useCallback((targetRequestId: string): void => {
    setAllRequests((prev) => {
      const current = prev.get(sessionId) ?? []
      const next = current.filter((r) => r.requestId !== targetRequestId)
      const map = new Map(prev)
      if (next.length === 0) map.delete(sessionId)
      else map.set(sessionId, next)
      return map
    })
  }, [sessionId, setAllRequests])

  // 另一端已作答时的低频收敛：判定为过期就本地移除横幅并提示，绝不触碰 run（R8-P0）。
  useInteractionGuardWatch({
    guard: interactionGuard,
    kind: 'permission',
    requestId,
    onResolved: (resolvedRequestId) => {
      removeRequest(resolvedRequestId)
      toast.info('该权限请求已在其它端处理', { description: '已自动收起横幅，未停止 Agent。' })
    },
  })

  // Enter 键快捷允许
  React.useEffect(() => {
    if (!request) return
    const handleKeyDown = (e: KeyboardEvent): void => {
      if (
        e.target instanceof HTMLInputElement ||
        e.target instanceof HTMLTextAreaElement ||
        (e.target instanceof HTMLElement && e.target.isContentEditable)
      ) return
      if (e.key === 'Enter') {
        e.preventDefault()
        respondRef.current?.('allow')
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [request?.requestId])

  /**
   * 关闭权限请求：本地先收起横幅，再按主端快照决定是否停止 Agent。
   *
   * R8-P0：仅当该 requestId **仍在主端待处理**（或无法判定）时才沿用「关闭并终止 Agent」语义；
   * 快照显示已被另一端处理时只做本地移除 + 轻提示，**不调用 onRequestStop**。
   */
  const handleDismiss = (): void => {
    const targetRequestId = requestId
    setAllRequests((prev) => {
      const map = new Map(prev)
      map.delete(sessionId)
      return map
    })
    if (!targetRequestId) {
      onRequestStop()
      return
    }
    void runDismissFlow({
      guard: interactionGuard,
      kind: 'permission',
      requestId: targetRequestId,
      requestStop: onRequestStop,
      notifyResolved: () => toast.info('该权限请求已在其它端处理', { description: '未停止 Agent。' }),
    })
  }

  if (!request) return null

  const iconColor = DANGER_ICON_STYLES[request.dangerLevel]
  const isDangerous = request.dangerLevel === 'dangerous'
  const IconComponent = isDangerous ? ShieldAlert : Shield

  /** 响应权限请求 */
  const respond = async (behavior: 'allow' | 'deny', alwaysAllow = false): Promise<void> => {
    if (responding) return
    setResponding(true)
    const targetRequestId = request.requestId

    try {
      // R8-P0：另一端已处理时不再回传（回传只会拿到「权限请求不存在或已处理」）。
      // 提交路径本身不会停止 Agent。
      await runSubmitFlow({
        guard: interactionGuard,
        kind: 'permission',
        requestId: targetRequestId,
        submit: async () => {
          await window.electronAPI.respondPermission({
            requestId: targetRequestId,
            behavior,
            alwaysAllow,
          })
          // 移除已响应的请求（FIFO 出队）
          removeRequest(targetRequestId)
        },
        onStale: () => {
          removeRequest(targetRequestId)
          toast.info('该权限请求已在其它端处理', { description: '无需重复提交。' })
        },
      })
    } catch (error) {
      console.error('[PermissionBanner] 响应失败:', error)
    } finally {
      setResponding(false)
    }
  }

  respondRef.current = respond

  return (
    <div
      className="mx-4 mb-3 rounded-xl bg-card shadow-lg overflow-hidden animate-in slide-in-from-bottom-2 duration-200"
    >
      {/* 头部 */}
      <div className="flex items-center justify-between px-3 py-2">
        <div className="flex items-center gap-2">
          <IconComponent className={`size-4 ${iconColor}`} />
          <span className="text-sm font-medium">
            {isDangerous ? '危险操作需要确认' : '需要确认'}
          </span>
          {requests.length > 1 && (
            <span className="text-xs text-muted-foreground">
              (+{requests.length - 1})
            </span>
          )}
        </div>
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-muted-foreground font-mono">
            {request.sdkDisplayName ?? formatToolName(request.toolName)}
          </span>
          <button
            type="button"
            className="size-5 flex items-center justify-center rounded-md text-muted-foreground/50 hover:text-foreground hover:bg-muted/60 transition-colors"
            onClick={handleDismiss}
            title="关闭并终止 Agent"
          >
            <X className="size-3.5" />
          </button>
        </div>
      </div>

      {/* 命令/操作内容 */}
      <div className="px-3 pb-2 space-y-1.5">
        {/* SDK 可读标题（优先展示，描述操作意图） */}
        {request.sdkTitle && (
          <p className="text-xs text-foreground">{request.sdkTitle}</p>
        )}
        {/* SDK 详细描述（与标题不同时才展示） */}
        {request.sdkDescription && request.sdkDescription !== request.sdkTitle && (
          <p className="text-xs text-muted-foreground">{request.sdkDescription}</p>
        )}
        {/* Bash 命令：始终展示代码块 */}
        {request.command ? (
          <pre className="text-xs font-mono bg-background/50 rounded px-2 py-1.5 overflow-x-auto whitespace-pre-wrap break-all max-h-[120px] overflow-y-auto">
            {request.command}
          </pre>
        ) : !request.sdkTitle && Object.keys(request.toolInput).length > 0 ? (
          <pre className="text-xs font-mono bg-background/50 rounded px-2 py-1.5 overflow-x-auto whitespace-pre-wrap break-all max-h-[120px] overflow-y-auto">
            {JSON.stringify(request.toolInput, null, 2)}
          </pre>
        ) : !request.sdkTitle ? (
          <p className="text-xs text-muted-foreground">
            {request.description}
          </p>
        ) : null}
      </div>

      {/* 操作按钮 */}
      <div className="flex items-center justify-end gap-1.5 px-3 pb-2.5">
        <span className="text-[10px] text-muted-foreground/40 mr-auto">
          Enter 允许
        </span>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => respond('deny')}
          disabled={responding}
          className="h-7 px-3 text-xs text-muted-foreground hover:text-destructive"
        >
          <X className="size-3 mr-1" />
          拒绝
        </Button>

        <Button
          variant="outline"
          size="sm"
          onClick={() => respond('allow', true)}
          disabled={responding}
          className="h-7 px-3 text-xs"
        >
          本次会话总是允许
        </Button>

        <Button
          variant="default"
          size="sm"
          onClick={() => respond('allow')}
          disabled={responding}
          className="h-7 px-3 text-xs"
        >
          <Check className="size-3 mr-1" />
          允许
        </Button>
      </div>
    </div>
  )
}
