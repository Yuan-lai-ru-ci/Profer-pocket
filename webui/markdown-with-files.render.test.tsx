import { describe, expect, test } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { buildTurnFileIndex } from './file-reference'
import { MarkdownWithFiles } from './markdown-with-files'

const openFile = (): void => {}

function render(text: string, paths?: string[]): string {
  const fileIndex = paths ? buildTurnFileIndex(paths) : undefined
  return renderToStaticMarkup(
    <MarkdownWithFiles
      text={text}
      onOpenFile={openFile}
      {...(fileIndex ? { fileIndex } : {})}
    />,
  )
}

describe('正文文件 chip 渲染', () => {
  test('Given 绝对路径行内代码 When 渲染 Then 出 chip，文案只留文件名', () => {
    const html = render('改好了：`/repo/src/app.ts`')
    expect(html).toContain('class="file-ref-chip"')
    expect(html).toContain('>app.ts</button>')
    expect(html).toContain('title="预览 /repo/src/app.ts"')
  })

  test('Given 裸文件名与本轮索引 When 渲染 Then 出 chip', () => {
    const html = render('看 `app.ts`', ['/repo/src/app.ts'])
    expect(html).toContain('class="file-ref-chip"')
    expect(html).toContain('>app.ts</button>')
  })

  test('Given 裸文件名没有索引 When 渲染 Then 保持普通行内代码', () => {
    const html = render('看 `app.ts`')
    expect(html).not.toContain('file-ref-chip')
    expect(html).toContain('<code>app.ts</code>')
  })

  test('Given 命令行行内代码 When 渲染 Then 不变成 chip', () => {
    const html = render('执行 `bun run build` 即可')
    expect(html).not.toContain('file-ref-chip')
    expect(html).toContain('<code>bun run build</code>')
  })

  test('Given 围栏代码 When 渲染 Then 即使内容是文件路径也保持代码块', () => {
    const html = render('```ts\nsrc/app.ts\n```')
    expect(html).not.toContain('file-ref-chip')
    expect(html).toContain('language-ts')
  })

  test('Given 带语言的围栏代码 When 渲染 Then 正文里同样有语法高亮', () => {
    const html = render('```ts\nconst answer: number = 42\n```')
    expect(html).toContain('language-ts')
    expect(html).toContain('hljs-keyword')
  })

  test('Given 未知语言 When 渲染 Then 保持纯文本代码块', () => {
    const html = render('```unknownlang\nconst answer = 42\n```')
    expect(html).toContain('language-unknownlang')
    expect(html).toContain('const answer = 42')
    expect(html).not.toContain('hljs-')
  })

  test('Given @file 引用 When 渲染 Then 与行内文件引用用同一种 chip', () => {
    const html = render('看 @file:notes.md 这份')
    expect(html).toContain('class="file-ref-chip"')
    expect(html).toContain('title="预览 notes.md"')
  })

  test('Given 编码过的中文 @file 引用 When 渲染 Then chip 显示解码后的文件名', () => {
    const token = encodeURIComponent('子目录/协作子代理测试报告.md')
    const html = render(`对 @file:${token} 做一些修改`)
    expect(html).toContain('class="file-ref-chip"')
    expect(html).toContain('>协作子代理测试报告.md</button>')
    expect(html).not.toContain('%E5')
    expect(html).toContain('title="预览 子目录/协作子代理测试报告.md"')
  })
})

describe('正文 Mermaid 代码块', () => {
  test('Given language-mermaid 围栏 When 服务端渲染 Then 走图表组件（未出图前先显示源码）', () => {
    const html = render('```mermaid\ngraph TD\n  A --> B\n```')
    expect(html).toContain('class="mermaid-src"')
    expect(html).toContain('language-mermaid')
    // 图表块的源码不再经过语法高亮，低亮化类名不应出现
    expect(html).not.toContain('hljs-')
  })

  test('Given 无语言但首行 flowchart TD When 渲染 Then 同样交给图表组件', () => {
    const html = render('```\nflowchart TD\n  A --> B\n```')
    expect(html).toContain('class="mermaid-src"')
  })

  test('Given 普通英文句子的无语言代码块 When 渲染 Then 保持普通代码块', () => {
    const html = render('```\ngraph of the results\n```')
    expect(html).not.toContain('mermaid-src')
    expect(html).toContain('<pre>')
  })

  test('Given 标了其它语言的代码块 When 渲染 Then 不走图表组件', () => {
    const html = render('```python\ngraph TD\nA-->B\n```')
    expect(html).not.toContain('mermaid-src')
    expect(html).toContain('language-python')
  })
})
