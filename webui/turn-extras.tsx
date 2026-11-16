/**
 * 单会话页的展示层补充：上下文注入块剥离、图片收集、工具调用折叠展示。
 *
 * 口径对齐主仓 session-core 的 stripUserContextBlocks 与桌面端 image-attachment-marker，
 * 但完全自写、只依赖 React，不搬桌面组件。
 */
import * as React from 'react'
import type { MessageGroup } from '@profer/session-core'
import { isImageAttachment, parseAttachedFiles, type AttachedFileRef } from './attachments'
import { createLruCache } from './image-cache'

/**
 * 图片缓存条数上限。按「一张截图 1-3MB」估，40 条最坏也就一百多 MB；
 * 真机的长会话里能同时命中的通常只有最近十来张。
 */
const IMAGE_CACHE_LIMIT = 40

/** 主仓 group.ts 的 USER_CONTEXT_BLOCK_PATTERNS；pocket 的 toTranscript 少剥了后两个。 */
export function stripContextBlocks(text: string): string {
  const patterns = [
    /<attached_files>[\s\S]*?<\/attached_files>\n*/g,
    /<quoted_file[^>]*>[\s\S]*?<\/quoted_file>\n*/g,
    /<quoted_context[^>]*>[\s\S]*?<\/quoted_context>\n*/g,
    /<referenced_sessions>[\s\S]*?<\/referenced_sessions>\n*/g,
    /\[PROFER_IMAGE_ATTACHMENT:[^\]]*\]/g,
  ]
  return patterns.reduce((result, pattern) => result.replace(pattern, ''), text).trim()
}

export interface ToolCall {
  id: string
  name: string
  detail: string
  /** 工具结果带 is_error：WS 投影会保留这个标记，是移动端唯一能看到的成败信号。 */
  failed?: boolean
}

export interface TurnExtrasData {
  tools: ToolCall[]
  imagePaths: string[]
  /** 这条用户消息带的附件（从 `<attached_files>` 块解析，不是模型看到的路径清单） */
  attachedFiles: AttachedFileRef[]
}

/** 用户消息正文：content 可能是纯字符串，也可能是 [{type:'text',text}]。 */
function readUserText(message: unknown): string {
  const record = message as { message?: { content?: unknown } } | undefined
  const content = record?.message?.content
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .map((block) => isRecord(block) && typeof block.text === 'string' ? block.text : '')
    .join('\n')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

/** 工具入参只挑一行人类可读的关键信息，长命令在 UI 上用 ellipsis 收起，title 保留全文。 */
function describeToolInput(name: string, input: Record<string, unknown>): string {
  for (const key of ['command', 'path', 'file_path', 'filePath', 'pattern', 'url', 'query', 'description', 'notebook_path']) {
    const value = asString(input[key])
    if (value) return value.replace(/\s+/g, ' ').trim()
  }
  if (name === 'AskUserQuestion' && Array.isArray(input.questions)) {
    const questions = input.questions
      .map((question) => isRecord(question) ? asString(question.question) : undefined)
      .filter((value): value is string => Boolean(value))
    if (questions.length > 0) return questions.join(' / ')
  }
  // WS 出口的投影会清空大部分入参（只剩 file_path），空对象不该渲染成 "{}"。
  const keys = Object.keys(input)
  if (keys.length === 0) return ''
  const json = JSON.stringify(input)
  return json.length > 160 ? `${json.slice(0, 160)}…` : json
}

/** tool_result 的 content 可能是字符串，也可能是 [{type:'text',text}]。 */
function readToolResultText(block: Record<string, unknown>): string {
  const content = block.content
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((item) => isRecord(item) && typeof item.text === 'string' ? item.text : '')
      .join('\n')
  }
  return ''
}

/** 图片引用对象在各形态下的路径字段；relativePath 最安全（一定在当前会话授权根内）。 */
const IMAGE_REF_PATH_KEYS = ['relativePath', 'path', 'localPath', 'absolutePath']

function readImageRefPath(value: unknown): string | undefined {
  if (!isRecord(value)) return undefined
  return IMAGE_REF_PATH_KEYS
    .map((key) => asString(value[key]))
    .find((path): path is string => Boolean(path))
}

