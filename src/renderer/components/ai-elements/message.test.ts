import { describe, expect, test } from 'bun:test'
import { localFileUrlToPath, Message, MessageContent, MessageResponse } from './message'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'

// 窄屏样式只挂在明确的消息/Markdown 根节点，保留真正的列表语义。
describe('Pocket Markdown layout hooks', () => {
  test('keeps nested list semantics under the scoped message-response root', () => {
    const html = renderToStaticMarkup(createElement(Message, { from: 'assistant' },
      createElement(MessageContent, null,
        createElement(MessageResponse, { children: '- 第一层\n  - 第二层\n    - 第三层' }),
      ),
    ))
    expect(html).toContain('message-item')
    expect(html).toContain('message-content')
    expect(html).toContain('message-response')
    expect(html.match(/<ul>/g)).toHaveLength(3)
    expect(html).toContain('第三层')
  })
})

describe('localFileUrlToPath', () => {
  test('normalizes a Windows file URL to an absolute Windows path', () => {
    expect(localFileUrlToPath('file:///C:/Users/yuan/Documents/report%20final.md'))
      .toBe('C:/Users/yuan/Documents/report final.md')
  })

  test('preserves an absolute Unix file path', () => {
    expect(localFileUrlToPath('file:///tmp/profer/report.md')).toBe('/tmp/profer/report.md')
  })

  test('allows localhost but rejects remote-host file URLs', () => {
    expect(localFileUrlToPath('file://localhost/C:/workspace/readme.md'))
      .toBe('C:/workspace/readme.md')
    expect(localFileUrlToPath('file://server/share/secret.md')).toBeNull()
  })

  test('rejects non-file URLs', () => {
    expect(localFileUrlToPath('https://example.com/report.md')).toBeNull()
  })
})
