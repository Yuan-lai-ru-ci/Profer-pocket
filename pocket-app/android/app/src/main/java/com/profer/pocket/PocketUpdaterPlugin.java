package com.profer.pocket;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * GitHub Release APK 更新桥接。
 *
 * 仅处理由前端完成 Release manifest 校验后传入的 APK URL：下载到私有 files/updates，
 * 流式 SHA-256 校验成功后才持久化，并只通过 Android 系统安装器交付 APK。
 */
@CapacitorPlugin(name = "PocketUpdater")
public class PocketUpdaterPlugin extends Plugin {
    private static final String PREFS_NAME = "profer_pocket_updater";
    private static final String KEY_PATH = "apkPath";
    private static final String KEY_VERSION_CODE = "versionCode";
    private static final String KEY_VERSION_NAME = "versionName";
    private static final String KEY_SHA256 = "sha256";
    private static final String KEY_URL = "downloadUrl";
    private static final String UPDATE_DIRECTORY = "updates";
    private static final String TEMP_FILE_NAME = "pocket-update.apk.part";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final String GITHUB_API_HOST = "api.github.com";
    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_OBJECTS_HOST = "objects.githubusercontent.com";
    private static final String GITHUB_RELEASE_ASSETS_HOST = "release-assets.githubusercontent.com";
    private static final String LATEST_RELEASE_PATH = "/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest";
    private static final int GITHUB_JSON_MAX_BYTES = 2 * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void load() {
        super.load();
        // 下载完成后才会写入记录；启动时验证仍能避免残缺/外部清理后的误安装。
        try {
            worker.execute(() -> safeGetValidDownloadedUpdate());
        } catch (RejectedExecutionException ignored) {
            // The plugin is already being destroyed; there is no active JS call to reject.
        }
    }

