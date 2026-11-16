/**
 * WebUI 两处 ReactMarkdown（正文 / 执行过程）共用的插件配置。
 * 与桌面端 message.tsx 一致：remarkGfm + remarkMath，rehypeKatex 容错解析。
 */
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import type * as React from 'react'
import type ReactMarkdown from 'react-markdown'
import { remarkFileMentions } from './file-mention-link'

// 从组件 props 推导插件列表类型，避免直接依赖传递依赖 unified
type PluggableList = NonNullable<React.ComponentProps<typeof ReactMarkdown>['rehypePlugins']>

// 模块级常量：每次渲染传同一个数组引用，避免 react-markdown 反复重建处理器
export const REMARK_PLUGINS: PluggableList = [remarkGfm, remarkMath, remarkFileMentions]
export const REHYPE_PLUGINS: PluggableList = [[rehypeKatex, { strict: 'ignore', throwOnError: false }]]
