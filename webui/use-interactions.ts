/**
 * 三类阻塞交互 + Goal 提议的队列与提交。
 *
 * 抽出来的原因：四条队列的生命周期完全同构（快照拉取 → 事件入队/出队 → 提交 → 重拉），
 * 但以前散在页面组件的四个 state、四个 setter、八个事件分支里，改一条队列要翻三处。
 *
 * 口径与桌面端三张横幅一致：
 * - request 入队、resolved 出队，提交成功后重拉一次快照（服务端是唯一事实来源）；
 * - Goal 提议**不阻塞运行**，所以只影响卡片区，不锁输入框。
 */
import * as React from 'react'
import type { WsClient } from '../src/renderer/pocket/ws-client'
import type { ProferEventPayload } from './protocol'
import {
  decodeInteractions,
  normalizeAskUserItems,
  normalizeExitPlanItems,
  normalizeGoalProposals,
  normalizePermissionItems,
  type AskUserItem,
  type ExitPlanAction,
  type ExitPlanItem,
  type GoalProposalItem,
  type InteractionHandlers,
  type PermissionItem,
} from './interactions'

export interface InteractionsState {
  permissions: PermissionItem[]
  askUsers: AskUserItem[]
  exitPlans: ExitPlanItem[]
  goalProposals: GoalProposalItem[]
  /** 阻塞性交互条数：>0 时锁输入框 */
  pendingCount: number
  /** 卡片区是否有内容（含不阻塞的 Goal 提议） */
  cardCount: number
  loadInteractions: (client: WsClient) => Promise<void>
  /** 处理交互类事件；返回 true 表示这条事件已被消费 */
  applyEvent: (event: ProferEventPayload) => boolean
  handlers: InteractionHandlers
}

/** 同一 requestId 覆盖式入队，避免重放/轮询造成重复卡片。 */
function upsertById<T extends { requestId: string }>(list: T[], item: T): T[] {
  const index = list.findIndex((entry) => entry.requestId === item.requestId)
  if (index < 0) return [...list, item]
  const next = list.slice()
  next[index] = item
  return next
}

