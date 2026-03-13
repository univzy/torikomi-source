package com.tobz.aio.extension

import android.content.Context

/**
 * Base interface untuk semua AIO extensions.
 * Implementasi harus:
 * 1. Declare di AndroidManifest dengan meta-data aio.extension=true
 * 2. Expose static method: companion object { fun getInstance(): IExtension }
 */
interface IExtension {
    /**
     * Extension ID (e.g., "tiktok", "youtube")
     */
    fun getId(): String

    /**
     * Platform name (e.g., "TikTok", "YouTube")
     */
    fun getPlatformName(): String

    /**
     * Version string (e.g., "1.0.0")
     */
    fun getVersion(): String

    /**
     * Scrape content dari URL dengan context dan optional CF cookies
     * Return JSON string hasil scrape (format: {"type":"...", "quality":"...", "url":"...", ...})
     */
    fun scrape(context: Context, url: String, cfCookies: String?): String

    /**
     * Check apakah URL valid untuk extension ini
     */
    fun canHandle(url: String): Boolean
}
