/**
 * 后台任务挂起状态的纯派生层。
 *
 * 只从**已加载的消息窗口**推：`task_started` / `task_progress` 建条目，
 * `task_notification` 收条目。主进程的 `run_completed.backgroundTasksPending`
 * 只是一次软空闲（还会再来真终态），所以它只作为「有任务在跑但计数未知」的兜底，
 * 不能单独当成终态。
 *
 * 任务状态不落盘到会话索引（只在 JSONL 的系统消息里），所以刷新后从消息重算，
 * 正好与运行中走同一套判据 —— 不存在「刷新就丢提示」的第二条状态链路。
 */
import type { SDKMessage } from '@profer/shared'

export interface BackgroundTaskInfo {
  taskId: string
  /** 任务说明；跨分页时可能为空 */
  description: string
}

export interface BackgroundTasksView {
  tasks: BackgroundTaskInfo[]
  /** 服务端给过 backgroundTasksPending=true；可能没有对应的 task_started（任务在更早的分页里） */
  pending: boolean
}

interface TaskMessageLike {
  type?: unknown
  subtype?: unknown
  task_id?: unknown
  description?: unknown
}

/**
 * 收集仍在跑的后台任务。
 *
 * `task_progress` 可能先于 `task_started` 出现在窗口里（首屏只拉尾部），
 * 所以有描述时也建条目；`task_notification` 到达即视为结束（completed / failed / stopped 都一样）。
 */
export function collectPendingBackgroundTasks(
  messages: readonly SDKMessage[],
  pendingFlag = false,
): BackgroundTasksView {
  const tasks = new Map<string, BackgroundTaskInfo>()
  for (const message of messages) {
    const record = message as TaskMessageLike
    if (record.type !== 'system') continue
    if (typeof record.task_id !== 'string' || !record.task_id) continue
    const taskId = record.task_id
    const description = typeof record.description === 'string' ? record.description.trim() : ''

    if (record.subtype === 'task_started') {
      tasks.set(taskId, { taskId, description })
    } else if (record.subtype === 'task_progress') {
      const existing = tasks.get(taskId)
      if (existing) {
        if (description) existing.description = description
      } else if (description) {
        tasks.set(taskId, { taskId, description })
      }
    } else if (record.subtype === 'task_notification') {
      tasks.delete(taskId)
    }
  }
  return { tasks: Array.from(tasks.values()), pending: pendingFlag }
}

/** 状态条主文案；没有可提示的内容时返回 null。 */
export function backgroundTaskLabel(view: BackgroundTasksView): string | null {
  if (view.tasks.length > 0) return `还有 ${view.tasks.length} 个后台任务在跑`
  return view.pending ? '后台任务仍在运行' : null
}

/** 展开时的一行说明：优先用任务自带的描述，没有就只报数量。 */
export function backgroundTaskDetail(view: BackgroundTasksView): string {
  const names = view.tasks.map((task) => task.description).filter(Boolean)
  return names.join(' · ')
}