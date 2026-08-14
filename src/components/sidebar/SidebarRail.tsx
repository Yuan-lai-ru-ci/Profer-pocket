/**
 * SidebarRail.tsx — 折叠态窄条（apps/tablet-client）
 *
 * 对齐桌面 left-sidebar/rail.tsx 的移动端子集：
 * - 展开侧边栏按钮（唯一布局控制）
 * - Agent/Chat 模式切换
 * - 新建会话 / 搜索
 * - 底部用户头像（点开设置占位）
 *
 * 复用与 ExpandedSidebar 同一次 useLeftSidebar()（通过 SidebarModel s 传入），
 * 避免重复实例化 hook 造成状态不同步。
 */

import * as React from 'react'
import { useAtom, useAtomValue } from 'jotai'
import { PanelLeftOpen, Bot, MessageSquare, Plus, Search } from 'lucide-react'
import { toast } from 'sonner'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { appModeAtom } from '@/atoms/app-mode'
import { userProfileAtom, DEFAULT_USER_AVATAR } from '@/atoms/ui-atoms'
import { UserAvatar } from '@/components/chat/UserAvatar'
import type { SidebarModel } from './use-left-sidebar'

export function SidebarRail({ s, onExpand }: { s: SidebarModel; onExpand: () => void }): React.ReactElement {
  const [mode, setMode] = useAtom(appModeAtom)
  const userProfile = useAtomValue(userProfileAtom)

  const iconBtn = 'flex size-10 items-center justify-center rounded-[12px] transition-colors'

  return (
    <div className="relative flex h-full flex-col items-center px-2 pt-2">
      {/* 展开按钮 */}
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            aria-label="展开侧边栏"
            onClick={onExpand}
            className={`${iconBtn} text-foreground/60 bg-muted hover:bg-foreground/[0.08] hover:text-foreground`}
          >
            <PanelLeftOpen size={17} />
          </button>
        </TooltipTrigger>
        <TooltipContent side="right">展开侧边栏</TooltipContent>
      </Tooltip>

      <div className="my-3 h-px w-8 bg-border/70" />

      {/* 模式切换 */}
      <div className="flex flex-col items-center gap-1.5">
        {([
          { value: 'agent', icon: <Bot size={18} />, label: 'Agent 模式' },
          { value: 'chat', icon: <MessageSquare size={17} />, label: 'Chat 模式' },
        ] as const).map(({ value, icon, label }) => (
          <Tooltip key={value}>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-label={label}
                onClick={() => setMode(value)}
                className={`${iconBtn} ${
                  mode === value
                    ? 'bg-primary/10 text-foreground'
                    : 'text-foreground/45 hover:bg-foreground/[0.06] hover:text-foreground/75'
                }`}
              >
                {icon}
              </button>
            </TooltipTrigger>
            <TooltipContent side="right">{label}</TooltipContent>
          </Tooltip>
        ))}
      </div>

      <div className="my-3 h-px w-8 bg-border/70" />

      {/* 新建会话 + 搜索 */}
      <div className="flex flex-col items-center gap-1.5">
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              aria-label={mode === 'agent' ? '新建 Agent 会话' : '新建 Chat 对话'}
              onClick={() => void s.handleNewAgentSession()}
              className={`${iconBtn} text-foreground/70 bg-primary/5 hover:bg-primary/10 hover:text-foreground border border-border/60 hover:border-border`}
            >
              <Plus size={16} />
            </button>
          </TooltipTrigger>
          <TooltipContent side="right">{mode === 'agent' ? '新会话' : '新对话'}</TooltipContent>
        </Tooltip>
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              aria-label="搜索"
              onClick={() => s.setSearchDialogOpen(true)}
              className={`${iconBtn} text-foreground/45 bg-primary/5 hover:bg-primary/10 hover:text-foreground/70 border border-border/60 hover:border-border`}
            >
              <Search size={16} />
            </button>
          </TooltipTrigger>
          <TooltipContent side="right">搜索</TooltipContent>
        </Tooltip>
      </div>

      <div className="my-3 h-px w-8 bg-border/70" />

      {/* 底部：用户头像 */}
      <div className="mt-auto flex flex-col items-center gap-2 pb-3">
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              aria-label="打开设置"
              onClick={() => toast.info('设置面板暂未开放（移动端待实现）')}
              className="flex size-10 items-center justify-center rounded-[12px] hover:bg-foreground/5"
            >
              <UserAvatar avatar={userProfile.avatar || DEFAULT_USER_AVATAR} size={28} />
            </button>
          </TooltipTrigger>
          <TooltipContent side="right">设置</TooltipContent>
        </Tooltip>
      </div>
    </div>
  )
}
