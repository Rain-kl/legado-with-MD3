package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.moderation.TextModerationService
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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

    private val moderationService by lazy { TextModerationService.create() }
    private val dispatcher by lazy {
        Dispatchers.Default.limitedParallelism(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 12)
        )
    }

    private val _unsafeBooks = MutableStateFlow<List<UnsafeBookItem>>(emptyList())
    val unsafeBooks: StateFlow<List<UnsafeBookItem>> = _unsafeBooks.asStateFlow()

    private val _progress = MutableStateFlow(SafetyReviewProgress())
    val progress: StateFlow<SafetyReviewProgress> = _progress.asStateFlow()

    var scanJob: Job? = null
        private set

    private enum class SkipReason {
        UNSUPPORTED_TYPE,
        EMPTY_CONTENT
    }

    private data class ScanOutcome(
        val unsafeItem: UnsafeBookItem? = null,
        val skipReason: SkipReason? = null
    )

    fun startSafetyReview() {
        if (_progress.value.running) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val localBooks = appDb.bookDao.all.filter { it.isLocal }
            AppLog.put(
                "书架安全审查开始: localBooks=${localBooks.size}, cores=${
                    Runtime.getRuntime().availableProcessors()
                }"
            )
            if (localBooks.isEmpty()) {
                _unsafeBooks.value = emptyList()
                _progress.value = SafetyReviewProgress(
                    running = false,
                    totalBooks = 0,
                    processedBooks = 0,
                    unsafeBooks = 0
                )
                AppLog.put("书架安全审查结束: 没有可审查的本地书籍")
                return@launch
            }

            _unsafeBooks.value = emptyList()
            _progress.value = SafetyReviewProgress(
                running = true,
                totalBooks = localBooks.size,
                processedBooks = 0,
                unsafeBooks = 0
            )

            val unsupportedTypeCount = AtomicInteger(0)
            val emptyContentCount = AtomicInteger(0)
            val exceptionCount = AtomicInteger(0)
            val exceptionSamples = ConcurrentLinkedQueue<String>()
            val skipSamples = ConcurrentLinkedQueue<String>()

            val unsafe = coroutineScope {
                localBooks.map { book ->
                    async(dispatcher) {
                        val outcome = kotlin.runCatching {
                            scanSingleBook(book)
                        }.getOrElse {
                            exceptionCount.incrementAndGet()
                            if (exceptionSamples.size < 10) {
                                exceptionSamples.add("${book.name}(${book.author}): ${it.localizedMessage}")
                            }
                            AppLog.put("书架安全审查单书异常: ${book.name}(${book.author})", it)
                            null
                        }
                        when (outcome?.skipReason) {
                            SkipReason.UNSUPPORTED_TYPE -> {
                                unsupportedTypeCount.incrementAndGet()
                                if (skipSamples.size < 10) {
                                    skipSamples.add("UNSUPPORTED_TYPE: ${book.name}(${book.author})")
                                }
                            }

                            SkipReason.EMPTY_CONTENT -> {
                                emptyContentCount.incrementAndGet()
                                if (skipSamples.size < 10) {
                                    skipSamples.add("EMPTY_CONTENT: ${book.name}(${book.author})")
                                }
                            }

                            null -> {}
                        }
                        val result = outcome?.unsafeItem
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
            AppLog.put(
                buildString {
                    append("书架安全审查完成: total=${localBooks.size}, unsafe=${unsafe.size}, ")
                    append("unsupportedType=${unsupportedTypeCount.get()}, emptyContent=${emptyContentCount.get()}, ")
                    append("exceptions=${exceptionCount.get()}")
                }
            )
            if (skipSamples.isNotEmpty()) {
                AppLog.put("书架安全审查跳过样本:\n${skipSamples.joinToString("\n")}")
            }
            if (exceptionSamples.isNotEmpty()) {
                AppLog.put("书架安全审查异常样本:\n${exceptionSamples.joinToString("\n")}")
            }
        }
    }

    fun addToGroup(books: List<Book>, groupId: Long) {
        if (books.isEmpty()) return
        execute {
            val updated = books.map { it.copy(group = groupId) }
            appDb.bookDao.update(*updated.toTypedArray())
        }
    }

    private fun scanSingleBook(book: Book): ScanOutcome {
        if (!book.isLocalTxt) {
            return ScanOutcome(skipReason = SkipReason.UNSUPPORTED_TYPE)
        }
        val text = readBookText(book)
        if (text.isNullOrBlank()) {
            return ScanOutcome(skipReason = SkipReason.EMPTY_CONTENT)
        }
        val result = moderationService.analyzeText(text)
        val flaggedCount = result.flaggedChapters
        val firstFlaggedTitle = result.details.firstOrNull()?.title.orEmpty()

        return if (flaggedCount > 0) {
            ScanOutcome(
                unsafeItem = UnsafeBookItem(
                    book = book,
                    flaggedChapterCount = flaggedCount,
                    firstFlaggedChapterTitle = firstFlaggedTitle
                )
            )
        } else {
            ScanOutcome()
        }
    }

    private fun readBookText(book: Book): String? {
        return LocalBook.getBookInputStream(book)
            .bufferedReader(book.fileCharset())
            .use { it.readText() }
    }
}
