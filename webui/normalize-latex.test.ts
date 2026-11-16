import { describe, expect, test } from 'bun:test'
import { normalizeLatexDelimiters } from './normalize-latex'

describe('LaTeX 分隔符规范化', () => {
  test('Given 块级 \\[ \\] When 规范化 Then 变成 $$ $$', () => {
    expect(normalizeLatexDelimiters('\\[a^2+b^2=c^2\\]')).toBe('$$a^2+b^2=c^2$$')
  })

  test('Given 行内 \\( \\) When 规范化 Then 变成 $ $', () => {
    expect(normalizeLatexDelimiters('公式 \\(x+y\\) 结束')).toBe('公式 $x+y$ 结束')
  })

  test('Given 已是 dollar 形式 When 规范化 Then 原样', () => {
    const text = '行内 $a$ 与块级 $$b$$'
    expect(normalizeLatexDelimiters(text)).toBe(text)
  })

  test('Given 代码块/行内代码含反斜杠括号 When 规范化 Then 保护不改', () => {
    const text = [
      '```tex',
      '\\(literal\\) \\[literal\\]',
      '```',
      '还有 `\\(code\\)`',
      '正文 \\(x\\)',
    ].join('\n')
    const result = normalizeLatexDelimiters(text)
    expect(result).toContain('\\(literal\\) \\[literal\\]')
    expect(result).toContain('`\\(code\\)`')
    expect(result).toContain('$x$')
    expect(result).not.toContain('PROFER_LATEX_PROTECT')
  })

  test('Given 无 LaTeX 分隔符 When 规范化 Then 原样返回', () => {
    expect(normalizeLatexDelimiters('普通文本')).toBe('普通文本')
    expect(normalizeLatexDelimiters('')).toBe('')
  })
})
