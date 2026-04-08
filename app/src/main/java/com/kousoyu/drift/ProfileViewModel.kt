package com.kousoyu.drift

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kousoyu.drift.data.local.DriftDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = DriftDatabase.getDatabase(application).mangaDao()
    private val novelDao = DriftDatabase.getDatabase(application).novelDao()

    // ── Real-time favorite count from Room ──────────────────────────────────
    val favoriteCount: StateFlow<Int> = dao.getFavoriteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val novelFavoriteCount: StateFlow<Int> = novelDao.getFavoriteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ── Cache size (formatted string) ───────────────────────────────────────
    private val _cacheSize = MutableStateFlow("…")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val totalBytes = dirSize(ctx.cacheDir)
            _cacheSize.value = formatSize(totalBytes)
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            // 清理 cacheDir 下的所有子目录和文件
            ctx.cacheDir.listFiles()?.forEach { deleteDir(it) }
            refreshCacheSize()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) size += f.length() }
        return size
    }

    private fun deleteDir(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach { it.delete() }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f G", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024        -> String.format("%.0f M", bytes / (1024.0 * 1024))
        bytes >= 1024L               -> String.format("%.0f K", bytes / 1024.0)
        else                         -> "$bytes B"
    }
}
