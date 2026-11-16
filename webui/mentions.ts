/**
 * 输入触发符解析：与桌面端 AgentView 保持同一语义 —— `/` 触发 Skill、`@` 引用文件。
 *
 * 两种 token 都**保留在消息正文里**，模型需要看到它们：
 * - `/skill:<slug>`：主进程同时用 mentionedSkills 做技能路由；
 * - `@file:<path>`：path 相对会话授权根（与桌面端一致）。
 */
export interface SkillOption {
  slug: string
  name: string
  description: string
}

/**
 * token 里的路径编码：空格/中文等转成 %XX，保证 `@file:(\S+)` 能完整匹配。
 * 桌面端 token 是原样路径，带空格时会被同款正则截断（既有行为）；
 * WebUI 在生成端编码，解析端 decode，避免「文件不存在」。
 */
export function encodeFileTokenPath(path: string): string {
  return encodeURIComponent(path)
}

/** 解码 token 路径；未编码的旧 token 原样返回 */
export function decodeFileTokenPath(encoded: string): string {
  try {
    return decodeURIComponent(encoded)
  } catch {
    return encoded
  }
}

export type FileSource = 'session' | 'workspace'

export interface FileOption {
  path: string
  name: string
  type: 'file' | 'dir'
  /** 会话文件 / 工作区文件（对齐桌面端 @ 候选的两组）。缺省按 session，兼容旧主进程扁平数据 */
  source?: FileSource
}

/** 一棵可展开的文件候选树（从后端扁平 path 列表构建，用于「@ 有目录显示」） */
export interface FileTreeNode {
  /** 建树/展开用的组内路径（工作区组已剥掉 ../workspace-files/ 前缀） */
  path: string
  /** 引用用的完整路径（@file:<refPath>，工作区文件带 ../workspace-files/） */
  refPath: string
  source: FileSource
  name: string
  type: 'file' | 'dir'
  depth: number
  children: FileTreeNode[]
}

/** 工作区文件建树时剥掉的相对前缀——引用时仍用完整 path */
function treePathOf(option: FileOption): string {
  if (option.source === 'workspace') {
    const marker = 'workspace-files/'
    const index = option.path.indexOf(marker)
    if (index >= 0) return option.path.slice(index + marker.length)
  }
  return option.path
}

/**
 * 把扁平的文件/目录候选构建成树。
 *
 * 后端给的是相对根的 POSIX 路径，目录与文件可能交错、也可能缺中间层
 * （扫描上限截断时），这里用「虚拟目录」补齐缺失的祖先，保证层级不断。
 * 排序：目录在前、文件在后，同名不区分大小写按名称排。
 */
export function buildFileTree(options: readonly FileOption[]): FileTreeNode[] {
  const roots: FileTreeNode[] = []
  const dirIndex = new Map<string, FileTreeNode>()

  // 同组内 refPath/source 一致（组是分开建的），取第一条非工作区项作代表
  const groupSource: FileSource = options[0]?.source ?? 'session'
  const refFor = (treePath: string): string => {
    if (groupSource !== 'workspace') return treePath
    const first = options.find((item) => treePathOf(item) === treePath)
    return first?.path ?? treePath
  }
  const ensureDir = (path: string, name: string, depth: number): FileTreeNode => {
    const existing = dirIndex.get(path)
    if (existing) return existing
    const node: FileTreeNode = { path, refPath: refFor(path), source: groupSource, name, type: 'dir', depth, children: [] }
    dirIndex.set(path, node)
    if (depth === 0) {
      roots.push(node)
    } else {
      const cut = path.lastIndexOf('/')
      const parentPath = cut >= 0 ? path.slice(0, cut) : ''
      // 父节点名字取父路径的最后一段（不是 cut 之后的这段——那是当前节点）
      const parentCut = parentPath.lastIndexOf('/')
      const parentName = parentCut >= 0 ? parentPath.slice(parentCut + 1) : parentPath
      ensureDir(parentPath, parentName || parentPath, depth - 1).children.push(node)
    }
    return node
  }

  for (const option of options) {
    const treePath = treePathOf(option)
    const parts = treePath.split('/').filter(Boolean)
    if (parts.length === 0) continue
    if (option.type === 'dir') {
      const node = ensureDir(treePath, option.name || parts[parts.length - 1]!, parts.length - 1)
      node.type = 'dir'
    } else {
      const fileName = option.name || parts[parts.length - 1]!
      const fileNode: FileTreeNode = {
        path: treePath,
        refPath: option.path,
        source: option.source ?? 'session',
        name: fileName,
        type: 'file',
        depth: parts.length - 1,
        children: [],
      }
      if (parts.length === 1) {
        roots.push(fileNode)
      } else {
        const parentPath = parts.slice(0, -1).join('/')
        ensureDir(parentPath, parts[parts.length - 2]!, parts.length - 2).children.push(fileNode)
      }
    }
  }

  const sort = (nodes: FileTreeNode[]): void => {
    nodes.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'dir' ? -1 : 1
      return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
    })
    for (const node of nodes) if (node.children.length > 0) sort(node.children)
  }
  sort(roots)
  return roots
}

