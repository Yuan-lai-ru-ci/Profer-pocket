/**
 * Markdown 原文 → 静态 HTML。
 *
 * 一次性预览页是独立文档，没有 React；在父页用与会话正文同一套插件渲染好片段，
 * 预览页只负责展示。react-markdown 默认不渲染原始 HTML、链接协议也经过过滤，
 * 生成的片段可以安全内联进 about:blank 页面。
 */
import * as React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import ReactMarkdown from 'react-markdown'
import { HighlightedCode } from './code-highlight'
import { REMARK_PLUGINS, REHYPE_PLUGINS } from './markdown-plugins'

export function renderMarkdownToStaticHtml(markdown: string): string {
  return renderToStaticMarkup(
    <ReactMarkdown
      remarkPlugins={REMARK_PLUGINS}
      rehypePlugins={REHYPE_PLUGINS}
      components={{
        // 临时标签页里的链接开新页，别把预览本身导航走
        a: ({ node: _node, ...props }) => <a {...props} target="_blank" rel="noreferrer" />,
        code: HighlightedCode,
      }}
    >
      {markdown}
    </ReactMarkdown>,
  )
}
