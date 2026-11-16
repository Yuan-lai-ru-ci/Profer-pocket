/**
 * 「带文件还原」的回退计划确认。
 *
 * rewind_prepare 会按工作区历史（Shadow Git）算出哪些文件要还原、哪些要删除；
 * 这一步**还没写盘**，所以必须让用户看过清单再执行 —— 尤其是「删除」项
 * （Agent 在那之后新建的文件会被撤掉）。
 */
import * as React from 'react'
import { useOverlay } from './overlay'
import type { RewindPlanFilePayload } from './protocol'

export interface RewindPlanSheetProps {
  files: RewindPlanFilePayload[]
  busy: boolean
  onExecute: () => void
  onClose: () => void
}

const MAX_LISTED = 8

function actionLabel(action: string): string {
  if (action === 'restore') return '还原'
  if (action === 'delete') return '删除'
  return '保留'
}

export function RewindPlanSheet({ files, busy, onExecute, onClose }: RewindPlanSheetProps): React.ReactElement {
  const sheetRef = useOverlay(onClose, !busy)
  const actionable = files.filter((file) => file.action === 'restore' || file.action === 'delete')
  const restoreCount = actionable.filter((file) => file.action === 'restore').length
  const deleteCount = actionable.length - restoreCount
  const shown = actionable.slice(0, MAX_LISTED)
  const overflow = actionable.length - shown.length

  return (
    <>
      <div className="sheet-scrim" onClick={() => { if (!busy) onClose() }} />
      <div className="sheet confirm-sheet" role="alertdialog" aria-modal="true" aria-label="回退计划" ref={sheetRef} tabIndex={-1}>
        <div className="history-head">
          <strong>回退并还原文件</strong>
          <button className="history-close" onClick={onClose} disabled={busy} aria-label="关闭">×</button>
        </div>
        <p className="turn-actions-text">
          对话将回退到这条消息之前。工作区历史里有这些文件的改动记录：
          还原 {restoreCount} 个{deleteCount > 0 ? `、删除 ${deleteCount} 个` : ''}。
        </p>
        {actionable.length === 0 ? (
          <p className="turn-actions-hint">这一段没有检测到文件改动，将只回退对话。</p>
        ) : (
          <div className="rewind-plan-list">
            {shown.map((file, index) => (
              <div className="rewind-plan-item" key={file.itemId ?? `${file.path}-${index}`}>
                <span className={`rewind-plan-action ${file.action === 'delete' ? 'del' : 'res'}`}>
                  {actionLabel(file.action ?? '')}
                </span>
                <span className="rewind-plan-path">{file.relativePath ?? file.path}</span>
              </div>
            ))}
            {overflow > 0 && <div className="rewind-plan-item"><span className="rewind-plan-path">…等 {overflow} 个文件</span></div>}
          </div>
        )}
        <p className="turn-actions-hint">
          文件版本来自工作区历史；无法安全还原的文件会保持原样。对话照常回退。
        </p>
        <div className="turn-actions-row">
          <button className="ibtn ghost" disabled={busy} onClick={onClose}>取消</button>
          <button className="ibtn primary" disabled={busy || actionable.length === 0} onClick={onExecute}>
            {busy ? '执行中…' : actionable.length === 0 ? '仅回退对话' : '确认执行'}
          </button>
        </div>
      </div>
    </>
  )
}
