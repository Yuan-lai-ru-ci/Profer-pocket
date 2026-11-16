/**
 * 正文里的本地文件引用识别。
 *
 * 桌面端由 `MarkdownInlineCode` + `TurnFileMapContext` 完成：绝对路径直接出 chip，
 * 裸文件名用「本轮工具实际触及的文件名 → 绝对路径」补全，同名冲突则放弃。WebUI
 * 拿不到 renderer 的会话上下文，但消息投影保留了每个工具调用的 `file_path`，因此
 * 同一套判据可以在这里独立成立：
 *
 * - 绝对路径（Unix / 盘符 / UNC）直接可预览；
 * - 带目录的相对路径原样交给服务端，由授权根解析（`resolveAuthorizedRemoteFilePath`）；
 * - 裸文件名只在本轮文件索引里唯一命中时才出 chip，避免误开同名文件；
 * - 行号后缀（`foo.ts:12`）只用于显示，传给预览加载器前剥掉。
 *
 * 扩展名白名单与桌面端 `file-path-chip.tsx` 对齐，并且刻意比「任意行内代码」更保守：
 * 命令、函数名、`npm run build` 这类内容不能变成可点的文件 chip。
 */

/** 与桌面端 FilePathChip 的可预览扩展名保持同一份口径。 */
const PREVIEWABLE_EXTENSIONS = new Set([
  'md', 'markdown', 'json', 'jsonc', 'json5', 'xml', 'html', 'htm', 'txt', 'log', 'csv',
  'yaml', 'yml', 'toml', 'ini', 'env', 'lock', 'ts', 'tsx', 'js', 'jsx', 'mjs', 'cjs',
  'py', 'go', 'rs', 'java', 'kt', 'swift', 'c', 'h', 'cpp', 'hpp', 'cs', 'sh', 'bash',
  'zsh', 'fish', 'css', 'scss', 'less', 'sql', 'rb', 'php', 'diff', 'patch',
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'mp4', 'webm', 'mov', 'pdf', 'docx',
])

export interface FileReference {
  /** 传给预览加载器的路径（绝对路径或可被服务端授权根解析的相对路径） */
  path: string
  /** chip 文案：只显示文件名 + 行号，长路径不占满手机宽度 */
  label: string
  /** 悬停提示：完整引用（含行号） */
  title: string
}

export interface StrippedLineCol {
  path: string
  /** `:12` / `:12:3`；没有则为空串 */
  suffix: string
}

/** 行号后缀只影响展示：预览页展示整份文件，不能把 `:12` 当成路径的一部分。 */
export function stripLineCol(value: string): StrippedLineCol {
  const match = value.match(/^(.+?)(:\d+(?::\d+)?)$/)
  // 两处保护：`foo:` 形态（`C:`）不拆；裸盘符 + 数字（`C:12`）也不拆
  if (match && !match[1]!.endsWith(':') && !/^[A-Za-z]$/.test(match[1]!)) {
    return { path: match[1]!, suffix: match[2]! }
  }
  return { path: value, suffix: '' }
}

export function fileBaseName(filePath: string): string {
  const parts = filePath.split(/[\\/]/)
  return parts[parts.length - 1] || filePath
}

function extensionOf(filePath: string): string {
  const name = fileBaseName(filePath)
  const dot = name.lastIndexOf('.')
  return dot <= 0 ? '' : name.slice(dot + 1).toLowerCase()
}

export function hasPreviewableExtension(filePath: string): boolean {
  return PREVIEWABLE_EXTENSIONS.has(extensionOf(filePath))
}

/**
 * 绝对路径判定：只认远程服务端能定位的形态。
 *
 * `~/...` 在桌面端由主进程展开为 home；WebUI 的 `resolveAuthorizedRemoteFilePath`
 * 不展开 `~`，放行只会得到一次「不在授权范围」的失败预览，所以刻意不认。
 */
