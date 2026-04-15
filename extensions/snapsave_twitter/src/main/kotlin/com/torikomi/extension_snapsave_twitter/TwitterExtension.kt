package com.torikomi.extension_snapsave_twitter

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.brotli.dec.BrotliInputStream
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class TwitterExtension : IExtension {
    companion object {
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
                    
                    Log.d("TWITTER", "Brotli decompressed: ${compressedBytes.size} → ${decompressed.size} bytes")
                    
                    response.newBuilder()
                        .body(decompressed.toResponseBody(responsebody.contentType()))
                        .removeHeader("Content-Encoding")
                        .build()
                } catch (e: Exception) {
                    Log.e("TWITTER", "Brotli decompression failed: ${e.message}", e)
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

    override fun canHandle(url: String): Boolean {
        return url.contains("twitter.com") || url.contains("x.com")
    }

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeTwitter(url, cfCookies)
        } catch (e: Exception) {
            errorJson("SnapSave download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeTwitter(url: String, cfCookies: String?): String {
        val token = getToken(cfCookies)
        if (token.isBlank()) {
            throw IllegalStateException("Failed to get token")
        }

        val formBody = FormBody.Builder()
            .add("url", url)
            .add("token", token)
            .build()

        val postReq = Request.Builder()
            .url("$apiBase/action.php")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", apiBase)
            .header("Referer", "$apiBase/")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                if (!cfCookies.isNullOrBlank()) {
                    header("Cookie", cfCookies)
                }
            }
            .post(formBody)
            .build()

        client.newCall(postReq).execute().use { postResp ->
            if (!postResp.isSuccessful) {
                val errBody = postResp.body?.string().orEmpty()
                Log.e("TWITTER", "scrapeTwitter FAILED - Status: ${postResp.code} ${postResp.message} - Body: ${errBody.take(500)}")
                throw IllegalStateException("API returned status ${postResp.code} - ${postResp.message}")
            }

            val raw = postResp.body?.string().orEmpty()
            if (raw.isEmpty()) {
                Log.e("TWITTER", "scrapeTwitter - Empty response body")
                throw IllegalStateException("Empty response data")
            }
            
            Log.d("TWITTER", "scrapeTwitter - Got response, extracting data...")
            val html = extractHtmlData(raw)
            if (html.isBlank()) {
                Log.e("TWITTER", "scrapeTwitter - Empty extracted data")
                throw IllegalStateException("Empty response data")
            }

            Log.d("TWITTER", "scrapeTwitter - SUCCESS")
            return parseTwitterData(html)
        }
    }

    private fun getToken(cfCookies: String?): String {
        val getReq = Request.Builder()
            .url(apiBase)
            .header("User-Agent", USER_AGENT)
            .apply {
                if (!cfCookies.isNullOrBlank()) {
                    header("Cookie", cfCookies)
                }
            }
            .build()

        return client.newCall(getReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                Log.e("TWITTER", "getToken FAILED - Status: ${resp.code} ${resp.message} - Body: ${errBody.take(200)}")
                return@use ""
            }
            val html = resp.body?.string().orEmpty()
            val document = Jsoup.parse(html)
            document.selectFirst("input[name=token]")?.attr("value").orEmpty()
        }
    }

    private fun extractHtmlData(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val json = gson.fromJson(raw, JsonObject::class.java)
            json.get("data")?.asString.orEmpty()
        }.getOrElse { raw }
    }

    private fun parseTwitterData(htmlContent: String): String {
        val document = Jsoup.parse(htmlContent)
        val downloadBlock = document.selectFirst("#download-block")
            ?: throw IllegalStateException("Download block not found")

        val downloadUrl = downloadBlock
            .selectFirst(".abuttons > a")
            ?.attr("href")
            .orEmpty()
        if (downloadUrl.isBlank()) {
            throw IllegalStateException("Download URL not found")
        }

        val description = document
            .selectFirst(".videotikmate-middle > p > span")
            ?.text()
            ?.trim()
            .orEmpty()

        val thumbnail = document
            .selectFirst(".videotikmate-left > img")
            ?.attr("src")
            .orEmpty()

        val buttonText = downloadBlock
            .selectFirst(".abuttons > a > span > span")
            ?.text()
            ?.trim()
            ?.lowercase()
            .orEmpty()

        val isVideo = !buttonText.contains("photo")

        val downloadItems = if (isVideo) {
            listOf(
                mapOf(
                    "key" to "video_hd",
                    "label" to "Video HD",
                    "type" to "video",
                    "url" to downloadUrl,
                    "mimeType" to "video/mp4",
                    "quality" to "HD",
                )
            )
        } else {
            emptyList()
        }

        val images = if (isVideo) emptyList() else listOf(downloadUrl)

        return gson.toJson(
            mapOf(
                "extensionId" to "twitter",
                "platform" to "twitter",
                "platformName" to getPlatformName(),
                "version" to getVersion(),
                "downloaderName" to getDownloaderName(),
                "description" to getDownloaderDescription(),
                "title" to description,
                "author" to "",
                "authorName" to "",
                "duration" to 0,
                "thumbnail" to thumbnail,
                "downloadItems" to downloadItems,
                "playCount" to 0,
                "diggCount" to 0,
                "commentCount" to 0,
                "shareCount" to 0,
                "downloadCount" to 0,
                "images" to images,
            )
        )
    }

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }
}
