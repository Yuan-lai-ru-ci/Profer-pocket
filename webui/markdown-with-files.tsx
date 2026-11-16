/**
 * 正文与执行过程叙述共用的 Markdown 渲染器。
 *
 * 两类别名都会落成同一种文件 chip：
 * - `@file:xxx` 引用由 remark 插件转成内部链接（`file-mention-link.ts`）；
 * - 行内代码里的本地文件引用由 `file-reference.ts` 识别。
 *
 * 点击一律交给 `onOpenFile`（页面侧接 `openStandalonePreview`），在新标签页预览，
 * 与工具行 / 引用文件预览保持同一套读取授权链。
 */
import * as React from 'react'
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown'
import { HighlightedCode } from './code-highlight'
import { FILE_PREVIEW_SCHEME, fileMentionLabel, pathFromFilePreviewUrl } from './file-mention-link'
import { REMARK_PLUGINS, REHYPE_PLUGINS } from './markdown-plugins'
import { resolveFileReference } from './file-reference'
import { MermaidBlock } from './mermaid-block'
import { mermaidFenceFromPre } from './mermaid-detection'

type MarkdownComponents = NonNullable<React.ComponentProps<typeof ReactMarkdown>['components']>

export interface MarkdownWithFilesProps {
  text: string
  /** 本轮工具触及的「文件名 → 路径」索引；缺省时裸文件名保持普通代码 */
  fileIndex?: ReadonlyMap<string, string>
  /** 点文件 chip 打开新标签页预览；不提供时文件引用保持普通行内代码 */
  onOpenFile?: (path: string) => void
}

export function MarkdownWithFiles({ text, fileIndex, onOpenFile }: MarkdownWithFilesProps): React.ReactElement {
  /**
   * 组件表必须跨渲染保持同一身份：调用方每帧都会新建 fileIndex Map，
   * 一旦让它进入依赖，`pre` 的类型就每帧变化，React 会把整棵子树卸载重挂 ——
   * MermaidBlock 的状态随之丢失，流式期间图表会在「源码 ↔ SVG」之间反复闪。
   * 因此只把最新值放 ref，组件本身用空依赖创建一次。
   */
  const latest = React.useRef({ fileIndex, onOpenFile })
  latest.current = { fileIndex, onOpenFile }

  const components = React.useMemo<MarkdownComponents>(() => ({
    // node 是 react-markdown 的 AST 附加字段，不能落到 DOM 属性上
    a: ({ href, children, node: _node, ...props }) => {
      const mentionedPath = typeof href === 'string' ? pathFromFilePreviewUrl(href) : null
      if (mentionedPath === null) return <a href={href} {...props}>{children}</a>
      return (
        <button
          type="button"
          className="file-ref-chip"
          title={`预览 ${mentionedPath}`}
          onClick={() => latest.current.onOpenFile?.(mentionedPath)}
        >
          {fileMentionLabel(mentionedPath)}
        </button>
      )
    },
    // 围栏代码块：mermaid 换成图表，其余保持原样。
    // 不能在 code 分支里判断 —— 自定义 code 组件后 React 层的 child.type 已不是 'code'，
    // 只有 hast node 还保留原始结构（见 mermaidFenceFromPre）。
    pre: ({ node, children, ...props }) => {
      const mermaidCode = mermaidFenceFromPre(node)
      if (mermaidCode === null) return <pre {...props}>{children}</pre>
      return <MermaidBlock code={mermaidCode} />
    },
    code: ({ className, children, node: _node, ...props }) => {
      // 围栏代码带 language-* 类名；多行内容也不可能是单个文件引用
      const raw = typeof children === 'string' ? children : ''
      const { fileIndex: index, onOpenFile: openFile } = latest.current
      const reference = openFile && !className && raw && !raw.includes('\n')
        ? resolveFileReference(raw, index)
        : null
      if (!reference || !openFile) {
        return <HighlightedCode className={className} {...props}>{children}</HighlightedCode>
      }
      return (
        <button
          type="button"
          className="file-ref-chip"
          title={`预览 ${reference.title}`}
          onClick={() => openFile(reference.path)}
        >
          {reference.label}
        </button>
      )
    },
  }), [])

  return (
    <ReactMarkdown
      remarkPlugins={REMARK_PLUGINS}
      rehypePlugins={REHYPE_PLUGINS}
      // react-markdown 默认只允许 http/https/mailto 等协议；
      // 内部预览链接必须放行，否则 href 变空 → 点 chip 停在/重载本页
      urlTransform={(url) => url.startsWith(FILE_PREVIEW_SCHEME)
        ? url
        : defaultUrlTransform(url)}
      components={components}
    >
      {text}
    </ReactMarkdown>
  )
}
