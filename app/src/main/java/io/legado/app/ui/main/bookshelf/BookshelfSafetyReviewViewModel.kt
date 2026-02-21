package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.utils.moderation.config.ModerationConfig
import io.legado.app.utils.moderation.core.ContentAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UnsafeBookItem(
    val book: Book,
    val flaggedChapterCount: Int,
    val firstFlaggedChapterTitle: String
)

data class SafetyReviewProgress(
    val running: Boolean = false,
    val totalBooks: Int = 0,
    val processedBooks: Int = 0,
    val unsafeBooks: Int = 0
)

class BookshelfSafetyReviewViewModel(application: Application) : BaseViewModel(application) {

    private val analyzer by lazy { ContentAnalyzer(ModerationConfig.defaults()) }
    private val dispatcher by lazy {
        Dispatchers.Default.limitedParallelism(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        )
    }

    private val _unsafeBooks = MutableStateFlow<List<UnsafeBookItem>>(emptyList())
    val unsafeBooks: StateFlow<List<UnsafeBookItem>> = _unsafeBooks.asStateFlow()

    private val _progress = MutableStateFlow(SafetyReviewProgress())
    val progress: StateFlow<SafetyReviewProgress> = _progress.asStateFlow()

    var scanJob: Job? = null
        private set

    fun startSafetyReview() {
        if (_progress.value.running) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val localBooks = appDb.bookDao.all.filter { it.isLocal }
            if (localBooks.isEmpty()) {
                _unsafeBooks.value = emptyList()
                _progress.value = SafetyReviewProgress(
                    running = false,
                    totalBooks = 0,
                    processedBooks = 0,
                    unsafeBooks = 0
                )
                return@launch
            }

            _unsafeBooks.value = emptyList()
            _progress.value = SafetyReviewProgress(
                running = true,
                totalBooks = localBooks.size,
                processedBooks = 0,
                unsafeBooks = 0
            )

            val unsafe = coroutineScope {
                localBooks.map { book ->
                    async(dispatcher) {
                        val result = scanSingleBook(book)
                        _progress.update { old ->
                            old.copy(
                                processedBooks = old.processedBooks + 1,
                                unsafeBooks = old.unsafeBooks + if (result != null) 1 else 0
                            )
                        }
                        result
                    }
                }.awaitAll().mapNotNull { it }
            }

            _unsafeBooks.value = unsafe
            _progress.update { old -> old.copy(running = false) }
        }
    }

    fun addToGroup(books: List<Book>, groupId: Long) {
        if (books.isEmpty()) return
        execute {
            val updated = books.map { it.copy(group = groupId) }
            appDb.bookDao.update(*updated.toTypedArray())
        }
    }

    private fun scanSingleBook(book: Book): UnsafeBookItem? {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
        if (chapters.isEmpty()) return null

        var flaggedCount = 0
        var firstFlaggedTitle = ""
        for (chapter in chapters) {
            val raw = BookHelp.getContent(book, chapter) ?: continue
            if (raw.isBlank()) continue

            val lines = raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.isEmpty()) continue

            val quick = analyzer.analyzeChapterQuick(lines)
            if (quick.isFlagged) {
                flaggedCount++
                if (firstFlaggedTitle.isEmpty()) {
                    firstFlaggedTitle = chapter.title
                }
                // For bookshelf-level triage, one hit is enough to mark unsafe.
                break
            }
        }

        return if (flaggedCount > 0) {
            UnsafeBookItem(
                book = book,
                flaggedChapterCount = flaggedCount,
                firstFlaggedChapterTitle = firstFlaggedTitle
            )
        } else {
            null
        }
    }
}
