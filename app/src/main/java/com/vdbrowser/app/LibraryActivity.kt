package com.vdbrowser.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Size
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Chrome-style Library: Bookmarks, History and Downloads in one full-screen page
 * with search. Opening a bookmark or history entry returns its URL to the browser.
 */
class LibraryActivity : AppCompatActivity() {

    enum class Section { BOOKMARKS, HISTORY, DOWNLOADS }
    private enum class Filter { ALL, VIDEOS, OTHER }

    private val handler = Handler(Looper.getMainLooper())
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val thumbs = ConcurrentHashMap<String, Bitmap>()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyText: TextView
    private lateinit var chips: View
    private lateinit var progressHeader: HeaderAdapter
    private lateinit var progressAdapter: DownloadProgressAdapter
    private lateinit var doneHeader: HeaderAdapter
    private lateinit var doneAdapter: FileAdapter
    private var clearItem: MenuItem? = null

    private var section = Section.BOOKMARKS
    private var query = ""
    private var filter = Filter.ALL
    private var lastInProgress = -1

    private val poll = object : Runnable {
        override fun run() {
            if (section == Section.DOWNLOADS) refreshProgress()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        setupToolbarMenu()

        list = findViewById(R.id.libraryList)
        empty = findViewById(R.id.libraryEmpty)
        emptyIcon = findViewById(R.id.libraryEmptyIcon)
        emptyText = findViewById(R.id.libraryEmptyText)
        chips = findViewById(R.id.filterChipsScroll)
        list.layoutManager = LinearLayoutManager(this)

        progressHeader = HeaderAdapter(getString(R.string.downloads_in_progress))
        progressAdapter = DownloadProgressAdapter(
            emptyList(),
            onPause = { id -> Downloads.pause(id) },
            onResume = { id -> Downloads.resume(id) },
            onCancel = { id -> Downloads.cancel(id) }
        )
        doneHeader = HeaderAdapter(getString(R.string.downloads_completed))
        doneAdapter = FileAdapter()

        findViewById<ChipGroup>(R.id.filterChips).setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.chipVideos -> Filter.VIDEOS
                R.id.chipOther -> Filter.OTHER
                else -> Filter.ALL
            }
            render()
        }

