package com.torikomi.extension_bskysaver

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.browser.BrowserCompatibilityManager
import com.torikomi.extension.IExtension
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

class BskysaverExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        private const val API_BASE = "https://bskysaver.com"

        @JvmStatic
        fun getInstance(): IExtension = BskysaverExtension()
    }

    private val client = BrowserCompatibilityManager.createBrowserCompatibleOkHttpClient(
        connectTimeoutMs = 30_000,
        readTimeoutMs = 30_000,
        writeTimeoutMs = 30_000
    )
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                .header("Accept-Language", "en-US,en;q=0.8")
                .build()
            chain.proceed(req)
        }
        .build()
    private val gson = Gson()

    override fun getId(): String = "bskysaver"
    override fun getPlatformId(): String = "bluesky"
    override fun getPlatformName(): String = "Bluesky"
    override fun getVersion(): String = "1.0.0"
    override fun getDownloaderName(): String = "BskySaver"
    override fun getDownloaderDescription(): String =
        "BskySaver downloader for quickly downloading Bluesky videos, images and GIFs in HD."

    override fun canHandle(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("bsky.app") || normalized.contains("bsky.social")
    }

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeBluesky(url, cfCookies)
        } catch (e: Exception) {
            errorJson("BskySaver download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeBluesky(url: String, cfCookies: String?): String {
        val apiUrl = "$API_BASE/download?url=${URLEncoder.encode(url, "UTF-8")}"

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("Referer", "$API_BASE/")
            .apply { if (!cfCookies.isNullOrBlank()) header("Cookie", cfCookies) }
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("API returned status ${response.code} - ${response.message}")
            }
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) throw IllegalStateException("Empty response from API")
            return parseBlueskyHtml(html)
        }
    }

    /**
     * Response markup (both images and videos) uses:
     *   .download__items > .download_item
     * Each item carries the media preview, author info, caption and a
     * `a.download__item__info__actions__button` whose href is the direct
     * download link (image -> /bskysaver/image, video -> /stream/bskysaver).
     */
    private fun parseBlueskyHtml(html: String): String {
        val doc = Jsoup.parse(html)
        val items = doc.select(".download__items .download_item")
        if (items.isEmpty()) {
            throw IllegalStateException("No downloadable media found in response")
        }

        val downloadItems = mutableListOf<Map<String, Any>>()
        val images = mutableListOf<String>()
        var thumbnail = ""
        var title = ""
        var author = ""
        var authorName = ""

        items.forEachIndexed { index, item ->
            val downloadUrl = item.selectFirst("a.download__item__info__actions__button")
                ?.attr("href").orEmpty()
            if (downloadUrl.isBlank()) return@forEachIndexed

            val isVideo = item.selectFirst(".video_wrapper") != null ||
                item.selectFirst("video") != null ||
                downloadUrl.contains("/stream/")

            // Capture post-level metadata from the first usable item.
            if (title.isBlank()) {
                title = item.selectFirst(".download__item__caption__text")?.text()?.trim().orEmpty()
            }
            if (authorName.isBlank()) {
                authorName = item.selectFirst(".download__item__profile_pic img")?.attr("alt")?.trim().orEmpty()
            }
            if (author.isBlank()) {
                author = item.selectFirst(".download__item__profile_pic span")?.text()?.trim().orEmpty()
            }

            if (isVideo) {
                val poster = item.selectFirst("video")?.attr("poster").orEmpty()
                    .ifBlank { item.selectFirst("[poster]")?.attr("poster").orEmpty() }
                    .ifBlank { item.selectFirst(".vjs-poster img")?.attr("src").orEmpty() }
                if (thumbnail.isBlank() && poster.isNotBlank()) thumbnail = poster

                downloadItems += mapOf(
                    "key" to "video_${index + 1}",
                    "label" to "Video",
                    "type" to "video",
                    "url" to downloadUrl,
                    "mimeType" to "video/mp4",
                    "quality" to "HD"
                )
            } else {
                val imageSrc = item.selectFirst("img.image__item")?.attr("src").orEmpty()
                    .ifBlank { downloadUrl }
                if (thumbnail.isBlank() && imageSrc.isNotBlank()) thumbnail = imageSrc

                downloadItems += mapOf(
                    "key" to "image_${index + 1}",
                    "label" to "Photo ${index + 1}",
                    "type" to "image",
                    "url" to downloadUrl,
                    "mimeType" to "image/jpeg",
                    "quality" to "Best"
                )
                if (imageSrc.isNotBlank()) images += imageSrc
            }
        }

        if (downloadItems.isEmpty()) {
            throw IllegalStateException("No download links found in response")
        }

        return gson.toJson(
            buildResultMap(
                title = title,
                author = author.ifBlank { authorName },
                authorName = authorName,
                thumbnail = thumbnail,
                downloadItems = downloadItems,
                images = images
            )
        )
    }

    private fun buildResultMap(
        title: String,
        author: String,
        authorName: String,
        thumbnail: String,
        downloadItems: List<Map<String, Any>>,
        images: List<String>,
    ): Map<String, Any> = mapOf(
        "extensionId" to getId(),
        "platform" to getPlatformId(),
        "platformName" to getPlatformName(),
        "version" to getVersion(),
        "downloaderName" to getDownloaderName(),
        "description" to getDownloaderDescription(),
        "title" to title,
        "author" to author,
        "authorName" to authorName,
        "duration" to 0,
        "thumbnail" to thumbnail,
        "downloadItems" to downloadItems,
        "images" to images,
        "playCount" to 0,
        "diggCount" to 0,
        "commentCount" to 0,
        "shareCount" to 0,
        "downloadCount" to 0
    )

    private fun errorJson(message: String): String {
        val json = JsonObject()
        json.addProperty("error", message)
        return json.toString()
    }
}
