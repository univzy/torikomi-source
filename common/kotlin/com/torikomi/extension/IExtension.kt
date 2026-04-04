package com.torikomi.extension

import android.content.Context

interface IExtension {
    /**
     * Unique downloader extension ID, e.g. musicaldown, ssstik, fastik.
     */
    fun getId(): String

    /**
     * Canonical platform ID, e.g. tiktok, youtube.
     */
    fun getPlatformId(): String

    /**
     * Human-readable platform name, e.g. TikTok, YouTube.
     */
    fun getPlatformName(): String
    fun getVersion(): String
    fun getDownloaderName(): String = "Internal Scraper"
    fun getDownloaderDescription(): String =
        "${getDownloaderName()} is used to download media from the ${getPlatformName()} platform"
    fun canHandle(url: String): Boolean
    fun scrape(context: Context, url: String, cfCookies: String? = null): String
}
