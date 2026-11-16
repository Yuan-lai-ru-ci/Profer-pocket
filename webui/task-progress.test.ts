import { describe, expect, test } from 'bun:test'
import type { SDKMessage } from '@profer/shared'
import {
  aggregateTaskItems,
  buildHistoricalTaskSubjects,
  isTaskProgressTool,
  parseTaskCreateResult,
  parseTaskToolInput,
  type TaskToolActivity,
} from './task-progress'

const create = (
  toolUseId: string,
  input: Record<string, unknown>,
  result?: string,
): TaskToolActivity => ({ toolUseId, toolName: 'TaskCreate', input, ...(result ? { result } : {}) })

const update = (toolUseId: string, input: Record<string, unknown>): TaskToolActivity => ({
  toolUseId,
  toolName: 'TaskUpdate',
  input,
})

describe('任务工具识别', () => {
  test('Given 内置与 MCP 前缀的任务工具 When 识别 Then 都归入任务进度', () => {
    expect(isTaskProgressTool('TaskCreate')).toBe(true)
    expect(isTaskProgressTool('TaskUpdate')).toBe(true)
    expect(isTaskProgressTool('TodoWrite')).toBe(true)
    expect(isTaskProgressTool('mcp__task-graph__profer_task_create')).toBe(true)
    expect(isTaskProgressTool('mcp__task_graph__profer_task_update')).toBe(true)
  })

  test('Given 普通工具 When 识别 Then 不归入任务进度', () => {
    expect(isTaskProgressTool('Read')).toBe(false)
    expect(isTaskProgressTool('mcp__task-graph__profer_task_delete')).toBe(false)
  })
})

describe('任务入参解析（WS 投影只有 inputText）', () => {
  test('Given 完整 inputText When 解析 Then 直接得到 JSON 入参', () => {
    const input = parseTaskToolInput('{"subject":"写测试","activeForm":"正在写测试"}', {}, undefined)
    expect(input).toEqual({ subject: '写测试', activeForm: '正在写测试' })
  })

  test('Given inputText 被中间截断 When 解析 Then 用宽松正则捡回关键字段', () => {
    const truncated = '{\n  "subject": "重构投影层",\n  "description": "非常长的描述…'
    expect(parseTaskToolInput(truncated, {}, undefined)).toEqual({ subject: '重构投影层' })
  })

  test('Given 没有任何可解析字段 When 解析 Then 退回摘要，避免出现「未命名任务」', () => {
    expect(parseTaskToolInput('', {}, '整理会话列表')).toEqual({ description: '整理会话列表' })
  })

  test('Given 消息里还带完整 input When 解析 Then 优先使用结构化入参', () => {
    expect(parseTaskToolInput(undefined, { subject: '直接给的对象' }, '摘要')).toEqual({ subject: '直接给的对象' })
  })
})

describe('TaskCreate 结果解析', () => {
  test('Given 结构化结果 When 解析 Then 取 task.id 与 subject', () => {
    expect(parseTaskCreateResult('{"task":{"id":"3","subject":"实现任务卡"}}')).toEqual({ id: '3', subject: '实现任务卡' })
  })

  test('Given 数字 id When 解析 Then 归一化成字符串', () => {
    expect(parseTaskCreateResult('{"task":{"id":7}}')).toEqual({ id: '7' })
  })

  test('Given 旧版文本结果 When 解析 Then 从 Task #N 兜底取 id', () => {
    expect(parseTaskCreateResult('Task #12 created successfully')).toEqual({ id: '12' })
  })

  test('Given 无法解析 When 解析 Then 返回 null', () => {
    expect(parseTaskCreateResult('ok')).toBeNull()
    expect(parseTaskCreateResult(undefined)).toBeNull()
  })
})

