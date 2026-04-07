package com.torikomi.extension_yt1s

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.torikomi.extension.IExtension
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class Yt1sExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val BASE_URL_ENCODED = "aHR0cHM6Ly9lbWJlZC5kbHNydi5vbmxpbmU="
        private val BASE_URL: String
            get() = String(Base64.decode(BASE_URL_ENCODED, Base64.NO_WRAP), Charsets.UTF_8)
        private val INFO_API_URL: String
            get() = "$BASE_URL/api/info"
        private val VIDEO_API_URL: String
            get() = "$BASE_URL/api/download/mp4"
        private val AUDIO_API_URL: String
            get() = "$BASE_URL/api/download/mp3"
        private const val SIGNING_SECRET = "bq7b3BBxmjR4YdrJFDFPGkDvYPeeDdHWZ+Bq8lYImeRY"

        @JvmStatic
        fun getInstance(): IExtension = Yt1sExtension()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    override fun getId(): String = "youtube"

    override fun getPlatformId(): String = "youtube"

    override fun getPlatformName(): String = "YouTube"

    override fun getVersion(): String = "1.0.0"

    override fun getDownloaderName(): String = "YT1S"

    override fun getDownloaderDescription(): String =
        "YouTube downloader via YT1S (video MP4 dan audio MP3)."

    override fun canHandle(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("youtube.com") ||
            normalized.contains("youtu.be") ||
            normalized.contains("m.youtube.com")
    }

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeYoutube(url)
        } catch (e: Exception) {
            errorJson("YouTube download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeYoutube(url: String): String {
        val videoId = extractVideoId(url)
            ?: throw IllegalStateException("Invalid YouTube URL")

        val info = fetchInfo(videoId)
        val title = info.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "YouTube Video"
        val thumbnail = info.get("thumbnail")?.asString?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        val author = info.get("author")?.asString.orEmpty()
        val duration = info.get("duration")?.asInt ?: 0
        val formats = info.getAsJsonArray("formats") ?: JsonArray()

        val bestVideoQuality = pickBestVideoQuality(formats)
        val videoUrl = bestVideoQuality?.let { requestVideoUrl(videoId, it) }.orEmpty()
        val audioUrl = requestAudioUrl(videoId, "128")

        val downloadItems = mutableListOf<Map<String, String>>()
        if (videoUrl.isNotBlank()) {
            downloadItems += mapOf(
                "key" to "video_best",
                "label" to (if (bestVideoQuality != null) "Video ${bestVideoQuality}p" else "Video"),
                "type" to "video",
                "url" to videoUrl,
                "mimeType" to "video/mp4",
                "quality" to (bestVideoQuality?.let { "${it}p" } ?: "")
            )
        }
        if (audioUrl.isNotBlank()) {
            downloadItems += mapOf(
                "key" to "audio_mp3",
                "label" to "Audio MP3",
                "type" to "audio",
                "url" to audioUrl,
                "mimeType" to "audio/mpeg",
                "quality" to "128kbps"
            )
        }

        if (downloadItems.isEmpty()) {
            throw IllegalStateException("YT1S tidak mengembalikan link unduhan")
        }

        return gson.toJson(
            mapOf(
                "extensionId" to "youtube",
                "platform" to "youtube",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to title,
                "author" to author,
                "authorName" to author,
                "duration" to duration,
                "thumbnail" to thumbnail,
                "downloadItems" to downloadItems,
                "playCount" to 0,
                "diggCount" to 0,
                "commentCount" to 0,
                "shareCount" to 0,
                "downloadCount" to 0,
                "images" to emptyList<String>()
            )
        )
    }

    private fun fetchInfo(videoId: String): JsonObject {
        val (timestamp, signature) = createSignedAuth()
        val body = gson.toJson(mapOf("videoId" to videoId))
        val req = Request.Builder()
            .url(INFO_API_URL)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Referer", "$BASE_URL/v1/full?videoId=$videoId")
            .header("x-app-timestamp", timestamp)
            .header("x-app-signature", signature)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Info API error: ${resp.code}")
            val raw = resp.body?.string().orEmpty()
            val json = JsonParser.parseString(raw).asJsonObject
            val status = json.get("status")?.asString.orEmpty()
            val info = json.getAsJsonObject("info")
            if (status != "info" || info == null) {
                throw IllegalStateException("Gagal mengambil info video")
            }
            return info
        }
    }

    private fun requestVideoUrl(videoId: String, quality: Int): String {
        val (timestamp, signature) = createSignedAuth()
        val body = gson.toJson(
            mapOf(
                "videoId" to videoId,
                "format" to "mp4",
                "quality" to quality.toString()
            )
        )
        val req = Request.Builder()
            .url(VIDEO_API_URL)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Referer", "$BASE_URL/v1/full?videoId=$videoId")
            .header("x-app-timestamp", timestamp)
            .header("x-app-signature", signature)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val raw = resp.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return ""
            return json.get("url")?.asString.orEmpty()
        }
    }

    private fun requestAudioUrl(videoId: String, bitrate: String): String {
        val (timestamp, signature) = createSignedAuth()
        val body = gson.toJson(
            mapOf(
                "videoId" to videoId,
                "format" to "mp3",
                "quality" to bitrate
            )
        )
        val req = Request.Builder()
            .url(AUDIO_API_URL)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Referer", "$BASE_URL/v1/full?videoId=$videoId")
            .header("x-app-timestamp", timestamp)
            .header("x-app-signature", signature)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val raw = resp.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return ""
            return json.get("url")?.asString.orEmpty()
        }
    }

    private fun pickBestVideoQuality(formats: JsonArray): Int? {
        val qualities = formats.mapNotNull { item ->
            val obj = item.asJsonObject
            val type = obj.get("type")?.asString.orEmpty()
            if (type != "video") return@mapNotNull null

            val qualityRaw = obj.get("quality")?.asString.orEmpty()
            val number = Regex("(\\d+)").find(qualityRaw)?.groupValues?.getOrNull(1)
            number?.toIntOrNull()
        }
        return qualities.maxOrNull()
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/embed/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/v/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
            Regex("[?&]v=([a-zA-Z0-9_-]{11})")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun createSignedAuth(): Pair<String, String> {
        val timestamp = System.currentTimeMillis().toString()
        val signature = sha256Hex(timestamp + SIGNING_SECRET)
        return timestamp to signature
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }
}
