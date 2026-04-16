package com.torikomi.extension_snapsave_twitter

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.brotli.dec.BrotliInputStream
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream

class TwitterExtension : IExtension {
    companion object {
        private const val TAG = "TWITTER_EXT"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        @JvmStatic
        fun getInstance(): IExtension = TwitterExtension()
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
            Log.d(TAG, "NetworkInterceptor: url=${chain.request().url} encoding=$contentEncoding status=${response.code}")
            if (contentEncoding.equals("br", ignoreCase = true)) {
                return@addNetworkInterceptor try {
                    val responsebody = response.body ?: return@addNetworkInterceptor response
                    val compressedBytes = responsebody.bytes()
                    val decompressedBytes = ByteArrayOutputStream()
                    BrotliInputStream(compressedBytes.inputStream()).use { it.copyTo(decompressedBytes) }
                    val decompressed = decompressedBytes.toByteArray()
                    Log.d(TAG, "Brotli: ${compressedBytes.size} → ${decompressed.size} bytes")
                    response.newBuilder()
                        .body(decompressed.toResponseBody(responsebody.contentType()))
                        .removeHeader("Content-Encoding")
                        .build()
                } catch (e: Exception) {
                    Log.e(TAG, "Brotli decompression failed: ${e.message}", e)
                    response
                }
            }
            response
        }
        .build()
    private val gson = Gson()
    private val apiBase = "https://twitterdownloader.snapsave.app"

    override fun getId(): String = "snapsave_twitter"
    override fun getPlatformId(): String = "twitter"
    override fun getPlatformName(): String = "Twitter"
    override fun getVersion(): String = "1.0.0"
    override fun getDownloaderName(): String = "SnapSave"
    override fun getDownloaderDescription(): String =
        "SnapSave downloader for quickly downloading Twitter/X videos and photos."

    override fun canHandle(url: String): Boolean =
        url.contains("twitter.com") || url.contains("x.com")

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        Log.d(TAG, "scrape() called — url=$url")
        return try {
            scrapeTwitter(url, cfCookies)
        } catch (e: Exception) {
            Log.e(TAG, "scrape() top-level exception: ${e.message}", e)
            errorJson("SnapSave download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeTwitter(url: String, cfCookies: String?): String {
        Log.d(TAG, "getToken() start")
        val token = getToken(cfCookies)
        Log.d(TAG, "getToken() result — blank=${token.isBlank()} value='${token.take(20)}'")
        if (token.isBlank()) throw IllegalStateException("Failed to get token from $apiBase")

        val formBody = FormBody.Builder()
            .add("url", url)
            .add("token", token)
            .build()

        val postReq = Request.Builder()
            .url("$apiBase/action.php")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Origin", apiBase)
            .header("Referer", "$apiBase/")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (!cfCookies.isNullOrBlank()) header("Cookie", cfCookies) }
            .post(formBody)
            .build()

        Log.d(TAG, "POST ${postReq.url} token=${token.take(10)}…")
        client.newCall(postReq).execute().use { postResp ->
            val statusLine = "${postResp.code} ${postResp.message}"
            Log.d(TAG, "POST response — $statusLine")
            if (!postResp.isSuccessful) {
                val body = postResp.body?.string().orEmpty()
                Log.e(TAG, "POST FAILED — $statusLine — body: ${body.take(300)}")
                throw IllegalStateException("API returned $statusLine")
            }
            val raw = postResp.body?.string().orEmpty()
            Log.d(TAG, "POST raw body (first 500): ${raw.take(500)}")
            if (raw.isEmpty()) throw IllegalStateException("Empty response from API")

            val html = extractHtmlData(raw)
            Log.d(TAG, "extractHtmlData — blank=${html.isBlank()} length=${html.length}")
            Log.d(TAG, "HTML snippet (first 500): ${html.take(500)}")
            if (html.isBlank()) throw IllegalStateException("Empty HTML data in response")

            val result = parseTwitterData(html)
            Log.d(TAG, "parseTwitterData result: $result")
            return result
        }
    }

