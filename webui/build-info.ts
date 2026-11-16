/**
 * 构建戳（vite define 注入 `__WEBUI_BUILD__`）。
 *
 * 单独成文件是因为它现在被三处消费：页脚/会话信息、诊断浮层、以及 useDiagnostics 的
 * 数据快照。`typeof` 守卫让它在单测（没有 define）里也能安全导入。
 */
declare const __WEBUI_BUILD__: string

export const WEBUI_BUILD = typeof __WEBUI_BUILD__ === 'string' ? __WEBUI_BUILD__ : 'dev'
