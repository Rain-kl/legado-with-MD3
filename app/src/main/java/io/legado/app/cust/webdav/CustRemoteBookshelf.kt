package io.legado.app.cust.webdav

import io.legado.app.constant.BookType
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookWebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CustRemoteBookshelf {

    private suspend fun remoteManager(): RemoteBookWebDav {
        return withContext(Dispatchers.IO) {
            appDb.serverDao.get(AppConfig.remoteServerId)?.getWebDavConfig()?.let {
                return@withContext RemoteBookWebDav(it.url, Authorization(it), AppConfig.remoteServerId)
            }
            AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("webDav没有配置")
        }
    }

    suspend fun listRemoteShelfBooks(): List<Book> {
        return withContext(Dispatchers.IO) {
            val manager = remoteManager()
            AppLog.put("远程书籍刷新开始: ${manager.rootBookUrl}")
            val remoteBooks = manager.getRemoteBookList(manager.rootBookUrl)
                .asSequence()
                .filter { !it.isDir }
                .sortedByDescending { it.lastModify }
                .toList()
            val books = remoteBooks.map { toShelfBook(it, manager.serverID) }
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
}
