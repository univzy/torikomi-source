package com.torikomi.extension_spotmate

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.torikomi.extension.IExtension
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class SpotmateExtension : IExtension {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0"
        private const val BASE_URL = "https://spotmate.online"
        private const val TRACK_DATA_URL = "$BASE_URL/getTrackData"
        private const val CONVERT_URL = "$BASE_URL/convert"

        @JvmStatic
        fun getInstance(): IExtension = SpotmateExtension()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    override fun getId(): String = "spotmate"

    override fun getPlatformId(): String = "spotify"

    override fun getPlatformName(): String = "Spotify"

    override fun getVersion(): String = "1.0.0"

    override fun getDownloaderName(): String = "Spotmate Downloader"

    override fun getDownloaderDescription(): String =
        "Download Spotify tracks, playlists, and albums as MP3 via Spotmate."

    override fun canHandle(url: String): Boolean =
        url.contains("open.spotify.com")

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeSpotify(url, cfCookies)
        } catch (e: Exception) {
            errorJson("Spotmate download failed: ${e.message ?: "Unknown error"}")
        }
    }

    // ── Session ───────────────────────────────────────────────────────────────

    private data class Session(val cookies: String, val csrfToken: String)

    private fun initSession(cfCookies: String?): Session {
        val req = Request.Builder()
            .url("$BASE_URL/en1")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("sec-ch-ua", "\"Microsoft Edge\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .apply {
                if (!cfCookies.isNullOrBlank()) header("Cookie", cfCookies)
            }
            .build()

        val resp = client.newCall(req).execute()

        if (resp.code == 403) {
            throw IllegalStateException(
                "Cloudflare blocked the request (403). Please provide a valid cf_clearance cookie."
            )
        }

        val html = resp.body?.string().orEmpty()

        // Collect session cookies from Set-Cookie headers (exclude cf_clearance)
        val sessionCookies = mutableMapOf<String, String>()
        for (header in resp.headers.values("set-cookie")) {
            val pair = header.split(";").firstOrNull()?.split("=", limit = 2)
            if (pair != null && pair.size == 2) {
                val name = pair[0].trim()
                val value = pair[1].trim()
                if (name != "cf_clearance") {
                    sessionCookies[name] = value
                }
            }
        }

        // Build final cookie string: user-provided + fresh session cookies
        val cookieParts = mutableListOf<String>()
        if (!cfCookies.isNullOrBlank()) cookieParts.add(cfCookies)
        for ((name, value) in sessionCookies) {
            cookieParts.add("$name=$value")
        }
        val cookieString = cookieParts.joinToString("; ")

        // Extract CSRF token from meta[name=csrf-token]
        val doc = Jsoup.parse(html)
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
        if (csrfToken.isBlank()) {
            throw IllegalStateException(
                "Failed to extract CSRF token from Spotmate. The page structure may have changed."
            )
        }

        return Session(cookieString, csrfToken)
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun postJson(url: String, body: String, session: Session): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/en1")
            .header("x-csrf-token", session.csrfToken)
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .apply {
                if (session.cookies.isNotBlank()) header("Cookie", session.cookies)
            }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(req).execute().use { it.body?.string().orEmpty() }
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private fun scrapeSpotify(url: String, cfCookies: String?): String {
        val session = initSession(cfCookies)
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("/track/") -> scrapeTrack(url, session)
            urlLower.contains("/playlist/") || urlLower.contains("/album/") ->
                scrapePlaylistOrAlbum(url, session)
            else -> errorJson(
                "Unsupported Spotify URL. Please provide a track, playlist, or album link."
            )
        }
    }

    // ── Single track ──────────────────────────────────────────────────────────

    private fun scrapeTrack(url: String, session: Session): String {
        // 1. Fetch track metadata via getTrackData
        val metaRaw = postJson(TRACK_DATA_URL, """{"spotify_url":"$url"}""", session)
        val metaJson = runCatching {
            JsonParser.parseString(metaRaw).asJsonObject
        }.getOrNull() ?: throw IllegalStateException("Invalid response from Spotmate getTrackData API")

        val trackName = metaJson.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "Spotify Track"
        val artists = metaJson.getAsJsonArray("artists")
            ?.mapNotNull { it.asJsonObject.get("name")?.asString }
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ").orEmpty()
        val thumbnail = metaJson.getAsJsonObject("album")
            ?.getAsJsonArray("images")
            ?.firstOrNull()?.asJsonObject?.get("url")?.asString.orEmpty()
        val durationSec = (metaJson.get("duration_ms")?.asInt ?: 0) / 1000

        // Clean URL (strip query params like ?si=...) for convert endpoint
        val cleanUrl = metaJson.getAsJsonObject("external_urls")
            ?.get("spotify")?.asString?.takeIf { it.isNotBlank() } ?: url

        // 2. Get download URL via convert
        val convertRaw = postJson(CONVERT_URL, """{"urls":"$cleanUrl"}""", session)
        val convertJson = runCatching {
            JsonParser.parseString(convertRaw).asJsonObject
        }.getOrNull() ?: throw IllegalStateException("Invalid response from Spotmate convert API")

        val hasError = convertJson.get("error")?.asBoolean ?: true
        if (hasError) {
            val msg = convertJson.get("message")?.asString
                ?: convertJson.get("error")?.asString?.takeIf { it != "true" }
                ?: "Unknown error"
            throw IllegalStateException("Spotmate convert failed: $msg")
        }

        val downloadUrl = convertJson.get("url")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No download URL returned by Spotmate")

        val title = if (artists.isNotBlank()) "$artists - $trackName" else trackName

        return gson.toJson(
            mapOf(
                "extensionId" to "spotmate",
                "platform" to "spotify",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to title,
                "author" to artists,
                "authorName" to artists,
                "duration" to durationSec,
                "thumbnail" to thumbnail,
                "downloadItems" to listOf(
                    mapOf(
                        "key" to "audio_mp3",
                        "label" to "Audio MP3",
                        "type" to "audio",
                        "url" to downloadUrl,
                        "mimeType" to "audio/mpeg",
                        "quality" to "MP3"
                    )
                ),
                "playCount" to 0,
                "diggCount" to 0,
                "commentCount" to 0,
                "shareCount" to 0,
                "downloadCount" to 0,
                "images" to emptyList<String>()
            )
        )
    }

    // ── Playlist / Album ──────────────────────────────────────────────────────

    private fun scrapePlaylistOrAlbum(url: String, session: Session): String {
        val raw = postJson(TRACK_DATA_URL, """{"spotify_url":"$url"}""", session)
        val json = runCatching {
            JsonParser.parseString(raw).asJsonObject
        }.getOrNull() ?: throw IllegalStateException("Invalid response from Spotmate getTrackData API")

        val playlistName = json.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "Spotify Playlist"
        val ownerName = json.getAsJsonObject("owner")?.get("display_name")?.asString.orEmpty()
        val thumbnail = json.getAsJsonArray("images")
            ?.firstOrNull()?.asJsonObject?.get("url")?.asString.orEmpty()

        val trackItemsArray = json.getAsJsonObject("tracks")?.getAsJsonArray("items")
            ?: throw IllegalStateException("No tracks found in Spotmate response")

        val downloadItems = mutableListOf<Map<String, Any>>()

        trackItemsArray.forEachIndexed { index, element ->
            val track = runCatching {
                element.asJsonObject.getAsJsonObject("track")
            }.getOrNull() ?: return@forEachIndexed

            val trackName = track.get("name")?.asString?.takeIf { it.isNotBlank() }
                ?: "Track ${index + 1}"
            val artists = track.getAsJsonArray("artists")
                ?.mapNotNull { it.asJsonObject.get("name")?.asString }
                ?.filter { it.isNotBlank() }
                ?.joinToString(", ").orEmpty()
            val trackUrl = track.getAsJsonObject("external_urls")
                ?.get("spotify")?.asString.orEmpty()
            val trackThumbnail = track.getAsJsonObject("album")
                ?.getAsJsonArray("images")
                ?.firstOrNull()?.asJsonObject?.get("url")?.asString.orEmpty()
            val durationSec = (track.get("duration_ms")?.asInt ?: 0) / 1000
            val trackId = track.get("id")?.asString ?: index.toString()

            val label = if (artists.isNotBlank()) "$artists - $trackName" else trackName

            downloadItems += mapOf(
                "key" to "playlist_item_$trackId",
                "label" to label,
                "type" to "playlist_item",
                "url" to trackUrl,
                "mimeType" to "",
                "quality" to "",
                "extra" to mapOf(
                    "thumbnail" to trackThumbnail,
                    "index" to (index + 1),
                    "duration" to durationSec
                )
            )
        }

        if (downloadItems.isEmpty()) {
            throw IllegalStateException("No downloadable tracks found in the playlist/album")
        }

        return gson.toJson(
            mapOf(
                "extensionId" to "spotmate",
                "platform" to "spotify",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to playlistName,
                "author" to ownerName,
                "authorName" to ownerName,
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

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }
}
