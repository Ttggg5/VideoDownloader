package com.vdbrowser.app

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar

/** "Update available" dialog and the download-then-install flow, shared by the browser and Settings. */
object UpdatePrompt {

    fun show(activity: Activity, update: Updater.Update, snackHost: View) {
        val message = buildString {
            if (update.size > 0) append(activity.getString(R.string.update_size, formatBytes(update.size)))
            if (update.notes.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(update.notes)
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available, update.versionName))
            .setMessage(message.ifBlank { null })
            .setPositiveButton(R.string.update_now) { _, _ -> download(activity, update, snackHost) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun download(activity: Activity, update: Updater.Update, snackHost: View) {
        val density = activity.resources.displayMetrics.density
        val bar = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            max = 100
        }
        val box = FrameLayout(activity).apply {
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
            addView(bar)
        }
        var cancelled = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_downloading)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()

        Updater.download(
            activity, update,
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
                    file != null -> Updater.install(activity, file)
                    !cancelled -> Snackbar.make(snackHost, R.string.update_failed, Snackbar.LENGTH_LONG).show()
                }
            }
        )
    }
}
