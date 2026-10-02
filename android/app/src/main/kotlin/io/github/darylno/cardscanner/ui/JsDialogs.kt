package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import io.github.darylno.cardscanner.R

/**
 * The review page's own `confirm()` / `alert()` as native dialogs.
 *
 * An Android WebView shows NO JavaScript dialog unless a [WebChromeClient]
 * handles it: `confirm()` returns false without ever asking. phone.html asks
 * before every bulk delete ("Delete ALL n scans? This cannot be undone."), so
 * on the phone's own review screen "Clear all", "Clear unpicked" and "Delete
 * flagged" did nothing at all while the same page worked on the paired
 * computer (owner, 2026-10-01: "need a way to delete all scans").
 *
 * Every result is delivered exactly once — the dialog's buttons answer it, and
 * a dismissal (Back, a tap outside) answers "cancel".
 */
object JsDialogs {
    fun install(activity: Activity, web: WebView) {
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                confirm(activity, message ?: "") { ok -> if (ok) result.confirm() else result.cancel() }
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                alert(activity, message ?: "") { result.confirm() }
                return true
            }
        }
    }

    /** OK / Cancel; [onResult] is called once — false on Cancel, Back or a tap outside. Returns null (and answers false) when [activity] is going away. */
    fun confirm(activity: Activity, message: String, onResult: (Boolean) -> Unit): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) { onResult(false); return null }
        var answered = false
        fun answer(ok: Boolean) { if (!answered) { answered = true; onResult(ok) } }
        return AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> answer(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
    }

    /** OK only; [onDone] is called once however the dialog goes away. */
    fun alert(activity: Activity, message: String, onDone: () -> Unit): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) { onDone(); return null }
        var done = false
        fun finish() { if (!done) { done = true; onDone() } }
        return AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }
}
