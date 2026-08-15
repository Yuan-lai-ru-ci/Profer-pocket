import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'
import pkg from './package.json' with { type: 'json' }

export default defineConfig({
  plugins: [react()],
  // __APP_VERSION__ 在 AboutSettings 模块顶层被引用（const APP_VERSION = __APP_VERSION__），
  // 不 define 会在设置面板模块求值时抛 ReferenceError。版本来源：Profer-pocket 自身 package.json。
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
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
    // 开发联调：浏览器把 http://127.0.0.1 视为安全上下文，页面直连 ws:// 电脑端 7788
    // 会被混合内容策略拦截（官方安卓版靠 Capacitor cleartext 放行，浏览器无此豁免）。
    // 加 /ws 代理：平板连同源 ws://127.0.0.1:5175/ws，vite 转发到电脑端 remote-service，
    // 绕开混合内容限制。联调时服务器地址留空（自动同源），token 正常填。
    proxy: {
      '/ws': {
        target: 'http://127.0.0.1:7788',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
