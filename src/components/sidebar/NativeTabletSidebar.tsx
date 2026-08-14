/**
 * NativeTabletSidebar.tsx — 移动端侧边栏双形态（apps/tablet-client）
 *
 * 对齐桌面 tablet/main.tsx 的 NativeTabletSidebar：
 * - 横屏且 ≥1024px：固定侧栏（.landscape:min-[1024px]:block）
 * - 竖屏/小屏：抽屉（滑出 + 遮罩 + Escape 关闭）
 *
 * 移动端差异：
 * - 抽屉实例 renderSearchDialog=false，SearchDialog 由固定侧栏实例承担（全局 atom + Portal），
 *   避免双实例同时打开叠双遮罩（桌面同样处理）。
 */

import * as React from 'react'
import { LeftSidebar } from './LeftSidebar'

interface NativeTabletSidebarProps {
  mobileOpen: boolean
  onDismiss: () => void
}

export function NativeTabletSidebar({ mobileOpen, onDismiss }: NativeTabletSidebarProps): React.ReactElement {
  // 横屏固定侧栏是否折叠成窄条（竖屏抽屉收起就是关闭抽屉，无需此态）
  const [landscapeCollapsed, setLandscapeCollapsed] = React.useState(false)

  // Escape 关闭抽屉
  React.useEffect(() => {
    if (!mobileOpen) return
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') onDismiss()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [mobileOpen, onDismiss])

  return (
    <>
      {/* 横屏固定侧栏（可折叠成窄条） */}
      <div className="hidden h-full shrink-0 landscape:min-[1024px]:block">
        <LeftSidebar
          width={landscapeCollapsed ? 60 : 288}
          collapsed={landscapeCollapsed}
          onCollapse={() => setLandscapeCollapsed(true)}
          onExpand={() => setLandscapeCollapsed(false)}
        />
      </div>

      {/* 竖屏抽屉（收起即关闭抽屉） */}
      <div
        className={`fixed inset-0 z-50 landscape:min-[1024px]:hidden ${mobileOpen ? 'pointer-events-auto' : 'pointer-events-none'}`}
        aria-hidden={!mobileOpen}
      >
        <button
          type="button"
          className={`absolute inset-0 z-0 bg-black/40 transition-opacity duration-200 ${mobileOpen ? 'opacity-100' : 'opacity-0'}`}
          onClick={onDismiss}
          aria-label="关闭会话导航"
          tabIndex={mobileOpen ? 0 : -1}
        />
        <div
          className={`absolute inset-y-0 left-0 z-10 touch-pan-y transition-transform duration-200 ease-out ${mobileOpen ? 'translate-x-0' : '-translate-x-full'}`}
        >
          <LeftSidebar width={288} renderSearchDialog={false} onCollapse={onDismiss} />
        </div>
      </div>
    </>
  )
}
