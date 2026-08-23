import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, unlinkSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'

const VERSION_NAME_PATTERN = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/
const SHA256_PATTERN = /^[a-f0-9]{64}$/
const ASSET_PATTERN = /^Profer-Pocket-(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)\.apk$/
const REQUIRED_CONFIG_KEYS = ['versionName', 'versionCode', 'releaseNotes', 'mandatory']
const REQUIRED_MANIFEST_KEYS = ['versionCode', 'versionName', 'apkAssetName', 'sha256', 'releaseNotes', 'mandatory']

export function validateReleaseConfig(config) {
  if (!config || typeof config !== 'object' || Array.isArray(config)) {
    throw new Error('release config must be a JSON object')
  }
  for (const key of REQUIRED_CONFIG_KEYS) {
    if (!(key in config)) throw new Error(`release config missing ${key}`)
  }
  if (typeof config.versionName !== 'string' || !VERSION_NAME_PATTERN.test(config.versionName)) {
    throw new Error('release config versionName must be a semantic version string')
  }
  if (!Number.isSafeInteger(config.versionCode) || config.versionCode <= 0) {
    throw new Error('release config versionCode must be a positive safe integer')
  }
  if (typeof config.releaseNotes !== 'string') throw new Error('release config releaseNotes must be a string')
  if (typeof config.mandatory !== 'boolean') throw new Error('release config mandatory must be boolean')
  return config
}

export function loadReleaseConfig(configPath) {
  let config
  try {
    config = JSON.parse(readFileSync(configPath, 'utf8'))
  } catch (error) {
    throw new Error(`unable to read release config ${configPath}: ${error.message}`)
  }
  return validateReleaseConfig(config)
}

export function assertPackageVersionsMatchReleaseConfig({ releaseConfig, rootPackagePath, pocketPackagePath }) {
  validateReleaseConfig(releaseConfig)
  let rootPackage
  let pocketPackage
  try {
    rootPackage = JSON.parse(readFileSync(rootPackagePath, 'utf8'))
    pocketPackage = JSON.parse(readFileSync(pocketPackagePath, 'utf8'))
  } catch (error) {
    throw new Error(`unable to read package version metadata: ${error.message}`)
  }
  const mismatches = []
  if (rootPackage.version !== releaseConfig.versionName) mismatches.push(`root package.json=${rootPackage.version ?? '<missing>'}`)
  if (pocketPackage.version !== releaseConfig.versionName) mismatches.push(`pocket-app/package.json=${pocketPackage.version ?? '<missing>'}`)
  if (mismatches.length) {
    throw new Error(`package versions must match release config versionName ${releaseConfig.versionName}: ${mismatches.join(', ')}`)
  }
  return releaseConfig.versionName
}

export function deriveDevVersion(releaseConfig) {
  validateReleaseConfig(releaseConfig)
  return {
    versionName: `${releaseConfig.versionName}-dev`,
    versionCode: releaseConfig.versionCode + 1000,
  }
}

