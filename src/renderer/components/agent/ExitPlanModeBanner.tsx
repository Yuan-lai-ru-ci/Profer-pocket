/**
 * ExitPlanModeBanner — Agent ExitPlanMode 计划审批横幅
 *
 * 仿照 Claude Code 的计划审批 UI，提供 4 个选项：
 * 1. 批准并完全自动执行 — 切换到 bypassPermissions
 * 2. 批准并自动审批 — 切换到 auto
 * 3. 拒绝计划 — deny
 * 4. 提供反馈 — 自由输入修改意见
 *
 * 键盘：↑↓ 选择，Enter 确认，数字键快速选择。
 */

import * as React from 'react'
import { useAtom } from 'jotai'
import {
  Check,
  ShieldCheck,
  X,
  MessageSquare,
  Send,
  FileText,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { toast } from 'sonner'
import { allPendingExitPlanRequestsAtom } from '@/atoms/agent-atoms'
import { isEditableTarget } from '@/lib/navigation-controller'
import {
  runDismissFlow,
  runSubmitFlow,
  useInteractionGuardWatch,
  type InteractionGuard,
} from '@/lib/interaction-guard'
import type { ExitPlanModeAction, ExitPlanAllowedPrompt } from '@profer/shared'

/** 选项定义 */
interface PlanOption {
  action: ExitPlanModeAction
  label: string
  description: string
  icon: React.ReactNode
  variant: 'default' | 'secondary' | 'destructive'
}

const PLAN_OPTIONS: PlanOption[] = [
  {
    action: 'approve_auto',
    label: '批准并完全自动执行',
    description: '后续工具调用全部自动允许',
    icon: <Check className="size-3.5" />,
    variant: 'default',
  },
  {
    action: 'approve_edit',
    label: '批准并自动审批',
    description: '使用 SDK 自动审批器判断后续操作',
    icon: <ShieldCheck className="size-3.5" />,
    variant: 'secondary',
  },
  {
    action: 'deny',
    label: '拒绝计划',
    description: '直接拒绝，Agent 不会执行计划',
    icon: <X className="size-3.5" />,
    variant: 'destructive',
  },
  {
    action: 'feedback',
    label: '提供修改意见',
    description: '告诉 Agent 需要调整什么',
    icon: <MessageSquare className="size-3.5" />,
    variant: 'secondary',
  },
]

interface ExitPlanModeBannerProps {
  sessionId: string
  onRequestStop: () => void
  /**
   * 交互守卫（仅移动端注入）：关闭/提交前先用主端快照判定请求是否仍待处理。
   * 未注入（桌面端）或判定失败时恒为 unknown → 完全保留原有行为。
   */
  interactionGuard?: InteractionGuard
}

export function ExitPlanModeBanner({ sessionId, onRequestStop, interactionGuard }: ExitPlanModeBannerProps): React.ReactElement | null {
  const [allRequests, setAllRequests] = useAtom(allPendingExitPlanRequestsAtom)
  const requests = allRequests.get(sessionId) ?? []
  const [focusedIdx, setFocusedIdx] = React.useState(0)
  const [showFeedback, setShowFeedback] = React.useState(false)
  const [feedbackText, setFeedbackText] = React.useState('')
  const [submitting, setSubmitting] = React.useState(false)

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

  // 另一端已审批/已关闭时的低频收敛：判定为过期就本地移除横幅并提示，绝不触碰 run（R8-P0）。
  useInteractionGuardWatch({
    guard: interactionGuard,
    kind: 'exitPlan',
    requestId,
    onResolved: (resolvedRequestId) => {
      removeRequest(resolvedRequestId)
      toast.info('该计划审批已在其它端处理', { description: '已自动收起横幅，未停止 Agent。' })
    },
  })

  // ===== Refs：确保 keydown handler 始终读取最新值，消除闭包过期问题 =====
  const focusedIdxRef = React.useRef(focusedIdx)
  focusedIdxRef.current = focusedIdx
  const feedbackTextRef = React.useRef(feedbackText)
  feedbackTextRef.current = feedbackText
  const handleActionRef = React.useRef<((action: ExitPlanModeAction) => void) | null>(null)

  // 重置状态
  React.useEffect(() => {
    setFocusedIdx(0)
    setShowFeedback(false)
    setFeedbackText('')
  }, [request?.requestId])

  const handleAction = async (action: ExitPlanModeAction): Promise<void> => {
    if (submitting || !request) return
    setSubmitting(true)
    const targetRequestId = request.requestId
    try {
      // R8-P0：另一端已处理时不再回传（回传只会拿到「计划审批请求不存在或已处理」）。
      // 提交路径本身不会停止 Agent。
      await runSubmitFlow({
        guard: interactionGuard,
        kind: 'exitPlan',
        requestId: targetRequestId,
        submit: async () => {
          await window.electronAPI.respondExitPlanMode({
            requestId: targetRequestId,
            action,
            feedback: action === 'feedback' ? feedbackText.trim() : undefined,
          })
          // 从队列移除
          removeRequest(targetRequestId)
        },
        onStale: () => {
          removeRequest(targetRequestId)
          toast.info('该计划审批已在其它端处理', { description: '无需重复提交。' })
        },
      })
    } catch (error) {
      console.error('[ExitPlanModeBanner] 响应失败:', error)
    } finally {
      setSubmitting(false)
    }
  }

  handleActionRef.current = handleAction

  /**
   * 关闭计划审批：本地先收起横幅，再按主端快照决定是否停止 Agent。
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
      kind: 'exitPlan',
      requestId: targetRequestId,
      requestStop: onRequestStop,
      notifyResolved: () => toast.info('该计划审批已在其它端处理', { description: '未停止 Agent。' }),
    })
  }

  // 键盘导航：只在 requestId 变化时重建 handler，内部通过 ref 读取最新值
  React.useEffect(() => {
    if (!request) return

    const handleKeyDown = (e: KeyboardEvent): void => {
      const curFocusIdx = focusedIdxRef.current

      // 反馈输入框与富文本编辑器内：仅 Enter 提交（输入法组合中跳过）。
      // ProseMirror/contenteditable 必须保留箭头键给原生光标移动。
      if (isEditableTarget(e.target)) {
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
          e.preventDefault()
          if (feedbackTextRef.current.trim()) {
            handleActionRef.current?.('feedback')
          }
        }
        if (e.key === 'Escape') {
          e.preventDefault()
          setShowFeedback(false)
          setFocusedIdx(3)
        }
        return
      }

      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        const count = PLAN_OPTIONS.length
        const next = e.key === 'ArrowDown'
          ? (curFocusIdx + 1) % count
          : (curFocusIdx - 1 + count) % count
        setFocusedIdx(next)
      } else if (e.key === 'Enter' && !e.isComposing) {
        e.preventDefault()
        const option = PLAN_OPTIONS[curFocusIdx]
        if (option) {
          if (option.action === 'feedback') {
            setShowFeedback(true)
          } else {
            handleActionRef.current?.(option.action)
          }
        }
      } else if (e.key >= '1' && e.key <= '4') {
        const idx = parseInt(e.key) - 1
        const option = PLAN_OPTIONS[idx]
        if (option) {
          setFocusedIdx(idx)
          if (option.action === 'feedback') {
            setShowFeedback(true)
          } else {
            handleActionRef.current?.(option.action)
          }
        }
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [request?.requestId])

  if (!request) return null

  return (
    <div className="mx-4 mb-3 rounded-xl bg-card shadow-lg overflow-hidden animate-in slide-in-from-bottom-2 duration-200">
      {/* 头部 */}
      <div className="px-4 pt-3 pb-2">
        <div className="flex items-center gap-2 mb-1">
          <FileText className="size-4 text-primary" />
          <span className="text-sm font-medium text-foreground flex-1">Agent 计划待审批</span>
          <button
            type="button"
            className="size-5 flex items-center justify-center rounded-md text-muted-foreground/50 hover:text-foreground hover:bg-muted/60 transition-colors"
            onClick={handleDismiss}
            title="关闭并终止 Agent"
          >
            <X className="size-3.5" />
          </button>
        </div>
        <p className="text-xs text-muted-foreground">
          Agent 已完成计划，请选择如何继续
        </p>
      </div>

      {/* allowedPrompts 展示 */}
      {request.allowedPrompts.length > 0 && (
        <AllowedPromptsList prompts={request.allowedPrompts} />
      )}

      {/* 选项列表 */}
      <div className="px-4 pb-2">
        <div className="flex flex-col gap-1">
          {PLAN_OPTIONS.map((option, idx) => {
            const isFocused = focusedIdx === idx
            return (
              <button
                key={option.action}
                type="button"
                className={`
                  flex items-center gap-2.5 px-3 py-2 rounded-lg text-xs transition-all outline-none text-left
                  ${option.variant === 'destructive'
                    ? 'bg-muted/50 text-foreground/80 hover:bg-destructive/10 hover:text-destructive'
                    : 'bg-muted/50 text-foreground/80 hover:bg-muted'
                  }
                  ${isFocused ? 'ring-2 ring-primary/50 ring-offset-1 ring-offset-card' : ''}
                `}
                onClick={() => {
                  if (option.action === 'feedback') {
                    setShowFeedback(true)
                  } else {
                    void handleAction(option.action)
                  }
                }}
                disabled={submitting}
              >
                <span className="text-[10px] shrink-0 text-muted-foreground/50">
                  {idx + 1}
                </span>
                <span className="shrink-0 text-muted-foreground/70">{option.icon}</span>
                <div className="flex flex-col min-w-0">
                  <span className="font-medium">{option.label}</span>
                  <span className="text-[11px] text-muted-foreground">{option.description}</span>
                </div>
              </button>
            )
          })}
        </div>
      </div>

      {/* 反馈输入框 */}
      {showFeedback && (
        <div className="px-4 pb-2">
          <div className="flex gap-2">
            <input
              type="text"
              className="flex-1 px-3 py-2 rounded-lg text-xs bg-muted/40 focus:bg-muted/60 focus:outline-none focus:ring-2 focus:ring-primary/30 placeholder:text-muted-foreground/40 transition-colors"
              placeholder="输入修改意见..."
              value={feedbackText}
              onChange={(e) => setFeedbackText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault()
                  e.stopPropagation()
                  if (feedbackText.trim()) {
                    void handleAction('feedback')
                  }
                }
              }}
              autoFocus
              disabled={submitting}
            />
            <Button
              variant="default"
              size="sm"
              onClick={() => void handleAction('feedback')}
              disabled={submitting || !feedbackText.trim()}
              className="h-8 px-3 text-xs shrink-0"
            >
              <Send className="size-3 mr-1" />
              发送
            </Button>
          </div>
        </div>
      )}

      {/* 底部提示 */}
      <div className="flex items-center px-4 pb-3">
        <span className="text-[10px] text-muted-foreground/40">
          点击选择 · ↑↓ Enter 确认 · 1-4 快速选择
        </span>
      </div>
    </div>
  )
}

/** allowedPrompts 展示列表 */
function AllowedPromptsList({ prompts }: { prompts: ExitPlanAllowedPrompt[] }): React.ReactElement {
  return (
    <div className="px-4 pb-2">
      <p className="text-[11px] text-muted-foreground mb-1">计划需要的权限：</p>
      <div className="flex flex-wrap gap-1">
        {prompts.map((p, idx) => (
          <span
            key={idx}
            className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] bg-primary/10 text-primary/80"
          >
            {p.prompt}
          </span>
        ))}
      </div>
    </div>
  )
}
