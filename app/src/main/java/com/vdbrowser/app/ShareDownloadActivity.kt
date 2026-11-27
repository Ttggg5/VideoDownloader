package com.vdbrowser.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Share target ("Download video"): finds the videos on a shared page in the
 * background and starts the download, all in a sheet over the app the user is in.
 */
class ShareDownloadActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: PageScanner? = null
    private var sheet: BottomSheetDialog? = null
    private var sheetContent: View? = null
    private val items = mutableListOf<MediaItem>()
    private lateinit var adapter: MediaAdapter
    private var minSizeBytes = 0L
    private var scanning = true

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            if (scanning || scanner?.sniffer?.hasPendingSizes() == true) handler.postDelayed(this, 400)
        }
    }

    private val timeout = Runnable {
        scanning = false
        scanner?.stop()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_download)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val url = extractUrl(text)?.let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        if (url == null) {
            Toast.makeText(this, R.string.no_link_shared, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        minSizeBytes = getSharedPreferences("settings", MODE_PRIVATE).getLong("min_size", 0L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        showSheet(url)
        val s = PageScanner(findViewById<FrameLayout>(R.id.scanHost), url) { /* polled by refresh */ }
        scanner = s
        s.start()
        handler.post(refresh)
        handler.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }

    private fun showSheet(url: String) {
        val content = layoutInflater.inflate(R.layout.sheet_share_download, null)
        content.findViewById<TextView>(R.id.shareHost).text = Uri.parse(url).host ?: url

        val list = content.findViewById<RecyclerView>(R.id.shareList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = MediaAdapter(
            items,
            onClick = { item -> download(item, url) },
            onNeedThumb = { item ->
                ThumbnailLoader.load(item.url, url, scanner?.userAgent) { bmp ->
                    if (bmp != null) runOnUiThread {
                        item.thumbnail = bmp
                        adapter.updateThumb(item)
                    }
                }
            }
        )
        list.adapter = adapter

        content.findViewById<View>(R.id.shareOpenBrowser).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }

        sheetContent = content
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(content)
        dialog.setOnDismissListener { finish() }
        dialog.show()
        sheet = dialog
    }

    private fun render() {
        val content = sheetContent ?: return
        // Sizes resolve in place on the same items, so rebind every tick.
        items.clear()
        items.addAll(scanner?.sniffer?.snapshotPassing(minSizeBytes).orEmpty())
        adapter.notifyDataSetChanged()
        val count = content.findViewById<TextView>(R.id.shareCount)
        count.text = items.size.toString()
        count.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        content.findViewById<View>(R.id.shareScanning).visibility = if (scanning) View.VISIBLE else View.GONE
        content.findViewById<View>(R.id.shareEmpty).visibility =
            if (!scanning && items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun download(item: MediaItem, pageUrl: String) {
        DownloadHelper.enqueue(this, item, pageUrl, scanner?.userAgent)
        sheet?.dismiss()   // finishes the activity; the download keeps going in DownloadService
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scanner?.stop()
        scanner = null
        sheet?.setOnDismissListener(null)
        sheet?.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 25_000L
    }
}
