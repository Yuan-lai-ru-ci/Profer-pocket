/**
 * 把消息正文里的 `@file:<path>` 变成可点的预览链接。
 *
 * 桌面端用 remarkMentions 把四种 token 转成 mention:// 链接；WebUI 只需要文件这一种，
 * 且点击后用 window.open 开一个一次性预览标签页（可多个并存、来回切换）。
 *
 * token 里的路径经 encodeURIComponent 编码（空格→%20），解析时 decode，
 * 避免带空格的文件名被 `\S+` 截断成「文件不存在」。
 */
import { decodeFileTokenPath } from './mentions'
import { fileBaseName } from './file-reference'
export const FILE_PREVIEW_SCHEME = 'profer-file-preview:'

/**
 * chip 文案：与桌面端 MentionChip 一致，只显示文件名（完整路径放进 title）。
 * token 里的百分号编码在这里已经还原，中文文件名不会再以 %E5… 的形式出现。
 */
export function fileMentionLabel(path: string): string {
  // 目录引用可能带尾部分隔符，先剥掉再取末段
  const trimmed = path.replace(/[\\/]+$/, '')
  return fileBaseName(trimmed) || path
}

/** 构造内部链接 URL（只在本页组件里解释，不当作外链打开） */
export function filePreviewUrl(path: string): string {
  return `${FILE_PREVIEW_SCHEME}${encodeURIComponent(path)}`
}

/** 从内部链接 URL 还原路径；不是本方案的 URL 返回 null */
export function pathFromFilePreviewUrl(url: string): string | null {
  if (!url.startsWith(FILE_PREVIEW_SCHEME)) return null
  try {
    return decodeURIComponent(url.slice(FILE_PREVIEW_SCHEME.length))
  } catch {
    return null
  }
}

export interface TextNode {
  type: 'text'
  value: string
}
export interface LinkNode {
  type: 'link'
  url: string
  children: TextNode[]
}
export type MentionNode = TextNode | LinkNode | Record<string, unknown>

/** 纯函数：把一段文本切成 文本 / 文件链接 片段；没有 token 返回 null */
export function splitFileMentions(value: string): MentionNode[] | null {
  const pattern = /(^|\s)@file:(\S+)/g
  const parts: MentionNode[] = []
  let last = 0
  let match: RegExpExecArray | null
  let found = false
  while ((match = pattern.exec(value)) !== null) {
    found = true
    const tokenStart = match.index + match[1]!.length
    if (tokenStart > last) parts.push({ type: 'text', value: value.slice(last, tokenStart) })
    const decodedPath = decodeFileTokenPath(match[2]!)
    parts.push({
      type: 'link',
      url: filePreviewUrl(decodedPath),
      // 链接文本只放展示名：静态预览没有 chip 组件，也不能露出编码后的 token
      children: [{ type: 'text', value: fileMentionLabel(decodedPath) }],
    })
    last = tokenStart + (match[0].length - match[1]!.length)
  }
  if (!found) return null
  if (last < value.length) parts.push({ type: 'text', value: value.slice(last) })
  return parts
}

/**
 * remark 插件：遍历所有节点的 children，把包含 `@file:` 的 text 子节点切成 link。
 * 出错时保持原样（提及是增强能力，不能让正文渲染失败）。
 */
export function remarkFileMentions(): (tree: unknown) => void {
  return (tree: unknown): void => {
    const visit = (node: unknown): void => {
      if (!node || typeof node !== 'object') return
      const children = (node as { children?: unknown }).children
      if (!Array.isArray(children)) return
      for (let index = 0; index < children.length; index += 1) {
        const child = children[index]
        if (!child || typeof child !== 'object') continue
        const record = child as { type?: unknown; value?: unknown }
        if (record.type === 'text' && typeof record.value === 'string') {
          try {
            const replacement = splitFileMentions(record.value)
            if (replacement) children.splice(index, 1, ...replacement)
          } catch {
            // 单个文本节点解析失败不能影响整条消息
          }
        } else {
          visit(child)
        }
      }
    }
    visit(tree)
  }
}
