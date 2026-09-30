package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.server.DeviceApi
import io.github.darylno.cardscanner.ui.AppSettings

/**
 * [DeviceApi]'s view of the phone: the app's [AppSettings] (the same values the
 * phone's own screens edit) plus the scan screen while it is open ([screen]),
 * so a change from the browser shows up on the live camera at once and the
 * browser can get a picture of the tray to draw the scan Area on.
 */
class DeviceBridge(private val settings: AppSettings) : DeviceApi.DeviceControl {

    /** The open scan screen. It applies changes on ITS UI thread and snapshots off it. */
    interface Screen {
        /** Settings changed from outside: re-read and apply them (any thread; post to the UI). */
        fun applyRemoteSettings()
        /** The current upright frame as JPEG, or null. Called off the UI thread. */
        fun snapshotJpeg(): ByteArray?
    }

    @Volatile var screen: Screen? = null

    override fun read() = DeviceApi.DeviceState(
        handheld = settings.mode == AppSettings.Mode.HANDHELD, auto = settings.auto, roi = settings.roi,
        torch = settings.torch, vibration = settings.vibration, highRes = settings.highRes,
        aeLock = settings.aeLock, checkMs = settings.checkMs,
    )

    override fun write(s: DeviceApi.DeviceState) {
        settings.mode = if (s.handheld) AppSettings.Mode.HANDHELD else AppSettings.Mode.MOUNT
        settings.auto = s.auto
        settings.roi = s.roi
        settings.torch = s.torch
        settings.vibration = s.vibration
        settings.highRes = s.highRes
        settings.aeLock = s.aeLock
        settings.checkMs = s.checkMs
        screen?.applyRemoteSettings()
    }

    override fun snapshot(): ByteArray? = screen?.snapshotJpeg()

    override fun cameraLive(): Boolean = screen != null
}
