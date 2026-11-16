import { describe, expect, test } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { TaskProgressCard } from './task-progress-card'
import type { TaskItem } from './task-progress'

function render(tasks: TaskItem[]): string {
  return renderToStaticMarkup(<TaskProgressCard tasks={tasks} />)
}

function tasksOf(count: number, activeIndex: number): TaskItem[] {
  return Array.from({ length: count }, (_, index) => ({
    id: String(index + 1),
    subject: `任务 ${index + 1}`,
    status: index < activeIndex ? 'completed' : index === activeIndex ? 'in_progress' : 'pending',
  }))
}

describe('任务进度卡', () => {
  test('Given 没有任务 When 渲染 Then 不占位', () => {
    expect(render([])).toBe('')
  })

  test('Given 两条任务完成一条 When 渲染 Then 显示计数与进度条', () => {
    const html = render(tasksOf(2, 0).map((task, index) => index === 0 ? { ...task, status: 'completed' } : task))
    expect(html).toContain('任务进度')
    expect(html).toContain('1/2')
    expect(html).toContain('class="task-bar"')
    expect(html).toContain('width:50%')
  })

  test('Given 只有一条任务 When 渲染 Then 不画进度条（一条占满没有信息量）', () => {
    const html = render(tasksOf(1, 0))
    expect(html).not.toContain('class="task-bar"')
  })

  test('Given 12 条任务且当前在第 10 条 When 折叠 Then 窗口围绕当前步，不是永远截前 8 条', () => {
    const html = render(tasksOf(12, 9))
    expect(html).toContain('任务 10')
    expect(html).toContain('任务 12')
    expect(html).not.toContain('任务 1<')
    expect(html).toContain('展开全部 12 项')
  })

  test('Given 进行中的任务带 activeForm When 渲染 Then 优先显示进行时文案', () => {
    const html = render([{
      id: '1',
      subject: '写测试',
      activeForm: '正在写测试',
      status: 'in_progress',
    }])
    expect(html).toContain('正在写测试')
    expect(html).not.toContain('>写测试<')
  })

  test('Given 失败与取消任务 When 渲染 Then 各自的标记语义可区分', () => {
    const html = render([
      { id: '1', subject: '失败项', status: 'failed' },
      { id: '2', subject: '取消项', status: 'cancelled' },
    ])
    expect(html).toContain('task-item failed')
    expect(html).toContain('task-item cancelled')
    expect(html).toContain('×')
    expect(html).toContain('–')
  })
})
