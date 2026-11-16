import { describe, expect, test } from 'bun:test'
import {
  buildTurnFileIndex,
  resolveFileReference,
  stripLineCol,
} from './file-reference'

describe('行内文件引用：路径识别', () => {
  test('Given 绝对路径 When 解析 Then 出 chip，显示文件名与行号', () => {
    expect(resolveFileReference('/Users/me/repo/src/main.ts')).toEqual({
      path: '/Users/me/repo/src/main.ts',
      label: 'main.ts',
      title: '/Users/me/repo/src/main.ts',
    })
    expect(resolveFileReference('/repo/src/main.ts:42:7')).toEqual({
      path: '/repo/src/main.ts',
      label: 'main.ts:42:7',
      title: '/repo/src/main.ts:42:7',
    })
  })

  test('Given Windows 盘符与 UNC 路径 When 解析 Then 都能识别', () => {
    expect(resolveFileReference('D:\\ProferAI\\README.md')?.path).toBe('D:\\ProferAI\\README.md')
    expect(resolveFileReference('C:/work/report.pdf')?.path).toBe('C:/work/report.pdf')
    expect(resolveFileReference('\\\\server\\share\\a.json')?.path).toBe('\\\\server\\share\\a.json')
  })

  test('Given 带目录的相对路径 When 解析 Then 原样交给服务端按授权根解析', () => {
    expect(resolveFileReference('src/webui/main.tsx')).toEqual({
      path: 'src/webui/main.tsx',
      label: 'main.tsx',
      title: 'src/webui/main.tsx',
    })
  })

  test('Given 行内代码不是文件 When 解析 Then 保持普通代码', () => {
    expect(resolveFileReference('npm run build')).toBeNull()
    expect(resolveFileReference('console.log')).toBeNull()
    expect(resolveFileReference('git diff HEAD~1 -- src/a.ts')).toBeNull()
    expect(resolveFileReference('https://example.com/a.ts')).toBeNull()
    expect(resolveFileReference('/repo/src')).toBeNull()
    expect(resolveFileReference('/repo/archive.zip')).toBeNull()
    expect(resolveFileReference('//cdn.example.com/a.ts')).toBeNull()
    expect(resolveFileReference('~/notes.md')).toBeNull()
    expect(resolveFileReference('多行\n文件.ts')).toBeNull()
  })

  test('Given 盘符后跟行号 When 拆分 Then 不把盘符当行号后缀', () => {
    expect(stripLineCol('C:12')).toEqual({ path: 'C:12', suffix: '' })
    expect(stripLineCol('src/a.ts:12')).toEqual({ path: 'src/a.ts', suffix: ':12' })
  })
})

describe('行内文件引用：本轮文件名索引', () => {
  test('Given 绝对路径工具事实 When 裸文件名引用 Then 补全为绝对路径', () => {
    const index = buildTurnFileIndex(['/repo/src/app.ts', '/repo/README.md'])
    expect(resolveFileReference('app.ts', index)).toEqual({
      path: '/repo/src/app.ts',
      label: 'app.ts',
      title: 'app.ts',
    })
  })

  test('Given 同名文件冲突 When 裸文件名引用 Then 不给 chip，避免误开', () => {
    const index = buildTurnFileIndex(['/repo/a/config.ts', '/repo/b/config.ts'])
    expect(index.has('config.ts')).toBe(false)
    expect(resolveFileReference('config.ts', index)).toBeNull()
  })

  test('Given 绝对路径与相对改动清单指向同名文件 When 建索引 Then 以绝对路径为准', () => {
    const index = buildTurnFileIndex(['/repo/src/app.ts', 'src/app.ts'])
    expect(index.get('app.ts')).toBe('/repo/src/app.ts')
    expect(resolveFileReference('app.ts', index)?.path).toBe('/repo/src/app.ts')
  })

  test('Given 索引里有同名但不同目录 When 带目录相对引用 Then 不套用索引', () => {
    const index = buildTurnFileIndex(['/repo/other/util.ts'])
    expect(resolveFileReference('src/util.ts', index)).toEqual({
      path: 'src/util.ts',
      label: 'util.ts',
      title: 'src/util.ts',
    })
    expect(resolveFileReference('other/util.ts', index)?.path).toBe('/repo/other/util.ts')
  })

  test('Given 没有索引 When 裸文件名引用 Then 保持普通代码', () => {
    expect(resolveFileReference('app.ts')).toBeNull()
    expect(resolveFileReference('app.ts', new Map())).toBeNull()
  })

  test('Given 索引里的路径带行号 When 建索引 Then 索引键不带后缀', () => {
    const index = buildTurnFileIndex(['/repo/src/app.ts:12'])
    expect(index.get('app.ts')).toBe('/repo/src/app.ts')
  })
})
