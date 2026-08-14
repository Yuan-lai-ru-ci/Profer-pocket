/**
 * FilePathChip — 文件路径芯片（移动端瘦客户端简化版）
 *
 * 移动端无文件系统访问 / 预览面板 / 文件管理器 / Electron IPC，
 * 因此降级为纯展示型芯片：保留文件名 + 文件类型图标 + 路径 title 提示，
 * 去掉「点击打开预览」「右键在文件管理器显示」等桌面专属入口。
 *
 * 保留 isAbsoluteFilePath / isRelativeFilePath 两个纯函数导出（message.tsx 的
 * MarkdownInlineCode 依赖它们判断是否为可点击的文件路径 chip）。
 */

import * as React from 'react'
import { FileText } from 'lucide-react'
import { cn } from '@/lib/utils'

/** 图片扩展名 */
const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'])
/** 视频扩展名 */
const VIDEO_EXTS = new Set(['mp4', 'webm', 'mov'])
/** 代码/结构化文本扩展名 */
const CODE_EXTS = new Set([
  'md', 'markdown',
  'json', 'jsonc', 'json5',
  'xml', 'html', 'htm',
  'txt', 'log', 'csv',
  'yaml', 'yml', 'toml', 'ini', 'env', 'lock',
  'ts', 'tsx', 'js', 'jsx', 'mjs', 'cjs',
  'py', 'go', 'rs', 'java', 'kt', 'swift',
  'c', 'h', 'cpp', 'hpp', 'cs',
  'sh', 'bash', 'zsh', 'fish',
  'css', 'scss', 'less',
  'sql', 'rb', 'php',
  'diff', 'patch',
])
/** 文档扩展名 */
const DOC_EXTS = new Set(['pdf', 'docx'])

/** 所有可预览的扩展名集合（用于相对路径检测） */
const ALL_PREVIEWABLE_EXTS = new Set([...IMAGE_EXTS, ...VIDEO_EXTS, ...CODE_EXTS, ...DOC_EXTS])

/** 从路径提取文件名（兼容 Windows 反斜杠与 Unix 正斜杠） */
function getFileName(filePath: string): string {
  const parts = filePath.split(/[\\/]/)
  return parts[parts.length - 1] || filePath
}

/** 从文件名提取扩展名（小写，不含点） */
function getExtension(filename: string): string {
  const dot = filename.lastIndexOf('.')
  if (dot === -1) return ''
  return filename.slice(dot + 1).toLowerCase()
}

/** 从路径中剥离末尾的行号/列号后缀（如 :42 或 :42:15） */
function stripLineCol(filePath: string): { path: string; suffix: string } {
  const m = filePath.match(/^(.+?)(:\d+(?::\d+)?)$/)
  if (m && !m[1]!.endsWith(':')) {
    return { path: m[1]!, suffix: m[2]! }
  }
  return { path: filePath, suffix: '' }
}

interface FilePathChipProps {
  /** 文件路径（绝对或相对，可能带行号后缀） */
  filePath: string
  /** 基础目录路径（向后兼容，单值） */
  basePath?: string
  /** 多个候选基础目录 */
  basePaths?: string[]
  className?: string
}

/** 文件路径芯片 — 移动端纯展示（不可点击、无右键菜单） */
export function FilePathChip({ filePath, basePath, basePaths, className }: FilePathChipProps): React.ReactElement {
  const trimmedPath = filePath.trim()
  const { path: cleanPath, suffix: lineColSuffix } = stripLineCol(trimmedPath)

  const filename = getFileName(cleanPath)
  const isAbsolute = cleanPath.startsWith('/') || /^[A-Za-z]:[\\/]/.test(cleanPath)

  const candidateBases = React.useMemo<string[]>(() => {
    if (basePaths && basePaths.length > 0) return basePaths.filter(Boolean)
    if (basePath) return [basePath]
    return []
  }, [basePath, basePaths])

  const displayPath = React.useMemo(() => {
    if (isAbsolute) return trimmedPath
    if (candidateBases.length > 0) {
      const base = candidateBases[0]!
      return base.endsWith('/') ? `${base}${cleanPath}` : `${base}/${cleanPath}`
    }
    return trimmedPath
  }, [trimmedPath, cleanPath, isAbsolute, candidateBases])

  return (
    <span
      title={displayPath}
      className={cn(
        'inline-flex items-center gap-1 rounded bg-primary/10 px-1.5 py-[2px] text-[12px] font-medium leading-[1.6] text-primary',
        'align-baseline not-prose',
        className,
      )}
    >
      <FileText className="size-3 shrink-0" />
      <span className="max-w-[240px] truncate">{filename}{lineColSuffix}</span>
    </span>
  )
}

/**
 * 检测文本是否为绝对文件路径
 */
export function isAbsoluteFilePath(text: string): boolean {
  const trimmed = text.trim()
  if (trimmed.length < 2) return false

  const { path: clean } = stripLineCol(trimmed)

  if (clean.startsWith('/') && /^\/[^\n]+(?:\/[^\n]+)*$/.test(clean)) {
    if (clean.endsWith('/') && !clean.includes('.')) return false
    return true
  }

  if (/^[A-Za-z]:[\\/]/.test(clean)) return true

  return false
}

/**
 * 检测文本是否为相对文件路径（需要 basePath 才有意义）
 */
export function isRelativeFilePath(text: string): boolean {
  const trimmed = text.trim()
  if (trimmed.length < 3) return false

  const { path: clean } = stripLineCol(trimmed)

  const ext = getExtension(clean)
  if (!ext || !ALL_PREVIEWABLE_EXTS.has(ext)) return false

  if (!/^[\w./@-]+$/.test(clean)) return false

  if (clean.startsWith('.') && !clean.startsWith('./') && !clean.includes('/')) return false

  return true
}
