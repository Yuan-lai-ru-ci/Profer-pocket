/**
 * 用户消息的操作面板（复制 / 编辑重发）。
 *
 * 「编辑重发」= 把会话回退到这条消息**之前**，再把原文放回输入框。
 * 回退范围只到对话：文件改动不回滚（那需要桌面端的工作区历史/Shadow Git）。
 * 这一点必须在确认页写清楚 —— 用户以为文件也回去了，会得到重复改动。
 */
import * as React from 'react'
import { useOverlay } from './overlay'

export interface TurnActionsProps {
  /** 该条用户消息的原文（已剥掉上下文注入块） */
  text: string
  canEdit: boolean
  /** 不能编辑时的原因（运行中 / 缺 uuid） */
  editHint?: string
  busy: boolean
  /** 是否同时还原文件改动（rewind both） */
  restoreFiles: boolean
  onRestoreFilesChange: (value: boolean) => void
  onCopy: () => void
  onEdit: () => void
  onClose: () => void
}

export function TurnActions({
  text, canEdit, editHint, busy, restoreFiles, onRestoreFilesChange, onCopy, onEdit, onClose,
}: TurnActionsProps): React.ReactElement {
  const [confirming, setConfirming] = React.useState(false)
  const sheetRef = useOverlay(onClose, !busy)
  const preview = text.replace(/\s+/g, ' ').trim().slice(0, 120)

  return (
    <>
      <div className="sheet-scrim" onClick={() => { if (!busy) onClose() }} />
      <div className="sheet turn-actions-sheet" role="dialog" aria-modal="true" aria-label="消息操作" ref={sheetRef} tabIndex={-1}>
        <div className="history-head">
          <strong>{confirming ? '回退到这条消息之前' : '这条消息'}</strong>
          <button className="history-close" onClick={onClose} disabled={busy} aria-label="关闭">×</button>
        </div>

        {confirming ? (
          <>
            <p className="turn-actions-text">
              这条消息及之后的所有回复都会被删除，然后你可以修改它并重新发送。
            </p>
            <label className="turn-actions-check">
              <input
                type="checkbox"
                checked={restoreFiles}
                onChange={(event) => onRestoreFilesChange(event.target.checked)}
              />
              <span>
                同时还原文件改动
                <span className="turn-action-hint">
                  按工作区历史把这一段里改过的文件恢复到当时版本；执行前会先给出文件清单。
                  不勾选则文件保持现状。
                </span>
              </span>
            </label>
            <div className="turn-actions-row">
              <button className="ibtn ghost" disabled={busy} onClick={() => setConfirming(false)}>返回</button>
              <button className="ibtn primary" disabled={busy} onClick={onEdit}>{busy ? '回退中…' : '确认回退'}</button>
            </div>
          </>
        ) : (
          <>
            <p className="turn-actions-preview">{preview || '（空消息）'}</p>
            <button className="turn-action" disabled={busy} onClick={onCopy}>
              <span className="turn-action-label">复制原文</span>
              <span className="turn-action-hint">把这条消息的文字复制到剪贴板</span>
            </button>
            <button
              className="turn-action"
              disabled={!canEdit || busy}
              onClick={() => setConfirming(true)}
            >
              <span className="turn-action-label">编辑重发</span>
              <span className="turn-action-hint">回退到这条之前，改完再发</span>
            </button>
            {!canEdit && editHint && <p className="turn-actions-hint">{editHint}</p>}
          </>
        )}
      </div>
    </>
  )
}
