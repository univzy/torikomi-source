package com.torikomi.extension_snapsave_twitter

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.extension.IExtension
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class TwitterExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        @JvmStatic
        fun getInstance(): IExtension = TwitterExtension()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
                throw IllegalStateException("API returned status ${postResp.code}")
            }

            val raw = postResp.body?.string().orEmpty()
            val html = extractHtmlData(raw)
            if (html.isBlank()) {
                throw IllegalStateException("Empty response data")
            }

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
            if (!resp.isSuccessful) return ""
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
