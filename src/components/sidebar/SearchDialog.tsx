/**
 * SearchDialog.tsx — 移动端全局搜索对话框（apps/tablet-client）
 *
 * 对齐桌面 app-shell/SearchDialog.tsx 的「按标题过滤会话」能力，但大幅精简：
 * - 只搜索 Agent 会话标题（移动端无 Chat 数据源，无消息内容搜索 IO）
 * - 输入即过滤（本地内存，无主进程 IO 卡顿问题，无需手动触发）
 * - 键盘导航：上下箭头 + Enter 打开 + Esc 关闭
 *
 * 绑定全局 atom（searchDialogOpenAtom）+ Portal 到 body。与横屏固定侧栏/竖屏抽屉
 * 共享同一实例，只渲染一份（避免双遮罩）。
 */

import * as React from 'react'
import { useAtom, useAtomValue } from 'jotai'
import { Search, X, Bot } from 'lucide-react'
import { Dialog, DialogContent, DialogPortal, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import { searchDialogOpenAtom } from '@/atoms/sidebar-atoms'
import { sessionsAtom, currentSessionIdAtom } from '@/atoms/session'
import type { AgentSessionMeta } from '@profer/shared'

export function SearchDialog({ onOpenSession }: { onOpenSession?: (id: string) => void }): React.ReactElement {
  const [open, setOpen] = useAtom(searchDialogOpenAtom)
  const sessions = useAtomValue(sessionsAtom)
  const currentId = useAtomValue(currentSessionIdAtom)
  const [query, setQuery] = React.useState('')
  const [selectedIndex, setSelectedIndex] = React.useState(0)

  const filtered = React.useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return []
    return sessions
      .filter((s) => !s.draft)
      .filter((s) => (s.title || '').toLowerCase().includes(q))
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, 50)
  }, [sessions, query])

  React.useEffect(() => {
    if (open) {
      setQuery('')
      setSelectedIndex(0)
    }
  }, [open])

  const handleSelect = React.useCallback(
    (session: AgentSessionMeta) => {
      onOpenSession?.(session.id)
      setOpen(false)
    },
    [setOpen, onOpenSession],
  )

  const handleKeyDown = (e: React.KeyboardEvent): void => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setSelectedIndex((i) => Math.min(i + 1, filtered.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setSelectedIndex((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const target = filtered[selectedIndex]
      if (target) handleSelect(target)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogPortal>
        <DialogContent
          hideClose
          className="top-[20%] translate-y-0 p-0 gap-0 max-w-md"
        >
          <DialogTitle className="sr-only">搜索会话</DialogTitle>
          <div className="flex items-center gap-2 border-b border-border/50 px-4 py-3">
            <Search size={16} className="text-muted-foreground" />
            <input
              autoFocus
              value={query}
              onChange={(e) => {
                setQuery(e.target.value)
                setSelectedIndex(0)
              }}
              onKeyDown={handleKeyDown}
              placeholder="搜索会话标题…"
              className="flex-1 min-w-0 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground/50"
            />
            <button
              onClick={() => setOpen(false)}
              className="flex-shrink-0 text-muted-foreground/50 hover:text-foreground transition-colors"
              aria-label="关闭"
            >
              <X size={16} />
            </button>
          </div>

          <div className="max-h-[320px] overflow-y-auto p-1.5">
            {query.trim() === '' ? (
              <div className="px-3 py-8 text-center text-[13px] text-muted-foreground/50">
                输入关键词搜索会话标题
              </div>
            ) : filtered.length === 0 ? (
              <div className="px-3 py-8 text-center text-[13px] text-muted-foreground/50">
                无匹配会话
              </div>
            ) : (
              filtered.map((session, i) => (
                <button
                  key={session.id}
                  onClick={() => {
                    handleSelect(session)
                  }}
                  onMouseEnter={() => setSelectedIndex(i)}
                  className={cn(
                    'w-full flex items-center gap-2 rounded-md px-2.5 py-2 text-left text-[13px] transition-colors',
                    i === selectedIndex ? 'bg-accent text-accent-foreground' : 'hover:bg-muted',
                    session.id === currentId && 'text-foreground',
                  )}
                >
                  <Bot size={14} className="flex-shrink-0 text-muted-foreground/50" />
                  <span className="flex-1 min-w-0 truncate">{session.title || '未命名会话'}</span>
                  {session.archived && (
                    <span className="flex-shrink-0 text-[11px] text-muted-foreground/50">已归档</span>
                  )}
                </button>
              ))
            )}
          </div>
        </DialogContent>
      </DialogPortal>
    </Dialog>
  )
}
