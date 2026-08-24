import * as React from 'react'
import { useAtomValue, useStore } from 'jotai'
import { Copy, Download, Loader2, RefreshCw, RotateCw } from 'lucide-react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { toast } from 'sonner'
import { pocketNativeVersionAtom, pocketUpdateStatusAtom } from '@/atoms/pocket-updater'
import { checkPocketUpdate, downloadAvailablePocketUpdate, installPocketUpdate, retryPocketUpdate } from '@/atoms/pocket-updater'
import { writeClipboardText } from '@/lib/clipboard'
import { Button } from '@/components/ui/button'
import { SettingsCard, SettingsRow, SettingsSection } from './primitives'

export function PocketUpdateSettings(): React.ReactElement {
  const store = useStore()
  const native = useAtomValue(pocketNativeVersionAtom)
  const status = useAtomValue(pocketUpdateStatusAtom)
  const update = status.status === 'available' || status.status === 'downloading' || status.status === 'downloaded' || status.status === 'error' ? status.update : undefined
  const checking = status.status === 'checking'
  const copy = async (): Promise<void> => {
    if (!update) return
    const ok = await writeClipboardText(update.apkDownloadUrl)
    if (ok) toast.success('APK 下载地址已复制')
    else toast.error('复制失败，请手动复制下载地址')
  }

  return <div className="space-y-6">
    <SettingsSection title="软件更新" description="仅从 Profer-pocket 的 GitHub Releases 获取经校验的 APK。">
      <SettingsCard>
        <SettingsRow label="当前版本"><span className="font-mono text-sm text-muted-foreground">{native ? `${native.versionName} (${native.versionCode})` : '正在读取 Android 版本…'}</span></SettingsRow>
        <SettingsRow label="更新状态"><span className="text-sm text-muted-foreground">{checking ? '正在检查…' : status.status === 'not-available' ? '已是最新版本' : status.status === 'available' ? `v${update?.versionName} 可下载` : status.status === 'downloading' ? `正在下载 v${update?.versionName}` : status.status === 'downloaded' ? `v${update?.versionName} 已验证` : status.status === 'error' ? status.message : '尚未检查'}</span></SettingsRow>
        {status.status === 'downloading' && <div className="px-4 pb-4"><div className="h-2 overflow-hidden rounded bg-muted"><div className="h-full bg-primary transition-all" style={{ width: `${Math.max(0, Math.min(100, status.progress.percent))}%` }} /></div><p className="mt-1 text-xs text-muted-foreground">{status.progress.total > 0 ? `${Math.round(status.progress.percent)}%` : '正在下载…'}</p></div>}
        <div className="flex flex-wrap gap-2 px-4 pb-4"><Button size="sm" variant="outline" disabled={checking || status.status === 'downloading'} onClick={() => void checkPocketUpdate(store)}>{checking ? <Loader2 className="mr-1.5 size-3.5 animate-spin" /> : <RefreshCw className="mr-1.5 size-3.5" />}检查更新</Button>{status.status === 'available' || (status.status === 'error' && status.phase !== 'install' && !status.apkPath) ? <Button size="sm" onClick={() => status.status === 'error' ? void retryPocketUpdate(store) : void downloadAvailablePocketUpdate(store)}><Download className="mr-1.5 size-3.5" />{status.status === 'error' ? '重试' : '下载更新'}</Button> : null}{status.status === 'downloaded' || (status.status === 'error' && Boolean(status.apkPath)) ? <><Button size="sm" onClick={() => void installPocketUpdate(store)}><RotateCw className="mr-1.5 size-3.5" />{status.status === 'error' && status.phase === 'install' ? '重试安装' : '立即安装'}</Button>{status.status === 'error' && status.phase !== 'install' ? <Button size="sm" variant="outline" onClick={() => void retryPocketUpdate(store)}>重试下载</Button> : null}</> : null}{update ? <Button size="sm" variant="ghost" onClick={() => void copy()}><Copy className="mr-1.5 size-3.5" />复制下载地址</Button> : null}</div>
      </SettingsCard>
      {update && <SettingsCard><div className="border-b px-4 py-3 text-sm font-medium">v{update.versionName} 更新日志</div><div className="prose max-w-none p-4 text-xs leading-6 dark:prose-invert prose-p:my-1.5 prose-li:my-0.5">{update.releaseNotes ? <Markdown remarkPlugins={[remarkGfm]}>{update.releaseNotes}</Markdown> : <p>此版本未提供更新日志。</p>}</div></SettingsCard>}
    </SettingsSection>
  </div>
}
