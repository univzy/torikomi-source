package com.tobz.aio_extension_douyin

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
        private const val SCRAPER_CHANNEL = "aio.extension.douyin/scraper"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val url       = uri.getQueryParameter("url") ?: return null
        val cfCookies = uri.getQueryParameter("cfCookies") ?: ""
        var jsonResult = ""
        val latch = CountDownLatch(1)

        val ctx = context ?: return null
        ctx.mainLooper.let { looper ->
            android.os.Handler(looper).post {
                val engine = FlutterEngine(ctx)
                engine.dartExecutor.executeDartEntrypoint(
                    DartExecutor.DartEntrypoint.createDefault()
                )
                MethodChannel(engine.dartExecutor.binaryMessenger, SCRAPER_CHANNEL)
                    .invokeMethod("scrape", mapOf("url" to url, "cfCookies" to cfCookies),
                        object : MethodChannel.Result {
                            override fun success(result: Any?) {
                                jsonResult = result as? String ?: ""
                                latch.countDown()
                            }
                            override fun error(code: String, msg: String?, details: Any?) {
                                jsonResult = """{"error":"$msg"}"""
                                latch.countDown()
                            }
                            override fun notImplemented() {
                                jsonResult = """{"error":"not implemented"}"""
                                latch.countDown()
                            }
                        })
            }
        }

        latch.await(60, TimeUnit.SECONDS)
        val cursor = MatrixCursor(arrayOf("result"))
        cursor.addRow(arrayOf(jsonResult))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
