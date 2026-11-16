/**
 * 文件预览的展示名解析。
 *
 * Windows 路径混进来是常态（工具行里的路径来自主进程，两种分隔符都可能出现），
 * 只按 `/` 切会把整条绝对路径当成文件名显示。
 */
import { describe, expect, test } from 'bun:test'
import { fileNameOf } from './file-preview'

describe('文件预览的展示名', () => {
  test('Given POSIX 路径 When 取文件名 Then 取最后一段', () => {
    expect(fileNameOf('/home/user/project/src/main.ts')).toBe('main.ts')
  })

  test('Given Windows 路径 When 取文件名 Then 同样取最后一段', () => {
    expect(fileNameOf('C://Users//me//project//README.md')).toBe('README.md')
  })

  test('Given 混合分隔符 When 取文件名 Then 取最后出现的那一段', () => {
    expect(fileNameOf('D:/work\\proj/a.tsx')).toBe('a.tsx')
  })

  test('Given 只有文件名 When 取文件名 Then 原样返回', () => {
    expect(fileNameOf('package.json')).toBe('package.json')
  })

  test('Given 空串 When 取文件名 Then 返回空串而不是崩溃', () => {
    expect(fileNameOf('')).toBe('')
  })
})
