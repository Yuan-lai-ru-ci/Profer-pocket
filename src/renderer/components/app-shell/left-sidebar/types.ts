/**
 * types.ts — 侧边栏共享类型
 *
 * 从 LeftSidebar.tsx 抽离的公共接口，供主文件与 use-left-sidebar hook 共用。
 */

export interface LeftSidebarProps {
  /** 可选固定宽度，默认使用 CSS 响应式宽度 */
  width?: number
  /** 拖拽过程中禁用 CSS transition，保证即时响应 */
  noTransition?: boolean
  /** 平板等受限环境：隐藏部分桌面能力入口。 */
  pocketMode?: boolean
  /** 移动端嵌入轨道时移除自身圆角和阴影，避免与外层 viewport 叠出边界线。 */
  flush?: boolean
  /** 移动抽屉专用：收起时关闭抽屉；未提供则使用桌面 collapsed rail。 */
  onCollapse?: () => void
  /** 是否渲染全局搜索对话框（SearchDialog）。移动版存在横屏固定侧栏 + 竖屏抽屉两个
   *  LeftSidebar 实例，SearchDialog 绑定全局 atom 且 Portal 到 body，必须只渲染一份，
   *  否则双实例同时打开会叠出双遮罩、互相触发 interactOutside 导致搜索框“一闪即逝”。 */
  renderSearchDialog?: boolean
}
