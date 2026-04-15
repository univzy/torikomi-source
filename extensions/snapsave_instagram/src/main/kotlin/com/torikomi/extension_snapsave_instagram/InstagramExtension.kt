package com.torikomi.extension_snapsave_instagram

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.brotli.dec.BrotliInputStream
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class InstagramExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        private const val API_BASE = "https://snapsave.app"

        @JvmStatic
        fun getInstance(): IExtension = InstagramExtension()
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 30_000,
        writeTimeoutMs = 30_000
    )
        .addInterceptor { chain ->
            val original = chain.request()
            val requestWithHeaders = original.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build()
            chain.proceed(requestWithHeaders)
        }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val contentEncoding = response.header("Content-Encoding").orEmpty()
            
            if (contentEncoding.equals("br", ignoreCase = true)) {
                return@addNetworkInterceptor try {
                    val responsebody = response.body ?: return@addNetworkInterceptor response
                    val compressedBytes = responsebody.bytes()
                    
                    // Decompress Brotli using BrotliInputStream
                    val decompressedBytes = ByteArrayOutputStream()
                    BrotliInputStream(compressedBytes.inputStream()).use { brStream ->
                        brStream.copyTo(decompressedBytes)
                    }
                    val decompressed = decompressedBytes.toByteArray()
                    
                    Log.d("INSTAGRAM", "Brotli decompressed: ${compressedBytes.size} → ${decompressed.size} bytes")
                    
                    response.newBuilder()
                        .body(decompressed.toResponseBody(responsebody.contentType()))
                        .removeHeader("Content-Encoding")
                        .build()
                } catch (e: Exception) {
                    Log.e("INSTAGRAM", "Brotli decompression failed: ${e.message}", e)
                    response
                }
            }
            response
        }
        .build()
    private val gson = Gson()

    override fun getId(): String = "snapsave_instagram"

    override fun getPlatformId(): String = "instagram"

    override fun getPlatformName(): String = "Instagram"

    override fun getVersion(): String = "1.0.0"

    override fun getDownloaderName(): String = "SnapSave"

    override fun getDownloaderDescription(): String =
        "SnapSave downloader for quickly downloading Instagram videos and photos."

    override fun canHandle(url: String): Boolean =
        url.contains("instagram.com") || url.contains("instagr.am")

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeInstagram(url, cfCookies)
        } catch (e: Exception) {
            errorJson("SnapSave Instagram download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeInstagram(url: String, cfCookies: String?): String {
        val formBody = FormBody.Builder()
            .add("url", url)
            .build()

        val request = Request.Builder()
            .url("$API_BASE/id/action.php?lang=id")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", API_BASE)
            .header("Referer", "$API_BASE/id")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                if (!cfCookies.isNullOrBlank()) {
                    header("Cookie", cfCookies)
                }
            }
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                Log.e("INSTAGRAM", "scrapeInstagram FAILED - Status: ${response.code} ${response.message} - URL: $API_BASE/id/action.php - Body: ${errorBody.take(500)}")
                throw IllegalStateException("API returned status ${response.code} - ${response.message}")
            }

            val encrypted = response.body?.string().orEmpty()
            if (encrypted.isEmpty()) {
                Log.e("INSTAGRAM", "scrapeInstagram - Empty response body from API")
                throw IllegalStateException("Empty response from API")
            }
            
            Log.d("INSTAGRAM", "scrapeInstagram - Got encrypted response, decrypting...")
            val html = decryptResponse(encrypted)
            if (html.isBlank()) {
                Log.e("INSTAGRAM", "scrapeInstagram - Failed to decrypt response")
                throw IllegalStateException("Failed to decrypt response")
            }

            Log.d("INSTAGRAM", "scrapeInstagram - SUCCESS, parsing data...")
            return parseInstagramData(html)
        }
    }

    private fun decryptResponse(encryptedData: String): String {
        val params = getEncodedParams(encryptedData)
        if (params.isEmpty()) return ""

        val decoded = decodeSnapApp(params)
        if (decoded.isEmpty()) return ""

        return getDecodedSnapSave(decoded)
    }

    private fun getEncodedParams(data: String): List<String> {
        return runCatching {
            val parts = data.split("decodeURIComponent(escape(r))}(")
            if (parts.size < 2) return emptyList()

            val paramString = parts[1].split("))")[0]
            paramString.split(",").map { it.replace("\"", "").trim() }
        }.getOrElse { emptyList() }
    }

    private fun decodeSnapApp(args: List<String>): String {
        if (args.size < 6) return ""

        val t = args[0]
        val o = args[2]
        val b = args[3].toIntOrNull() ?: 0
        val z = args[4].toIntOrNull() ?: 0
        if (z <= 0 || z > o.length) return ""

        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val baseChars = alphabet.substring(0, z).toList()

        val result = StringBuilder()
        var i = 0

        while (i < t.length) {
            var s = ""
            while (i < t.length && t[i] != o[z]) {
                s += t[i]
                i++
            }
            i++

            for (j in o.indices) {
                s = s.replace(o[j].toString(), j.toString())
            }

            val decoded = decodeBase(s, z, baseChars)
            val charCode = decoded - b
            if (charCode > 0) {
                result.append(charCode.toChar())
            }
        }

        return result.toString()
    }

    private fun decodeBase(value: String, base: Int, baseChars: List<Char>): Int {
        val reversed = value.reversed()
        var output = 0

        for (index in reversed.indices) {
            val digit = baseChars.indexOf(reversed[index])
            if (digit != -1) {
                output += digit * pow(base, index)
            }
        }

        return output
    }

    private fun getDecodedSnapSave(data: String): String {
        val errorParts = data.split("document.querySelector(\"#alert\").innerHTML = \"")
        if (errorParts.size > 1) {
            val errorMsg = errorParts[1].split("\";")[0].trim()
            if (errorMsg.isNotEmpty()) {
                throw IllegalStateException("API Error: $errorMsg")
            }
        }

        val parts = data.split("getElementById(\"download-section\").innerHTML = \"")
        if (parts.size < 2) return ""

        return parts[1]
            .split("\"; document.getElementById(\"inputData\").remove(); ")[0]
            .replace("\\", "")
    }

    private fun parseInstagramData(htmlContent: String): String {
        val doc = Jsoup.parse(htmlContent)

        val parsed = when {
            doc.selectFirst("table.table") != null -> parseTableLayout(doc)
            doc.selectFirst("div.download-items") != null -> parseDownloadItemsLayout(doc)
            doc.selectFirst("div.card") != null -> parseCardLayout(doc)
            else -> parseSingleLayout(doc)
        }

        val downloadItems = mutableListOf<Map<String, String>>()
        if (parsed.videoUrl.isNotBlank()) {
            downloadItems += mapOf(
                "key" to "video",
                "label" to "Video",
                "type" to "video",
                "url" to parsed.videoUrl,
                "mimeType" to "video/mp4",
                "quality" to "Original"
            )
        }

        return gson.toJson(
            mapOf(
                "extensionId" to "instagram",
                "platform" to "instagram",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to "",
                "author" to "",
                "authorName" to "",
                "duration" to 0,
                "thumbnail" to parsed.thumbnail,
                "downloadItems" to downloadItems,
                "playCount" to 0,
                "diggCount" to 0,
                "commentCount" to 0,
                "shareCount" to 0,
                "downloadCount" to 0,
                "images" to parsed.images
            )
        )
    }

    private fun parseTableLayout(doc: org.jsoup.nodes.Document): ParsedMedia {
        val rows = doc.select("table.table tbody tr")
        if (rows.isEmpty()) throw IllegalStateException("No table rows found")

        val thumb = doc.selectFirst("article.media > figure img")?.attr("src").orEmpty()
        val firstRow = rows.firstOrNull() ?: throw IllegalStateException("No table rows found")
        val cells = firstRow.select("td")
        if (cells.size < 3) throw IllegalStateException("Failed to parse table layout")

        val button = cells[2].selectFirst("button")
        var videoUrl = button?.attr("onclick").orEmpty()

        if (videoUrl.contains("get_progressApi")) {
            val match = Regex("get_progressApi\\('(.+?)'\\)").find(videoUrl)
            if (match != null) {
                videoUrl = "$API_BASE${match.groupValues[1]}"
            }
        }

        if (videoUrl.isBlank()) {
            videoUrl = button?.attr("href").orEmpty()
        }
        if (videoUrl.isBlank()) {
            videoUrl = cells[2].selectFirst("a")?.attr("href").orEmpty()
        }
        if (videoUrl.isBlank()) throw IllegalStateException("Video URL not found")

        return ParsedMedia(videoUrl = videoUrl, thumbnail = thumb)
    }

    private fun parseCardLayout(doc: org.jsoup.nodes.Document): ParsedMedia {
        val firstCard = doc.selectFirst("div.card") ?: throw IllegalStateException("No cards found")
        val link = firstCard.selectFirst("div.card-body a")
        val linkText = link?.text()?.trim().orEmpty().lowercase()
        val url = link?.attr("href").orEmpty()
        if (url.isBlank()) throw IllegalStateException("No URL found in card")

        return if (linkText.contains("photo")) {
            ParsedMedia(images = listOf(url))
        } else {
            ParsedMedia(videoUrl = url)
        }
    }

    private fun parseDownloadItemsLayout(doc: org.jsoup.nodes.Document): ParsedMedia {
        val items = doc.select("div.download-items")
        if (items.isEmpty()) throw IllegalStateException("No download items found")

        val first = items.firstOrNull() ?: throw IllegalStateException("No download items found")
        val videoEl = first.selectFirst("video")
        val btnWrap = first.selectFirst("div.download-items__btn")
        val spanText = btnWrap?.selectFirst("span")?.text()?.trim().orEmpty().lowercase()
        val isVideo = videoEl != null || spanText.contains("video")

        return if (isVideo) {
            val thumb = first.selectFirst("div.download-items__thumb > img")?.attr("src")
                ?: videoEl?.attr("poster")
                ?: ""
            val videoUrl = btnWrap?.selectFirst("a")?.attr("href").orEmpty()
            if (videoUrl.isBlank()) throw IllegalStateException("Video URL not found")
            ParsedMedia(videoUrl = videoUrl, thumbnail = thumb)
        } else {
            val images = items.mapNotNull { item ->
                val imageUrl = item.selectFirst("div.download-items__thumb > img")?.attr("src")
                    ?: item.selectFirst("div.download-items__btn a")?.attr("href")
                imageUrl?.takeIf { it.isNotBlank() && !it.contains(".mp4") }
            }
            if (images.isEmpty()) throw IllegalStateException("No images found")
            ParsedMedia(images = images)
        }
    }

    private fun parseSingleLayout(doc: org.jsoup.nodes.Document): ParsedMedia {
        val link = doc.selectFirst("a")
        val button = doc.selectFirst("button")
        val linkText = link?.text()?.trim().orEmpty().lowercase()

        var url = link?.attr("href").orEmpty()
        if (url.isBlank()) {
            val onclick = button?.attr("onclick").orEmpty()
            if (onclick.contains("get_progressApi")) {
                val match = Regex("get_progressApi\\('(.+?)'\\)").find(onclick)
                if (match != null) {
                    url = "$API_BASE${match.groupValues[1]}"
                }
            }
        }

        if (url.isBlank()) throw IllegalStateException("No download URL found")

        return if (linkText.contains("photo")) {
            ParsedMedia(images = listOf(url))
        } else {
            ParsedMedia(videoUrl = url)
        }
    }

    private fun pow(base: Int, exp: Int): Int {
        var result = 1
        repeat(exp) { result *= base }
        return result
    }

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }

    private data class ParsedMedia(
        val videoUrl: String = "",
        val thumbnail: String = "",
        val images: List<String> = emptyList()
    )
}
