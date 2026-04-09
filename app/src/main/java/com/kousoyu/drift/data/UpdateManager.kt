package com.kousoyu.drift.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App Update Manager — checks GitHub Releases for new versions and handles
 * APK download + install via the system DownloadManager.
 *
 * Architecture:
 *   1. [checkUpdate] hits the GitHub Releases API for the latest release
 *   2. Compares remote versionCode (parsed from tag "v{code}") with current
 *   3. If newer, exposes [updateState] = Available(info)
 *   4. [downloadAndInstall] enqueues a DownloadManager request, then triggers install
 *
 * IMPORTANT: Change [GITHUB_OWNER] and [GITHUB_REPO] to your actual repository
 * when you create one. For now, it uses a placeholder that you'll update.
 */
class UpdateManager(private val context: Context) {

    // ─── Configure these when you create your GitHub repo ────────────────────
    companion object {
        const val GITHUB_OWNER = "kousoyu"
        const val GITHUB_REPO  = "Drift"

        private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        // GitHub CDN 多代理 fallback（按优先级尝试，最后直连兜底）
        private val DOWNLOAD_PROXIES = listOf(
            "https://gh-proxy.com/",
            "https://mirror.ghproxy.com/",
            ""  // 空字符串 = 直连 GitHub（无代理）
        )
    }

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val changelog: String,
        val downloadUrl: String,
        val fileSize: Long      // bytes, 0 if unknown
    )

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class Available(val info: UpdateInfo) : UpdateState()
        data object UpToDate : UpdateState()
        data class Downloading(val progress: Int) : UpdateState()   // 0-100
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    /** Retains the UpdateInfo across state transitions (Available → Downloading → Error). */
    var lastUpdateInfo: UpdateInfo? = null
        private set

    private val client get() = DriftHttpClient.get()
    private var progressJob: Job? = null
    private var downloadReceiver: BroadcastReceiver? = null

    /**
     * Check GitHub Releases API for the latest version.
     * Call this from a coroutine scope (e.g. in ViewModel or LaunchedEffect).
     */
    suspend fun checkUpdate() {
        _updateState.value = UpdateState.Checking
        try {
            val result = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("GitHub API: HTTP ${response.code}")
                }
                response.body!!.string()
            }

            val json = JSONObject(result)
            val tagName = json.getString("tag_name")             // e.g. "v2" or "v1.1"
            val body    = json.optString("body", "Bug fixes and improvements.")
            val assets  = json.getJSONArray("assets")

            // Find the .apk asset
            var downloadUrl = ""
            var fileSize = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    fileSize = asset.optLong("size", 0)
                    break
                }
            }

            if (downloadUrl.isEmpty()) {
                _updateState.value = UpdateState.Error("Release 中没有找到 APK 文件")
                return
            }

            // Parse remote version for semantic comparison
            val remoteVersionName = tagName.removePrefix("v").removePrefix("V")
            val currentVersionName = getCurrentVersionName()

            if (isNewerVersion(remoteVersionName, currentVersionName)) {
                val updateInfo = UpdateInfo(
                    versionName = remoteVersionName,
                    versionCode = parseVersionCode(tagName),
                    changelog   = body,
                    downloadUrl = downloadUrl,
                    fileSize    = fileSize
                )
                lastUpdateInfo = updateInfo
                _updateState.value = UpdateState.Available(updateInfo)
            } else {
                _updateState.value = UpdateState.UpToDate
            }
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error(e.message ?: "检查更新失败")
        }
    }

    /**
     * Download APK via system DownloadManager and trigger install when done.
     */
    fun downloadAndInstall(info: UpdateInfo) {
        _updateState.value = UpdateState.Downloading(0)

        // Run proxy resolution + download enqueue on IO thread
        CoroutineScope(Dispatchers.IO).launch {
            // Clean old APK files
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadDir?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }

            val fileName = "Drift-v${info.versionName}.apk"
            // 多代理 fallback：使用第一个可用的代理，最后直连
            val proxyUrl = resolveDownloadUrl(info.downloadUrl)
            val request = DownloadManager.Request(Uri.parse(proxyUrl)).apply {
                setTitle("Drift 更新 v${info.versionName}")
                setDescription("正在下载新版本...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            // ── 轮询下载进度 ──
            progressJob?.cancel()
            progressJob = launch {
                while (isActive) {
                    delay(300)
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val downloaded = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
                        val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0L
                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else 0
                        cursor.close()

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            break
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            _updateState.value = UpdateState.Error("下载失败，请重试")
                            break
                        }
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            _updateState.value = UpdateState.Downloading(pct)
                        }
                    } else {
                        cursor?.close()
                        break
                    }
                }
            }

            // Unregister any previous receiver to prevent leaks
            try { downloadReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}

            // Register receiver to trigger install when download completes
            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id != downloadId) return

                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    downloadReceiver = null
                    progressJob?.cancel()

                    // ── 检查下载状态 ──
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                        cursor.close()

                        if (status != DownloadManager.STATUS_SUCCESSFUL) {
                            _updateState.value = UpdateState.Error("下载失败，请重试")
                            return
                        }
                    }

                    val apkFile = File(downloadDir, fileName)
                    if (!apkFile.exists() || apkFile.length() < 1024) {
                        _updateState.value = UpdateState.Error("下载文件无效，请重试")
                        return
                    }

                    // ── 检查安装权限 ──
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (!ctx.packageManager.canRequestPackageInstalls()) {
                            try {
                                val settingsIntent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${ctx.packageName}")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                ctx.startActivity(settingsIntent)
                                _updateState.value = UpdateState.Error("请允许安装权限后重试")
                            } catch (_: Exception) {
                                _updateState.value = UpdateState.Error("请在设置中允许安装未知来源应用")
                            }
                            return
                        }
                    }

                    installApk(ctx, apkFile)
                    _updateState.value = UpdateState.Idle
                }
            }

            // registerReceiver must run on main thread
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        downloadReceiver,
                        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    context.registerReceiver(
                        downloadReceiver,
                        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                    )
                }
            }
        } // end CoroutineScope
    }

    /**
     * Trigger the system APK installer via FileProvider.
     */
    private fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("安装失败: ${e.message}")
        }
    }

    fun dismiss() {
        _updateState.value = UpdateState.Idle
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1  // fallback
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /**
     * Parse version code from tag name (fallback only, not used for comparison).
     */
    private fun parseVersionCode(tag: String): Int {
        val clean = tag.removePrefix("v").removePrefix("V")
        val parts = clean.split(".")
        return try {
            when (parts.size) {
                1 -> parts[0].toInt()
                2 -> parts[0].toInt() * 10 + parts[1].toInt()
                3 -> parts[0].toInt() * 100 + parts[1].toInt() * 10 + parts[2].toInt()
                else -> parts[0].toInt()
            }
        } catch (e: NumberFormatException) {
            0
        }
    }

    /**
     * Semantic version comparison: "1.3" > "1.2", "2.0" > "1.9"
     * Compares each segment numerically, left to right.
     */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false  // equal
    }

    /**
     * Resolve download URL with proxy fallback.
     * Tries each proxy in order; falls back to direct GitHub URL.
     */
    private fun resolveDownloadUrl(originalUrl: String): String {
        if (!originalUrl.contains("github.com")) return originalUrl
        for (proxy in DOWNLOAD_PROXIES) {
            val testUrl = if (proxy.isEmpty()) originalUrl else proxy + originalUrl
            try {
                val req = Request.Builder().url(testUrl).head().build()
                val resp = client.newCall(req).execute()
                resp.close()
                if (resp.isSuccessful || resp.code in 301..302) {
                    return testUrl
                }
            } catch (_: Exception) { /* try next */ }
        }
        return originalUrl  // all proxies failed, use direct URL
    }
}
