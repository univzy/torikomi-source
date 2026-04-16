package com.torikomi.extension_ytdown

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.brotli.dec.BrotliInputStream
class YtdownExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0"
        private const val PROXY_API_URL_ENCODED = "aHR0cHM6Ly9hcHAueXRkb3duLnRvL3Byb3h5LnBocA=="
        private val PROXY_API_URL: String
            get() = String(Base64.decode(PROXY_API_URL_ENCODED, Base64.NO_WRAP), Charsets.UTF_8)
        private const val INNERTUBE_URL = "https://www.youtube.com/youtubei/v1/browse"
        private const val INNERTUBE_CLIENT_VERSION = "2.20231121.09.00"
        private const val PLAYLIST_MAX_PAGES = 20  // up to 20 × 100 items per page

        @JvmStatic
        fun getInstance(): IExtension = YtdownExtension()
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 60_000,
        writeTimeoutMs = 60_000
    )
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
            throw IllegalStateException("YTDown returned no media formats")

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
            throw IllegalStateException("YTDown returned no media formats - invalid response")
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
            throw IllegalStateException("Playlist is empty or not accessible")
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

    /**
     * Fetches a full YouTube playlist via the InnerTube API with pagination.
     * Supports playlists of any size (tested up to 500+).
     */
    private fun fetchPlaylistData(playlistId: String): PlaylistData {
        val allEntries = mutableListOf<PlaylistEntry>()
        var playlistTitle = "YouTube Playlist"
        var continuationToken: String? = null
        var isFirstPage = true
        var pageCount = 0

        do {
            val bodyJson = if (isFirstPage) {
                """{"context":{"client":{"clientName":"WEB","clientVersion":"$INNERTUBE_CLIENT_VERSION","hl":"en","gl":"US"}},"browseId":"VL$playlistId"}"""
            } else {
                """{"context":{"client":{"clientName":"WEB","clientVersion":"$INNERTUBE_CLIENT_VERSION","hl":"en","gl":"US"}},"continuation":"${continuationToken!!}"}"""
            }

            val req = Request.Builder()
                .url(INNERTUBE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, */*")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", INNERTUBE_CLIENT_VERSION)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val responseJson = runCatching {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    JsonParser.parseString(raw).asJsonObject
                }
            }.getOrNull() ?: break

            // Extract playlist title from first page metadata
            if (isFirstPage) {
                val title = runCatching {
                    responseJson.getAsJsonObject("metadata")
                        ?.getAsJsonObject("playlistMetadataRenderer")
                        ?.get("title")?.asString
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
                if (title != null) playlistTitle = title
            }

            val videos = mutableListOf<PlaylistEntry>()
            var nextToken: String? = null
            traverseForPlaylistItems(responseJson, videos) { nextToken = it }

            allEntries += videos
            continuationToken = nextToken
            isFirstPage = false
            pageCount++

            // Stop if no new videos and no continuation
            if (videos.isEmpty()) break

        } while (continuationToken != null && pageCount < PLAYLIST_MAX_PAGES)

        return PlaylistData(playlistTitle, allEntries)
    }

    /**
     * Recursively traverses a JsonElement tree to locate:
     * - playlistVideoRenderer objects → extract video id + title
     * - continuationItemRenderer objects → extract pagination token
     */
    private fun traverseForPlaylistItems(
        element: JsonElement,
        videos: MutableList<PlaylistEntry>,
        onContinuationToken: (String) -> Unit,
    ) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach {
                traverseForPlaylistItems(it, videos, onContinuationToken)
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject

                if (obj.has("playlistVideoRenderer")) {
                    val vr = obj.getAsJsonObject("playlistVideoRenderer")
                    val videoId = vr.get("videoId")?.asString
                        ?.takeIf { it.length == 11 } ?: return
                    val title = runCatching {
                        vr.getAsJsonObject("title")
                            ?.getAsJsonArray("runs")
                            ?.get(0)?.asJsonObject
                            ?.get("text")?.asString
                    }.getOrNull()
                        ?: runCatching { vr.getAsJsonObject("title")?.get("simpleText")?.asString }.getOrNull()
                        ?: "Video"
                    videos += PlaylistEntry(videoId, title
                        .replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&#39;", "'"))
                    return  // do not recurse deeper into this renderer
                }

                if (obj.has("continuationItemRenderer")) {
                    val token = runCatching {
                        obj.getAsJsonObject("continuationItemRenderer")
                            ?.getAsJsonObject("continuationEndpoint")
                            ?.getAsJsonObject("continuationCommand")
                            ?.get("token")?.asString
                    }.getOrNull()
                    if (!token.isNullOrBlank()) onContinuationToken(token)
                    return
                }

                obj.entrySet().forEach { (_, v) ->
                    traverseForPlaylistItems(v, videos, onContinuationToken)
                }
            }
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
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://app.ytdown.to")
            .header("Referer", "https://app.ytdown.to/")
            .header("x-requested-with", "XMLHttpRequest")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Proxy API error: ${resp.code} ${resp.message}")
            }

            var raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) {
                throw IllegalStateException("Proxy API response is empty")
            }

            // Remove BOM if present
            if (raw.startsWith("\ufeff")) {
                raw = raw.substring(1)
            }

            // Validate it's valid JSON
            if (!raw.trim().startsWith("{") && !raw.trim().startsWith("[")) {
                throw IllegalStateException("Response is not JSON. First 200 chars: ${raw.take(200)}")
            }

            val json = runCatching {
                JsonParser.parseString(raw).asJsonObject
            }.getOrElse { ex ->
                throw IllegalStateException("JSON parse error: ${ex.message}. Response: ${raw.take(200)}")
            }

            val api = json.getAsJsonObject("api")
                ?: throw IllegalStateException("Invalid response: missing 'api' field. Response: ${raw.take(200)}")
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
