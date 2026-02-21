package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.help.storage.Restore
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreFlowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tempDirs = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        // Reset database to a deterministic baseline before each test.
        val empty = createBackupDir("reset")
        Restore.restoreLocked(empty.absolutePath)
    }

    @After
    fun tearDown() {
        tempDirs.forEach { dir ->
            dir.deleteRecursively()
        }
        tempDirs.clear()
    }

    @Test
    fun restoreOverwrite_shouldReplaceOldDataAndRestoreAllMainTables() = runBlocking {
        seedOldData()

        val backupDir = createBackupDir("full")
        val backupBook = Book(
            bookUrl = "/storage/emulated/0/Books/test.txt",
            origin = BookType.localTag,
            originName = "test.txt",
            name = "new-book",
            author = "new-author",
            type = 0, // old type value, restore should normalize with upType
            coverUrl = "old-cover"
        )
        writeJson(backupDir, "bookshelf.json", listOf(backupBook))
        writeJson(
            backupDir,
            "bookmark.json",
            listOf(Bookmark(time = 2001L, bookName = "new-book", bookAuthor = "new-author"))
        )
        writeJson(
            backupDir,
            "bookGroup.json",
            listOf(BookGroup(groupId = 1024L, groupName = "backup-group", order = 10))
        )
        writeJson(
            backupDir,
            "bookSource.json",
            listOf(
                BookSource(
                    bookSourceUrl = "https://backup-source",
                    bookSourceName = "backup-source-name"
                )
            )
        )
        writeJson(
            backupDir,
            "rssSources.json",
            listOf(RssSource(sourceUrl = "https://backup-rss", sourceName = "backup-rss-name"))
        )
        writeJson(
            backupDir,
            "rssStar.json",
            listOf(RssStar(origin = "https://backup-rss", link = "https://backup-rss/article"))
        )
        writeJson(
            backupDir,
            "replaceRule.json",
            listOf(ReplaceRule(id = 3001L, name = "backup-replace", pattern = "a", replacement = "b"))
        )
        writeJson(
            backupDir,
            "searchHistory.json",
            listOf(SearchKeyword(word = "backup-keyword", usage = 9))
        )
        writeJson(
            backupDir,
            "sourceSub.json",
            listOf(RuleSub(id = 4001L, name = "backup-sub", url = "https://sub"))
        )
        writeJson(
            backupDir,
            "txtTocRule.json",
            listOf(TxtTocRule(id = 5001L, name = "backup-toc", rule = "chapter"))
        )
        writeJson(
            backupDir,
            "httpTTS.json",
            listOf(HttpTTS(id = 6001L, name = "backup-tts", url = "https://tts"))
        )
        writeJson(
            backupDir,
            "dictRule.json",
            listOf(DictRule(name = "backup-dict", urlRule = "https://dict?q={{key}}"))
        )
        writeJson(
            backupDir,
            "keyboardAssists.json",
            listOf(KeyboardAssist(type = 99, key = "K", value = "V", serialNo = 1))
        )
        writeJson(
            backupDir,
            "readRecord.json",
            listOf(
                ReadRecord(
                    deviceId = "device-remote",
                    bookName = "new-book",
                    bookAuthor = "new-author",
                    readTime = 321L
                )
            )
        )
        writeJson(
            backupDir,
            "readRecordDetail.json",
            listOf(
                ReadRecordDetail(
                    deviceId = "device-remote",
                    bookName = "new-book",
                    bookAuthor = "new-author",
                    date = "2026-02-21",
                    readTime = 321L
                )
            )
        )
        writeJson(
            backupDir,
            "readRecordSession.json",
            listOf(
                ReadRecordSession(
                    id = 7001L,
                    deviceId = "device-remote",
                    bookName = "new-book",
                    bookAuthor = "new-author",
                    startTime = 1000L,
                    endTime = 1321L,
                    words = 100
                )
            )
        )
        writeJson(
            backupDir,
            "servers.json",
            listOf(Server(id = 8001L, name = "backup-server", sortNumber = 1))
        )

        Restore.restoreLocked(backupDir.absolutePath)

        assertNull(appDb.bookDao.getBook("https://old-book"))
        val restoredBook = appDb.bookDao.getBook(backupBook.bookUrl)
        assertNotNull(restoredBook)
        assertTrue(restoredBook!!.type and BookType.local > 0)
        assertEquals(LocalBook.getCoverPath(restoredBook), restoredBook.coverUrl)

        assertTrue(appDb.bookmarkDao.all.any { it.bookName == "new-book" })
        assertFalse(appDb.bookmarkDao.all.any { it.bookName == "old-book" })
        assertNotNull(appDb.bookGroupDao.getByID(1024L))
        assertNull(appDb.bookSourceDao.getBookSource("https://old-source"))
        assertNotNull(appDb.bookSourceDao.getBookSource("https://backup-source"))
        assertTrue(appDb.rssSourceDao.all.any { it.sourceUrl == "https://backup-rss" })
        assertTrue(appDb.rssStarDao.all.any { it.link == "https://backup-rss/article" })
        assertTrue(appDb.replaceRuleDao.all.any { it.name == "backup-replace" })
        assertTrue(appDb.searchKeywordDao.all.any { it.word == "backup-keyword" })
        assertTrue(appDb.ruleSubDao.all.any { it.id == 4001L })
        assertTrue(appDb.txtTocRuleDao.all.any { it.id == 5001L })
        assertTrue(appDb.httpTTSDao.all.any { it.id == 6001L })
        assertTrue(appDb.dictRuleDao.all.any { it.name == "backup-dict" })
        assertTrue(appDb.keyboardAssistsDao.all.any { it.type == 99 && it.key == "K" })
        assertTrue(appDb.readRecordDao.all.any { it.bookName == "new-book" })
        assertTrue(appDb.readRecordDao.allDetail.any { it.date == "2026-02-21" })
        assertTrue(appDb.readRecordDao.allSession.any { it.id == 7001L })
        assertTrue(appDb.serverDao.all.any { it.id == 8001L })
        assertFalse(appDb.serverDao.all.any { it.id == 9001L })
    }

    @Test
    fun restoreOverwrite_whenFileMissing_shouldKeepPresetAndRemoveOldBusinessData() = runBlocking {
        appDb.replaceRuleDao.insert(ReplaceRule(id = 9101L, name = "to-be-cleared", pattern = "x"))

        val backupDir = createBackupDir("partial")
        writeJson(
            backupDir,
            "bookshelf.json",
            listOf(
                Book(
                    bookUrl = "https://from-backup",
                    origin = "https://source",
                    name = "from-backup",
                    author = "author"
                )
            )
        )

        Restore.restoreLocked(backupDir.absolutePath)

        val presetIds = listOf(
            BookGroup.IdAll,
            BookGroup.IdLocal,
            BookGroup.IdText,
            BookGroup.IdManga,
            BookGroup.IdAudio,
            BookGroup.IdReading,
            BookGroup.IdUnread,
            BookGroup.IdReadFinished,
            BookGroup.IdRemote,
            BookGroup.IdNetNone,
            BookGroup.IdLocalNone,
            BookGroup.IdError
        )
        presetIds.forEach { groupId ->
            assertNotNull("missing preset group: $groupId", appDb.bookGroupDao.getByID(groupId))
        }
        assertTrue(appDb.keyboardAssistsDao.all.isNotEmpty())
        assertTrue(appDb.replaceRuleDao.all.isEmpty())
    }

    @Test
    fun restoreOverwrite_shouldClearServerTableWhenBackupHasNoServersFile() = runBlocking {
        appDb.serverDao.insert(Server(id = 9201L, name = "old-server"))
        val backupDir = createBackupDir("no-server")

        Restore.restoreLocked(backupDir.absolutePath)

        assertTrue(appDb.serverDao.all.isEmpty())
    }

    private suspend fun seedOldData() {
        appDb.bookDao.insert(
            Book(
                bookUrl = "https://old-book",
                origin = "https://old-source",
                name = "old-book",
                author = "old-author"
            )
        )
        appDb.bookmarkDao.insert(Bookmark(time = 1001L, bookName = "old-book", bookAuthor = "old-author"))
        appDb.bookGroupDao.insert(BookGroup(groupId = 512L, groupName = "old-group"))
        appDb.bookSourceDao.insert(BookSource(bookSourceUrl = "https://old-source", bookSourceName = "old-source-name"))
        appDb.rssSourceDao.insert(RssSource(sourceUrl = "https://old-rss", sourceName = "old-rss-name"))
        appDb.rssStarDao.insert(RssStar(origin = "https://old-rss", link = "https://old-rss/article"))
        appDb.replaceRuleDao.insert(ReplaceRule(id = 2001L, name = "old-replace", pattern = "x", replacement = "y"))
        appDb.searchKeywordDao.insert(SearchKeyword(word = "old-keyword"))
        appDb.ruleSubDao.insert(RuleSub(id = 2002L, name = "old-sub", url = "https://old-sub"))
        appDb.txtTocRuleDao.insert(TxtTocRule(id = 2003L, name = "old-toc", rule = "old"))
        appDb.httpTTSDao.insert(HttpTTS(id = 2004L, name = "old-tts", url = "https://old-tts"))
        appDb.dictRuleDao.insert(DictRule(name = "old-dict", urlRule = "https://old-dict"))
        appDb.keyboardAssistsDao.insert(KeyboardAssist(type = 98, key = "OLD", value = "OLD", serialNo = 1))
        appDb.readRecordDao.insert(
            ReadRecord(
                deviceId = "device-local",
                bookName = "old-book",
                bookAuthor = "old-author",
                readTime = 123
            )
        )
        appDb.readRecordDao.insertDetail(
            ReadRecordDetail(
                deviceId = "device-local",
                bookName = "old-book",
                bookAuthor = "old-author",
                date = "2026-01-01",
                readTime = 123
            )
        )
        appDb.readRecordDao.insertSession(
            ReadRecordSession(
                id = 2005L,
                deviceId = "device-local",
                bookName = "old-book",
                bookAuthor = "old-author",
                startTime = 1000L,
                endTime = 1123L
            )
        )
        appDb.serverDao.insert(Server(id = 9001L, name = "old-server", sortNumber = 1))
    }

    private fun writeJson(dir: File, fileName: String, value: Any) {
        File(dir, fileName).writeText(GSON.toJson(value))
    }

    private fun createBackupDir(prefix: String): File {
        val dir = File(context.cacheDir, "restore-test-$prefix-${UUID.randomUUID()}")
        dir.mkdirs()
        tempDirs.add(dir)
        return dir
    }
}
