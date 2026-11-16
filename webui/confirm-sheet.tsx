/**
 * 通用确认面板（覆盖层）。
 *
 * 现在有三处需要「先说清楚再执行」：压缩上下文、停止本轮并发送、编辑重发。
 * 后者是两段式菜单，前两者本质相同 —— 抽成一个组件，避免每加一个动作就抄一遍
 * 遮罩 + 标题 + 按钮行的排版（以及漏掉 Esc / 焦点这些细节）。
 */
import * as React from 'react'
import { useOverlay } from './overlay'

export interface ConfirmSheetProps {
  title: string
  /** 正文：说明「这个动作会做什么」 */
  description: React.ReactNode
  /** 补充一句代价 / 不可逆性；没有就不渲染 */
  hint?: React.ReactNode
  confirmLabel: string
  cancelLabel?: string
  busy?: boolean
  onConfirm: () => void
  onClose: () => void
}

export function ConfirmSheet({
  title, description, hint, confirmLabel, cancelLabel = '取消', busy = false, onConfirm, onClose,
}: ConfirmSheetProps): React.ReactElement {
  // 执行中不响应 Esc：避免关掉面板后动作仍在跑、状态停在未知处
  const sheetRef = useOverlay(onClose, !busy)
  return (
    <>
      <div className="sheet-scrim" onClick={() => { if (!busy) onClose() }} />
      <div className="sheet confirm-sheet" role="alertdialog" aria-modal="true" aria-label={title} ref={sheetRef} tabIndex={-1}>
        <div className="history-head">
          <strong>{title}</strong>
          <button className="history-close" onClick={onClose} disabled={busy} aria-label="关闭">×</button>
        </div>
        <p className="turn-actions-text">{description}</p>
        {hint && <p className="turn-actions-hint">{hint}</p>}
        <div className="turn-actions-row">
          <button className="ibtn ghost" disabled={busy} onClick={onClose}>{cancelLabel}</button>
          <button className="ibtn primary" disabled={busy} onClick={onConfirm}>{busy ? '处理中…' : confirmLabel}</button>
        </div>
      </div>
    </>
  )
}
