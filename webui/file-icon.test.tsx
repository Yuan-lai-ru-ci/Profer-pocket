import { describe, expect, test } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { FileTypeIcon, resolveFileIconKind } from './file-icon'

describe('@ 引用列表的文件图标归类', () => {
  test('Given 常见扩展名 When 归类 Then 大小写不敏感且与桌面语义一致', () => {
    expect(resolveFileIconKind('报告.PDF', false)).toBe('pdf')
    expect(resolveFileIconKind('数据.xlsx', false)).toBe('sheet')
    expect(resolveFileIconKind('说明.md', false)).toBe('markdown')
    expect(resolveFileIconKind('app.tsx', false)).toBe('tsx')
    expect(resolveFileIconKind('c-summary.json', false)).toBe('json')
    expect(resolveFileIconKind('logo.svg', false)).toBe('svg')
  })

  test('Given 无扩展名或特殊文件名 When 归类 Then 按完整文件名识别', () => {
    expect(resolveFileIconKind('Dockerfile', false)).toBe('docker')
    expect(resolveFileIconKind('docker-compose.yml', false)).toBe('docker')
    expect(resolveFileIconKind('package.json', false)).toBe('npm')
    expect(resolveFileIconKind('tsconfig.build.json', false)).toBe('tsconfig')
    expect(resolveFileIconKind('vite.config.ts', false)).toBe('vite')
    expect(resolveFileIconKind('.env.local', false)).toBe('config')
    expect(resolveFileIconKind('LICENSE', false)).toBe('license')
  })

  test('Given 目录 When 归类 Then 展开与折叠用不同文件夹图标', () => {
    expect(resolveFileIconKind('src', true)).toBe('folder')
    expect(resolveFileIconKind('src', true, true)).toBe('folder-open')
  })

  test('Given 未知扩展名或点开头的普通文件 When 归类 Then 落到通用文件图标', () => {
    expect(resolveFileIconKind('archive.xyz', false)).toBe('file')
    expect(resolveFileIconKind('notes', false)).toBe('file')
    expect(resolveFileIconKind('.hidden', false)).toBe('file')
  })

  test('Given 完整路径 When 归类 Then 只看最后一段', () => {
    expect(resolveFileIconKind('src/components/App.tsx', false)).toBe('tsx')
  })
})

describe('文件图标渲染', () => {
  test('Given PDF 与未知类型 When 渲染 Then 出内联 SVG 且两者不同', () => {
    const pdf = renderToStaticMarkup(<FileTypeIcon name="发票.pdf" isDirectory={false} />)
    const generic = renderToStaticMarkup(<FileTypeIcon name="a.xyz" isDirectory={false} />)
    expect(pdf).toContain('<svg')
    expect(pdf).toContain('aria-hidden="true"')
    expect(pdf).not.toBe(generic)
  })

  test('Given 展开的目录 When 渲染 Then 与折叠状态不同', () => {
    const closed = renderToStaticMarkup(<FileTypeIcon name="src" isDirectory />)
    const open = renderToStaticMarkup(<FileTypeIcon name="src" isDirectory isOpen />)
    expect(closed).toContain('<svg')
    expect(closed).not.toBe(open)
  })
})