/** 两组候选各自建树（会话文件 / 工作区文件，与桌面端两个分组对应） */
export function buildFileGroups(options: readonly FileOption[]): {
  session: FileTreeNode[]
  workspace: FileTreeNode[]
} {
  return {
    session: buildFileTree(options.filter((item) => item.source === 'session')),
    workspace: buildFileTree(options.filter((item) => item.source === 'workspace')),
  }
}

/** 把树按展开集合展平成可键盘导航的可见行（含目录行本身） */
export function flattenFileTree(roots: readonly FileTreeNode[], expanded: ReadonlySet<string>): FileTreeNode[] {
  const flat: FileTreeNode[] = []
  const visit = (nodes: readonly FileTreeNode[]): void => {
    for (const node of nodes) {
      flat.push(node)
      if (node.type === 'dir' && expanded.has(node.path)) visit(node.children)
    }
  }
  visit(roots)
  return flat
}

/** 正文里的 `/skill:<slug>` token 就是技能提及标记 */
export function extractMentionedSkills(text: string): string[] {
  const result: string[] = []
  for (const match of text.matchAll(/\/skill:(\S+)/g)) {
    const slug = match[1]
    if (slug && !result.includes(slug)) result.push(slug)
  }
  return result
}

export interface MentionQuery {
  /** 触发符之后、光标之前的查询串（可能为空） */
  query: string
  /** 触发符在正文中的下标：选中候选后从这里替换到光标 */
  start: number
}

/**
 * `/` 技能触发：必须在行首或空白之后，且 `/` 与光标之间不能有空白或另一个 `/`
 * （避免把 `https://`、文件路径误判成技能候选）。
 */
export function resolveSkillQuery(value: string, caret: number): MentionQuery | null {
  const before = value.slice(0, caret)
  const match = before.match(/(?:^|\s)\/([^\s/]*)$/)
  if (!match) return null
  const head = match[0]
  const start = before.length - head.length + head.indexOf('/')
  return { query: match[1] ?? '', start }
}

/**
 * `@` 文件触发：必须在行首或空白之后，且 `@` 与光标之间不能有空白
 * （邮箱、路径里的 @ 不触发）。
 */
export function resolveFileQuery(value: string, caret: number): MentionQuery | null {
  const before = value.slice(0, caret)
  const match = before.match(/(?:^|\s)@([^\s@]*)$/)
  if (!match) return null
  const head = match[0]
  const start = before.length - head.length + head.indexOf('@')
  return { query: match[1] ?? '', start }
}

/** 兼容旧名（早期 pocket 构建与回归测试仍按 `@` 语义调用） */
export const resolveMentionQuery = resolveFileQuery

/**
 * 查询串是否已经是完整 token 的内容（`file:…` / `skill:…`）。
 *
 * 这类上下文只可能来自已确认的 `@file:` / `/skill:` token；用户把光标点回 token
 * 内部是在改正文，不该把候选面板重新弹出来（尤其是点回输入框恢复焦点时）。
 */
export function isCommittedMentionQuery(query: string): boolean {
  return query.startsWith('file:') || query.startsWith('skill:')
}

/** 按查询过滤技能：slug 与名称都参与匹配。不截断数量 —— 后端有 20 个就该看到 20 个 */
export function filterSkills(skills: readonly SkillOption[], query: string): SkillOption[] {
  const q = query.trim().toLowerCase()
  return q
    ? skills.filter((skill) => skill.slug.toLowerCase().includes(q) || skill.name.toLowerCase().includes(q))
    : [...skills]
}

/** 按查询过滤文件：文件名与相对路径都参与匹配 */
export function filterFiles(files: readonly FileOption[], query: string): FileOption[] {
  const q = query.trim().toLowerCase()
  const matched = q
    ? files.filter((file) => file.name.toLowerCase().includes(q) || file.path.toLowerCase().includes(q))
    : [...files]
  // 候选面板要能滚动，这里给一个宽松上限避免一次渲染几千条
  return matched.slice(0, 80)
}
