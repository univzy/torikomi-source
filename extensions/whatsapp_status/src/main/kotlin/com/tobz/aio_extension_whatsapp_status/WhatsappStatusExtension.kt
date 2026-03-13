package com.tobz.aio_extension_whatsapp_status
import android.content.Context
import com.tobz.aio.extension.IExtension
import com.google.gson.JsonObject

class WhatsappStatusExtension : IExtension {
    companion object {
        @JvmStatic
        fun getInstance(): IExtension = WhatsappStatusExtension()
    }
    override fun getId() = "whatsapp_status"
    override fun getPlatformName() = ""
    override fun getVersion() = "1.0.0"
    override fun canHandle(url: String) = false
    override fun scrape(context: Context, url: String, cfCookies: String?) = "{\"error\":\"Not implemented\"}"
}
