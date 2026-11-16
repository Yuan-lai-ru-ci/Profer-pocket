import { describe, expect, test } from 'bun:test'
import type { SDKMessage } from '@profer/shared'
import { INITIAL_PAGE, decodeMessagePage, mergePrepend, mergeReloadPage, tailPageSize, upsertMessage } from './message-page'

const message = (uuid: string, text = 'hi'): SDKMessage =>
  ({ uuid, type: 'assistant', message: { role: 'assistant', content: text } } as unknown as SDKMessage)

describe('消息分页：返回形态解析', () => {
  test('Given 分页结构 When 解析 Then 取出正文、起点与是否还有更早', () => {
    const page = decodeMessagePage({
      messages: [message('a')],
      total: 120,
      startIndex: 60,
      endIndex: 119,
      hasMore: true,
    })
    expect(page.messages).toHaveLength(1)
    expect(page.startIndex).toBe(60)
    expect(page.hasMore).toBe(true)
  })

  test('Given 旧服务端返回裸数组 When 解析 Then 视为「全量已到头」，不显示加载更早', () => {
    const page = decodeMessagePage([message('a'), message('b')])
    expect(page.messages).toHaveLength(2)
    expect(page.startIndex).toBe(0)
    expect(page.hasMore).toBe(false)
  })

  test('Given 外层 data 包裹 When 解析 Then 同样能取到分页字段', () => {
    const page = decodeMessagePage({
      data: { messages: [message('a')], startIndex: 5, hasMore: true },
    })
    expect(page.messages).toHaveLength(1)
    expect(page.startIndex).toBe(5)
    expect(page.hasMore).toBe(true)
  })

  test('Given 脏数据或空值 When 解析 Then 返回空页而不是抛错', () => {
    for (const raw of [null, undefined, {}, 'oops', { messages: 'nope' }]) {
      expect(decodeMessagePage(raw)).toEqual({ messages: [], startIndex: 0, hasMore: false })
    }
  })

  test('Given 分页字段缺失 When 解析 Then 起点回退 0，且不谎称还有更早', () => {
    const page = decodeMessagePage({ messages: [message('a')] })
    expect(page.startIndex).toBe(0)
    expect(page.hasMore).toBe(false)
  })
})

describe('消息分页：向前拼接', () => {
  test('Given 相邻两页在 turn 边界重叠 When 拼接 Then 按 uuid 去重且更早的在前', () => {
    const older = [message('a'), message('b'), message('c')]
    const current = [message('c'), message('d')]
    const merged = mergePrepend(older, current)
    expect(merged.map((item) => (item as { uuid: string }).uuid)).toEqual(['a', 'b', 'c', 'd'])
  })

  test('Given 更早的页全部已存在 When 拼接 Then 结果不变，不会重复渲染', () => {
    const current = [message('a'), message('b')]
    expect(mergePrepend([message('a')], current)).toHaveLength(2)
  })

  test('Given 历史消息没有 uuid When 拼接 Then 全部保留，宁可重复也不丢历史', () => {
    const nameless = { type: 'assistant', message: { content: 'x' } } as unknown as SDKMessage
    const merged = mergePrepend([nameless], [message('a')])
    expect(merged).toHaveLength(2)
  })
})

describe('消息分页：尾部窗口大小', () => {
  test('Given 未加载过历史 When 取尾部 Then 至少一屏首屏量', () => {
    expect(tailPageSize(0)).toBe(INITIAL_PAGE)
  })

  test('Given 已加载过历史 When 重取尾部 Then 覆盖当前持有量并留余量，避免窗口往前滑', () => {
    expect(tailPageSize(200)).toBeGreaterThan(200)
  })
})

/** partial 帧：coordinator 明言「部分帧可能没有 UUID，终稿才有」，身份只在 _provenance.messageId。 */
const partialFrame = (text: string, opts: { messageId?: string; uuid?: string } = {}): SDKMessage =>
  ({
    type: 'assistant',
    ...(opts.uuid ? { uuid: opts.uuid } : {}),
    _partial: true,
    _provenance: { schemaVersion: 1, runId: 'run-1', messageId: opts.messageId ?? 'msg-1', author: { type: 'agent', id: 's1' } },
    message: { role: 'assistant', content: [{ type: 'text', text }] },
  } as unknown as SDKMessage)

/** 终稿：带 provider uuid，与 partial 共享同一个 messageId。 */
const finalFrame = (text: string, uuid = 'uuid-final', messageId = 'msg-1'): SDKMessage =>
  ({
    type: 'assistant',
    uuid,
    _provenance: { schemaVersion: 1, runId: 'run-1', messageId, author: { type: 'agent', id: 's1' } },
    message: { role: 'assistant', content: [{ type: 'text', text }] },
  } as unknown as SDKMessage)

