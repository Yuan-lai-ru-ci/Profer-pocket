import { describe, expect, test } from 'bun:test'
import {
  decodeInteractions,
  normalizeAskUserItems,
  normalizeExitPlanItems,
  normalizeGoalProposals,
  normalizePermissionItems,
  normalizePermissionMode,
} from './interactions'

describe('WebUI 交互数据归一化', () => {
  test('Given 权限请求 When 归一化 Then 保留展示字段并丢弃无 requestId 的脏项', () => {
    const result = normalizePermissionItems([
      {
        requestId: 'p1',
        toolName: 'Bash',
        command: 'ls -la',
        sdkDisplayName: 'bash-tool',
        sdkTitle: 'List files',
        sdkDescription: 'Read the directory',
        dangerLevel: 'dangerous',
        toolInput: { command: 'ls -la' },
      },
      { toolName: 'Bash' },
      null,
    ])
    expect(result).toHaveLength(1)
    expect(result[0]?.command).toBe('ls -la')
    expect(result[0]?.sdkDisplayName).toBe('bash-tool')
    expect(result[0]?.dangerLevel).toBe('dangerous')
  })

  test('Given AskUser 问题 When 归一化 Then 保留选项、说明与多选标记', () => {
    const result = normalizeAskUserItems([{
      requestId: 'a1',
      questions: [{
        question: '选哪个？',
        header: '方案',
        multiSelect: true,
        options: [{ label: 'A', description: '第一种' }, { label: 'B' }, null],
      }],
    }])
    expect(result).toHaveLength(1)
    expect(result[0]?.questions[0]?.multiSelect).toBe(true)
    expect(result[0]?.questions[0]?.options.map((option) => option.label)).toEqual(['A', 'B'])
    expect(result[0]?.questions[0]?.options[0]?.description).toBe('第一种')
  })

  test('Given ExitPlanMode 的 toolInput.plan When 归一化 Then 取出计划正文', () => {
    const result = normalizeExitPlanItems([{
      requestId: 'e1',
      toolInput: { plan: '先做 A，再做 B' },
      allowedPrompts: [{ tool: 'Bash', prompt: 'run tests' }],
    }])
    expect(result[0]?.plan).toBe('先做 A，再做 B')
    expect(result[0]?.allowedPrompts?.[0]?.tool).toBe('Bash')
  })

  test('Given 快照四组都有值 When 解码 Then 分离到对应数组', () => {
    const result = decodeInteractions({
      permissions: [{ requestId: 'p1', toolName: 'Bash' }],
      askUsers: [{ requestId: 'a1', questions: [{ question: 'q', options: [] }] }],
      exitPlans: [{ requestId: 'e1', toolInput: {} }],
      goalProposals: [{ id: 'g1', objective: '补齐测试', rationale: '多轮', createdAt: 1 }],
    })
    expect(result.permissions).toHaveLength(1)
    expect(result.askUsers).toHaveLength(1)
    expect(result.exitPlans).toHaveLength(1)
    expect(result.goalProposals).toHaveLength(1)
  })

  test('Given Goal 提议缺 objective 或 id When 归一化 Then 丢弃，不让空卡片出现', () => {
    const result = normalizeGoalProposals([
      { id: 'g1', objective: '把构建修好', verification: 'bun run build 通过', createdAt: 5 },
      { id: 'g2', objective: '   ' },
      { objective: '没有 id' },
      null,
    ])
    expect(result).toHaveLength(1)
    expect(result[0]?.objective).toBe('把构建修好')
    expect(result[0]?.verification).toBe('bun run build 通过')
    // 契约字段可缺省：模型只给目标也算有效提议
    expect(result[0]?.constraints).toBeUndefined()
  })

  test('Given 会话为「完全自动」 When 归一化权限模式 Then 原样透传 bypassPermissions', () => {
    expect(normalizePermissionMode('bypassPermissions')).toBe('bypassPermissions')
    expect(normalizePermissionMode('auto')).toBe('auto')
    expect(normalizePermissionMode('plan')).toBe('plan')
  })

  test('Given 权限模式缺失或非法 When 归一化 Then 回退完全自动，绝不降级成 auto', () => {
    expect(normalizePermissionMode(undefined)).toBe('bypassPermissions')
    expect(normalizePermissionMode(null)).toBe('bypassPermissions')
    expect(normalizePermissionMode('unknown')).toBe('bypassPermissions')
  })

  test('Given 空快照或脏数据 When 解码 Then 全部为空数组，不抛错', () => {
    expect(decodeInteractions(null)).toEqual({ permissions: [], askUsers: [], exitPlans: [], goalProposals: [] })
    expect(normalizePermissionItems(undefined)).toEqual([])
  })
})
