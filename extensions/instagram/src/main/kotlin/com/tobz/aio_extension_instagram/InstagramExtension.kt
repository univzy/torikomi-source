package com.tobz.aio_extension_instagram
import android.content.Context
import com.tobz.aio.extension.IExtension
import com.google.gson.JsonObject

class InstagramExtension : IExtension {
    companion object {
        @JvmStatic
        fun getInstance(): IExtension = InstagramExtension()
    }
    override fun getId() = "instagram"
    override fun getPlatformName() = ""
    override fun getVersion() = "1.0.0"
    override fun canHandle(url: String) = false
    override fun scrape(context: Context, url: String, cfCookies: String?) = "{\"error\":\"Not implemented\"}"
}
