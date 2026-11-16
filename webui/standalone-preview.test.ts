import { describe, expect, test } from 'bun:test'
import { buildPreviewDocument, escapeHtml } from './standalone-preview'

describe('预览页 HTML 转义', () => {
  test('Given 文件名含尖括号/引号 When 转义 Then 不能注入标签', () => {
    const escaped = escapeHtml('<img src=x onerror="alert(1)">')
    expect(escaped).not.toContain('<img')
    expect(escaped).toContain('&lt;img')
    expect(escaped).toContain('&quot;')
  })
})

describe('一次性预览页自包含', () => {
  test('Given 图片 When 生成 Then 内联 img、无脚本、无外部资源', () => {
    const html = buildPreviewDocument('shot.png', { kind: 'image', dataUrl: 'data:image/png;base64,AAAA' })
    expect(html).toContain('<img src="data:image/png;base64,AAAA"')
    expect(html).toContain('<meta charset="utf-8">')
    // 允许的脚本只有本地下载函数；不引用任何外部资源、不连 WS
    expect(html).not.toContain('<script src=')
    expect(html).not.toContain('WebSocket')
    expect(html).not.toContain('fetch(')
  })

  test('Given PDF When 生成 Then 桌面内嵌 Blob URL，手机直接跳转到 PDF', () => {
    const html = buildPreviewDocument('报告.pdf', { kind: 'pdf', dataUrl: 'data:application/pdf;base64,BBBB' })
    expect(html).toContain('type="application/pdf"')
    expect(html).toContain('id="profer-pdf"')
    // data URL 直接喂给 object 时，部分浏览器会走下载而不是内置查看器：脚本升级成 Blob URL
    expect(html).toContain("URL.createObjectURL(new Blob([bytes],{type:mime}))")
    // 手机不渲染内嵌 PDF：预览标签页自己就是新标签页，直接跳过去，不再多一步点击
    expect(html).toContain('location.replace(url)')
    expect(html).toContain('/Android|iPad|iPhone|iPod|Mobile/')
    expect(html).not.toContain('profer-pdf-mobile')
    expect(html).toContain('在新标签页打开')
    expect(html).toContain('data-name="报告.pdf"')
    expect(html).toContain('proferDownload()')
    // 不能再用 data URL 直接 download（Safari 会存成「下载」无扩展名）
    expect(html).not.toContain('<a href="data:application/pdf')
  })

  test('Given 非 PDF When 生成 Then 不注入 PDF 的 Blob 升级脚本', () => {
    const html = buildPreviewDocument('shot.png', { kind: 'image', dataUrl: 'data:image/png;base64,AAAA' })
    expect(html).not.toContain('profer-pdf')
  })

  test('Given 文本含 HTML When 生成 Then 正文被转义仍可读', () => {
    const html = buildPreviewDocument('a.txt', { kind: 'text', text: '<script>x</script>', truncated: true })
    expect(html).toContain('&lt;script&gt;x&lt;/script&gt;')
    expect(html).not.toContain('<script>x</script>')
    expect(html).toContain('仅显示前 256 KB')
  })

  test('Given Markdown When 生成 Then 默认预览且可切原文', () => {
    const html = buildPreviewDocument('notes.md', {
      kind: 'markdown',
      text: '# 标题\n\n正文',
      html: '<h1>标题</h1>\n<p>正文</p>',
      truncated: false,
    })
    // 默认展示渲染结果，原文容器先隐藏
    expect(html).toContain('<div class="md-preview" id="profer-md-preview"><h1>标题</h1>')
    expect(html).toContain('id="profer-md-raw" hidden')
    expect(html).toContain("proferSetView('preview')")
    expect(html).toContain("proferSetView('raw')")
    expect(html).toContain('aria-pressed="true"')
    expect(html).toContain('id="profer-md-preview"')
    expect(html).toContain('# 标题')
  })

  test('Given Markdown When 点复制全文 Then 复制的是原文', () => {
    const html = buildPreviewDocument('notes.md', {
      kind: 'markdown',
      text: '# 标题\n\n`code`',
      html: '<h1>标题</h1>',
      truncated: false,
    })
    expect(html).toContain('id="profer-copy"')
    expect(html).toContain('复制全文')
    expect(html).toContain('document.execCommand(')
    // 原文放在隐藏 pre 里，复制走 textContent —— 转义后的实体解析回来仍是原文
    expect(html).toContain('`code`')
    expect(html).toContain('navigator.clipboard')
  })

  test('Given Markdown 原文含标签 When 生成 Then 原文视图不能注入脚本', () => {
    const html = buildPreviewDocument('x.md', {
      kind: 'markdown',
      text: '</pre><script>alert(1)</script>',
      html: '<p>ok</p>',
      truncated: true,
    })
    expect(html).toContain('&lt;/pre&gt;&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(html).not.toContain('</pre><script>')
    expect(html).toContain('仅显示前 256 KB')
  })

  test('Given Markdown 含公式 When 带 KaTeX 样式 Then 内联进页面', () => {
    const withMath = buildPreviewDocument('m.md', {
      kind: 'markdown',
      text: '$x^2$',
      html: '<span class="katex">x</span>',
      truncated: false,
      katexCss: '.katex{color:red}',
    })
    expect(withMath).toContain('<style>.katex{color:red}</style>')
    const plain = buildPreviewDocument('m.md', {
      kind: 'markdown',
      text: '# 无公式',
      html: '<h1>无公式</h1>',
      truncated: false,
    })
    expect(plain).not.toContain('.katex{')
  })

  test('Given 普通文本 When 生成 Then 不出现 Markdown 的切换与复制', () => {
    const html = buildPreviewDocument('a.txt', { kind: 'text', text: 'hi', truncated: false })
    expect(html).not.toContain('profer-md-preview')
    expect(html).not.toContain('proferCopy')
    expect(html).not.toContain('复制全文')
  })

  test('Given 二进制/错误 When 生成 Then 给下载或错误文案', () => {
    const binary = buildPreviewDocument('a.docx', { kind: 'binary', dataUrl: 'data:application/octet-stream;base64,CCCC' })
    expect(binary).toContain('下载文件')
    const error = buildPreviewDocument('a', { kind: 'error', message: '越权' })
    expect(error).toContain('打不开：越权')
  })
})
