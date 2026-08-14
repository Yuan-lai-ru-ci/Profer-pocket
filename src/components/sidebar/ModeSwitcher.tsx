/**
 * ModeSwitcher — 移动端 Agent/Chat 模式切换（apps/tablet-client）
 *
 * 对齐桌面 app-shell/ModeSwitcher.tsx 的滑动指示器视觉，精简：
 *  - 去 navigationController / 键盘导航（移动端无键盘）
 *  - 去 Tab 恢复逻辑（移动端无多 Tab）
 * 切换后仅置 appModeAtom，侧边栏和主区据此切换视图。
 */

import * as React from 'react'
import { useAtom } from 'jotai'
import { Bot, MessageSquare } from 'lucide-react'
import { appModeAtom, type AppMode } from '@/atoms/app-mode'
import { cn } from '@/lib/utils'

const modes: { value: AppMode; label: string; icon: React.ReactNode }[] = [
  { value: 'agent', label: 'Agent', icon: <Bot size={15} /> },
  { value: 'chat', label: 'Chat', icon: <MessageSquare size={15} /> },
]

export function ModeSwitcher(): React.ReactElement {
  const [mode, setMode] = useAtom(appModeAtom)

  return (
    <div className="pt-2 select-none">
      <div className="relative flex rounded-xl p-1 bg-primary/5">
        <div
          className={cn(
            'mode-slider pointer-events-none absolute top-1 bottom-1 w-[calc(50%-4px)] rounded-lg bg-background shadow-sm transition-transform duration-300 ease-in-out',
            mode === 'agent' ? 'translate-x-0' : 'translate-x-full',
          )}
        />
        {modes.map(({ value, label, icon }) => {
          const isActive = mode === value
          return (
            <button
              key={value}
              type="button"
              aria-pressed={isActive}
              onClick={() => setMode(value)}
              className={cn(
                'relative z-[1] h-8 flex-1 flex items-center justify-center gap-1.5 rounded-lg px-3 py-0 text-sm font-medium transition-colors duration-200 select-none',
                isActive ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {icon}
              {label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

