/**
 * 待发送附件（选择 → 校验 → 上传 → 就绪）。
 *
 * 抽出来的原因：这段是「异步上传 + 列表状态 + 发送前置条件」的组合，
 * 其中两个点被真实 bug 咬过，注释必须跟着代码走：
 * 1. 上限计数不能从 ref 里逐轮读 —— 同一次多选时 ref 还没更新，10 个上限会被整批绕过；
 * 2. 服务端遇到同名文件会加序号，就绪后必须记**服务端返回的文件名**，否则发送时解析不到。
 */
import * as React from 'react'
import type { WsClient } from '../src/renderer/pocket/ws-client'
import { base64FromDataUrl, validateAttachmentFile } from './attachments'

/**
 * 待发送附件。
 *
 * `filename` 用**服务端返回的名字**：同名文件会被加序号（a.png → a-1.png），
 * 拿本地原名回填会在发送时解析不到。失败项留在列表里而不是弹走 ——
 * 用户需要看到是哪个文件、为什么被拒。
 */
export interface StagedAttachment {
  key: string
  filename: string
  size: number
  status: 'uploading' | 'ready' | 'error'
  error?: string
}

export interface AttachmentsState {
  attachments: StagedAttachment[]
  fileInputRef: React.MutableRefObject<HTMLInputElement | null>
  /** 已就绪、可随消息发出的附件 */
  ready: StagedAttachment[]
  /** 还有文件在上传：此期间不许发送，否则会发出一条缺文件的消息 */
  uploading: boolean
  pickFiles: (files: FileList | null) => void
  removeAttachment: (key: string) => void
  clearAttachments: () => void
}

export function useAttachments(input: {
  clientRef: React.MutableRefObject<WsClient | null>
  sessionId: string
  connected: boolean
  onNotice: (message: string) => void
}): AttachmentsState {
  const { clientRef, sessionId, connected, onNotice } = input
  const [attachments, setAttachments] = React.useState<StagedAttachment[]>([])
  const fileInputRef = React.useRef<HTMLInputElement | null>(null)
  /** 上传回调里要读当前列表（闭包拿不到最新 state）。 */
  const attachmentsRef = React.useRef<StagedAttachment[]>([])
  attachmentsRef.current = attachments

  const pickFiles = React.useCallback((files: FileList | null): void => {
    if (!files || files.length === 0) return
    const client = clientRef.current
    if (!client || !connected) {
      onNotice('连接未就绪，暂时无法上传附件')
      return
    }
    // 计数在循环外累加：attachmentsRef 要等 React 提交后才更新，同一次多选 12 个文件时
    // 每轮都读到同一个旧值，10 个的上限会被整批绕过。
    let staged = attachmentsRef.current.filter((item) => item.status !== 'error').length
    for (const file of Array.from(files)) {
      const invalid = validateAttachmentFile(file, staged)
      const key = `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
      if (invalid) {
        // 放进列表而不是只弹一条 notice：用户要能看出是哪个文件被拒了
        setAttachments((previous) => [...previous, {
          key, filename: file.name || '未命名文件', size: file.size, status: 'error', error: invalid,
        }])
        continue
      }
      staged += 1
      setAttachments((previous) => [...previous, {
        key, filename: file.name, size: file.size, status: 'uploading',
      }])
      const reader = new FileReader()
      reader.onerror = () => {
        setAttachments((previous) => previous.map((item) => (item.key === key
          ? { ...item, status: 'error', error: '读取文件失败' }
          : item)))
      }
      reader.onload = () => {
        const base64 = base64FromDataUrl(String(reader.result ?? ''))
        if (!base64) {
          setAttachments((previous) => previous.map((item) => (item.key === key
            ? { ...item, status: 'error', error: '文件内容为空' }
            : item)))
          return
        }
        void client.saveAgentAttachment(sessionId, file.name, base64)
          .then((result) => {
            const saved = result as { filename?: unknown } | null
            const filename = typeof saved?.filename === 'string' && saved.filename ? saved.filename : file.name
            setAttachments((previous) => previous.map((item) => (item.key === key
              ? { ...item, status: 'ready', filename }
              : item)))
          })
          .catch((error: unknown) => {
            setAttachments((previous) => previous.map((item) => (item.key === key
              ? { ...item, status: 'error', error: error instanceof Error ? error.message : '上传失败' }
              : item)))
          })
      }
      reader.readAsDataURL(file)
    }
  }, [clientRef, sessionId, connected, onNotice])

  const removeAttachment = React.useCallback((key: string): void => {
    setAttachments((previous) => previous.filter((entry) => entry.key !== key))
  }, [])

  const clearAttachments = React.useCallback((): void => {
    setAttachments([])
  }, [])

  const ready = attachments.filter((item) => item.status === 'ready')
  const uploading = attachments.some((item) => item.status === 'uploading')

  return { attachments, fileInputRef, ready, uploading, pickFiles, removeAttachment, clearAttachments }
}
