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
        private const val PLAYLIST_FEED_BASE_URL = "https://www.youtube.com/feeds/videos.xml?playlist_id="
        private const val PLAYLIST_MAX_ITEMS = 25
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

    override fun getVersion(): String = "1.0.1"

    override fun getDownloaderName(): String = "YT1S"

    override fun getDownloaderDescription(): String =
        "YouTube downloader via YT1S (video MP4 dan audio MP3/M4A/OPUS)."

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
        val playlistId = extractPlaylistId(url)
        val urlLower = url.lowercase()
        val shouldUsePlaylistFlow = playlistId != null &&
            (urlLower.contains("/playlist") || extractVideoId(url) == null)
        if (shouldUsePlaylistFlow) {
            return scrapeYoutubePlaylist(playlistId)
        }

        val videoId = extractVideoId(url)
            ?: throw IllegalStateException("Invalid YouTube URL")

        val info = fetchInfo(videoId)
        val title = info.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "YouTube Video"
        val thumbnail = info.get("thumbnail")?.asString?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        val author = info.get("author")?.asString.orEmpty()
        val duration = info.get("duration")?.asInt ?: 0
        val formats = info.getAsJsonArray("formats") ?: JsonArray()

        val videoQualities = extractVideoQualities(formats)
        val audioOptions = buildAudioOptions(formats)

        val downloadItems = mutableListOf<Map<String, String>>()
        videoQualities.forEach { quality ->
            val videoUrl = requestVideoUrl(videoId, quality)
            if (videoUrl.isNotBlank()) {
                downloadItems += mapOf(
                    "key" to "video_${quality}",
                    "label" to "Video ${quality}p",
                    "type" to "video",
                    "url" to videoUrl,
                    "mimeType" to "video/mp4",
                    "quality" to "${quality}p"
                )
            }
        }
        audioOptions.forEach { option ->
            val audioUrl = requestAudioUrl(videoId, option.format, option.bitrate)
            if (audioUrl.isNotBlank()) {
                downloadItems += mapOf(
                    "key" to "audio_${option.format}_${option.bitrate ?: "default"}",
                    "label" to option.label,
                    "type" to "audio",
                    "url" to audioUrl,
                    "mimeType" to audioMimeType(option.format),
                    "quality" to option.quality
                )
            }
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

    private fun scrapeYoutubePlaylist(playlistId: String): String {
        val videoIds = fetchPlaylistVideoIds(playlistId)
        if (videoIds.isEmpty()) {
            throw IllegalStateException("Playlist kosong atau tidak bisa diakses")
        }

        val downloadItems = mutableListOf<Map<String, String>>()
        var playlistTitle = "YouTube Playlist"
        var author = ""
        var thumbnail = ""

        videoIds.forEachIndexed { index, videoId ->
            val info = runCatching { fetchInfo(videoId) }.getOrNull() ?: return@forEachIndexed
            val title = info.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "Video ${index + 1}"
            if (index == 0) {
                val firstThumbnail = info.get("thumbnail")?.asString.orEmpty()
                if (firstThumbnail.isNotBlank()) thumbnail = firstThumbnail

                val firstAuthor = info.get("author")?.asString.orEmpty()
                if (firstAuthor.isNotBlank()) author = firstAuthor

                val feedTitle = fetchPlaylistTitle(playlistId)
                playlistTitle = feedTitle ?: "Playlist - $title"
            }

            val formats = info.getAsJsonArray("formats") ?: JsonArray()
            val bestQuality = extractVideoQualities(formats).firstOrNull() ?: return@forEachIndexed
            val videoUrl = requestVideoUrl(videoId, bestQuality)
            if (videoUrl.isBlank()) return@forEachIndexed

            val indexLabel = (index + 1).toString().padStart(2, '0')
            downloadItems += mapOf(
                "key" to "playlist_${indexLabel}_$videoId",
                "label" to "$indexLabel. $title",
                "type" to "video",
                "url" to videoUrl,
                "mimeType" to "video/mp4",
                "quality" to "${bestQuality}p"
            )
        }

        if (downloadItems.isEmpty()) {
            throw IllegalStateException("Tidak ada video playlist yang bisa diunduh")
        }

        if (thumbnail.isBlank()) {
            thumbnail = "https://i.ytimg.com/vi/${videoIds.first()}/maxresdefault.jpg"
        }

        return gson.toJson(
            mapOf(
                "extensionId" to "youtube",
                "platform" to "youtube",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to "${getDownloaderDescription()} Playlist mode: 1 item video per entry.",
                "title" to playlistTitle,
                "author" to author,
                "authorName" to author,
                "duration" to 0,
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

    private fun fetchPlaylistVideoIds(playlistId: String): List<String> {
        val req = Request.Builder()
            .url("$PLAYLIST_FEED_BASE_URL$playlistId")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string().orEmpty()
            val ids = Regex("<yt:videoId>([a-zA-Z0-9_-]{11})</yt:videoId>")
                .findAll(xml)
                .map { it.groupValues[1] }
                .distinct()
                .take(PLAYLIST_MAX_ITEMS)
                .toList()
            return ids
        }
    }

    private fun fetchPlaylistTitle(playlistId: String): String? {
        val req = Request.Builder()
            .url("$PLAYLIST_FEED_BASE_URL$playlistId")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val xml = resp.body?.string().orEmpty()
            val titleMatch = Regex("<title>([^<]+)</title>").find(xml) ?: return null
            val title = titleMatch.groupValues.getOrNull(1)?.trim().orEmpty()
            return title.takeIf { it.isNotBlank() }
        }
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

    private fun requestAudioUrl(videoId: String, format: String, bitrate: String?): String {
        val (timestamp, signature) = createSignedAuth()
        val payload = mutableMapOf(
            "videoId" to videoId,
            "format" to format
        )
        if (!bitrate.isNullOrBlank()) payload["quality"] = bitrate
        val body = gson.toJson(payload)
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

    private fun extractVideoQualities(formats: JsonArray): List<Int> {
        return formats.mapNotNull { item ->
            val obj = item.asJsonObject
            val type = obj.get("type")?.asString.orEmpty()
            if (type != "video") return@mapNotNull null

            val qualityRaw = obj.get("quality")?.asString.orEmpty()
            val number = Regex("(\\d+)").find(qualityRaw)?.groupValues?.getOrNull(1)
            number?.toIntOrNull()
        }
            .distinct()
            .sortedDescending()
    }

    private fun buildAudioOptions(formats: JsonArray): List<AudioOption> {
        val fromInfo = formats.mapNotNull { item ->
            val obj = item.asJsonObject
            val type = obj.get("type")?.asString.orEmpty()
            if (type != "audio") return@mapNotNull null
            obj.get("format")?.asString?.lowercase()?.takeIf { it.isNotBlank() }
        }
            .distinct()

        val normalized = if (fromInfo.isEmpty()) listOf("mp3") else (fromInfo + "mp3").distinct()
        val options = mutableListOf<AudioOption>()

        if ("mp3" in normalized) {
            val mp3Bitrates = listOf("320", "256", "128", "96", "64")
            mp3Bitrates.forEach { bitrate ->
                options += AudioOption(
                    format = "mp3",
                    bitrate = bitrate,
                    label = "Audio MP3 ${bitrate}kbps",
                    quality = "${bitrate}kbps"
                )
            }
        }

        if ("m4a" in normalized) {
            options += AudioOption(
                format = "m4a",
                bitrate = "128",
                label = "Audio M4A",
                quality = "M4A"
            )
        }

        if ("opus" in normalized) {
            options += AudioOption(
                format = "opus",
                bitrate = "128",
                label = "Audio OPUS",
                quality = "OPUS"
            )
        }

        return options
    }

    private data class AudioOption(
        val format: String,
        val bitrate: String?,
        val label: String,
        val quality: String
    )

    private fun audioMimeType(format: String): String {
        return when (format.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            else -> "audio/$format"
        }
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

    private fun extractPlaylistId(url: String): String? {
        val patterns = listOf(
            Regex("[?&]list=([a-zA-Z0-9_-]+)"),
            Regex("youtube\\.com/playlist\\?list=([a-zA-Z0-9_-]+)")
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
