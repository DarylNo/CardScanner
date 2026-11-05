package io.github.darylno.cardscanner.ui

import android.os.Build
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.RefocusPolicy
import io.github.darylno.cardscanner.core.ScreenDim
import java.util.Locale

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
            .append(" · check ").append(app.settings.checkMs).append(" ms")
            .append(" · zoom to fit ").append(if (app.settings.zoomFit) "on" else "off")
            .append(" · refocus on a card ").append(RefocusPolicy.label(app.settings.refocusMin).lowercase(Locale.ROOT)).append('\n')
        append("card list ").append(app.cardList.summary()).append('\n')
        append(zoomProbeLine(app.settings.zoomProbeLast)).append('\n')
        append("screen kept on · ").append(app.settings.dimAfterS.let { if (it <= 0) "never dims" else "dims after ${ScreenDim.label(it)} idle" })
            .append(" · phone brightness ")
            .append(runCatching { android.provider.Settings.System.getInt(app.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS).toString() }.getOrDefault("?"))
            .append('/').append(ScreenAwake.declaredMax()?.toString() ?: "?").append('\n')
        app.updates.latest?.let { append("latest release ").append(it.version).append(if (app.updates.locked) " — UPDATE REQUIRED" else "").append('\n') }
        append("queue pending ").append(app.uploads.pending).append("\n\n")
        // Live while the scan screen has its camera; else the last snapshot, with its age.
        append(CameraDiagnostics.current()).append("\n\n")
        append("recent captures (ms):\n").append(app.settings.recentTimings.ifBlank { "—" })
    }

    /**
     * The zoom probe's last verdict (1.1.17; AppSettings.zoomProbeLast, so it shows with the scan
     * screen closed): "zoom probe: 31 Oct 14:03 · camera 0 · 1600×1200 — <what the runs said>",
     * or "zoom probe: never run" (also for a record that does not read back).
     */
    fun zoomProbeLine(json: String?): String {
        val m = json?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() }
        val words = m?.get("words") as? String ?: return "zoom probe: never run"
        val at = (m["at"] as? Number)?.toLong()
        return "zoom probe: " + listOfNotNull(
            // Locale.US, as the app's other dates (review of a60e51d): on Android java.text's month names
            // come from ICU, whose ROOT locale has none in English — "31 M10 14:03" on the phone.
            at?.let { java.text.SimpleDateFormat("d MMM HH:mm", Locale.US).format(java.util.Date(it)) },
            (m["camera"] as? String)?.let { "camera $it" },
            m["stream"] as? String,
        ).joinToString(" · ") + " — " + words
    }
}
