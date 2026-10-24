package com.profer.pocket.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 双通道更新检查，全部走 GitHub Release：
 * - 正式版：最新的非预发布 Release。
 * - 测试版：预发布（prerelease），设置里连点版本号 10 次触发。
 */
object NativePocketUpdater {
    private const val RELEASES_LATEST =
        "https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest"
    private const val RELEASES_LIST =
        "https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases?per_page=30"
    private const val GITHUB_ACCEPT = "application/vnd.github+json"
    private const val MANIFEST_ASSET = "pocket-update.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkAssetName: String,
        val sha256: String,
        val apkUrl: String,
    )

    /** 正式通道：最新的非预发布 Release。 */
    suspend fun checkStable(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            readReleaseInfo(fetchJson(RELEASES_LATEST, GITHUB_ACCEPT))
                ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    /** 测试通道：预发布（prerelease）里 id 最大的那个。 */
    suspend fun checkInternal(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val releases = fetchJsonArray(RELEASES_LIST, GITHUB_ACCEPT)
            val latestPrerelease = (0 until releases.length())
                .map { releases.getJSONObject(it) }
                .filter { it.optBoolean("prerelease") && !it.optBoolean("draft") }
                .maxByOrNull { it.getLong("id") }
                ?: return@runCatching null
            readReleaseInfo(latestPrerelease)
                ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(info.apkAssetName.endsWith(".apk"))
            val target = File(context.cacheDir, info.apkAssetName)
            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Profer-Pocket")
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(target.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(digest == info.sha256) { "APK SHA-256 校验失败" }
            val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runCatching
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun openConnection(url: String, accept: String?): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Profer-Pocket")
        if (accept != null) connection.setRequestProperty("Accept", accept)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        return connection
    }

    private fun fetchJson(url: String, accept: String?): JSONObject =
        openConnection(url, accept).inputStream.bufferedReader().use { JSONObject(it.readText()) }

    private fun fetchJsonArray(url: String, accept: String?): JSONArray =
        openConnection(url, accept).inputStream.bufferedReader().use { JSONArray(it.readText()) }

    /** 从 Release 的资产里找 manifest，再按 manifest 找 APK。 */
    private fun readReleaseInfo(release: JSONObject): UpdateInfo? {
        val assets = release.getJSONArray("assets")
        val manifestUrl = findAssetUrl(assets, MANIFEST_ASSET) ?: return null
        val manifest = fetchJson(manifestUrl, null)
        val apkAssetName = manifest.getString("apkAssetName")
        val apkUrl = findAssetUrl(assets, apkAssetName) ?: return null
        return readInfo(manifest, apkUrl)
    }

    private fun findAssetUrl(assets: JSONArray, name: String): String? =
        (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name") == name }
            ?.getString("browser_download_url")

    private fun readInfo(manifest: JSONObject, apkUrl: String) = UpdateInfo(
        versionCode = manifest.getInt("versionCode"),
        versionName = manifest.getString("versionName"),
        apkAssetName = manifest.getString("apkAssetName"),
        sha256 = manifest.getString("sha256").lowercase(),
        apkUrl = apkUrl,
    )
}
