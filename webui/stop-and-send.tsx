/**
 * 「当前内核不支持运行中追加」时的兜底确认。
 *
 * 只有 Pi 这类没有流式追加能力的 runtime 会走到这里（Claude 直接排队）。
 * 唯一能让这句话立刻发出去的路径是：停掉正在跑的这一轮，再按空闲路径发送。
 * 这会中断正在进行的回答，所以必须显式确认，不能替用户决定。
 */
import * as React from 'react'
import { ConfirmSheet } from './confirm-sheet'

export interface StopAndSendProps {
  busy: boolean
  onStop: () => void
  onClose: () => void
}

export function StopAndSend({ busy, onStop, onClose }: StopAndSendProps): React.ReactElement {
  return (
    <ConfirmSheet
      title="这一轮还在跑"
      description="当前内核不支持在运行中追加消息。可以等它跑完再发，或者现在停止这一轮、立刻把你刚才写的内容发出去。"
      hint="停止会中断正在生成的回答，已经产出的内容会保留在会话里。"
      confirmLabel="停止本轮并发送"
      cancelLabel="等它跑完"
      busy={busy}
      onConfirm={onStop}
      onClose={onClose}
    />
  )
}