describe('实时消息合并：按 _provenance 身份覆盖，partial 不成残条', () => {
  test('Given partial 帧无 uuid、final 带 uuid 且同 messageId When 合并 Then 覆盖成一条（残条根因）', () => {
    // 这正是「正文一输出完就消失、刷新又出现」的机制：只按 uuid 合并时，
    // 每条 partial（无 uuid）都被追加，text 残条之后挂 tool_use 残条挡住 headline。
    const list = upsertMessage([], partialFrame('你'))
    const list2 = upsertMessage(list, partialFrame('你好'))
    const list3 = upsertMessage(list2, finalFrame('你好', 'uuid-final'))
    expect(list3).toHaveLength(1)
    expect((list3[0] as { message: { content: Array<{ text: string }> } }).message.content[0].text).toBe('你好')
  })

  test('Given partial 帧各自带不同 uuid When 合并 Then 仍按 messageId 覆盖成一条', () => {
    const list = upsertMessage([], partialFrame('你', { uuid: 'u1', messageId: 'm' }))
    const list2 = upsertMessage(list, partialFrame('你好', { uuid: 'u2', messageId: 'm' }))
    expect(list2).toHaveLength(1)
  })

  test('Given 两条不同 messageId 的消息 When 合并 Then 各自成条不互相覆盖', () => {
    let list: SDKMessage[] = []
    list = upsertMessage(list, partialFrame('a', { messageId: 'm1' }))
    list = upsertMessage(list, partialFrame('b', { messageId: 'm2' }))
    list = upsertMessage(list, finalFrame('a终稿', 'uuid-a', 'm1'))
    expect(list).toHaveLength(2)
  })

  test('Given 无 _provenance 的旧消息 When 合并 Then 回退按 uuid 覆盖', () => {
    let list = upsertMessage([], message('u1', 'old'))
    list = upsertMessage(list, message('u1', 'new'))
    expect(list).toHaveLength(1)
  })

  test('Given result 消息（无 uuid 无 provenance）When 合并 Then 追加不去覆盖任何东西', () => {
    const result = { type: 'result', subtype: 'success' } as unknown as SDKMessage
    let list = upsertMessage([finalFrame('hi')], result)
    list = upsertMessage(list, result)
    expect(list).toHaveLength(3)
  })

  test('Given 已有 final、同身份 partial 还在飞（回前台重拉后的反序）When 合并 Then 保留 final 不让内容倒退', () => {
    const list = upsertMessage([finalFrame('完整正文')], partialFrame('完整正'))
    expect(list).toHaveLength(1)
    expect((list[0] as { message: { content: Array<{ text: string }> } }).message.content[0].text).toBe('完整正文')
  })

  test('Given 已有 partial、同身份 final 到达 When 合并 Then 终稿覆盖增量', () => {
    const list = upsertMessage([partialFrame('完整正')], finalFrame('完整正文'))
    expect(list).toHaveLength(1)
    expect((list[0] as { message: { content: Array<{ text: string }> } }).message.content[0].text).toBe('完整正文')
  })

  test('Given 同身份 final 重复到达（重连重放）When 合并 Then 保持一条', () => {
    let list = upsertMessage([], finalFrame('hi'))
    list = upsertMessage(list, finalFrame('hi'))
    expect(list).toHaveLength(1)
  })
})

