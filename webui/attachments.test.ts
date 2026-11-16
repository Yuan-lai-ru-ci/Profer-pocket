import { describe, expect, test } from 'bun:test'
import {
  MAX_ATTACHMENT_BYTES,
  MAX_ATTACHMENT_COUNT,
  base64FromDataUrl,
  formatBytes,
  isImageAttachment,
  parseAttachedFiles,
  resolveSendText,
  validateAttachmentFile,
} from './attachments'

describe('附件校验', () => {
  test('Given 正常文件 When 校验 Then 放行', () => {
    expect(validateAttachmentFile({ name: '报表.xlsx', size: 1024 }, 0)).toBeNull()
  })

  test('Given 超过 10 MB When 校验 Then 拒绝并说明大小（服务端硬上限，提前拦省一次往返）', () => {
    const message = validateAttachmentFile({ name: 'big.zip', size: MAX_ATTACHMENT_BYTES + 1 }, 0)
    expect(message).toContain('超过 10.0 MB')
  })

  test('Given 刚好等于上限 When 校验 Then 放行（边界不该被拒绝）', () => {
    expect(validateAttachmentFile({ name: 'edge.bin', size: MAX_ATTACHMENT_BYTES }, 0)).toBeNull()
  })

  test('Given 空文件 When 校验 Then 拒绝（传上去也没用）', () => {
    expect(validateAttachmentFile({ name: 'empty.txt', size: 0 }, 0)).toBe('空文件')
  })

  test('Given 已达数量上限 When 校验 Then 拒绝', () => {
    expect(validateAttachmentFile({ name: 'a.txt', size: 1 }, MAX_ATTACHMENT_COUNT)).toContain('最多')
  })
})

describe('体积格式化', () => {
  test('Given 各量级 When 格式化 Then 单位可读', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
  })
})

describe('data URL 取 base64', () => {
  test('Given 标准 data URL When 取 Then 只留载荷（服务端要裸 base64）', () => {
    expect(base64FromDataUrl('data:image/png;base64,iVBORw0KGgo=')).toBe('iVBORw0KGgo=')
  })

  test('Given 非 base64 或残缺 data URL When 取 Then 返回空串，交给上层报错', () => {
    expect(base64FromDataUrl('data:text/plain,hello')).toBe('')
    expect(base64FromDataUrl('not-a-data-url')).toBe('')
    expect(base64FromDataUrl('')).toBe('')
  })
})

describe('发送文案', () => {
  test('Given 有正文 When 解析 Then 用正文（附件不影响文案）', () => {
    expect(resolveSendText('  看下这个  ', 2)).toBe('看下这个')
  })

  test('Given 只有附件 When 解析 Then 给一句兜底文案', () => {
    // 不能把 <attached_files> 当正文：那是给模型看的路径清单
    expect(resolveSendText('   ', 1)).toBe('请查看我上传的附件。')
  })

  test('Given 既没正文也没附件 When 解析 Then 空串（发送键保持禁用）', () => {
    expect(resolveSendText('', 0)).toBe('')
  })
})

describe('消息里的附件块解析', () => {
  test('Given 服务端写入的标准块 When 解析 Then 取出文件名与路径，正文不带路径清单', () => {
    const raw = '<attached_files>\n- image.png: C:\\Users\\x\\session\\image.png\n- 说明.pdf: C:\\Users\\x\\session\\说明.pdf\n</attached_files>\n\n现在这个项目干净了吗？'
    const { files, text } = parseAttachedFiles(raw)
    expect(files).toEqual([
      { filename: 'image.png', path: 'C:\\Users\\x\\session\\image.png' },
      { filename: '说明.pdf', path: 'C:\\Users\\x\\session\\说明.pdf' },
    ])
    expect(text).toBe('现在这个项目干净了吗？')
    expect(text).not.toContain('attached_files')
  })

  test('Given Windows 绝对路径带冒号 When 解析 Then 只在第一个 ": " 处切分', () => {
    const raw = '<attached_files>\n- a.txt: C:\\a\\b\\a.txt\n</attached_files>\n'
    expect(parseAttachedFiles(raw).files[0]?.path).toBe('C:\\a\\b\\a.txt')
  })

  test('Given <file path> 引用写法 When 解析 Then 文件名从路径尾部取', () => {
    const raw = '<attached_files>\n<file path="/tmp/a.ts" />\n</attached_files>\n帮我改成 TypeScript'
    const { files, text } = parseAttachedFiles(raw)
    expect(files).toEqual([{ filename: 'a.ts', path: '/tmp/a.ts' }])
    expect(text).toBe('帮我改成 TypeScript')
  })

  test('Given 没有附件块 When 解析 Then 原样返回正文，不误伤带尖括号的文字', () => {
    const text = '请解释 <div> 和 </div> 的区别'
    expect(parseAttachedFiles(text)).toEqual({ files: [], text })
  })

  test('Given 附件-only 消息 When 解析 Then files 有值、正文是兜底文案', () => {
    const raw = '<attached_files>\n- a.png: /s/a.png\n</attached_files>\n\n请查看我上传的附件。'
    const result = parseAttachedFiles(raw)
    expect(result.files).toHaveLength(1)
    expect(result.text).toBe('请查看我上传的附件。')
  })

  test('Given 图片与非图片附件 When 判定类型 Then 只把图片标出来', () => {
    expect(isImageAttachment('a.PNG')).toBe(true)
    expect(isImageAttachment('a.svg')).toBe(true)
    expect(isImageAttachment('a.pdf')).toBe(false)
    expect(isImageAttachment('a.txt')).toBe(false)
  })
})
