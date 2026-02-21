package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityBookshelfSafetyReviewBinding
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class BookshelfSafetyReviewActivity :
    VMBaseActivity<ActivityBookshelfSafetyReviewBinding, BookshelfSafetyReviewViewModel>(),
    SelectActionBar.CallBack,
    PopupMenu.OnMenuItemClickListener,
    GroupSelectDialog.CallBack,
    UnsafeBookAdapter.CallBack {

    companion object {
        private const val REQ_ADD_TO_GROUP = 8801
    }

    override val binding by viewBinding(ActivityBookshelfSafetyReviewBinding::inflate)
    override val viewModel by viewModels<BookshelfSafetyReviewViewModel>()

    private val adapter by lazy { UnsafeBookAdapter(this, this) }
    private val groupList: ArrayList<BookGroup> = arrayListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.selectActionBar.setMainActionText(R.string.add_to_group)
        binding.selectActionBar.inflateMenu(R.menu.unsafe_books_sel)
        binding.selectActionBar.setCallBack(this)
        binding.selectActionBar.setOnMenuItemClickListener(this)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter

        initGroupData()
        observeState()

        viewModel.startSafetyReview()
    }

    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("安全审查页面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.progress.collect { progress ->
                val total = progress.totalBooks
                val processed = progress.processedBooks
                binding.progressBar.max = if (total > 0) total else 1
                binding.progressBar.progress = processed

                val text = if (progress.running) {
                    getString(
                        R.string.bookshelf_safety_review_progress,
                        processed,
                        total,
                        progress.unsafeBooks
                    )
                } else {
                    if (total == 0) {
                        getString(R.string.bookshelf_safety_review_no_local)
                    } else {
                        getString(
                            R.string.bookshelf_safety_review_done,
                            total,
                            progress.unsafeBooks
                        )
                    }
                }
                binding.tvProgress.text = text
            }
        }

        lifecycleScope.launch {
            viewModel.unsafeBooks.collect { items ->
                adapter.setItems(items)
                binding.emptyView.isGone = items.isNotEmpty()
                upSelectCount()
            }
        }
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        selectGroup(REQ_ADD_TO_GROUP, 0)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_add_to_group -> selectGroup(REQ_ADD_TO_GROUP, 0)
            R.id.menu_check_selected_interval -> adapter.checkSelectedInterval()
        }
        return true
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        if (requestCode != REQ_ADD_TO_GROUP) return
        val selectedBooks = adapter.selection
        if (selectedBooks.isEmpty()) {
            toastOnUi(R.string.bookshelf_safety_review_select_book)
            return
        }
        viewModel.addToGroup(selectedBooks, groupId)
        toastOnUi(R.string.success)
    }

    private fun selectGroup(requestCode: Int, groupId: Long) {
        showDialogFragment(GroupSelectDialog(groupId, requestCode))
    }

    override fun upSelectCount() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.itemCount)
    }

    override fun openBook(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }
}
