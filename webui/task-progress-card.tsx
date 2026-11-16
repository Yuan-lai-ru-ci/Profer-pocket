/**
 * 任务进度卡：TaskCreate / TaskUpdate / TodoWrite 聚合后的可读视图。
 *
 * 与桌面端 TaskProgressCard 同一套信息：总数、当前步（activeForm 优先）、已完成数。
 * 移动端差异：超过 8 项时折叠窗口围绕「当前正在做的那一条」，而不是永远截前 8 条 ——
 * 长任务跑到后面时，用户最想看到的是现在，不是开头。
 */
import * as React from 'react'
import type { TaskItem } from './task-progress'

const MAX_VISIBLE = 8

function markOf(item: TaskItem): string | null {
  if (item.status === 'completed') return '✓'
  if (item.status === 'failed') return '×'
  if (item.status === 'cancelled') return '–'
  return null
}

export function TaskProgressCard({ tasks }: { tasks: readonly TaskItem[] }): React.ReactElement | null {
  const [expanded, setExpanded] = React.useState(false)
  if (tasks.length === 0) return null

  const completedCount = tasks.filter((item) => item.status === 'completed').length
  const totalCount = tasks.length
  const needsCollapse = totalCount > MAX_VISIBLE

  let start = 0
  if (needsCollapse && !expanded) {
    const activeIndex = tasks.findIndex((item) => item.status === 'in_progress')
    const firstOpen = tasks.findIndex((item) => item.status !== 'completed')
    const anchor = activeIndex >= 0 ? activeIndex : firstOpen >= 0 ? firstOpen : 0
    start = Math.max(0, Math.min(anchor - 2, totalCount - MAX_VISIBLE))
  }
  const visibleItems = needsCollapse && !expanded
    ? tasks.slice(start, start + MAX_VISIBLE)
    : tasks
  const percent = Math.round((completedCount / totalCount) * 100)

  return (
    <div className="task-card">
      <div className="task-card-head">
        <span className="task-card-title">任务进度</span>
        <span className="task-card-count">{completedCount}/{totalCount}</span>
      </div>
      {totalCount > 1 && (
        <div
          className="task-bar"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={totalCount}
          aria-valuenow={completedCount}
        >
          <span style={{ width: `${percent}%` }} />
        </div>
      )}
      <ul className="task-list">
        {visibleItems.map((item) => (
          <li key={item.id} className={`task-item ${item.status}`}>
            <span className="task-mark" aria-hidden="true">{markOf(item)}</span>
            <span className="task-subject">
              {item.status === 'in_progress' && item.activeForm ? item.activeForm : item.subject}
            </span>
          </li>
        ))}
      </ul>
      {needsCollapse && (
        <button
          type="button"
          className="task-more"
          onClick={() => setExpanded((previous) => !previous)}
          aria-expanded={expanded}
        >
          {expanded ? '收起' : `展开全部 ${totalCount} 项`}
        </button>
      )}
    </div>
  )
}
