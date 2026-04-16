package com.torikomi.extension_snapsave_threads

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

class ThreadsExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        private const val API_BASE = "https://threads.snapsave.app"

        @JvmStatic
        fun getInstance(): IExtension = ThreadsExtension()
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 30_000,
        writeTimeoutMs = 30_000
    )
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/html, */*")
                .build()
            chain.proceed(req)
        }
        .build()
    private val gson = Gson()

    override fun getId(): String = "snapsave_threads"

    override fun getPlatformId(): String = "threads"

    override fun getPlatformName(): String = "Threads"

    override fun getVersion(): String = "1.0.0"

    override fun getDownloaderName(): String = "SnapSave"

    override fun getDownloaderDescription(): String =
        "SnapSave downloader for Threads videos and photos."

    override fun canHandle(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("threads.net") || normalized.contains("threads.com")
    }

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeThreads(url, cfCookies)
        } catch (e: Exception) {
            errorJson("SnapSave Threads download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeThreads(url: String, cfCookies: String?): String {
        val apiUrl = "$API_BASE/api/action?url=${java.net.URLEncoder.encode(url, "UTF-8")}"

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", API_BASE)
            .header("Referer", "$API_BASE/")
            .apply {
                if (!cfCookies.isNullOrBlank()) {
                    header("Cookie", cfCookies)
                }
            }
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("API returned status ${response.code} - ${response.message}")
            }
            val raw = response.body?.string().orEmpty()
            if (raw.isEmpty()) throw IllegalStateException("Empty response from API")

            // Try new JSON format first
            val jsonResult = tryParseJsonFormat(raw)
            if (jsonResult != null) return jsonResult

            // Try old JSON wrapper with "data" field
            val dataHtml = tryExtractDataField(raw)
            if (!dataHtml.isNullOrBlank()) return parseThreadsHtml(dataHtml)

            // Fall back to encrypted response
            val html = decryptResponse(raw)
            if (html.isBlank()) throw IllegalStateException("Failed to parse response")
            return parseThreadsHtml(html)
        }
    }

    /**
     * New JSON format: { "items": [ { "type": "video"|"image", "downloadUrl": "...", "thumbnail": "..." } ], "status_code": 200 }
     */
    private fun tryParseJsonFormat(raw: String): String? {
        return runCatching {
            val json = JSONObject(raw)
            if (!json.has("items") || !json.has("status_code")) return null

            val items = json.getJSONArray("items")
            if (items.length() == 0) return null

            val downloadItems = mutableListOf<Map<String, Any>>()
            var thumbnail = ""

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val type = item.optString("type").lowercase()
                val downloadUrl = item.optString("downloadUrl")
                    .ifBlank { item.optString("videoUrl").ifBlank { item.optString("imageUrl") } }
                val itemThumb = item.optString("thumbnail")
                if (itemThumb.isNotBlank() && thumbnail.isBlank()) thumbnail = itemThumb

                if (downloadUrl.isBlank()) continue

                when (type) {
                    "video" -> downloadItems += mapOf(
                        "key" to "video_$i",
                        "label" to "Video",
                        "type" to "video",
                        "url" to downloadUrl,
                        "mimeType" to "video/mp4",
                        "quality" to "HD"
                    )
                    "image" -> downloadItems += mapOf(
                        "key" to "image_$i",
                        "label" to "Photo ${i + 1}",
                        "type" to "image",
                        "url" to downloadUrl,
                        "mimeType" to "image/jpeg",
                        "quality" to ""
                    )
                }
            }

            if (downloadItems.isEmpty()) return null

            gson.toJson(buildResultMap(downloadItems = downloadItems, thumbnail = thumbnail))
        }.getOrNull()
    }

    private fun tryExtractDataField(raw: String): String? {
        return runCatching {
            val json = JSONObject(raw)
            json.optString("data").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // ── Decryption (same algorithm as Instagram/Facebook) ────────────────────

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
            for (j in o.indices) s = s.replace(o[j].toString(), j.toString())
            val charCode = decodeBase(s, z, baseChars) - b
            if (charCode > 0) result.append(charCode.toChar())
        }

        return result.toString()
    }

    private fun decodeBase(value: String, base: Int, baseChars: List<Char>): Int {
        val reversed = value.reversed()
        var output = 0
        for (index in reversed.indices) {
            val digit = baseChars.indexOf(reversed[index])
            if (digit != -1) output += digit * pow(base, index)
        }
        return output
    }

    private fun getDecodedSnapSave(data: String): String {
        val parts = data.split("getElementById(\"download-section\").innerHTML = \"")
        if (parts.size < 2) return ""
        return parts[1]
            .split("\"; document.getElementById(\"inputData\").remove(); ")[0]
            .replace("\\", "")
    }

    // ── HTML parsing ─────────────────────────────────────────────────────────

    private fun parseThreadsHtml(htmlContent: String): String {
        val doc = Jsoup.parse(htmlContent)

        val downloadItems = mutableListOf<Map<String, Any>>()
        var thumbnail = ""

        when {
            doc.selectFirst("#download-block") != null -> {
                val result = parseDownloadBlock(doc)
                thumbnail = result.thumbnail
                downloadItems += result.items
            }
            doc.selectFirst("table.table") != null -> {
                val result = parseTableLayout(doc)
                thumbnail = result.thumbnail
                downloadItems += result.items
            }
            doc.selectFirst("div.download-items") != null -> {
                val result = parseDownloadItemsLayout(doc)
                thumbnail = result.thumbnail
                downloadItems += result.items
            }
            else -> {
                val result = parseSingleLayout(doc)
                downloadItems += result.items
            }
        }

        if (downloadItems.isEmpty()) {
            throw IllegalStateException("No download links found in response")
        }

        return gson.toJson(buildResultMap(downloadItems = downloadItems, thumbnail = thumbnail))
    }

    /** #download-block layout (Twitter-style used by threads.snapsave.app) */
    private fun parseDownloadBlock(doc: org.jsoup.nodes.Document): ParsedItems {
        val block = doc.selectFirst("#download-block")
            ?: throw IllegalStateException("download-block not found")
        val link = block.selectFirst(".abuttons > a")
        val url = link?.attr("href").orEmpty()
        if (url.isBlank()) throw IllegalStateException("Download URL not found in download-block")

        val thumbnail = doc.selectFirst(".videotikmate-left > img")?.attr("src").orEmpty()
        val buttonText = block.selectFirst(".abuttons > a > span > span")?.text()?.trim().orEmpty()
        val isVideo = !buttonText.lowercase().contains("photo")

        return if (isVideo) {
            ParsedItems(
                items = listOf(
                    mapOf("key" to "video_1", "label" to "Video", "type" to "video",
                        "url" to url, "mimeType" to "video/mp4", "quality" to "HD")
                ),
                thumbnail = thumbnail
            )
        } else {
            ParsedItems(
                items = listOf(
                    mapOf("key" to "image_1", "label" to "Photo", "type" to "image",
                        "url" to url, "mimeType" to "image/jpeg", "quality" to "")
                )
            )
        }
    }

    private fun parseTableLayout(doc: org.jsoup.nodes.Document): ParsedItems {
        val rows = doc.select("table.table tbody tr")
        if (rows.isEmpty()) throw IllegalStateException("No table rows found")

        val thumbnail = doc.selectFirst("article.media > figure img")?.attr("src").orEmpty()
        val cells = rows.first()!!.select("td")
        if (cells.size < 3) throw IllegalStateException("Failed to parse table layout")

        val button = cells[2].selectFirst("button")
        var videoUrl = button?.attr("onclick").orEmpty()

        if (videoUrl.contains("get_progressApi")) {
            val match = Regex("get_progressApi\\('(.+?)'\\)").find(videoUrl)
            if (match != null) videoUrl = "$API_BASE${match.groupValues[1]}"
        } else {
            videoUrl = button?.attr("href").orEmpty()
                .ifBlank { cells[2].selectFirst("a")?.attr("href").orEmpty() }
        }

        if (videoUrl.isBlank()) throw IllegalStateException("Video URL not found in table")

        return ParsedItems(
            items = listOf(
                mapOf("key" to "video_1", "label" to "Video", "type" to "video",
                    "url" to videoUrl, "mimeType" to "video/mp4", "quality" to "HD")
            ),
            thumbnail = thumbnail
        )
    }

    private fun parseDownloadItemsLayout(doc: org.jsoup.nodes.Document): ParsedItems {
        val allItems = doc.select("div.download-items")
        if (allItems.isEmpty()) throw IllegalStateException("No download-items div found")

        val first = allItems.first()!!
        val videoEl = first.selectFirst("video")
        val btnWrap = first.selectFirst("div.download-items__btn")
        val spanText = btnWrap?.selectFirst("span")?.text()?.trim().orEmpty().lowercase()
        val isVideo = videoEl != null || spanText.contains("video")

        return if (isVideo) {
            val thumbnail = first.selectFirst("div.download-items__thumb > img")?.attr("src")
                ?: videoEl?.attr("poster") ?: ""
            val videoUrl = btnWrap?.selectFirst("a")?.attr("href").orEmpty()
            if (videoUrl.isBlank()) throw IllegalStateException("Video URL not found")
            ParsedItems(
                items = listOf(
                    mapOf("key" to "video_1", "label" to "Video", "type" to "video",
                        "url" to videoUrl, "mimeType" to "video/mp4", "quality" to "HD")
                ),
                thumbnail = thumbnail
            )
        } else {
            val images = allItems.mapIndexedNotNull { idx, item ->
                val imgUrl = item.selectFirst("div.download-items__thumb > img")?.attr("src")
                    ?: item.selectFirst("div.download-items__btn a")?.attr("href")
                imgUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    mapOf<String, Any>("key" to "image_${idx + 1}", "label" to "Photo ${idx + 1}",
                        "type" to "image", "url" to url, "mimeType" to "image/jpeg", "quality" to "")
                }
            }
            if (images.isEmpty()) throw IllegalStateException("No images found")
            ParsedItems(items = images)
        }
    }

    private fun parseSingleLayout(doc: org.jsoup.nodes.Document): ParsedItems {
        val link = doc.selectFirst("a")
        val url = link?.attr("href").orEmpty()
        if (url.isBlank()) throw IllegalStateException("No download URL found")

        val linkText = link?.text()?.trim().orEmpty().lowercase()
        val isImage = linkText.contains("photo")

        return if (isImage) {
            ParsedItems(
                items = listOf(
                    mapOf("key" to "image_1", "label" to "Photo", "type" to "image",
                        "url" to url, "mimeType" to "image/jpeg", "quality" to "")
                )
            )
        } else {
            ParsedItems(
                items = listOf(
                    mapOf("key" to "video_1", "label" to "Video", "type" to "video",
                        "url" to url, "mimeType" to "video/mp4", "quality" to "HD")
                )
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildResultMap(
        downloadItems: List<Map<String, Any>>,
        thumbnail: String = "",
    ): Map<String, Any> = mapOf(
        "extensionId" to "snapsave_threads",
        "platform" to "threads",
        "platformName" to getPlatformName(),
        "version" to getVersion(),
        "downloaderName" to getDownloaderName(),
        "description" to getDownloaderDescription(),
        "title" to "",
        "author" to "",
        "authorName" to "",
        "duration" to 0,
        "thumbnail" to thumbnail,
        "downloadItems" to downloadItems,
        "playCount" to 0,
        "diggCount" to 0,
        "commentCount" to 0,
        "shareCount" to 0,
        "downloadCount" to 0
    )

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

    private data class ParsedItems(
        val items: List<Map<String, Any>> = emptyList(),
        val thumbnail: String = ""
    )
}