        val tabs = findViewById<TabLayout>(R.id.libraryTabs)
        listOf(R.string.bookmarks, R.string.history, R.string.downloads).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        section = runCatching {
            Section.valueOf(intent.getStringExtra(EXTRA_SECTION) ?: "")
        }.getOrDefault(Section.BOOKMARKS)
        tabs.getTabAt(section.ordinal)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                section = Section.values()[tab.position]
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        render()
    }

    private fun setupToolbarMenu() {
        val search = toolbar.menu.add(0, 1, 0, R.string.search_hint)
            .setIcon(R.drawable.ic_search)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val sv = SearchView(toolbar.context).apply {
            queryHint = getString(R.string.search_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean {
                    query = q.orEmpty().trim()
                    render()
                    return true
                }
            })
        }
        search.actionView = sv
        clearItem = toolbar.menu.add(0, 2, 1, R.string.clear)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == 2) confirmClear()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
        render()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        thumbPool.shutdownNow()
        super.onDestroy()
    }

    private fun matches(vararg fields: String) =
        query.isEmpty() || fields.any { it.contains(query, ignoreCase = true) }

    // ------------------------------------------------------------- Render

    private fun render() {
        chips.visibility = if (section == Section.DOWNLOADS) View.VISIBLE else View.GONE
        clearItem?.isVisible = section != Section.BOOKMARKS
        clearItem?.setTitle(if (section == Section.DOWNLOADS) R.string.clear_list else R.string.clear_history)
        when (section) {
            Section.BOOKMARKS -> renderBookmarks()
            Section.HISTORY -> renderHistory()
            Section.DOWNLOADS -> renderDownloads()
        }
    }

    private fun showEmpty(none: Boolean, icon: Int, text: Int) {
        empty.visibility = if (none) View.VISIBLE else View.GONE
        emptyIcon.setImageResource(icon)
        emptyText.setText(if (query.isNotEmpty()) R.string.no_results else text)
    }

    private fun renderBookmarks() {
        val items = Bookmarks.all(this).filter { matches(it.title, it.url) }.toMutableList()
        lateinit var adapter: BookmarkAdapter
        adapter = BookmarkAdapter(
            items,
            onOpen = { openUrl(it.url) },
            onDelete = { pos, item ->
                val storeIndex = Bookmarks.all(this).indexOfFirst { it.url == item.url }
                Bookmarks.remove(this, item.url)
                showEmpty(adapter.itemCount == 0, R.drawable.ic_bookmarks, R.string.no_bookmarks)
                Snackbar.make(list, R.string.bookmark_removed, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) {
                        Bookmarks.insert(this, storeIndex, item)
                        adapter.restore(pos, item)
                        showEmpty(false, R.drawable.ic_bookmarks, R.string.no_bookmarks)
                    }.show()
            }
        )
        list.adapter = adapter
        showEmpty(items.isEmpty(), R.drawable.ic_bookmarks, R.string.no_bookmarks)
    }

    /** History grouped under Today / Yesterday / Earlier. */
    private fun renderHistory() {
        val all = BrowsingHistory.all(this).filter { matches(it.title, it.url) }
        val today = startOfDay(0)
        val yesterday = startOfDay(-1)
        val groups = listOf(
            R.string.today to all.filter { it.time >= today },
            R.string.yesterday to all.filter { it.time in yesterday until today },
            R.string.earlier to all.filter { it.time < yesterday }
        ).filter { it.second.isNotEmpty() }

        val parts = ArrayList<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
        groups.forEach { (title, entries) ->
            parts.add(HeaderAdapter(getString(title)).also { it.visible = true })
            parts.add(
                BrowsingHistoryAdapter(
                    entries.toMutableList(),
                    onOpen = { openUrl(it.url) },
                    onDelete = { entry ->
                        BrowsingHistory.remove(this, entry.url)
                        render()
                    }
                )
            )
        }
        list.adapter = ConcatAdapter(parts)
        showEmpty(all.isEmpty(), R.drawable.ic_history, R.string.no_page_history)
    }

    private fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun renderDownloads() {
        if (list.adapter !is ConcatAdapter || (list.adapter as ConcatAdapter).adapters.firstOrNull() !== progressHeader) {
            list.adapter = ConcatAdapter(progressHeader, progressAdapter, doneHeader, doneAdapter)
        }
        refreshFiles()
        refreshProgress()
    }

    private fun refreshProgress() {
        val active = Downloads.snapshot().filter {
            (it.state == Downloads.State.RUNNING || it.state == Downloads.State.PAUSED) && matches(it.title)
        }
        progressAdapter.submit(active)
        progressHeader.visible = active.isNotEmpty()
        // A download just finished: it now belongs in the completed list.
        if (lastInProgress >= 0 && active.size < lastInProgress) refreshFiles()
        lastInProgress = active.size
        updateDownloadsEmpty()
    }

    private fun refreshFiles() {
        val entries = DownloadHistory.all(this).filter {
            matches(it.title) && when (filter) {
                Filter.ALL -> true
                Filter.VIDEOS -> isVideo(it)
                Filter.OTHER -> !isVideo(it)
            }
        }
        doneAdapter.submit(entries)
        doneHeader.visible = entries.isNotEmpty()
        updateDownloadsEmpty()
    }

    private fun updateDownloadsEmpty() {
        if (section != Section.DOWNLOADS) return
        showEmpty(
            progressAdapter.itemCount == 0 && doneAdapter.itemCount == 0,
            R.drawable.ic_download, R.string.no_downloads
        )
    }

    // ------------------------------------------------------------ Actions

    private fun openUrl(url: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    private fun confirmClear() {
        when (section) {
            Section.HISTORY -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.clear) { _, _ -> BrowsingHistory.clear(this); render() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            Section.DOWNLOADS -> { DownloadHistory.clear(this); render() }
            Section.BOOKMARKS -> Unit
        }
    }

    private fun isVideo(e: DownloadHistory.Entry): Boolean {
        if (e.mime.startsWith("video/")) return true
        val ext = e.title.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "ts", "avi", "flv")
    }

    // -------------------------------------------------------- File actions

    private fun contentUri(e: DownloadHistory.Entry): Uri? {
        if (e.uri.isBlank()) return null
        if (e.uri.startsWith("content://")) return Uri.parse(e.uri)
        val f = File(e.uri)
        if (!f.exists()) return null
        return runCatching { FileProvider.getUriForFile(this, "$packageName.updates", f) }.getOrNull()
    }

    private fun mimeOf(e: DownloadHistory.Entry, uri: Uri): String =
        e.mime.ifBlank { contentResolver.getType(uri) ?: "*/*" }

    private fun open(e: DownloadHistory.Entry) {
        val uri = contentUri(e) ?: return snack(if (e.uri.isBlank()) R.string.file_unknown else R.string.file_missing)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(e, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, getString(R.string.open)))
        } catch (ex: ActivityNotFoundException) {
            snack(R.string.no_app_to_open)
        }
    }

    private fun share(e: DownloadHistory.Entry) {
        val uri = contentUri(e) ?: return snack(if (e.uri.isBlank()) R.string.file_unknown else R.string.file_missing)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(e, uri))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun confirmDelete(e: DownloadHistory.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_file_title)
            .setMessage(getString(R.string.delete_file_msg, e.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                // The file may already be gone, or (after a reinstall) not ours to delete;
                // either way the entry is removed from the list.
                runCatching {
                    if (e.uri.startsWith("content://")) contentResolver.delete(Uri.parse(e.uri), null, null)
                    else if (e.uri.isNotBlank()) File(e.uri).delete()
                }
                DownloadHistory.remove(this, e)
                thumbs.remove(e.uri)
                refreshFiles()
                snack(R.string.file_deleted)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun snack(res: Int) {
        Snackbar.make(list, res, Snackbar.LENGTH_SHORT).show()
    }

    private fun loadThumb(e: DownloadHistory.Entry, onLoaded: () -> Unit) {
        if (e.uri.isBlank() || !isVideo(e) || thumbs.containsKey(e.uri)) return
        thumbPool.execute {
            val bmp = runCatching {
                if (e.uri.startsWith("content://")) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(Uri.parse(e.uri), Size(240, 160), null)
                    } else null
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(e.uri, MediaStore.Images.Thumbnails.MINI_KIND)
                }
            }.getOrNull() ?: return@execute
            thumbs[e.uri] = bmp
            handler.post(onLoaded)
        }
    }

    // ------------------------------------------------------------ Adapters

    /** A single section title row, shown only while its section has items. */
    private class HeaderAdapter(private val title: String) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var visible = false
            set(value) {
                if (field == value) return
                field = value
                if (value) notifyItemInserted(0) else notifyItemRemoved(0)
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_section_header, parent, false)
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = title
        }

        override fun getItemCount() = if (visible) 1 else 0
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        private var items: List<DownloadHistory.Entry> = emptyList()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.fileThumb)
            val name: TextView = view.findViewById(R.id.fileName)
            val info: TextView = view.findViewById(R.id.fileInfo)
            val more: ImageButton = view.findViewById(R.id.fileMore)
        }

        fun submit(list: List<DownloadHistory.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_downloaded_file, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val done = e.state == "COMPLETED"
            holder.name.text = e.title
            val time = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            holder.info.text = when {
                e.state == "FAILED" -> "${getString(R.string.state_failed)} · $time"
                e.state == "CANCELED" -> "${getString(R.string.state_canceled)} · $time"
                e.uri.isBlank() -> "${formatBytes(e.bytes)} · ${getString(R.string.file_unknown)}"
                else -> "${formatBytes(e.bytes)} · $time"
            }
            holder.itemView.alpha = if (done) 1f else 0.6f

            val bmp = thumbs[e.uri]
            if (bmp != null) {
                ImageViewCompat.setImageTintList(holder.thumb, null)
                holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.thumb.setImageBitmap(bmp)
            } else {
                ImageViewCompat.setImageTintList(
                    holder.thumb,
                    android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(
                            holder.thumb, com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    )
                )
                holder.thumb.scaleType = ImageView.ScaleType.CENTER
                holder.thumb.setImageResource(if (isVideo(e)) R.drawable.ic_play else R.drawable.ic_download)
                if (done) loadThumb(e) { notifyDataSetChanged() }
            }

            holder.itemView.setOnClickListener { if (done) open(e) }
            holder.more.setOnClickListener { v ->
                val menu = PopupMenu(this@LibraryActivity, v)
                if (done) {
                    menu.menu.add(0, 1, 0, R.string.open)
                    menu.menu.add(0, 2, 1, R.string.share)
                }
                menu.menu.add(0, 3, 2, R.string.delete)
                menu.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> open(e)
                        2 -> share(e)
                        3 -> confirmDelete(e)
                    }
                    true
                }
                menu.show()
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_URL = "url"

        fun intent(context: Context, section: Section) =
            Intent(context, LibraryActivity::class.java).putExtra(EXTRA_SECTION, section.name)
    }
}
