package com.vdbrowser.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar

/**
 * Full-screen settings, grouped into sections. Only writes the "settings" prefs;
 * the browser re-reads and applies them when it comes back to the front.
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var root: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        root = findViewById(R.id.settingsContent)

        // General
        row(R.id.optLanguage) { showLanguageDialog() }
        switch(R.id.optRestore, R.id.optRestoreSwitch, "restore_tabs", true)
        row(R.id.optSearch) { showSearchEngineDialog() }

        // Browsing
        switch(R.id.optAdblock, R.id.optAdblockSwitch, "adblock", true)
        switch(R.id.optDesktop, R.id.optDesktopSwitch, "desktop", false)
        row(R.id.optTextSize) { showTextSizeDialog() }

        // Downloads
        row(R.id.optMinSize) { showMinSizeDialog() }
        row(R.id.optFloating) { toggleFloatingButton() }

        // Privacy
        row(R.id.optClearData) { showClearDataDialog() }

        // About
        row(R.id.optUpdates) { checkForUpdates() }
    }

    override fun onResume() {
        super.onResume()
        refreshValues()   // e.g. back from Android's Accessibility settings
        Updater.resumePendingInstall(this)
    }

    private fun refreshValues() {
        text(R.id.optLanguageValue, languageLabel())
        text(R.id.optSearchValue, searchEngineLabel(prefs.getString("search_engine", "google") ?: "google"))
        text(R.id.optTextSizeValue, "${prefs.getInt("text_zoom", 100)}%")
        val min = prefs.getLong("min_size", 0L)
        text(R.id.optMinSizeValue, if (min <= 0) getString(R.string.size_off) else formatBytes(min))
        text(R.id.optUpdatesValue, getString(R.string.version_label, BuildConfig.VERSION_NAME))
        findViewById<MaterialSwitch>(R.id.optFloatingSwitch).isChecked = floatingOn()
    }

    private fun row(id: Int, onClick: () -> Unit) = findViewById<View>(id).setOnClickListener { onClick() }

    private fun text(id: Int, value: String) { findViewById<TextView>(id).text = value }

    private fun switch(rowId: Int, switchId: Int, key: String, default: Boolean) {
        val sw = findViewById<MaterialSwitch>(switchId)
        sw.isChecked = prefs.getBoolean(key, default)
        row(rowId) {
            sw.isChecked = !sw.isChecked
            prefs.edit().putBoolean(key, sw.isChecked).apply()
        }
    }

    private fun snack(res: Int) = Snackbar.make(root, res, Snackbar.LENGTH_SHORT).show()

    // ------------------------------------------------------------ General

    private fun languageLabel(): String {
        val cur = AppCompatDelegate.getApplicationLocales()
        val loc = if (cur.isEmpty) null else cur[0]
        return when {
            loc == null -> getString(R.string.lang_system)
            loc.language != "zh" -> getString(R.string.lang_english)
            loc.script == "Hant" -> getString(R.string.lang_chinese_traditional)
            else -> getString(R.string.lang_chinese_simplified)
        }
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(labels, tags.indexOf(currentTag).coerceAtLeast(0)) { dialog, which ->
                val tag = tags[which]
                dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun searchEngineLabel(key: String): String = when (key) {
        "bing" -> "Bing"
        "ddg" -> "DuckDuckGo"
        else -> "Google"
    }

    private fun showSearchEngineDialog() {
        val keys = arrayOf("google", "bing", "ddg")
        val current = prefs.getString("search_engine", "google")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opt_search_engine)
            .setSingleChoiceItems(keys.map { searchEngineLabel(it) }.toTypedArray(), keys.indexOf(current).coerceAtLeast(0)) { d, which ->
                prefs.edit().putString("search_engine", keys[which]).apply()
                refreshValues()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------- Browsing

    private fun showTextSizeDialog() {
        val density = resources.displayMetrics.density
        val start = prefs.getInt("text_zoom", 100)
        val preview = TextView(this).apply {
            text = getString(R.string.text_size_preview)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            textSize = 16f * start / 100f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val slider = Slider(this).apply {
            valueFrom = 50f
            valueTo = 200f
            stepSize = 10f
            value = start.coerceIn(50, 200).let { (it / 10) * 10 }.toFloat()
            setLabelFormatter { "${it.toInt()}%" }
            addOnChangeListener { _, v, _ -> preview.textSize = 16f * v / 100f }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
            addView(preview)
            addView(slider)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_size)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putInt("text_zoom", slider.value.toInt()).apply()
                refreshValues()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------- Downloads

    private fun showMinSizeDialog() {
        val values = longArrayOf(0L, 100L * 1024, 500L * 1024, 1L * 1024 * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024)
        val labels = values.map { if (it <= 0) getString(R.string.size_off) else formatBytes(it) }.toTypedArray()
        val checked = values.indexOfFirst { it == prefs.getLong("min_size", 0L) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opt_min_size)
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs.edit().putLong("min_size", values[which]).apply()
                refreshValues()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun floatingOn() =
        prefs.getBoolean(FloatingButtonService.PREF_ENABLED, false) && FloatingButtonService.isEnabled(this)

    private fun toggleFloatingButton() {
        if (floatingOn()) {
            prefs.edit().putBoolean(FloatingButtonService.PREF_ENABLED, false).apply()
        } else {
            prefs.edit().putBoolean(FloatingButtonService.PREF_ENABLED, true).apply()
            if (!FloatingButtonService.isEnabled(this)) {
                // Explain the Accessibility permission, then send the user to turn it on.
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.floating_button_title)
                    .setMessage(R.string.floating_button_explain)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        refreshValues()
    }

    // ------------------------------------------------------------ Privacy

    private fun showClearDataDialog() {
        val labels = arrayOf(
            getString(R.string.clear_history_option),
            getString(R.string.clear_cookies_option),
            getString(R.string.clear_cache_option)
        )
        val checked = booleanArrayOf(true, true, true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opt_clear_data)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.clear) { _, _ ->
                if (checked[0]) BrowsingHistory.clear(this)
                if (checked[1]) {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                }
                // The WebView cache is shared by the whole app, so any WebView can clear it.
                if (checked[2]) runCatching { WebView(this).apply { clearCache(true); destroy() } }
                snack(R.string.data_cleared)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------- About

    private fun checkForUpdates() {
        text(R.id.optUpdatesValue, getString(R.string.checking))
        Updater.check { result ->
            text(R.id.optUpdatesValue, getString(R.string.version_label, BuildConfig.VERSION_NAME))
            when (result) {
                is Updater.CheckResult.Available -> {
                    prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                    UpdatePrompt.show(this, result.update, root)
                }
                Updater.CheckResult.UpToDate -> {
                    prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                    snack(R.string.up_to_date)
                }
                Updater.CheckResult.Failed -> snack(R.string.update_check_failed)
            }
        }
    }
}
