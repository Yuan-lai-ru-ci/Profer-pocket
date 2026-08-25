/**
 * Build a Profer-pocket APK.
 *
 * Usage:
 *   node scripts/build-apk.mjs
 *   node scripts/build-apk.mjs --variant=dev
 *   node scripts/build-apk.mjs --variant=release
 *
 * Release metadata comes only from release.config.json. The release task is
 * deliberately assembleRelease; Android signing fails closed in Gradle.
 */
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync, copyFileSync, mkdirSync, readdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  applyGradleDefaultConfig,
  assertPackageVersionsMatchReleaseConfig,
  deriveDevVersion,
  loadReleaseConfig,
  restoreGradleDevConfig,
} from './release-utils.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const appRoot = resolve(here, '..')
const androidRoot = resolve(appRoot, 'android')
const appGradle = resolve(androidRoot, 'app/build.gradle')
const stringsXml = resolve(androidRoot, 'app/src/main/res/values/strings.xml')
const capConfig = resolve(appRoot, 'capacitor.config.ts')
const releaseConfigPath = resolve(appRoot, 'release.config.json')
const releaseConfig = loadReleaseConfig(releaseConfigPath)
assertPackageVersionsMatchReleaseConfig({
  releaseConfig,
  rootPackagePath: resolve(appRoot, '../package.json'),
  pocketPackagePath: resolve(appRoot, 'package.json'),
})

const VARIANTS = {
  dev: {
    appId: 'com.profer.pocket.dev',
    appName: 'Profer Pocket（开发版）',
    versionCode: String(deriveDevVersion(releaseConfig).versionCode),
    versionName: deriveDevVersion(releaseConfig).versionName,
  },
  release: {
    appId: 'com.profer.pocket',
    appName: 'Profer Pocket',
    versionCode: String(releaseConfig.versionCode),
    versionName: releaseConfig.versionName,
  },
}
const DEV_CFG = VARIANTS.dev
const variantArg = process.argv.find((arg) => arg.startsWith('--variant='))
const variant = variantArg ? variantArg.slice('--variant='.length) : 'dev'
const cfg = VARIANTS[variant]
if (!cfg) {
  console.error(`[build-apk] 未知 variant: "${variant}"（可选 dev / release）`)
  process.exit(1)
}

function step(title) {
  console.log(`\n=== ${title} ===`)
}

function run(cmd, args, cwd) {
  const exec = process.platform === 'win32' ? 'cmd' : cmd
  const fullArgs = process.platform === 'win32' ? ['/c', cmd, ...args] : args
  console.log(`> ${cmd} ${args.join(' ')}  (cwd: ${cwd})`)
  const result = spawnSync(exec, fullArgs, { cwd, stdio: 'inherit', shell: false })
  if (result.status !== 0) {
    throw new Error(`[build-apk] 命令失败: ${cmd} ${args.join(' ')} (exit ${result.status})`)
  }
}

function getCommitShortId() {
  try {
    const repoRoot = resolve(appRoot, '..')
    const result = spawnSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' })
    if (result.status === 0 && result.stdout) return result.stdout.trim()
  } catch {
    // A source archive without git metadata can still produce a dev APK.
  }
  return null
}

function applyConfig(config) {
  let capacitor = readFileSync(capConfig, 'utf8')
  capacitor = capacitor.replace(/appId: '[^']*'/, `appId: '${config.appId}'`)
  capacitor = capacitor.replace(/appName: '[^']*'/, `appName: '${config.appName}'`)
  writeFileSync(capConfig, capacitor)

  const gradle = applyGradleDefaultConfig(readFileSync(appGradle, 'utf8'), config)
  writeFileSync(appGradle, gradle)

  let strings = readFileSync(stringsXml, 'utf8')
  strings = strings.replace(/<string name="app_name">[^<]*<\/string>/, `<string name="app_name">${config.appName}</string>`)
  strings = strings.replace(/<string name="title_activity_main">[^<]*<\/string>/, `<string name="title_activity_main">${config.appName}</string>`)
  strings = strings.replace(/<string name="package_name">[^<]*<\/string>/, `<string name="package_name">${config.appId}</string>`)
  strings = strings.replace(/<string name="custom_url_scheme">[^<]*<\/string>/, `<string name="custom_url_scheme">${config.appId}</string>`)
  writeFileSync(stringsXml, strings)
}

