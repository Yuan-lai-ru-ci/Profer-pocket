/**
 * 一键构建 Profer-pocket 开发版 APK。
 *
 * 步骤：校验环境 → 安装依赖（首次）→ 同步 web → cap sync android → gradlew assembleDebug。
 * 环境要求：ANDROID_HOME=C:\Android\Sdk、JAVA_HOME=C:\Android\jdk-21（必须 JDK 21）。
 * 产物：android/app/build/outputs/apk/debug/app-debug.apk
 *
 * 用法：node scripts/build-apk.mjs
 */
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const appRoot = resolve(here, '..') // tablet-app/
const androidRoot = resolve(appRoot, 'android')

function step(title) {
  console.log(`\n=== ${title} ===`)
}
function run(cmd, args, cwd) {
  // Windows 下统一走 cmd /c，确保当前目录下的 .bat（如 gradlew.bat）能被找到。
  const exec = process.platform === 'win32' ? 'cmd' : cmd
  const fullArgs = process.platform === 'win32' ? ['/c', cmd, ...args] : args
  console.log(`> ${cmd} ${args.join(' ')}  (cwd: ${cwd})`)
  const r = spawnSync(exec, fullArgs, { cwd, stdio: 'inherit', shell: false })
  if (r.status !== 0) {
    console.error(`[build-apk] 失败: ${cmd} ${args.join(' ')}`)
    process.exit(r.status ?? 1)
  }
}

// 0. 环境校验（仅提示，不阻断）
if (!process.env.ANDROID_HOME) console.warn('[build-apk] 警告: ANDROID_HOME 未设置，将依赖 android/local.properties')
if (!process.env.JAVA_HOME) console.warn('[build-apk] 警告: JAVA_HOME 未设置，请确认 gradle 能找到 JDK21')

// 1. 依赖安装（node_modules 不存在才装）
if (!existsSync(resolve(appRoot, 'node_modules'))) {
  step('npm install')
  run('npm', ['install'], appRoot)
}

// 2. 同步前端产物到 web/
step('sync-web')
run('node', ['scripts/sync-web.mjs'], appRoot)

// 3. 同步 Capacitor 到 android 工程
step('cap sync android')
run('npx', ['cap', 'sync', 'android'], appRoot)

// 4. gradle 打 debug APK
step('gradlew assembleDebug')
if (process.platform === 'win32') {
  // 注意：spawnSync 直接以 cmd /c 执行 gradlew.bat 时，cwd 偶发不生效导致找不到命令；
  // 改为经 Git Bash 执行 POSIX 版 gradlew 脚本（本机环境必有 Git Bash）。
  const gr = spawnSync('bash', ['-lc', `cd '${androidRoot}' && ./gradlew assembleDebug`], { stdio: 'inherit' })
  if (gr.status !== 0) {
    console.error('[build-apk] 失败: gradlew assembleDebug')
    process.exit(gr.status ?? 1)
  }
} else {
  run('./gradlew', ['assembleDebug'], androidRoot)
}

const apk = resolve(androidRoot, 'app/build/outputs/apk/debug/app-debug.apk')
console.log(`\n[build-apk] ✅ APK 已生成: ${apk}`)