    private fun getToken(cfCookies: String?): String {
        val getReq = Request.Builder()
            .url(apiBase)
            .header("User-Agent", USER_AGENT)
            .apply { if (!cfCookies.isNullOrBlank()) header("Cookie", cfCookies) }
            .build()

        return client.newCall(getReq).execute().use { resp ->
            Log.d(TAG, "getToken response — ${resp.code} ${resp.message}")
            if (!resp.isSuccessful) {
                Log.e(TAG, "getToken FAILED — ${resp.code}")
                return@use ""
            }
            val html = resp.body?.string().orEmpty()
            Log.d(TAG, "getToken HTML length=${html.length}")
            val document = Jsoup.parse(html)
            val token = document.selectFirst("input[name=token]")?.attr("value").orEmpty()
            Log.d(TAG, "getToken token found=${token.isNotBlank()}")
            token
        }
    }

    private fun extractHtmlData(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val json = gson.fromJson(raw, JsonObject::class.java)
            val errorField = json.get("error")
            Log.d(TAG, "extractHtmlData — error field: $errorField")
            json.get("data")?.asString.orEmpty()
        }.getOrElse {
            Log.e(TAG, "extractHtmlData — JSON parse failed: ${it.message}, returning raw")
            raw
        }
    }

    private fun parseTwitterData(htmlContent: String): String {
        val document = Jsoup.parse(htmlContent)

        val authorName = document.selectFirst("h1[itemprop=name] a")?.text()?.trim().orEmpty()
        val caption    = document.selectFirst(".videotikmate-middle > p > span")?.text()?.trim().orEmpty()
        val title      = caption.ifBlank { authorName }
        val thumbnail  = document.selectFirst(".videotikmate-left > img")?.attr("src").orEmpty()

        val desktopHref = document.selectFirst(".videotikmate-right .abuttons > a")?.attr("href").orEmpty()
        val mobileHref  = document.selectFirst("#download-block .abuttons > a")?.attr("href").orEmpty()
        val downloadUrl = desktopHref.ifBlank { mobileHref }

        val buttonText = (document.selectFirst("#download-block .abuttons > a span span")
            ?: document.selectFirst(".videotikmate-right .abuttons > a span span"))
            ?.text()?.trim()?.lowercase().orEmpty()
        val isPhoto = buttonText.contains("photo")

        Log.d(TAG, "parse — author='$authorName' title='${title.take(60)}' thumbnail='$thumbnail'")
        Log.d(TAG, "parse — desktopHref='$desktopHref' mobileHref='$mobileHref' buttonText='$buttonText' isPhoto=$isPhoto")

        val downloadItems = if (!isPhoto && downloadUrl.isNotBlank()) {
            listOf(mapOf("key" to "video_hd", "label" to "Video HD", "type" to "video",
                         "url" to downloadUrl, "mimeType" to "video/mp4", "quality" to "HD"))
        } else emptyList()

        val images = if (isPhoto) {
            val photoUrl = downloadUrl.ifBlank {
                if (thumbnail.contains("pbs.twimg.com/media"))
                    thumbnail.substringBefore("?") + "?format=jpg&name=orig"
                else thumbnail
            }
            if (photoUrl.isNotBlank()) listOf(photoUrl) else emptyList()
        } else emptyList()

        Log.d(TAG, "parse — downloadItems=${downloadItems.size} images=${images.size}")

        return gson.toJson(mapOf(
            "extensionId"    to "twitter",
            "platform"       to "twitter",
            "platformName"   to getPlatformName(),
            "version"        to getVersion(),
            "downloaderName" to getDownloaderName(),
            "description"    to getDownloaderDescription(),
            "title"          to title,
            "author"         to authorName,
            "authorName"     to authorName,
            "duration"       to 0,
            "thumbnail"      to thumbnail,
            "downloadItems"  to downloadItems,
            "playCount"      to 0, "diggCount" to 0, "commentCount" to 0,
            "shareCount"     to 0, "downloadCount" to 0,
            "images"         to images,
        ))
    }

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }
}
