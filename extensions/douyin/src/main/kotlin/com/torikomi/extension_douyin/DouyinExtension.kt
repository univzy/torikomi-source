package com.torikomi.extension_douyin

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.Request

class DouyinExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36"
        private const val BASE_VIDEO_URL =
            "https://www.douyin.com/aweme/v1/play/?video_id=%s&ratio=1080p&line=0"

        @JvmStatic
        fun getInstance(): IExtension = DouyinExtension()
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 60_000,
        writeTimeoutMs = 30_000
    ).build()

    private val gson = Gson()

    override fun getId(): String = "douyin"

    override fun getPlatformId(): String = "douyin"

    override fun getPlatformName(): String = "Douyin"

    override fun getVersion(): String = "1.0.0"

    override fun getDownloaderName(): String = "Douyin"

    override fun getDownloaderDescription(): String =
        "Douyin downloader for video and image posts."

    override fun canHandle(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("douyin.com") || normalized.contains("iesdouyin.com")
    }

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeDouyin(url)
        } catch (e: Exception) {
            errorJson("Douyin download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeDouyin(url: String): String {
        val normalizedUrl = normalizeInputUrl(url)
        val request = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val body = client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }

        val mediaInfo = parseMediaInfoFromHtml(body)
        val author = mediaInfo.getObjectOrNull("author")
        val statistics = mediaInfo.getObjectOrNull("statistics")

        val description = mediaInfo.getString("desc")
        val authorName = author?.getString("nickname").orEmpty()
        val authorUnique = author?.getString("unique_id").orEmpty()
        val authorDisplay = authorName.ifBlank { authorUnique }

        val durationSec = ((mediaInfo.getLong("duration") ?: 0L) / 1000L).toInt()
        val videoUri = mediaInfo.getObjectOrNull("video")
            ?.getObjectOrNull("play_addr")
            ?.getString("uri")
            .orEmpty()

        val thumbnail = mediaInfo.getObjectOrNull("video")
            ?.getObjectOrNull("cover")
            ?.getArrayOrNull("url_list")
            ?.firstString()
            .orElse {
                mediaInfo.getArrayOrNull("images")
                    ?.firstImageUrl()
            }
            .orEmpty()

        val images = mediaInfo.getArrayOrNull("images")
            ?.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                element.asJsonObject
                    .getArrayOrNull("url_list")
                    ?.firstString()
            }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val downloadItems = mutableListOf<Map<String, Any>>()

        if (videoUri.isNotBlank() && !videoUri.endsWith("mp3", ignoreCase = true)) {
            val videoUrl = BASE_VIDEO_URL.format(videoUri)
            downloadItems += mapOf(
                "key" to "video",
                "label" to "Video 1080p",
                "type" to "video",
                "url" to videoUrl,
                "mimeType" to "video/mp4",
                "quality" to "1080p"
            )
        }

        return gson.toJson(
            mapOf(
                "extensionId" to getId(),
                "platform" to getPlatformId(),
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to description,
                "author" to authorDisplay,
                "authorName" to authorDisplay,
                "duration" to durationSec,
                "thumbnail" to thumbnail,
                "downloadItems" to downloadItems,
                "playCount" to (statistics?.getLong("play_count") ?: 0L),
                "diggCount" to (statistics?.getLong("digg_count") ?: 0L),
                "commentCount" to (statistics?.getLong("comment_count") ?: 0L),
                "shareCount" to (statistics?.getLong("share_count") ?: 0L),
                "downloadCount" to (statistics?.getLong("download_count") ?: 0L),
                "images" to images
            )
        )
    }

    private fun parseMediaInfoFromHtml(body: String): JsonObject {
        val jsonObjects = extractJsonObjects(body)
        val pageData = jsonObjects.firstNotNullOfOrNull { candidate ->
            val loaderData = candidate.getObjectOrNull("loaderData") ?: return@firstNotNullOfOrNull null
            loaderData.getObjectOrNull("video_(id)/page") ?: loaderData.getObjectOrNull("note_(id)/page")
        } ?: throw IllegalStateException("No video info found in Douyin page")

        val itemList = pageData.getObjectOrNull("videoInfoRes")?.getArrayOrNull("item_list")
            ?: throw IllegalStateException("No video information found in the data")

        if (itemList.size() == 0) {
            throw IllegalStateException("No video information found in the data")
        }

        val first = itemList[0]
        if (!first.isJsonObject) {
            throw IllegalStateException("No video information found in the data")
        }
        return first.asJsonObject
    }

    private fun normalizeInputUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("URL is empty")
        }

        val extracted = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.value
            ?: trimmed

        val withScheme = if (
            extracted.startsWith("http://", ignoreCase = true) ||
            extracted.startsWith("https://", ignoreCase = true)
        ) {
            extracted
        } else {
            "https://$extracted"
        }

        return withScheme.trimEnd(')', ',', '.', ';', '!', '?')
    }

    private fun extractJsonObjects(text: String): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        var start = -1
        var depth = 0
        var inString = false
        var escape = false

        for (index in text.indices) {
            val ch = text[index]

            if (start == -1) {
                if (ch == '{') {
                    start = index
                    depth = 1
                }
                continue
            }

            if (inString) {
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, index + 1)
                        runCatching {
                            JsonParser.parseString(candidate)
                        }.getOrNull()?.takeIf(JsonElement::isJsonObject)?.let {
                            results += it.asJsonObject
                        }
                        start = -1
                    }
                }
            }
        }

        return results
    }

    private fun JsonObject.getString(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonNull) null else element.asString
    }

    private fun JsonObject.getObjectOrNull(key: String): JsonObject? {
        val element = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonObject) return null
        return element.asJsonObject
    }

    private fun JsonObject.getArrayOrNull(key: String): com.google.gson.JsonArray? {
        val element = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonArray) return null
        return element.asJsonArray
    }

    private fun JsonObject.getLong(key: String): Long? {
        val element = get(key) ?: return null
        return if (element.isJsonNull) null else runCatching { element.asLong }.getOrNull()
    }

    private fun com.google.gson.JsonArray.firstString(): String? {
        if (size() == 0) return null
        val first = get(0)
        return if (first.isJsonNull) null else runCatching { first.asString }.getOrNull()
    }

    private fun com.google.gson.JsonArray.firstImageUrl(): String? {
        if (size() == 0) return null
        val first = get(0)
        if (!first.isJsonObject) return null
        return first.asJsonObject.getArrayOrNull("url_list")?.firstString()
    }

    private fun <T> T?.orElse(fallback: () -> T?): T? = this ?: fallback()

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }
}
