package com.vdbrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vdbrowser.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tabs = mutableListOf<Tab>()
    private var currentIndex = 0
    private val currentTab get() = tabs[currentIndex]

    private var adBlockEnabled = true
    private var tabStripAdapter: TabStripAdapter? = null

    private val homeUrl = "https://www.google.com"

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adBlockEnabled = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("adblock", true)

        requestNotificationPermissionIfNeeded()
        setupUi()
        setupBackNavigation()

        val initial = intent?.dataString ?: homeUrl
        addTabAndSelect(createTab(initial))
    }

    // ---------------------------------------------------------------- Tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(url: String?): Tab {
        val web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val tab = Tab(web)
        configureWebView(web, tab)
        if (!url.isNullOrBlank()) {
            tab.url = url
            web.loadUrl(url)
        }
        return tab
    }

    private fun addTabAndSelect(tab: Tab) {
        tabs.add(tab)
        selectTab(tabs.size - 1)
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        val web = currentTab.webView
        (web.parent as? ViewGroup)?.removeView(web)
        binding.webContainer.removeAllViews()
        binding.webContainer.addView(web)

        if (!binding.urlBar.hasFocus()) binding.urlBar.setText(currentTab.url)
        updateBadge()
        updateNavButtons()
        updateTabCount()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            addTabAndSelect(createTab(homeUrl))
            return
        }
        val target = if (index < currentIndex) currentIndex - 1
        else currentIndex.coerceAtMost(tabs.size - 1)
        selectTab(target)
    }

    private fun updateTabCount() {
        binding.tabCount.text = tabs.size.toString()
        tabStripAdapter?.notifyDataSetChanged()
        binding.tabStrip?.let { strip -> strip.post { strip.scrollToPosition(currentIndex) } }
    }

    /** Refresh the on-top tab strip (e.g. after a title or URL changes). */
    private fun refreshTabStrip() {
        tabStripAdapter?.notifyDataSetChanged()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(web: WebView, tab: Tab) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Present a desktop-ish UA without the "wv" tag so sites don't block the WebView.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(JsBridge(tab), "VDBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tab.sniffer.clear()
                tab.url = url ?: ""
                if (tab === currentTab) {
                    if (!binding.urlBar.hasFocus()) binding.urlBar.setText(url)
                    updateBadge()
                }
                refreshTabStrip()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.url = url ?: tab.url
                injectScanner(view)
                if (tab === currentTab) updateNavButtons()
                refreshTabStrip()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Runs on a background thread.
                val reqUrl = request?.url?.toString() ?: return null
                if (adBlockEnabled && AdBlocker.shouldBlock(reqUrl)) {
                    return AdBlocker.blockedResponse()
                }
                tab.sniffer.consider(reqUrl)
                if (tab === currentTab) runOnUiThread { updateBadge() }
                return null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab !== currentTab) return
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title
                    refreshTabStrip()
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // A link with target=_blank or window.open -> open in a new tab.
                val newTab = createTab(null)
                addTabAndSelect(newTab)
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }
        }

        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
            val type = name.substringAfterLast('.', "bin")
            DownloadHelper.enqueue(
                this,
                MediaItem(dlUrl, type, name, isStream = false),
                tab.url,
                userAgent ?: web.settings.userAgentString
            )
        }
    }

    // ----------------------------------------------------------------- UI

    private fun setupUi() {
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(binding.urlBar.text.toString())
                true
            } else false
        }
        binding.btnBack.setOnClickListener {
            if (currentTab.webView.canGoBack()) currentTab.webView.goBack()
        }
        binding.btnDownloads.setOnClickListener { showMediaSheet() }
        binding.btnTabs.setOnClickListener { showTabSwitcher() }
        binding.btnMenu.setOnClickListener { showOverflowMenu() }

        // Large screens (sw600dp) show a desktop-style tab strip on top.
        binding.tabStrip?.let { strip ->
            strip.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            tabStripAdapter = TabStripAdapter(
                tabs = tabs,
                currentIndex = { currentIndex },
                onSelect = { i -> selectTab(i) },
                onClose = { i -> closeTab(i) }
            )
            strip.adapter = tabStripAdapter
        }
        binding.btnNewTabStrip?.setOnClickListener {
            addTabAndSelect(createTab(homeUrl))
        }

        binding.swipeRefresh.setOnRefreshListener {
            currentTab.webView.reload()
            binding.swipeRefresh.isRefreshing = false
        }
        // Only allow pull-to-refresh when the page is scrolled to the top.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            currentTab.webView.scrollY > 0
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentTab.webView.canGoBack() -> currentTab.webView.goBack()
                    tabs.size > 1 -> closeTab(currentIndex)
                    else -> finish()
                }
            }
        })
    }

    private fun navigate(input: String) {
        val raw = input.trim()
        if (raw.isEmpty()) return
        val looksLikeUrl = raw.contains(".") && !raw.contains(" ")
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            looksLikeUrl -> "https://$raw"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(raw)
        }
        currentTab.webView.loadUrl(url)
        hideKeyboard()
        binding.urlBar.clearFocus()
    }

    /** Scan the rendered DOM for <video>/<source> elements after the page settles. */
    private fun injectScanner(view: WebView?) {
        val js = """
            (function() {
              try {
                var add = function(u) {
                  if (u && u.indexOf('blob:') !== 0 && u.indexOf('data:') !== 0) {
                    VDBridge.onMediaFound(u);
                  }
                };
                document.querySelectorAll('video').forEach(function(v) {
                  add(v.currentSrc); add(v.src);
                  v.querySelectorAll('source').forEach(function(s) { add(s.src); });
                });
                document.querySelectorAll('source').forEach(function(s) { add(s.src); });
              } catch (e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun showMediaSheet() {
        val items = currentTab.sniffer.snapshot()
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_downloads, null)
        sheet.setContentView(content)

        val recycler = content.findViewById<RecyclerView>(R.id.mediaList)
        val empty = content.findViewById<TextView>(R.id.emptyView)
        val header = content.findViewById<TextView>(R.id.sheetTitle)
        header.text = getString(R.string.detected_media, items.size)

        if (items.isEmpty()) {
            recycler.visibility = View.GONE
            empty.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            empty.visibility = View.GONE
            recycler.layoutManager = LinearLayoutManager(this)
            val adapter = MediaAdapter(items) { item ->
                if (item.isStream) {
                    Toast.makeText(this, R.string.stream_note, Toast.LENGTH_LONG).show()
                }
                DownloadHelper.enqueue(
                    this, item, currentTab.url, currentTab.webView.settings.userAgentString
                )
                sheet.dismiss()
            }
            recycler.adapter = adapter

            // Resolve each video's size in the background and update its row.
            val ua = currentTab.webView.settings.userAgentString
            val pageUrl = currentTab.url
            items.forEach { item ->
                if (item.sizeBytes == MediaItem.SIZE_UNKNOWN) {
                    item.sizeBytes = MediaItem.SIZE_FETCHING
                    SizeFetcher.fetch(item.url, pageUrl, ua) { size ->
                        runOnUiThread { adapter.updateSize(item, size) }
                    }
                }
            }
        }
        sheet.show()
    }

    private fun showTabSwitcher() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_tabs, null)
        sheet.setContentView(content)

        val recycler = content.findViewById<RecyclerView>(R.id.tabList)
        val header = content.findViewById<TextView>(R.id.tabsTitle)
        recycler.layoutManager = LinearLayoutManager(this)

        lateinit var adapter: TabAdapter
        fun refreshHeader() { header.text = getString(R.string.tabs_title, tabs.size) }
        adapter = TabAdapter(
            tabs = tabs,
            currentIndex = { currentIndex },
            onSelect = { i -> selectTab(i); sheet.dismiss() },
            onClose = { i ->
                closeTab(i)
                adapter.notifyDataSetChanged()
                refreshHeader()
            }
        )
        recycler.adapter = adapter
        refreshHeader()

        content.findViewById<View>(R.id.btnNewTab).setOnClickListener {
            addTabAndSelect(createTab(homeUrl))
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.menu.add(0, MENU_NEW_TAB, 0, R.string.menu_new_tab)
        popup.menu.add(0, MENU_FORWARD, 1, R.string.menu_forward).isEnabled =
            currentTab.webView.canGoForward()
        popup.menu.add(0, MENU_REFRESH, 2, R.string.menu_refresh)
        popup.menu.add(0, MENU_BOOKMARK_ADD, 3, getString(R.string.menu_bookmark_add)).apply {
            isCheckable = true
            isChecked = Bookmarks.isBookmarked(this@MainActivity, currentTab.url)
        }
        popup.menu.add(0, MENU_BOOKMARKS, 4, R.string.bookmarks)
        popup.menu.add(0, MENU_DOWNLOADS, 5, R.string.menu_downloads)
        popup.menu.add(0, MENU_ADBLOCK, 6, getString(R.string.menu_adblock)).apply {
            isCheckable = true
            isChecked = adBlockEnabled
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NEW_TAB -> { addTabAndSelect(createTab(homeUrl)); true }
                MENU_FORWARD -> { if (currentTab.webView.canGoForward()) currentTab.webView.goForward(); true }
                MENU_REFRESH -> { currentTab.webView.reload(); true }
                MENU_BOOKMARK_ADD -> { toggleBookmark(); true }
                MENU_BOOKMARKS -> { showBookmarks(); true }
                MENU_DOWNLOADS -> { showDownloadsDialog(); true }
                MENU_ADBLOCK -> { toggleAdBlock(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleBookmark() {
        val url = currentTab.url
        if (url.isBlank()) return
        if (Bookmarks.isBookmarked(this, url)) {
            Bookmarks.remove(this, url)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            Bookmarks.add(this, currentTab.title, url)
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBookmarks() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_bookmarks, null)
        sheet.setContentView(content)

        val recycler = content.findViewById<RecyclerView>(R.id.bookmarksList)
        val empty = content.findViewById<TextView>(R.id.bookmarksEmpty)
        val items = Bookmarks.all(this)

        if (items.isEmpty()) {
            recycler.visibility = View.GONE
            empty.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            empty.visibility = View.GONE
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = BookmarkAdapter(
                items,
                onOpen = { item -> currentTab.webView.loadUrl(item.url); sheet.dismiss() },
                onDelete = { item -> Bookmarks.remove(this, item.url) }
            )
        }
        sheet.show()
    }

    private fun toggleAdBlock() {
        adBlockEnabled = !adBlockEnabled
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("adblock", adBlockEnabled).apply()
        val msg = if (adBlockEnabled) R.string.adblock_on else R.string.adblock_off
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Bottom sheet that polls the download store and shows live progress for each download. */
    private fun showDownloadsDialog() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_downloads, null)
        sheet.setContentView(content)

        val recycler = content.findViewById<RecyclerView>(R.id.downloadsList)
        val empty = content.findViewById<TextView>(R.id.downloadsEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = DownloadProgressAdapter(emptyList())
        recycler.adapter = adapter

        val handler = Handler(Looper.getMainLooper())
        val refresh = object : Runnable {
            override fun run() {
                val statuses = Downloads.snapshot()
                empty.visibility = if (statuses.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (statuses.isEmpty()) View.GONE else View.VISIBLE
                adapter.submit(statuses)
                handler.postDelayed(this, 700)
            }
        }
        sheet.setOnShowListener { handler.post(refresh) }
        sheet.setOnDismissListener { handler.removeCallbacks(refresh) }
        sheet.show()
    }

    private fun updateBadge() {
        val count = currentTab.sniffer.count()
        binding.downloadBadge.apply {
            visibility = if (count > 0) View.VISIBLE else View.GONE
            text = if (count > 9) "9+" else count.toString()
        }
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (currentTab.webView.canGoBack()) 1f else 0.4f
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { addTabAndSelect(createTab(it)) }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    /** Bridge that injected page JavaScript uses to report media URLs back to the app. */
    inner class JsBridge(private val tab: Tab) {
        @JavascriptInterface
        fun onMediaFound(url: String) {
            tab.sniffer.consider(url)
            if (tab === currentTab) runOnUiThread { updateBadge() }
        }
    }

    companion object {
        private const val MENU_NEW_TAB = 1
        private const val MENU_FORWARD = 2
        private const val MENU_REFRESH = 3
        private const val MENU_BOOKMARK_ADD = 4
        private const val MENU_BOOKMARKS = 5
        private const val MENU_DOWNLOADS = 6
        private const val MENU_ADBLOCK = 7
    }
}
