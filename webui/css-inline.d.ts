/**
 * Vite 的 `?inline` 导入：CSS 作为字符串内联进 JS。
 * 自包含预览页拿不到打包后的样式表文件，只能把需要的 CSS 文本一起带过去。
 */
declare module '*.css?inline' {
  const css: string
  export default css
}
