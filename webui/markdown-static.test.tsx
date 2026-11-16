import { describe, expect, test } from 'bun:test'
import { renderMarkdownToStaticHtml } from './markdown-static'

describe('Markdown 静态渲染（一次性预览页用）', () => {
  test('Given GFM 表格与任务列表 When 渲染 Then 生成对应结构', () => {
    const html = renderMarkdownToStaticHtml(
      '| 列 A | 列 B |\n| --- | --- |\n| 1 | 2 |\n\n- [x] 已完成\n- [ ] 待办',
    )
    expect(html).toContain('<table>')
    expect(html).toContain('<th>列 A</th>')
    expect(html).toContain('type="checkbox"')
    expect(html).toContain('checked')
  })

  test('Given 行内与块级公式 When 渲染 Then 产出 KaTeX 标记', () => {
    const html = renderMarkdownToStaticHtml('$x^2$\n\n$$\n\\frac{1}{2}\n$$')
    expect(html).toContain('class="katex"')
    expect(html).toContain('katex-display')
  })

  test('Given 原始 HTML When 渲染 Then 不进入输出（预览页不能执行脚本）', () => {
    const html = renderMarkdownToStaticHtml('<script>alert(1)</script>\n\n正文')
    expect(html).not.toContain('<script>')
    expect(html).toContain('正文')
  })

  test('Given 链接 When 渲染 Then 开新标签页', () => {
    const html = renderMarkdownToStaticHtml('[Profer](https://example.com)')
    expect(html).toContain('href="https://example.com"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noreferrer"')
  })

  test('Given 带语言的围栏代码 When 渲染 Then 产出语法高亮标记', () => {
    const html = renderMarkdownToStaticHtml('```ts\nconst answer: number = 42\n```')
    expect(html).toContain('language-ts')
    expect(html).toContain('hljs-keyword')
    expect(html).toContain('hljs-number')
  })

  test('Given 未知语言 When 渲染 Then 原样输出代码而不报错', () => {
    const html = renderMarkdownToStaticHtml('```mermaid\ngraph TD; A-->B\n```')
    expect(html).toContain('language-mermaid')
    expect(html).toContain('graph TD; A--&gt;B')
    expect(html).not.toContain('hljs-')
  })

  test('Given console 围栏 When 渲染 Then 按 shell 高亮（别名补齐）', () => {
    const html = renderMarkdownToStaticHtml('```console\n# 查看进程\nps aux | head\n```')
    expect(html).toContain('language-console')
    expect(html).toContain('hljs-comment')
  })
})
