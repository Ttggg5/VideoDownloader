package com.vdbrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
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
    private var isWide = false

    // User options (persisted in the "settings" SharedPreferences).
    private var minSizeBytes = 0L
    private var desktopMode = false
    private var searchEngine = "google"

    private val mobileUa by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "")
    }

    /** User-agent in effect, captured for off-main-thread size fetches. */
    @Volatile
    private var currentUa: String = ""

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            updateDownloadStatus()
            statusHandler.postDelayed(this, 800)
        }
    }

    /** Receives the resolved href from a long-pressed link. */
    private val linkHandler = Handler(Looper.getMainLooper()) { msg ->
        val url = msg.data?.getString("url")
        if (!url.isNullOrBlank()) showLinkMenu(url)
        true
    }

    private val homeUrl = "https://www.google.com"

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        adBlockEnabled = prefs.getBoolean("adblock", true)
        minSizeBytes = prefs.getLong("min_size", 0L)
        desktopMode = prefs.getBoolean("desktop", false)
        searchEngine = prefs.getString("search_engine", "google") ?: "google"
        currentUa = if (desktopMode) DESKTOP_UA else mobileUa
        AdBlocker.init(applicationContext)

        isWide = resources.getBoolean(R.bool.is_wide)
        requestNotificationPermissionIfNeeded()
        setupUi()
        setupBackNavigation()

        val initial = intent?.dataString ?: homeUrl
        addTabAndSelect(createTab(initial))
    }

    /**
     * We handle configuration changes ourselves (see the manifest) so tabs and
     * their WebViews survive. When the width crosses the sw600dp threshold we
     * re-inflate the matching layout and re-attach the current tab, preserving
     * all state.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowWide = resources.getBoolean(R.bool.is_wide)
        if (nowWide != isWide) {
            isWide = nowWide
            rebindLayout()
        }
    }

    private fun rebindLayout() {
        val editing = binding.urlBar.hasFocus()
        val pendingText = binding.urlBar.text?.toString()

        // Detach the live WebView so it isn't destroyed with the old view tree.
        (currentTab.webView.parent as? ViewGroup)?.removeView(currentTab.webView)

        tabStripAdapter = null
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUi()
        selectTab(currentIndex)   // re-attaches the WebView and refreshes the bars
        updateDownloadStatus()

        if (editing) {
            binding.urlBar.setText(pendingText)
            binding.urlBar.requestFocus()
        }
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
        updateBookmarkIcon()
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

    /** Open a URL in a new tab without leaving the current one. */
    private fun openInBackgroundTab(url: String) {
        tabs.add(createTab(url))
        updateTabCount()
        Toast.makeText(this, R.string.opened_in_new_tab, Toast.LENGTH_SHORT).show()
    }

    private fun showLinkMenu(url: String) {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_link, null)
        sheet.setContentView(content)

        content.findViewById<TextView>(R.id.linkUrl).text = url
        content.findViewById<View>(R.id.linkOpenNewTab).setOnClickListener {
            openInBackgroundTab(url)
            sheet.dismiss()
        }
        content.findViewById<View>(R.id.linkCopy).setOnClickListener {
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("url", url))
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
            sheet.dismiss()
        }
        sheet.show()
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
            userAgentString = if (desktopMode) DESKTOP_UA else mobileUa
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(JsBridge(tab), "VDBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tab.sniffer.clear()
                tab.url = url ?: ""
                tab.favicon = null
                if (tab === currentTab) {
                    if (!binding.urlBar.hasFocus()) binding.urlBar.setText(url)
                    updateBadge()
                    updateBookmarkIcon()
                }
                refreshTabStrip()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.url = url ?: tab.url
                injectScanner(view)
                if (tab === currentTab) {
                    updateNavButtons()
                    updateBookmarkIcon()
                }
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
                val found = tab.sniffer.consider(reqUrl)
                if (found != null) {
                    fetchSize(found, tab)
                    if (tab === currentTab) runOnUiThread { updateBadge() }
                }
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

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null) {
                    tab.favicon = icon
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

        // Long-press a link to open it in a new tab or copy it.
        web.setOnLongClickListener {
            val type = web.hitTestResult.type
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                web.requestFocusNodeHref(linkHandler.obtainMessage())
                true
            } else {
                false
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
        binding.btnForward.setOnClickListener {
            if (currentTab.webView.canGoForward()) currentTab.webView.goForward()
        }
        binding.btnBookmark.setOnClickListener {
            toggleBookmark()
            updateBookmarkIcon()
        }
        binding.btnTabs.setOnClickListener { showTabSwitcher() }
        binding.btnMenu.setOnClickListener { showOverflowMenu() }
        binding.dlStatus.setOnClickListener { showDownloadsDialog() }
        setupFloatingDownloadButton()

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

    /** The download FAB opens the media sheet on tap and can be dragged anywhere. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingDownloadButton() {
        // Listener sits on the FAB (the touchable child) but moves its container.
        val container = binding.dlFab
        var downX = 0f
        var downY = 0f
        var offsetX = 0f
        var offsetY = 0f
        var dragged = false
        val touchSlop = 12f

        binding.btnDownloads.setOnTouchListener { _, event ->
            val parent = container.parent as View
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    offsetX = container.x - event.rawX
                    offsetY = container.y - event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > touchSlop ||
                        kotlin.math.abs(event.rawY - downY) > touchSlop
                    ) dragged = true
                    container.x = (event.rawX + offsetX)
                        .coerceIn(0f, (parent.width - container.width).toFloat())
                    container.y = (event.rawY + offsetY)
                        .coerceIn(0f, (parent.height - container.height).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) showMediaSheet()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateBookmarkIcon() {
        val marked = Bookmarks.isBookmarked(this, currentTab.url)
        binding.btnBookmark.setImageResource(
            if (marked) R.drawable.ic_star else R.drawable.ic_star_border
        )
    }

    private fun navigate(input: String) {
        val raw = input.trim()
        if (raw.isEmpty()) return
        val looksLikeUrl = raw.contains(".") && !raw.contains(" ")
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            looksLikeUrl -> "https://$raw"
            else -> searchUrl(raw)
        }
        currentTab.webView.loadUrl(url)
        hideKeyboard()
        binding.urlBar.clearFocus()
    }

    private fun searchUrl(query: String): String {
        val q = android.net.Uri.encode(query)
        return when (searchEngine) {
            "bing" -> "https://www.bing.com/search?q=$q"
            "ddg" -> "https://duckduckgo.com/?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
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

    /** Shows detected media as a popup anchored above the floating download button. */
    private fun showMediaSheet() {
        // Pre-filter by the minimum-size option so items don't pop in/out.
        val items = currentTab.sniffer.snapshotPassing(minSizeBytes).toMutableList()
        val content = layoutInflater.inflate(R.layout.sheet_downloads, null)
        val widthPx = minOf(resources.displayMetrics.widthPixels - dp(24), dp(360))

        val popup = PopupWindow(this).apply {
            this.width = widthPx
            isFocusable = true
            isOutsideTouchable = true
            elevation = dp(20).toFloat()
            animationStyle = android.R.style.Animation_Dialog
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_popup))
            contentView = content
        }

        val recycler = content.findViewById<RecyclerView>(R.id.mediaList)
        val empty = content.findViewById<View>(R.id.emptyView)
        val header = content.findViewById<TextView>(R.id.sheetTitle)
        val loading = content.findViewById<View>(R.id.mediaLoading)
        header.text = getString(R.string.detected_media, items.size)

        if (items.isEmpty()) {
            recycler.visibility = View.GONE
            empty.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            empty.visibility = View.GONE
            recycler.layoutManager = LinearLayoutManager(this)
            val pageUrl = currentTab.url
            val ua = currentUa
            lateinit var adapter: MediaAdapter
            adapter = MediaAdapter(
                items,
                onClick = { item ->
                    popup.dismiss()
                    showRenameDialog(item, pageUrl, ua)
                },
                onNeedThumb = { item ->
                    ThumbnailLoader.load(item.url, pageUrl, ua) { bmp ->
                        if (bmp != null) runOnUiThread {
                            item.thumbnail = bmp
                            adapter.updateThumb(item)
                        }
                    }
                }
            )
            recycler.adapter = adapter

            // Show a spinner while sizes are still resolving, refreshing as they land.
            val handler = Handler(Looper.getMainLooper())
            val poll = object : Runnable {
                override fun run() {
                    adapter.notifyDataSetChanged()
                    if (currentTab.sniffer.hasPendingSizes()) {
                        loading.visibility = View.VISIBLE
                        handler.postDelayed(this, 400)
                    } else {
                        loading.visibility = View.GONE
                    }
                }
            }
            handler.post(poll)
            popup.setOnDismissListener { handler.removeCallbacks(poll) }
        }

        // Place the popup fully above or below the FAB (whichever has more room),
        // so it never covers the button. Cap the height so long lists scroll.
        val margin = dp(8)
        val fab = binding.dlFab
        val fabLoc = IntArray(2); fab.getLocationInWindow(fabLoc)
        val rootLoc = IntArray(2); binding.root.getLocationInWindow(rootLoc)
        val rootTop = rootLoc[1]
        val rootBottom = rootLoc[1] + binding.root.height
        val rootLeft = rootLoc[0]
        val rootRight = rootLoc[0] + binding.root.width

        val spaceAbove = fabLoc[1] - rootTop - margin
        val spaceBelow = rootBottom - (fabLoc[1] + fab.height) - margin
        val showAbove = spaceAbove >= spaceBelow
        val avail = (if (showAbove) spaceAbove else spaceBelow)
            .coerceAtMost((resources.displayMetrics.heightPixels * 0.6f).toInt())
            .coerceAtLeast(dp(120))

        content.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.AT_MOST)
        )
        popup.height = content.measuredHeight

        val maxX = (rootRight - widthPx - margin).coerceAtLeast(rootLeft + margin)
        val x = (fabLoc[0] + fab.width - widthPx).coerceIn(rootLeft + margin, maxX)
        val y = if (showAbove) fabLoc[1] - popup.height - margin
        else fabLoc[1] + fab.height + margin
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
        dimBehind(popup)
    }

    /** Let the user rename the file, then start the download. */
    private fun showRenameDialog(item: MediaItem, pageUrl: String?, ua: String?) {
        val content = layoutInflater.inflate(R.layout.dialog_rename, null)
        val input = content.findViewById<android.widget.EditText>(R.id.renameInput)
        input.setText(DownloadHelper.defaultName(item))

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(content)
            .setPositiveButton(R.string.download_action) { _, _ ->
                if (item.isStream) {
                    Toast.makeText(this, R.string.stream_note, Toast.LENGTH_LONG).show()
                }
                DownloadHelper.enqueue(this, item, pageUrl, ua, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Dims the screen behind a popup window to draw attention to it. */
    private fun dimBehind(popup: PopupWindow) {
        try {
            var root: View = popup.contentView
            while (root.parent is View) root = root.parent as View
            val lp = root.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
            lp.dimAmount = 0.35f
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
                .updateViewLayout(root, lp)
        } catch (e: Exception) {
            // Best-effort emphasis; ignore if the internal view tree differs.
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        popup.menuInflater.inflate(R.menu.overflow_menu, popup.menu)
        forceMenuIcons(popup)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> { addTabAndSelect(createTab(homeUrl)); true }
                R.id.menu_refresh -> { currentTab.webView.reload(); true }
                R.id.menu_bookmarks -> { showBookmarks(); true }
                R.id.menu_downloads -> { showDownloadsDialog(); true }
                R.id.menu_history -> { showHistory(); true }
                R.id.menu_options -> { showOptions(); true }
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
        val empty = content.findViewById<View>(R.id.bookmarksEmpty)
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

    /** Force a PopupMenu to render item icons (hidden by default). */
    private fun forceMenuIcons(popup: PopupMenu) {
        runCatching {
            val field = popup.javaClass.getDeclaredField("mPopup")
            field.isAccessible = true
            val helper = field.get(popup)
            helper.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                .invoke(helper, true)
        }
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showOptions() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_options, null)
        sheet.setContentView(content)

        val langValue = content.findViewById<TextView>(R.id.optLanguageValue)
        val sizeValue = content.findViewById<TextView>(R.id.optMinSizeValue)
        val searchValue = content.findViewById<TextView>(R.id.optSearchValue)
        val adblockSwitch =
            content.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.optAdblockSwitch)
        val desktopSwitch =
            content.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.optDesktopSwitch)

        fun sizeLabel() =
            if (minSizeBytes <= 0) getString(R.string.size_off) else formatBytes(minSizeBytes)

        langValue.text = currentLanguageLabel()
        sizeValue.text = sizeLabel()
        searchValue.text = searchEngineLabel()
        adblockSwitch.isChecked = adBlockEnabled
        desktopSwitch.isChecked = desktopMode

        content.findViewById<View>(R.id.optLanguage).setOnClickListener {
            sheet.dismiss(); showLanguageDialog()
        }
        content.findViewById<View>(R.id.optMinSize).setOnClickListener {
            showMinSizeDialog { sizeValue.text = sizeLabel() }
        }
        content.findViewById<View>(R.id.optSearch).setOnClickListener {
            showSearchEngineDialog { searchValue.text = searchEngineLabel() }
        }
        content.findViewById<View>(R.id.optAdblock).setOnClickListener {
            adBlockEnabled = !adBlockEnabled
            adblockSwitch.isChecked = adBlockEnabled
            prefs().edit().putBoolean("adblock", adBlockEnabled).apply()
        }
        content.findViewById<View>(R.id.optDesktop).setOnClickListener {
            desktopMode = !desktopMode
            desktopSwitch.isChecked = desktopMode
            prefs().edit().putBoolean("desktop", desktopMode).apply()
            applyDesktopMode()
        }
        content.findViewById<View>(R.id.optClearData).setOnClickListener {
            clearBrowsingData()
            Toast.makeText(this, R.string.data_cleared, Toast.LENGTH_SHORT).show()
        }
        sheet.show()
    }

    private fun currentLanguageLabel(): String {
        val cur = AppCompatDelegate.getApplicationLocales()
        val loc = if (cur.isEmpty) null else cur[0]
        return when {
            loc == null -> getString(R.string.lang_system)
            loc.language != "zh" -> getString(R.string.lang_english)
            loc.script == "Hant" -> getString(R.string.lang_chinese_traditional)
            else -> getString(R.string.lang_chinese_simplified)
        }
    }

    private fun searchEngineLabel(): String = when (searchEngine) {
        "bing" -> "Bing"
        "ddg" -> "DuckDuckGo"
        else -> "Google"
    }

    private fun showMinSizeDialog(onChanged: () -> Unit) {
        val values = longArrayOf(
            0L, 100L * 1024, 500L * 1024, 1L * 1024 * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024
        )
        val labels = values
            .map { if (it <= 0) getString(R.string.size_off) else formatBytes(it) }
            .toTypedArray()
        val checked = values.indexOfFirst { it == minSizeBytes }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.opt_min_size)
            .setSingleChoiceItems(labels, checked) { d, which ->
                minSizeBytes = values[which]
                prefs().edit().putLong("min_size", minSizeBytes).apply()
                onChanged()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSearchEngineDialog(onChanged: () -> Unit) {
        val keys = arrayOf("google", "bing", "ddg")
        val labels = arrayOf("Google", "Bing", "DuckDuckGo")
        val checked = keys.indexOf(searchEngine).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.opt_search_engine)
            .setSingleChoiceItems(labels, checked) { d, which ->
                searchEngine = keys[which]
                prefs().edit().putString("search_engine", searchEngine).apply()
                onChanged()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyDesktopMode() {
        currentUa = if (desktopMode) DESKTOP_UA else mobileUa
        tabs.forEach { it.webView.settings.userAgentString = currentUa }
        currentTab.webView.reload()
    }

    private fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.webView.clearCache(true); it.webView.clearHistory() }
    }

    private fun showLanguageDialog() {
        val tags = arrayOf("", "en", "zh-Hans", "zh-Hant")
        val labels = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_english),
            getString(R.string.lang_chinese_simplified),
            getString(R.string.lang_chinese_traditional)
        )
        val current = AppCompatDelegate.getApplicationLocales()
        val loc = if (current.isEmpty) null else current[0]
        val currentTag = when {
            loc == null -> ""
            loc.language != "zh" -> loc.language
            loc.script == "Hant" -> "zh-Hant"
            else -> "zh-Hans"
        }
        val checked = tags.indexOf(currentTag).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val tag = tags[which]
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Bottom sheet that polls the download store and shows live progress for each download. */
    private fun showDownloadsDialog() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_downloads, null)
        sheet.setContentView(content)

        val recycler = content.findViewById<RecyclerView>(R.id.downloadsList)
        val empty = content.findViewById<View>(R.id.downloadsEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = DownloadProgressAdapter(
            emptyList(),
            onPause = { id -> Downloads.pause(id) },
            onResume = { id -> Downloads.resume(id) },
            onCancel = { id -> Downloads.cancel(id) }
        )
        recycler.adapter = adapter

        val handler = Handler(Looper.getMainLooper())
        val refresh = object : Runnable {
            override fun run() {
                // Only show in-flight downloads; finished ones (done/failed/
                // canceled) move to history.
                val statuses = Downloads.snapshot().filter {
                    it.state == Downloads.State.RUNNING ||
                        it.state == Downloads.State.PAUSED
                }
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

    private fun showHistory() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_history, null)
        sheet.setContentView(content)

        val recycler = content.findViewById<RecyclerView>(R.id.historyList)
        val empty = content.findViewById<View>(R.id.historyEmpty)
        val clear = content.findViewById<View>(R.id.btnClearHistory)
        val items = DownloadHistory.all(this)

        fun render(list: List<DownloadHistory.Entry>) {
            if (list.isEmpty()) {
                recycler.visibility = View.GONE
                empty.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                empty.visibility = View.GONE
                recycler.layoutManager = LinearLayoutManager(this)
                recycler.adapter = HistoryAdapter(list)
            }
        }
        render(items)
        clear.setOnClickListener {
            DownloadHistory.clear(this)
            render(emptyList())
        }
        sheet.show()
    }

    private fun updateBadge() {
        val count = currentTab.sniffer.countPassing(minSizeBytes)
        binding.downloadBadge.apply {
            visibility = if (count > 0) View.VISIBLE else View.GONE
            text = if (count > 99) "99+" else count.toString()
        }
    }

    /** Resolve a detected item's size in the background (drives the badge + filter). */
    private fun fetchSize(item: MediaItem, tab: Tab) {
        if (item.sizeBytes != MediaItem.SIZE_UNKNOWN) return
        // Direct files: only size the representative copy. Streams: analyze all,
        // so we can discover which playlist is the master.
        if (!item.isStream && !tab.sniffer.isRepresentative(item)) return
        item.sizeBytes = MediaItem.SIZE_FETCHING
        if (item.isStream) {
            HlsSize.analyze(item.url, tab.url, currentUa) { info ->
                item.isMaster = info.isMaster
                item.downloadUrl = info.variantUrl
                if (info.variants.isNotEmpty()) tab.sniffer.addVariants(info.variants)
                item.sizeBytes = if (info.size > 0) info.size else MediaItem.SIZE_UNKNOWN
                runOnUiThread { if (tab === currentTab) updateBadge() }
            }
        } else {
            SizeFetcher.fetch(item.url, tab.url, currentUa) { size ->
                item.sizeBytes = if (size > 0) size else MediaItem.SIZE_UNKNOWN
                runOnUiThread { if (tab === currentTab) updateBadge() }
            }
        }
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (currentTab.webView.canGoBack()) 1f else 0.4f
        binding.btnForward.alpha = if (currentTab.webView.canGoForward()) 1f else 0.4f
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

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusUpdater)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusUpdater)
        CookieManager.getInstance().flush()
    }

    /** Reflect active downloads (count + progress) in the address bar. */
    private fun updateDownloadStatus() {
        val count = Downloads.inProgressCount()
        if (count > 0) {
            binding.dlStatus.visibility = View.VISIBLE
            binding.dlCount.text = count.toString()
            binding.dlProgress.setProgressCompat(Downloads.totalProgressPercent().coerceIn(0, 100), true)
            if (binding.urlBar.paddingStart != dp(42)) {
                binding.urlBar.setPaddingRelative(
                    dp(42), binding.urlBar.paddingTop, binding.urlBar.paddingEnd, binding.urlBar.paddingBottom
                )
            }
        } else if (binding.dlStatus.visibility != View.GONE) {
            binding.dlStatus.visibility = View.GONE
            binding.urlBar.setPaddingRelative(
                dp(14), binding.urlBar.paddingTop, binding.urlBar.paddingEnd, binding.urlBar.paddingBottom
            )
        }
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
            val found = tab.sniffer.consider(url)
            if (found != null) {
                fetchSize(found, tab)
                if (tab === currentTab) runOnUiThread { updateBadge() }
            }
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
