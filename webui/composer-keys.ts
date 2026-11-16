/**
 * 输入框的「连按两次回车发送」判定。
 *
 * 背景：触屏上没有 Shift，Enter 只能拿来换行，发送必须移手去点右侧圆键。
 * 手机上一只手打字时那颗按钮很远，所以给「Enter 连按两次」一个快捷发送。
 *
 * 为什么不只看「内容以换行结尾」：写完一段想空一行继续写（"a\n\nb"）时，
 * 第二个 Enter 之前的内容正好以换行结尾，会被误判成发送。所以还要叠一个时间窗：
 * 两次 Enter 的间隔在窗口内才算「连按」，慢一点按仍然是插入空行。
 *
 * 窗口取 800ms：比双击判定（~500ms）宽一点，因为拇指按键盘比鼠标慢；
 * 又不至于让「想一想再换行」被吞掉。
 */
export const DOUBLE_ENTER_WINDOW_MS = 800

export interface DoubleEnterInput {
  /** 本次 Enter 的时间戳 */
  now: number
  /** 上一次 Enter 的时间戳；null 表示没有 */
  lastEnterAt: number | null
  /** 当前输入框内容（本次 Enter 尚未插入换行） */
  value: string
  windowMs?: number
}

export function isDoubleEnterToSend(input: DoubleEnterInput): boolean {
  if (input.lastEnterAt === null) return false
  // 光标不在末尾时按下 Enter 是在中间插行，不该发送；末尾插入才会让内容以换行结尾
  if (!input.value.endsWith('\n')) return false
  return input.now - input.lastEnterAt <= (input.windowMs ?? DOUBLE_ENTER_WINDOW_MS)
}
