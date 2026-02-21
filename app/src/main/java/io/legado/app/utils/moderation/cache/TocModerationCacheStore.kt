package io.legado.app.utils.moderation.cache

import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonObject

data class TocModerationCacheItem(
    val chapterIndex: Int,
    val chapterTitle: String,
    val score: Double,
    val flaggedLinesCount: Int
)

data class TocModerationCachePayload(
    val checkedChapters: Int,
    val skippedChapters: Int,
    val flaggedItems: List<TocModerationCacheItem>,
    val updatedAt: Long = System.currentTimeMillis()
)

object TocModerationCacheStore {
    private const val CACHE_NAME = "toc_moderation_cache"

    private val cache by lazy {
        ACache.get(
            cacheName = CACHE_NAME,
            cacheDir = false
        )
    }

    fun buildCacheKey(bookName: String, author: String): String {
        return MD5Utils.md5Encode("$bookName#$author")
    }

    fun get(bookName: String, author: String): TocModerationCachePayload? {
        val key = buildCacheKey(bookName, author)
        val raw = cache.getAsString(key) ?: return null
        return GSON.fromJsonObject<TocModerationCachePayload>(raw).getOrNull()
    }

    fun put(bookName: String, author: String, payload: TocModerationCachePayload) {
        val key = buildCacheKey(bookName, author)
        cache.put(key, GSON.toJson(payload))
    }

    fun remove(bookName: String, author: String) {
        val key = buildCacheKey(bookName, author)
        cache.remove(key)
    }
}
