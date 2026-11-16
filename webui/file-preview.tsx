/**
 * 文件预览（工具行路径 / 正文 @file: 引用点开）。
 *
 * 四类内容：图片内联、PDF 内联（浏览器自带查看器）、文本等宽正文、其它二进制给下载按钮。
 * 下载走浏览器 `<a download>` + data URL，交给系统应用打开（手机上最实用）。
 * 授权完全在主进程：这里只传路径，服务端按会话授权根解析，越权一律报错原文带回。
 */
import * as React from 'react'
import { isImageAttachment } from './attachments'
import { formatBytes } from './attachments'
import { useOverlay } from './overlay'

export type TextPreviewResult =
  | { status: 'ok'; text: string; truncated: boolean; size: number }
  | { status: 'binary'; size: number }
  | { status: 'error'; message: string }

export type BinaryLoadResult =
  | { status: 'ok'; dataUrl: string; size: number }
  | { status: 'error'; message: string }

export interface FilePreviewProps {
  filePath: string
  loadImage: (path: string) => Promise<{ status: 'ok'; dataUrl: string } | { status: 'pending' } | { status: 'error'; message: string }>
  loadText: (path: string) => Promise<TextPreviewResult>
  /** 读任意文件为 data URL（PDF 内联 / 二进制下载用） */
  loadBinary: (path: string) => Promise<BinaryLoadResult>
  onClose: () => void
}

type ViewState =
  | { kind: 'loading' }
  | { kind: 'image'; dataUrl: string }
  | { kind: 'pdf'; dataUrl: string; size: number }
  | { kind: 'text'; text: string; truncated: boolean; size: number }
  | { kind: 'binary'; size: number }
  | { kind: 'error'; message: string }

/** 触发浏览器下载 data URL；文件名由调用方给（服务端不暴露绝对路径） */
export function downloadDataUrl(dataUrl: string, name: string): void {
  const anchor = document.createElement('a')
  anchor.href = dataUrl
  anchor.download = name
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}

/** 从任意路径取展示名：Windows 与 POSIX 分隔符都认 */
export function fileNameOf(filePath: string): string {
  const parts = filePath.split(/[\\/]/)
  return parts[parts.length - 1] || filePath
}

export function FilePreview({ filePath, loadImage, loadText, loadBinary, onClose }: FilePreviewProps): React.ReactElement {
  const sheetRef = useOverlay(onClose)
  const [state, setState] = React.useState<ViewState>({ kind: 'loading' })
  const name = fileNameOf(filePath)
  const isPdf = /\.pdf$/i.test(name)

  React.useEffect(() => {
    let alive = true
    const run = async (): Promise<void> => {
      if (isImageAttachment(name)) {
        const result = await loadImage(filePath)
        if (!alive) return
        if (result.status === 'ok') { setState({ kind: 'image', dataUrl: result.dataUrl }); return }
        if (result.status === 'error') { setState({ kind: 'error', message: result.message }); return }
        // pending：WS 还没就绪，等一下再说 —— 这里不轮询，直接告诉用户重试
        setState({ kind: 'error', message: '连接尚未就绪，请稍后再打开' })
        return
      }
      if (isPdf) {
        const result = await loadBinary(filePath)
        if (!alive) return
        if (result.status === 'error') { setState({ kind: 'error', message: result.message }); return }
        setState({ kind: 'pdf', dataUrl: result.dataUrl, size: result.size })
        return
      }
      const result = await loadText(filePath)
      if (!alive) return
      if (result.status === 'ok') { setState({ kind: 'text', text: result.text, truncated: result.truncated, size: result.size }); return }
      if (result.status === 'binary') {
        // 二进制：懒加载 data URL，只在用户点「下载」时才走 WS（避免大文件白传）
        setState({ kind: 'binary', size: result.size })
        return
      }
      setState({ kind: 'error', message: result.message })
    }
    void run()
    return () => { alive = false }
  }, [filePath, name, loadImage, loadText, loadBinary, isPdf])

  const [downloading, setDownloading] = React.useState(false)
  const doDownload = async (): Promise<void> => {
    setDownloading(true)
    try {
      const result = await loadBinary(filePath)
      if (result.status === 'error') { setState({ kind: 'error', message: result.message }); return }
      downloadDataUrl(result.dataUrl, name)
    } finally {
      setDownloading(false)
    }
  }

  return (
    <>
      <div className="sheet-scrim" onClick={onClose} />
      <div className="sheet file-sheet" role="dialog" aria-modal="true" aria-label={`预览 ${name}`} ref={sheetRef} tabIndex={-1}>
        <div className="history-head">
          <strong className="file-sheet-name" title={filePath}>{name}</strong>
          <button className="history-close" onClick={onClose} aria-label="关闭预览">×</button>
        </div>
        {state.kind === 'loading' && <div className="history-empty">正在读取…</div>}
        {state.kind === 'error' && <div className="history-empty">打不开：{state.message}</div>}
        {state.kind === 'binary' && (
          <div className="history-empty">
            <div>这是一个二进制文件（{formatBytes(state.size)}），网页里无法预览。</div>
            <button
              type="button"
              className="file-download-btn"
              disabled={downloading}
              onClick={() => { void doDownload() }}
            >
              {downloading ? '准备中…' : '下载并用其他应用打开'}
            </button>
          </div>
        )}
        {state.kind === 'image' && (
          <div className="file-image"><img src={state.dataUrl} alt={name} /></div>
        )}
        {state.kind === 'pdf' && (
          <>
            <object className="file-pdf" data={state.dataUrl} type="application/pdf" aria-label={name}>
              <div className="history-empty">
                浏览器无法内嵌显示这个 PDF（{formatBytes(state.size)}）。
                <button type="button" className="file-download-btn" onClick={() => { void doDownload() }}>
                  下载并用其他应用打开
                </button>
              </div>
            </object>
            <div className="file-sheet-foot">
              {formatBytes(state.size)}
              <button type="button" className="file-download-link" onClick={() => { void doDownload() }}>下载</button>
            </div>
          </>
        )}
        {state.kind === 'text' && (
          <>
            <pre className="file-text">{state.text}</pre>
            <div className="file-sheet-foot">
              {formatBytes(state.size)}
              {state.truncated ? ' · 仅显示前 256 KB' : ''}
            </div>
          </>
        )}
      </div>
    </>
  )
}
