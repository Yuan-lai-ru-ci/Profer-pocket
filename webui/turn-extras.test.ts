import { describe, expect, test } from 'bun:test'
import type { MessageGroup } from '@profer/session-core'
import { extractTurnExtras, stripContextBlocks } from './turn-extras'

describe('上下文注入块剥离', () => {
  test('Given 用户消息含中断上下文 When 渲染前剥离 Then 只保留用户真正输入的内容', () => {
    const raw = `<quoted_context source="agent-interruption" label="已被用户中断">\n上次任务被中断（已被用户中断，2026/11/11 22:34:19），可能未完成。\n请先查看对话历史中你上一条收到的用户消息，继续完成该任务。\n</quoted_context>\n继续`
    expect(stripContextBlocks(raw)).toBe('继续')
  })

  test('Given 其它上下文注入块 When 剥离 Then 全部剔除且不留空行', () => {
    const raw = '<attached_files>a</attached_files>\n<quoted_file path="x">b</quoted_file>\n<referenced_sessions>c</referenced_sessions>\n[PROFER_IMAGE_ATTACHMENT:{"localPath":"x"}]\n真正的问题'
    expect(stripContextBlocks(raw)).toBe('真正的问题')
  })

  test('Given 普通文本 When 剥离 Then 原样保留（只 trim）', () => {
    expect(stripContextBlocks('  展示一个图片  ')).toBe('展示一个图片')
  })
})