export function useInteractions(input: {
  clientRef: React.MutableRefObject<WsClient | null>
  sessionId: string
  /** 全局提交锁：与 goal / 模型切换共用，避免同时发两条互相干扰的命令 */
  acting: boolean
  setActing: (value: boolean) => void
  onNotice: (message: string) => void
  /** Goal 提议被接受后要刷新 Goal 状态条 */
  refreshGoal: () => Promise<void>
}): InteractionsState {
  const { clientRef, sessionId, acting, setActing, onNotice, refreshGoal } = input
  const [permissions, setPermissions] = React.useState<PermissionItem[]>([])
  const [askUsers, setAskUsers] = React.useState<AskUserItem[]>([])
  const [exitPlans, setExitPlans] = React.useState<ExitPlanItem[]>([])
  const [goalProposals, setGoalProposals] = React.useState<GoalProposalItem[]>([])

  /** 快照拉取：页面晚连 / 断线重连时补回已经存在的待处理交互。 */
  const loadInteractions = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const next = decodeInteractions(await client.getPendingInteractions(sessionId))
      setPermissions(next.permissions)
      setAskUsers(next.askUsers)
      setExitPlans(next.exitPlans)
      setGoalProposals(next.goalProposals)
    } catch {
      // 拉取失败不打断消息读取，事件流与下一轮轮询会补上
    }
  }, [sessionId])

  const respondPermission = React.useCallback(async (
    requestId: string,
    behavior: 'allow' | 'deny',
    alwaysAllow = false,
  ): Promise<void> => {
    const client = clientRef.current
    if (!client || acting) return
    setActing(true)
    try {
      await client.respondPermission(requestId, behavior, alwaysAllow)
      setPermissions((previous) => previous.filter((item) => item.requestId !== requestId))
      await loadInteractions(client)
    } catch (error) {
      onNotice(`权限响应失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [acting, clientRef, loadInteractions, onNotice, setActing])

  const respondAskUser = React.useCallback(async (requestId: string, answers: Record<string, string>): Promise<void> => {
    const client = clientRef.current
    if (!client || acting) return
    setActing(true)
    try {
      await client.respondAskUser(requestId, answers)
      setAskUsers((previous) => previous.filter((item) => item.requestId !== requestId))
      await loadInteractions(client)
    } catch (error) {
      onNotice(`回答提交失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [acting, clientRef, loadInteractions, onNotice, setActing])

  const respondExitPlan = React.useCallback(async (
    requestId: string,
    action: ExitPlanAction,
    feedback?: string,
  ): Promise<void> => {
    const client = clientRef.current
    if (!client || acting) return
    setActing(true)
    try {
      await client.respondExitPlanMode(requestId, action, feedback)
      setExitPlans((previous) => previous.filter((item) => item.requestId !== requestId))
      await loadInteractions(client)
    } catch (error) {
      onNotice(`计划审批失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [acting, clientRef, loadInteractions, onNotice, setActing])

  /**
   * Goal 提议确认。
   *
   * 批准失败时主进程**故意保留**提议（不出队），所以这里不能乐观删卡片：
   * 只提示失败原因并重拉一次快照，用户可以直接重试。
   */
  const respondGoalProposal = React.useCallback(async (proposalId: string, accepted: boolean): Promise<void> => {
    const client = clientRef.current
    if (!client || acting) return
    setActing(true)
    try {
      await client.respondGoalProposal(proposalId, accepted)
      setGoalProposals((previous) => previous.filter((item) => item.id !== proposalId))
      await loadInteractions(client)
      await refreshGoal()
    } catch (error) {
      onNotice(`Goal 提议处理失败：${error instanceof Error ? error.message : String(error)}`)
      await loadInteractions(client)
    } finally {
      setActing(false)
    }
  }, [acting, clientRef, loadInteractions, onNotice, refreshGoal, setActing])

  const handlers = React.useMemo<InteractionHandlers>(() => ({
    onPermission: (id, behavior, alwaysAllow) => { void respondPermission(id, behavior, alwaysAllow) },
    onAskUser: (id, answers) => { void respondAskUser(id, answers) },
    onExitPlan: (id, action, feedback) => { void respondExitPlan(id, action, feedback) },
    onGoalProposal: (id, accepted) => { void respondGoalProposal(id, accepted) },
  }), [respondPermission, respondAskUser, respondExitPlan, respondGoalProposal])

  const applyEvent = React.useCallback((event: ProferEventPayload): boolean => {
    const type = event.type
    const request = event.request
    if (type === 'permission_request' && request) {
      const item = normalizePermissionItems([request])[0]
      if (item) setPermissions((previous) => upsertById(previous, item))
      return true
    }
    if (type === 'permission_resolved') {
      setPermissions((previous) => previous.filter((item) => item.requestId !== event.requestId))
      return true
    }
    if (type === 'ask_user_request' && request) {
      const item = normalizeAskUserItems([request])[0]
      if (item) setAskUsers((previous) => upsertById(previous, item))
      return true
    }
    if (type === 'ask_user_resolved') {
      setAskUsers((previous) => previous.filter((item) => item.requestId !== event.requestId))
      return true
    }
    if (type === 'exit_plan_mode_request' && request) {
      const item = normalizeExitPlanItems([request])[0]
      if (item) setExitPlans((previous) => upsertById(previous, item))
      return true
    }
    if (type === 'exit_plan_mode_resolved') {
      setExitPlans((previous) => previous.filter((item) => item.requestId !== event.requestId))
      return true
    }
    if (type === 'goal_proposal' && event.proposal) {
      // 提议不阻塞运行：模型提议后这一轮照常跑完，用户批准才由状态机接管后续轮次。
      const item = normalizeGoalProposals([event.proposal])[0]
      if (item) {
        setGoalProposals((previous) => previous.some((entry) => entry.id === item.id)
          ? previous.map((entry) => (entry.id === item.id ? item : entry))
          : [...previous, item])
      }
      return true
    }
    if (type === 'goal_proposal_resolved') {
      setGoalProposals((previous) => previous.filter((item) => item.id !== event.proposalId))
      return true
    }
    return false
  }, [])

  const pendingCount = permissions.length + askUsers.length + exitPlans.length
  const cardCount = pendingCount + goalProposals.length

  return {
    permissions,
    askUsers,
    exitPlans,
    goalProposals,
    pendingCount,
    cardCount,
    loadInteractions,
    applyEvent,
    handlers,
  }
}
