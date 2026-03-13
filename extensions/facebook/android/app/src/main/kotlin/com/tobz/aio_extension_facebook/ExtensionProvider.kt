package com.tobz.aio_extension_facebook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExtensionProvider : ContentProvider() {

    companion object {
        private const val SCRAPER_CHANNEL = "aio.extension.facebook/scraper"
        private const val QUERY_TIMEOUT_SEC = 60L
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val url = uri.getQueryParameter("url") ?: ""
        val latch = CountDownLatch(1)
        var resultJson = ""
        val context = context ?: return emptyCursor()

        val engine = FlutterEngine(context)
        engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
        val channel = MethodChannel(engine.dartExecutor.binaryMessenger, SCRAPER_CHANNEL)
        channel.invokeMethod("scrape", mapOf("url" to url), object : MethodChannel.Result {
            override fun success(result: Any?) { resultJson = result?.toString() ?: ""; latch.countDown() }
            override fun error(code: String, msg: String?, details: Any?) { resultJson = """{"error":"$msg"}"""; latch.countDown() }
            override fun notImplemented() { resultJson = """{"error":"not implemented"}"""; latch.countDown() }
        })
        latch.await(QUERY_TIMEOUT_SEC, TimeUnit.SECONDS)
        engine.destroy()

        val cursor = MatrixCursor(arrayOf("result"))
        cursor.addRow(arrayOf(resultJson))
        return cursor
    }

    private fun emptyCursor(): Cursor {
        val c = MatrixCursor(arrayOf("result"))
        c.addRow(arrayOf("""{"error":"context unavailable"}"""))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun getType(uri: Uri) = "vnd.android.cursor.item/aio.scrape"
}
