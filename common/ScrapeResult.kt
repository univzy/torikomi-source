package com.torikomi.common

import org.json.JSONArray
import org.json.JSONObject

data class DownloadItem(
    val key: String,
    val label: String,
    val type: String,
    val url: String,
    val mimeType: String,
    val quality: String = "",
    val fileSize: Int? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("key", key)
            put("label", label)
            put("type", type)
            put("url", url)
            put("mimeType", mimeType)
            put("quality", quality)
            fileSize?.let { put("fileSize", it) }
            if (extra.isNotEmpty()) {
                put("extra", JSONObject(extra))
            }
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): DownloadItem {
            val extraObject = json.optJSONObject("extra")
            val extraMap = mutableMapOf<String, Any?>()
            if (extraObject != null) {
                val keys = extraObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extraMap[key] = extraObject.opt(key)
                }
            }

            return DownloadItem(
                key = json.optString("key"),
                label = json.optString("label"),
                type = json.optString("type"),
                url = json.optString("url"),
                mimeType = json.optString("mimeType"),
                quality = json.optString("quality"),
                fileSize = if (json.has("fileSize") && !json.isNull("fileSize")) json.optInt("fileSize") else null,
                extra = extraMap,
            )
        }
    }
}

data class ScrapeResult(
    val extensionId: String,
    val platform: String,
    val title: String,
    val thumbnail: String = "",
    val author: String = "",
    val authorName: String = "",
    val duration: Int = 0,
    val downloadItems: List<DownloadItem> = emptyList(),
    val images: List<String> = emptyList(),
    val error: String? = null,
) {
    val hasError: Boolean get() = !error.isNullOrEmpty()
    val isSuccess: Boolean get() = error == null

    fun toJsonObject(): JSONObject {
        val items = JSONArray()
        downloadItems.forEach { items.put(it.toJsonObject()) }

        val imageArray = JSONArray()
        images.forEach { imageArray.put(it) }

        return JSONObject().apply {
            put("extensionId", extensionId)
            put("platform", platform)
            put("title", title)
            put("thumbnail", thumbnail)
            put("author", author)
            if (authorName.isNotEmpty()) put("authorName", authorName)
            if (duration > 0) put("duration", duration)
            put("downloadItems", items)
            put("images", imageArray)
            if (error != null) put("error", error)
        }
    }

    fun toJsonString(): String = toJsonObject().toString()

    companion object {
        fun error(extensionId: String, platform: String, message: String): ScrapeResult {
            return ScrapeResult(
                extensionId = extensionId,
                platform = platform,
                title = "",
                error = message,
            )
        }

        fun fromJsonObject(json: JSONObject): ScrapeResult {
            val itemArray = json.optJSONArray("downloadItems") ?: JSONArray()
            val items = mutableListOf<DownloadItem>()
            for (index in 0 until itemArray.length()) {
                val item = itemArray.optJSONObject(index) ?: continue
                items.add(DownloadItem.fromJsonObject(item))
            }

            val imageArray = json.optJSONArray("images") ?: JSONArray()
            val imageList = mutableListOf<String>()
            for (index in 0 until imageArray.length()) {
                imageList.add(imageArray.optString(index))
            }

            return ScrapeResult(
                extensionId = json.optString("extensionId"),
                platform = json.optString("platform"),
                title = json.optString("title"),
                thumbnail = json.optString("thumbnail"),
                author = json.optString("author"),
                authorName = json.optString("authorName"),
                duration = json.optInt("duration", 0),
                downloadItems = items,
                images = imageList,
                error = if (json.has("error") && !json.isNull("error")) json.optString("error") else null,
            )
        }

        fun fromJsonString(jsonString: String): ScrapeResult {
            return fromJsonObject(JSONObject(jsonString))
        }
    }
}
