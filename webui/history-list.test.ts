/**
 * 「历史对话」列表的行为测试。
 *
 * 这条派生的失败模式都很安静：下标错位会把 A 回合的时间挂到 B 回合上，
 * 空回合不跳过会点进去一片空白，不截断会把面板撑成正文。
 */
import { describe, expect, test } from 'bun:test'
import { buildHistoryEntries, filterHistoryEntries, toHistoryPreview, HISTORY_PREVIEW_MAX } from './history-list'

describe('历史对话列表', () => {
  test('Given 用户与助手交替 When 派生 Then 每个有内容的回合一条，时间正序（最新在最下）', () => {
    const entries = buildHistoryEntries({
      turns: [
        { index: 0, role: 'user', text: '第一条' },
        { index: 1, role: 'assistant', text: '' },
        { index: 2, role: 'user', text: '第二条' },
      ],
      headlines: [undefined, '回答一', undefined],
      summaries: [undefined, '执行过程：3 次工具调用', undefined],
      clocks: ['10:00', '10:01', '10:05'],
    })
    expect(entries.map((entry) => entry.turnIndex)).toEqual([0, 1, 2])
    expect(entries[1]).toEqual({ turnIndex: 1, role: 'assistant', clock: '10:01', preview: '回答一', text: '回答一' })
  })

  test('Given 下标不连续（system 分组被过滤） When 派生 Then 按 turn.index 取时间而不是数组位置', () => {
    const entries = buildHistoryEntries({
      turns: [
        { index: 0, role: 'user', text: '你好' },
        { index: 3, role: 'user', text: '继续' },
      ],
      headlines: [],
      summaries: [],
      clocks: ['08:00', '不该被用到', '也不该', '09:30'],
    })
    expect(entries[0]).toMatchObject({ turnIndex: 0, clock: '08:00' })
    expect(entries[1]).toMatchObject({ turnIndex: 3, clock: '09:30' })
  })

  test('Given 助手只有工具调用没有正文 When 派生 Then 用执行过程摘要兜底', () => {
    const entries = buildHistoryEntries({
      turns: [{ index: 0, role: 'assistant', text: '' }],
      headlines: [''],
      summaries: ['执行过程：54 次工具调用'],
      clocks: ['11:11'],
    })
    expect(entries[0]?.preview).toBe('执行过程：54 次工具调用')
  })

  test('Given 回合内容为空 When 派生 Then 不进列表（点进去是空白）', () => {
    const entries = buildHistoryEntries({
      turns: [
        { index: 0, role: 'user', text: '   ' },
        { index: 1, role: 'assistant', text: '' },
      ],
      headlines: [''],
      summaries: [undefined],
      clocks: ['', ''],
    })
    expect(entries).toEqual([])
  })

  test('Given 用户消息带上下文注入块 When 派生 Then 预览里不出现标记与路径', () => {
    const entries = buildHistoryEntries({
      turns: [{
        index: 0,
        role: 'user',
        text: '<attached_files>\n- a.png: C:\\tmp\\a.png\n</attached_files>\n看看这张图',
      }],
      headlines: [],
      summaries: [],
      clocks: ['12:00'],
    })
    expect(entries[0]?.preview).toBe('看看这张图')
  })

  test('Given 压缩分隔条（system 回合） When 派生 Then 不进列表（它不是谁说的话）', () => {
    const entries = buildHistoryEntries({
      turns: [
        { index: 0, role: 'user', text: '你好' },
        { index: 1, role: 'system', text: '上下文已压缩' },
        { index: 2, role: 'assistant', text: '' },
      ],
      headlines: [undefined, undefined, '回答'],
      summaries: [],
      clocks: ['10:00', '10:01', '10:02'],
    })
    expect(entries.map((entry) => entry.turnIndex)).toEqual([0, 2])
  })

  test('Given 超长正文 When 派生 Then 压成一行并截断', () => {
    const long = `第一行\n第二行 ${'内容'.repeat(100)}`
    const preview = toHistoryPreview(long)
    expect(preview.includes('\n')).toBe(false)
    expect(preview.length).toBe(HISTORY_PREVIEW_MAX + 1)
    expect(preview.endsWith('…')).toBe(true)
  })
})

describe('历史对话关键词筛选', () => {
  const entries = [
    { turnIndex: 0, role: 'user' as const, clock: '10:00', preview: '帮我查一下部署脚本', text: '帮我查一下部署脚本' },
    { turnIndex: 1, role: 'assistant' as const, clock: '10:01', preview: '部署脚本在 scripts/ 下', text: '部署脚本在 scripts/ 目录，注意先用 dry-run 验证' },
    { turnIndex: 2, role: 'user' as const, clock: '10:05', preview: '数据库迁移', text: '数据库迁移怎么回滚' },
  ]

  test('Given 空关键词 When 筛选 Then 全部保留', () => {
    expect(filterHistoryEntries(entries, '')).toHaveLength(3)
    expect(filterHistoryEntries(entries, '   ')).toHaveLength(3)
  })

  test('Given 单个关键词 When 筛选 Then 按子串命中且大小写不敏感', () => {
    expect(filterHistoryEntries(entries, '部署').map((entry) => entry.turnIndex)).toEqual([0, 1])
    expect(filterHistoryEntries(entries, 'DRY-RUN').map((entry) => entry.turnIndex)).toEqual([1])
  })

  test('Given 多个关键词 When 筛选 Then 每个词都要命中（AND）', () => {
    expect(filterHistoryEntries(entries, '部署 dry-run').map((entry) => entry.turnIndex)).toEqual([1])
    expect(filterHistoryEntries(entries, '部署 数据库')).toEqual([])
  })

  test('Given 关键词只出现在预览之外 When 筛选 Then 仍能命中（按全文匹配）', () => {
    // 预览只有前 68 字，关键词在正文后半段
    const long = buildHistoryEntries({
      turns: [{ index: 0, role: 'assistant', text: '' }],
      headlines: [`${'铺垫'.repeat(60)}关键结论在这里`],
      summaries: [],
      clocks: ['12:00'],
    })
    expect(long[0]?.preview.includes('关键结论')).toBe(false)
    expect(filterHistoryEntries(long, '关键结论')).toHaveLength(1)
  })

  test('Given 没有命中 When 筛选 Then 返回空数组（UI 显示无结果）', () => {
    expect(filterHistoryEntries(entries, '不存在的东西')).toEqual([])
  })
})
