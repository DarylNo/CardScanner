package io.github.darylno.cardscanner.ui

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.camera.CameraController
import io.github.darylno.cardscanner.camera.CaptureBurst
import io.github.darylno.cardscanner.camera.CaptureOutcome
import io.github.darylno.cardscanner.camera.CaptureTrigger
import io.github.darylno.cardscanner.camera.DetectionUpdate
import io.github.darylno.cardscanner.camera.ScanMode
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.RoiFrac

/**
 * [CameraPort] over the CAMERA agent's [CameraController] (which owns the
 * analysis thread, the frame ring, the AutoScanner and the capture pipeline).
 * Created per camera screen; [bind] constructs the controller (it needs the
 * LifecycleOwner) and replays the settings chosen before binding.
 */
class CameraAdapter(private val context: Context, private val settings: AppSettings) : CameraPort {
    private var controller: CameraController? = null
    private var handheld = false
    private var auto = true
    private var roi: RoiFrac? = null
    private var preview: PreviewView? = null

    override fun bind(owner: LifecycleOwner, preview: PreviewView, listener: CameraPort.Listener) {
        val c = CameraController(context, owner, object : CameraController.Listener {
            override fun onDetection(update: DetectionUpdate) {
                listener.onFrameSize(update.uprightW, update.uprightH)
                val dbg = "mask ${update.box?.let { "%.1f%%".format(it.maskFrac * 100) } ?: "—"}" +
                    " · ${update.event.javaClass.simpleName}" +
                    ((update.event as? io.github.darylno.cardscanner.core.AutoScanner.Event.Watching)
                        ?.let { " · steady ${it.stableCount}" } ?: "") +
                    "\n" + (controller?.pipeline?.lastSummary ?: "")
                listener.onDetection(update.event, update.grayW, update.grayH, dbg)
            }

            override fun onCaptureStarted(request: CaptureBurst) {
                listener.onFrameSize(request.uprightW, request.uprightH)
                listener.onCaptureStarted(request.trigger == CaptureTrigger.MANUAL)
            }

            override fun onCapture(outcome: CaptureOutcome) {
                val r = outcome.result
                if (r == null) {
                    listener.onCaptureFailed(outcome.error?.message ?: outcome.error?.toString() ?: "unknown error")
                    return
                }
                listener.onCaptured(
                    Captured(r.primary, r.fallbacks, r.flattened, r.summary(), if (outcome.mode == ScanMode.MOUNT) outcome.scene else null),
                    outcome.trigger == CaptureTrigger.MANUAL,
                )
            }

            override fun onCameraError(message: String, fatal: Boolean) {
                // Forward non-fatal errors too: "capture with no frames in the ring" and
                // analyzer exceptions mid-burst END a capture without any onCapture, so
                // dropping them left the status stuck on "Capturing…"/"Retrying — capturing…".
                listener.onCameraError(message)
            }

            override fun onCameraReady(summary: String) {
                CameraDiagnostics.last = controller?.diagnostics()
            }
        })
        controller = c
        this.preview = preview
        c.setScanMode(if (handheld) ScanMode.HANDHELD else ScanMode.MOUNT)
        c.setRoi(roi)
        c.setAuto(auto)
        c.setTorch(settings.torch)
        c.setAeAwbLock(settings.aeLock)
        c.start(preview, if (settings.highRes) CameraController.Resolution.HIGH else CameraController.Resolution.STANDARD)
    }

    /**
     * Re-bind ONLY if another screen (Setup's QR camera) unbound us. Rebinding
     * unconditionally on every resume re-learned the empty tray with the last
     * card still on it — lifting that card then read as a new one, a phantom
     * scan of the bare tray.
     */
    override fun rebind() {
        val v = preview ?: return
        val c = controller ?: return
        if (!c.isBound()) c.start(v)
    }

    override fun unbind() {
        controller?.release()
        controller = null
        preview = null
    }

    override fun setHandheld(handheld: Boolean) {
        this.handheld = handheld
        controller?.setScanMode(if (handheld) ScanMode.HANDHELD else ScanMode.MOUNT)
    }

    override fun setAuto(on: Boolean) {
        auto = on
        controller?.setAuto(on)
    }

    override fun setRoi(roi: RoiFrac?) {
        this.roi = roi
        controller?.setRoi(roi)
    }

    override fun relearn() { controller?.reset() }
    override fun pause(paused: Boolean) { controller?.setPaused(paused) }
    override fun manualScan() { controller?.manualScan() }
    override fun tapFocus(x: Float, y: Float) { controller?.tapToFocus(x, y) }
    override fun noCard(scene: Gray?) { controller?.onNoCard(scene) }
    override fun setTorch(on: Boolean) { controller?.setTorch(on) }
    override fun setAeLock(on: Boolean) { controller?.setAeAwbLock(on) }

    /**
     * The controller locks focus on the first card after every bind (Mount) and
     * exposes no switch to turn that off; "on" re-locks on the card in view now.
     */
    override fun setFocusLock(on: Boolean) {
        if (on && !handheld) controller?.refocus()
    }

    override fun setHighRes(high: Boolean) {
        controller?.setResolution(if (high) CameraController.Resolution.HIGH else CameraController.Resolution.STANDARD)
    }

    override fun diagnostics(): String = controller?.diagnostics() ?: "camera not bound"
    override fun snapshotJpeg(): ByteArray? = controller?.snapshotJpeg()
}
