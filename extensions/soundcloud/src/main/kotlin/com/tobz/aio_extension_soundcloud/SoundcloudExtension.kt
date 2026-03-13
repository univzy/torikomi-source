package com.tobz.aio_extension_soundcloud
import android.content.Context
import com.tobz.aio.extension.IExtension
import com.google.gson.JsonObject

class SoundcloudExtension : IExtension {
    companion object {
        @JvmStatic
        fun getInstance(): IExtension = SoundcloudExtension()
    }
    override fun getId() = "soundcloud"
    override fun getPlatformName() = ""
    override fun getVersion() = "1.0.0"
    override fun canHandle(url: String) = false
    override fun scrape(context: Context, url: String, cfCookies: String?) = "{\"error\":\"Not implemented\"}"
}
