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
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.color.MaterialColors
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private var barHidden = false
    private var scrollAccum = 0
    private val barInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private var badgeTab: Tab? = null
    private var badgeCount = 0
    private val incognitoProfileSupported by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }
    private var sharedCookieWarned = false
    /** Tabs closed but still undoable; destroyed when their snackbar ends. */
    private val pendingClosed = mutableListOf<Tab>()

    private var adBlockEnabled = true
    private var tabStripAdapter: TabStripAdapter? = null
    private var isWide = false

    // User options (persisted in the "settings" SharedPreferences).
    private var minSizeBytes = 0L
    private var desktopMode = false
    private var searchEngine = "google"
    private var textZoom = 100

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

    /**
     * Find the href of a long-pressed image link. requestFocusNodeHref reports
     * the *focused* node, which can be a link touched earlier, so its answer is
     * only trusted when its image src matches the pressed image; otherwise the
     * link is looked up at the touch point in the page.
     */
    private fun resolveImageLink(web: WebView, tab: Tab, imageSrc: String?) {
        val reply = Handler(Looper.getMainLooper()) { msg ->
            val href = msg.data?.getString("url")
            val src = msg.data?.getString("src")
            if (!href.isNullOrBlank() && !imageSrc.isNullOrBlank() && src == imageSrc) {
                showLinkMenu(href)
            } else {
                linkAtTouchPoint(web, tab) { found ->
                    val url = found ?: imageSrc
                    if (!url.isNullOrBlank()) showLinkMenu(url)
                }
            }
            true
        }
        web.requestFocusNodeHref(reply.obtainMessage())
    }

    private fun linkAtTouchPoint(web: WebView, tab: Tab, onResult: (String?) -> Unit) {
        @Suppress("DEPRECATION")
        val scale = web.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
        val x = tab.lastTouchX / scale
        val y = tab.lastTouchY / scale
        val js = "(function(){var e=document.elementFromPoint($x,$y);" +
            "var a=e&&e.closest?e.closest('a[href]'):null;return a?a.href:null;})()"
        web.evaluateJavascript(js) { result ->
            val href = runCatching { org.json.JSONTokener(result).nextValue() as? String }.getOrNull()
            onResult(href?.takeIf { it.isNotBlank() })
        }
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
        textZoom = prefs.getInt("text_zoom", 100)
        currentUa = if (desktopMode) DESKTOP_UA else mobileUa
        AdBlocker.init(applicationContext)

        isWide = resources.getBoolean(R.bool.is_wide)
        wipeIncognitoIfDone()   // leftovers if the app was killed with incognito tabs open
        requestNotificationPermissionIfNeeded()
        setupUi()
        setupBackNavigation()

        val shared = resolveInitialUrl(intent)
        val restored = prefs.getBoolean("restore_tabs", true) && restoreSession()
        when {
            shared != null -> addTabAndSelect(createTab(shared))
            !restored -> addTabAndSelect(createTab(homeUrl))
        }

        if (savedInstanceState == null) checkForUpdatesQuietly()
    }

    // ------------------------------------------------------------- Session

    /** Tabs worth reopening next time: real pages only (no blank start pages). */
    private fun sessionTabs(): List<Tab> =
        tabs.filter { !it.incognito && !it.showingStart && (it.pendingUrl ?: it.url).isNotBlank() }

    private fun saveSession() {
        if (tabs.isEmpty()) return
        TabGroups.prune(this, (tabs + pendingClosed).mapNotNullTo(HashSet()) { it.groupId })
        val keep = sessionTabs()
        Session.save(
            this,
            keep.map { Session.SavedTab(it.pendingUrl ?: it.url, it.title, it.groupId) },
            keep.indexOf(currentTab).coerceAtLeast(0)
        )
    }

    /**
     * Rebuild last session's tabs. Only the selected one loads now; the rest
     * load the first time they're opened, so startup stays quick.
     */
    private fun restoreSession(): Boolean {
        val saved = Session.load(this) ?: return false
        saved.tabs.forEach { s ->
            val tab = createTab(null)
            tab.url = s.url
            tab.title = s.title.ifBlank { s.url }
            tab.groupId = s.group
            tab.pendingUrl = s.url
            tabs.add(tab)
        }
        selectTab(saved.current)
        return true
    }

    // ------------------------------------------------------------- Updates

    /** At most once a day, look for a newer release and offer it. */
    private fun checkForUpdatesQuietly() {
        val now = System.currentTimeMillis()
        if (now - prefs().getLong("last_update_check", 0L) < UPDATE_CHECK_INTERVAL_MS) return
        Updater.check { result ->
            if (result !is Updater.CheckResult.Failed) {
                prefs().edit().putLong("last_update_check", now).apply()
            }
            if (result is Updater.CheckResult.Available && !isFinishing) showUpdateDialog(result.update)
        }
    }

    private fun showUpdateDialog(update: Updater.Update) {
        val message = buildString {
            if (update.size > 0) append(getString(R.string.update_size, formatBytes(update.size)))
            if (update.notes.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(update.notes)
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available, update.versionName))
            .setMessage(message.ifBlank { null })
            .setPositiveButton(R.string.update_now) { _, _ -> downloadUpdate(update) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun downloadUpdate(update: Updater.Update) {
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            max = 100
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(bar)
        }
        var cancelled = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_downloading)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()

        Updater.download(
            this, update,
            isCancelled = { cancelled },
            onProgress = { pct ->
                // Determinate mode can only be switched on, never back off, while visible.
                if (pct >= 0) {
                    if (bar.isIndeterminate) bar.isIndeterminate = false
                    bar.setProgressCompat(pct, true)
                }
            },
            onDone = { file ->
                if (dialog.isShowing) dialog.dismiss()
                when {
                    file != null -> Updater.install(this, file)
                    !cancelled -> snack(null, getString(R.string.update_failed))
                }
            }
        )
    }

    /** Manual check from Options; [status] shows progress and the current version. */
    private fun checkForUpdatesNow(host: View, status: TextView) {
        status.text = getString(R.string.checking)
        Updater.check { result ->
            status.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
            when (result) {
                is Updater.CheckResult.Available -> {
                    prefs().edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                    showUpdateDialog(result.update)
                }
                Updater.CheckResult.UpToDate -> {
                    prefs().edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                    snack(host, getString(R.string.up_to_date))
                }
                Updater.CheckResult.Failed -> snack(host, getString(R.string.update_check_failed))
            }
        }
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
    private fun createTab(url: String?, incognito: Boolean = false): Tab {
        val web = WebView(this)
        // The profile must be set before the WebView is used for anything.
        if (incognito && incognitoProfileSupported) {
            runCatching { WebViewCompat.setProfile(web, INCOGNITO_PROFILE) }
        }
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val tab = Tab(web)
        tab.incognito = incognito
        configureWebView(web, tab)
        if (incognito && !incognitoProfileSupported) web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
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

    /** A blank tab that shows the start page until it navigates somewhere. */
    private fun openNewTab(incognito: Boolean = false) {
        addTabAndSelect(createTab(null, incognito).also { it.showingStart = true })
        if (incognito && !incognitoProfileSupported && !sharedCookieWarned) {
            sharedCookieWarned = true
            snack(null, getString(R.string.incognito_shared_cookies))
        }
    }

    /** Once no incognito tab is left, delete its profile: cookies, storage and cache. */
    private fun wipeIncognitoIfDone() {
        if (!incognitoProfileSupported) return
        if (tabs.any { it.incognito } || pendingClosed.any { it.incognito }) return
        runCatching { ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE) }
    }

    /** Dark bar and address pill for incognito tabs, theme colors otherwise. */
    private fun applyChromeLook() {
        val inc = currentTab.incognito
        val on = if (inc) ContextCompat.getColor(this, R.color.incognito_on)
        else MaterialColors.getColor(binding.urlBar, com.google.android.material.R.attr.colorOnSurface)
        val onVar = if (inc) ContextCompat.getColor(this, R.color.incognito_on_variant)
        else MaterialColors.getColor(binding.urlBar, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val onVarList = android.content.res.ColorStateList.valueOf(onVar)

        binding.urlPill.setBackgroundResource(if (inc) R.drawable.bg_urlbar_incognito else R.drawable.bg_urlbar)
        binding.findBar.setBackgroundResource(if (inc) R.drawable.bg_urlbar_incognito else R.drawable.bg_urlbar)
        binding.urlBar.setTextColor(on)
        binding.urlBar.setHintTextColor(onVar)
        binding.findInput.setTextColor(on)
        binding.findInput.setHintTextColor(onVar)
        binding.findCount.setTextColor(onVar)
        binding.urlLeadingIcon.setImageResource(if (inc) R.drawable.ic_incognito else R.drawable.ic_search)
        listOf(binding.urlLeadingIcon, binding.btnBookmark, binding.findPrev, binding.findNext, binding.findClose)
            .forEach { androidx.core.widget.ImageViewCompat.setImageTintList(it, onVarList) }

        // On phones the nav buttons sit on the bar itself, so they follow it too.
        binding.bottomBar?.let { bar ->
            bar.setBackgroundResource(if (inc) R.drawable.bg_floating_bar_incognito else R.drawable.bg_floating_bar)
            listOf(binding.btnBack, binding.btnForward, binding.btnMenu)
                .forEach { androidx.core.widget.ImageViewCompat.setImageTintList(it, onVarList) }
            binding.tabCount.setTextColor(onVar)
            binding.tabCount.background?.mutate()?.setTint(onVar)
        }
    }

    private fun updateStartPage() {
        val page = binding.startPage
        val show = currentTab.showingStart
        page.root.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val inc = currentTab.incognito
        page.startHero.setBackgroundResource(if (inc) R.drawable.bg_hero_incognito else R.drawable.bg_hero)
        page.startHeroIcon.setImageResource(if (inc) R.drawable.ic_incognito else R.drawable.ic_download)
        page.startHeroTitle.setText(if (inc) R.string.incognito else R.string.app_name)
        page.startHeroSubtitle.visibility = if (inc) View.VISIBLE else View.GONE
        val items = SpeedDial.tiles(this, if (isWide) 12 else 8)
        page.startEmpty.visibility = View.GONE
        page.startTiles.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(this, if (isWide) 6 else 4)
        page.startTiles.adapter = StartTileAdapter(
            items,
            onOpen = { tile -> navigate(tile.url) },
            onLongPress = { tile, anchor -> showTileMenu(tile, anchor) },
            onAdd = { showSiteDialog(null) }
        )
        page.startTiles.scheduleLayoutAnimation()
    }

    private fun showTileMenu(tile: SpeedDial.Tile, anchor: View) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.edit)
        menu.menu.add(0, 2, 1, if (tile.pinned) R.string.unpin else R.string.pin)
        menu.menu.add(0, 3, 2, R.string.remove)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showSiteDialog(tile)
                2 -> {
                    if (tile.pinned) SpeedDial.unpin(this, tile.url) else SpeedDial.pin(this, tile.title, tile.url)
                    updateStartPage()
                }
                3 -> {
                    // Remove = gone from the dial: unpin it and stop suggesting the site.
                    val index = if (tile.pinned) SpeedDial.unpin(this, tile.url) else -1
                    SpeedDial.hide(this, tile.url)
                    updateStartPage()
                    snack(null, getString(R.string.site_removed), R.string.undo) {
                        if (tile.pinned) SpeedDial.pin(this, tile.title, tile.url, index)
                        else SpeedDial.unhide(this, SpeedDial.host(tile.url))
                        updateStartPage()
                    }
                }
            }
            true
        }
        menu.show()
    }

    /** Add a site to the speed dial, or edit an existing tile (which pins it). */
    private fun showSiteDialog(tile: SpeedDial.Tile?) {
        val content = layoutInflater.inflate(R.layout.dialog_site, null)
        val name = content.findViewById<android.widget.EditText>(R.id.siteName)
        val address = content.findViewById<android.widget.EditText>(R.id.siteUrl)
        tile?.let { name.setText(it.title); address.setText(it.url) }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (tile == null) R.string.add_site else R.string.edit_site)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                val raw = address.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                val title = name.text.toString().trim()
                if (tile != null && tile.pinned) SpeedDial.update(this, tile.url, title.ifBlank { SpeedDial.host(url) }, url)
                else SpeedDial.pin(this, title, url)
                updateStartPage()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.firstOrNull { it.webView.parent === binding.webContainer }?.let { capturePreview(it) }
        currentIndex = index
        currentTab.pendingUrl?.let { url ->
            currentTab.pendingUrl = null
            currentTab.webView.loadUrl(url)
        }
        val web = currentTab.webView
        (web.parent as? ViewGroup)?.removeView(web)
        binding.webContainer.removeAllViews()
        binding.webContainer.addView(web)

        if (!binding.urlBar.hasFocus()) binding.urlBar.setText(currentTab.url)
        updateBadge()
        updateNavButtons()
        updateTabCount()
        updateBookmarkIcon()
        updateStartPage()
        applyChromeLook()
        closeFindBar()
        scrollAccum = 0
        showBar()
    }

    /** Snapshot the visible part of a tab's page at reduced size for the tab grid. */
    private fun capturePreview(tab: Tab) {
        if (tab.showingStart) { tab.preview = null; return }
        val web = tab.webView
        if (web.width <= 0 || web.height <= 0 || !web.isAttachedToWindow) return
        val scale = 0.4f
        val w = (web.width * scale).toInt().coerceAtLeast(1)
        val h = (web.height * scale).toInt().coerceAtLeast(1)
        val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565) }.getOrNull() ?: return
        runCatching {
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
            web.draw(canvas)
            tab.preview = bmp
        }
    }

    /**
     * Close a tab with an Undo snackbar. The WebView is only destroyed once the
     * snackbar goes away without Undo, so restoring keeps the page and history.
     */
    private fun closeTab(index: Int, snackHost: View? = null, onRestored: () -> Unit = {}) {
        if (index !in tabs.indices) return
        val wasCurrent = index == currentIndex
        val tab = tabs.removeAt(index)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        pendingClosed.add(tab)

        if (tabs.isEmpty()) {
            openNewTab()
        } else {
            val target = if (index < currentIndex) currentIndex - 1
            else currentIndex.coerceAtMost(tabs.size - 1)
            selectTab(target)
        }

        snack(
            snackHost, getString(R.string.tab_closed), R.string.undo,
            onEnd = { undone ->
                if (pendingClosed.remove(tab) && !undone) {
                    tab.webView.destroy()
                    if (tab.incognito) wipeIncognitoIfDone()
                }
            }
        ) {
            val at = index.coerceAtMost(tabs.size)
            if (!wasCurrent && at <= currentIndex) currentIndex++
            tabs.add(at, tab)
            if (wasCurrent) selectTab(at) else updateTabCount()
            onRestored()
        }
    }

    /**
     * Show a snackbar on [host] (a sheet's content, so it appears above the
     * sheet) or on the main screen, kept clear of the floating bottom bar.
     */
    private fun snack(
        host: View?,
        text: CharSequence,
        actionRes: Int = 0,
        onEnd: ((undone: Boolean) -> Unit)? = null,
        action: (() -> Unit)? = null
    ) {
        val bar = Snackbar.make(host ?: binding.root, text, Snackbar.LENGTH_LONG)
        if (host == null) binding.bottomBar?.let { if (!barHidden) bar.anchorView = it }
        if (actionRes != 0 && action != null) bar.setAction(actionRes) { action() }
        if (onEnd != null) {
            bar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    onEnd(event == DISMISS_EVENT_ACTION)
                }
            })
        }
        bar.show()
    }

    private fun updateTabCount() {
        binding.tabCount.text = tabs.size.toString()
        tabStripAdapter?.notifyDataSetChanged()
        binding.tabStrip?.let { strip -> strip.post { strip.scrollToPosition(currentIndex) } }
    }

    /** Open a URL in a new tab without leaving the current one. */
    private fun openInBackgroundTab(url: String) {
        val tab = createTab(url)
        tabs.add(tab)
        updateTabCount()
        snack(null, getString(R.string.opened_in_new_tab), R.string.switch_to_tab) {
            val i = tabs.indexOf(tab)
            if (i >= 0) selectTab(i)
        }
    }

    private fun showLinkMenu(url: String) {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_link, null)
        sheet.setContentView(content)

        content.findViewById<TextView>(R.id.linkUrl).text = url
        // javascript:, mailto:, #... links can't be loaded in a fresh tab.
        val isWeb = url.startsWith("http://", true) || url.startsWith("https://", true)
        content.findViewById<View>(R.id.linkOpenNewTab).visibility = if (isWeb) View.VISIBLE else View.GONE
        content.findViewById<View>(R.id.linkOpenNewTab).setOnClickListener {
            openInBackgroundTab(url)
            sheet.dismiss()
        }
        content.findViewById<View>(R.id.linkCopy).setOnClickListener {
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("url", url))
            snack(null, getString(R.string.link_copied))
            sheet.dismiss()
        }
        sheet.show()
    }

    /** Refresh the on-top tab strip (e.g. after a title or URL changes). */
    private fun refreshTabStrip() {
        tabStripAdapter?.notifyDataSetChanged()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
            textZoom = this@MainActivity.textZoom
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(JsBridge(tab), "VDBridge")
        web.setOnScrollChangeListener { _, _, y, _, oldY -> onPageScrolled(tab, y, oldY) }

        web.setFindListener { active, count, done ->
            if (tab === currentTab && done) {
                binding.findCount.text = if (count == 0) "0/0" else "${active + 1}/$count"
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (tab === currentTab) closeFindBar()
                tab.sniffer.clear()
                tab.url = url ?: ""
                tab.favicon = null
                if (tab.showingStart) {
                    tab.showingStart = false
                    if (tab === currentTab) updateStartPage()
                }
                if (tab === currentTab) {
                    showBar()
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
                if (!tab.incognito) {
                    BrowsingHistory.record(this@MainActivity, view?.title ?: tab.title, tab.url)
                }
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

        // Remember where the finger went down, for resolving image links below.
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                tab.lastTouchX = event.x
                tab.lastTouchY = event.y
            }
            false
        }

        // Long-press a link to open it in a new tab or copy it.
        web.setOnLongClickListener {
            val hit = web.hitTestResult
            val extra = hit.extra
            when (hit.type) {
                // extra is the href of the link under this exact press.
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    if (!extra.isNullOrBlank()) showLinkMenu(extra)
                    true
                }
                // extra is the image's src; the link around it must be looked up.
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    resolveImageLink(web, tab, extra)
                    true
                }
                else -> false
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
        barHidden = false
        scrollAccum = 0
        binding.bottomBar?.clipToOutline = true
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showBar()
                localSuggestionSource = null   // re-read history/bookmarks for this edit
            } else {
                hideSuggestions()
            }
        }
        setupSuggestions()
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
        binding.dlStatus.setOnClickListener { openDownloadsPage() }
        setupFindBar()
        binding.startPage.startSearch.setOnClickListener {
            binding.urlBar.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.urlBar, InputMethodManager.SHOW_IMPLICIT)
        }
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
            openNewTab()
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
                    binding.findBar.visibility == View.VISIBLE -> closeFindBar()
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

        fun pressScale(v: View, s: Float) =
            v.animate().scaleX(s).scaleY(s).setDuration(110).start()

        binding.btnDownloads.setOnTouchListener { v, event ->
            val parent = container.parent as View
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressScale(v, 0.9f)
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
                    pressScale(v, 1f)
                    if (!dragged) showMediaSheet()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressScale(v, 1f)
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

    // --------------------------------------------- Address-bar suggestions

    private val suggestHandler = Handler(Looper.getMainLooper())
    private var suggestRunnable: Runnable? = null
    private var latestSuggestQuery = ""
    private var localSuggestionSource: List<Suggestion>? = null
    private lateinit var suggestionAdapter: SuggestionAdapter

    private fun setupSuggestions() {
        suggestionAdapter = SuggestionAdapter(
            onPick = { s -> navigate(s.target) },
            onFill = { text ->
                binding.urlBar.setText(text)
                binding.urlBar.setSelection(text.length)
            }
        )
        // On phones the list grows upward from the bar, best match closest to it.
        binding.suggestionList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, binding.bottomBar != null)
        binding.suggestionList.adapter = suggestionAdapter
        binding.urlBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (!binding.urlBar.hasFocus()) return
                scheduleSuggestions(e?.toString().orEmpty().trim())
            }
        })
    }

    private fun scheduleSuggestions(query: String) {
        suggestRunnable?.let { suggestHandler.removeCallbacks(it) }
        latestSuggestQuery = query
        if (query.isEmpty() || query == currentTab.url) { hideSuggestions(); return }
        val r = Runnable { loadSuggestions(query) }
        suggestRunnable = r
        suggestHandler.postDelayed(r, 150)
    }

    private fun loadSuggestions(query: String) {
        val source = localSuggestionSource ?: buildLocalSuggestionSource().also { localSuggestionSource = it }
        val local = source.asSequence()
            .filter { it.title.contains(query, true) || it.target.contains(query, true) }
            .distinctBy { it.target }
            .take(4)
            .toList()
        showSuggestions(local)
        if (currentTab.incognito) return   // don't send incognito typing to the search engine
        SearchSuggest.fetch(searchEngine, query) { remote ->
            if (query != latestSuggestQuery || !binding.urlBar.hasFocus()) return@fetch
            showSuggestions(local + remote.take(5).map { Suggestion(Suggestion.Kind.SEARCH, it, it) })
        }
    }

    private fun buildLocalSuggestionSource(): List<Suggestion> =
        Bookmarks.all(this).map { Suggestion(Suggestion.Kind.BOOKMARK, it.title.ifBlank { it.url }, it.url) } +
            BrowsingHistory.all(this).map { Suggestion(Suggestion.Kind.HISTORY, it.title.ifBlank { it.url }, it.url) }

    private fun showSuggestions(list: List<Suggestion>) {
        if (list.isEmpty() || !binding.urlBar.hasFocus()) { hideSuggestions(); return }
        suggestionAdapter.submit(list)
        binding.suggestionPanel.visibility = View.VISIBLE
        // Keep the card inside the space between the screen top and the bar.
        binding.suggestionPanel.post {
            val parent = binding.suggestionPanel.parent as? View ?: return@post
            val lp = binding.suggestionPanel.layoutParams as ViewGroup.MarginLayoutParams
            val room = parent.height - lp.topMargin - lp.bottomMargin - dp(16)
            val rv = binding.suggestionList
            rv.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(binding.suggestionPanel.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            if (room > 0 && rv.measuredHeight > room) rv.layoutParams.height = room
            rv.requestLayout()
        }
    }

    private fun hideSuggestions() {
        suggestRunnable?.let { suggestHandler.removeCallbacks(it) }
        binding.suggestionPanel.visibility = View.GONE
    }

    private fun navigate(input: String) {
        val raw = input.trim()
        if (raw.isEmpty()) return
        if (currentTab.showingStart) {
            currentTab.showingStart = false
            updateStartPage()
        }
        currentTab.webView.loadUrl(resolveInput(raw))
        hideKeyboard()
        binding.urlBar.clearFocus()
    }

    /** Turn raw address-bar (or shared) text into a loadable URL: as-is if it's
     *  already a URL, schemed if it looks like a bare domain, else a search. */
    private fun resolveInput(raw: String): String {
        val looksLikeUrl = raw.contains(".") && !raw.contains(" ")
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            looksLikeUrl -> "https://$raw"
            else -> searchUrl(raw)
        }
    }

    /** The URL to open for a launch/share intent, or null if it carries none. */
    private fun resolveInitialUrl(intent: Intent?): String? {
        intent ?: return null
        intent.dataString?.let { return it }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (text.isEmpty()) return null
            // A share can carry a bare link or a sentence with a link buried in it
            // ("check this out: https://..."); pull just the URL out when present,
            // otherwise treat the whole text as a search query.
            return resolveInput(extractUrl(text) ?: text)
        }
        return null
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
        view?.evaluateJavascript(MediaScanJs.SCRIPT, null)
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
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_popup))
            contentView = content
        }

        val recycler = content.findViewById<RecyclerView>(R.id.mediaList)
        val empty = content.findViewById<View>(R.id.emptyView)
        val header = content.findViewById<TextView>(R.id.sheetTitle)
        val loading = content.findViewById<View>(R.id.mediaLoading)
        header.text = getString(R.string.detected_media_title)
        content.findViewById<TextView>(R.id.mediaCount).text = items.size.toString()

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
        popup.animationStyle = if (showAbove) R.style.Animation_VD_PopupUp else R.style.Animation_VD_PopupDown
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

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(content)
            .setPositiveButton(R.string.download_action) { _, _ ->
                if (item.isStream) {
                    snack(null, getString(R.string.stream_note))
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

        capturePreview(currentTab)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        val recycler = content.findViewById<RecyclerView>(R.id.tabList)
        val header = content.findViewById<TextView>(R.id.tabsTitle)
        val back = content.findViewById<View>(R.id.btnGroupBack)
        val rename = content.findViewById<View>(R.id.btnGroupRename)
        val ungroup = content.findViewById<View>(R.id.btnGroupUngroup)
        val newIncognito = content.findViewById<View>(R.id.btnNewIncognito)
        recycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, if (isWide) 4 else 2)

        // null = all tabs (groups collapsed into one card); otherwise one group's tabs.
        var viewingGroup: String? = null
        lateinit var adapter: TabAdapter

        fun refresh() {
            val group = TabGroups.get(this, viewingGroup)?.takeIf { g -> tabs.any { it.groupId == g.id } }
            if (group == null) viewingGroup = null
            header.text = if (group != null) "${group.name} (${tabs.count { it.groupId == group.id }})"
            else getString(R.string.tabs_title, tabs.size)
            header.setTextColor(
                group?.color ?: MaterialColors.getColor(header, com.google.android.material.R.attr.colorOnSurface)
            )
            val inGroup = if (group != null) View.VISIBLE else View.GONE
            back.visibility = inGroup
            rename.visibility = inGroup
            ungroup.visibility = inGroup
            newIncognito.visibility = if (group == null) View.VISIBLE else View.GONE
            adapter.refresh()
        }
        fun close(i: Int) {
            closeTab(i, content) { refresh() }
            refresh()
        }
        adapter = TabAdapter(
            tabs = tabs,
            entries = { tabEntries(viewingGroup) },
            currentIndex = { currentIndex },
            onSelect = { i -> selectTab(i); sheet.dismiss() },
            onClose = { i -> close(i) },
            onLongPress = { i, anchor -> showTabCardMenu(i, anchor) { refresh() } },
            onOpenGroup = { id -> viewingGroup = id; refresh() }
        )
        recycler.adapter = adapter
        refresh()

        back.setOnClickListener { viewingGroup = null; refresh() }
        rename.setOnClickListener {
            val id = viewingGroup ?: return@setOnClickListener
            showGroupNameDialog(TabGroups.get(this, id), withColors = false) { name, _ ->
                TabGroups.rename(this, id, name)
                refresh()
                refreshTabStrip()
            }
        }
        ungroup.setOnClickListener {
            val id = viewingGroup ?: return@setOnClickListener
            tabs.filter { it.groupId == id }.forEach { it.groupId = null }
            viewingGroup = null
            refresh()
            updateTabCount()
        }

        // Swipe a tab card sideways to close it; it fades out as it slides.
        androidx.recyclerview.widget.ItemTouchHelper(object :
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                0,
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                    androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (adapter.entryAt(vh.bindingAdapterPosition) is TabEntry.Single) super.getSwipeDirs(rv, vh) else 0

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val entry = adapter.entryAt(vh.bindingAdapterPosition)
                if (entry is TabEntry.Single) close(entry.index) else refresh()
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val w = vh.itemView.width.coerceAtLeast(1)
                vh.itemView.alpha = 1f - (kotlin.math.abs(dX) / w).coerceIn(0f, 1f)
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.alpha = 1f
            }
        }).attachToRecyclerView(recycler)

        newIncognito.setOnClickListener {
            openNewTab(incognito = true)
            sheet.dismiss()
        }
        content.findViewById<View>(R.id.btnNewTab).setOnClickListener {
            val group = viewingGroup
            openNewTab()
            if (group != null) moveTabToGroup(tabs.size - 1, group)
            sheet.dismiss()
        }
        sheet.show()
    }

    // ---------------------------------------------------------- Tab groups

    /** Grid entries: one group's tabs, or every tab with each group collapsed into one card. */
    private fun tabEntries(groupId: String?): List<TabEntry> {
        if (groupId != null) {
            return tabs.indices.filter { tabs[it].groupId == groupId }.map { TabEntry.Single(it) }
        }
        val out = ArrayList<TabEntry>()
        val seen = HashSet<String>()
        tabs.forEachIndexed { i, tab ->
            val group = TabGroups.get(this, tab.groupId)
            when {
                group == null -> out.add(TabEntry.Single(i))
                seen.add(group.id) ->
                    out.add(TabEntry.Group(group, tabs.indices.filter { tabs[it].groupId == group.id }))
            }
        }
        return out
    }

    /** Put a tab in a group (kept next to the group's other tabs), or take it out (null). */
    private fun moveTabToGroup(index: Int, groupId: String?) {
        if (index !in tabs.indices) return
        val current = currentTab
        val tab = tabs[index]
        tab.groupId = groupId
        if (groupId != null) {
            tabs.removeAt(index)
            val last = tabs.indexOfLast { it.groupId == groupId }
            tabs.add(if (last >= 0) last + 1 else index.coerceAtMost(tabs.size), tab)
        }
        currentIndex = tabs.indexOf(current).coerceAtLeast(0)
        updateTabCount()
    }

    private fun showTabCardMenu(index: Int, anchor: View, onChanged: () -> Unit) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab.incognito) return
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        val groups = TabGroups.all(this).filter { it.id != tab.groupId && tabs.any { t -> t.groupId == it.id } }
        menu.menu.add(0, 1, 0, R.string.add_to_new_group)
        groups.forEachIndexed { i, g -> menu.menu.add(0, 100 + i, 1 + i, getString(R.string.move_to_group, g.name)) }
        if (tab.groupId != null) menu.menu.add(0, 2, 99, R.string.remove_from_group)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showGroupNameDialog(null, withColors = true) { name, color ->
                    val g = TabGroups.create(this, name, color)
                    moveTabToGroup(tabs.indexOf(tab), g.id)
                    onChanged()
                }
                2 -> { moveTabToGroup(tabs.indexOf(tab), null); onChanged() }
                else -> groups.getOrNull(item.itemId - 100)?.let {
                    moveTabToGroup(tabs.indexOf(tab), it.id)
                    onChanged()
                }
            }
            true
        }
        menu.show()
    }

    /** Name (and, for a new group, color) picker. */
    private fun showGroupNameDialog(
        existing: TabGroups.Group?,
        withColors: Boolean,
        onDone: (String, Int) -> Unit
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_rename, null) as android.widget.LinearLayout
        content.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.renameLayout)
            .hint = getString(R.string.group_name)
        val input = content.findViewById<android.widget.EditText>(R.id.renameInput)
        val used = TabGroups.all(this).size
        input.setText(existing?.name ?: "${getString(R.string.default_group_name)} ${used + 1}")

        var color = existing?.color ?: TabGroups.COLORS[used % TabGroups.COLORS.size]
        if (withColors) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, dp(4))
            }
            val ring = MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurface)
            val swatches = TabGroups.COLORS.map { c ->
                View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
                    contentDescription = null
                }
            }
            fun paint() = swatches.forEachIndexed { i, v ->
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(TabGroups.COLORS[i])
                    if (TabGroups.COLORS[i] == color) setStroke(dp(3), ring)
                }
            }
            swatches.forEachIndexed { i, v ->
                v.setOnClickListener { color = TabGroups.COLORS[i]; paint() }
                row.addView(v)
            }
            paint()
            content.addView(row)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.new_group else R.string.rename_group)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifBlank { getString(R.string.default_group_name) }
                onDone(name, color)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOverflowMenu() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_menu, null)
        sheet.setContentView(content)

        content.findViewById<android.widget.ImageView>(R.id.menuBookmarkAddIcon).setImageResource(
            if (Bookmarks.isBookmarked(this, currentTab.url)) R.drawable.ic_star else R.drawable.ic_star_border
        )

        fun action(id: Int, block: () -> Unit) {
            content.findViewById<View>(id).setOnClickListener { sheet.dismiss(); block() }
        }
        action(R.id.menuNewTab) { openNewTab() }
        action(R.id.menuNewIncognito) { openNewTab(incognito = true) }
        action(R.id.menuBookmarks) { showBookmarks() }
        action(R.id.menuHistoryPages) { showBrowsingHistory() }
        action(R.id.menuDownloads) { openDownloadsPage() }
        action(R.id.menuRefresh) { currentTab.webView.reload() }
        action(R.id.menuShare) { sharePage() }
        action(R.id.menuCopyLink) { copyPageLink() }
        action(R.id.menuFind) { openFindBar() }
        action(R.id.menuBookmarkAdd) { toggleBookmark(); updateBookmarkIcon() }
        action(R.id.menuHistory) { openDownloadsPage() }
        action(R.id.menuOptions) { showOptions() }
        sheet.show()
    }

    private fun openDownloadsPage() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    // ------------------------------------------------ Share / find / zoom

    private fun sharePage() {
        val url = currentTab.url
        if (url.isBlank()) return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, currentTab.title)
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, getString(R.string.share_page)))
    }

    private fun copyPageLink() {
        val url = currentTab.url
        if (url.isBlank()) return
        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("url", url))
        snack(null, getString(R.string.link_copied))
    }

    private fun setupFindBar() {
        binding.findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (binding.findBar.visibility != View.VISIBLE) return
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) {
                    currentTab.webView.clearMatches()
                    binding.findCount.text = ""
                } else {
                    currentTab.webView.findAllAsync(q)
                }
            }
        })
        binding.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentTab.webView.findNext(true); true
            } else false
        }
        binding.findPrev.setOnClickListener { currentTab.webView.findNext(false) }
        binding.findNext.setOnClickListener { currentTab.webView.findNext(true) }
        binding.findClose.setOnClickListener { closeFindBar() }
    }

    private fun openFindBar() {
        showBar()
        binding.urlPill.visibility = View.GONE
        binding.findBar.visibility = View.VISIBLE
        binding.findCount.text = ""
        binding.findInput.setText("")
        binding.findInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFindBar() {
        if (binding.findBar.visibility != View.VISIBLE) return
        binding.findBar.visibility = View.GONE
        binding.urlPill.visibility = View.VISIBLE
        tabs.forEach { it.webView.clearMatches() }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.findInput.windowToken, 0)
    }

    private fun showTextSizeDialog(onChanged: () -> Unit) {
        val preview = TextView(this).apply {
            text = getString(R.string.text_size_preview)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            textSize = 16f * textZoom / 100f
            setPadding(0, 0, 0, dp(12))
        }
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 50f
            valueTo = 200f
            stepSize = 10f
            value = textZoom.coerceIn(50, 200).let { (it / 10) * 10 }.toFloat()
            setLabelFormatter { "${it.toInt()}%" }
            addOnChangeListener { _, v, _ -> preview.textSize = 16f * v / 100f }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(preview)
            addView(slider)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_size)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                textZoom = slider.value.toInt()
                prefs().edit().putInt("text_zoom", textZoom).apply()
                tabs.forEach { it.webView.settings.textZoom = textZoom }
                onChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleBookmark() {
        val url = currentTab.url
        if (url.isBlank()) return
        val all = Bookmarks.all(this)
        val pos = all.indexOfFirst { it.url == url }
        if (pos >= 0) {
            val item = all[pos]
            Bookmarks.remove(this, url)
            snack(null, getString(R.string.bookmark_removed), R.string.undo) {
                Bookmarks.insert(this, pos, item)
                updateBookmarkIcon()
            }
        } else {
            Bookmarks.add(this, currentTab.title, url)
            snack(null, getString(R.string.bookmark_added))
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
            lateinit var adapter: BookmarkAdapter
            fun syncEmpty() {
                val none = adapter.itemCount == 0
                recycler.visibility = if (none) View.GONE else View.VISIBLE
                empty.visibility = if (none) View.VISIBLE else View.GONE
            }
            adapter = BookmarkAdapter(
                items,
                onOpen = { item -> currentTab.webView.loadUrl(item.url); sheet.dismiss() },
                onDelete = { pos, item ->
                    Bookmarks.remove(this, item.url)
                    updateBookmarkIcon()
                    syncEmpty()
                    snack(content, getString(R.string.bookmark_removed), R.string.undo) {
                        Bookmarks.insert(this, pos, item)
                        adapter.restore(pos, item)
                        updateBookmarkIcon()
                        syncEmpty()
                    }
                }
            )
            recycler.adapter = adapter
        }
        sheet.setOnDismissListener { updateStartPage() }
        sheet.show()
    }

    private fun showBrowsingHistory() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_browsing_history, null)
        sheet.setContentView(content)

        val recycler = content.findViewById<RecyclerView>(R.id.pageHistoryList)
        val empty = content.findViewById<View>(R.id.pageHistoryEmpty)
        val clear = content.findViewById<View>(R.id.btnClearPageHistory)
        val items = BrowsingHistory.all(this)

        fun render(list: MutableList<BrowsingHistory.Entry>) {
            if (list.isEmpty()) {
                recycler.visibility = View.GONE
                empty.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                empty.visibility = View.GONE
                recycler.layoutManager = LinearLayoutManager(this)
                recycler.adapter = BrowsingHistoryAdapter(
                    list,
                    onOpen = { entry -> currentTab.webView.loadUrl(entry.url); sheet.dismiss() },
                    onDelete = { entry -> BrowsingHistory.remove(this, entry.url) }
                )
            }
        }
        render(items)
        clear.setOnClickListener {
            BrowsingHistory.clear(this)
            render(mutableListOf())
        }
        sheet.show()
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
        val floatingSwitch =
            content.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.optFloatingSwitch)
        fun floatingOn() =
            prefs().getBoolean(FloatingButtonService.PREF_ENABLED, false) && FloatingButtonService.isEnabled(this)
        floatingSwitch.isChecked = floatingOn()
        content.findViewById<View>(R.id.optFloating).setOnClickListener {
            if (floatingOn()) {
                prefs().edit().putBoolean(FloatingButtonService.PREF_ENABLED, false).apply()
                floatingSwitch.isChecked = false
            } else {
                prefs().edit().putBoolean(FloatingButtonService.PREF_ENABLED, true).apply()
                if (FloatingButtonService.isEnabled(this)) {
                    floatingSwitch.isChecked = true
                } else {
                    sheet.dismiss()
                    showFloatingButtonSetup()
                }
            }
        }
        val restoreSwitch =
            content.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.optRestoreSwitch)
        restoreSwitch.isChecked = prefs().getBoolean("restore_tabs", true)
        content.findViewById<View>(R.id.optRestore).setOnClickListener {
            restoreSwitch.isChecked = !restoreSwitch.isChecked
            prefs().edit().putBoolean("restore_tabs", restoreSwitch.isChecked).apply()
        }
        val textSizeValue = content.findViewById<TextView>(R.id.optTextSizeValue)
        textSizeValue.text = "$textZoom%"
        content.findViewById<View>(R.id.optTextSize).setOnClickListener {
            showTextSizeDialog { textSizeValue.text = "$textZoom%" }
        }
        content.findViewById<View>(R.id.optClearData).setOnClickListener {
            clearBrowsingData()
            snack(content, getString(R.string.data_cleared))
        }
        val updatesValue = content.findViewById<TextView>(R.id.optUpdatesValue)
        updatesValue.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        content.findViewById<View>(R.id.optUpdates).setOnClickListener {
            checkForUpdatesNow(content, updatesValue)
        }
        sheet.show()
    }

    /** Explain the Accessibility permission, then send the user to turn it on. */
    private fun showFloatingButtonSetup() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.floating_button_title)
            .setMessage(R.string.floating_button_explain)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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

        MaterialAlertDialogBuilder(this)
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


    private fun updateBadge() {
        val count = currentTab.sniffer.countPassing(minSizeBytes)
        val grew = badgeTab === currentTab && count > badgeCount
        badgeTab = currentTab
        badgeCount = count
        binding.downloadBadge.apply {
            visibility = if (count > 0) View.VISIBLE else View.GONE
            text = if (count > 99) "99+" else count.toString()
            if (grew) {
                scaleX = 0.5f
                scaleY = 0.5f
                animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
        }
    }

    // ------------------------------------------------- Bottom bar auto-hide

    private fun onPageScrolled(tab: Tab, y: Int, oldY: Int) {
        if (tab !== currentTab || binding.bottomBar == null) return
        if (y <= 0) { scrollAccum = 0; showBar(); return }
        val dy = y - oldY
        if ((dy > 0) != (scrollAccum > 0)) scrollAccum = 0
        scrollAccum += dy
        if (scrollAccum > dp(24)) hideBar() else if (scrollAccum < -dp(24)) showBar()
    }

    private fun hideBar() {
        val bar = binding.bottomBar ?: return
        if (barHidden || binding.urlBar.hasFocus() || binding.findBar.visibility == View.VISIBLE) return
        barHidden = true
        val margin = (bar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        bar.animate().translationY((bar.height + margin + dp(12)).toFloat())
            .setDuration(220).setInterpolator(barInterpolator).start()
    }

    private fun showBar() {
        val bar = binding.bottomBar ?: return
        if (!barHidden) return
        barHidden = false
        bar.animate().translationY(0f).setDuration(220).setInterpolator(barInterpolator).start()
    }

    /** Resolve a detected item's size in the background (drives the badge + filter). */
    private fun fetchSize(item: MediaItem, tab: Tab) {
        MediaSizer.fetch(item, tab.sniffer, tab.url, currentUa) {
            runOnUiThread { if (tab === currentTab) updateBadge() }
        }
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (currentTab.webView.canGoBack()) 1f else 0.38f
        binding.btnForward.alpha = if (currentTab.webView.canGoForward()) 1f else 0.38f
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
        resolveInitialUrl(intent)?.let { addTabAndSelect(createTab(it)) }
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusUpdater)
        // Back from the "install unknown apps" setting: continue the update.
        Updater.resumePendingInstall(this)
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        statusHandler.removeCallbacks(statusUpdater)
        CookieManager.getInstance().flush()
    }

    /** Reflect active downloads (count + progress) in the address bar. */
    private fun updateDownloadStatus() {
        val count = Downloads.inProgressCount()
        if (count > 0) {
            val determinate = Downloads.allInProgressHaveKnownTotal()
            if (binding.dlStatus.visibility != View.VISIBLE) {
                // Material's indicator throws if switched to indeterminate while
                // visible, so this must be decided before it first becomes visible.
                binding.dlProgress.isIndeterminate = !determinate
                binding.dlStatus.visibility = View.VISIBLE
            }
            binding.dlCount.text = count.toString()
            if (determinate) {
                // Switching to determinate while visible is always safe.
                if (binding.dlProgress.isIndeterminate) binding.dlProgress.isIndeterminate = false
                binding.dlProgress.setProgressCompat(Downloads.totalProgressPercent().coerceIn(0, 100), true)
            }
            binding.urlLeadingIcon.visibility = View.GONE
        } else if (binding.dlStatus.visibility != View.GONE) {
            binding.dlStatus.visibility = View.GONE
            binding.urlLeadingIcon.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        pendingClosed.forEach { it.webView.destroy() }
        pendingClosed.clear()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        wipeIncognitoIfDone()
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
        private const val INCOGNITO_PROFILE = "incognito"
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
