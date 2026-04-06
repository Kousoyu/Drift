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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

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
        // TODO: 替换成你的 GitHub 用户名和仓库名
        const val GITHUB_OWNER = "kousoyu"
        const val GITHUB_REPO  = "Drift"
        
        private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
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

    private val client = OkHttpClient()

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

            // Parse remote version code from tag: "v2" → 2, "v1.1" → 11, etc.
            val remoteVersionCode = parseVersionCode(tagName)
            val remoteVersionName = tagName.removePrefix("v")
            
            // Get current version code from package info
            val currentVersionCode = getCurrentVersionCode()

            if (remoteVersionCode > currentVersionCode) {
                _updateState.value = UpdateState.Available(
                    UpdateInfo(
                        versionName = remoteVersionName,
                        versionCode = remoteVersionCode,
                        changelog   = body,
                        downloadUrl = downloadUrl,
                        fileSize    = fileSize
                    )
                )
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

        // Clean old APK files
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        downloadDir?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }

        val fileName = "Drift-v${info.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
            setTitle("Drift 更新 v${info.versionName}")
            setDescription("正在下载新版本...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            // GitHub may redirect, allow both HTTP and HTTPS
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // Register receiver to trigger install when download completes
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    val apkFile = File(downloadDir, fileName)
                    if (apkFile.exists()) {
                        installApk(ctx, apkFile)
                        _updateState.value = UpdateState.Idle
                    } else {
                        _updateState.value = UpdateState.Error("下载文件不存在")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * Trigger the system APK installer via FileProvider.
     */
    private fun installApk(context: Context, apkFile: File) {
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
     * Parse version code from tag name.
     * "v2" → 2, "v1.1" → 11, "v2.3.1" → 231
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
}
