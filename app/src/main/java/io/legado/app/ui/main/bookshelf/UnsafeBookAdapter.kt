package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemArrangeBookBinding
import java.util.Collections

class UnsafeBookAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<UnsafeBookItem, ItemArrangeBookBinding>(context) {

    private val selectedBookUrls = hashSetOf<String>()

    val selection: List<Book>
        get() = getItems()
            .filter { selectedBookUrls.contains(it.book.bookUrl) }
            .map { it.book }

    override fun getViewBinding(parent: ViewGroup): ItemArrangeBookBinding {
        return ItemArrangeBookBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        callBack.upSelectCount()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemArrangeBookBinding,
        item: UnsafeBookItem,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            val book = item.book
            tvName.text = book.name
            tvAuthor.text = book.author
            tvAuthor.visibility = if (book.author.isBlank()) View.GONE else View.VISIBLE
            tvOrigin.text = context.getString(
                io.legado.app.R.string.bookshelf_safety_review_hit_count,
                item.flaggedChapterCount
            )
            tvGroupS.text = context.getString(
                io.legado.app.R.string.bookshelf_safety_review_first_hit,
                item.firstFlaggedChapterTitle
            )
            tvGroup.visibility = View.GONE
            tvDelete.visibility = View.GONE
            checkbox.isChecked = selectedBookUrls.contains(book.bookUrl)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemArrangeBookBinding) {
        binding.apply {
            checkbox.setOnCheckedChangeListener { buttonView, isChecked ->
                if (!buttonView.isPressed) return@setOnCheckedChangeListener
                getItem(holder.layoutPosition)?.book?.let { book ->
                    if (isChecked) selectedBookUrls.add(book.bookUrl)
                    else selectedBookUrls.remove(book.bookUrl)
                    callBack.upSelectCount()
                }
            }
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.book?.let { book ->
                    checkbox.isChecked = !checkbox.isChecked
                    if (checkbox.isChecked) selectedBookUrls.add(book.bookUrl)
                    else selectedBookUrls.remove(book.bookUrl)
                    callBack.upSelectCount()
                }
            }
            tvName.setOnClickListener {
                getItem(holder.layoutPosition)?.book?.let { book ->
                    callBack.openBook(book)
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            getItems().forEach { selectedBookUrls.add(it.book.bookUrl) }
        } else {
            selectedBookUrls.clear()
        }
        notifyDataSetChanged()
        callBack.upSelectCount()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun revertSelection() {
        getItems().forEach {
            val bookUrl = it.book.bookUrl
            if (selectedBookUrls.contains(bookUrl)) selectedBookUrls.remove(bookUrl)
            else selectedBookUrls.add(bookUrl)
        }
        notifyDataSetChanged()
        callBack.upSelectCount()
    }

    fun checkSelectedInterval() {
        val selectedPosition = linkedSetOf<Int>()
        getItems().forEachIndexed { index, item ->
            if (selectedBookUrls.contains(item.book.bookUrl)) {
                selectedPosition.add(index)
            }
        }
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val itemCount = maxPosition - minPosition + 1
        for (i in minPosition..maxPosition) {
            getItem(i)?.let {
                selectedBookUrls.add(it.book.bookUrl)
            }
        }
        notifyItemRangeChanged(minPosition, itemCount, bundleOf(Pair("selected", null)))
        callBack.upSelectCount()
    }

    interface CallBack {
        fun upSelectCount()
        fun openBook(book: Book)
    }
}
