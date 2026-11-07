package com.tellybox.tv.ui.channels

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.settings.ChannelNames
import com.tellybox.tv.databinding.DialogSearchBinding
import com.tellybox.tv.ui.chrome.SimpleRow
import com.tellybox.tv.ui.chrome.SimpleRowAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search as a card over the live screen: results appear in the standard row style as you
 * type. [onPick] gets a chosen result; [onSubmit] gets the query when Enter is pressed, to
 * list every match in the guide.
 */
class SearchCard(
    private val activity: AppCompatActivity,
    private val initial: String,
    private val onPick: (query: String, channel: ChannelEntity) -> Unit,
    private val onSubmit: (query: String) -> Unit,
) : AppCompatDialog(activity, R.style.Theme_Tellybox_SearchCard) {

    private lateinit var b: DialogSearchBinding
    private var results: List<ChannelEntity> = emptyList()
    private var job: Job? = null
    private val adapter = SimpleRowAdapter { row ->
        val channel = results.firstOrNull { it.id == row.id } ?: return@SimpleRowAdapter
        dismiss()
        onPick(query(), channel)
    }

    private fun query() = b.searchInput.text.toString().trim()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = DialogSearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.searchResults.layoutManager = LinearLayoutManager(context)
        b.searchResults.adapter = adapter
        b.searchResults.itemAnimator = null
        b.searchInput.setText(initial)
        b.searchInput.setSelection(initial.length)
        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = lookUp(delayMs = 250)
        })
        b.searchInput.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || enter) {
                dismiss()
                onSubmit(query())
                true
            } else {
                false
            }
        }
        lookUp(delayMs = 0)
    }

    override fun onStart() {
        super.onStart()
        // A card with a clear margin from every edge, not a full-width slab.
        val metrics = context.resources.displayMetrics
        window?.setLayout((metrics.widthPixels * 0.56f).toInt(), (metrics.heightPixels * 0.78f).toInt())
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // A fresh search opens the keyboard; reopening one shows its results first.
        val keyboard = if (initial.isEmpty()) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or keyboard)
        b.searchInput.requestFocus()
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }

    private fun lookUp(delayMs: Long) {
        job?.cancel()
        val q = query()
        job = activity.lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (q.isEmpty()) {
                show(q, emptyList(), "")
                return@launch
            }
            val app = activity.appContainer
            val found = app.channels.search(q)
            val tokens = app.settings.get().channelNameTokens
            show(q, found, tokens)
        }
    }

    private fun show(q: String, found: List<ChannelEntity>, tokens: String) {
        results = found
        adapter.submit(found.map { c ->
            val value = when (c.kind) {
                ContentKind.MOVIE -> context.getString(R.string.search_movie)
                ContentKind.SERIES -> context.getString(R.string.search_series)
                else -> c.number?.toString().orEmpty()
            }
            SimpleRow(c.id, ChannelNames.display(c.name, tokens), value)
        })
        b.searchResults.scrollToPosition(0)
        b.searchCount.text = if (q.isEmpty()) "" else context.resources.getQuantityString(R.plurals.search_results, found.size, found.size)
        b.searchEmpty.text = context.getString(R.string.search_no_results, q)
        b.searchEmpty.visibility = if (q.isNotEmpty() && found.isEmpty()) View.VISIBLE else View.GONE
    }
}
