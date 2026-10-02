package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.R

/** The debug report (diagnostics + the live log) through the share sheet; the clipboard when no app takes it. */
object DebugShare {
    fun share(activity: Activity, app: App) {
        val text = app.debugReport()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Card Scanner debug report")
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.settings_share_report)))
        } catch (_: Exception) {
            activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Card Scanner debug report", text))
            Toast.makeText(activity, activity.getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
    }
}
