import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import {
  applyGradleDefaultConfig,
  assertPackageVersionsMatchReleaseConfig,
  cleanupGeneratedManifestAfterPublishFailure,
  deriveDevVersion,
  buildPublishArgs,
  restoreGradleDevConfig,
  createUpdateManifest,
  expectedApkAssetName,
  parseReleaseArgs,
  sha256File,
  validateApkAssetName,
  validateApkMetadata,
  validateReleaseConfig,
  validateUpdateManifest,
} from './release-utils.mjs'

const config = {
  versionName: '0.1.11',
  versionCode: 10,
  releaseNotes: 'Phase A',
  mandatory: false,
}
const hash = 'a'.repeat(64)

test('asserts root and pocket package versions match release config', () => {
  const directory = mkdtempSync(join(tmpdir(), 'pocket-version-'))
  const rootPackagePath = join(directory, 'package.json')
  const pocketPackagePath = join(directory, 'pocket-app-package.json')
  writeFileSync(rootPackagePath, JSON.stringify({ version: config.versionName }))
  writeFileSync(pocketPackagePath, JSON.stringify({ version: config.versionName }))
  assert.equal(assertPackageVersionsMatchReleaseConfig({ releaseConfig: config, rootPackagePath, pocketPackagePath }), config.versionName)
  writeFileSync(pocketPackagePath, JSON.stringify({ version: '0.1.10' }))
  assert.throws(
    () => assertPackageVersionsMatchReleaseConfig({ releaseConfig: config, rootPackagePath, pocketPackagePath }),
    /pocket-app\/package\.json=0\.1\.10/,
  )
  writeFileSync(rootPackagePath, JSON.stringify({ version: '0.1.10' }))
  assert.throws(
    () => assertPackageVersionsMatchReleaseConfig({ releaseConfig: config, rootPackagePath, pocketPackagePath }),
    /root package\.json=0\.1\.10/,
  )
})

test('derives dev version independently from release version', () => {
  assert.deepEqual(deriveDevVersion(config), { versionName: '0.1.11-dev', versionCode: 1010 })
})

test('applies release Gradle config and restores dynamic dev expressions', () => {
  const dynamicDev = `        applicationId "com.profer.pocket.dev"\n        versionCode releaseConfig.versionCode + 1000\n        versionName "${'${releaseConfig.versionName}'}-dev"`
  const release = applyGradleDefaultConfig(dynamicDev, {
    appId: 'com.profer.pocket',
    versionCode: 10,
    versionName: '0.1.11',
  })
  assert.match(release, /applicationId "com\.profer\.pocket"/)
  assert.match(release, /versionCode 10/)
  assert.match(release, /versionName "0\.1\.11"/)
  assert.doesNotMatch(release, /1010|0\.1\.11-dev/)
  const restored = restoreGradleDevConfig(release)
  assert.match(restored, /versionCode releaseConfig\.versionCode \+ 1000/)
  assert.match(restored, /versionName "\$\{releaseConfig\.versionName\}-dev"/)
  assert.doesNotMatch(restored, /versionCode 1010|versionName "0\.1\.11-dev"/)
})

test('cleans only a newly generated manifest after publish failure', () => {
  const directory = mkdtempSync(join(tmpdir(), 'pocket-manifest-cleanup-'))
  const manifestPath = join(directory, 'pocket-update.json')
  writeFileSync(manifestPath, '{}')
  assert.equal(cleanupGeneratedManifestAfterPublishFailure({ publish: true, manifestExistedBefore: false, manifestPath }), true)
  assert.equal(existsSync(manifestPath), false)
  writeFileSync(manifestPath, '{}')
  assert.equal(cleanupGeneratedManifestAfterPublishFailure({ publish: true, manifestExistedBefore: true, manifestPath }), false)
  assert.equal(existsSync(manifestPath), true)
})

test('validates release config and rejects unsafe versions', () => {
  assert.deepEqual(validateReleaseConfig(config), config)
  assert.throws(() => validateReleaseConfig({ ...config, versionCode: 0 }), /positive safe integer/)
  assert.throws(() => validateReleaseConfig({ ...config, versionName: 'latest' }), /semantic version/)
  assert.throws(() => validateReleaseConfig({ ...config, mandatory: 'false' }), /mandatory must be boolean/)
})

test('validates APK asset name and metadata against the release config', () => {
  const apkName = expectedApkAssetName(config.versionName)
  assert.equal(apkName, 'Profer-Pocket-0.1.11.apk')
  assert.equal(validateApkAssetName(apkName, config.versionName), apkName)
  assert.throws(() => validateApkAssetName('app-release.apk', config.versionName), /APK filename must be/)
  assert.equal(validateApkMetadata({ versionName: '0.1.11', versionCode: 10 }, config).versionCode, 10)
  assert.throws(() => validateApkMetadata({ versionName: '0.1.11', versionCode: 9 }, config), /metadata mismatch/)
})

test('hashes APK bytes and creates a strict update manifest', () => {
  const directory = mkdtempSync(join(tmpdir(), 'pocket-release-'))
  const apkPath = join(directory, 'Profer-Pocket-0.1.11.apk')
  writeFileSync(apkPath, 'test apk bytes')
  const actualHash = sha256File(apkPath)
  assert.match(actualHash, /^[a-f0-9]{64}$/)
  const manifest = createUpdateManifest({ config, apkAssetName: 'Profer-Pocket-0.1.11.apk', sha256: hash })
  assert.deepEqual(Object.keys(manifest), ['versionCode', 'versionName', 'apkAssetName', 'sha256', 'releaseNotes', 'mandatory'])
  assert.deepEqual(validateUpdateManifest(manifest, config), manifest)
  assert.throws(() => createUpdateManifest({ config, apkAssetName: 'wrong.apk', sha256: hash }), /apkAssetName/)
  assert.throws(() => validateUpdateManifest({ ...manifest, sha256: 'BAD' }, config), /sha256/)
})

test('release args default to dry-run and publish is explicit', () => {
  assert.deepEqual(parseReleaseArgs(['--apk', 'signed.apk']), { publish: false, dryRun: true, mandatory: false, apk: 'signed.apk' })
  assert.equal(parseReleaseArgs(['--publish', '--tag', 'v0.1.11', '--apk', 'x']).publish, true)
  const publishArgs = buildPublishArgs({
    tag: 'v0.1.11',
    versionName: config.versionName,
    apkPath: 'Profer-Pocket-0.1.11.apk',
    manifestPath: 'pocket-update.json',
  })
  assert.deepEqual(publishArgs.slice(0, 5), ['release', 'create', 'v0.1.11', 'Profer-Pocket-0.1.11.apk', 'pocket-update.json'])
  assert.throws(() => buildPublishArgs({ tag: 'latest', versionName: config.versionName, apkPath: 'Profer-Pocket-0.1.11.apk', manifestPath: 'pocket-update.json' }), /requires tag v0.1.11/)
  assert.throws(() => buildPublishArgs({ tag: 'v0.1.11', versionName: config.versionName, apkPath: 'other.apk', manifestPath: 'pocket-update.json' }), /filename does not match/)
})