describe('重拉页面合并：流式中的消息不被冲掉', () => {
  test('Given 在飞的 partial（不在 JSONL）When 合并页面 Then 原样保留在最新侧', () => {
    const live = [partialFrame('正在输出', { messageId: 'm-live' })]
    const page = [finalFrame('旧回复', 'u-old', 'm-old')]
    const merged = mergeReloadPage(live, page)
    expect(merged).toHaveLength(2)
    expect(merged[1]).toBe(live[0])
  })

  test('Given 页面带回同身份 final（漏帧后落盘）When 合并 Then final 覆盖 partial', () => {
    const live = [partialFrame('正在输出', { messageId: 'm' })]
    const page = [finalFrame('完整回复', 'u', 'm')]
    const merged = mergeReloadPage(live, page)
    expect(merged).toHaveLength(1)
    expect((merged[0] as { message: { content: Array<{ text: string }> } }).message.content[0].text).toBe('完整回复')
  })

  test('Given 页面有新消息 When 合并 Then 按顺序追加尾部且不重复', () => {
    const current = [finalFrame('a', 'ua', 'ma')]
    const page = [finalFrame('a', 'ua', 'ma'), finalFrame('b', 'ub', 'mb')]
    const merged = mergeReloadPage(current, page)
    expect(merged).toHaveLength(2)
    expect((merged[1] as { message: { content: Array<{ text: string }> } }).message.content[0].text).toBe('b')
  })

  test('Given 已有 final、页面里同身份 partial 还在飞 When 合并 Then 保留 final 不倒退', () => {
    const current = [finalFrame('完整回复', 'u', 'm')]
    const page = [partialFrame('完整回', { messageId: 'm' })]
    const merged = mergeReloadPage(current, page)
    expect(merged).toHaveLength(1)
    expect((merged[0] as { message: { content: Array<{ text: string }> } }).message.content[0].text).toBe('完整回复')
  })

  test('Given 前置加载过更早消息 When 合并页面 Then 更早消息保留在头部', () => {
    const older = { ...message('u-older', '更早'), _createdAt: 500 } as unknown as SDKMessage
    const current = [older, { ...finalFrame('a', 'ua', 'ma'), _createdAt: 2000 } as unknown as SDKMessage]
    const page = [
      { ...finalFrame('a', 'ua', 'ma'), _createdAt: 2000 } as unknown as SDKMessage,
      { ...finalFrame('b', 'ub', 'mb'), _createdAt: 3000 } as unknown as SDKMessage,
    ]
    const merged = mergeReloadPage(current, page)
    expect(merged).toHaveLength(3)
    expect(merged[0]).toBe(older)
  })
})

describe('mergeReloadPage 的顺序：更早消息不许被顶到尾部', () => {
  const stamped = (text: string, messageId: string, createdAt: number): SDKMessage =>
    ({ ...finalFrame(text, `u-${messageId}`, messageId), _createdAt: createdAt } as unknown as SDKMessage)

  test('Given 页面包含当前没持有的更早消息 When 合并 Then 按时间顺序排，最新消息留在底部', () => {
    // 这就是「模型回复完消息跳到上面去」的根因：更早消息被追加到尾部 → 乱序
    const current = [stamped('B', 'mb', 2000), stamped('C', 'mc', 3000)]
    const page = [stamped('A', 'ma', 1000), stamped('B', 'mb', 2000), stamped('C', 'mc', 3000), stamped('D', 'md', 4000)]
    const merged = mergeReloadPage(current, page)
    expect(merged.map((m) => (m as { message: { content: Array<{ text: string }> } }).message.content[0].text)).toEqual(['A', 'B', 'C', 'D'])
  })

  test('Given 在飞 partial 无 createdAt When 合并 Then 留在尾部（最新侧），不乱序', () => {
    const current = [stamped('B', 'mb', 2000), partialFrame('正在输出', { messageId: 'm-live' })]
    const page = [stamped('A', 'ma', 1000), stamped('B', 'mb', 2000)]
    const merged = mergeReloadPage(current, page)
    expect(merged.map((m) => (m as { message: { content: Array<{ text: string }> } }).message.content[0].text)).toEqual(['A', 'B', '正在输出'])
  })

  test('Given 前置加载的更早消息 When 合并 Then 保持在头部', () => {
    const current = [stamped('Old', 'mo', 500), stamped('B', 'mb', 2000)]
    const page = [stamped('B', 'mb', 2000), stamped('C', 'mc', 3000)]
    const merged = mergeReloadPage(current, page)
    expect(merged.map((m) => (m as { message: { content: Array<{ text: string }> } }).message.content[0].text)).toEqual(['Old', 'B', 'C'])
  })
})

describe('compacting 瞬态消息不残留', () => {
  const compacting = (): SDKMessage =>
    ({ type: 'system', subtype: 'compacting', uuid: 'sys-1', message: '正在压缩上下文...' } as unknown as SDKMessage)
  const boundary = (): SDKMessage =>
    ({ type: 'system', subtype: 'compact_boundary', uuid: 'sys-2' } as unknown as SDKMessage)

  test('Given 页面里没有 compacting（不落盘）When 合并 Then 瞬态被丢弃不残留', () => {
    // 实测：两次压缩各留一条「正在压缩上下文...」——合并只加不减的代价
    const current = [finalFrame('a', 'ua', 'ma'), compacting()]
    const page = [finalFrame('a', 'ua', 'ma')]
    const merged = mergeReloadPage(current, page)
    expect(merged).toHaveLength(1)
  })

  test('Given compact_boundary（落盘的历史分隔）When 合并 Then 保留', () => {
    const current = [boundary(), finalFrame('a', 'ua', 'ma')]
    const page = [finalFrame('a', 'ua', 'ma')]
    const merged = mergeReloadPage(current, page)
    expect(merged).toHaveLength(2)
    expect(merged.some((m) => (m as { subtype?: string }).subtype === 'compact_boundary')).toBe(true)
  })
})
