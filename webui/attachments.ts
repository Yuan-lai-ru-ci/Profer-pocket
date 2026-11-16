/**
 * 附件的客户端判定（纯函数，便于测试）。
 *
 * 服务端 `save_agent_attachment` 有两道硬限制：单个 10 MB、文件名会被 basename 化。
 * 这里提前挡一遍只是为了让用户**立刻**看到原因，而不是等一轮 WS 往返后收到一句
 * 「附件不能超过 10 MB」却不知道是哪个文件。
 */

export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
export const MAX_ATTACHMENT_COUNT = 10

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * 校验一个待上传文件。返回 null 表示可以上传，否则返回给用户看的原因。
 *
 * @param staged 当前已就绪 + 上传中的数量，用于卡总数上限
 */
export function validateAttachmentFile(
  file: { name: string; size: number },
  staged: number,
): string | null {
  const name = file.name.trim()
  if (!name) return '文件名为空'
  if (file.size === 0) return '空文件'
  if (file.size > MAX_ATTACHMENT_BYTES) return `超过 ${formatBytes(MAX_ATTACHMENT_BYTES)}（${formatBytes(file.size)}）`
  if (staged >= MAX_ATTACHMENT_COUNT) return `最多 ${MAX_ATTACHMENT_COUNT} 个附件`
  return null
}

/** FileReader 给的是 data URL，服务端要裸 base64。 */
export function base64FromDataUrl(dataUrl: string): string {
  const index = dataUrl.indexOf(',')
  if (index < 0) return ''
  const head = dataUrl.slice(0, index)
  if (!head.includes(';base64')) return ''
  return dataUrl.slice(index + 1)
}

/**
 * 附件-only 发送时的兜底文案。
 *
 * 不能把 `<attached_files>` 块当正文发出去：那块是给模型看的路径清单，
 * 用户气泡里出现一堆绝对路径既难看也没意义。
 */
export function resolveSendText(text: string, attachmentCount: number): string {
  const trimmed = text.trim()
  if (trimmed) return trimmed
  if (attachmentCount > 0) return '请查看我上传的附件。'
  return ''
}

export interface AttachedFileRef {
  /** 展示用的名字（服务端写的是 label，通常就是文件名） */
  filename: string
  /** 会话目录内的绝对路径；可能缺失（旧数据只有文件名） */
  path?: string
}

/**
 * 解析用户消息里的 `<attached_files>` 块。
 *
 * 桌面端 `parseAttachedFiles` 的同一格式：`- 名称: 绝对路径`；此外兼容
 * `<file path="..." />` 那种引用写法。返回剥掉块之后的正文，气泡里不会露出路径清单。
 *
 * 注意 Windows 路径带冒号（`C:\...`），所以按**第一个** `: ` 切，不能用 split(':')。
 */
export function parseAttachedFiles(text: string): { files: AttachedFileRef[]; text: string } {
  const regex = /<attached_files>\s*([\s\S]*?)\s*<\/attached_files>\s*/
  const match = text.match(regex)
  if (!match) return { files: [], text: text.trim() }
  const files: AttachedFileRef[] = []
  for (const line of match[1]!.split('\n')) {
    const trimmed = line.trim()
    // `- label: path` —— 用 lastIndexOf 也不行（路径里可能还有冒号），取第一个 ': '
    const dash = trimmed.match(/^-\s+(.+?):\s+(.+)$/)
    if (dash) {
      files.push({ filename: dash[1]!.trim(), path: dash[2]!.trim() })
      continue
    }
    const fileTag = trimmed.match(/^<file\s+path=["'](.+?)["']\s*\/?>$/)
    if (fileTag) {
      const path = fileTag[1]!.trim()
      files.push({ filename: path.split(/[\\/]/).pop() ?? path, path })
    }
  }
  return { files, text: text.replace(regex, '').trim() }
}

/** 附件里能当图片预览的类型（与桌面 isImageFile 同口径）。 */
export function isImageAttachment(filename: string): boolean {
  return /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i.test(filename)
}
