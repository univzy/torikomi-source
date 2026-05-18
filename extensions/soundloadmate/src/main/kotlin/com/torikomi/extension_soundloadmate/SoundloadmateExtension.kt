package com.torikomi.extension_soundloadmate

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Cookie
import org.jsoup.Jsoup

/**
 * SoundCloud downloader powered by https://soundloadmate.com.
 *
 * Flow (mirrors the SoundCloudMate web client):
 *   1. GET  /enB13                              → save session cookie + extract `<input type="hidden" name="X" value="Y">`
 *   2. POST /action      (form-urlencoded)      → JSON `{success: true, html: "..."}` containing one or more
 *                                                  `<form name="submitapurl">` blocks. Each form has
 *                                                    - `<input name="data"  value='<base64-of-track-json>'>`
 *                                                    - `<input name="token" value="<obfuscated-token>">`
 *      Decoded base64 → `{id, name, artist, cover, link, [albumname]}`
 *   3. POST /action/track (form-urlencoded)     → JSON `{error: false, data: "<HTML with download anchors>"}`
 *      Parse the HTML for `<a class="button is-download" href="https://rapid.soundloadmate.com/v2?...">`
 *      = MP3 URL.
 *
 * Single tracks return one downloadItem; playlists/albums return one playlist_item per track so the
 * main app's PlaylistItemCard UI can lazily resolve each MP3 URL on demand.
 */
class SoundloadmateExtension : IExtension {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val BASE_URL = "https://soundloadmate.com"
        private const val INDEX_PATH = "/enB13"
        private const val ACTION_URL = "$BASE_URL/action"
        private const val TRACK_URL = "$BASE_URL/action/track"

