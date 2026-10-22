package com.profer.pocket.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/** Developer-channel updater for the native Android client. */
object NativePocketUpdater {
    private const val MANIFEST_URL = "http://<internal-host>/<internal-dir>/pocket-update.json"
    private const val APK_BASE_URL = "http://<internal-host>/<internal-dir>/"

    data class UpdateInfo(val versionCode: Int, val versionName: String, val apkAssetName: String, val sha256: String)

    suspend fun check(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val manifest = URL(MANIFEST_URL).openStream().bufferedReader().use { JSONObject(it.readText()) }
            val info = UpdateInfo(
                versionCode = manifest.getInt("versionCode"),
                versionName = manifest.getString("versionName"),
                apkAssetName = manifest.getString("apkAssetName"),
                sha256 = manifest.getString("sha256").lowercase(),
            )
            if (info.versionCode <= BuildConfig.VERSION_CODE) null else info
        }
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(info.apkAssetName.endsWith(".apk"))
            val target = File(context.cacheDir, info.apkAssetName)
            val connection = URL(APK_BASE_URL + info.apkAssetName).openConnection() as HttpURLConnection
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
}
