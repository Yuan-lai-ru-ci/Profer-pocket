import * as React from 'react'
import { useAtomValue } from 'jotai'
import { Download, RotateCw, X } from 'lucide-react'
import { toast } from 'sonner'
import { pocketUpdateCardHiddenAtom, pocketUpdateDeferredAtom, pocketUpdateStatusAtom } from '@/atoms/pocket-updater'
import { writeClipboardText } from '@/lib/clipboard'
import type { Store } from 'jotai/vanilla/store'
import { deferPocketUpdate, downloadAvailablePocketUpdate, hidePocketUpdateCard, installPocketUpdate, retryPocketUpdate } from '@/atoms/pocket-updater'

function formatBytes(bytes: number): string { return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(0, bytes / 1024).toFixed(0)} KB` }

export function PocketUpdateCard({ store }: { store: Store }): React.ReactElement | null {
  const status = useAtomValue(pocketUpdateStatusAtom, { store })
  const deferred = useAtomValue(pocketUpdateDeferredAtom, { store })
  const hidden = useAtomValue(pocketUpdateCardHiddenAtom, { store })
  const update = status.status === 'available' || status.status === 'downloading' || status.status === 'downloaded' || status.status === 'error' ? status.update : undefined
  if (!update || hidden || deferred || status.status === 'checking' || status.status === 'not-available' || status.status === 'idle') return null

  const copy = async (): Promise<void> => {
    const ok = await writeClipboardText(update.apkDownloadUrl)
    if (ok) toast.success('APK 下载地址已复制')
    else toast.error('复制失败，请手动复制下载地址')
  }
  const downloading = status.status === 'downloading'
  const downloaded = status.status === 'downloaded'
  const cachedAfterFailure = status.status === 'error' && Boolean(status.apkPath)
  const failed = status.status === 'error'

  // R5：Android WebView / 卓易通 的 env(safe-area-inset-top) 恒为 0，原生侧注入 --pocket-safe-top；
  // 变量缺失/为 0 时与旧写法等价（max(0.5rem, env())）。
  return (
    <aside className="fixed right-2 z-[90] w-[min(18rem,calc(100vw-1rem))] rounded-lg border border-border bg-background px-2.5 py-2 shadow-lg" style={{ top: 'max(0.5rem, max(env(safe-area-inset-top), var(--pocket-safe-top, 0px)))' }} aria-label="应用更新">
      <button className="absolute right-1.5 top-1.5 rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground" onClick={() => downloading ? hidePocketUpdateCard(store) : deferPocketUpdate(store)} aria-label={downloading ? '隐藏下载提示' : '稍后提醒'}><X className="size-3.5" /></button>
      <div className="pr-5 text-xs font-medium">{downloaded ? '更新已就绪' : downloading ? '正在下载更新' : failed ? '更新操作失败' : '发现新版本'}</div>
      <p className="mt-0.5 text-[11px] leading-4 text-muted-foreground">v{update.versionName}{failed ? `：${status.message}` : downloaded ? ' 已验证，可安装。' : ' 已发布。'}</p>
      {downloading && <div className="mt-2 space-y-1"><div className="h-1 overflow-hidden rounded bg-muted"><div className="h-full bg-primary transition-all" style={{ width: `${Math.max(0, Math.min(100, status.progress.percent))}%` }} /></div><div className="flex justify-between text-[10px] text-muted-foreground"><span>{status.progress.total > 0 ? `${formatBytes(status.progress.transferred)} / ${formatBytes(status.progress.total)}` : `${formatBytes(status.progress.transferred)} 已下载`}</span><span>{status.progress.total > 0 ? `${Math.round(status.progress.percent)}%` : '下载中'}</span></div></div>}
      <div className="mt-2 flex flex-wrap justify-end gap-1.5">
        {failed && <button className="text-xs text-muted-foreground hover:text-foreground" onClick={() => void copy()}>复制地址</button>}
        {downloaded || cachedAfterFailure ? <><button className="text-xs text-muted-foreground hover:text-foreground" onClick={() => deferPocketUpdate(store)}>下次再说</button><button className="inline-flex items-center gap-1 rounded bg-primary px-2.5 py-1.5 text-xs font-medium text-primary-foreground" onClick={() => void installPocketUpdate(store)}><RotateCw className="size-3.5" />立即安装</button></> : downloading ? <button className="text-xs text-muted-foreground hover:text-foreground" onClick={() => hidePocketUpdateCard(store)}>后台下载</button> : <><button className="text-xs text-muted-foreground hover:text-foreground" onClick={() => deferPocketUpdate(store)}>稍后</button><button className="inline-flex items-center gap-1 rounded bg-primary px-2.5 py-1.5 text-xs font-medium text-primary-foreground" onClick={() => failed ? void retryPocketUpdate(store) : void downloadAvailablePocketUpdate(store)}><Download className="size-3.5" />{failed ? '重试' : '立即下载'}</button></>}
      </div>
    </aside>
  )
}
