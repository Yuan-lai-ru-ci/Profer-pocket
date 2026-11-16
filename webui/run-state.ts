/**
 * 运行态错误的分类。
 *
 * 背景：客户端只能从事件流推断「这一轮还在不在跑」，刷新/重连/断帧之后必然落后。
 * 落后一次就会对正在跑的会话发 `send_message`，被 run claim 拒绝 —— 用户看到一条红字，
 * 但其实这是可恢复的状态，不是失败。分类放在这里，是为了让发送路径能分支：
 *   - isRunActiveError → 运行态置真，改走排队；
 *   - isQueueUnsupportedError → 当前内核（Pi）没有追加能力，给「停止本轮并发送」的确认。
 *
 * 同时接受机器可读 `code` 与旧版文案：服务端补齐 code 之前，老进程只会给文案。
 */
export function errorCodeOf(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null) return undefined
  const code = (error as { code?: unknown }).code
  return typeof code === 'string' ? code : undefined
}

/** 「这一轮还在跑」——run claim 被拒。 */
export function isRunActiveError(error: unknown): boolean {
  if (errorCodeOf(error) === 'AGENT_RUN_ALREADY_ACTIVE') return true
  const message = error instanceof Error ? error.message : String(error)
  return message.includes('AGENT_RUN_ALREADY_ACTIVE')
}

/** 当前内核没有流式追加能力（Pi）—— 只能停止本轮再发。 */
export function isQueueUnsupportedError(error: unknown): boolean {
  if (errorCodeOf(error) === 'QUEUE_UNSUPPORTED') return true
  const message = error instanceof Error ? error.message : String(error)
  return message.includes('不支持追加消息') || message.includes('不支持流式追加消息')
}
