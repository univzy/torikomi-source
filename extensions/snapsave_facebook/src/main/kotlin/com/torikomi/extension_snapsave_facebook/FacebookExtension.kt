package com.torikomi.extension_snapsave_facebook

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup

class FacebookExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        private const val API_BASE = "https://snapsave.app"

        @JvmStatic
        fun getInstance(): IExtension = FacebookExtension()
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 30_000,
        writeTimeoutMs = 30_000
    )
        .addInterceptor { chain ->
            val requestWithHeaders = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            chain.proceed(requestWithHeaders)
        }
        .build()
    private val gson = Gson()

    override fun getId(): String = "snapsave_facebook"

    override fun getPlatformId(): String = "facebook"

    override fun getPlatformName(): String = "Facebook"

    override fun getVersion(): String = "1.0.0"

    override fun getDownloaderName(): String = "SnapSave"

    override fun getDownloaderDescription(): String =
        "SnapSave downloader for Facebook videos and reels."

    override fun canHandle(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("facebook.com") ||
            normalized.contains("fb.watch") ||
            normalized.contains("m.facebook.com")
    }

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeFacebook(url, cfCookies)
        } catch (e: Exception) {
            errorJson("SnapSave Facebook download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeFacebook(url: String, cfCookies: String?): String {
        val formBody = FormBody.Builder()
            .add("url", url)
            .build()

        val request = Request.Builder()
            .url("$API_BASE/action.php?lang=en")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", API_BASE)
            .header("Referer", "$API_BASE/id/facebook-reels-download")
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
                throw IllegalStateException("API returned status ${response.code} - ${response.message}")
            }
            val encrypted = response.body?.string().orEmpty()
            if (encrypted.isEmpty()) throw IllegalStateException("Empty response from API")
            val html = decryptResponse(encrypted)
            if (html.isBlank()) throw IllegalStateException("Failed to decrypt response")
            return parseFacebookData(html)
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

    private fun parseFacebookData(htmlContent: String): String {
        val doc = Jsoup.parse(htmlContent)

        val downloadItems = mutableListOf<Map<String, Any>>()
        var thumbnail = ""

        when {
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
            doc.selectFirst("div.card") != null -> {
                val result = parseCardLayout(doc)
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

        return gson.toJson(
            mapOf(
                "extensionId" to "snapsave_facebook",
                "platform" to "facebook",
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
        )
    }

    /**
     * Table layout: multiple quality rows (HD, SD, etc.).
     * Each row can be a direct link or a render/polling link.
     */
    private fun parseTableLayout(doc: org.jsoup.nodes.Document): ParsedItems {
        val rows = doc.select("table.table tbody tr")
        if (rows.isEmpty()) throw IllegalStateException("No table rows found")

        val thumbnail = doc.selectFirst("article.media > figure img")?.attr("src").orEmpty()
        val items = mutableListOf<Map<String, Any>>()

        rows.forEachIndexed { index, row ->
            val cells = row.select("td")
            if (cells.size < 3) return@forEachIndexed

            val resolution = cells[0].text().trim().ifBlank { "Video ${index + 1}" }
            val button = cells[2].selectFirst("button")
            val onclick = button?.attr("onclick").orEmpty()

            val (videoUrl, isRender) = if (onclick.contains("get_progressApi")) {
                val match = Regex("get_progressApi\\('(.+?)'\\)").find(onclick)
                val progressUrl = if (match != null) "$API_BASE${match.groupValues[1]}" else ""
                progressUrl to true
            } else {
                val href = button?.attr("href").orEmpty()
                    .ifBlank { cells[2].selectFirst("a")?.attr("href").orEmpty() }
                href to false
            }

            if (videoUrl.isBlank()) return@forEachIndexed

            val extra: Map<String, Any> = if (isRender) {
                mapOf(
                    "resolver" to "polling",
                    "pollUrl" to videoUrl,
                    "statusKey" to "progress",
                    "completedStatus" to "100",
                    "fileUrlKey" to "url",
                    "pollInterval" to "3000",
                    "maxAttempts" to "30"
                )
            } else {
                emptyMap()
            }

            val itemMap = mutableMapOf<String, Any>(
                "key" to "video_${resolution.replace(" ", "_").lowercase()}_${index + 1}",
                "label" to resolution,
                "type" to "video",
                "url" to if (isRender) "" else videoUrl,
                "mimeType" to "video/mp4",
                "quality" to resolution
            )
            if (extra.isNotEmpty()) itemMap["extra"] = extra

            items += itemMap
        }

        if (items.isEmpty()) throw IllegalStateException("No video qualities found in table")
        return ParsedItems(items = items, thumbnail = thumbnail)
    }

    private fun parseDownloadItemsLayout(doc: org.jsoup.nodes.Document): ParsedItems {
        val firstItem = doc.selectFirst("div.download-items")
            ?: throw IllegalStateException("No download-items div found")

        val thumbnail = firstItem.selectFirst("div.download-items__thumb > img")?.attr("src").orEmpty()
        val btnWrap = firstItem.selectFirst("div.download-items__btn")
        val videoUrl = btnWrap?.selectFirst("a")?.attr("href").orEmpty()

        if (videoUrl.isBlank()) throw IllegalStateException("Video URL not found in download-items")

        return ParsedItems(
            items = listOf(
                mapOf(
                    "key" to "video_hd_1",
                    "label" to "HD",
                    "type" to "video",
                    "url" to videoUrl,
                    "mimeType" to "video/mp4",
                    "quality" to "HD"
                )
            ),
            thumbnail = thumbnail
        )
    }

    private fun parseCardLayout(doc: org.jsoup.nodes.Document): ParsedItems {
        val firstCard = doc.selectFirst("div.card") ?: throw IllegalStateException("No card found")
        val link = firstCard.selectFirst("div.card-body a")
        val url = link?.attr("href").orEmpty()
        if (url.isBlank()) throw IllegalStateException("No URL found in card")

        return ParsedItems(
            items = listOf(
                mapOf(
                    "key" to "video_hd_1",
                    "label" to "HD",
                    "type" to "video",
                    "url" to url,
                    "mimeType" to "video/mp4",
                    "quality" to "HD"
                )
            )
        )
    }

    private fun parseSingleLayout(doc: org.jsoup.nodes.Document): ParsedItems {
        val link = doc.selectFirst("a")
        val button = doc.selectFirst("button")

        var url = link?.attr("href").orEmpty()
        var isRender = false

        if (url.isBlank()) {
            val onclick = button?.attr("onclick").orEmpty()
            if (onclick.contains("get_progressApi")) {
                val match = Regex("get_progressApi\\('(.+?)'\\)").find(onclick)
                if (match != null) {
                    url = "$API_BASE${match.groupValues[1]}"
                    isRender = true
                }
            }
        }

        if (url.isBlank()) throw IllegalStateException("No download URL found")

        val extra: Map<String, Any> = if (isRender) {
            mapOf(
                "resolver" to "polling",
                "pollUrl" to url,
                "statusKey" to "progress",
                "completedStatus" to "100",
                "fileUrlKey" to "url",
                "pollInterval" to "3000",
                "maxAttempts" to "30"
            )
        } else {
            emptyMap()
        }

        val itemMap = mutableMapOf<String, Any>(
            "key" to "video_hd_1",
            "label" to "HD",
            "type" to "video",
            "url" to if (isRender) "" else url,
            "mimeType" to "video/mp4",
            "quality" to "HD"
        )
        if (extra.isNotEmpty()) itemMap["extra"] = extra

        return ParsedItems(items = listOf(itemMap))
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

    private data class ParsedItems(
        val items: List<Map<String, Any>> = emptyList(),
        val thumbnail: String = ""
    )
}
