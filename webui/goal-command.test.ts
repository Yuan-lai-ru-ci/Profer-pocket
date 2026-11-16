/**
 * `/goal` 解析的行为测试（镜像主仓语法）。
 *
 * 这些用例同时充当「主仓语法变了要同步这里」的提醒：任何一条对不上都说明两端口径分叉。
 */
import { describe, expect, test } from 'bun:test'
import { parseGoalCommand, parseGoalContractInput } from './goal-command'

describe('/goal 命令解析', () => {
  test('Given 普通文本 When 解析 Then 不是 Goal 命令', () => {
    expect(parseGoalCommand('帮我改一下登录页')).toEqual({ type: 'not_goal' })
    // 前缀相似但不是一个词
    expect(parseGoalCommand('/goalkeeper 你好')).toEqual({ type: 'not_goal' })
  })

  test('Given /goal 带目标 When 解析 Then 得到 start', () => {
    expect(parseGoalCommand('/goal 完成登录页')).toEqual({ type: 'start', goal: '完成登录页' })
  })

  test('Given 大写与多余空格 When 解析 Then 同样识别', () => {
    expect(parseGoalCommand('  /GOAL   完成登录页  ')).toEqual({ type: 'start', goal: '完成登录页' })
  })

  test('Given 目标带契约标记 When 解析 Then 标记进 contract、不留在正文', () => {
    const command = parseGoalCommand([
      '/goal 完成登录页',
      '@verify: pnpm test:e2e login 通过',
      '@constraint: 不修改支付相关代码',
      '@stop: 需要生产环境凭据时',
    ].join('\n'))
    expect(command).toEqual({
      type: 'start',
      goal: '完成登录页',
      contract: {
        verification: 'pnpm test:e2e login 通过',
        constraints: '不修改支付相关代码',
        stopWhen: '需要生产环境凭据时',
      },
    })
  })

  test('Given 子命令 When 解析 Then 返回对应类型', () => {
    for (const name of ['status', 'pause', 'resume', 'stop', 'clear'] as const) {
      expect(parseGoalCommand(`/goal ${name}`)).toEqual({ type: name })
      expect(parseGoalCommand(`/goal ${name.toUpperCase()}`)).toEqual({ type: name })
    }
  })

  test('Given 多行且首行是 status When 解析 Then 当成目标正文而不是子命令', () => {
    const command = parseGoalCommand('/goal status\n把接口返回改成 204')
    expect(command.type).toBe('start')
    expect(command.type === 'start' && command.goal).toContain('status')
  })

  test('Given 只有 /goal When 解析 Then 提示目标为空', () => {
    expect(parseGoalCommand('/goal')).toMatchObject({ type: 'invalid' })
    expect(parseGoalCommand('/goal   ')).toMatchObject({ type: 'invalid' })
  })

  test('Given 未知英文子命令 When 解析 Then 明确报错（不静默当目标）', () => {
    const command = parseGoalCommand('/goal restar')
    expect(command.type).toBe('invalid')
    expect(command.type === 'invalid' && command.reason).toContain('restar')
  })

  test('Given 只有契约标记没有目标 When 解析 Then 报错', () => {
    expect(parseGoalCommand('/goal @verify: 测试通过')).toMatchObject({ type: 'invalid' })
  })
})

describe('契约标记解析', () => {
  test('Given 同类标记出现两次 When 解析 Then 只取第一条', () => {
    const { contract } = parseGoalContractInput('做点事\n@verify: 第一条\n@verify: 第二条')
    expect(contract?.verification).toBe('第一条')
  })

  test('Given 标记超过 240 字 When 解析 Then 该行按正文保留（不静默丢弃）', () => {
    const long = `@verify: ${'很长的验收标准'.repeat(60)}`
    const { goal, contract } = parseGoalContractInput(`做点事\n${long}`)
    expect(contract?.verification).toBeUndefined()
    expect(goal).toContain('很长的验收标准')
  })

  test('Given 没有标记 When 解析 Then 不产生空 contract 对象', () => {
    expect(parseGoalContractInput('就一句话')).toEqual({ goal: '就一句话' })
  })
})
