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
 * 双通道更新检查：
 * - 正式版：GitHub 最新 Release，APK 与 pocket-update.json 作为 Release 资产。
 * - 测试版：AList 开发者源（设置里连点版本号 10 次触发）。
 */
object NativePocketUpdater {
    private const val RELEASES_API =
        "https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest"
    private const val MANIFEST_ASSET = "pocket-update.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkAssetName: String,
        val sha256: String,
        val apkUrl: String,
    )

    /** 正式通道：GitHub Release。 */
    suspend fun checkStable(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val release = fetchJson(RELEASES_API, "application/vnd.github+json")
            val assets = release.getJSONArray("assets")
            val manifestUrl = findAssetUrl(assets, MANIFEST_ASSET)
                ?: error("Release ${release.optString("tag_name")} 缺少 $MANIFEST_ASSET")
            val manifest = fetchJson(manifestUrl, null)
            val apkAssetName = manifest.getString("apkAssetName")
            val apkUrl = findAssetUrl(assets, apkAssetName)
                ?: error("Release 缺少 APK 资产 $apkAssetName")
            readInfo(manifest, apkUrl).takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    /** 测试通道：AList 开发者源，地址由本地构建注入（见 build.gradle.kts）。 */
    suspend fun checkInternal(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = BuildConfig.INTERNAL_UPDATE_BASE_URL.trim()
            require(baseUrl.isNotEmpty()) { "测试通道未配置" }
            val manifest = fetchJson(baseUrl.trimEnd('/') + "/" + MANIFEST_ASSET, null)
            val apkAssetName = manifest.getString("apkAssetName")
            readInfo(manifest, baseUrl.trimEnd('/') + "/" + apkAssetName)
                .takeIf { it.versionCode > BuildConfig.VERSION_CODE }
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

    private fun fetchJson(url: String, accept: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Profer-Pocket")
        if (accept != null) connection.setRequestProperty("Accept", accept)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
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
