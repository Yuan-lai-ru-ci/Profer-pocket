/**
 * 本轮完成提醒。
 *
 * 为什么不能只靠 Notification API：WebUI 跑在 `http://<局域网 IP>` 这类**非安全上下文**，
 * 按规范 `Notification` 在那里不可用（iOS Safari 也只在「添加到主屏幕」后的 PWA 里支持）。
 * 所以做成两层：
 *   1. 有权限 → 发系统通知（应用在后台时用户才看得到）；
 *   2. 没权限 → 标题前缀「●」+ 回到页面时自动还原 —— 至少切回浏览器时能看出"刚刚跑完了"。
 *
 * 触发口径：只在 `active` 由 true 变 false 且页面**不可见**时提醒。
 * 正在看页面时再弹一条通知纯属噪音。
 */
import * as React from 'react'

/** 在用户手势里调用：Safari 要求 requestPermission 有手势上下文。 */
export function requestNotifyPermission(): void {
  try {
    if (typeof Notification === 'undefined') return
    if (Notification.permission !== 'default') return
    void Notification.requestPermission()
  } catch {
    /* 非安全上下文下这里可能直接抛错，忽略即可 */
  }
}

export interface CompletionNotifyInput {
  /** 会话是否在跑（由 sessionActive 传入） */
  active: boolean
  /** 通知标题：会话名 */
  title: string
  /** 通知正文：本轮最终正文的一行摘要 */
  preview: string
}

export function useCompletionNotify(input: CompletionNotifyInput): void {
  const { active, title, preview } = input
  const previousRef = React.useRef(active)
  /** 标题前缀的还原函数：可见时调用一次 */
  const restoreTitleRef = React.useRef<(() => void) | null>(null)

  // 页面回到前台就还原标题（用户已经看到消息了，标记没意义）
  React.useEffect(() => {
    const onVisible = (): void => {
      if (document.visibilityState !== 'visible') return
      restoreTitleRef.current?.()
      restoreTitleRef.current = null
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])

  React.useEffect(() => {
    const wasActive = previousRef.current
    previousRef.current = active
    // 只关心「跑完了」这一刻，且只关心用户不在看的时候
    if (!wasActive || active) return
    if (document.visibilityState === 'visible') return

    const body = preview.trim() || '本轮已完成'
    let notified = false
    try {
      if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
        new Notification(title || 'Profer', { body, tag: 'profer-webui-run' })
        notified = true
      }
    } catch {
      notified = false
    }
    if (notified) return

    // 降级：标题打点。同一个还原函数只挂一次，避免连跑多轮叠出「●●」
    if (!restoreTitleRef.current) {
      const original = document.title
      document.title = `● ${title || 'Profer'}`
      restoreTitleRef.current = () => { document.title = original }
    }
  }, [active, title, preview])
}