/**
 * 从 assistant-turn 的消息里收集工具调用与图片路径。
 *
 * 图片分两种形态，必须都覆盖：
 * 1. **WS 投影形态**（实际线上）：`projectSdkMessageForPocket` 把 tool_use 入参归一成
 *    `{ file_path }`，并把 tool_result 正文整段丢弃，只挂 `images: [{path, filename, mediaType}]`。
 *    因此只认 `input.path` 会永远取不到图 —— 这是「有工具调用却没有图片」的根因。
 * 2. **原始形态**（本地 JSONL / 未投影）：`input.path` 存在，消息顶层 `tool_use_result.image`
 *    带 `relativePath`，或 tool_result 正文回报「相对路径：…」。
 *
 * 优先级：tool_result 关联到的引用 > tool_use 入参。原始 input.path 可能是别的会话目录的
 * 绝对路径，服务端按当前会话授权根解析会拒绝。
 */
export function extractTurnExtras(group: MessageGroup): TurnExtrasData {
  if (group.type !== 'assistant-turn') {
    if (group.type !== 'user') return { tools: [], imagePaths: [], attachedFiles: [] }
    // 附件块留在消息正文里（各端 transcript 会剥掉它再显示）。在这里先解析成
    // 文件名 + 路径：气泡上挂 chip，图片附件顺便交给 TurnImages 出缩略图。
    const { files } = parseAttachedFiles(readUserText(group.message))
    return {
      tools: [],
      attachedFiles: files,
      imagePaths: files
        .filter((file) => file.path && isImageAttachment(file.filename))
        .map((file) => file.path!)
        .filter((path, index, list) => list.indexOf(path) === index),
    }
  }
  const tools: ToolCall[] = []
  const imageToolPaths = new Map<string, string>()
  const resolvedPaths = new Map<string, string>()
  // tool_result 不保证紧跟 tool_use（可能被拆到不同消息里），先收标记最后再回填。
  const failedIds = new Set<string>()

  for (const message of group.turnMessages) {
    const record = message as {
      message?: { content?: unknown }
      tool_use_result?: unknown
      toolUseResult?: unknown
    }
    // 原始形态：图片挂在消息顶层的 tool_use_result.image 上（投影后该字段会被丢弃）。
    const messageImagePath = readImageRefPath(
      (record.tool_use_result as { image?: unknown } | undefined)?.image
      ?? (record.toolUseResult as { image?: unknown } | undefined)?.image,
    )
    const content = record.message?.content
    if (!Array.isArray(content)) continue
    for (const block of content) {
      if (!isRecord(block)) continue
      if (block.type === 'tool_use' && typeof block.name === 'string') {
        const input = isRecord(block.input) ? block.input : {}
        const id = typeof block.id === 'string' ? block.id : `${block.name}-${tools.length}`
        tools.push({ id, name: block.name, detail: describeToolInput(block.name, input) })
        if (isImageTool(block.name)) {
          const path = IMAGE_PATH_KEYS
            .map((key) => asString(input[key]))
            .find((value): value is string => Boolean(value))
          if (path) imageToolPaths.set(id, path)
        }
        continue
      }
      if (block.type !== 'tool_result' || typeof block.tool_use_id !== 'string') continue
      if (block.is_error === true) failedIds.add(block.tool_use_id)
      // WS 投影形态：图片引用直接挂在 tool_result 块的 images 上。
      const images = Array.isArray(block.images) ? block.images : []
      let path = images.map(readImageRefPath).find((value): value is string => Boolean(value))
      // 原始形态：顶层 tool_use_result.image，或正文里的「相对路径：…」。
      path = path ?? messageImagePath
      if (!path) {
        const match = /相对路径[：:]\s*([^。\n]+)/.exec(readToolResultText(block))
        path = match?.[1]?.trim()
      }
      if (path) resolvedPaths.set(block.tool_use_id, path)
    }
  }

  const imagePaths: string[] = []
  for (const [id, original] of imageToolPaths) {
    const path = resolvedPaths.get(id) ?? original
    if (!imagePaths.includes(path)) imagePaths.push(path)
  }
  return {
    tools: failedIds.size === 0
      ? tools
      : tools.map((tool) => (failedIds.has(tool.id) ? { ...tool, failed: true } : tool)),
    imagePaths,
    attachedFiles: [],
  }
}

/** 用户气泡里的附件 chip：只显示文件名，不把绝对路径铺给用户看。 */
export function TurnAttachments({ files }: { files: AttachedFileRef[] }): React.ReactElement | null {
  if (files.length === 0) return null
  return (
    <div className="turn-attachments">
      {files.map((file, index) => (
        <span className="turn-attachment-chip" key={`${file.filename}-${index}`} title={file.path ?? file.filename}>
          <span className="turn-attachment-icon" aria-hidden="true" />
          <span className="turn-attachment-name">{file.filename}</span>
          {isImageAttachment(file.filename) && <span className="turn-attachment-kind">图片</span>}
        </span>
      ))}
    </div>
  )
}

