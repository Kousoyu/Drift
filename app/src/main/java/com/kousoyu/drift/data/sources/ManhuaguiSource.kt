package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import com.kousoyu.drift.utils.LZString
import com.kousoyu.drift.utils.Unpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

class ManhuaguiSource(private val client: OkHttpClient) : MangaSource {
    override val name: String = "漫画柜 (Native)"
    override val baseUrl: String = "https://tw.manhuagui.com"
    private val imageServer = arrayOf("https://i.hamreus.com", "https://cf.hamreus.com")

    override fun getHeaders(): Map<String, String> {
        return mapOf(
            "Referer" to baseUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    }

    private fun getRequest(url: String): Request {
        val builder = okhttp3.Headers.Builder()
        getHeaders().forEach { builder.add(it.key, it.value) }
        return Request.Builder().url(url).headers(builder.build()).build()
    }

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(getRequest("$baseUrl/list/view_p1.html")).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP \${response.code}"))
            
            val doc = Jsoup.parse(response.body?.string() ?: "")
            val items = doc.select("ul#contList > li")
            val mangas = items.mapNotNull { item ->
                val a = item.select("a.bcover").first() ?: return@mapNotNull null
                val title = a.attr("title").trim()
                val url = a.attr("href")
                
                val img = a.select("img").first()
                val coverUrl = if (img?.hasAttr("src") == true) img.attr("abs:src") else img?.attr("abs:data-src") ?: ""
                
                Manga(
                    title = title,
                    coverUrl = coverUrl,
                    detailUrl = url,
                    sourceName = name
                )
            }
            Result.success(mangas)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(getRequest("$baseUrl/s/\${query}_p1.html")).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP \${response.code}"))
            
            val doc = Jsoup.parse(response.body?.string() ?: "")
            val items = doc.select("div.book-result > ul > li")
            val mangas = items.mapNotNull { item ->
                val detail = item.select("div.book-detail").first() ?: return@mapNotNull null
                val a = detail.select("dl > dt > a").first() ?: return@mapNotNull null
                val title = a.attr("title").trim()
                val url = a.attr("href")
                val coverUrl = item.select("div.book-cover > a.bcover > img").first()?.attr("abs:src") ?: ""
                
                Manga(
                    title = title,
                    coverUrl = coverUrl,
                    detailUrl = url,
                    sourceName = name
                )
            }
            Result.success(mangas)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (detailUrl.startsWith("http")) detailUrl else "$baseUrl$detailUrl"
            val response = client.newCall(getRequest(fullUrl)).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP \${response.code}"))
            
            val doc = Jsoup.parse(response.body?.string() ?: "", fullUrl)
            val title = doc.select("div.book-title > h1:nth-child(1)").text().trim()
            val coverUrl = doc.select("p.hcover > img").attr("abs:src")
            val author = doc.select("span:contains(漫画作者) > a , span:contains(漫畫作者) > a").text().trim().replace(" ", ", ")
            val description = doc.select("div#intro-all").text().trim()
            val statusStr = doc.select("div.book-detail > ul.detail-list > li.status > span > span").first()?.text() ?: ""
            
            // Decrypt R18 hidden chapters if needed
            val hiddenEncryptedChapterList = doc.select("#__VIEWSTATE").first()
            if (hiddenEncryptedChapterList != null) {
                val decodedHiddenChapterList = LZString.decompressFromBase64(hiddenEncryptedChapterList.`val`())
                if (decodedHiddenChapterList != null) {
                    val hiddenChapterList = Jsoup.parse(decodedHiddenChapterList, fullUrl)
                    doc.select("#erroraudit_show").first()?.replaceWith(hiddenChapterList)
                }
            }

            val chapters = mutableListOf<MangaChapter>()
            val sectionList = doc.select("[id^=chapter-list-]")
            sectionList.forEach { section ->
                val pageList = section.select("ul")
                pageList.reverse()
                pageList.forEach { page ->
                    val chapterList = page.select("li > a.status0")
                    chapterList.forEach {
                        val cUrl = it.attr("href")
                        val cName = it.attr("title").trim().ifEmpty { it.select("span").first()?.ownText() ?: "" }
                        chapters.add(MangaChapter(cName, cUrl))
                    }
                }
            }
            
            val detail = MangaDetail(
                url = fullUrl,
                title = title,
                coverUrl = coverUrl,
                author = author,
                description = description,
                status = statusStr,
                chapters = chapters
            )
            Result.success(detail)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val packedRegex = Regex("""window\[".*?"\](\(.*\)\s*\{[\s\S]+\}\s*\(.*\))""")
    private val packedContentRegex = Regex("""['"]([0-9A-Za-z+/=]+)['"]\[['"].*?['"]]\(['"].*?['"]\)""")
    private val singleQuoteRegex = Regex("""\\'""")
    private val blockCcArgRegex = Regex("""\{.*\}""")

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl"
            val response = client.newCall(getRequest(fullUrl)).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP \${response.code}"))
            
            val html = response.body?.string() ?: ""
            val packedMatch = packedRegex.find(html)
                ?: return@withContext Result.failure(Exception("Cannot find packed JS code"))
                
            val imgCode = packedMatch.groupValues[1].let { str ->
                str.replace(packedContentRegex) { match ->
                    val lzs = match.groupValues[1]
                    val decoded = LZString.decompressFromBase64(lzs)
                    "'\$decoded'.split('|')"
                }
            }
            
            val imgDecode = Unpacker.unpack(singleQuoteRegex.replace(imgCode, "-"))
            val imgJsonStrMatch = blockCcArgRegex.find(imgDecode)
                ?: return@withContext Result.failure(Exception("Cannot find decoded JSON arguments"))
                
            val imgJsonStr = imgJsonStrMatch.groupValues[0]
            val jsonObj = JSONObject(imgJsonStr)
            
            val files = jsonObj.getJSONArray("files")
            val path = jsonObj.getString("path")
            val sl = jsonObj.getJSONObject("sl")
            val sl_e = sl.getString("e")
            val sl_m = sl.getString("m")
            
            val urls = mutableListOf<String>()
            for (i in 0 until files.length()) {
                val fileStr = files.getString(i)
                val imgUrl = "\${imageServer[0]}\$path\$fileStr?e=\$sl_e&m=\$sl_m"
                urls.add(imgUrl)
            }
            
            Result.success(urls)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
