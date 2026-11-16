/**
 * 连按两次回车发送的判定。
 *
 * 判错的两种代价都不小：该发没发（快捷手势失效，用户以为键盘坏了）
 * 或不该发却发（想空一行继续写，结果半句话就发出去了）。
 */
import { describe, expect, test } from 'bun:test'
import { isDoubleEnterToSend } from './composer-keys'

describe('连按两次回车发送', () => {
  test('Given 上一次回车在窗口内且内容以换行结尾 When 再按回车 Then 发送', () => {
    expect(isDoubleEnterToSend({ now: 1000, lastEnterAt: 400, value: '你好\n' })).toBe(true)
  })

  test('Given 没有上一次回车 When 按回车 Then 只换行', () => {
    expect(isDoubleEnterToSend({ now: 1000, lastEnterAt: null, value: '你好\n' })).toBe(false)
  })

  test('Given 间隔超过窗口 When 按回车 Then 仍按空行处理（想做段落分隔）', () => {
    expect(isDoubleEnterToSend({ now: 2000, lastEnterAt: 400, value: '你好\n' })).toBe(false)
  })

  test('Given 中间插入过其它文字 When 按回车 Then 不发送', () => {
    expect(isDoubleEnterToSend({ now: 700, lastEnterAt: 400, value: '你好\n继续写' })).toBe(false)
  })

  test('Given 光标在中间（内容不以换行结尾） When 按回车 Then 不发送', () => {
    expect(isDoubleEnterToSend({ now: 700, lastEnterAt: 400, value: '第一行\n第二行' })).toBe(false)
  })

  test('Given 自定义窗口 When 判断 Then 以传入窗口为准', () => {
    expect(isDoubleEnterToSend({ now: 1000, lastEnterAt: 400, value: 'x\n', windowMs: 500 })).toBe(false)
    expect(isDoubleEnterToSend({ now: 900, lastEnterAt: 400, value: 'x\n', windowMs: 500 })).toBe(true)
  })
})
