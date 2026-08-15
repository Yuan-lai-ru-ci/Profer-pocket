/**
 * 剪贴板写入工具（带降级方案）。
 *
 * 桌面 Electron 通过放开 clipboard 权限使 navigator.clipboard 可用；
 * 但 Capacitor WebView（平板）里该 API 可能不可用或静默失败。
 * 统一走此函数：优先异步 Clipboard API，失败降级为 document.execCommand('copy')
 * （临时 textarea + 选中 + 复制，WebView 兼容性最好）。execCommand 需由用户
 * 手势触发，故降级在点击的同步路径中执行。
 */

/**
 * 把文本写入系统剪贴板。
 * @returns 是否写入成功（失败时已打印错误日志）
 */
export async function writeClipboardText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Clipboard API 存在但写入被拒（权限 / WebView 限制），走 execCommand 降级
  }

  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.top = '0'
    textarea.style.left = '0'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.focus()
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch (error) {
    console.error('复制失败:', error)
    return false
  }
}
