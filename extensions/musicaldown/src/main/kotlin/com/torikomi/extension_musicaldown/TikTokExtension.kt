package com.torikomi.extension_musicaldown

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.torikomi.extension.IExtension
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class TikTokExtension : IExtension {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

        @JvmStatic
        fun getInstance(): IExtension = TikTokExtension()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val musicalDownUrl = "https://musicaldown.com"
    private val musicalDownApi = "https://musicaldown.com/download"

    override fun getId(): String = "musicaldown"

    override fun getPlatformId(): String = "tiktok"

    override fun getPlatformName(): String = "TikTok"

    override fun getVersion(): String = "1.0.0"

    override fun getDownloaderName(): String = "MusicalDown"

    override fun canHandle(url: String): Boolean {
        return url.contains("tiktok.com") || url.contains("vm.tiktok.com") || url.contains("vt.tiktok.com")
    }

    override fun scrape(context: Context, url: String, cfCookies: String?): String {
        return try {
            scrapeTikTok(url, cfCookies)
        } catch (e: Exception) {
            errorJson("MusicalDown download failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun scrapeTikTok(url: String, cfCookies: String?): String {
        val getReq = Request.Builder()
            .url(musicalDownUrl)
            .header("User-Agent", USER_AGENT)
            .apply {
                if (!cfCookies.isNullOrBlank()) {
                    header("Cookie", cfCookies)
                }
            }
            .build()

        val getResp = client.newCall(getReq).execute()
        if (getResp.code == 403) {
            throw IllegalStateException("403")
        }

        val getHtml = getResp.body?.string().orEmpty()
        val document = Jsoup.parse(getHtml)

        val sessionCookie = getResp.header("set-cookie")
            ?.split(";")
            ?.firstOrNull()
            .orEmpty()

        val cookieHeader = listOfNotNull(
            cfCookies?.takeIf { it.isNotBlank() },
            sessionCookie.takeIf { it.isNotBlank() }
        ).joinToString("; ")

        val inputs = document.select("div > input")
        if (inputs.isEmpty()) {
            throw IllegalStateException("MusicalDown form token not found")
        }

        val firstName = inputs.first()?.attr("name").orEmpty()
        val formBodyBuilder = FormBody.Builder()
        for (input in inputs) {
            val name = input.attr("name")
            if (name.isBlank()) continue
            val value = if (name == firstName) url else input.attr("value")
            formBodyBuilder.add(name, value)
        }

        val postReq = Request.Builder()
            .url(musicalDownApi)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://musidown.com/download")
            .header("Referer", "https://musidown.com/download/en")
            .apply {
                if (cookieHeader.isNotBlank()) {
                    header("Cookie", cookieHeader)
                }
            }
            .post(formBodyBuilder.build())
            .build()

        val postResp = client.newCall(postReq).execute()
        val resultHtml = postResp.body?.string().orEmpty()
        val resultDoc = Jsoup.parse(resultHtml)

        val images = resultDoc
            .select("div.row > div.col.s12.m3")
            .mapNotNull { it.selectFirst("img")?.attr("src")?.trim() }
            .filter { it.isNotEmpty() }

        if (images.isNotEmpty()) {
            return gson.toJson(
                mapOf(
                    "title" to "",
                    "author" to "",
                    "authorName" to "",
                    "duration" to 0,
                    "cover" to "",
                    "videoUrl" to "",
                    "videoUrlHD" to "",
                    "videoUrlWatermark" to "",
                    "music" to "",
                    "playCount" to 0,
                    "diggCount" to 0,
                    "commentCount" to 0,
                    "shareCount" to 0,
                    "downloadCount" to 0,
                    "images" to images
                )
            )
        }

        val videos = mutableMapOf<String, String>()
        val videoContainer = resultDoc.select("div.row > div")
        if (videoContainer.size > 1) {
            val links = videoContainer[1].select("a")
            for (link in links) {
                val href = link.attr("href")
                if (href.isBlank() || href == "#modal2") continue

                val dataEvent = link.attr("data-event")
                when {
                    dataEvent.contains("hd", ignoreCase = true) -> videos["videoHD"] = href
                    dataEvent.contains("mp4", ignoreCase = true) -> videos["videoSD"] = href
                    dataEvent.contains("watermark", ignoreCase = true) -> videos["videoWatermark"] = href
                    href.contains("type=mp3", ignoreCase = true) -> videos["music"] = href
                }
            }
        }

        val avatar = resultDoc.selectFirst("div.img-area > img")?.attr("src").orEmpty()
        val nickname = resultDoc.selectFirst("h2.video-author > b")?.text().orEmpty()
        val desc = resultDoc.selectFirst("p.video-desc")?.text().orEmpty()

        return gson.toJson(
            mapOf(
                "title" to desc,
                "author" to "",
                "authorName" to nickname,
                "duration" to 0,
                "cover" to avatar,
                "videoUrl" to (videos["videoSD"] ?: videos["videoHD"] ?: ""),
                "videoUrlHD" to (videos["videoHD"] ?: ""),
                "videoUrlWatermark" to (videos["videoWatermark"] ?: ""),
                "music" to (videos["music"] ?: ""),
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
