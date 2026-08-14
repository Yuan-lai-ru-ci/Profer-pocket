/**
 * LeftSidebar.tsx — 移动端侧边栏外壳（apps/tablet-client）
 *
 * 对齐桌面 app-shell/LeftSidebar.tsx 的薄壳职责：只负责 assembler（hook + 视图 + 对话框 + 搜索）。
 * 移动端无折叠窄栏（rail），始终展开态；不渲染 Electron 专属 Dialogs。
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import { PanelLeftClose } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { appModeAtom } from '@/atoms/app-mode'
import { SearchDialog } from './SearchDialog'
import { useLeftSidebar } from './use-left-sidebar'
import { ExpandedSidebar } from './expanded-sidebar'
import { SidebarDialogs } from './sidebar-dialogs'
import { ModeSwitcher } from './ModeSwitcher'
import { ChatConversationList } from './ChatConversationList'
import { SidebarRail } from './SidebarRail'

export interface LeftSidebarProps {
  /** 可选固定宽度，默认 288 */
  width?: number
  /** 是否渲染全局搜索对话框（移动端横屏固定侧栏 + 竖屏抽屉共存时只渲染一份） */
  renderSearchDialog?: boolean
  /** 折叠态（横屏固定侧栏收起成窄条；对齐桌面 sidebarCollapsed） */
  collapsed?: boolean
  /** 点击“收起侧边栏”按钮（展开态顶部 ModeSwitcher 旁）时的回调 */
  onCollapse?: () => void
  /** 点击 窄条 的“展开侧边栏”按钮时的回调 */
  onExpand?: () => void
}

export function LeftSidebar({ width = 288, renderSearchDialog = true, collapsed = false, onCollapse, onExpand }: LeftSidebarProps): React.ReactElement {
  const s = useLeftSidebar()
  const mode = useAtomValue(appModeAtom)

  return (
    <div
      className={cn(
        'relative h-full overflow-hidden bg-[hsl(var(--sidebar-surface))] rounded-2xl shadow-xl dark:shadow-md',
      )}
      style={{ width, minWidth: collapsed ? width : 200 }}
    >
      <div className="flex h-full flex-col">
        {collapsed ? (
          <SidebarRail s={s} onExpand={onExpand ?? (() => undefined)} />
        ) : (
          <>
            {/* 顶部行：Agent/Chat 切换 + 收起按钮（对齐原版 ExpandedSidebar 顶部结构） */}
            <div className="flex items-start gap-1.5 px-3">
              <div className="min-w-0 flex-1">
                <ModeSwitcher />
              </div>
              {onCollapse && (
                <Tooltip>
                  <TooltipTrigger asChild>
                    <button
                      type="button"
                      onClick={onCollapse}
                      className="sidebar-collapse-button mt-2 flex size-10 shrink-0 items-center justify-center rounded-[10px] text-foreground/40 bg-primary/5 hover:bg-primary/10 hover:text-foreground/60 transition-[background-color,border-color,color] duration-150 border border-border/60 hover:border-border"
                      aria-label="收起侧边栏"
                    >
                      <PanelLeftClose size={14} />
                    </button>
                  </TooltipTrigger>
                  <TooltipContent side="right">收起侧边栏</TooltipContent>
                </Tooltip>
              )}
            </div>
            {mode === 'chat' ? (
              <ChatConversationList />
            ) : (
              <ExpandedSidebar s={s} />
            )}
            {mode !== 'chat' && <SidebarDialogs s={s} />}
            {renderSearchDialog && <SearchDialog onOpenSession={(id) => s.handleSelectAgentSession(id, '')} />}
          </>
        )}
      </div>
    </div>
  )
}