export function isPreviewableAbsolutePath(filePath: string): boolean {
  if (!filePath || filePath.length < 3 || filePath.endsWith('/') || filePath.endsWith('\\')) return false
  if (/[\n\r?#]/.test(filePath)) return false
  const absolute = filePath.startsWith('/')
    || filePath.startsWith('\\\\')
    || /^[A-Za-z]:[\\/]/.test(filePath)
  if (!absolute) return false
  // `//cdn.example.com/a.ts` 是协议相对 URL，不是本地文件
  if (filePath.startsWith('//')) return false
  return hasPreviewableExtension(filePath)
}

/**
 * 相对路径判定。
 *
 * 不允许空白：桌面端为了带空格的路径放开了空格，代价是 `` `git diff HEAD~1 -- a.ts` ``
 * 这类命令行也会被当成文件；WebUI 面向手机（误触成本更高），只接受确切像路径的内容。
 */
export function isPreviewableRelativePath(filePath: string): boolean {
  if (!filePath || filePath.length < 3 || /[\s?#]/.test(filePath)) return false
  // 绝对路径的扩展名不在白名单时（`/repo/archive.zip`、协议相对 URL）不能再被当成相对路径
  if (filePath.startsWith('/') || filePath.startsWith('\\\\') || /^[A-Za-z]:[\\/]/.test(filePath)) return false
  if (!hasPreviewableExtension(filePath)) return false
  if (!/^[\w./@\\\p{L}\p{M}-]+$/u.test(filePath)) return false
  if (filePath.endsWith('/') || filePath.endsWith('\\')) return false
  // 隐藏文件名（`.eslintrc.json`）不像正文里的路径引用；`./x` 或带目录时才放行
  if (filePath.startsWith('.') && !filePath.startsWith('./') && !filePath.startsWith('.\\')
    && !filePath.includes('/') && !filePath.includes('\\')) return false
  return true
}

function isAbsolutePath(filePath: string): boolean {
  return filePath.startsWith('/') || filePath.startsWith('\\\\') || /^[A-Za-z]:[\\/]/.test(filePath)
}

/**
 * 本轮「文件名 → 路径」索引。
 *
 * 来源是工具调用的 `file_path`（Read/Edit/Write 等）与改动清单。绝对路径优先：
 * 改动清单里的 `src/a.ts` 与工具入参 `/repo/src/a.ts` 通常指同一个文件，直接选
 * 绝对路径；同名但不同绝对路径则视为冲突并剔除，宁可不给 chip 也不误开。
 */
export function buildTurnFileIndex(paths: readonly string[]): Map<string, string> {
  const absoluteByBase = new Map<string, string>()
  const relativeByBase = new Map<string, string>()
  const conflicted = new Set<string>()

  for (const raw of paths) {
    const trimmed = raw.trim()
    if (!trimmed) continue
    const { path } = stripLineCol(trimmed)
    if (!isPreviewableAbsolutePath(path) && !isPreviewableRelativePath(path)) continue
    const base = fileBaseName(path)
    if (!base) continue
    const target = isAbsolutePath(path) ? absoluteByBase : relativeByBase
    if (conflicted.has(base)) continue
    const existing = target.get(base)
    if (existing && existing !== path) {
      target.delete(base)
      conflicted.add(base)
      continue
    }
    target.set(base, path)
  }

  const index = new Map(relativeByBase)
  for (const [base, path] of absoluteByBase) index.set(base, path)
  return index
}

/**
 * 行内代码 → 文件引用；不像文件就返回 null，保持普通代码样式。
 *
 * `index` 缺省（例如用户消息）时仍识别绝对路径与带目录的相对路径。
 */
export function resolveFileReference(
  value: string,
  index?: ReadonlyMap<string, string>,
): FileReference | null {
  const text = value.trim()
  if (!text || /[\n\r]/.test(text)) return null
  const { path, suffix } = stripLineCol(text)
  const label = `${fileBaseName(path)}${suffix}`
  const title = text

  if (isPreviewableAbsolutePath(path)) return { path, label, title }
  if (!isPreviewableRelativePath(path)) return null

  const hasSeparator = /[\\/]/.test(path)
  if (!hasSeparator) {
    const indexed = index?.get(path)
    // 裸文件名离开本轮索引就无法可靠定位，宁可保留普通代码
    return indexed ? { path: indexed, label, title } : null
  }

  const indexed = index?.get(fileBaseName(path))
  if (indexed) {
    const normalizedIndexed = indexed.replace(/\\/g, '/')
    const normalizedPath = path.replace(/\\/g, '/').replace(/^\.\//, '')
    // 只有索引路径确实以该相对路径结尾时才采用，避免同名不同目录误配
    if (normalizedIndexed.endsWith(`/${normalizedPath}`)) return { path: indexed, label, title }
  }
  return { path, label, title }
}
