import { describe, expect, test } from 'bun:test'
import {
  FILE_PREVIEW_SCHEME, filePreviewUrl, pathFromFilePreviewUrl,
  remarkFileMentions, splitFileMentions,
} from './file-mention-link'
import { encodeFileTokenPath } from './mentions'

describe('@file 链接 URL', () => {
  test('Given 中文/路径 When 构造 Then 可逆', () => {
    const url = filePreviewUrl('../workspace-files/报告 2026.pdf')
    expect(url.startsWith(FILE_PREVIEW_SCHEME)).toBe(true)
    expect(pathFromFilePreviewUrl(url)).toBe('../workspace-files/报告 2026.pdf')
  })

  test('Given 非本方案 URL When 还原 Then null', () => {
    expect(pathFromFilePreviewUrl('https://example.com')).toBeNull()
    expect(pathFromFilePreviewUrl('mailto:a@b')).toBeNull()
  })

  test('Given 损坏的编码 When 还原 Then null（不抛错）', () => {
    expect(pathFromFilePreviewUrl(`${FILE_PREVIEW_SCHEME}%ZZ`)).toBeNull()
  })
})

describe('正文 token 切分', () => {
  test('Given 单个 @file When 切分 Then 文本 + 链接', () => {
    expect(splitFileMentions('看这个 @file:报告.md 吧')).toEqual([
      { type: 'text', value: '看这个 ' },
      // 链接文本是展示名（桌面端 MentionChip 同款），不带 `@file:` 前缀
      { type: 'link', url: filePreviewUrl('报告.md'), children: [{ type: 'text', value: '报告.md' }] },
      { type: 'text', value: ' 吧' },
    ])
  })

  test('Given 多个 @file When 切分 Then 各自成链接', () => {
    const parts = splitFileMentions('@file:a.md 和 @file:b.png')!
    expect(parts.filter((part) => (part as { type?: string }).type === 'link')).toHaveLength(2)
  })

  test('Given 编码后的空格路径 token When 切分 Then 还原成完整路径链接', () => {
    const token = encodeFileTokenPath('2020_tai_pre (1).pdf')
    const parts = splitFileMentions(`见 @file:${token}`)!
    const link = parts.find((part) => (part as { type?: string }).type === 'link') as {
      url: string
      children: Array<{ value: string }>
    }
    expect(pathFromFilePreviewUrl(link.url)).toBe('2020_tai_pre (1).pdf')
    expect(link.children[0]?.value).toBe('2020_tai_pre (1).pdf')
  })

  test('Given 编码后的中文路径 When 切分 Then 展示名是解码后的文件名', () => {
    const token = encodeFileTokenPath('子目录/协作子代理测试报告.md')
    const parts = splitFileMentions(`对 @file:${token} 做修改`)!
    const link = parts.find((part) => (part as { type?: string }).type === 'link') as {
      url: string
      children: Array<{ value: string }>
    }
    expect(link.children[0]?.value).toBe('协作子代理测试报告.md')
    expect(link.children[0]?.value).not.toContain('%')
    expect(pathFromFilePreviewUrl(link.url)).toBe('子目录/协作子代理测试报告.md')
  })

  test('Given 邮箱/普通文本 When 切分 Then 原样返回 null', () => {
    expect(splitFileMentions('mail@example.com')).toBeNull()
    expect(splitFileMentions('没有引用')).toBeNull()
  })
})

describe('remark 插件', () => {
  test('Given mdast 树 When 应用 Then text 子节点被换成 link，其它节点不动', () => {
    const tree = {
      type: 'root',
      children: [
        { type: 'paragraph', children: [{ type: 'text', value: '请见 @file:交付.pdf' }] },
        { type: 'paragraph', children: [{ type: 'text', value: '普通文字' }] },
      ],
    }
    remarkFileMentions()(tree)
    const paragraph = tree.children[0] as { children: Array<{ type: string; url?: string }> }
    expect(paragraph.children.map((node) => node.type)).toEqual(['text', 'link'])
    expect(paragraph.children[1].url).toBe(filePreviewUrl('交付.pdf'))
    const second = tree.children[1] as { children: Array<{ type: string }> }
    expect(second.children[0].type).toBe('text')
  })
})
