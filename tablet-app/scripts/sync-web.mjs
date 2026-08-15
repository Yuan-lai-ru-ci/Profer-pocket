/**
 * 从 Profer-pocket 根仓库的 vite 构建产物（dist/）生成 Capacitor webDir（web/）。
 *
 * 背景：Profer-pocket 的 vite 配置 base:'./'，构建产物 dist/ 已是自包含单页
 * （index.html + assets/，资源相对引用），因此只需清空 web/ 后整体拷贝，无需像
 * 主仓库那样修正 ../assets/ 引用。
 *
 * 用法：node scripts/sync-web.mjs
 */
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const appRoot = resolve(here, '..') // tablet-app/
const pocketRoot = resolve(appRoot, '..') // Profer-pocket/
const src = resolve(pocketRoot, 'dist')
const webDir = resolve(appRoot, 'web')

if (!existsSync(resolve(src, 'index.html'))) {
  console.error(`[sync-web] 未找到前端产物: ${resolve(src, 'index.html')}`)
  console.error('[sync-web] 请先在 Profer-pocket 根目录执行 bun run build')
  process.exit(1)
}

// 1. 清空并重建 webDir
rmSync(webDir, { recursive: true, force: true })
mkdirSync(webDir, { recursive: true })

// 2. 拷贝 dist 全部内容到 web/
cpSync(src, webDir, { recursive: true })

// 3. 报告产物统计
function dirSize(p) {
  let total = 0
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const full = resolve(d, e.name)
      if (e.isDirectory()) walk(full)
      else total += statSync(full).size
    }
  }
  walk(p)
  return total
}
console.log(`[sync-web] 前端产物已同步到 ${webDir}`)
console.log(`[sync-web] index.html: ${(statSync(resolve(webDir, 'index.html')).size / 1024).toFixed(1)} KB, 总大小: ${(dirSize(webDir) / 1024 / 1024).toFixed(2)} MB`)
