package com.kousoyu.drift.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.net.URLDecoder
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kousoyu.drift.data.local.DriftDatabase
sealed class ReaderState {
    object Loading : ReaderState()
    data class Success(val images: List<String>, val headers: Map<String, String> = emptyMap()) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = DriftDatabase.getDatabase(app).mangaDao()

    var state by mutableStateOf<ReaderState>(ReaderState.Loading)
        private set

    fun loadChapter(urlEncoded: String, mangaUrlEncoded: String = "", chapterNameEncoded: String = "", sourceNameEncoded: String = "") {
        viewModelScope.launch {
            state = ReaderState.Loading
            try {
                val url = URLDecoder.decode(urlEncoded, "UTF-8")
                val explicitSourceName = if (sourceNameEncoded.isNotEmpty()) URLDecoder.decode(sourceNameEncoded, "UTF-8") else ""
                
                if (mangaUrlEncoded.isNotEmpty() && chapterNameEncoded.isNotEmpty()) {
                    val mangaUrl = URLDecoder.decode(mangaUrlEncoded, "UTF-8")
                    val chapterName = URLDecoder.decode(chapterNameEncoded, "UTF-8")
                    
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            dao.updateReadingProgressSync(mangaUrl, chapterName, url)
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                }
                
                // Fallback to active source if mangaUrl is empty, otherwise grab manga source
                val targetSource = if (explicitSourceName.isNotEmpty()) {
                    SourceManager.getSourceByName(explicitSourceName)
                } else if (mangaUrlEncoded.isNotEmpty()) {
                    val mUrl = URLDecoder.decode(mangaUrlEncoded, "UTF-8")
                    dao.getMangaByUrlSync(mUrl)?.sourceName?.let { SourceManager.getSourceByName(it) } ?: SourceManager.currentSource.value
                } else {
                    SourceManager.currentSource.value
                }
                
                targetSource.getChapterImages(url)
                    .onSuccess { state = ReaderState.Success(it, targetSource.getHeaders()) }
                    .onFailure { state = ReaderState.Error(it.message ?: "Failed to load chapter images") }
            } catch (e: Exception) {
                state = ReaderState.Error(e.message ?: "Invalid URL")
            }
        }
    }
}
