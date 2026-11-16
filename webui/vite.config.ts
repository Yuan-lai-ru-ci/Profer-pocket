import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

/**
 * WebUI 独立构建配置：与 pocket 主入口（Capacitor WebView）完全隔离。
 *
 * base 固定为 /webui/：桌面端 remote-service 把 /s/<id> 映射到本页，
 * 而本页的静态资源统一走 /webui/assets/*，两者同源、无需反向代理。
 * dev 时用 http://127.0.0.1:5176/webui/?session=<id>&token=<token> 打开。
 */
/**
 * 构建时间戳：页面右下角展示，用来判断浏览器跑的是不是最新产物。
 * 静态资源虽然带 hash，但「看起来没变化」的排查成本极高（改完刷新还是旧行为），
 * 因此把构建时间固定进 bundle，一眼可辨。
 */
const BUILD_STAMP = (() => {
  const now = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
})()

export default defineConfig({
  plugins: [react()],
  root: __dirname,
  base: '/webui/',
  define: { __WEBUI_BUILD__: JSON.stringify(BUILD_STAMP) },
  build: {
    outDir: resolve(__dirname, 'dist'),
    emptyOutDir: true,
    rollupOptions: { input: resolve(__dirname, 'index.html') },
  },
  resolve: {
    alias: {
      // 直接指向源码，避免依赖 workspace 软链；ws-client 里的 @profer/shared 是 type-only 导入。
      '@profer/shared': resolve(__dirname, '../packages/shared/src/index.ts'),
      '@profer/session-core': resolve(__dirname, '../packages/session-core/src/index.ts'),
    },
  },
  server: {
    port: 5176,
    strictPort: true,
    proxy: {
      '^/ws(?:[/?]|$)': {
        target: `http://127.0.0.1:${process.env.POCKET_REMOTE_PORT?.trim() || '7789'}`,
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
