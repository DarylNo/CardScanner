package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.CameraChoice
import io.github.darylno.cardscanner.core.server.DeviceApi
import io.github.darylno.cardscanner.ui.AppSettings

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
        /** Settings changed from outside: re-read and apply them (any thread; post to the UI). */
        fun applyRemoteSettings()
        /** The current upright frame as JPEG, or null. Called off the UI thread. */
        fun snapshotJpeg(): ByteArray?
    }

    @Volatile var screen: Screen? = null

    override fun read() = DeviceApi.DeviceState(
        auto = settings.auto, roi = settings.roi,
        torch = settings.torch, vibration = settings.vibration, highRes = settings.highRes,
        aeLock = settings.aeLock, checkMs = settings.checkMs, cameraId = settings.cameraId,
    )

    override fun cameras(): List<Pair<String, String>> =
        CameraChoice.offered(lenses()).map { it.id to CameraChoice.label(it) }

    override fun write(s: DeviceApi.DeviceState) {
        val before = read()
        if (before != s) io.github.darylno.cardscanner.core.DebugLog.global.i("device", "settings changed from the computer: " +
            listOfNotNull(
                ("mode → " + if (s.auto) "Tray" else "Tap to scan").takeIf { before.auto != s.auto },
                "area → ${s.roi?.encode() ?: "full frame"}".takeIf { before.roi != s.roi },
                "torch → ${s.torch}".takeIf { before.torch != s.torch },
                "high res → ${s.highRes}".takeIf { before.highRes != s.highRes },
                "exposure lock → ${s.aeLock}".takeIf { before.aeLock != s.aeLock },
                "vibration → ${s.vibration}".takeIf { before.vibration != s.vibration },
                "check → ${s.checkMs} ms".takeIf { before.checkMs != s.checkMs },
                "camera → ${s.cameraId?.let { "camera $it" } ?: "Automatic"}".takeIf { before.cameraId != s.cameraId },
            ).joinToString(", "))
        settings.auto = s.auto
        settings.roi = s.roi
        settings.torch = s.torch
        settings.vibration = s.vibration
        settings.highRes = s.highRes
        settings.aeLock = s.aeLock
        settings.checkMs = s.checkMs
        settings.cameraId = s.cameraId
        screen?.applyRemoteSettings()
    }

    override fun snapshot(): ByteArray? = screen?.snapshotJpeg()

    override fun cameraLive(): Boolean = screen != null
}
