package com.vdbrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vdbrowser.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val sniffer = MediaSniffer()
    private var currentPageUrl: String? = null

    private val homeUrl = "https://www.google.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupUi()
        setupBackNavigation()

        val initial = intent?.dataString ?: homeUrl
        binding.webView.loadUrl(initial)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Present a desktop-ish UA without the "wv" tag so sites don't block the WebView.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(JsBridge(), "VDBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                sniffer.clear()
                currentPageUrl = url
                if (!binding.urlBar.hasFocus()) binding.urlBar.setText(url)
                updateBadge()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentPageUrl = url
                injectScanner(view)
                updateNavButtons()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Runs on a background thread; just record candidates and let the load proceed.
                request?.url?.toString()?.let { sniffer.consider(it) }
                runOnUiThread { updateBadge() }
                return null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        // Fires when the page itself triggers a file download (e.g. a download link).
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val type = name.substringAfterLast('.', "bin")
            DownloadHelper.enqueue(
                this,
                MediaItem(url, type, name, isStream = false),
                currentPageUrl,
                userAgent ?: web.settings.userAgentString
            )
        }
    }

    private fun setupUi() {
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(binding.urlBar.text.toString())
                true
            } else false
        }
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnRefresh.setOnClickListener { binding.webView.reload() }
        binding.btnDownloads.setOnClickListener { showMediaSheet() }

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
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
        binding.webView.loadUrl(url)
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
        val items = sniffer.snapshot()
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
            recycler.adapter = MediaAdapter(items) { item ->
                if (item.isStream) {
                    Toast.makeText(this, R.string.stream_note, Toast.LENGTH_LONG).show()
                }
                DownloadHelper.enqueue(
                    this, item, currentPageUrl, binding.webView.settings.userAgentString
                )
                sheet.dismiss()
            }
        }
        sheet.show()
    }

    private fun updateBadge() {
        val count = sniffer.count()
        binding.downloadBadge.apply {
            visibility = if (count > 0) View.VISIBLE else View.GONE
            text = if (count > 9) "9+" else count.toString()
        }
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (binding.webView.canGoBack()) 1f else 0.4f
        binding.btnForward.alpha = if (binding.webView.canGoForward()) 1f else 0.4f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { binding.webView.loadUrl(it) }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    /** Bridge that injected page JavaScript uses to report media URLs back to the app. */
    inner class JsBridge {
        @JavascriptInterface
        fun onMediaFound(url: String) {
            sniffer.consider(url)
            runOnUiThread { updateBadge() }
        }
    }
}
