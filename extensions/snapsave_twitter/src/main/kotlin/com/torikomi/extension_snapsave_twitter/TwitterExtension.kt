package com.torikomi.extension_snapsave_twitter

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup

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
            val requestWithHeaders = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            chain.proceed(requestWithHeaders)
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
        return try {
            scrapeTwitter(url, cfCookies)
        } catch (e: Exception) {
            errorJson("SnapSave download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeTwitter(url: String, cfCookies: String?): String {
        val token = getToken(cfCookies)
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

        client.newCall(postReq).execute().use { postResp ->
            if (!postResp.isSuccessful) {
                throw IllegalStateException("API returned ${postResp.code} ${postResp.message}")
            }
            val raw = postResp.body?.string().orEmpty()
            if (raw.isEmpty()) throw IllegalStateException("Empty response from API")

            val html = extractHtmlData(raw)
            if (html.isBlank()) throw IllegalStateException("Empty HTML data in response")
            return parseTwitterData(html)
        }
    }

    private fun getToken(cfCookies: String?): String {
        val getReq = Request.Builder()
            .url(apiBase)
            .header("User-Agent", USER_AGENT)
            .apply { if (!cfCookies.isNullOrBlank()) header("Cookie", cfCookies) }
            .build()

        return client.newCall(getReq).execute().use { resp ->
            if (!resp.isSuccessful) return@use ""
            val html = resp.body?.string().orEmpty()
            Jsoup.parse(html).selectFirst("input[name=token]")?.attr("value").orEmpty()
        }
    }

    private fun extractHtmlData(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            gson.fromJson(raw, JsonObject::class.java).get("data")?.asString.orEmpty()
        }.getOrElse { raw }
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
