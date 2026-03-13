package com.tobz.aio_extension_pinterest

import android.content.ContentProvider; import android.content.ContentValues
import android.database.Cursor; import android.database.MatrixCursor; import android.net.Uri
import io.flutter.embedding.engine.FlutterEngine; import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch; import java.util.concurrent.TimeUnit

class ExtensionProvider : ContentProvider() {
    companion object { private const val SCRAPER_CHANNEL = "aio.extension.pinterest/scraper"; private const val TIMEOUT = 60L }
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor {
        val url = uri.getQueryParameter("url") ?: ""
        val cfCookies = uri.getQueryParameter("cfCookies")
        val latch = CountDownLatch(1); var json = ""
        val ctx = context ?: return emptyCursor()
        val engine = FlutterEngine(ctx); engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
        val args = mutableMapOf<String, Any?>("url" to url)
        if (!cfCookies.isNullOrEmpty()) args["cfCookies"] = cfCookies
        MethodChannel(engine.dartExecutor.binaryMessenger, SCRAPER_CHANNEL).invokeMethod("scrape", args, object : MethodChannel.Result {
            override fun success(r: Any?) { json = r?.toString() ?: ""; latch.countDown() }
            override fun error(c: String, m: String?, d: Any?) { json = """{"error":"$m"}"""; latch.countDown() }
            override fun notImplemented() { json = """{"error":"not implemented"}"""; latch.countDown() }
        })
        latch.await(TIMEOUT, TimeUnit.SECONDS); engine.destroy()
        return MatrixCursor(arrayOf("result")).also { it.addRow(arrayOf(json)) }
    }
    private fun emptyCursor() = MatrixCursor(arrayOf("result")).also { it.addRow(arrayOf("""{"error":"context unavailable"}""")) }
    override fun insert(uri: Uri, v: ContentValues?) = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun getType(uri: Uri) = "vnd.android.cursor.item/aio.scrape"
}