    @PluginMethod
    public void getCurrentVersion(PluginCall call) {
        try {
            PackageInfo packageInfo = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            JSObject result = new JSObject();
            result.put("versionCode", getLongVersionCode(packageInfo));
            result.put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName);
            call.resolve(result);
        } catch (PackageManager.NameNotFoundException error) {
            call.reject("无法读取当前应用版本", error);
        }
    }

    @PluginMethod
    public void getDownloadedUpdate(PluginCall call) {
        submitWorker(call, () -> {
            try {
                DownloadedUpdate update = getValidDownloadedUpdate();
                if (update == null) {
                    call.resolve();
                    return;
                }
                call.resolve(update.toJson());
            } catch (Exception error) {
                call.reject("无法读取已下载更新包，请稍后重试", error);
            }
        });
    }

    @PluginMethod
    public void downloadUpdate(PluginCall call) {
        final String url = call.getString("url");
        final String sha256 = normalizeSha256(call.getString("sha256"));
        final Integer versionCode = call.getInt("versionCode");
        final String versionName = call.getString("versionName");

        if (!isTrustedGithubUrl(url)) {
            call.reject("下载地址必须是受信任的 GitHub HTTPS 链接");
            return;
        }
        if (sha256 == null) {
            call.reject("更新包校验值无效");
            return;
        }
        if (versionCode == null || versionCode <= 0 || versionName == null || versionName.trim().isEmpty()) {
            call.reject("更新包版本信息无效");
            return;
        }

        final DownloadRequest request = new DownloadRequest(url, sha256, versionCode, versionName.trim());
        submitWorker(call, () -> downloadAndVerify(call, request));
    }

    /**
     * Fetches the Release API response or the manifest asset through Android networking. WebView
     * requests to GitHub REST without User-Agent receive HTTP 403, so this bridge owns that header.
     */
    @PluginMethod
    public void fetchGithubJson(PluginCall call) {
        final String url = call.getString("url");
        if (!isTrustedGithubJsonUrl(url)) {
            call.reject("更新信息地址必须是受信任的 GitHub Release 或 asset HTTPS 链接");
            return;
        }
        submitWorker(call, () -> {
            HttpURLConnection connection = null;
            try {
                connection = openGithubJsonConnection(url);
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("GitHub 返回错误（HTTP " + responseCode + "）");
                }
                JSObject result = new JSObject();
                result.put("json", readUtf8(connection));
                call.resolve(result);
            } catch (IOException error) {
                call.reject("获取 GitHub 更新信息失败：" + readableNetworkError(error), error);
            } catch (Exception error) {
                call.reject("获取 GitHub 更新信息时发生错误，请稍后重试", error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    @PluginMethod
    public void installDownloadedUpdate(PluginCall call) {
        submitWorker(call, () -> {
            try {
                DownloadedUpdate update = getValidDownloadedUpdate();
                if (update == null) {
                    call.reject("没有可安装的已校验更新包，请重新下载");
                    return;
                }
                PackageInfo current = getContext().getPackageManager()
                        .getPackageInfo(getContext().getPackageName(), 0);
                PackageInfo candidate = getArchivePackageInfo(update.file);
                if (candidate == null || !getContext().getPackageName().equals(candidate.packageName)) {
                    safeClearDownloadedUpdate();
                    call.reject("更新包不属于当前应用，已清理");
                    return;
                }
                if (getLongVersionCode(candidate) <= getLongVersionCode(current)) {
                    safeClearDownloadedUpdate();
                    call.reject("更新包版本不高于当前应用，已清理");
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && !getContext().getPackageManager().canRequestPackageInstalls()) {
                    call.reject("请先在系统设置中允许 Profer Pocket 安装未知应用，然后重试");
                    return;
                }

                Uri apkUri = FileProvider.getUriForFile(
                        getContext(), getContext().getPackageName() + ".fileprovider", update.file);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apkUri, APK_MIME_TYPE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                call.resolve();
            } catch (PackageManager.NameNotFoundException error) {
                call.reject("无法读取当前应用版本", error);
            } catch (IllegalArgumentException error) {
                safeClearDownloadedUpdate();
                call.reject("更新包路径无效，已清理，请重新下载", error);
            } catch (Exception error) {
                call.reject("无法打开系统安装器，请稍后重试", error);
            }
        });
    }

    @PluginMethod
    public void clearDownloadedUpdate(PluginCall call) {
        submitWorker(call, () -> {
            try {
                clearDownloadedUpdate();
                call.resolve();
            } catch (Exception error) {
                call.reject("无法清理已下载更新包，请稍后重试", error);
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        worker.shutdownNow();
        super.handleOnDestroy();
    }

    private void downloadAndVerify(PluginCall call, DownloadRequest request) {
        File tempFile = null;
        try {
            tempFile = new File(getUpdatesDirectory(), TEMP_FILE_NAME);
            clearFile(tempFile);
            HttpURLConnection connection = openHttpsConnection(request.url);
            // minSdkVersion=23；getContentLengthLong() 需要更高 API。APK 通常远低于 int 上限，
            // 服务器未给 Content-Length 时保留 -1，前端据此显示未知总大小进度。
            int contentLength = connection.getContentLength();
            long totalBytes = contentLength >= 0 ? contentLength : -1L;
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                throw new IOException("下载服务器返回错误（HTTP " + connection.getResponseCode() + "）");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long transferred = 0;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(tempFile, false)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    transferred += count;
                    notifyProgress(transferred, totalBytes);
                }
                output.getFD().sync();
            } finally {
                connection.disconnect();
            }

            String actualSha256 = toHex(digest.digest());
            if (!request.sha256.equals(actualSha256)) {
                throw new VerificationException("更新包校验失败，文件已删除，请重试");
            }
            PackageInfo downloadedPackage = getArchivePackageInfo(tempFile);
            if (downloadedPackage == null || !getContext().getPackageName().equals(downloadedPackage.packageName)) {
                throw new VerificationException("更新包不属于当前应用，文件已删除");
            }
            if (getLongVersionCode(downloadedPackage) != request.versionCode
                    || !request.versionName.equals(downloadedPackage.versionName)) {
                throw new VerificationException("更新包版本与更新信息不一致，文件已删除");
            }

            File targetFile = new File(getUpdatesDirectory(), "Profer-Pocket-" + request.versionCode + ".apk");
            clearFile(targetFile);
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("无法保存已校验更新包");
            }
            persistDownloadedUpdate(new DownloadedUpdate(targetFile, request.versionCode, request.versionName,
                    request.sha256, request.url));
            notifyProgress(transferred, totalBytes);
            call.resolve();
        } catch (VerificationException error) {
            clearFile(tempFile);
            call.reject(error.getMessage());
        } catch (NoSuchAlgorithmException error) {
            clearFile(tempFile);
            call.reject("设备不支持更新包校验", error);
        } catch (IOException error) {
            clearFile(tempFile);
            call.reject("下载更新包失败：" + readableNetworkError(error), error);
        } catch (Exception error) {
            clearFile(tempFile);
            call.reject("下载更新包时发生错误，请重试", error);
        }
    }

    private HttpURLConnection openHttpsConnection(String rawUrl) throws IOException {
        URL url = new URL(rawUrl);
        if (!isTrustedGithubUrl(rawUrl)) throw new IOException("下载地址必须来自受信任的 GitHub HTTPS 主机");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", githubUserAgent());
        connection.setRequestProperty("Accept", APK_MIME_TYPE + ", application/octet-stream");
        connection.connect();
        if (!isTrustedGithubUrl(connection.getURL().toString())) {
            connection.disconnect();
            throw new IOException("下载跳转到了不受信任的地址");
        }
        return connection;
    }

    private HttpURLConnection openGithubJsonConnection(String rawUrl) throws IOException {
        URL url = new URL(rawUrl);
        if (!isTrustedGithubJsonUrl(rawUrl)) throw new IOException("更新信息地址不受信任");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", githubUserAgent());
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/json");
        connection.connect();
        if (!isTrustedGithubJsonUrl(connection.getURL().toString())) {
            connection.disconnect();
            throw new IOException("更新信息跳转到了不受信任的地址");
        }
        return connection;
    }

    private String githubUserAgent() {
        try {
            PackageInfo packageInfo = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            String versionName = packageInfo.versionName;
            if (versionName != null && !versionName.trim().isEmpty()) {
                return "Profer-Pocket/" + versionName.trim().replaceAll("[\\r\\n]", "");
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // A stable fallback preserves GitHub's required User-Agent contract.
        }
        return "Profer-Pocket/unknown";
    }

    private static String readUtf8(HttpURLConnection connection) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > GITHUB_JSON_MAX_BYTES) throw new IOException("GitHub 更新信息过大");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private boolean submitWorker(PluginCall call, Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException error) {
            call.reject("更新服务已关闭，请重启应用后重试", error);
            return false;
        }
    }

    private DownloadedUpdate safeGetValidDownloadedUpdate() {
        try {
            return getValidDownloadedUpdate();
        } catch (Exception ignored) {
            return null;
        }
    }

    private DownloadedUpdate getValidDownloadedUpdate() {
        SharedPreferences preferences = preferences();
        String storedPath = preferences.getString(KEY_PATH, null);
        String sha256 = normalizeSha256(preferences.getString(KEY_SHA256, null));
        int versionCode = preferences.getInt(KEY_VERSION_CODE, 0);
        String versionName = preferences.getString(KEY_VERSION_NAME, null);
        String url = preferences.getString(KEY_URL, null);
        if (storedPath == null || sha256 == null || versionCode <= 0 || versionName == null || versionName.isEmpty()
                || !isTrustedGithubUrl(url)) {
            safeClearDownloadedUpdate();
            return null;
        }

        File file = new File(storedPath);
        if (!isInsideUpdatesDirectory(file) || !file.isFile()) {
            safeClearDownloadedUpdate();
            return null;
        }
        try {
            if (!sha256.equals(sha256ForFile(file))) {
                safeClearDownloadedUpdate();
                return null;
            }
            return new DownloadedUpdate(file, versionCode, versionName, sha256, url);
        } catch (IOException | NoSuchAlgorithmException error) {
            safeClearDownloadedUpdate();
            return null;
        }
    }

    private void persistDownloadedUpdate(DownloadedUpdate update) {
        preferences().edit()
                .putString(KEY_PATH, update.file.getAbsolutePath())
                .putInt(KEY_VERSION_CODE, update.versionCode)
                .putString(KEY_VERSION_NAME, update.versionName)
                .putString(KEY_SHA256, update.sha256)
                .putString(KEY_URL, update.url)
                .apply();
    }

    private void safeClearDownloadedUpdate() {
        try {
            clearDownloadedUpdate();
        } catch (Exception ignored) {
            // The caller will report the original operation failure; never strand a PluginCall while cleaning up.
        }
    }

    private void clearDownloadedUpdate() {
        // 此目录仅由本插件持有。清理整个目录可覆盖“文件已落盘、偏好尚未来得及写入”
        // 的异常退出，避免留下无法验证和不可安装的孤立 APK。
        File directory = getUpdatesDirectory();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) clearFile(file);
        }
        preferences().edit().clear().apply();
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private File getUpdatesDirectory() {
        File directory = new File(getContext().getFilesDir(), UPDATE_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建更新包目录");
        }
        return directory;
    }

    private boolean isInsideUpdatesDirectory(File file) {
        try {
            String directoryPath = getUpdatesDirectory().getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(directoryPath);
        } catch (IOException error) {
            return false;
        }
    }

    private PackageInfo getArchivePackageInfo(File apkFile) {
        PackageManager packageManager = getContext().getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES));
        }
        return packageManager.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_ACTIVITIES);
    }

    private static long getLongVersionCode(PackageInfo packageInfo) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;
    }

    private static String normalizeSha256(String value) {
        if (value == null || !value.matches("(?i)^[0-9a-f]{64}$")) return null;
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean isTrustedGithubUrl(String value) {
        return isTrustedGithubAssetUrl(value);
    }

    private static boolean isTrustedGithubJsonUrl(String value) {
        return isLatestGithubApiUrl(value) || isTrustedGithubAssetUrl(value);
    }

    private static boolean isTrustedGithubAssetUrl(String value) {
        try {
            URL url = new URL(value);
            return isTrustedHttpsGithubUrl(url)
                    && (GITHUB_HOST.equals(url.getHost().toLowerCase(Locale.ROOT))
                    || GITHUB_OBJECTS_HOST.equals(url.getHost().toLowerCase(Locale.ROOT))
                    || GITHUB_RELEASE_ASSETS_HOST.equals(url.getHost().toLowerCase(Locale.ROOT)));
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean isLatestGithubApiUrl(String value) {
        try {
            URL url = new URL(value);
            return isTrustedHttpsGithubUrl(url)
                    && GITHUB_API_HOST.equals(url.getHost().toLowerCase(Locale.ROOT))
                    && LATEST_RELEASE_PATH.equals(url.getPath())
                    && (url.getQuery() == null || url.getQuery().isEmpty());
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean isTrustedHttpsGithubUrl(URL url) {
        int port = url.getPort();
        return "https".equalsIgnoreCase(url.getProtocol())
                && url.getUserInfo() == null
                && (port == -1 || port == 443);
    }

    private static String sha256ForFile(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return builder.toString();
    }

    private static void clearFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            // 后续调用会因文件仍存在而失败/清理，不能把失败静默为可安装状态。
        }
    }

    private void notifyProgress(long transferred, long total) {
        JSObject progress = new JSObject();
        progress.put("transferred", transferred);
        progress.put("total", total > 0 ? total : 0);
        progress.put("percent", total > 0 ? Math.min(100, (int) ((transferred * 100) / total)) : 0);
        notifyListeners("updateDownloadProgress", progress);
    }

    private static String readableNetworkError(IOException error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "网络或存储不可用" : message;
    }

    private static final class DownloadRequest {
        final String url;
        final String sha256;
        final int versionCode;
        final String versionName;

        DownloadRequest(String url, String sha256, int versionCode, String versionName) {
            this.url = url;
            this.sha256 = sha256;
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }

    private static final class DownloadedUpdate {
        final File file;
        final int versionCode;
        final String versionName;
        final String sha256;
        final String url;

        DownloadedUpdate(File file, int versionCode, String versionName, String sha256, String url) {
            this.file = file;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.sha256 = sha256;
            this.url = url;
        }

        JSObject toJson() {
            JSObject result = new JSObject();
            result.put("versionCode", versionCode);
            result.put("versionName", versionName);
            result.put("sha256", sha256);
            result.put("url", url);
            result.put("apkPath", file.getAbsolutePath());
            return result;
        }
    }

    private static final class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }
    }
}