        @JvmStatic
        fun getInstance(): IExtension = SoundloadmateExtension()
    }

    /**
     * Per-instance in-memory CookieJar so `session_data` issued by GET /enB13
     * is automatically attached to subsequent POSTs against the same host.
     * Required: the dart reference uses `cookie_jar` for the same reason.
     */
    private val cookieJar = object : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list += c
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            val valid = list.filter { it.expiresAt > now }
            if (valid.size != list.size) {
                store[url.host] = valid.toMutableList()
            }
            return valid
        }
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 60_000,
        writeTimeoutMs = 30_000
    )
        .cookieJar(cookieJar)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    override fun getId(): String = "soundloadmate"

    override fun getPlatformId(): String = "soundcloud"

    override fun getPlatformName(): String = "SoundCloud"

    override fun getVersion(): String = "1.0.1"

    override fun getDownloaderName(): String = "SoundCloudMate"

    override fun getDownloaderDescription(): String =
        "Download SoundCloud tracks and playlists as MP3 via SoundCloudMate."

    override fun canHandle(url: String): Boolean =
        url.contains("soundcloud.com") || url.contains("on.soundcloud.com")

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeSoundcloud(url)
        } catch (e: Exception) {
            errorJson("SoundCloudMate download failed: ${e.message ?: "Unknown error"}")
        }
    }

    // ── Step 1: hidden token ──────────────────────────────────────────────────

    private data class HiddenToken(val name: String, val value: String)

    private fun fetchHiddenToken(): HiddenToken {
        val req = Request.Builder()
            .url(BASE_URL + INDEX_PATH)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to load SoundCloudMate index page (HTTP ${resp.code})")
            }
            resp.body?.string().orEmpty()
        }

        val doc = Jsoup.parse(html)
        val hidden = doc.selectFirst("input[type=hidden][name][value]")
            ?: throw IllegalStateException("Hidden token not found on SoundCloudMate page")

        val name = hidden.attr("name")
        val value = hidden.attr("value")
        if (name.isBlank() || value.isBlank()) {
            throw IllegalStateException("Hidden token attributes are empty")
        }
        return HiddenToken(name, value)
    }

    // ── Step 2: POST /action → metadata forms ─────────────────────────────────

    private data class TrackForm(val dataBase64: String, val token: String)

    private fun fetchTrackForms(sourceUrl: String, hiddenToken: HiddenToken): List<TrackForm> {
        val body = FormBody.Builder()
            .add("url", sourceUrl)
            .add(hiddenToken.name, hiddenToken.value)
            .build()

        val req = Request.Builder()
            .url(ACTION_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", BASE_URL)
            .header("Referer", BASE_URL + INDEX_PATH)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()

        val raw = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("SoundCloudMate /action returned HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }
        if (raw.isBlank()) throw IllegalStateException("Empty response from SoundCloudMate /action")

        val outer = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("Could not parse /action response as JSON")

        if (outer.get("error")?.asBoolean == true) {
            val message = outer.get("message")?.asString
                ?: outer.get("error_code")?.asString
                ?: "unknown error"
            throw IllegalStateException("SoundCloudMate refused the request: $message")
        }

        val html = outer.get("html")?.asString.orEmpty()
        if (html.isBlank()) {
            throw IllegalStateException("/action response did not include an html payload")
        }

        val doc = Jsoup.parse(html)
        val forms = doc.select("form[name=submitapurl]")
        if (forms.isEmpty()) throw IllegalStateException("No tracks parsed from /action response")

        return forms.mapNotNull { form ->
            val dataValue = form.selectFirst("input[name=data]")?.attr("value")?.takeIf { it.isNotBlank() }
            val tokenValue = form.selectFirst("input[name=token]")?.attr("value")?.takeIf { it.isNotBlank() }
            if (dataValue == null || tokenValue == null) null else TrackForm(dataValue, tokenValue)
        }
    }

    // ── Step 3: POST /action/track → MP3 URL ─────────────────────────────────

    private fun fetchDownloadUrl(form: TrackForm, baseUrl: String): String {
        val body = FormBody.Builder()
            .add("data", form.dataBase64)
            .add("base", baseUrl)
            .add("token", form.token)
            .build()

        val req = Request.Builder()
            .url(TRACK_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", BASE_URL)
            .header("Referer", BASE_URL + INDEX_PATH)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()

        val raw = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("SoundCloudMate /action/track returned HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }
        if (raw.isBlank()) throw IllegalStateException("Empty response from /action/track")

        val outer = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("Could not parse /action/track response as JSON")

        if (outer.get("error")?.asBoolean == true) {
            val message = outer.get("message")?.asString ?: "unknown error"
            throw IllegalStateException("SoundCloudMate refused the track: $message")
        }

        val innerHtml = outer.get("data")?.asString
            ?: throw IllegalStateException("/action/track response did not include data")

        val doc = Jsoup.parse(innerHtml)
        val anchor = doc.selectFirst("a.button.is-download")
            ?: doc.selectFirst("a[class*=is-download]")
            ?: throw IllegalStateException("Download link not found in track response")

        val href = anchor.attr("href").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Download link is empty")
        return href
    }

    // ── Routing & assembly ────────────────────────────────────────────────────

    private fun scrapeSoundcloud(sourceUrl: String): String {
        val token = fetchHiddenToken()
        val forms = fetchTrackForms(sourceUrl, token)

        val tracks = forms.map { form ->
            val metaJson = decodeMetaJson(form.dataBase64)
            ParsedTrack(
                form = form,
                id = metaJson.get("id")?.let { if (it.isJsonPrimitive) it.asString else null }.orEmpty(),
                name = metaJson.get("name")?.asString.orEmpty(),
                artist = metaJson.get("artist")?.asString.orEmpty(),
                cover = metaJson.get("cover")?.asString.orEmpty(),
                link = metaJson.get("link")?.asString.orEmpty(),
                albumName = metaJson.get("albumname")?.asString,
            )
        }

        if (tracks.isEmpty()) {
            throw IllegalStateException("Track metadata is empty")
        }

        // Single track → resolve download URL eagerly so the user can tap once.
        if (tracks.size == 1 && tracks[0].albumName.isNullOrBlank()) {
            val t = tracks[0]
            val downloadUrl = fetchDownloadUrl(t.form, sourceUrl)
            return buildSingleTrackJson(t, downloadUrl)
        }

        // Playlist / album: ship metadata + per-track resolver hint. The main
        // app will call us again with each individual SoundCloud URL via the
        // playlist_item resolver = "polling" pattern (re-scrape per item).
        return buildPlaylistJson(tracks)
    }

    private fun decodeMetaJson(dataBase64: String): JsonObject {
        val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
        val jsonString = String(bytes, Charsets.UTF_8)
        return JsonParser.parseString(jsonString).asJsonObject
    }

    private fun buildSingleTrackJson(track: ParsedTrack, downloadUrl: String): String {
        val title = if (track.artist.isNotBlank()) "${track.artist} - ${track.name}" else track.name
        return gson.toJson(
            mapOf(
                "extensionId" to "soundloadmate",
                "platform" to "soundcloud",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to title,
                "author" to track.artist,
                "authorName" to track.artist,
                "duration" to 0,
                "thumbnail" to track.cover,
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

    private fun buildPlaylistJson(tracks: List<ParsedTrack>): String {
        val first = tracks.first()
        val (playlistName, playlistArtist) = run {
            val rawAlbum = first.albumName?.takeIf { it.isNotBlank() } ?: "Playlist"
            if (rawAlbum.contains(" - ")) {
                val parts = rawAlbum.split(" - ", limit = 2)
                parts[0] to parts[1]
            } else {
                rawAlbum to first.artist
            }
        }
        val cover = first.cover

        val downloadItems = tracks.mapIndexed { index, t ->
            val label = if (t.artist.isNotBlank()) "${t.artist} - ${t.name}" else t.name
            mapOf(
                "key" to "playlist_item_${t.id.ifBlank { index.toString() }}",
                "label" to label,
                "type" to "playlist_item",
                "url" to t.link,
                "mimeType" to "",
                "quality" to "",
                "extra" to mapOf(
                    "thumbnail" to t.cover,
                    "index" to (index + 1),
                    "duration" to 0
                )
            )
        }

        return gson.toJson(
            mapOf(
                "extensionId" to "soundloadmate",
                "platform" to "soundcloud",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to playlistName,
                "author" to playlistArtist,
                "authorName" to playlistArtist,
                "duration" to 0,
                "thumbnail" to cover,
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

    private data class ParsedTrack(
        val form: TrackForm,
        val id: String,
        val name: String,
        val artist: String,
        val cover: String,
        val link: String,
        val albumName: String?,
    )
}
