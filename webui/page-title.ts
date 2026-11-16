/**
 * 标签页标题 = 会话名。
 *
 * 一个页面只承载一个会话，所以浏览器标签、历史记录、加书签后的名字都该是会话名，
 * 而不是统一的「Profer 会话」—— 同时开两个会话页时，光看标签分不出哪个是哪个。
 */

export const DEFAULT_PAGE_TITLE = 'Profer 会话'

/** 会话名可能为空（新会话还没自动生成标题），此时退回固定文案而不是拿 sessionId 当名字。 */
export function resolvePageTitle(title: unknown): string {
  if (typeof title !== 'string') return DEFAULT_PAGE_TITLE
  const value = title.trim()
  return value || DEFAULT_PAGE_TITLE
}

/**
 * 写入标题。
 *
 * `document.title` 管标签页；两个 meta 分别管 iOS「添加到主屏幕」与部分浏览器的
 * 应用名（Android 上若装了 PWA，任务切换器读的是它们而不是 document.title）。
 */
export function applyPageTitle(title: unknown): string {
  const value = resolvePageTitle(title)
  if (typeof document === 'undefined') return value
  document.title = value
  for (const selector of [
    'meta[name="apple-mobile-web-app-title"]',
    'meta[name="application-name"]',
  ]) {
    const node = document.querySelector(selector)
    if (node) node.setAttribute('content', value)
  }
  return value
}