export function applyGradleDefaultConfig(source, config) {
  const withAppId = source.replace(/(^\s*applicationId\s+")[^"]+("\s*$)/m, `$1${config.appId}$2`)
  const withVersionCode = withAppId.replace(/(^\s*versionCode\s+)[^\r\n]+/m, `$1${config.versionCode}`)
  return withVersionCode.replace(/(^\s*versionName\s+")[^"]+("\s*$)/m, `$1${config.versionName}$2`)
}

export function restoreGradleDevConfig(source) {
  const withVersionCode = source.replace(/(^\s*versionCode\s+)[^\r\n]+/m, '$1releaseConfig.versionCode + 1000')
  return withVersionCode.replace(/(^\s*versionName\s+")[^"]+("\s*$)/m, '$1${releaseConfig.versionName}-dev$2')
}

export function cleanupGeneratedManifestAfterPublishFailure({ publish, manifestExistedBefore, manifestPath }) {
  if (!publish || manifestExistedBefore || !existsSync(manifestPath)) return false
  unlinkSync(manifestPath)
  return true
}

export function expectedApkAssetName(versionName) {
  if (typeof versionName !== 'string' || !VERSION_NAME_PATTERN.test(versionName)) {
    throw new Error('versionName must be a semantic version string')
  }
  return `Profer-Pocket-${versionName}.apk`
}

export function validateApkAssetName(apkPath, versionName) {
  const assetName = basename(apkPath)
  const expected = expectedApkAssetName(versionName)
  if (assetName !== expected || !ASSET_PATTERN.test(assetName)) {
    throw new Error(`APK filename must be ${expected}; received ${assetName}`)
  }
  return assetName
}

export function sha256File(filePath) {
  if (!existsSync(filePath)) throw new Error(`APK file does not exist: ${filePath}`)
  return createHash('sha256').update(readFileSync(filePath)).digest('hex')
}

function candidateAaptPaths() {
  const candidates = []
  for (const envName of ['POCKET_AAPT_PATH', 'AAPT2', 'AAPT']) {
    if (process.env[envName]) candidates.push(process.env[envName])
  }
  const sdkRoot = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
  if (sdkRoot) {
    const buildToolsRoot = join(sdkRoot, 'build-tools')
    if (existsSync(buildToolsRoot)) {
      const versions = readdirSync(buildToolsRoot, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => entry.name)
        .sort()
        .reverse()
      for (const version of versions) {
        candidates.push(join(buildToolsRoot, version, process.platform === 'win32' ? 'aapt.exe' : 'aapt'))
        candidates.push(join(buildToolsRoot, version, process.platform === 'win32' ? 'aapt2.exe' : 'aapt2'))
      }
    }
  }
  return candidates
}

function findAapt() {
  for (const candidate of candidateAaptPaths()) {
    if (existsSync(candidate)) return candidate
  }
  for (const command of process.platform === 'win32' ? ['aapt.exe', 'aapt2.exe', 'aapt', 'aapt2'] : ['aapt', 'aapt2']) {
    try {
      execFileSync(command, ['version'], { stdio: 'ignore' })
      return command
    } catch {
      // Continue probing the next Android SDK tool.
    }
  }
  throw new Error('Android aapt/aapt2 was not found. Set POCKET_AAPT_PATH or ANDROID_HOME to an SDK with build-tools.')
}

export function readApkMetadata(apkPath, aaptPath) {
  if (!existsSync(apkPath)) throw new Error(`APK file does not exist: ${apkPath}`)
  const tool = aaptPath || findAapt()
  let output
  try {
    output = execFileSync(tool, ['dump', 'badging', apkPath], { encoding: 'utf8' })
  } catch (error) {
    throw new Error(`failed to read APK metadata with ${tool}: ${error.message}`)
  }
  const match = output.match(/^package:.*?versionCode='([^']+)'.*?versionName='([^']+)'/m)
  if (!match) throw new Error(`aapt output did not contain package version metadata for ${apkPath}`)
  const versionCode = Number(match[1])
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) throw new Error(`APK versionCode is invalid: ${match[1]}`)
  return { versionCode, versionName: match[2] }
}

export function validateApkMetadata(metadata, config) {
  if (!metadata || metadata.versionName !== config.versionName || metadata.versionCode !== config.versionCode) {
    throw new Error(`APK metadata mismatch: expected ${config.versionName}/${config.versionCode}, received ${metadata?.versionName}/${metadata?.versionCode}`)
  }
  return metadata
}

export function createUpdateManifest({ config, apkAssetName, sha256, releaseNotes = config.releaseNotes, mandatory = config.mandatory }) {
  validateReleaseConfig(config)
  if (apkAssetName !== expectedApkAssetName(config.versionName)) throw new Error('manifest apkAssetName does not match release version')
  if (typeof sha256 !== 'string' || !SHA256_PATTERN.test(sha256)) throw new Error('manifest sha256 must be 64 lowercase hexadecimal characters')
  if (typeof releaseNotes !== 'string') throw new Error('manifest releaseNotes must be a string')
  if (typeof mandatory !== 'boolean') throw new Error('manifest mandatory must be boolean')
  return {
    versionCode: config.versionCode,
    versionName: config.versionName,
    apkAssetName,
    sha256,
    releaseNotes,
    mandatory,
  }
}

export function validateUpdateManifest(manifest, config) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw new Error('manifest must be a JSON object')
  for (const key of REQUIRED_MANIFEST_KEYS) {
    if (!(key in manifest)) throw new Error(`manifest missing ${key}`)
  }
  if (manifest.versionCode !== config.versionCode || manifest.versionName !== config.versionName) throw new Error('manifest version does not match release config')
  if (manifest.apkAssetName !== expectedApkAssetName(config.versionName)) throw new Error('manifest apkAssetName does not match release config')
  if (typeof manifest.sha256 !== 'string' || !SHA256_PATTERN.test(manifest.sha256)) throw new Error('manifest sha256 must be 64 lowercase hexadecimal characters')
  if (typeof manifest.releaseNotes !== 'string' || typeof manifest.mandatory !== 'boolean') throw new Error('manifest releaseNotes/mandatory has invalid type')
  return manifest
}

export function parseReleaseArgs(argv) {
  const args = { publish: false, dryRun: true, mandatory: false }
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index]
    if (value === '--publish') {
      args.publish = true
      args.dryRun = false
    } else if (value === '--dry-run') {
      args.dryRun = true
    } else if (value === '--mandatory') {
      args.mandatory = true
    } else if (value === '--apk' || value === '--release-notes-file' || value === '--manifest') {
      const next = argv[++index]
      if (!next) throw new Error(`${value} requires a value`)
      const key = value === '--release-notes-file'
        ? 'releaseNotesFile'
        : value.slice(2)
      args[key] = next
    } else if (value === '--release-notes') {
      const next = argv[++index]
      if (next === undefined) throw new Error('--release-notes requires a value')
      args.releaseNotes = next
    } else if (value === '--tag') {
      args.tag = argv[++index]
      if (!args.tag) throw new Error('--tag requires a value')
    } else if (value === '--help' || value === '-h') {
      args.help = true
    } else {
      throw new Error(`unknown argument: ${value}`)
    }
  }
  return args
}

export function buildPublishArgs({ tag, versionName, apkPath, manifestPath }) {
  const expectedTag = `v${versionName}`
  if (!tag || tag !== expectedTag) throw new Error(`publish requires tag ${expectedTag}`)
  if (!apkPath || !manifestPath) throw new Error('publish requires exactly one APK and pocket-update.json')
  if (basename(apkPath) !== expectedApkAssetName(versionName)) throw new Error('publish APK filename does not match release version')
  if (basename(manifestPath) !== 'pocket-update.json') throw new Error('publish manifest must be named pocket-update.json')
  return ['release', 'create', tag, apkPath, manifestPath, '--title', tag, '--generate-notes']
}

export function resolveManifestPath(apkPath, requestedPath) {
  return resolve(requestedPath || join(dirname(apkPath), 'pocket-update.json'))
}

export { REQUIRED_MANIFEST_KEYS, SHA256_PATTERN }
