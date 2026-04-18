package com.torikomi.extension_spotmate

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.torikomi.extension.IExtension

class ExtensionProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context
        if (ctx == null) {
            return singleResultCursor("""{"error":"context unavailable"}""")
        }

        val url = uri.getQueryParameter("url").orEmpty()
        val cfCookies = uri.getQueryParameter("cfCookies")

        val resultJson = runCatching {
            val extension = SpotmateExtension()
            extension.scrape(ctx, url, cfCookies)
        }.getOrElse { ex ->
            val safeMessage = (ex.message ?: "unknown error").replace("\"", "'")
            "{\"error\":\"$safeMessage\"}"
        }

        return singleResultCursor(resultJson)
    }

    private fun singleResultCursor(resultJson: String): Cursor {
        return MatrixCursor(arrayOf("result")).apply {
            addRow(arrayOf(resultJson))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun getType(uri: Uri) = "vnd.android.cursor.item/torikomi.scrape"
}
