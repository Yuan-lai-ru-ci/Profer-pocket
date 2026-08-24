#!/usr/bin/env node
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  assertPackageVersionsMatchReleaseConfig,
  buildPublishArgs,
  cleanupGeneratedManifestAfterPublishFailure,
  createUpdateManifest,
  loadReleaseConfig,
  parseReleaseArgs,
  readApkMetadata,
  resolveManifestPath,
  sha256File,
  validateApkAssetName,
  validateApkMetadata,
} from './release-utils.mjs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const usage = `Usage:
  node scripts/prepare-github-release.mjs --apk <path> [options]

Options:
  --apk <path>                 Signed APK, named Profer-Pocket-<version>.apk
  --release-notes <text>       Release notes (defaults to release.config.json)
  --release-notes-file <path>  Read release notes from a UTF-8 file
  --manifest <path>            Output path (defaults beside APK)
  --tag v<version>             Required with --publish
  --publish                    Explicitly run gh release create
  --dry-run                    Validate and generate manifest without network writes (default)
  --mandatory                  Mark the update as mandatory
  --help                       Show this help
`

function fail(message) {
  console.error(`[prepare-github-release] ${message}`)
  process.exitCode = 1
}

if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log(usage)
  process.exit(0)
}

try {
  const args = parseReleaseArgs(process.argv.slice(2))
  if (!args.apk) throw new Error('--apk is required')
  const config = loadReleaseConfig(fileURLToPath(new URL('../release.config.json', import.meta.url)))
  const appRoot = fileURLToPath(new URL('..', import.meta.url))
  assertPackageVersionsMatchReleaseConfig({
    releaseConfig: config,
    rootPackagePath: resolve(appRoot, '../package.json'),
    pocketPackagePath: resolve(appRoot, 'package.json'),
  })
  const apkPath = resolve(args.apk)
  const apkAssetName = validateApkAssetName(apkPath, config.versionName)
  const metadata = readApkMetadata(apkPath)
  validateApkMetadata(metadata, config)
  const sha256 = sha256File(apkPath)
  let releaseNotes = args.releaseNotes ?? config.releaseNotes
  if (args.releaseNotesFile) {
    releaseNotes = readFileSync(resolve(args.releaseNotesFile), 'utf8')
  }
  const manifest = createUpdateManifest({
    config,
    apkAssetName,
    sha256,
    releaseNotes,
    mandatory: args.mandatory || config.mandatory,
  })
  const manifestPath = resolveManifestPath(apkPath, args.manifest)
  const manifestExistedBefore = existsSync(manifestPath)
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  console.log(`[prepare-github-release] manifest: ${manifestPath}`)
  console.log(`[prepare-github-release] sha256: ${sha256}`)

  if (args.publish) {
    const publishArgs = buildPublishArgs({
      tag: args.tag,
      versionName: config.versionName,
      apkPath,
      manifestPath,
    })
    console.log(`[prepare-github-release] publishing tag ${args.tag} with APK and pocket-update.json`)
    try {
      execFileSync('gh', publishArgs, { stdio: 'inherit' })
    } catch (error) {
      const removed = cleanupGeneratedManifestAfterPublishFailure({
        publish: true,
        manifestExistedBefore,
        manifestPath,
      })
      if (removed) {
        throw new Error('GitHub Release 未创建，已删除本次生成的本地 manifest', { cause: error })
      }
      throw error
    }
  } else {
    console.log('[prepare-github-release] dry-run: no GitHub CLI write was performed')
  }
} catch (error) {
  fail(error instanceof Error ? error.message : String(error))
}
