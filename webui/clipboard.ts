/**
 * 复制一段文本到剪贴板。
 *
 * 为什么需要降级：WebUI 跑在 `http://192.168.x.x` 这类**非安全上下文**里，
 * `navigator.clipboard` 在部分浏览器直接不存在或拒绝。`execCommand('copy')` 必须在
 * 用户手势的同步栈里执行，所以先同步试它，失败再走异步剪贴板 API。
 *
 * 注意：诊断浮层里的「复制」不用本函数 —— 它复制的是**当前可见的面板内容**，
 * 走选区 + execCommand 才是对的；这里只处理「已知字符串」这一种。
 */
export async function copyText(text: string): Promise<boolean> {
  // 1) 同步降级路径：临时 textarea + 选区
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    // 放到视口外，避免聚焦时页面跳动（iOS 会滚动到焦点元素）
    area.style.position = 'fixed'
    area.style.top = '-1000px'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    area.setSelectionRange(0, area.value.length)
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    if (ok) return true
  } catch {
    /* 继续尝试异步 API */
  }

  // 2) 安全上下文下的正规 API
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 用户拒绝或无权限 */
  }
  return false
}
