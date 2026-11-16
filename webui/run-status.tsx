/**
 * 输入条上方的运行状态条。
 *
 * 四类都只在「有事要说」时出现，未展开时各占一行：
 * - 重试 / 落盘失败：一次运行内的异常，结束后消失；
 * - 后台任务：软空闲期间仍在跑的任务，避免用户以为会话已经彻底结束；
 * - 委派子任务：在跑 / 在等回答。只报状态，不做子会话跳转（发布白名单是会话级的）。
 */
import * as React from 'react'
import { backgroundTaskDetail, backgroundTaskLabel, type BackgroundTasksView } from './background-tasks'
import { delegationLineDetail, delegationSummary, type DelegationsView } from './delegations'
import type { PersistenceWarning, RetryInfo } from './run-outcome'

export interface RunStatusProps {
  retry: RetryInfo | null
  persistenceWarning: PersistenceWarning | null
  background: BackgroundTasksView
  delegations: DelegationsView
}

export function RunStatus({
  retry, persistenceWarning, background, delegations,
}: RunStatusProps): React.ReactElement | null {
  const backgroundText = backgroundTaskLabel(background)
  const backgroundDetail = backgroundTaskDetail(background)
  const delegationText = delegationSummary(delegations)
  if (!backgroundText && !delegationText && !retry && !persistenceWarning) return null

  return (
    <div className="run-status">
      {persistenceWarning && (
        <div className="run-strip error">
          <span className="run-strip-dot" aria-hidden="true" />
          <span className="run-strip-text">{persistenceWarning.message}</span>
        </div>
      )}
      {retry && (
        <div className="run-strip warning">
          <span className="run-strip-dot pulse" aria-hidden="true" />
          <span className="run-strip-text">
            正在重试（第 {retry.attempt}
            {retry.maxAttempts ? ` / ${retry.maxAttempts}` : ''} 次）
            {retry.delaySeconds !== undefined ? `，${Math.max(0, Math.round(retry.delaySeconds))} 秒后继续` : ''}
          </span>
          {retry.reason && <span className="run-strip-note">{retry.reason}</span>}
        </div>
      )}
      {delegationText && (
        <details className={`run-strip delegation${delegations.blockedCount > 0 ? ' waiting' : ''}`}>
          <summary>
            <span className="run-strip-dot" aria-hidden="true" />
            <span className="run-strip-text">{delegationText}</span>
            <span className="run-strip-toggle">详情</span>
          </summary>
          <ul className="run-strip-list">
            {delegations.running.map((entry) => {
              const detail = delegationLineDetail(entry)
              return (
                <li key={entry.delegationId}>
                  <span className="run-strip-item-title">{entry.title}</span>
                  <span className="run-strip-item-meta">
                    {[entry.roleLabel, detail, entry.blocked.length > 0 ? `等待 ${entry.blocked.length} 项回答` : '']
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </li>
              )
            })}
          </ul>
        </details>
      )}
      {backgroundText && (
        <details className="run-strip background">
          <summary>
            <span className="run-strip-dot" aria-hidden="true" />
            <span className="run-strip-text">{backgroundText}</span>
            {backgroundDetail && <span className="run-strip-toggle">详情</span>}
          </summary>
          {backgroundDetail && (
            <ul className="run-strip-list">
              {background.tasks.filter((task) => task.description).map((task) => (
                <li key={task.taskId}>
                  <span className="run-strip-item-title">{task.description}</span>
                </li>
              ))}
            </ul>
          )}
        </details>
      )}
    </div>
  )
}