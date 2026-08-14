/**
 * Composer.tsx — 输入区（移动端瘦客户端 · 富文本版）
 *
 * 对齐桌面 AgentView 输入区：内部用 TipTap 富文本 RichTextInput（迁移自桌面
 * ai-elements/rich-text-input.tsx），外面套发送/停止圆形按钮。
 *
 * 与桌面差异（移动端无 IPC 数据源）：
 *  - 不启 Mentions（@文件 /Skill #MCP &会话）：无文件树/skill/mcp 列表来源。
 *  - 富文本格式化（粗斜体/标题/列表/代码块/引用/表格/折叠/粘贴）完整保留。
 *
 * tabletMode 行为：普通 Enter 换行（触屏无 Shift），发送靠按钮或 Ctrl/Cmd+Enter。
 */

import * as React from 'react'
import { CornerDownLeft, Square } from 'lucide-react'
import { RichTextInput } from '@/components/ai-elements/rich-text-input'

export interface ComposerProps {
  value: string
  onChange: (v: string) => void
  onSend: () => void
  onStop: () => void
  /** 是否能发送（消息非空 + 渠道就绪 + 不在 stopping） */
  canSend: boolean
  /** 流式状态：running / stopping / backgroundWaiting */
  streaming: boolean
  backgroundWaiting: boolean
  stopping: boolean
  disabled?: boolean
  /** 输入框占位文案（默认 Agent 文案，Chat 等场景可覆盖） */
  placeholder?: string
}

export function Composer({
  value,
  onChange,
  onSend,
  onStop,
  canSend,
  streaming,
  backgroundWaiting,
  stopping,
  disabled,
  placeholder,
}: ComposerProps): React.ReactElement {
  const isActive = streaming || backgroundWaiting || stopping

  const handleSubmit = (): void => {
    if (isActive) {
      onStop()
    } else {
      onSend()
    }
  }

  return (
    <div className="shrink-0 px-2.5 pb-2.5">
      <div className="agent-input-surface relative z-10 rounded-[17px] border-[0.5px] border-border bg-background/70 backdrop-blur-sm transition-[background-color,border-color,box-shadow] duration-200">
        {/* 富文本编辑器本体（对齐桌面：ProseMirror padding 5px 15px 0） */}
        <RichTextInput
          value={value}
          onChange={onChange}
          onSubmit={onSend}
          placeholder={placeholder ?? (isActive ? '' : '发送消息给 Agent…')}
          disabled={disabled}
          collapsible
          tabletMode
          autoFocusTrigger={undefined}
        />
        {/* 发送/停止按钮（对齐桌面 inputTrailingNode：ghost + rounded-full 44px，居右） */}
        <div className="flex items-center justify-end px-2 pb-2">
          <button
            type="button"
            aria-label={isActive ? '停止' : '发送'}
            onClick={handleSubmit}
            disabled={disabled || (!isActive && !canSend)}
            className={`flex h-[44px] w-[44px] shrink-0 items-center justify-center rounded-full transition-colors ${
              isActive
                ? 'text-destructive hover:bg-[var(--stop-hover-bg)]'
                : canSend
                  ? 'text-primary hover:bg-primary/10'
                  : 'cursor-not-allowed text-foreground/30'
            }`}
          >
            {isActive
              ? <Square className="size-4 fill-current" strokeWidth={0} />
              : <CornerDownLeft className="size-[22px]" />}
          </button>
        </div>
      </div>
      {stopping && <div className="mt-1 px-1 text-xs text-muted-foreground/50">正在停止 Agent…</div>}
    </div>
  )
}
