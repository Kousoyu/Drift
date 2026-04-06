package com.kousoyu.drift.data

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import com.kousoyu.drift.data.sources.ManhuaguiSource
import com.kousoyu.drift.data.sources.CopyMangaSource
import com.kousoyu.drift.data.sources.BaoziNativeSource
import java.util.concurrent.TimeUnit

/**
 * Central registry of all manga sources.
 *
 * Architecture: Pure static. No network calls, no OTA, no dynamic loading.
 * Every source is a hardcoded native plugin — this guarantees stability.
 */
object SourceManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // ── Native plugins ──────────────────────────────────────────────────────
    // CopyMangaSource   : JSON API + AES-CBC decryption (self-healing key)
    // BaoziNativeSource  : HTML scraping with mirror fallback + CDN images
    // ManhuaguiSource   : LZString + JS unpacking for chapter images
    private val copyManga  = CopyMangaSource(client)
    private val baozi      = BaoziNativeSource(client)
    private val manhuagui  = ManhuaguiSource(client)

    // ── Aggregate (virtual) ─────────────────────────────────────────────────
    private val aggregate = AggregateSource()

    // ── Public ──────────────────────────────────────────────────────────────
    val sources: List<MangaSource> = listOf(aggregate, copyManga, baozi, manhuagui)
    val currentSource = MutableStateFlow<MangaSource>(aggregate)

    fun getSourceByName(name: String): MangaSource =
        sources.find { it.name == name } ?: aggregate
}
