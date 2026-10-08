import * as React from 'react'
import type { AgentSessionMeta } from '@profer/shared'

export interface SessionSettingMutationInput {
  session?: AgentSessionMeta
  execute: (expectedRevision?: number) => Promise<AgentSessionMeta>
  applyOptimistic?: () => void
  applyAuthoritative: (session: AgentSessionMeta) => void
  rollback: () => void
  refresh?: () => Promise<AgentSessionMeta | undefined>
  onError?: (error: unknown) => void
}

export function isSessionRevisionConflict(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)
  return message.includes('REVISION_CONFLICT') || message.includes('状态已更新')
}

/**
 * 会话设置统一提交器：
 * - 单次 pending 防重复点击
 * - 所有命令携带当前会话 revision
 * - 成功只接受完整权威会话
 * - revision 冲突先刷新桌面快照
 * - 普通失败统一回滚
 */
export function useSessionSettingMutation(): {
  pending: boolean
  mutate: (input: SessionSettingMutationInput) => Promise<AgentSessionMeta | undefined>
} {
  const pendingRef = React.useRef(false)
  const [pending, setPending] = React.useState(false)

  const mutate = React.useCallback(async (input: SessionSettingMutationInput): Promise<AgentSessionMeta | undefined> => {
    if (pendingRef.current) return undefined

    pendingRef.current = true
    setPending(true)
    input.applyOptimistic?.()

    try {
      const updated = await input.execute(input.session?.revision)
      input.applyAuthoritative(updated)
      return updated
    } catch (error) {
      let refreshed = false
      if (isSessionRevisionConflict(error) && input.refresh) {
        try {
          const fresh = await input.refresh()
          if (fresh) {
            input.applyAuthoritative(fresh)
            refreshed = true
          }
        } catch (refreshError) {
          input.onError?.(refreshError)
        }
      }
      if (!refreshed) input.rollback()
      input.onError?.(error)
      return undefined
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }, [])

  return { pending, mutate }
}
