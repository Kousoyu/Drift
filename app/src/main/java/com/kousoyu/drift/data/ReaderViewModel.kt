package com.kousoyu.drift.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.net.URLDecoder
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kousoyu.drift.data.local.DriftDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

sealed class ReaderState {
    object Loading : ReaderState()
    data class Success(
        val images: List<String>,
        val headers: Map<String, String> = emptyMap(),
        val chapters: List<MangaChapter> = emptyList(),
        val currentIndex: Int = -1,
        val initialPage: Int = 0
    ) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = DriftDatabase.getDatabase(app).mangaDao()

    var state by mutableStateOf<ReaderState>(ReaderState.Loading)
        private set

    // Chapter navigation context
    private var chapters: List<MangaChapter> = emptyList()
    private var currentChapterIndex: Int = -1
    private var mangaUrl: String = ""
    private var sourceName: String = ""

    // Debounced page saving
    private var pageSaveJob: Job? = null

    fun loadChapter(
        urlEncoded: String,
        mangaUrlEncoded: String = "",
        chapterNameEncoded: String = "",
        sourceNameEncoded: String = "",
        chapterList: List<MangaChapter> = emptyList()
    ) {
        viewModelScope.launch {
            state = ReaderState.Loading
            try {
                val url = URLDecoder.decode(urlEncoded, "UTF-8")
                val explicitSource = if (sourceNameEncoded.isNotEmpty()) URLDecoder.decode(sourceNameEncoded, "UTF-8") else ""

                if (mangaUrlEncoded.isNotEmpty()) mangaUrl = URLDecoder.decode(mangaUrlEncoded, "UTF-8")
                if (explicitSource.isNotEmpty()) sourceName = explicitSource

                // Store chapter list for navigation
                if (chapterList.isNotEmpty()) chapters = chapterList
                currentChapterIndex = chapters.indexOfFirst { it.url == url }.takeIf { it >= 0 }
                    ?: chapters.indexOfFirst { url.endsWith(it.url) }.takeIf { it >= 0 }
                    ?: -1

                // Save reading progress (chapter level)
                if (mangaUrl.isNotEmpty() && chapterNameEncoded.isNotEmpty()) {
                    val chapterName = URLDecoder.decode(chapterNameEncoded, "UTF-8")
                    launch(Dispatchers.IO) {
                        runCatching { dao.updateReadingProgressSync(mangaUrl, chapterName, url) }
                    }
                }

                // Resolve source (ALL DB access on IO thread)
                val targetSource = withContext(Dispatchers.IO) {
                    when {
                        explicitSource.isNotEmpty() -> SourceManager.getSourceByName(explicitSource)
                        mangaUrl.isNotEmpty() -> dao.getMangaByUrlSync(mangaUrl)?.sourceName
                            ?.let { SourceManager.getSourceByName(it) } ?: SourceManager.currentSource.value
                        else -> SourceManager.currentSource.value
                    }
                }

                // Load initial page from DB
                val savedPage = withContext(Dispatchers.IO) {
                    if (mangaUrl.isNotEmpty()) dao.getMangaByUrlSync(mangaUrl)?.lastReadPage ?: 0 else 0
                }

                targetSource.getChapterImages(url)
                    .onSuccess { images ->
                        state = ReaderState.Success(
                            images = images,
                            headers = targetSource.getHeaders(),
                            chapters = chapters,
                            currentIndex = currentChapterIndex,
                            initialPage = savedPage
                        )
                    }
                    .onFailure { state = ReaderState.Error(it.message ?: "加载失败") }
            } catch (e: Exception) {
                state = ReaderState.Error(e.message ?: "无效URL")
            }
        }
    }

    /** Navigate to adjacent chapter. delta: -1 = prev, +1 = next */
    fun navigateChapter(delta: Int) {
        val newIndex = currentChapterIndex + delta
        if (newIndex !in chapters.indices) return
        val ch = chapters[newIndex]

        // Reset page to 0 for new chapter
        if (mangaUrl.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { dao.updateReadingPageSync(mangaUrl, 0) }
            }
        }

        loadChapter(
            urlEncoded = java.net.URLEncoder.encode(ch.url, "UTF-8"),
            mangaUrlEncoded = java.net.URLEncoder.encode(mangaUrl, "UTF-8"),
            chapterNameEncoded = java.net.URLEncoder.encode(ch.name, "UTF-8"),
            sourceNameEncoded = java.net.URLEncoder.encode(sourceName, "UTF-8"),
            chapterList = chapters
        )
    }

    val hasPrevChapter: Boolean get() = currentChapterIndex > 0
    val hasNextChapter: Boolean get() = currentChapterIndex in 0 until chapters.size - 1

    /** Debounced save of scroll position (called on every scroll, saves after 500ms idle). */
    fun onPageChanged(page: Int) {
        if (mangaUrl.isEmpty()) return
        pageSaveJob?.cancel()
        pageSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            runCatching { dao.updateReadingPageSync(mangaUrl, page) }
        }
    }
}