function injectBuildTag() {
  const indexPath = resolve(appRoot, 'web/index.html')
  let html = readFileSync(indexPath, 'utf8')
  const marker = "window.__POCKET_BUILD__='dev'"
  if (html.includes(marker)) return
  html = html.replace('</head>', `  <script>${marker}</script>\n</head>`)
  writeFileSync(indexPath, html)
}

function patchGradleRepos() {
  const changed = []
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name)
      if (entry.isDirectory()) {
        if (entry.name !== 'build') walk(path)
      } else if (entry.name.endsWith('.gradle')) {
        const content = readFileSync(path, 'utf8')
        const patched = content.replace(/google\(\)/g, "maven { url 'https://maven.aliyun.com/repository/google' }")
        if (patched !== content) {
          writeFileSync(path, patched)
          changed.push(path)
        }
      }
    }
  }
  walk(androidRoot)
  if (changed.length) console.log(`[build-apk] 已替换 ${changed.length} 个 Gradle google() 仓库地址`)
}

function gradleBuild(task) {
  if (process.platform === 'win32') {
    const wrapper = resolve(androidRoot, 'gradlew.bat').replace(/'/g, "''")
    const result = spawnSync('powershell.exe', [
      '-NoProfile', '-ExecutionPolicy', 'Bypass',
      '-Command', `& '${wrapper}' ${task} --no-daemon; exit $LASTEXITCODE`,
    ], { cwd: androidRoot, stdio: 'inherit', shell: false })
    if (result.status !== 0) throw new Error(`[build-apk] 失败: gradlew ${task}`)
  } else {
    run('./gradlew', [task, '--no-daemon'], androidRoot)
  }
}

console.log(`[build-apk] variant=${variant} appId=${cfg.appId} versionName=${cfg.versionName} (versionCode ${cfg.versionCode})`)
try {
  if (!process.env.ANDROID_HOME) console.warn('[build-apk] 警告: ANDROID_HOME 未设置')
  if (!process.env.JAVA_HOME) console.warn('[build-apk] 警告: JAVA_HOME 未设置')

  applyConfig(cfg)
  if (!existsSync(resolve(appRoot, 'node_modules'))) run('npm', ['install'], appRoot)
  step('sync-web')
  run('node', ['scripts/sync-web.mjs'], appRoot)
  if (variant === 'dev') injectBuildTag()
  step('cap sync android')
  run('npx', ['cap', 'sync', 'android'], appRoot)
  patchGradleRepos()

  const gradleTask = variant === 'release' ? 'assembleRelease' : 'assembleDebug'
  step(`gradlew ${gradleTask}`)
  gradleBuild(gradleTask)

  const apk = resolve(androidRoot, `app/build/outputs/apk/${variant === 'release' ? 'release/app-release.apk' : 'debug/app-debug.apk'}`)
  const outDir = resolve(appRoot, 'releases')
  mkdirSync(outDir, { recursive: true })
  const commitId = variant === 'dev' ? getCommitShortId() : null
  const outputName = variant === 'dev' && commitId
    ? `Profer-Pocket-${commitId}.apk`
    : `Profer-Pocket-${cfg.versionName}.apk`
  const outputPath = resolve(outDir, outputName)
  copyFileSync(apk, outputPath)
  console.log(`[build-apk] APK 已生成: ${outputPath}`)
} finally {
  applyConfig(DEV_CFG)
  writeFileSync(appGradle, restoreGradleDevConfig(readFileSync(appGradle, 'utf8')))
  console.log('[build-apk] 配置已恢复为 dev 默认')
}
