package io.github.darylno.cardscanner.ui

import android.os.Build
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.BuildConfig

/** Settings → Diagnostics, and the head of the debug report: what the phone is and what state it's in. */
object DiagnosticsText {
    fun build(app: App): String = buildString {
        append("app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("server ").append(if (app.phoneServer.running) "serving on :${app.phoneServer.port}" else "stopped")
            .append(" · paired computers ").append(app.phoneServer.admins.count).append('\n')
        append("card database ").append(app.identify.store.installedManifest()?.let { "${it.rows} rows · ${it.buildDate}" } ?: "none")
            .append('\n')
        append("mode ").append(if (app.settings.auto) "Tray" else "Tap to scan")
            .append(" · area ").append(app.settings.roi?.encode() ?: "full frame")
            .append(" · check ").append(app.settings.checkMs).append(" ms\n")
        app.updates.latest?.let { append("latest release ").append(it.version).append(if (app.updates.locked) " — UPDATE REQUIRED" else "").append('\n') }
        append("queue pending ").append(app.uploads.pending).append("\n\n")
        // Live while the scan screen has its camera; else the last snapshot, with its age.
        append(CameraDiagnostics.current()).append("\n\n")
        append("recent captures (ms):\n").append(app.settings.recentTimings.ifBlank { "—" })
    }
}
