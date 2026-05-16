package com.torikomi.extension_whatsapp_status

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ExtensionProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        return singleResultCursor("""{"error":"WhatsApp Status uses the Torikomi UI."}""")
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
