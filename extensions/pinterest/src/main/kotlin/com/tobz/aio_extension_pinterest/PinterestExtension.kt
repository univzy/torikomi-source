package com.tobz.aio_extension_pinterest
import android.content.Context
import com.tobz.aio.extension.IExtension
import com.google.gson.JsonObject

class PinterestExtension : IExtension {
    companion object {
        @JvmStatic
        fun getInstance(): IExtension = PinterestExtension()
    }
    override fun getId() = "pinterest"
    override fun getPlatformName() = ""
    override fun getVersion() = "1.0.0"
    override fun canHandle(url: String) = false
    override fun scrape(context: Context, url: String, cfCookies: String?) = "{\"error\":\"Not implemented\"}"
}