/** 图片 data URL 缓存：同一张图在多次重渲染 / 重新加载历史时不重复走 WS。 */
const IMAGE_CACHE = createLruCache<string>(IMAGE_CACHE_LIMIT)

/**
 * 图片读取结果必须三分：ok / pending / error。
 *
 * 早期实现用 `string | null`，把「WS 还没 open」和「服务端拒绝」混成同一个 null，
 * 于是首屏早于连接就绪时会永久闩上「图片不可用」；而真正的服务端拒绝原因
 * （例如「不在当前会话授权范围内」）又被 catch 静默吞掉，只剩一个无法诊断的占位。
 */
export type ImageLoadResult =
  | { status: 'ok'; dataUrl: string }
  | { status: 'pending' }
  | { status: 'error'; message: string }

type ImageState = ImageLoadResult | { status: 'loading' }

/** 工具名可能是 MCP 前缀形式（mcp__profer__send_local_image），只比对末段。 */
const IMAGE_TOOL_NAMES = ['send_local_image', 'generate_image']

function isImageTool(name: string): boolean {
  const leaf = name.includes('__') ? name.slice(name.lastIndexOf('__') + 2) : name
  return IMAGE_TOOL_NAMES.includes(leaf)
}

/** 各版本的图片工具入参键名不统一，按可靠度依次尝试。 */
const IMAGE_PATH_KEYS = ['path', 'filePath', 'file_path', 'imagePath', 'image_path', 'localPath', 'source']

function TurnImage({ path, loadImage }: {
  path: string
  loadImage: (path: string) => Promise<ImageLoadResult>
}): React.ReactElement {
  const cached = IMAGE_CACHE.get(path)
  const [state, setState] = React.useState<ImageState>(
    cached ? { status: 'ok', dataUrl: cached } : { status: 'loading' },
  )
  const [attempt, setAttempt] = React.useState(0)
  const [expanded, setExpanded] = React.useState(false)
  // 成功即终点；error 不闩死，连接就绪（loadImage 身份变化）或手动重试都会再来一次。
  const settledRef = React.useRef(state.status === 'ok')
  const name = path.split(/[\\/]/).pop() || path

  React.useEffect(() => {
    if (settledRef.current) return
    let alive = true
    void loadImage(path).then((result) => {
      if (!alive) return
      if (result.status === 'ok') {
        settledRef.current = true
        IMAGE_CACHE.set(path, result.dataUrl)
        setState(result)
        return
      }
      // pending 不改状态：保持「正在加载」，等 connected 变化后由 effect 自动重试。
      if (result.status === 'error') setState(result)
    })
    return () => { alive = false }
  }, [path, loadImage, attempt])

  // 大图是全屏浮层，必须能用 Esc 退出，否则键盘用户会被关在里面。
  React.useEffect(() => {
    if (!expanded) return
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') setExpanded(false)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [expanded])

  if (state.status === 'ok') {
    return (
      <figure className="turn-image">
        <button className="turn-image-open" onClick={() => setExpanded(true)} title="点按查看大图">
          <img src={state.dataUrl} alt={name} />
        </button>
        <figcaption>{name}</figcaption>
        {expanded && (
          <div
            className="img-viewer"
            role="dialog"
            aria-label={`${name}，点按任意处或按 Esc 关闭`}
            onClick={() => setExpanded(false)}
          >
            <img src={state.dataUrl} alt={name} />
          </div>
        )}
      </figure>
    )
  }
  if (state.status === 'error') {
    return (
      <div className="turn-image placeholder failed">
        <div>图片不可用：{name}</div>
        <div className="turn-image-error">{state.message}</div>
        <button
          className="turn-image-retry"
          onClick={() => {
            setState({ status: 'loading' })
            setAttempt((previous) => previous + 1)
          }}
        >
          重试
        </button>
      </div>
    )
  }
  return <div className="turn-image placeholder">{`正在加载图片：${name}…`}</div>
}

/**
 * 回合里的图片：工具行已由 `TurnProcess` 的时间线接管，
 * 这里只负责把图片按路径拉成 data URL 并排开。
 */
export function TurnImages({ imagePaths, loadImage }: {
  imagePaths: string[]
  loadImage: (path: string) => Promise<ImageLoadResult>
}): React.ReactElement | null {
  if (imagePaths.length === 0) return null

  return (
    <div className="turn-images">
      {imagePaths.map((path, index) => (
        <TurnImage key={`${path}-${index}`} path={path} loadImage={loadImage} />
      ))}
    </div>
  )
}
