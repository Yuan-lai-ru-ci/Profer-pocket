/**
 * 一键构建 Profer-pocket APK（正式版 / 开发版双 variant）。
 *
 * 用法：
 *   node scripts/build-apk.mjs                     # 默认 dev
 *   node scripts/build-apk.mjs --variant=dev
 *   node scripts/build-apk.mjs --variant=release
 *
 * variant 差异（构建前写入配置，构建结束无论成败都会恢复为 dev 默认）：
 *   | 项             | release          | dev                        |
 *   | appId          | com.profer.pocket | com.profer.pocket.dev      |
 *   | appName        | Profer Pocket     | Profer Pocket（开发版）    |
 *   | versionCode    | 1                | 10                         |
 *   | versionName    | 0.1.0            | 0.1.1-dev                  |
 *
 * 步骤：校验环境 → 写入 variant 配置 → 同步 web → cap sync android → gradlew assembleDebug。
 * 环境要求：ANDROID_HOME=C:\Android\Sdk、JAVA_HOME=C:\Android\jdk-21（必须 JDK 21）。
 * 产物：android/app/build/outputs/apk/debug/app-debug.apk，并复制到 releases/Profer-Pocket-<versionName>.apk
 */
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync, copyFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const appRoot = resolve(here, '..') // pocket-app/
const androidRoot = resolve(appRoot, 'android')
const appGradle = resolve(androidRoot, 'app/build.gradle')
const stringsXml = resolve(androidRoot, 'app/src/main/res/values/strings.xml')
const capConfig = resolve(appRoot, 'capacitor.config.ts')

// ── 双 variant 配置（发新版时在此同步版本号） ──
const VARIANTS = {
  dev: {
    appId: 'com.profer.pocket.dev',
    appName: 'Profer Pocket（开发版）',
    versionCode: '11',
    versionName: '0.1.2-dev',
  },
  release: {
    appId: 'com.profer.pocket',
    appName: 'Profer Pocket',
    versionCode: '2',
    versionName: '0.1.1',
  },
}
const DEV_CFG = VARIANTS.dev

// ── 解析 --variant=xxx ──
const variantArg = process.argv.find((a) => a.startsWith('--variant='))
const variant = variantArg ? variantArg.split('=')[1] : 'dev'
const cfg = VARIANTS[variant]
if (!cfg) {
  console.error(`[build-apk] 未知 variant: "${variant}"（可选 dev / release）`)
  process.exit(1)
}

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
    throw new Error(`[build-apk] 命令失败: ${cmd} ${args.join(' ')} (exit ${r.status})`)
  }
}

/** 把三处配置写入指定 variant 的值（cap sync 会按 appId 覆盖 applicationId 与 strings 的 package/custom_url_scheme） */
function applyConfig(c) {
  let t = readFileSync(capConfig, 'utf8')
  t = t.replace(/appId: '[^']*'/, `appId: '${c.appId}'`)
  t = t.replace(/appName: '[^']*'/, `appName: '${c.appName}'`)
  writeFileSync(capConfig, t)

  let g = readFileSync(appGradle, 'utf8')
  // applicationId 需直接改：实测 cap sync 不会用 capacitor.config 的 appId 覆盖 build.gradle
  g = g.replace(/applicationId "[^"]*"/, `applicationId "${c.appId}"`)
  g = g.replace(/versionCode \d+/, `versionCode ${c.versionCode}`)
  g = g.replace(/versionName "[^"]*"/, `versionName "${c.versionName}"`)
  writeFileSync(appGradle, g)

  let s = readFileSync(stringsXml, 'utf8')
  s = s.replace(/<string name="app_name">[^<]*<\/string>/, `<string name="app_name">${c.appName}</string>`)
  s = s.replace(/<string name="title_activity_main">[^<]*<\/string>/, `<string name="title_activity_main">${c.appName}</string>`)
  s = s.replace(/<string name="package_name">[^<]*<\/string>/, `<string name="package_name">${c.appId}</string>`)
  s = s.replace(/<string name="custom_url_scheme">[^<]*<\/string>/, `<string name="custom_url_scheme">${c.appId}</string>`)
  writeFileSync(stringsXml, s)
}

console.log(`\n[build-apk] variant=${variant}  appId=${cfg.appId}  versionName=${cfg.versionName} (versionCode ${cfg.versionCode})`)

try {
  // 0. 环境校验（仅提示，不阻断）
  if (!process.env.ANDROID_HOME) console.warn('[build-apk] 警告: ANDROID_HOME 未设置，将依赖 android/local.properties')
  if (!process.env.JAVA_HOME) console.warn('[build-apk] 警告: JAVA_HOME 未设置，请确认 gradle 能找到 JDK21')

  // 1. 写入 variant 配置
  step('写入 variant 配置')
  applyConfig(cfg)

  // 2. 依赖安装（node_modules 不存在才装）
  if (!existsSync(resolve(appRoot, 'node_modules'))) {
    step('npm install')
    run('npm', ['install'], appRoot)
  }

  // 3. 同步前端产物到 web/
  step('sync-web')
  run('node', ['scripts/sync-web.mjs'], appRoot)

  // 4. 同步 Capacitor 到 android 工程
  step('cap sync android')
  run('npx', ['cap', 'sync', 'android'], appRoot)

  // 5. gradle 打 debug APK
  step('gradlew assembleDebug')
  if (process.platform === 'win32') {
    // 注意：spawnSync 直接以 cmd /c 执行 gradlew.bat 时，cwd 偶发不生效导致找不到命令；
    // 改为经 Git Bash 执行 POSIX 版 gradlew 脚本（本机环境必有 Git Bash）。
    const gr = spawnSync('bash', ['-lc', `cd '${androidRoot}' && ./gradlew assembleDebug`], { stdio: 'inherit' })
    if (gr.status !== 0) {
      throw new Error('[build-apk] 失败: gradlew assembleDebug')
    }
  } else {
    run('./gradlew', ['assembleDebug'], androidRoot)
  }

  // 6. 复制产物为带版本名文件
  step('复制 APK 产物')
  const apk = resolve(androidRoot, 'app/build/outputs/apk/debug/app-debug.apk')
  const outDir = resolve(appRoot, 'releases')
  mkdirSync(outDir, { recursive: true })
  const outApk = resolve(outDir, `Profer-Pocket-${cfg.versionName}.apk`)
  copyFileSync(apk, outApk)
  console.log(`[build-apk] ✅ APK 已生成: ${outApk}`)
} finally {
  // 无论成败恢复 dev 默认配置，避免工作区残留 release 配置
  applyConfig(DEV_CFG)
  console.log('[build-apk] 配置已恢复为 dev 默认')
}
