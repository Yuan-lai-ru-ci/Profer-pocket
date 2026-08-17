/**
 * FilePreviewDialog — 文件内嵌预览弹窗
 *
 * 根据文件扩展名分派预览方式：
 * - 图片: base64 → ImageLightbox
 * - PDF: prepare-pdf-preview → HTML iframe
 * - Office: docx-to-html / office-to-html → HTML iframe
 * - 代码/文本: resolve-and-read → 代码查看器
 * - 其他: 文件信息 + 下载提示
 */

import * as React from 'react'
import { X, Loader2 } from 'lucide-react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { getPreviewCache, setPreviewCache } from '@/lib/preview-cache'

interface FilePreviewDialogProps {
  open: boolean
  filePath: string
  fileName: string
  onClose: () => void
  /** 团队模式：预览前先下载到本地 */
  teamDownload?: () => Promise<string | null>
}

type PreviewState =
  | { status: 'loading' }
  | { status: 'image'; src: string }
  | { status: 'html'; html: string }
  | { status: 'iframe'; src: string }
  | { status: 'text'; content: string; language: string }
  | { status: 'unsupported' }
  | { status: 'error'; message: string }

const TEXT_EXTS = new Set([
  'txt', 'md', 'json', 'csv', 'xml', 'html', 'htm', 'css', 'scss', 'less',
  'js', 'jsx', 'ts', 'tsx', 'py', 'rb', 'go', 'rs', 'java', 'c', 'cpp', 'h',
  'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf', 'sh', 'bat', 'sql', 'graphql',
  'env', 'gitignore', 'dockerfile', 'log',
])

const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico'])

function ext(name: string): string {
  return name.split('.').pop()?.toLowerCase() ?? ''
}

function langFromExt(e: string): string {
  const map: Record<string, string> = {
    js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
    py: 'python', rb: 'ruby', rs: 'rust', go: 'go', java: 'java',
    c: 'c', cpp: 'cpp', h: 'c', sh: 'bash', bat: 'batch',
    sql: 'sql', yaml: 'yaml', yml: 'yaml', toml: 'toml',
    json: 'json', xml: 'xml', html: 'html', css: 'css',
    md: 'markdown', graphql: 'graphql',
  }
  return map[e] ?? e
}

