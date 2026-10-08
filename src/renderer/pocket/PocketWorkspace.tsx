import React from 'react'
import { createPortal } from 'react-dom'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'
import { LeftSidebar } from '@/components/app-shell/LeftSidebar'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { Link, Loader2, Menu, RefreshCw } from 'lucide-react'
import { PocketSessionContent } from './PocketSessionContent'

export interface PocketWorkspaceProps {
  connection: 'idle' | 'connecting' | 'open' | 'reconnecting' | 'error' | 'unauthorized'
  reconnectBannerText: string
  visualTop: number
  landscapeWide: boolean
  sidebarOpen: boolean
  activeTitle: string
  appMode: 'agent' | 'chat' | 'scratch'
  currentSessionId: string | null
  currentChatId: string | null
  userName: string
  safeAreaClassName: string
  onOpenSidebar: () => void
  onCloseSidebar: () => void
  onRefresh: () => void
  onRequestUnbind: () => void
  onCreateSession: () => void
  onCreateConversation: () => void
  unbindConfirmOpen: boolean
  onUnbindConfirmOpenChange: (open: boolean) => void
  onUnbind: () => void
}

export function PocketWorkspace({
  connection,
  reconnectBannerText,
  visualTop,
  landscapeWide,
  sidebarOpen,
  activeTitle,
  appMode,
  currentSessionId,
  currentChatId,
  userName,
  safeAreaClassName,
  onOpenSidebar,
  onCloseSidebar,
  onRefresh,
  onRequestUnbind,
  onCreateSession,
  onCreateConversation,
  unbindConfirmOpen,
  onUnbindConfirmOpenChange,
  onUnbind,
}: PocketWorkspaceProps): React.ReactElement {
  return (
    <>
      {connection !== 'open' && createPortal(
        <div className="fixed inset-x-0 z-40 flex justify-center px-4" style={{ top: `calc(${visualTop}px + ${landscapeWide ? '12px' : '60px'} + env(safe-area-inset-top))` }}>
          <div className="pointer-events-auto flex items-center gap-2 rounded-full bg-amber-500/95 px-4 py-1.5 text-[12px] font-medium text-white shadow-lg">
            <Loader2 className="size-3.5 animate-spin" />
            <span>{reconnectBannerText}</span>
          </div>
        </div>,
        document.body,
      )}

      {!landscapeWide && !sidebarOpen && createPortal(
        <div className="fixed inset-x-0 z-30 flex h-12 items-center bg-tabbar-surface/90 px-2 backdrop-blur-md" style={{ top: `calc(${visualTop}px + env(safe-area-inset-top))` }}>
          <Button type="button" variant="ghost" size="icon" onClick={onOpenSidebar} className="mr-1 size-10 shrink-0 rounded-[12px] text-foreground/65 hover:bg-foreground/[0.06]" aria-label="打开导航"><Menu className="size-[18px]" /></Button>
          <div className="min-w-0 flex-1 px-1"><span className="block truncate text-sm font-medium text-foreground">{activeTitle}</span></div>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button type="button" variant="ghost" size="icon" onClick={onRefresh} className="size-10 shrink-0 rounded-[12px] text-foreground/65 hover:bg-foreground/[0.06]" aria-label="刷新当前内容"><RefreshCw className="size-[18px]" /></Button>
            </TooltipTrigger>
            <TooltipContent side="bottom">刷新当前内容</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button type="button" variant="ghost" size="icon" onClick={onRequestUnbind} className="relative size-10 shrink-0 rounded-[12px] text-foreground/65 hover:bg-foreground/[0.06]" aria-label="已绑定，点击解绑">
                <Link className="size-[18px]" />
                <span className="absolute right-1.5 top-1.5 size-1.5 rounded-full bg-emerald-500" aria-hidden />
              </Button>
            </TooltipTrigger>
            <TooltipContent side="bottom">已绑定 · 点击解绑</TooltipContent>
          </Tooltip>
        </div>,
        document.body,
      )}

      <div className={`pocket-app-root flex h-full w-full overflow-hidden bg-background p-0 text-foreground landscape:min-[1024px]:p-2 ${safeAreaClassName}`}>
        <NativePocketSidebar mobileOpen={sidebarOpen} onOpen={onOpenSidebar} onDismiss={onCloseSidebar} wide={landscapeWide} safeAreaClassName={safeAreaClassName}>
          <div className="flex min-h-0 flex-1 flex-col touch-pan-y">
            <PocketSessionContent
              appMode={appMode}
              currentSessionId={currentSessionId}
              currentChatId={currentChatId}
              userName={userName}
              wide={landscapeWide}
              onCreateSession={onCreateSession}
              onCreateConversation={onCreateConversation}
            />
          </div>
        </NativePocketSidebar>
      </div>

      <AlertDialog open={unbindConfirmOpen} onOpenChange={onUnbindConfirmOpenChange}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>解绑此设备？</AlertDialogTitle>
            <AlertDialogDescription>解绑后将清除本机保存的服务器地址和访问令牌，断开当前连接并回到连接页，需要重新输入才能继续使用。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction className="bg-destructive text-destructive-foreground hover:bg-destructive/90" onClick={onUnbind}>确认解绑</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

function NativePocketSidebar({ mobileOpen, onOpen, onDismiss, wide, safeAreaClassName, children }: { mobileOpen: boolean; onOpen: () => void; onDismiss: () => void; wide: boolean; safeAreaClassName: string; children: React.ReactNode }): React.ReactElement {
  const viewportWidth = window.innerWidth
  const conversationRightDistance = 30
  const conversationLeft = viewportWidth - conversationRightDistance
  const panelGap = 8
  const sidebarWidth = Math.max(120, conversationLeft - panelGap)
  const touchStartRef = React.useRef<{ x: number; y: number } | null>(null)

  const handleTouchStart = (event: React.TouchEvent<HTMLDivElement>): void => {
    const touch = event.touches[0]
    if (!touch) return
    touchStartRef.current = { x: touch.clientX, y: touch.clientY }
  }

  const handleTouchEnd = (event: React.TouchEvent<HTMLDivElement>): void => {
    const start = touchStartRef.current
    touchStartRef.current = null
    const touch = event.changedTouches[0]
    if (!start || !touch || wide) return

    const deltaX = touch.clientX - start.x
    const deltaY = touch.clientY - start.y
    if (Math.abs(deltaX) < 64 || Math.abs(deltaX) < Math.abs(deltaY) * 1.35) return

    if (!mobileOpen && deltaX > 0) onOpen()
    if (mobileOpen && deltaX < 0) onDismiss()
  }

  return (
    <>
      <div className="hidden h-full shrink-0 landscape:min-[1024px]:block"><LeftSidebar width={288} pocketMode /></div>
      <div className="relative h-full min-w-0 flex-1 overflow-hidden landscape:min-[1024px]:block">
        {wide ? (
          <div className="flex h-full min-w-0 flex-col overflow-hidden bg-content-area pt-0 landscape:min-[1024px]:ml-2 landscape:min-[1024px]:rounded-[24px] landscape:min-[1024px]:border landscape:min-[1024px]:border-border/70 landscape:min-[1024px]:shadow-xl">{children}</div>
        ) : (
          <div
            className="relative h-full w-full overflow-hidden"
            onTouchStart={handleTouchStart}
            onTouchEnd={handleTouchEnd}
            onTouchCancel={() => { touchStartRef.current = null }}
          >
            <div
              className={`absolute left-0 top-[10px] bottom-0 overflow-hidden bg-background transition-[left,width] duration-300 sidebar-collapse-ease ${safeAreaClassName}`}
              style={{ left: mobileOpen ? 0 : -viewportWidth, width: sidebarWidth }}
              onClick={(event) => {
                if ((event.target as Element | null)?.closest?.('[data-profer-navigation-item="session"][data-profer-navigation-active="true"]')) onDismiss()
              }}
            >
              <LeftSidebar width={sidebarWidth} pocketMode flush onCollapse={onDismiss} renderSearchDialog={false} />
            </div>
            <div
              className={`absolute inset-y-3 z-10 flex flex-col border border-border/50 bg-content-area shadow-[0_12px_32px_-20px_rgb(0_0_0_/_0.38)] transition-[left,border-radius,box-shadow] duration-300 sidebar-collapse-ease ${mobileOpen ? 'rounded-[28px]' : 'rounded-none border-transparent shadow-none'}`}
              style={{ left: mobileOpen ? conversationLeft : 0, width: viewportWidth }}
            >{children}</div>
          </div>
        )}
      </div>
    </>
  )
}
