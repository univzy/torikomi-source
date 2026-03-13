package com.tobz.aio_extension_spotify
import android.content.Context
import com.tobz.aio.extension.IExtension
import com.google.gson.JsonObject

class SpotifyExtension : IExtension {
    companion object {
        @JvmStatic
        fun getInstance(): IExtension = SpotifyExtension()
    }
    override fun getId() = "spotify"
    override fun getPlatformName() = ""
    override fun getVersion() = "1.0.0"
    override fun canHandle(url: String) = false
    override fun scrape(context: Context, url: String, cfCookies: String?) = "{\"error\":\"Not implemented\"}"
}