export function FilePreviewDialog({ open, filePath, fileName, onClose, teamDownload }: FilePreviewDialogProps): React.ReactElement {
  const [state, setState] = React.useState<PreviewState>({ status: 'loading' })

  React.useEffect(() => {
    if (!open || !filePath) return
    setState({ status: 'loading' })
    loadPreview()
  }, [open, filePath]) // eslint-disable-line

  const loadPreview = async (): Promise<void> => {
    const e = ext(fileName)
    try {
      let localPath = filePath
      // 团队模式：先下载到本地
      if (teamDownload) {
        const downloaded = await teamDownload()
        if (!downloaded) { setState({ status: 'error', message: '文件下载失败，请重试' }); return }
        localPath = downloaded
      }
      // 团队下载后拿到的是临时目录绝对路径，IPC handler 需要授权上下文
      const parentDir = localPath.replace(/[/\\][^/\\]*$/, '') || '/'
      const access = teamDownload ? { candidateBasePaths: [parentDir] } : undefined

      if (IMAGE_EXTS.has(e)) {
        // 图片预览：桌面走 registerPreviewPath（profer-file:// 自定义协议），
        // Pocket 端无法加载该协议，改为 readFileAsDataUrl（WS 返回 base64 data URL）。
        // 命中内存缓存时直接渲染，不重复请求电脑端。
        const cacheKey = `img:${localPath}`
        const cached = getPreviewCache<{ resolvedPath: string; dataUrl: string }>(cacheKey)
        if (cached?.dataUrl) {
          setState({ status: 'image', src: cached.dataUrl })
        } else {
          const result = await window.electronAPI.readFileAsDataUrl(localPath, access)
          if (result?.dataUrl) {
            setPreviewCache(cacheKey, result)
            setState({ status: 'image', src: result.dataUrl })
          } else {
            setState({ status: 'error', message: '无法读取图片' })
          }
        }
      } else if (e === 'pdf') {
        const result = await window.electronAPI.preparePdfPreview(localPath, access)
        if (result?.tmpHtmlUrl) setState({ status: 'iframe', src: result.tmpHtmlUrl })
        else setState({ status: 'error', message: '无法预览 PDF' })
      } else if (e === 'docx') {
        const result = await window.electronAPI.docxToHtml(localPath, access)
        if (result?.html) setState({ status: 'html', html: result.html })
        else setState({ status: 'error', message: '无法预览文档' })
      } else if (['xlsx', 'pptx', 'odt', 'ods', 'odp'].includes(e)) {
        const result = await window.electronAPI.officeToHtml(localPath, access)
        if (result?.html) setState({ status: 'html', html: result.html })
        else setState({ status: 'error', message: '无法预览文档' })
      } else if (TEXT_EXTS.has(e) || !e) {
        // 文本/代码预览：内存缓存命中直接渲染（MVP：反复查看同一文件秒开）
        const cacheKey = `text:${localPath}`
        const cached = getPreviewCache<{ resolvedPath: string; content: string }>(cacheKey)
        if (cached?.content !== undefined) {
          setState({ status: 'text', content: cached.content, language: langFromExt(e) })
        } else {
          const result = await window.electronAPI.resolveAndReadFile(localPath, access)
          if (result?.content !== undefined && result.content !== null) {
            setPreviewCache(cacheKey, result)
            setState({ status: 'text', content: result.content, language: langFromExt(e) })
          } else {
            setState({ status: 'error', message: '无法读取文件' })
          }
        }
      } else {
        setState({ status: 'unsupported' })
      }
    } catch (err) {
      setState({ status: 'error', message: err instanceof Error ? err.message : '加载失败' })
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      {/* 标题栏右侧已自带关闭按钮（见 DialogHeader），关闭 DialogContent 默认注入的右上角 X，避免两个关闭按钮 */}
      <DialogContent hideClose className={cn(
        'max-w-[calc(56rem-18px)] h-[calc(80vh-24px)] flex flex-col p-0 gap-0',
        state.status === 'image' && 'max-w-[calc(64rem-18px)] h-[calc(90vh-24px)]',
      )}>
        <DialogHeader className="flex flex-row items-center justify-between px-4 py-2 border-b flex-shrink-0">
          <DialogTitle className="text-sm font-medium truncate flex-1 mr-2">{fileName}</DialogTitle>
          <DialogDescription className="sr-only">
            预览文件 {fileName}
          </DialogDescription>
          <div className="flex items-center gap-1">
            <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onClose}>
              <X className="size-3.5" />
            </Button>
          </div>
        </DialogHeader>

        <div className="flex-1 min-h-0 overflow-auto">
          {state.status === 'loading' && (
            <div className="flex items-center justify-center h-full">
              <Loader2 className="size-6 animate-spin text-muted-foreground" />
            </div>
          )}
          {state.status === 'image' && (
            <div className="flex items-center justify-center h-full bg-black/5">
              <img src={state.src} alt={fileName} className="max-w-full max-h-full object-contain" />
            </div>
          )}
          {state.status === 'html' && (
            <iframe srcDoc={state.html} className="w-full h-full border-0" sandbox="allow-scripts" />
          )}
          {state.status === 'iframe' && (
            <iframe src={state.src} className="w-full h-full border-0" />
          )}
          {state.status === 'text' && (
            <pre className="p-4 text-xs font-mono whitespace-pre-wrap overflow-auto h-full">
              <code>{state.content}</code>
            </pre>
          )}
          {state.status === 'unsupported' && (
            <div className="flex flex-col items-center justify-center h-full gap-3 text-muted-foreground">
              <p className="text-sm">暂不支持预览此文件类型（.{ext(fileName)}）</p>
            </div>
          )}
          {state.status === 'error' && (
            <div className="flex items-center justify-center h-full text-sm text-destructive">
              {state.message}
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
