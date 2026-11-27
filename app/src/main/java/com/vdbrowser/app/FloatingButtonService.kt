package com.vdbrowser.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Floating download button drawn over other apps. Tapping it reads the current
 * browser's address bar (nothing else) and opens the "Download video" picker for
 * that page; in apps without an address bar it falls back to a copied link.
 */
class FloatingButtonService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var removeTarget: View? = null
    /** True while one of this app's own screens (other than the share picker) is in front. */
    private var inOwnApp = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_ENABLED) updateBubble()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // Turned on straight from Android's Accessibility settings: show the button.
        if (!prefs.contains(PREF_ENABLED)) prefs.edit().putBoolean(PREF_ENABLED, true).apply()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        updateBubble()
        if (bubble != null) Toast.makeText(this, R.string.floating_button_on, Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPkg = event.packageName?.toString()
        // The front app is the active window's. Our bubble and toasts never take
        // focus, so their own window events must not count as "this app is open"
        // (that used to hide the bubble the moment it appeared).
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: eventPkg ?: return
        if (pkg == "com.android.systemui") return   // shade/recents: keep the current state
        if (pkg == packageName) {
            val cls = event.className?.toString()
            // Only our real activities decide; overlay/toast/dialog events keep the current state.
            if (eventPkg != packageName || cls == null || !cls.startsWith("$packageName.")) return
            inOwnApp = cls != ShareDownloadActivity::class.java.name
        } else {
            inOwnApp = false
        }
        updateBubble()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        removeBubble()
        hideRemoveTarget()
        super.onDestroy()
    }

    // ------------------------------------------------------------- Bubble

    /** Shown when enabled, except inside this app (which has its own download button). */
    private fun updateBubble() {
        val want = prefs.getBoolean(PREF_ENABLED, false) && !inOwnApp
        if (want && bubble == null) addBubble() else if (!want && bubble != null) removeBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(BUBBLE_DP)
        val frame = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_download)
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
            background = ContextCompat.getDrawable(context, R.drawable.bg_brand_gradient_oval)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(15)
            setPadding(pad, pad, pad, pad)
            elevation = dp(6).toFloat()
            contentDescription = getString(R.string.share_download_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineSpotShadowColor = ContextCompat.getColor(context, R.color.brand_glow)
                outlineAmbientShadowColor = ContextCompat.getColor(context, R.color.brand_glow)
            }
        }
        // Extra room around the circle so its shadow isn't cut off by the window.
        val margin = dp(8)
        frame.addView(button, FrameLayout.LayoutParams(size, size).apply { setMargins(margin, margin, margin, margin) })

        val params = overlayParams(size + margin * 2, size + margin * 2, touchable = true).apply {
            x = prefs.getInt("float_x", screenWidth() - width)
            y = prefs.getInt("float_y", screenHeight() / 3)
        }
        clampToScreen(params)
        attachDrag(frame, button, params)
        runCatching { wm.addView(frame, params) }.onSuccess {
            bubble = frame
            bubbleParams = params
            frame.scaleX = 0.5f; frame.scaleY = 0.5f; frame.alpha = 0f
            frame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(frame: View, button: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        val slop = dp(8)

        button.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragged = false
                    button.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragged && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragged = true
                        showRemoveTarget()
                    }
                    if (dragged) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(frame, params) }
                        val over = isOverRemoveTarget(e.rawX, e.rawY)
                        removeTarget?.animate()?.scaleX(if (over) 1.25f else 1f)?.scaleY(if (over) 1.25f else 1f)
                            ?.setDuration(120)?.start()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!dragged) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) onTap(button)
                    } else if (isOverRemoveTarget(e.rawX, e.rawY)) {
                        hideRemoveTarget()
                        prefs.edit().putBoolean(PREF_ENABLED, false).apply()   // listener removes it
                        Toast.makeText(this, R.string.floating_button_removed, Toast.LENGTH_LONG).show()
                    } else {
                        hideRemoveTarget()
                        snapToEdge(frame, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(frame: View, params: WindowManager.LayoutParams) {
        clampToScreen(params)
        val targetX = if (params.x + params.width / 2 < screenWidth() / 2) 0 else screenWidth() - params.width
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(frame, params) }
            }
            start()
        }
        prefs.edit().putInt("float_x", targetX).putInt("float_y", params.y).apply()
    }

    private fun clampToScreen(params: WindowManager.LayoutParams) {
        params.x = params.x.coerceIn(0, (screenWidth() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight() - params.height).coerceAtLeast(0))
    }

    // ------------------------------------------------------- Remove target

    private fun showRemoveTarget() {
        if (removeTarget != null) return
        val size = dp(64)
        val target = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
            background = ContextCompat.getDrawable(context, R.drawable.bg_remove_target)
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.drop_to_hide)
            alpha = 0f
        }
        val params = overlayParams(size, size, touchable = false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        runCatching { wm.addView(target, params) }.onSuccess {
            removeTarget = target
            target.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun hideRemoveTarget() {
        removeTarget?.let { runCatching { wm.removeView(it) } }
        removeTarget = null
    }

    private fun isOverRemoveTarget(rawX: Float, rawY: Float): Boolean {
        val t = removeTarget ?: return false
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        val cx = loc[0] + t.width / 2f
        val cy = loc[1] + t.height / 2f
        val r = dp(64).toFloat()
        return (rawX - cx) * (rawX - cx) + (rawY - cy) * (rawY - cy) < r * r
    }

    // ---------------------------------------------------------------- Tap

    private fun onTap(button: View) {
        button.animate().scaleX(1.15f).scaleY(1.15f).setDuration(90)
            .withEndAction { button.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
            .start()
        val url = currentBrowserUrl()
        val intent = Intent(this, ShareDownloadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (url != null) {
            intent.action = Intent.ACTION_SEND
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, url)
        } else {
            intent.putExtra(ShareDownloadActivity.EXTRA_FROM_CLIPBOARD, true)
        }
        runCatching { startActivity(intent) }
    }

    /** The URL shown in the front browser's address bar, or null if it isn't a known browser. */
    private fun currentBrowserUrl(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val ids = BROWSER_URL_IDS[pkg] ?: return null
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull() ?: continue
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.contains(' ')) continue   // empty, or a search being typed
            return if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        }
        return null
    }

    // ------------------------------------------------------------ Helpers

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREF_ENABLED = "floating_button"
        private const val BUBBLE_DP = 56

        /** Address-bar view IDs of common browsers. */
        private val BROWSER_URL_IDS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.chrome.dev" to listOf("url_bar"),
            "com.chrome.canary" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.kiwibrowser.browser" to listOf("url_bar"),
            "com.vivaldi.browser" to listOf("url_bar"),
            "com.opera.browser" to listOf("url_field"),
            "com.opera.mini.native" to listOf("url_field"),
            "com.opera.gx" to listOf("url_field"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view"),
            "org.mozilla.focus" to listOf("display_url", "mozac_browser_toolbar_url_view"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "custom_tab_toolbar_url_bar_text"),
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput")
        )

        /** Whether the user has turned this service on in Android's Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = "${context.packageName}/${FloatingButtonService::class.java.name}"
            val short = "${context.packageName}/.FloatingButtonService"
            return enabled.split(':').any { it.equals(me, true) || it.equals(short, true) }
        }
    }
}
