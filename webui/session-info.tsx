/**
 * 会话信息条（横排、居中）。
 *
 * 触发方式：点标题胶囊里「除了模型那一行、除了左边历史按钮」的任意区域 —— 包括标题本身。
 * 所以这里不是"菜单"而是一条只读信息条：一行铺开、居中，点空白处或按 Esc 收起。
 *
 * 为什么不做成下拉菜单：这三四项是"确认当前会话状态"用的（模型、权限、短码、构建），
 * 不需要逐项可点。横排一行比竖排菜单更短、也不与「模型/强度」那个真正的操作菜单混在一起。
 */
import * as React from 'react'
import { useOverlay } from './overlay'

export interface SessionInfoProps {
  items: Array<{ key: string; value: string }>
  onClose: () => void
}

export function SessionInfo({ items, onClose }: SessionInfoProps): React.ReactElement {
  const stripRef = useOverlay(onClose)
  return (
    <>
      {/* 透明遮罩：只接住「点外面关闭」，不压暗 —— 与模型菜单同一口径 */}
      <div className="info-scrim" onClick={onClose} />
      <div className="info-strip" role="dialog" aria-label="会话信息" ref={stripRef} tabIndex={-1}>
        {items.map((item) => (
          <span className="info-item" key={item.key}>
            <span className="info-key">{item.key}</span>
            <span className="info-val">{item.value}</span>
          </span>
        ))}
      </div>
    </>
  )
}
