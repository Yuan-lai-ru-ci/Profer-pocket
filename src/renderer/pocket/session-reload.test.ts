import { describe, expect, test } from 'bun:test'
import {
  bumpReloadNonce,
  buildReloadKey,
  classifyPocketReloadFailure,
  collectPocketReloadInvalidations,
  collectPocketReloadSteps,
  consumePocketReloadNonce,
  describePocketReloadFailure,
  getReloadNonce,
  normalizeReloadNonce,
  POCKET_RELOAD_TOAST,
} from './session-reload'

describe('normalizeReloadNonce / getReloadNonce', () => {
  test('非有限数、负数、undefined 归一化为 0', () => {
    expect(normalizeReloadNonce(undefined)).toBe(0)
    expect(normalizeReloadNonce(null)).toBe(0)
    expect(normalizeReloadNonce(Number.NaN)).toBe(0)
    expect(normalizeReloadNonce(Number.POSITIVE_INFINITY)).toBe(0)
    expect(normalizeReloadNonce(-3)).toBe(0)
    expect(normalizeReloadNonce(0)).toBe(0)
    expect(normalizeReloadNonce(2.7)).toBe(2)
  })

  test('缺失会话返回 0（桌面端不写该 atom ⇒ 恒为 0，行为与旧实现一致）', () => {
    expect(getReloadNonce(new Map(), 'session-1')).toBe(0)
    expect(getReloadNonce(undefined, 'session-1')).toBe(0)
    expect(getReloadNonce(new Map([['session-1', 4]]), 'session-1')).toBe(4)
  })
})

describe('bumpReloadNonce', () => {
  test('按会话独立递增，且不修改入参 Map（jotai atom 写入语义）', () => {
    const before = new Map<string, number>([['session-1', 1]])
    const next = bumpReloadNonce(before, 'session-1')

    expect(before.get('session-1')).toBe(1)
    expect(next.get('session-1')).toBe(2)
    expect(next).not.toBe(before)
  })

  test('不同会话互不影响（跨会话切换不会污染游标）', () => {
    const first = bumpReloadNonce(new Map(), 'session-1')
    const second = bumpReloadNonce(first, 'session-2')

    expect(getReloadNonce(second, 'session-1')).toBe(1)
    expect(getReloadNonce(second, 'session-2')).toBe(1)
  })

  test('可接受 undefined（atom 初始值被替换的边界）', () => {
    expect(getReloadNonce(bumpReloadNonce(undefined, 's'), 's')).toBe(1)
  })
})

describe('buildReloadKey', () => {
  test('nonce 变化即换 key（触发消息子树重挂载）', () => {
    expect(buildReloadKey('agent', 'session-1', 0)).toBe('agent:session-1#0')
    expect(buildReloadKey('agent', 'session-1', 1)).toBe('agent:session-1#1')
    expect(buildReloadKey('agent', 'session-1', 0)).not.toBe(buildReloadKey('agent', 'session-1', 1))
  })

  test('chat 与 agent 即使 id 相同也不共键', () => {
    expect(buildReloadKey('chat', 'x', 1)).not.toBe(buildReloadKey('agent', 'x', 1))
  })
})

describe('consumePocketReloadNonce', () => {
  test('游标之后的新 nonce ⇒ 走全量水合并推进游标', () => {
    expect(consumePocketReloadNonce(1, 0)).toEqual({ shouldFullHydrate: true, consumedNonce: 1 })
    expect(consumePocketReloadNonce(3, 1)).toEqual({ shouldFullHydrate: true, consumedNonce: 3 })
  })

  test('已消费的 nonce 不再重复触发（流结束/错误等尾部刷新仍走首帧分页）', () => {
    expect(consumePocketReloadNonce(1, 1)).toEqual({ shouldFullHydrate: false, consumedNonce: 1 })
    expect(consumePocketReloadNonce(0, 0)).toEqual({ shouldFullHydrate: false, consumedNonce: 0 })
  })

  test('游标回退/脏值不触发全量（防御 atom 重置或旧结构）', () => {
    expect(consumePocketReloadNonce(0, 2).shouldFullHydrate).toBe(false)
    expect(consumePocketReloadNonce(Number.NaN, 1).shouldFullHydrate).toBe(false)
  })
})

describe('强制刷新应重置的状态清单', () => {
  test('agent：传输层分页缓存 + 滚动记忆 + 消息子树重挂载', () => {
    expect(collectPocketReloadInvalidations('agent')).toEqual([
      'sdk-messages-page-cache',
      'scroll-position',
      'message-subtree',
    ])
  })

  test('chat：只清滚动记忆（不重挂载 ChatView，避免丢待发送附件与草稿）', () => {
    expect(collectPocketReloadInvalidations('chat')).toEqual(['scroll-position'])
  })

  test('步骤顺序固定：先失效 → 再同步权威态 → 最后触发全量水合', () => {
    expect(collectPocketReloadSteps()).toEqual([
      'invalidate-local-view-state',
      'resync-authoritative-state',
      'trigger-full-rehydrate',
    ])
  })

  test('成功 toast 文案体现「已重新加载」而不是「已刷新」', () => {
    expect(POCKET_RELOAD_TOAST.agent).toBe('已重新加载当前会话')
    expect(POCKET_RELOAD_TOAST.chat).toBe('已重新加载当前对话')
  })
})

describe('弱网失败归因', () => {
  test('连接不可用优先判定为 disconnected（即使 error 为 undefined）', () => {
    expect(classifyPocketReloadFailure(undefined, { connected: false })).toBe('disconnected')
    expect(classifyPocketReloadFailure(new Error('指令超时'), { connected: false })).toBe('disconnected')
  })

  test('WsClient 的 15s 超时文案归为 timeout', () => {
    expect(classifyPocketReloadFailure(new Error('指令超时'))).toBe('timeout')
    expect(classifyPocketReloadFailure(new Error('Request timeout'))).toBe('timeout')
  })

  test('未连接 / 断链 / 非 Error 抛出等归因', () => {
    expect(classifyPocketReloadFailure(new Error('连接未就绪，请稍候重试'))).toBe('disconnected')
    expect(classifyPocketReloadFailure(new Error('连接已断开'))).toBe('disconnected')
    expect(classifyPocketReloadFailure('WebSocket is closed')).toBe('disconnected')
    expect(classifyPocketReloadFailure({ nope: true })).toBe('unknown')
    expect(classifyPocketReloadFailure(new Error('会话不存在'))).toBe('unknown')
  })

  test('失败文案承诺「界面保持原样」且带可重试提示', () => {
    for (const kind of ['disconnected', 'timeout', 'unknown'] as const) {
      const text = describePocketReloadFailure(kind)
      expect(text).toContain('保持原样')
      expect(text).toContain('重试')
    }
    expect(describePocketReloadFailure('unknown', new Error('会话不存在'))).toContain('会话不存在')
  })
})
