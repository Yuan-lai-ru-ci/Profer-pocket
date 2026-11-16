import { describe, expect, test } from 'bun:test'
import type { SDKMessage } from '@profer/shared'
import {
  backgroundTaskDetail,
  backgroundTaskLabel,
  collectPendingBackgroundTasks,
} from './background-tasks'

const system = (fields: Record<string, unknown>): SDKMessage =>
  ({ type: 'system', ...fields } as unknown as SDKMessage)

describe('后台任务：从消息窗口推导挂起状态', () => {
  test('Given 任务启动后持续上报进度 When 收集 Then 只保留一个任务且描述取最新', () => {
    const view = collectPendingBackgroundTasks([
      system({ subtype: 'task_started', task_id: 't1', description: '跑测试' }),
      system({ subtype: 'task_progress', task_id: 't1', description: '正在跑第 3 个文件' }),
    ])
    expect(view.tasks).toEqual([{ taskId: 't1', description: '正在跑第 3 个文件' }])
  })

  test('Given 任务完成通知到达 When 收集 Then 从挂起列表移除', () => {
    const view = collectPendingBackgroundTasks([
      system({ subtype: 'task_started', task_id: 't1', description: '跑测试' }),
      system({ subtype: 'task_notification', task_id: 't1', status: 'completed' }),
    ])
    expect(view.tasks).toHaveLength(0)
  })

  test('Given 首屏只拉到 task_progress（task_started 在更早分页）When 收集 Then 用描述建条目，不丢提示', () => {
    const view = collectPendingBackgroundTasks([
      system({ subtype: 'task_progress', task_id: 't1', description: '构建中' }),
    ])
    expect(view.tasks).toEqual([{ taskId: 't1', description: '构建中' }])
  })

  test('Given 两个任务在跑 When 收集 Then 按出现顺序都给出来', () => {
    const view = collectPendingBackgroundTasks([
      system({ subtype: 'task_started', task_id: 'a', description: 'A' }),
      system({ subtype: 'task_started', task_id: 'b', description: 'B' }),
    ])
    expect(view.tasks.map((task) => task.taskId)).toEqual(['a', 'b'])
  })

  test('Given 脏消息（task_id 非字符串）When 收集 Then 忽略而不是抛错', () => {
    const view = collectPendingBackgroundTasks([
      system({ subtype: 'task_started', task_id: 42 }),
      system({ subtype: 'task_started' }),
      { type: 'assistant', message: { content: [] } } as unknown as SDKMessage,
    ])
    expect(view.tasks).toHaveLength(0)
  })
})

describe('后台任务：状态条文案', () => {
  test('Given 有两个任务 When 取文案 Then 报数量', () => {
    expect(backgroundTaskLabel({
      tasks: [{ taskId: 'a', description: 'A' }, { taskId: 'b', description: 'B' }],
      pending: true,
    })).toBe('还有 2 个后台任务在跑')
  })

  test('Given 服务端说还有任务但窗口里没有 task_started When 取文案 Then 退化为不带数量的提示', () => {
    expect(backgroundTaskLabel({ tasks: [], pending: true })).toBe('后台任务仍在运行')
  })

  test('Given 没有任务也没有挂起标记 When 取文案 Then 返回 null（整条不渲染）', () => {
    expect(backgroundTaskLabel({ tasks: [], pending: false })).toBeNull()
  })

  test('Given 任务带描述 When 取详情 Then 用 · 连接成一行', () => {
    expect(backgroundTaskDetail({
      tasks: [{ taskId: 'a', description: '跑测试' }, { taskId: 'b', description: '构建' }],
      pending: false,
    })).toBe('跑测试 · 构建')
  })
})