package io.legado.app.cust.webdav

import io.legado.app.constant.BookType
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.book.isRemoteMetadataMissing
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

object CustRemoteBookshelf {

    private const val CACHE_FILE_NAME = "remote_shelf_cache.json"
    private const val META_MISSING_ATTR = "metaMissing"

    private val cacheFile: File
        get() = File(appCtx.filesDir, CACHE_FILE_NAME)

    private suspend fun remoteManager(): RemoteBookWebDav {
        return withContext(Dispatchers.IO) {
            appDb.serverDao.get(AppConfig.remoteServerId)?.getWebDavConfig()?.let {
                return@withContext RemoteBookWebDav(it.url, Authorization(it), AppConfig.remoteServerId)
            }
            AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("webDav没有配置")
        }
    }

    suspend fun listRemoteShelfBooks(forceRefresh: Boolean = false): List<Book> {
        return withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                readCacheBooks().takeIf { it.isNotEmpty() }?.let {
                    val hasMetaFlag = it.any { cacheBook -> cacheBook.origin.contains(META_MISSING_ATTR) }
                    if (hasMetaFlag) {
                        AppLog.put("远程书籍使用本地缓存: ${it.size} 本")
                        return@withContext it
                    }
                }
            }
            val manager = remoteManager()
            AppLog.put("远程书籍刷新开始: ${manager.rootBookUrl}")
            val remoteBooks = manager.getRemoteBookList(manager.rootBookUrl)
                .asSequence()
                .filter { !it.isDir }
                .sortedByDescending { it.lastModify }
                .toList()
            val localBooks = appDb.bookDao.all
            val localByNameAuthor = localBooks
                .groupBy { normalizeNameAuthor(it.name, it.author) }
                .mapValues { (_, list) -> list.maxByOrNull { it.durChapterTime } }
            val localByFileName = localBooks
                .groupBy { normalizeFileName(it.originName) }
                .mapValues { (_, list) -> list.maxByOrNull { it.durChapterTime } }
            val remoteItems = remoteBooks.map { remote ->
                val shelfBook = toShelfBook(remote, manager.serverID)
                val localBook = localByFileName[normalizeFileName(remote.filename)]
                    ?: localByNameAuthor[normalizeNameAuthor(shelfBook.name, shelfBook.author)]
                val metaMissing = !hasLocalMetadata(localBook)
                val mergedBook = mergeWithLocalBook(shelfBook, localBook)
                markMetaMissing(mergedBook, metaMissing)
                mergedBook
            }
            val books = remoteItems.partition { it.isRemoteMetadataMissing() }
                .let { (metaMissingBooks, normalBooks) -> metaMissingBooks + normalBooks }
            writeCacheBooks(books)
            AppLog.put("远程书籍刷新完成: ${books.size} 本")
            books
        }
    }

    suspend fun ensureLocalBook(remoteShelfBook: Book): Book {
        return withContext(Dispatchers.IO) {
            appDb.bookDao.getBookByFileName(remoteShelfBook.originName)?.let {
                return@withContext it
            }
            val remoteUrl = remoteShelfBook.origin
                .removePrefix(BookType.webDavTag)
                .let { CustomUrl(it).getUrl() }
            val manager = remoteManager()
            val remoteBook = manager.getRemoteBook(remoteUrl)
                ?: throw NoStackTraceException("远程文件不存在")
            val localUri = manager.downloadRemoteBook(remoteBook)
            val importedBooks = LocalBook.importFiles(localUri)
            importedBooks.forEach { imported ->
                imported.origin = BookType.webDavTag + CustomUrl(remoteBook.path)
                    .putAttribute("serverID", manager.serverID)
                    .toString()
                imported.save()
            }
            appDb.bookDao.getBookByFileName(remoteBook.filename)
                ?: importedBooks.firstOrNull()
                ?: throw NoStackTraceException("导入失败")
        }
    }

    private fun toShelfBook(remoteBook: RemoteBook, serverID: Long?): Book {
        val (name, author) = LocalBook.parseNameAuthorByFileName(remoteBook.filename)
        return Book(
            bookUrl = "cust_remote://${remoteBook.path.hashCode()}",
            origin = BookType.webDavTag + CustomUrl(remoteBook.path)
                .putAttribute("serverID", serverID)
                .toString(),
            originName = remoteBook.filename,
            name = name,
            author = author,
            type = BookType.local or BookType.text,
            latestChapterTime = remoteBook.lastModify
        )
    }

    private fun mergeWithLocalBook(remoteBook: Book, localBook: Book?): Book {
        if (localBook == null) return remoteBook
        remoteBook.type = localBook.type
        remoteBook.coverUrl = localBook.coverUrl
        remoteBook.customCoverUrl = localBook.customCoverUrl
        remoteBook.intro = localBook.intro
        remoteBook.customIntro = localBook.customIntro
        remoteBook.remark = localBook.remark
        remoteBook.latestChapterTitle = localBook.latestChapterTitle
        remoteBook.totalChapterNum = localBook.totalChapterNum
        remoteBook.durChapterTitle = localBook.durChapterTitle
        remoteBook.durChapterIndex = localBook.durChapterIndex
        remoteBook.durChapterPos = localBook.durChapterPos
        remoteBook.durChapterTime = localBook.durChapterTime
        remoteBook.wordCount = localBook.wordCount
        return remoteBook
    }

    private fun hasLocalMetadata(localBook: Book?): Boolean {
        localBook ?: return false
        return !localBook.getDisplayCover().isNullOrBlank()
                || !localBook.latestChapterTitle.isNullOrBlank()
                || localBook.totalChapterNum > 0
                || !localBook.getDisplayIntro().isNullOrBlank()
    }

    private fun markMetaMissing(book: Book, metaMissing: Boolean) {
        val customUrl = CustomUrl(book.origin.removePrefix(BookType.webDavTag))
            .putAttribute(META_MISSING_ATTR, metaMissing)
        book.origin = BookType.webDavTag + customUrl.toString()
    }

    private fun normalizeFileName(fileName: String): String {
        return fileName.trim().lowercase()
    }

    private fun normalizeNameAuthor(name: String, author: String): String {
        return "${name.trim().lowercase()}\u0000${author.trim().lowercase()}"
    }

    private fun readCacheBooks(): List<Book> {
        return kotlin.runCatching {
            if (!cacheFile.exists()) return emptyList()
            GSON.fromJsonArray<Book>(cacheFile.readText()).getOrNull() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun writeCacheBooks(books: List<Book>) {
        kotlin.runCatching {
            cacheFile.writeText(GSON.toJson(books))
        }.onFailure {
            AppLog.put("写入远程书籍缓存失败\n${it.localizedMessage}", it)
        }
    }
}
