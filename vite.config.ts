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
  root: resolve(__dirname, 'src/renderer'),
  base: './',
  build: {
    outDir: resolve(__dirname, 'dist'),
    emptyOutDir: true,
    rollupOptions: {
      // 纯移动端：仅 tablet 单入口（桌面 index.html 不构建）
      input: {
        tablet: resolve(__dirname, 'src/renderer/tablet/index.html'),
      },
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
  },
})
