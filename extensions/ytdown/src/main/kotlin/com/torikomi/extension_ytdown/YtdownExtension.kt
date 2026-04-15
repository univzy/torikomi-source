package com.torikomi.extension_ytdown

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayOutputStream

class YtdownExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0"
        private const val PROXY_API_URL_ENCODED = "aHR0cHM6Ly9hcHAueXRkb3duLnRvL3Byb3h5LnBocA=="
        private val PROXY_API_URL: String
            get() = String(Base64.decode(PROXY_API_URL_ENCODED, Base64.NO_WRAP), Charsets.UTF_8)
        private const val PLAYLIST_FEED_BASE_URL = "https://www.youtube.com/feeds/videos.xml?playlist_id="
        private const val PLAYLIST_MAX_ITEMS = 25

        @JvmStatic
        fun getInstance(): IExtension = YtdownExtension()
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 60_000,
        writeTimeoutMs = 60_000
    )
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
                    
                    response.newBuilder()
                        .body(decompressed.toResponseBody(responsebody.contentType()))
                        .removeHeader("Content-Encoding")
                        .build()
                } catch (e: Exception) {
                    response
                }
            }
            response
        }
        .build()
    private val gson = Gson()

    override fun getId(): String = "youtube"

    override fun getPlatformId(): String = "youtube"

    override fun getPlatformName(): String = "YouTube"

    override fun getVersion(): String = "1.0.2"

    override fun getDownloaderName(): String = "YTDown"

    override fun getDownloaderDescription(): String =
        "YouTube downloader via YTDown (video MP4 dan audio MP3/M4A)."

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

        val api = fetchMediaItems(url)
        val title = api.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "YouTube Video"
        val thumbnail = api.get("imagePreviewUrl")?.asString?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        val userInfoObj = runCatching { api.getAsJsonObject("userInfo") }.getOrNull()
        val author = userInfoObj?.get("name")?.asString?.takeIf { it.isNotBlank() }
            ?: userInfoObj?.get("username")?.asString.orEmpty()
        val mediaItems = api.getAsJsonArray("mediaItems")
            ?: throw IllegalStateException("YTDown tidak mengembalikan format apapun")

        val downloadItems = mutableListOf<Map<String, Any>>()
        mediaItems.forEachIndexed { index, element ->
            val item = element.asJsonObject
            val type = item.get("type")?.asString.orEmpty()
            val mediaUrl = item.get("mediaUrl")?.asString?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val mediaQuality = item.get("mediaQuality")?.asString.orEmpty()
            val mediaExtension = item.get("mediaExtension")?.asString.orEmpty()
            val mediaFileSize = item.get("mediaFileSize")?.asString.orEmpty()
            val mediaRes = runCatching { item.get("mediaRes")?.asString }.getOrNull()
                ?.takeIf { it.isNotBlank() }.orEmpty()

            val isVideo = type.equals("Video", ignoreCase = true)
            val isAudio = type.equals("Audio", ignoreCase = true)
            if (!isVideo && !isAudio) return@forEachIndexed

            val label = buildString {
                if (isVideo) {
                    if (mediaRes.isNotBlank()) append("$mediaRes ")
                    else if (mediaQuality.isNotBlank()) append("$mediaQuality ")
                    if (mediaExtension.isNotBlank()) append(mediaExtension)
                } else {
                    append("Audio")
                    if (mediaQuality.isNotBlank()) append(" $mediaQuality")
                    if (mediaExtension.isNotBlank()) append(" $mediaExtension")
                }
                if (mediaFileSize.isNotBlank()) append(" ($mediaFileSize)")
            }

            val mimeType = if (isVideo) "video/mp4" else audioMimeType(mediaExtension.lowercase())
            val key = "${type.lowercase()}_${mediaExtension.lowercase()}_${index + 1}"

            downloadItems += mapOf(
                "key" to key,
                "label" to label,
                "type" to if (isVideo) "video" else "audio",
                "url" to "",
                "mimeType" to mimeType,
                "quality" to mediaQuality,
                "fileSize" to 0,
                "extra" to buildPollExtra(mediaUrl)
            )
        }

        if (downloadItems.isEmpty()) {
            throw IllegalStateException("YTDown tidak mengembalikan format - response tidak valid")
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

    private fun scrapeYoutubePlaylist(playlistId: String): String {
        val data = fetchPlaylistData(playlistId)
        if (data.entries.isEmpty()) {
            throw IllegalStateException("Playlist kosong atau tidak bisa diakses")
        }

        val firstThumbnail = "https://i.ytimg.com/vi/${data.entries.first().videoId}/hqdefault.jpg"

        val downloadItems = data.entries.mapIndexed { index, entry ->
            val thumbnail = "https://i.ytimg.com/vi/${entry.videoId}/hqdefault.jpg"
            mapOf(
                "key"      to "playlist_item_${entry.videoId}",
                "label"    to entry.title,
                "type"     to "playlist_item",
                "url"      to "https://www.youtube.com/watch?v=${entry.videoId}",
                "mimeType" to "",
                "quality"  to "",
                "extra"    to mapOf(
                    "thumbnail" to thumbnail,
                    "index"     to (index + 1),
                )
            )
        }

        return gson.toJson(
            mapOf(
                "extensionId"  to "youtube",
                "platform"     to "youtube",
                "platformName" to getPlatformName(),
                "version"      to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description"  to getDownloaderDescription(),
                "title"        to data.title,
                "author"       to "",
                "authorName"   to "",
                "duration"     to 0,
                "thumbnail"    to firstThumbnail,
                "downloadItems" to downloadItems,
                "playCount"    to 0,
                "diggCount"    to 0,
                "commentCount" to 0,
                "shareCount"   to 0,
                "downloadCount" to 0
            )
        )
    }

    private data class PlaylistEntry(val videoId: String, val title: String)
    private data class PlaylistData(val title: String, val entries: List<PlaylistEntry>)

    /** Satu request ke RSS feed — ekstrak judul playlist + semua video (id + judul). */
    private fun fetchPlaylistData(playlistId: String): PlaylistData {
        val req = Request.Builder()
            .url("$PLAYLIST_FEED_BASE_URL$playlistId")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return PlaylistData("YouTube Playlist", emptyList())
            val xml = resp.body?.string().orEmpty()

            // First <title> in the feed = playlist title
            val playlistTitle = Regex("<title>([^<]+)</title>")
                .find(xml)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() } ?: "YouTube Playlist"

            val entries = Regex("<entry>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL))
                .findAll(xml)
                .mapNotNull { m ->
                    val block = m.groupValues[1]
                    val videoId = Regex("<yt:videoId>([a-zA-Z0-9_-]{11})</yt:videoId>")
                        .find(block)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                    val rawTitle = Regex("<title>([^<]+)</title>")
                        .find(block)?.groupValues?.getOrNull(1)?.trim() ?: "Video"
                    val title = rawTitle
                        .replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&#39;", "'")
                    PlaylistEntry(videoId, title)
                }
                .take(PLAYLIST_MAX_ITEMS)
                .toList()

            return PlaylistData(playlistTitle, entries)
        }
    }

    private fun fetchMediaItems(videoUrl: String): JsonObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("url", videoUrl)
            .build()

        val req = Request.Builder()
            .url(PROXY_API_URL)
            .header("User-Agent", USER_AGENT)
            .header("Connection", "keep-alive")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("x-requested-with", "XMLHttpRequest")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Proxy API error: ${resp.code} ${resp.message}")
            }
            val raw = resp.body?.string().orEmpty()
            val json = JsonParser.parseString(raw).asJsonObject
            val api = json.getAsJsonObject("api")
                ?: throw IllegalStateException("Response tidak valid: field 'api' tidak ada")
            val status = api.get("status")?.asString.orEmpty()
            if (status != "ok") {
                val msg = api.get("message")?.asString ?: status
                throw IllegalStateException("API error: $msg")
            }
            return api
        }
    }

    private fun buildPollExtra(pollUrl: String): Map<String, Any> {
        return mapOf(
            "resolver" to "ytdown-polling",
            "pollUrl" to pollUrl
        )
    }

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
        val queryList = runCatching {
            Uri.parse(url)
                .getQueryParameter("list")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!queryList.isNullOrBlank()) return queryList

        val patterns = listOf(
            Regex("[?&]list=([^&#]+)", RegexOption.IGNORE_CASE),
            Regex("youtube\\.com/playlist\\?[^#]*list=([^&#]+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val playlistId = match.groupValues[1].trim()
                if (playlistId.isNotBlank()) return playlistId
            }
        }
        return null
    }

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }
}
