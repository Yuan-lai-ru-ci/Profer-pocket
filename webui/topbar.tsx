/**
 * 顶栏：一个圆框一横排 —— 历史对话 / 标题 / 模型 chip / 连接绿点。
 *
 * 三个触发区（互斥，各自 stopPropagation，避免一次点击弹两层）：
 * - 左边「历史对话」按钮 → 历史面板；
 * - 圆框其余区域（**标题**、标题下面那一行、留白）→ 会话设置菜单；
 * - 右上角连接绿点 → 会话信息条（会话 id + 构建时间）。
 *
 * 绿点原来只是状态指示，现在兼任信息入口：它是右上角唯一的空位，而「id / 构建」
 * 属于排障信息，与「连接状态」正好同一语义层级。绿点本体只有 7px，外面套了一个
 * 24px 命中区的透明按钮，否则手机上点不中。
 */
import * as React from 'react'
import { formatTokens, type ContextUsageInfo } from './context-usage'

export interface TopbarProps {
  title: string
  sessionId: string
  connected: boolean
  /** 未连接时的原因文案（作为状态点 title） */
  connection: string
  /** 有错误提示：状态点转红 */
  hasNotice: boolean
  /** 页面 bundle 落后于服务端 */
  staleBundle: boolean
  /** 会话信息是否已到：未到时菜单里的模型/权限不可选 */
  metaReady: boolean
  modelLabel: string
  settingsMenuOpen: boolean
  onToggleSettings: () => void
  /** 历史对话面板 */
  historyOpen: boolean
  onToggleHistory: () => void
  /** 还没有消息时不显示历史入口（点了也是空面板） */
  historyAvailable: boolean
  /** 会话信息条（绿点触发） */
  infoOpen: boolean
  onOpenInfo: () => void
  /** 上下文占用（窗口/用量任一缺失时不渲染） */
  contextUsage?: ContextUsageInfo
  /** 点百分比：打开设置菜单并直接定位到「压缩上下文」 */
  onOpenCompact: () => void
}

export function Topbar({
  title, sessionId, connected, connection, hasNotice, staleBundle,
  metaReady, modelLabel, settingsMenuOpen, onToggleSettings,
  historyOpen, onToggleHistory, historyAvailable, infoOpen, onOpenInfo, contextUsage, onOpenCompact,
}: TopbarProps): React.ReactElement {
  return (
    <header className="topbar">
      {/* 圆框整块可点：标题、标题下面那一行、留白都进设置菜单；只有历史按钮与绿点例外 */}
      <div className="title-pill" onClick={onToggleSettings} title={title || sessionId}>
        <button
          type="button"
          className="title-text title-button"
          aria-expanded={settingsMenuOpen}
          aria-haspopup="menu"
          onClick={(event) => {
            // 标题本身也在可点区里：显式处理一次，键盘用户（Tab + Enter）才能触发
            event.stopPropagation()
            onToggleSettings()
          }}
        >
          {title || 'Profer 会话'}
        </button>
        {/* 模型行 + 上下文占用同一行：百分比紧挨「标准 · 模型 · 最高」，一眼知道它属于这一会话 */}
        <div className="model-row">
          <button
            className="model-chip"
            onClick={(event) => {
              // 这一行同样进设置菜单；不 stopPropagation 会冒泡到胶囊，toggle 两次等于没点
              event.stopPropagation()
              onToggleSettings()
            }}
            aria-expanded={settingsMenuOpen}
            aria-haspopup="menu"
            disabled={!metaReady}
            title="切换模型、强度、预设与目标"
          >
            {modelLabel}
          </button>
          {contextUsage?.percent != null && (
            <button
              type="button"
              className={`ctx-chip${contextUsage.warning ? ' warn' : ''}`}
              onClick={(event) => {
                event.stopPropagation()
                onOpenCompact()
              }}
              title={`上下文已用 ${formatTokens(contextUsage.used)} / ${formatTokens(contextUsage.window)} tokens（${contextUsage.percent}%），点按定位到压缩上下文`}
              aria-label={`上下文已用 ${contextUsage.percent}%，点按定位到压缩上下文`}
            >
              {contextUsage.warning ? '⚠ ' : ''}{contextUsage.percent}%
            </button>
          )}
        </div>
        {/* 左右固定：历史入口在最左、连接绿点在最右，不参与两行居中 */}
        <div className="pill-aside">
          {historyAvailable && (
            <button
              type="button"
              className="pill-icon"
              onClick={(event) => {
                event.stopPropagation()
                onToggleHistory()
              }}
              title="历史对话"
              aria-label="历史对话"
              aria-pressed={historyOpen}
            >
              ≡
            </button>
          )}
        </div>
        <div className="pill-aside right">
          {staleBundle && (
            <span className="stale-badge" title="页面代码落后于服务端，硬刷新（Ctrl+Shift+R）后生效">
              ⚠
            </span>
          )}
          <button
            type="button"
            className="conn-btn"
            data-open={infoOpen ? 'true' : undefined}
            onClick={(event) => {
              event.stopPropagation()
              onOpenInfo()
            }}
            title={`${connected ? '已连接' : connection} · 查看会话信息`}
            aria-label={`${connected ? '已连接' : connection} · 查看会话信息`}
            aria-expanded={infoOpen}
          >
            <span
              className={`conn-dot ${connected ? 'ok' : hasNotice ? 'bad' : 'wait'}`}
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
    </header>
  )
}
