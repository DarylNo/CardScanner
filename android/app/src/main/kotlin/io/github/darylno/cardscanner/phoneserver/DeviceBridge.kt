package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.CameraChoice
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.server.DeviceApi
import io.github.darylno.cardscanner.ui.AppSettings
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [DeviceApi]'s view of the phone: the app's [AppSettings] (the same values the
 * phone's own screens edit) plus the scan screen while it is open ([screen]),
 * so a change from the browser shows up on the live camera at once and the
 * browser can get a picture of the tray to draw the scan Area on.
 */
class DeviceBridge(
    private val settings: AppSettings,
    /** The phone's cameras (CameraCatalog in the app; a test passes its own). */
    private val lenses: () -> List<CameraChoice.Lens> = { emptyList() },
) : DeviceApi.DeviceControl {

    /** The open scan screen. It applies changes on ITS UI thread and snapshots off it. */
    interface Screen {
        /**
         * Settings changed from outside: re-read and apply them (any thread; post to the
         * UI). Returns the posted work, or null when it already ran — [write] waits for it
         * (≤ [APPLY_WAIT_MS]) so the PATCH answer reads the camera AFTER the change.
         */
        fun applyRemoteSettings(): java.util.concurrent.Future<*>?
        /** The current upright frame as JPEG, or null. Called off the UI thread. */
        fun snapshotJpeg(): ByteArray?
        /** The camera's zoom now (any thread), or null. */
        fun zoomState(): io.github.darylno.cardscanner.core.ZoomCoordinator.State? = null
    }

    @Volatile var screen: Screen? = null

    override fun read() = DeviceApi.DeviceState(
        auto = settings.auto, roi = settings.roi,
        torch = settings.torch, vibration = settings.vibration, highRes = settings.highRes,
        aeLock = settings.aeLock, checkMs = settings.checkMs, cameraId = settings.cameraId,
        zoomFit = settings.zoomFit,
    )

    override fun cameras(): List<Pair<String, String>> =
        CameraChoice.offered(lenses()).map { it.id to CameraChoice.label(it) }

    override fun write(s: DeviceApi.DeviceState) {
        val before = read()
        if (before != s) DebugLog.global.i("device", "settings changed from the computer: " +
            listOfNotNull(
                ("mode → " + if (s.auto) "Tray" else "Tap to scan").takeIf { before.auto != s.auto },
                "area → ${s.roi?.encode() ?: "full frame"}".takeIf { before.roi != s.roi },
                "torch → ${s.torch}".takeIf { before.torch != s.torch },
                "high res → ${s.highRes}".takeIf { before.highRes != s.highRes },
                "exposure lock → ${s.aeLock}".takeIf { before.aeLock != s.aeLock },
                "vibration → ${s.vibration}".takeIf { before.vibration != s.vibration },
                "check → ${s.checkMs} ms".takeIf { before.checkMs != s.checkMs },
                "camera → ${s.cameraId?.let { "camera $it" } ?: "Automatic"}".takeIf { before.cameraId != s.cameraId },
                "zoom to fit the Area → ${s.zoomFit}".takeIf { before.zoomFit != s.zoomFit },
            ).joinToString(", "))
        settings.auto = s.auto
        settings.roi = s.roi
        settings.torch = s.torch
        settings.vibration = s.vibration
        settings.highRes = s.highRes
        settings.aeLock = s.aeLock
        settings.checkMs = s.checkMs
        settings.cameraId = s.cameraId
        settings.zoomFit = s.zoomFit
        val applying = screen?.applyRemoteSettings() ?: return
        // DeviceApi answers the PATCH with state() right after this returns, and the zoom in it
        // is the camera's: wait for the scan screen's UI thread to have applied the change, or
        // the answer carried the zoom from BEFORE it (review of 1.1.11: "Zoom to fit is off"
        // right after ticking the box, until the panel's next poll). Bounded: a busy UI thread
        // only makes this one answer stale, never the setting.
        try {
            applying.get(APPLY_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            DebugLog.global.i("device", "the scan screen had not applied the change after $APPLY_WAIT_MS ms — this answer may show the zoom from before it")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            DebugLog.global.w("device", "applying the change on the scan screen: ${e.cause ?: e}")
        }
    }

    override fun snapshot(): ByteArray? = screen?.snapshotJpeg()

    override fun zoom() = screen?.zoomState()

    override fun cameraLive(): Boolean = screen != null

    companion object {
        /** Longest a PATCH waits for the scan screen to apply it before answering. */
        const val APPLY_WAIT_MS = 500L
    }
}