describe('回合工具与图片提取', () => {
  const turn = (...contents: unknown[][]) => ({
    type: 'assistant-turn',
    assistantMessages: [],
    turnMessages: contents.map((content) => ({ message: { content } })),
  }) as unknown as MessageGroup

  test('Given send_local_image 工具调用 When 提取 Then 收集图片路径并登记工具', () => {
    const result = extractTurnExtras(turn([
      { type: 'text', text: '图片已生成' },
      { type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'ls -la' } },
      { type: 'tool_use', id: 't2', name: 'send_local_image', input: { path: 'drawing.png' } },
    ]))
    expect(result.imagePaths).toEqual(['drawing.png'])
    expect(result.tools.map((tool) => tool.name)).toEqual(['Bash', 'send_local_image'])
    expect(result.tools[1]?.detail).toBe('drawing.png')
  })

  test('Given 同一图片被多次转发 When 提取 Then 去重只保留一个', () => {
    const result = extractTurnExtras(turn([
      { type: 'tool_use', id: 't1', name: 'send_local_image', input: { path: '.context/a.png' } },
      { type: 'tool_use', id: 't2', name: 'generate_image', input: { filePath: '.context/a.png' } },
    ]))
    expect(result.imagePaths).toEqual(['.context/a.png'])
  })

  test('Given 工具结果回报了规范化相对路径 When 提取 Then 优先用它，避免跨会话绝对路径被授权拒绝', () => {
    const result = extractTurnExtras(
      turn(
        [{ type: 'tool_use', id: 'img-1', name: 'send_local_image', input: { path: 'C:/profer/other-session/drawing-2.png' } }],
        [{ type: 'tool_result', tool_use_id: 'img-1', content: [{ type: 'text', text: '图片已附加到本轮回复。相对路径：.context/agent-output-images/521897d0.png。Profer 会自动展示图片。' }] }],
      ),
    )
    expect(result.imagePaths).toEqual(['.context/agent-output-images/521897d0.png'])
  })

  test('Given 工具结果文本是裸字符串缺相对路径 When 提取 Then 回退到原始 path', () => {
    const result = extractTurnExtras(
      turn(
        [{ type: 'tool_use', id: 'img-1', name: 'send_local_image', input: { path: 'drawing.png' } }],
        [{ type: 'tool_result', tool_use_id: 'img-1', content: '图片已附加到本轮回复。' }],
      ),
    )
    expect(result.imagePaths).toEqual(['drawing.png'])
  })

  test('Given MCP 前缀的工具名 When 提取 Then 按末段识别为图片工具', () => {
    const result = extractTurnExtras(turn([
      { type: 'tool_use', id: 't1', name: 'mcp__profer__send_local_image', input: { path: '.context/a.png' } },
    ]))
    expect(result.imagePaths).toEqual(['.context/a.png'])
  })

  test('Given 入参用的是 file_path / imagePath 等别名 When 提取 Then 仍能取到路径', () => {
    const result = extractTurnExtras(turn([
      { type: 'tool_use', id: 't1', name: 'send_local_image', input: { file_path: '.context/b.png' } },
      { type: 'tool_use', id: 't2', name: 'generate_image', input: { imagePath: '.context/c.png' } },
    ]))
    expect(result.imagePaths).toEqual(['.context/b.png', '.context/c.png'])
  })

  test('Given WS 投影把工具入参归一成 file_path When 提取 Then 仍能取到图片路径', () => {
    // projectSdkMessageForPocket 只保留 { file_path }，input.path 在线上并不存在。
    const result = extractTurnExtras(turn([
      { type: 'tool_use', id: 't1', name: 'Bash', input: {} },
      { type: 'tool_use', id: 't2', name: 'send_local_image', input: { file_path: '.context/agent-output-images/a.png' } },
    ]))
    expect(result.imagePaths).toEqual(['.context/agent-output-images/a.png'])
    // 入参被清空的工具不该渲染成 "{}"
    expect(result.tools[0]?.detail).toBe('')
  })

  test('Given WS 投影把图片挂在 tool_result.images When 提取 Then 用该引用路径', () => {
    const result = extractTurnExtras(
      turn(
        [{ type: 'tool_use', id: 'img-1', name: 'send_local_image', input: { file_path: 'C:/other-session/drawing.png' } }],
        [{
          type: 'tool_result',
          tool_use_id: 'img-1',
          images: [{ path: 'C:/profer/agent-workspaces/5/s1/.context/agent-output-images/b.png', filename: 'b.png', mediaType: 'image/png' }],
        }],
      ),
    )
    expect(result.imagePaths).toEqual(['C:/profer/agent-workspaces/5/s1/.context/agent-output-images/b.png'])
  })

  test('Given 原始形态的顶层 tool_use_result.image When 提取 Then 优先用 relativePath', () => {
    const group = {
      type: 'assistant-turn',
      turnMessages: [
        { message: { content: [{ type: 'tool_use', id: 'img-1', name: 'send_local_image', input: { path: 'C:/other/drawing.png' } }] } },
        {
          message: { content: [{ type: 'tool_result', tool_use_id: 'img-1' }] },
          tool_use_result: { image: { localPath: 'C:/profer/.../c.png', relativePath: '.context/agent-output-images/c.png' } },
        },
      ],
    } as unknown as MessageGroup
    expect(extractTurnExtras(group).imagePaths).toEqual(['.context/agent-output-images/c.png'])
  })

  test('Given 用户回合或空内容 When 提取 Then 返回空数组', () => {
    expect(extractTurnExtras({ type: 'user', message: {} } as unknown as MessageGroup))
      .toEqual({ tools: [], imagePaths: [], attachedFiles: [] })
    expect(extractTurnExtras(turn([{ type: 'text', text: '纯文本' }])))
      .toEqual({ tools: [], imagePaths: [], attachedFiles: [] })
  })

  test('Given 工具结果带 is_error When 提取 Then 标记该次调用失败，供界面着色', () => {
    const result = extractTurnExtras(turn([
      { type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'false' } },
      { type: 'tool_use', id: 't2', name: 'Read', input: { file_path: 'a.ts' } },
      { type: 'tool_result', tool_use_id: 't1', is_error: true },
      { type: 'tool_result', tool_use_id: 't2' },
    ]))
    expect(result.tools.map((tool) => tool.failed)).toEqual([true, undefined])
  })

  test('Given 没有任何错误 When 提取 Then 不额外挂 failed 字段（保持快照可比）', () => {
    const result = extractTurnExtras(turn([
      { type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'true' } },
      { type: 'tool_result', tool_use_id: 't1' },
    ]))
    expect(result.tools[0]).toEqual({ id: 't1', name: 'Bash', detail: 'true' })
  })
})

describe('用户消息的附件提取', () => {
  const userTurn = (text: string): MessageGroup => ({
    type: 'user',
    message: { type: 'user', message: { content: [{ type: 'text', text }] } },
  } as unknown as MessageGroup)

  test('Given 带附件块的用户消息 When 提取 Then 出 chip 数据，图片路径交给 TurnImages', () => {
    const raw = '<attached_files>\n- shot.png: /s/shot.png\n- note.pdf: /s/note.pdf\n</attached_files>\n\n看下附件'
    const extras = extractTurnExtras(userTurn(raw))
    expect(extras.attachedFiles).toEqual([
      { filename: 'shot.png', path: '/s/shot.png' },
      { filename: 'note.pdf', path: '/s/note.pdf' },
    ])
    expect(extras.imagePaths).toEqual(['/s/shot.png'])
  })

  test('Given 纯文本用户消息 When 提取 Then 没有附件也没有图片', () => {
    const extras = extractTurnExtras(userTurn('帮我看下这个'))
    expect(extras.attachedFiles).toEqual([])
    expect(extras.imagePaths).toEqual([])
  })
})
