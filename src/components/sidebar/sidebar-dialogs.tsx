/**
 * sidebar-dialogs.tsx — 移动端侧边栏弹窗（apps/tablet-client）
 *
 * 对齐桌面 left-sidebar/sidebar-dialogs.tsx 的删除确认 / 迁移确认弹窗，
 * 移动端精简：仅保留 删除会话确认 + 迁移项目选择（MoveSessionDialog 的桌面专属
 * 弹窗不搬，用简化 AlertDialog + 项目下拉/列表替代）。
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/components/ui/alert-dialog'
import { workspacesAtom } from '@/atoms/session'
import type { SidebarModel } from './use-left-sidebar'

export function SidebarDialogs({ s }: { s: SidebarModel }): React.ReactElement {
  const {
    pendingDeleteId,
    handleConfirmDelete,
    setPendingDeleteId,
    moveTargetId,
    handleMoveSession,
    setMoveTargetId,
    workspaces,
    currentWorkspaceId,
  } = s

  const moveTargets = React.useMemo(
    () => workspaces.filter((w) => w.id !== currentWorkspaceId),
    [workspaces, currentWorkspaceId],
  )

  return (
    <>
      {/* 删除确认 */}
      <AlertDialog open={pendingDeleteId != null} onOpenChange={(open) => { if (!open) setPendingDeleteId(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除会话</AlertDialogTitle>
            <AlertDialogDescription>
              确定删除该会话？此操作不可撤销，会话消息将被永久删除。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={() => void handleConfirmDelete()} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 迁移项目 */}
      <AlertDialog open={moveTargetId != null} onOpenChange={(open) => { if (!open) setMoveTargetId(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>迁移会话到项目</AlertDialogTitle>
            <AlertDialogDescription>选择目标项目，会话将移动到该项目。</AlertDialogDescription>
          </AlertDialogHeader>
          <div className="flex flex-col gap-1 max-h-[240px] overflow-y-auto">
            {moveTargets.length === 0 ? (
              <p className="px-1 py-2 text-[13px] text-muted-foreground/50">暂无其他项目</p>
            ) : (
              moveTargets.map((w) => (
                <button
                  key={w.id}
                  onClick={() => void handleMoveSession(w.id)}
                  className="rounded-md px-2.5 py-2 text-left text-[13px] text-foreground hover:bg-muted transition-colors"
                >
                  {w.name}
                </button>
              ))
            )}
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
