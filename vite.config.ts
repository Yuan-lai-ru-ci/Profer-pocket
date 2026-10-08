import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'
import releaseConfig from './pocket-app/release.config.json' with { type: 'json' }

/**
 * 联调目标：电脑端 remote-service 端口。
 * 默认 7789 —— 主仓库 ProferAI dev（未打包）的 remote-service 默认端口，与安装版 7788 隔离。
 * 桌面开发版与安装版可同时运行，移动端网页默认连开发版；需要连安装版时用
 * `POCKET_REMOTE_PORT=7788 bun run dev` 覆盖。
 */
const remoteServicePort = process.env.POCKET_REMOTE_PORT?.trim() || '7789'

export default defineConfig({
  plugins: [react()],
  // __APP_VERSION__ 在 AboutSettings 模块顶层被引用（const APP_VERSION = __APP_VERSION__），
  // 不 define 会在设置面板模块求值时抛 ReferenceError。版本来源：Pocket release config。
  define: {
    __APP_VERSION__: JSON.stringify(releaseConfig.versionName),
  },
  // root 指向 pocket 目录：单入口 html 相对 root 为 index.html，
  // 构建时直接输出 dist/index.html（匹配 Capacitor sync-web 的 webDir 入口）；
  // dev 时 http://localhost:5175/ 直接就是 pocket 页面。
  root: resolve(__dirname, 'src/renderer/pocket'),
  base: './',
  build: {
    outDir: resolve(__dirname, 'dist'),
    emptyOutDir: true,
    rollupOptions: {
      // 纯移动端：仅 pocket 单入口（桌面 index.html 不构建）
      input: resolve(__dirname, 'src/renderer/pocket/index.html'),
    },
  },
  resolve: {
    alias: {
      '@/types': resolve(__dirname, 'src/types'),
      '@': resolve(__dirname, 'src/renderer'),
    },
  },
  server: {
    port: 5175,
    strictPort: true,
    open: false,
    // 开发联调：浏览器把 http://127.0.0.1 视为安全上下文，页面直连 ws:// 电脑端会被
    // 混合内容策略拦截（官方安卓版靠 Capacitor cleartext 放行，浏览器无此豁免）。
    // 加 /ws 代理：平板连同源 ws://127.0.0.1:5175/ws，vite 转发到电脑端 remote-service，
    // 绕开混合内容限制。联调时服务器地址留空（自动同源），token 正常填。
    proxy: {
      // 只代理 WebSocket 端点 /ws（含 /ws/xxx 子路径与 ?query），
      // 排除 /ws-client.ts 等以 /ws 开头的静态模块路径——否则 main.tsx 的
      // import './ws-client' 会被代理到电脑端返回 404，整个应用崩溃白屏。
      '^/ws(?:[/?]|$)': {
        target: `http://127.0.0.1:${remoteServicePort}`,
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
