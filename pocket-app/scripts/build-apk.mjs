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
 *   | versionCode    | 6                | 17                         |
 *   | versionName    | 0.1.5            | 0.1.8-dev                  |
 *
 * 步骤：校验环境 → 写入 variant 配置 → 同步 web → cap sync android → gradlew assembleDebug。
 * 环境要求：ANDROID_HOME=C:\Android\Sdk、JAVA_HOME=C:\Android\jdk-21（必须 JDK 21）。
 * 产物：android/app/build/outputs/apk/debug/app-debug.apk，并复制到 releases/。
 * 命名规则（工作区 CLAUDE.md）：dev 版文件名只用当前分支提交短 ID（Profer-Pocket-<commit>.apk），不附带版本号，
 * 提交 ID 已唯一标识该分支构建；区分多分支并行出的 dev 包，防 AList 互相覆盖。
 * release 版文件名用版本号（Profer-Pocket-<版本>.apk），不用提交 ID。
 */
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync, copyFileSync, mkdirSync, readdirSync } from 'node:fs'
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
    versionCode: '17',
    versionName: '0.1.8-dev',
  },
  release: {
    appId: 'com.profer.pocket',
    appName: 'Profer Pocket',
    versionCode: '6',
    versionName: '0.1.5',
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

/** 取仓库当前提交短 ID（dev 版文件名标注功能分支用；git 不可用时返回 null） */
function getCommitShortId() {
  try {
    const repoRoot = resolve(appRoot, '..') // Profer-pocket/
    const r = spawnSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' })
    if (r.status === 0 && r.stdout) return r.stdout.trim()
  } catch { /* git 不可用则忽略 */ }
  return null
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

/**
 * dev 变体向前端 web/index.html 注入 __POCKET_BUILD__='dev' 标记，驱动前端调试 HUD 开启。
 * 前端产物 dev/release 是同一份 bundle（变体差异只在 APK 层），只有注入标记才能区分。
 * release 变体不调用本函数 → HUD 关闭。注入位置为 <head> 闭合前，尽早生效；幂等（已注入则跳过）。
 */
function injectBuildTag() {
  const indexPath = resolve(appRoot, 'web/index.html')
  let html = readFileSync(indexPath, 'utf8')
  const marker = "window.__POCKET_BUILD__='dev'"
  if (html.includes(marker)) {
    console.log('[build-apk] dev 标记已存在，跳过注入')
    return
  }
  const script = `<script>${marker}</script>`
  html = html.replace('</head>', `  ${script}\n</head>`)
  writeFileSync(indexPath, html)
  console.log(`[build-apk] ✅ 已注入 dev 标记: ${script}`)
}

/**
 * 替换 android 工程内所有 *.gradle 的 google() 为阿里云镜像。
 *
 * 背景：本机 maven.google.com https 被阻断（curl 超时），必须走阿里云镜像，
 * 否则 gradlew 拉依赖会卡死。cap sync 每次会重新生成
 * capacitor-cordova-android-plugins/build.gradle（又变回 google()），
 * 所以必须在 sync 之后、gradlew 之前执行本函数。幂等（重复执行无害）。
 */
function patchGradleRepos() {
  const changed = []
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = resolve(d, e.name)
      if (e.isDirectory()) {
        if (e.name === 'build') continue
        walk(p)
      } else if (e.name.endsWith('.gradle')) {
        let c = readFileSync(p, 'utf8')
        const n = c.replace(
          /google\(\)/g,
          "maven { url 'https://maven.aliyun.com/repository/google' }"
        )
        if (n !== c) {
          writeFileSync(p, n)
          changed.push(p)
        }
      }
    }
  }
  walk(androidRoot)
  if (changed.length) {
    console.log('[build-apk] 🔧 已替换 google() 为阿里云镜像:')
    for (const p of changed) console.log(`  ${p}`)
  } else {
    console.log('[build-apk] google() 无需替换（已是镜像配置）')
  }
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

  // 3.5 dev 变体注入 __POCKET_BUILD__ 标记（release 不注入 → 前端 HUD 关闭）
  if (variant === 'dev') {
    step('注入 dev 构建标记')
    injectBuildTag()
  }

  // 4. 同步 Capacitor 到 android 工程
  step('cap sync android')
  run('npx', ['cap', 'sync', 'android'], appRoot)

  // 4.5 maven 镜像 patch（cap sync 会重新生成插件 gradle，需在此之后替换 google()）
  step('maven 镜像 patch')
  patchGradleRepos()

  // 5. gradle 打 debug APK
  step('gradlew assembleDebug')
  if (process.platform === 'win32') {
    // 本机 cmd 对带空格/中文用户路径的 .bat 参数解析不稳定，使用 PowerShell 直接调用 wrapper。
    const wrapper = resolve(androidRoot, 'gradlew.bat').replace(/'/g, "''")
    const gr = spawnSync('powershell.exe', [
      '-NoProfile',
      '-ExecutionPolicy', 'Bypass',
      '-Command', `& '${wrapper}' assembleDebug`,
    ], {
      cwd: androidRoot,
      stdio: 'inherit',
      shell: false,
    })
    if (gr.status !== 0) {
      throw new Error('[build-apk] 失败: gradlew assembleDebug')
    }
  } else {
    run('./gradlew', ['assembleDebug'], androidRoot)
  }

  // 6. 复制产物：dev 版文件名只用提交短 ID（不附带版本号）；release 用版本号
  step('复制 APK 产物')
  const apk = resolve(androidRoot, 'app/build/outputs/apk/debug/app-debug.apk')
  const outDir = resolve(appRoot, 'releases')
  mkdirSync(outDir, { recursive: true })
  const commitId = variant === 'dev' ? getCommitShortId() : null
  const outApk = variant === 'dev' && commitId
    ? resolve(outDir, `Profer-Pocket-${commitId}.apk`)
    : resolve(outDir, `Profer-Pocket-${cfg.versionName}.apk`)
  copyFileSync(apk, outApk)
  console.log(`[build-apk] ✅ APK 已生成: ${outApk}${variant === 'dev' && commitId ? '（dev 文件名=提交短ID）' : ''}`)
} finally {
  // 无论成败恢复 dev 默认配置，避免工作区残留 release 配置
  applyConfig(DEV_CFG)
  console.log('[build-apk] 配置已恢复为 dev 默认')
}
