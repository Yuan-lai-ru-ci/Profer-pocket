import { describe, expect, test } from 'bun:test'
import { looksLikeMermaidDefinition, mermaidFenceFromPre, shouldRenderMermaidCodeBlock } from './mermaid-detection'

describe('Mermaid 代码块识别', () => {
  test('Given language-mermaid When 判定 Then 渲染', () => {
    expect(shouldRenderMermaidCodeBlock('language-mermaid', '随便什么内容')).toBe(true)
  })

  test('Given language-mmd 别名 When 判定 Then 渲染', () => {
    expect(shouldRenderMermaidCodeBlock('language-mmd', 'graph TD\nA-->B')).toBe(true)
  })

  test('Given 标了其它语言 When 首行像图 When 判定 Then 不渲染', () => {
    expect(shouldRenderMermaidCodeBlock('language-python', 'graph TD\nA-->B')).toBe(false)
    expect(shouldRenderMermaidCodeBlock('language-text', 'sequenceDiagram')).toBe(false)
  })

  test('Given 无语言且 flowchart 带方向 When 判定 Then 渲染', () => {
    expect(looksLikeMermaidDefinition('flowchart TD\n  A --> B')).toBe(true)
    expect(looksLikeMermaidDefinition('graph LR\n  A --> B')).toBe(true)
    expect(looksLikeMermaidDefinition('flowchart-elk TB\n  A --> B')).toBe(true)
  })

  test('Given 无语言但首行是普通英文句子 When 判定 Then 不渲染', () => {
    expect(looksLikeMermaidDefinition('graph of the results is below')).toBe(false)
    expect(looksLikeMermaidDefinition('flowchart for the pipeline')).toBe(false)
  })

  test('Given 首行是 mermaid 指令或注释 When 判定 Then 跳过再看下一行', () => {
    expect(looksLikeMermaidDefinition('%%{init: {"theme":"dark"}}%%\nsequenceDiagram\n  A->>B: hi')).toBe(true)
    expect(looksLikeMermaidDefinition('%% 说明\nclassDiagram')).toBe(true)
  })

  test('Given 官方支持的图型关键字 When 判定 Then 渲染', () => {
    for (const firstLine of ['sequenceDiagram', 'stateDiagram-v2', 'erDiagram', 'gantt', 'pie', 'gitGraph', 'mindmap', 'timeline']) {
      expect(looksLikeMermaidDefinition(`${firstLine}\n  x`)).toBe(true)
    }
  })

  test('Given 普通代码首行 When 判定 Then 不渲染', () => {
    expect(looksLikeMermaidDefinition('const answer = 42')).toBe(false)
    expect(looksLikeMermaidDefinition('def graph():\n    pass')).toBe(false)
  })
})

function pre(className: string | undefined, code: string): unknown {
  return {
    type: 'element',
    tagName: 'pre',
    children: [{
      type: 'element',
      tagName: 'code',
      properties: className ? { className: [className] } : {},
      children: [{ type: 'text', value: code }],
    }],
  }
}

describe('从 <pre> 提取 Mermaid 源码', () => {
  test('Given react-markdown 的 pre hast When 是 mermaid Then 返回去掉行尾换行的源码', () => {
    expect(mermaidFenceFromPre(pre('language-mermaid', 'graph TD\nA-->B\n'))).toBe('graph TD\nA-->B')
  })

  test('Given 没有语言标记但首行是图型 When 提取 Then 照常返回源码', () => {
    expect(mermaidFenceFromPre(pre(undefined, 'sequenceDiagram\nA->>B: hi'))).toBe('sequenceDiagram\nA->>B: hi')
  })

  test('Given 普通代码块 When 提取 Then 返回 null', () => {
    expect(mermaidFenceFromPre(pre('language-ts', 'const a = 1'))).toBeNull()
    expect(mermaidFenceFromPre(pre(undefined, 'plain text'))).toBeNull()
  })

  test('Given 非 pre 结构 When 提取 Then 返回 null', () => {
    expect(mermaidFenceFromPre(undefined)).toBeNull()
    expect(mermaidFenceFromPre({ type: 'element', tagName: 'pre', children: [] })).toBeNull()
  })
})