describe('任务聚合', () => {
  test('Given TaskCreate 后 TaskUpdate 完成 When 聚合 Then 状态更新、名字保留', () => {
    const tasks = aggregateTaskItems([
      create('t1', { subject: '补任务卡', activeForm: '正在补任务卡' }, '{"task":{"id":"1","subject":"补任务卡"}}'),
      update('t2', { taskId: '1', status: 'in_progress' }),
      update('t3', { taskId: '1', status: 'completed' }),
    ], false)
    expect(tasks).toEqual([
      { id: '1', subject: '补任务卡', status: 'completed', activeForm: '正在补任务卡' },
    ])
  })

  test('Given 跨轮 TaskUpdate 先于本条目的 TaskCreate When 聚合 Then 用历史映射补名字', () => {
    const tasks = aggregateTaskItems([
      update('u1', { taskId: '9', status: 'in_progress' }),
    ], false, new Map([['9', '历史任务名']]))
    expect(tasks).toEqual([{ id: '9', subject: '历史任务名', status: 'in_progress' }])
  })

  test('Given 没有历史映射的孤立 TaskUpdate When 聚合 Then 不造「任务 #N」幽灵条目', () => {
    expect(aggregateTaskItems([update('u1', { taskId: '9', status: 'in_progress' })], false)).toEqual([])
  })

  test('Given TodoWrite 两次 When 聚合 Then 后一份清单整体替换前一份', () => {
    const tasks = aggregateTaskItems([
      { toolUseId: 't1', toolName: 'TodoWrite', input: { todos: [{ content: '旧一', status: 'completed' }, { content: '旧二', status: 'pending' }] } },
      { toolUseId: 't2', toolName: 'TodoWrite', input: { todos: [{ content: '新一', status: 'in_progress' }] } },
    ], false)
    expect(tasks.map((task) => task.subject)).toEqual(['新一'])
    expect(tasks[0]?.status).toBe('in_progress')
  })

  test('Given 已删除任务 When 聚合 Then 不进清单', () => {
    const tasks = aggregateTaskItems([
      create('t1', { subject: '会被删掉' }, '{"task":{"id":"1"}}'),
      update('t2', { taskId: '1', status: 'deleted' }),
    ], false)
    expect(tasks).toEqual([])
  })

  test('Given 流式已结束 When 聚合 Then in_progress 退回 pending（不再转圈）', () => {
    const tasks = aggregateTaskItems([
      create('t1', { subject: '做到一半' }, '{"task":{"id":"1"}}'),
      update('t2', { taskId: '1', status: 'in_progress' }),
    ], true)
    expect(tasks[0]?.status).toBe('pending')
  })

  test('Given 用户手动打断 When 聚合 Then in_progress 标记为 cancelled', () => {
    const tasks = aggregateTaskItems([
      create('t1', { subject: '被打断的任务' }, '{"task":{"id":"1"}}'),
      update('t2', { taskId: '1', status: 'in_progress' }),
    ], true, undefined, true)
    expect(tasks[0]?.status).toBe('cancelled')
  })

  test('Given MCP 前缀的 TaskCreate When 聚合 Then 同样建条目', () => {
    const tasks = aggregateTaskItems([
      { toolUseId: 't1', toolName: 'mcp__task-graph__profer_task_create', input: { subject: 'MCP 任务' } },
    ], false)
    expect(tasks[0]?.subject).toBe('MCP 任务')
  })
})

describe('跨轮任务名映射', () => {
  const assistant = (blocks: unknown[]): SDKMessage => ({ type: 'assistant', message: { content: blocks } } as never)
  const user = (blocks: unknown[]): SDKMessage => ({ type: 'user', message: { content: blocks } } as never)

  test('Given TaskCreate 入参 subject + 结果 id When 构建映射 Then 得到 id → subject', () => {
    const subjects = buildHistoricalTaskSubjects([
      assistant([{
        type: 'tool_use',
        id: 'tool-1',
        name: 'TaskCreate',
        input: {},
        inputText: '{"subject":"跨轮任务"}',
      }]),
      user([{ type: 'tool_result', tool_use_id: 'tool-1', output: '{"task":{"id":"5"}}' }]),
    ])
    expect(subjects.get('5')).toBe('跨轮任务')
  })

  test('Given 没有 TaskCreate When 构建映射 Then 返回空表', () => {
    expect(buildHistoricalTaskSubjects([assistant([{ type: 'tool_use', id: 'a', name: 'Read' }])]).size).toBe(0)
  })
})
